// SPDX-License-Identifier: MIT
package dev.nymph.auto.vision

import android.content.Context
import dev.nymph.auto.core.log.RunLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * ncnn 轻量裁决模型的 JNI 门面（离线 HUD 级联 S4 支路）。
 *
 * 设计约束（全部对应方案的工程注意）：
 *  - **so 加载**：OpenCV / ncnn / libc++_shared 均为 vendored 预编译库，随 APK 走；
 *    `System.loadLibrary` 顺序为 libc++_shared → ncnn → ncnn-jni → opencv_java5。
 *  - **单线程独占**：Net 的创建、推理、关闭全部在 [executor] 单线程池上执行，绝不在主线程；
 *    一个 [CompiledModel]（此处为 ncnn::Net）常驻，buffer 复用。
 *  - **fail-closed 降级**：模型缺失 / 加载失败 / 推理异常时 [available]=false 或返回 null，
 *    调用方据此降级到纯 CV 主干（V1），功能不降级、精度略降。
 *  - **零网络**：模型来自 assets/ 或 filesDir/，不联网、不热更、不埋点。
 */
object NcnnNative : Closeable {

    /** JNI 实现在 app/src/main/cpp/ncnn_jni.cpp；包名/类名必须与 native 侧一致。 */
    private external fun nativeAvailable(): Boolean
    private external fun nativeLoad(paramPath: String, binPath: String, useVulkan: Int): Long
    private external fun nativeClassify(handle: Long, rgba: ByteArray, w: Int, h: Int): FloatArray?
    private external fun nativeClose(handle: Long)

    /** 单线程 executor：Net 实例的创建/推理/关闭严格串行，避免多线程并发访问同一 Net。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ncnn-inference").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** 输入边长（方案约定的 96×96 crop）。 */
    const val INPUT_SIZE = 96

    @Volatile private var loaded = false
    @Volatile private var handle = 0L
    @Volatile private var failed = false

    init {
        val ok = runCatching { nativeAvailable() }.getOrDefault(false)
        loaded = ok
        if (!ok) {
            RunLog.w("NcnnNative", "ncnn-jni 库不可用，S4 模型支路关闭（降级纯 CV 主干）")
        }
    }

    /** 模型支路是否可用。模型未加载时为 false——调用方必须据此降级，不得假设可用。 */
    val available: Boolean get() = loaded && handle != 0L && !failed

    /**
     * 加载模型。param/bin 路径由调用方给出（assets 需先解到 filesDir）。
     * 必须在 [dispatcher] 线程上调用；返回是否成功。
     */
    suspend fun load(paramPath: String, binPath: String, useVulkan: Boolean = false): Boolean =
        withContext(dispatcher) {
            if (!loaded) return@withContext false
            val fP = File(paramPath)
            val fB = File(binPath)
            if (!fP.isFile || !fB.isFile) {
                RunLog.i("NcnnNative", "模型文件缺失（$paramPath / $binPath），S4 支路关闭")
                failed = true
                return@withContext false
            }
            if (handle != 0L) nativeClose(handle)
            handle = runCatching { nativeLoad(paramPath, binPath, if (useVulkan) 1 else 0) }
                .onFailure { RunLog.w("NcnnNative", "nativeLoad 异常：${it.message}") }
                .getOrDefault(0L)
            failed = handle == 0L
            if (failed) RunLog.w("NcnnNative", "模型加载失败，降级纯 CV 主干")
            !failed
        }

    /**
     * 对 96×96 RGBA crop 分类，返回各类别置信度（按 labels.txt 顺序）。
     * 输入长度必须为 INPUT_SIZE*INPUT_SIZE*4，否则 fail-closed 返回 null。
     * 在 [dispatcher] 单线程上执行；buffer 由调用方复用。
     */
    suspend fun classify(rgba: ByteArray): FloatArray? = withContext(dispatcher) {
        if (!available || rgba.size != INPUT_SIZE * INPUT_SIZE * 4) {
            return@withContext null
        }
        runCatching { nativeClassify(handle, rgba, INPUT_SIZE, INPUT_SIZE) }
            .onFailure { RunLog.w("NcnnNative", "推理异常：${it.message}") }
            .getOrNull()
    }

    /** 关闭模型；在 [dispatcher] 线程执行，重复关闭 no-op。 */
    suspend fun closeModel() = withContext(dispatcher) {
        if (handle != 0L) {
            runCatching { nativeClose(handle) }
            handle = 0L
        }
        failed = false
    }

    override fun close() {
        // executor 关闭不放这里做（进程级单例，随进程结束）；只释放 native 句柄。
        if (handle != 0L) {
            runCatching { nativeClose(handle) }
            handle = 0L
        }
    }

    /** 供 NymphApp 启动时按序加载 so。libc++_shared 必须最先。 */
    fun loadLibraries() {
        val libs = listOf("c++_shared", "ncnn", "ncnn-jni", "opencv_java5")
        for (name in libs) {
            runCatching { System.loadLibrary(name) }
                .onFailure { RunLog.w("NcnnNative", "loadLibrary($name) 失败：${it.message}") }
        }
    }
}

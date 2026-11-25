// SPDX-License-Identifier: MIT
package dev.nymph.auto.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.nymph.auto.core.log.RunLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

/**
 * **争抢录屏权限取帧源**（第二种取帧模式，与 [AccessibilityScreenshotSource] 并存）。
 *
 * 与无障碍模式的本质区别：
 *  - 无障碍 `takeScreenshot()`：不创建 MediaProjection、不与录屏 App 抢权限，但 API 30+ 且
 *    部分 ROM 有黑帧/限频；这是**默认模式**。
 *  - MediaProjection：创建虚拟显示、独占录屏会话，帧率与画质更稳定，但**与任何录屏 App 互斥**，
 *    且 Android 14+ 每次前台服务启动都需用户重新授权——这是**用户显式选择的增强模式**。
 *
 * 两者实现同一个 [ScreenSource] 接口，`VisionPipeline` 不感知差异；由 [FrameSourceSelector]
 * 根据用户的模式选择 + 能力探测决定注入哪一个，失败自动降级回无障碍模式。
 *
 * 工程约束：
 *  - 取帧用 `ImageReader` 最新帧（acquireLatestImage），只保留最近一帧，丢弃积压，避免延迟累积；
 *  - 每帧立即 copy 成软件 Bitmap 并 close Image，杜绝硬件缓冲泄漏；
 *  - 真实 bounds 用 [WindowMetrics]（API 30+）而非已废弃的 DisplayMetrics 全局值。
 */
class MediaProjectionSource(
    private val context: Context,
    private val grantResultCode: Int,
    private val grantData: Intent,
) : ScreenSource, AutoCloseable {

    private val lock = Mutex()
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var lastAt = 0L
    @Volatile private var failed = false
    @Volatile private var lastError = ""

    /** 最近一次取帧失败是否因安全窗口（MediaProjection 下 secure 页同样黑屏）。 */
    @Volatile var lastFailureWasSecure = false
        private set

    override val available: Boolean
        get() = !failed && mediaProjection != null && imageReader != null

    override val unavailableReason: String
        get() = if (failed) lastError else "MediaProjection 未授权或已释放"

    override fun lastGrabAt(): Long = lastAt

    /** 建立投影会话。必须在拿到用户授权 resultCode/data 后调用，失败返回 false。 */
    fun start(): Boolean {
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(grantResultCode, grantData)
                ?: throw IllegalStateException("getMediaProjection 返回 null（授权无效或已过期）")
            mediaProjection = mp
            val (w, h, dpi) = realBounds()
            handlerThread = HandlerThread("mp-capture").apply { start() }
            val handler = Handler(handlerThread!!.looper)
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                // 只 drain 最新帧，丢弃积压：ImageReader 满时旧帧会导致延迟累积。
                val img = r.acquireLatestImage()
                img?.close()
            }, handler)
            imageReader = reader
            virtualDisplay = mp.createVirtualDisplay(
                "nymph-hud-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler,
            )
            RunLog.i("MediaProjection", "取帧会话就绪：${w}x${h} @${dpi}dpi（争抢录屏权限模式）")
            true
        } catch (t: Throwable) {
            failed = true
            lastError = "MediaProjection 建立失败：${t.message}"
            RunLog.w("MediaProjection", lastError)
            false
        }
    }

    /** 真实屏幕 bounds（WindowMetrics，API 30+）。 */
    private fun realBounds(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val b = m.bounds
            Triple(b.width(), b.height(), context.resources.configuration.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics().also { context.resources.displayMetrics }
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    override suspend fun grab(): Bitmap? = lock.withLock {
        val reader = imageReader ?: return@withLock null
        if (!available) return@withLock null
        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            failed = true
            lastError = "acquireLatestImage 失败：${t.message}"
            RunLog.w("MediaProjection", lastError)
            null
        }
        if (image == null) return@withLock null
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = plane.rowStride / plane.pixelStride
            val h = image.height
            // rowStride 可能大于像素宽（对齐填充），必须逐行拷贝，不能直接取 buffer。
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var srcOffset = 0
            val rowBytes = w * plane.pixelStride
            for (y in 0 until h) {
                buf.position(srcOffset)
                val row = ByteArray(rowBytes)
                buf.get(row)
                // RGBA_8888 → ARGB_8888：低字节是 R，高字节是 A。
                val argb = IntArray(w)
                for (x in 0 until w) {
                    val p = x * 4
                    val a = row[p + 3].toInt() and 0xFF
                    val r = row[p].toInt() and 0xFF
                    val g = row[p + 1].toInt() and 0xFF
                    val b = row[p + 2].toInt() and 0xFF
                    argb[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bmp.setPixels(argb, 0, w, 0, y, w, 1)
                srcOffset += plane.rowStride
            }
            lastAt = android.os.SystemClock.elapsedRealtime()
            bmp
        } catch (t: Throwable) {
            lastFailureWasSecure = true // MediaProjection 黑屏常见secure页
            RunLog.w("MediaProjection", "帧转换失败：${t.message}")
            null
        } finally {
            image.close()
        }
    }

    override fun close() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            handlerThread?.quitSafely()
        } catch (t: Throwable) {
            RunLog.w("MediaProjection", "释放失败：${t.message}")
        } finally {
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            handlerThread = null
        }
    }

    companion object {
        /** 请求用户授权：从 Activity  startActivityForResult(MEDIA_PROJECTION_SERVICE)。 */
        fun requestIntent(context: Context): Intent? {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return null
            return mpm.createScreenCaptureIntent()
        }
    }
}

/**
 * 取帧模式选择器：根据用户选择 + 能力探测决定注入哪个 [ScreenSource]，
 * 失败自动降级回无障碍模式（默认、不争抢录屏权限）。
 */
enum class FrameMode {
    /** 无障碍截图（默认）：不争抢录屏权限。 */
    ACCESSIBILITY,
    /** MediaProjection：争抢录屏权限，帧率画质更稳，Android 14+ 每次需重新授权。 */
    MEDIA_PROJECTION,
}

object FrameSourceSelector {
    /** 当前模式（进程内，重启回默认）。 */
    @Volatile var mode: FrameMode = FrameMode.ACCESSIBILITY

    /** MediaProjection 授权是否仍有效（Android 14+ 每次前台服务启动需重新确认）。 */
    @Volatile var mpAuthorized: Boolean = false

    /**
     * 解析实际使用的模式：用户选了 MediaProjection 但未授权 → 自动降级无障碍。
     * 这样即使授权过期也不会让取帧链断裂。
     */
    fun resolve(): FrameMode = when {
        mode == FrameMode.MEDIA_PROJECTION && mpAuthorized -> FrameMode.MEDIA_PROJECTION
        else -> FrameMode.ACCESSIBILITY
    }
}

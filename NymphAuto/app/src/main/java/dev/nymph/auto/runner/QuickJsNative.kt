package dev.nymph.auto.runner

import dev.nymph.auto.core.log.RunLog

/**
 * quickjs-ng JNI 绑定入口（native：app/src/main/cpp/quickjs_jni.c）。
 *
 * so 来源：cpp/ 下 vendored quickjs-ng（MIT）+ JNI 桥，用 NDK 交叉编译
 * （重建命令见 cpp/README-quickjs.md），产物置于
 * app/src/main/jniLibs/arm64-v8a/libquickjs-jni.so。
 *
 * 无 Android 依赖：加载失败只置 [available]=false，调用方降级内存引擎，
 * JVM 单元测试不会因 native 缺失而崩溃。
 */
object QuickJsNative {
    private const val TAG = "QuickJsNative"

    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("quickjs-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            RunLog.e(TAG, "libquickjs-jni.so 不可用：${e.message}")
            false
        }
    }

    /** 创建独立 JS 运行时；callback 需有 onCall(String, String): String 方法。 */
    external fun create(callback: Any): Long

    external fun destroy(ptr: Long)

    /** 执行 JS，返回结果 JSON；异常返回 {"__nymph_error__":"..."}；undefined 返回空串。 */
    external fun eval(ptr: Long, code: String): String

    external fun setVar(ptr: Long, name: String, valueJson: String)

    external fun setGlobal(ptr: Long, name: String, valueJson: String)

    /** 整体替换 globalThis.vars。 */
    external fun setVarsObject(ptr: Long, mapJson: String)

    /** 回读 globalThis.vars 的 JSON。 */
    external fun syncVars(ptr: Long): String

    external fun reset(ptr: Long)

    /** 指令级中断（停止按钮）。 */
    external fun interrupt(ptr: Long)
}

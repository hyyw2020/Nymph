package dev.nymph.auto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.log.LogStore

class NymphApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogStore.init(this) // 日志文件持久化（无网络，仅本机）
        // 离线视觉栈：按依赖顺序加载 vendored so（libc++_shared → ncnn → ncnn-jni → opencv_java5）。
        // 全部随 APK 走、零网络；失败由各门面 fail-closed 降级，不影响其余功能。
        dev.nymph.auto.vision.NcnnNative.loadLibraries()
        installCrashHandler()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_STATUS,
                "运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示当前脚本执行状态"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 全局未捕获异常处理：完整堆栈写入运行日志 + filesDir/logs/last_crash.txt，
     * 下次启动 MainActivity 会弹出「检测到上次崩溃」提示，日志页也有崩溃横幅。
     *
     * 链式调用原默认处理器（系统默认行为：结束进程并提示「应用已停止」），
     * 不吞崩溃、不改系统语义，只保证崩溃「有据可查」。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            // v2.9.38：冷启动即崩时用户进不去日志页，无法导出 last_crash.txt。
            // 崩溃时在独立进程拉起错误页直接显示堆栈，用户截图即可提供定位证据。
            // 全部 runCatching：诊断逻辑任何失败都不得干扰系统默认崩溃处理链。
            runCatching {
                RunLog.e("Crash", "未捕获异常：${ex.javaClass.name}: ${ex.message}")
                RunLog.e("Crash", ex.stackTraceToString())
                LogStore.writeCrash(thread.name, ex)

                // 错误页自身崩溃时不再拉起，避免 :crash 进程无限重启循环。
                if (!dev.nymph.auto.ui.CrashReportActivity.isCrashProcess(this)) {
                    val text = buildString {
                        appendLine("线程：${thread.name}")
                        appendLine("异常：${ex.javaClass.name}: ${ex.message}")
                        appendLine("Android API ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.MODEL}")
                        appendLine("---- 堆栈 ----")
                        append(ex.stackTraceToString())
                    }
                    dev.nymph.auto.ui.CrashReportActivity.launch(this, text)
                }
            }
            previous?.uncaughtException(thread, ex)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
        }
    }

    companion object {
        const val CHANNEL_STATUS = "status"
    }
}

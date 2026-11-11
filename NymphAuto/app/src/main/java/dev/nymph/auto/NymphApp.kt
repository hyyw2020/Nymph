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
            runCatching {
                RunLog.e("Crash", "未捕获异常：${ex.javaClass.name}: ${ex.message}")
                RunLog.e("Crash", ex.stackTraceToString())
                LogStore.writeCrash(thread.name, ex)
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

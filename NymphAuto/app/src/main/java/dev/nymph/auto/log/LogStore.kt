package dev.nymph.auto.log

import android.content.Context
import dev.nymph.auto.core.log.LogEntry
import dev.nymph.auto.core.log.RunLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志持久化：把 [RunLog] 的每条记录追加到 filesDir/logs/nymphauto.log。
 *
 * - 单文件上限 512KB，超限滚动为 .1（只保留一份历史），防止长跑写满存储；
 * - 未捕获崩溃的完整堆栈写入 filesDir/logs/last_crash.txt，
 *   下次启动可检测（[hasCrash]）并展示，保证「崩溃必有堆栈可查」；
 * - 无网络权限，导出仅支持应用内查看与系统分享（ACTION_SEND）；
 * - 在 [dev.nymph.auto.NymphApp.onCreate] 初始化一次。
 */
object LogStore {

    private const val MAX_BYTES = 512 * 1024L
    private var file: File? = null
    private var crashFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        file = File(dir, "nymphauto.log")
        crashFile = File(dir, "last_crash.txt")
        RunLog.addListener { entry -> append(entry) }
    }

    /**
     * v2.9.45：[RunLog] 在锁外通知监听器，append 可能被脚本线程、主线程、无障碍
     * 回调等并发进入。裸的 `length()` 检查 + rename 轮转 + appendText 会交错，
     * 造成写丢失、重复轮转或写入已改名的旧文件。@Synchronized 与 init/tail/clear
     * 共用实例锁，把「检查—轮转—追加」串行化。
     */
    @Synchronized
    private fun append(entry: LogEntry) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, f.name + ".1")
                old.delete()
                f.renameTo(old)
                f.createNewFile()
            }
            f.appendText(entry.format() + "\n")
        }
    }

    /** 读取最近 [lines] 行（供日志页展示）。与写入共用实例锁，避免读到轮转中间态。 */
    @Synchronized
    fun tail(lines: Int = 500): List<String> {
        val f = file ?: return RunLog.snapshot().map { it.format() }
        val all = runCatching { f.readLines() }.getOrDefault(emptyList())
        return if (all.size <= lines) all else all.takeLast(lines)
    }

    fun exportText(): String = tail(2000).joinToString("\n")

    @Synchronized
    fun clear() {
        RunLog.clear()
        runCatching { file?.writeText("") }
    }

    // ------------------------------------------------------------ 崩溃堆栈

    /**
     * 写入未捕获崩溃的完整堆栈（在崩溃线程调用，全部 runCatching 包裹，
     * 写盘失败也不能影响系统默认的崩溃处理链）。
     */
    fun writeCrash(thread: String, ex: Throwable): Boolean {
        val f = crashFile ?: return false
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val text = buildString {
            appendLine("时间：$time")
            appendLine("线程：$thread")
            appendLine("异常：${ex.javaClass.name}: ${ex.message}")
            appendLine("---- 完整堆栈 ----")
            append(ex.stackTraceToString())
            appendLine()
            appendLine("---- 设备信息 ----")
            appendLine("Android API ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.MODEL}")
        }
        return runCatching { f.writeText(text) }.isSuccess
    }

    fun hasCrash(): Boolean = runCatching { crashFile?.length() ?: 0L }.getOrDefault(0L) > 0

    fun readCrash(): String? = runCatching {
        crashFile?.takeIf { it.length() > 0 }?.readText()
    }.getOrNull()

    fun clearCrash() {
        runCatching { crashFile?.delete() }
    }
}

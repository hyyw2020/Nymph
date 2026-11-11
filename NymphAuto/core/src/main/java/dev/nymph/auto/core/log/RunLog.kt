package dev.nymph.auto.core.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日志级别。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 一条运行日志。[format] 输出单行可读文本（本地时区，毫秒精度）。
 */
data class LogEntry(val timeMs: Long, val level: LogLevel, val tag: String, val msg: String) {
    fun format(): String {
        val t = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timeMs))
        val lv = when (level) {
            LogLevel.DEBUG -> "D"; LogLevel.INFO -> "I"; LogLevel.WARN -> "W"; LogLevel.ERROR -> "E"
        }
        return "$t $lv/$tag: $msg"
    }
}

/**
 * 运行日志总线：内存环形缓冲（默认 500 条）+ 观察者回调。
 *
 * 设计约束：
 *  - 纯 JVM（无 Android 依赖），core 单测可直接验证；
 *  - 线程安全（无障碍回调、执行器、UI 三处并发写入）；
 *  - 文件持久化由 app 层 [dev.nymph.auto.log.LogStore] 挂监听器实现，
 *    core 不碰文件系统（保持可移植与可测）。
 */
object RunLog {

    private const val DEFAULT_CAP = 500

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private val listeners = mutableListOf<(LogEntry) -> Unit>()
    private var cap = DEFAULT_CAP

    fun log(level: LogLevel, tag: String, msg: String) {
        val e = LogEntry(System.currentTimeMillis(), level, tag, msg)
        val snapshot: List<(LogEntry) -> Unit>
        synchronized(lock) {
            buffer.addLast(e)
            while (buffer.size > cap) buffer.removeFirst()
            snapshot = listeners.toList()
        }
        // 监听器在锁外通知，避免其回调重入造成死锁
        snapshot.forEach { runCatching { it(e) } }
    }

    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(LogLevel.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(LogLevel.ERROR, tag, msg)

    /** 当前快照（最早在前）。 */
    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    /** 格式化为多行文本（供分享/导出）。 */
    fun exportText(): String = snapshot().joinToString("\n") { it.format() }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun addListener(l: (LogEntry) -> Unit) = synchronized(lock) { listeners.add(l) }

    fun removeListener(l: (LogEntry) -> Unit) = synchronized(lock) { listeners.remove(l) }
}

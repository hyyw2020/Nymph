package dev.nymph.auto.core.runner

/**
 * 等待浮层状态（裁定阶段 3.5）："正在等待'确认'按钮 3.2s / 15s"。
 *
 * 主界面只显示这一行；绝对时间轴、注入模式等进高级日志。
 * timeoutMs=0 表示无限等待（repeatCheckMax=0）。
 */
data class WaitStatus(
    val target: String,
    val elapsedMs: Long,
    val timeoutMs: Long,
) {
    /** 浮层一行文案。 */
    fun render(): String = buildString {
        append("正在等待「$target」")
        append(" ${"%.1f".format(elapsedMs / 1000.0)}s")
        if (timeoutMs > 0) append(" / ${"%.0f".format(timeoutMs / 1000.0)}s")
        else append("（无超时上限）")
    }
}

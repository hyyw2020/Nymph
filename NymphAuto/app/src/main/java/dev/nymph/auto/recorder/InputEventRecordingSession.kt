// SPDX-License-Identifier: MIT
package dev.nymph.auto.recorder

import dev.nymph.auto.core.input.InputEventRecorder
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.engine.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 真值输入录制会话（v3.0.0）。
 *
 * 经 [ShizukuBridge] 的 shell 通道启动 `getevent -lt`，逐行喂给纯 JVM 的
 * [InputEventRecorder.Parser]，把内核原始触点事件流转成 ([x%],[y%],[t],[pressure],[size])
 * 真值轨迹。**不读屏幕内容**：不碰 MediaProjection / takeScreenshot / 控件树。
 *
 * ## 降级（fail-loud，绝不静默回退截图猜）
 *
 * - `getevent` 探测失败 / 无 `/dev/input` 读权限 → [start] 返回 false，
 *   [status] 给出明确原因，由 UI 显示常驻横幅「本次未启用真值录制」。
 * - 不自动切回截图录制——用户前三版的取证链保留为独立高级模式，由用户显式选择。
 *
 * ## 边界
 *
 * Shizuku/adb shell 对 `/dev/input/event*` 的读权限看 ROM SELinux；首次启动
 * 用 `probeGetevent()` 探测一次，失败即降级。真机行为需在授权设备验证。
 */
class InputEventRecordingSession(
    private val scope: CoroutineScope,
    private val shell: ShizukuBridge,
) {
    /** 录制状态（供 UI 常驻横幅 / 日志）。 */
    data class Status(val active: Boolean, val message: String)

    private val _status = MutableStateFlow(Status(false, "未开始"))
    val status: StateFlow<Status> = _status

    private val _frames = MutableStateFlow<List<InputEventRecorder.Frame>>(emptyList())
    val frames: StateFlow<List<InputEventRecorder.Frame>> = _frames

    private var job: Job? = null

    /**
     * 启动真值录制。
     *
     * 关键：普通 app 进程直接 `ProcessBuilder("getevent")` 拿不到 `/dev/input`
     * 读权限（shell uid 才行）。因此通过 [shell] 的 shell 通道启动 getevent 并把
     * 输出重定向到 app 内部目录文件，再读该文件流喂给解析器——这是 Shizuku/root
     * shell 下可行的路径。会话结束由 shell 通道 kill 掉 getevent 进程。
     *
     * @param outFile getevent 输出重定向目标（app 内部目录，调用方提供）。
     * @param calibration 一次性标定（absMax + 屏幕尺寸 + 方向），由 `getevent -i` 读出后构造。
     * @return true=录制已启动；false=能力不可用（原因见 [status]），调用方走显式降级。
     */
    fun start(outFile: java.io.File, calibration: InputEventRecorder.Calibration): Boolean {
        val probe = shell.probeGetevent()
        if (!probe.ok) {
            val msg = "真值录制不可用：getevent 探测失败（${probe.output}）。" +
                "未启用截图降级；请改用手工拾取坐标 + 快速点击，或授予 Shizuku/root 权限。"
            _status.value = Status(false, msg)
            RunLog.w("InputRecord", msg)
            return false
        }
        // 通过 shell 通道启动 getevent 并重定向到 app 可读文件。
        val launch = shell.executeShellCommand(
            arrayOf("sh", "-c", "getevent -lt > ${outFile.absolutePath} 2>/dev/null & echo $!"),
        )
        if (!launch.ok) {
            val msg = "真值录制不可用：无法通过 shell 启动 getevent。未降级到截图。"
            _status.value = Status(false, msg)
            RunLog.w("InputRecord", msg)
            return false
        }
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val parser = InputEventRecorder.Parser()
            val collected = mutableListOf<InputEventRecorder.Frame>()
            _status.value = Status(true, "真值录制中：getevent /dev/input（不读屏幕内容）")
            try {
                val reader = BufferedReader(InputStreamReader(java.io.FileInputStream(outFile)))
                reader.useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        parser.onLine(line)?.let { frame -> collected.add(frame) }
                    }
                }
                withContext(Dispatchers.Main) {
                    _frames.value = collected.toList()
                    _status.value = Status(false, "真值录制结束：${collected.size} 帧")
                }
            } catch (e: Throwable) {
                val msg = "真值录制失败：${e.message}。未降级到截图，请用手工拾取。"
                withContext(Dispatchers.Main) { _status.value = Status(false, msg) }
                RunLog.w("InputRecord", msg)
            }
        }
        return true
    }

    /** 停止录制并 kill shell 侧的 getevent 进程。 */
    fun stop() {
        job?.cancel()
        job = null
        shell.executeShellCommand(arrayOf("sh", "-c", "pkill -f 'getevent -lt' 2>/dev/null; true"))
        if (!_status.value.message.startsWith("真值录制失败")) {
            _status.value = Status(false, "已停止真值录制")
        }
    }

    /** 会话结束清理（取消协程，避免泄漏）。 */
    fun release() {
        stop()
        scope.cancel()
    }
}

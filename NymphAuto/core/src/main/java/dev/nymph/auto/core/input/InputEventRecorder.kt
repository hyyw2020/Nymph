// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.input

/**
 * 真值输入事件录制（v3.0.0，core 纯 JVM，无 Android 依赖）。
 *
 * ## 真值从哪来
 *
 * 从 `getevent -lt /dev/input/eventX` 的文本流解析多点触控原始事件：
 * - `ABS_MT_SLOT` / `ABS_MT_TRACKING_ID`：多指 slot 状态机（tracking=-1 为抬指）；
 * - `ABS_MT_POSITION_X/Y`：原始触摸坐标（设备像素）；
 * - `ABS_MT_PRESSURE` / `ABS_MT_TOUCH_MAJOR`：压力 / 接触尺寸真值；
 * - `EV_SYN SYN_REPORT`：一帧同步边界，自带内核时间戳。
 *
 * 这条通道**不读屏幕内容**：不碰 MediaProjection / takeScreenshot / PixelCopy /
 * screencap，不依赖控件树，不依赖 VIEW_CLICKED。Unity / Flutter / SurfaceView
 * 等自绘界面完全无感——补上了无障碍语义录制覆盖不到的主战场。
 *
 * ## 坐标标定（一次性，不是每帧猜）
 *
 * [Calibration] 记录某输入设备的 `ABS_MT_POSITION_X/Y` 最大值（由 `getevent -i`
 * 读出）与录制时的屏幕宽高 + 旋转。归一化时把原始坐标除以 absMax 再乘屏幕
 * 像素，得到与 [dev.nymph.auto.core.model.CoordUnit.PCT] 一致的百分比。
 * 多显示器 / 外接触控设备按 device+orientation 分条目，不做运行时自适应。
 *
 * ## 边界（诚实标注）
 *
 * - root 直读 `/dev/input/event*` 稳定；Shizuku / adb shell 的读权限看 ROM
 *   SELinux，`getevent` 多数设备能跑但不打包票——首次启动探测一次，失败走显式
 *   降级（手工拾取一次坐标 + SimpleTap + 常驻横幅），绝不静默回退截图猜。
 * - `getevent` / `input` / `sendevent` 是系统自带二进制，不算新依赖。
 * - 纯 JVM 可测：解析器不碰 Android，逐行喂文本即可验证状态机。
 */
object InputEventRecorder {

    /** 一次标定：某设备在某屏幕方向下的原始坐标量程。 */
    data class Calibration(
        val device: String,
        val orientation: Int,
        val absMaxX: Int,
        val absMaxY: Int,
        val screenW: Int,
        val screenH: Int,
    ) {
        init {
            require(absMaxX > 0 && absMaxY > 0) { "absMax 须为正：$absMaxX x $absMaxY" }
            require(screenW > 0 && screenH > 0) { "屏幕尺寸须为正：$screenW x $screenH" }
        }

        /** 原始设备坐标 → 屏幕百分比（0–100，钳制）。 */
        fun toPercent(rawX: Int, rawY: Int): Pair<Double, Double> {
            val px = (rawX.toDouble() / absMaxX * 100.0).coerceIn(0.0, 100.0)
            val py = (rawY.toDouble() / absMaxY * 100.0).coerceIn(0.0, 100.0)
            return px to py
        }
    }

    /** 单个触点在一帧的采样。 */
    data class Contact(
        val slot: Int,
        val trackingId: Int,
        val rawX: Int,
        val rawY: Int,
        val pressure: Int = 0,
        val touchMajor: Int = 0,
    )

    /** 一帧（SYN_REPORT）的完整触点集合 + 内核时间戳（毫秒）。 */
    data class Frame(
        val timestampMs: Long,
        val contacts: List<Contact>,
    )

    /** 解析状态机：把 getevent 文本行转成逐帧 [Frame]。 */
    class Parser {
        private var curSlot = 0
        private val contacts = mutableMapOf<Int, MutableContact>()
        private var curTimestampMs = 0L

        private data class MutableContact(
            var trackingId: Int = -1,
            var x: Int = 0,
            var y: Int = 0,
            var pressure: Int = 0,
            var touchMajor: Int = 0,
        )

        /** 喂一行 getevent 输出；返回该行是否构成一帧（SYN_REPORT）。 */
        fun onLine(line: String): Frame? {
            val t = line.trim()
            if (t.isEmpty()) return null
            // 时间戳行格式：[  1234.567890] 或 [ 1234.567890]
            TIME_RE.find(t)?.let { m ->
                val secs = m.groupValues[1].toDoubleOrNull() ?: 0.0
                curTimestampMs = (secs * 1000.0).toLong()
            }
            // ABS_MT_SLOT 00  -> slot=0
            if (t.contains("ABS_MT_SLOT")) {
                parseHexTail(t)?.let { curSlot = it }
                return null
            }
            // 事件值多为 8 位十六进制：ABS_MT_POSITION_X 0000012c
            when {
                t.contains("ABS_MT_TRACKING_ID") -> {
                    parseHexTail(t)?.let { contacts.getOrPut(curSlot) { MutableContact() }.trackingId = it }
                }
                t.contains("ABS_MT_POSITION_X") -> {
                    parseHexTail(t)?.let { contacts.getOrPut(curSlot) { MutableContact() }.x = it }
                }
                t.contains("ABS_MT_POSITION_Y") -> {
                    parseHexTail(t)?.let { contacts.getOrPut(curSlot) { MutableContact() }.y = it }
                }
                t.contains("ABS_MT_PRESSURE") -> {
                    parseHexTail(t)?.let { contacts.getOrPut(curSlot) { MutableContact() }.pressure = it }
                }
                t.contains("ABS_MT_TOUCH_MAJOR") -> {
                    parseHexTail(t)?.let { contacts.getOrPut(curSlot) { MutableContact() }.touchMajor = it }
                }
                t.contains("SYN_REPORT") -> return flush()
            }
            return null
        }

        /** 抬指（tracking=-1）的 slot 从活动集移除，避免在帧里残留。 */
        private fun flush(): Frame {
            val active = contacts.filter { it.value.trackingId >= 0 }
            // 只保留本帧有 trackingId 的触点；坐标为默认 0 且无 tracking 的忽略。
            val snapshot = active.map { (slot, c) ->
                Contact(slot, c.trackingId, c.x, c.y, c.pressure, c.touchMajor)
            }.sortedBy { it.slot }
            // 清理抬指：tracking=-1 的 slot 删除，避免下一帧污染。
            val lifted = contacts.filter { it.value.trackingId < 0 }.map { it.key }
            lifted.forEach { contacts.remove(it) }
            return Frame(curTimestampMs, snapshot)
        }

        companion object {
            private val TIME_RE = Regex("\\[\\s*(\\d+\\.\\d+)\\]")
            /** 取行尾的十六进制 token（getevent -l 输出如 `0000012c` / 抬指 `ffffffff`）。 */
            private fun parseHexTail(line: String): Int? {
                val tok = line.trim().split(Regex("\\s+")).lastOrNull() ?: return null
                // 用 Long 解析再收窄：ffffffff（tracking=-1）在 Int 下直接 toInt(16) 会抛异常。
                return tok.toLongOrNull(16)?.toInt()
            }
        }
    }
}

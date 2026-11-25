// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.input.InputEventRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.0 真值输入录制回归：getevent 解析 / slot 状态机 / 标定归一化。
 *
 * 锁死：ABS 十六进制解析、slot 切换、tracking=-1 抬指移除、SYN_REPORT 帧边界、
 * 时间戳提取、多指并存、absMax 标定到百分比的归一化与钳制。
 */
class V300InputEventRecorderTest {

    private fun cal() = InputEventRecorder.Calibration(
        device = "event5", orientation = 0,
        absMaxX = 1000, absMaxY = 2000,
        screenW = 1000, screenH = 2000,
    )

    @Test
    fun `标定归一化到百分比并钳制`() {
        val c = cal()
        assertEquals(50.0 to 25.0, c.toPercent(500, 500))
        assertEquals(0.0 to 0.0, c.toPercent(-10, -10))
        assertEquals(100.0 to 100.0, c.toPercent(1500, 3000))
    }

    @Test
    fun `单指点击解析出完整真值帧`() {
        val p = InputEventRecorder.Parser()
        p.onLine("[  1234.567890] /dev/input/event5: EV_ABS       ABS_MT_SLOT          00000000")
        p.onLine("[  1234.567900] /dev/input/event5: EV_ABS       ABS_MT_TRACKING_ID   0000002a")
        p.onLine("[  1234.567910] /dev/input/event5: EV_ABS       ABS_MT_POSITION_X    000001f4") // 500
        p.onLine("[  1234.567920] /dev/input/event5: EV_ABS       ABS_MT_POSITION_Y    000003e8") // 1000
        p.onLine("[  1234.567930] /dev/input/event5: EV_ABS       ABS_MT_PRESSURE      00000040") // 64
        val frame = p.onLine("[  1234.567940] /dev/input/event5: EV_SYN       SYN_REPORT           00000000")
        assertTrue(frame != null)
        assertEquals(1, frame!!.contacts.size)
        val c = frame.contacts[0]
        assertEquals(0, c.slot); assertEquals(42, c.trackingId)
        assertEquals(500, c.rawX); assertEquals(1000, c.rawY); assertEquals(64, c.pressure)
        assertEquals(1234567L, frame.timestampMs)
    }

    @Test
    fun `抬指tracking负一后帧内无触点`() {
        val p = InputEventRecorder.Parser()
        p.onLine("EV_ABS ABS_MT_SLOT 00000000")
        p.onLine("EV_ABS ABS_MT_TRACKING_ID 00000001")
        p.onLine("EV_ABS ABS_MT_POSITION_X 00000064")
        p.onLine("EV_SYN SYN_REPORT 00000000")
        // 抬指：-1 的十六进制补码 ffffffff
        p.onLine("EV_ABS ABS_MT_SLOT 00000000")
        p.onLine("EV_ABS ABS_MT_TRACKING_ID ffffffff")
        val frame = p.onLine("EV_SYN SYN_REPORT 00000000")
        assertTrue(frame != null)
        assertEquals(0, frame!!.contacts.size)
    }

    @Test
    fun `多指slot分别记录`() {
        val p = InputEventRecorder.Parser()
        // slot 0
        p.onLine("EV_ABS ABS_MT_SLOT 00000000")
        p.onLine("EV_ABS ABS_MT_TRACKING_ID 00000001")
        p.onLine("EV_ABS ABS_MT_POSITION_X 00000064")
        // slot 1
        p.onLine("EV_ABS ABS_MT_SLOT 00000001")
        p.onLine("EV_ABS ABS_MT_TRACKING_ID 00000002")
        p.onLine("EV_ABS ABS_MT_POSITION_X 000001f4")
        val frame = p.onLine("EV_SYN SYN_REPORT 00000000")
        assertTrue(frame != null)
        assertEquals(2, frame!!.contacts.size)
        assertEquals(0, frame.contacts[0].slot)
        assertEquals(1, frame.contacts[1].slot)
        assertEquals(100, frame.contacts[0].rawX)
        assertEquals(500, frame.contacts[1].rawX)
    }
}

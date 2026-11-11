// SPDX-License-Identifier: MIT
package dev.nymph.auto.compose

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.ui.compose.describe
import dev.nymph.auto.ui.compose.fmt
import dev.nymph.auto.ui.compose.fmtPct
import dev.nymph.auto.ui.compose.kindOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑器坐标格式化与动作摘要的边界测试（v2.6.0）。
 * 拾取浮层回填的百分比带 4 位小数，输入框往返必须无损、
 * 摘要必须完整呈现坐标与参数，避免「存进去和看到的不一样」。
 */
class EditorFormatTest {

    @Test
    fun `fmt 空值与整数与小数`() {
        assertEquals("", fmt(0.0))
        assertEquals("50", fmt(50.0))
        assertEquals("34.5678", fmt(34.5678))
    }

    @Test
    fun `fmtPct 保留必要小数`() {
        assertEquals("50", fmtPct(50.0))
        assertEquals("33.33", fmtPct(100.0 / 3))
    }

    @Test
    fun `fmt 与 fmtPct 在德语区仍是小数点 回填解析不丢坐标`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val s = fmt(34.5678)
            assertEquals("34.5678", s)
            // 编辑器打开已有小数坐标 → 确定时 num(s) 必须解析回原值，不能被清零
            assertEquals(34.5678, s.toDoubleOrNull()!!, 1e-9)
            assertEquals("33.33", fmtPct(100.0 / 3))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test
    fun `kindOf 四类互映射`() {
        assertEquals(0, kindOf(Action(type = ActionType.TAP)))
        assertEquals(1, kindOf(Action(type = ActionType.LONG_PRESS)))
        assertEquals(2, kindOf(Action(type = ActionType.MULTI_TAP)))
        assertEquals(3, kindOf(Action(type = ActionType.SWIPE)))
    }

    @Test
    fun `describe 点击含坐标与参数`() {
        val a = Action(
            type = ActionType.TAP, unit = CoordUnit.PCT,
            x = 34.5678, y = 66.0, holdMs = 100, times = 3, waitMs = 200, desc = "开宝箱",
        )
        val s = describe(a)
        assert(s.contains("34.57")) { s }
        assert(s.contains("66")) { s }
        assert(s.contains("×3") || s.contains("重复 3")) { s }
        assert(s.contains("开宝箱")) { s }
    }

    @Test
    fun `describe 滑动含终点`() {
        val a = Action(
            type = ActionType.SWIPE, unit = CoordUnit.PCT,
            x = 50.0, y = 80.0, x2 = 50.0, y2 = 20.0, durationMs = 220,
        )
        val s = describe(a)
        assert(s.contains("→")) { s }
        assert(s.contains("220ms")) { s }
    }
}

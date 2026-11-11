// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.1 视觉设计 token 的纯 JVM 约束。
 *
 * 无真机环境下没法截图比对，但至少能用数据锁住「层级不糊、间距为正、
 * 文字可读」这三条底线，避免又把颜色/字号硬编码散落回 View 里。
 */
class FloatingEditorThemeTest {

    @Test
    fun `字号层级严格递减 标题大于正文大于标签大于提示`() {
        assertTrue(FloatingEditorTheme.fontSizeHierarchyOk())
        assertTrue(FloatingEditorTheme.TEXT_TITLE_SP > FloatingEditorTheme.TEXT_BODY_SP)
        assertTrue(FloatingEditorTheme.TEXT_BODY_SP > FloatingEditorTheme.TEXT_LABEL_SP)
        assertTrue(FloatingEditorTheme.TEXT_LABEL_SP > FloatingEditorTheme.TEXT_HINT_SP)
    }

    @Test
    fun `圆角与间距均为正 卡片圆角大于控件圆角`() {
        assertTrue(FloatingEditorTheme.geometryOk())
        assertTrue(FloatingEditorTheme.RADIUS_CARD_DP > FloatingEditorTheme.RADIUS_CONTROL_DP)
        assertTrue(FloatingEditorTheme.STATUS_MIN_DP > 0)
        assertEquals(18, FloatingEditorTheme.STATUS_MIN_DP)
    }

    @Test
    fun `正文与次文字在卡片底色上可读`() {
        assertTrue(FloatingEditorTheme.textReadable())
        assertTrue(FloatingEditorTheme.contrast(FloatingEditorTheme.TEXT, FloatingEditorTheme.BG) > 100.0)
        assertTrue(FloatingEditorTheme.contrast(FloatingEditorTheme.TEXT_DIM, FloatingEditorTheme.BG) > 60.0)
    }

    @Test
    fun `亮度计算与对比度对称 且黑底白字对比最大`() {
        val a = FloatingEditorTheme.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val b = FloatingEditorTheme.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(a, b, 0.0001)
        assertTrue(a > 250.0)
        assertEquals(0.0, FloatingEditorTheme.contrast(0xFF123456.toInt(), 0xFF123456.toInt()), 0.0001)
    }

    @Test
    fun `强调色与状态色彼此可区分`() {
        val distinct = listOf(
            FloatingEditorTheme.ACCENT,
            FloatingEditorTheme.OK,
            FloatingEditorTheme.WARN,
            FloatingEditorTheme.DANGER,
        )
        assertEquals(4, distinct.toSet().size)
        // 折叠小球必须足够不透明，否则在其他 APP 上几乎看不见
        assertTrue((FloatingEditorTheme.BALL ushr 24) >= 0xE0)
    }
}

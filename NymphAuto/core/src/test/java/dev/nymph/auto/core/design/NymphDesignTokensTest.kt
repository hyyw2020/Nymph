// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import dev.nymph.auto.core.editor.FloatingEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0「设计 token 单一真源」的纯 JVM 约束。
 *
 * 无真机、无 Roborazzi 截图基线的环境里，能自动验证的是：
 *  - 主界面与悬浮层引用的是**同一组常量**（改一处两边同时生效）；
 *  - 玻璃卡片文本对比度、字号层级、几何、语义色可区分性仍是构造性质。
 */
class NymphDesignTokensTest {

    @Test
    fun `悬浮层色板逐槽等于真源投影 不存在第二份定义`() {
        val t = NymphDesignTokens
        assertEquals(t.asArgb(t.GLASS_BG), FloatingEditorTheme.BG)
        assertEquals(t.asArgb(t.GLASS_ELEVATED), FloatingEditorTheme.BG_ELEVATED)
        assertEquals(t.asArgb(t.GLASS_STROKE), FloatingEditorTheme.STROKE)
        assertEquals(t.asArgb(t.GLASS_STROKE_FOCUS), FloatingEditorTheme.STROKE_FOCUS)
        assertEquals(t.asArgb(t.ACCENT), FloatingEditorTheme.ACCENT)
        assertEquals(t.asArgb(t.GLASS_ACCENT_SOFT), FloatingEditorTheme.ACCENT_SOFT)
        assertEquals(t.asArgb(t.GLASS_CONTROL), FloatingEditorTheme.CONTROL)
        assertEquals(t.asArgb(t.TEXT_GLASS), FloatingEditorTheme.TEXT)
        assertEquals(t.asArgb(t.TEXT_GLASS_DIM), FloatingEditorTheme.TEXT_DIM)
        assertEquals(t.asArgb(t.TEXT_GLASS_MUTED), FloatingEditorTheme.TEXT_MUTED)
        assertEquals(t.asArgb(t.OK), FloatingEditorTheme.OK)
        assertEquals(t.asArgb(t.WARNING), FloatingEditorTheme.WARN)
        assertEquals(t.asArgb(t.DANGER), FloatingEditorTheme.DANGER)
        assertEquals(t.asArgb(t.GLASS_BALL), FloatingEditorTheme.BALL)
        assertEquals(t.asArgb(t.ACCENT_BRIGHT), FloatingEditorTheme.BALL_RING)
    }

    @Test
    fun `悬浮层尺寸字号全部来自真源 且字号层级保持 v2_8_1 视觉值`() {
        val t = NymphDesignTokens
        assertEquals(t.RADIUS_CARD_DP, FloatingEditorTheme.RADIUS_CARD_DP)
        assertEquals(t.RADIUS_CONTROL_DP, FloatingEditorTheme.RADIUS_CONTROL_DP)
        assertEquals(t.RADIUS_FIELD_DP, FloatingEditorTheme.RADIUS_FIELD_DP)
        assertEquals(t.PAD_CARD_DP, FloatingEditorTheme.PAD_CARD_DP)
        assertEquals(t.GAP_DP, FloatingEditorTheme.GAP_DP)
        assertEquals(t.BAR_H_DP, FloatingEditorTheme.BAR_H_DP)
        assertEquals(t.STATUS_MIN_DP, FloatingEditorTheme.STATUS_MIN_DP)
        assertEquals(18, t.STATUS_MIN_DP)
        assertEquals(t.TEXT_TITLE_SP, FloatingEditorTheme.TEXT_TITLE_SP)
        assertEquals(t.TEXT_BODY_SP, FloatingEditorTheme.TEXT_BODY_SP)
        assertEquals(t.TEXT_LABEL_SP, FloatingEditorTheme.TEXT_LABEL_SP)
        assertEquals(t.TEXT_HINT_SP, FloatingEditorTheme.TEXT_HINT_SP)
        // v2.8.1 视觉一字节不变
        assertEquals(13f, t.TEXT_TITLE_SP, 0f)
        assertEquals(12.5f, t.TEXT_BODY_SP, 0f)
        assertEquals(11f, t.TEXT_LABEL_SP, 0f)
        assertEquals(10.5f, t.TEXT_HINT_SP, 0f)
        // v2.9.8 紧凑化：间距/控件高度全部收紧，但字号一字节不变（可读性不降）
        assertEquals(10, t.PAD_CARD_DP)
        assertEquals(8, t.PAD_CONTROL_H_DP)
        assertEquals(6, t.PAD_CONTROL_V_DP)
        assertEquals(5, t.GAP_DP)
        assertEquals(34, t.BAR_H_DP)
        assertEquals(30, t.FIELD_MIN_H_DP)
        assertEquals(28, t.ICON_BTN_DP)
        assertTrue(t.RADIUS_CARD_DP > t.RADIUS_CONTROL_DP)
        assertTrue(t.GAP_DP < t.PAD_CARD_DP)
    }

    @Test
    fun `约束方法委托真源 两处判定一致`() {
        assertEquals(NymphDesignTokens.fontSizeHierarchyOk(), FloatingEditorTheme.fontSizeHierarchyOk())
        assertEquals(NymphDesignTokens.geometryOk(), FloatingEditorTheme.geometryOk())
        assertEquals(NymphDesignTokens.glassTextReadable(), FloatingEditorTheme.textReadable())
        assertTrue(NymphDesignTokens.fontSizeHierarchyOk())
        assertTrue(NymphDesignTokens.geometryOk())
        assertTrue(NymphDesignTokens.glassTextReadable())
    }

    @Test
    fun `警告色在主界面与悬浮层同源 不再两处各写一遍`() {
        assertEquals(NymphDesignTokens.WARNING, 0xFFFBBF24)
        assertEquals(NymphDesignTokens.asArgb(NymphDesignTokens.WARNING), FloatingEditorTheme.WARN)
        // 主界面 tertiary（Amber）与悬浮层 WARN 引用同一个 token
        assertEquals(NymphDesignTokens.WARNING, NymphDesignTokens.asArgb(NymphDesignTokens.WARNING).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun `语义状态色彼此可区分 且对比度构造性达标`() {
        assertTrue(NymphDesignTokens.semanticColorsDistinct())
        assertTrue(NymphDesignTokens.contrast(NymphDesignTokens.TEXT_GLASS, NymphDesignTokens.GLASS_BG) > 100.0)
        assertTrue(NymphDesignTokens.contrast(NymphDesignTokens.TEXT_GLASS_DIM, NymphDesignTokens.GLASS_BG) > 60.0)
        // 亮度差对称、同色为 0
        val a = NymphDesignTokens.contrast(0xFFFFFFFF, 0xFF000000)
        val b = NymphDesignTokens.contrast(0xFF000000, 0xFFFFFFFF)
        assertEquals(a, b, 0.0001)
        assertEquals(0.0, NymphDesignTokens.contrast(0xFF123456, 0xFF123456), 0.0001)
    }

    @Test
    fun `asArgb 高低位正确 卡片底透出底层 折叠小球仍足够不透明`() {
        // v2.9.8：0xED → 0xD9 → 0xB0；RGB 不变，只降 alpha，
        // 因此 luminance / contrast 自检与文字可读性不受影响。
        assertEquals(0xB00C1422.toInt(), NymphDesignTokens.asArgb(NymphDesignTokens.GLASS_BG))
        val bgAlpha = (NymphDesignTokens.asArgb(NymphDesignTokens.GLASS_BG) ushr 24) and 0xFF
        assertTrue("卡片底 alpha 应明显低于 v2.9.7 的 0xD9，实测 0x${bgAlpha.toString(16)}",
            bgAlpha < 0xC0)
        assertTrue("小球仍要足够不透明", (FloatingEditorTheme.BALL ushr 24) >= 0xE0)
        assertFalse(NymphDesignTokens.asArgb(NymphDesignTokens.ACCENT) == 0)
    }

    @Test
    fun `浮窗色板 v2_9_3 不重复定义 且全部可区分`() {
        // 12 个浮窗色槽彼此不同（新增一个槽绝不能写成与已有槽相同的值）
        assertTrue(NymphDesignTokens.overlayTokensDistinct())
        // 浮窗强调绿刻意与品牌绿分离（避免浮在亮色 APP 上刺眼）
        assertTrue(NymphDesignTokens.overlayAccentDistinctFromAccent())
        // 关键视觉值锁定，防止回退
        assertEquals(0xF21F2430, NymphDesignTokens.OVERLAY_BG)
        assertEquals(0xFF059669, NymphDesignTokens.OVERLAY_ACCENT)
        assertEquals(0xFF34D399, NymphDesignTokens.OVERLAY_ACCENT_SOFT)
        assertEquals(0xFF6B7280, NymphDesignTokens.OVERLAY_IDLE)
        assertEquals(0xFF4ADE80, NymphDesignTokens.OVERLAY_RUN)
    }
}

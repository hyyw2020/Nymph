// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import dev.nymph.auto.core.design.NymphDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v2.9.3「浮窗统一视觉投影」接线约束（ADR-008：新增能力必须接线且有单测锁死）。
 *
 * 证明三套浮窗（录制面板 / 运行球 / 拾取手柄）引用的 [OverlayTheme] 确实从
 * [NymphDesignTokens] 真源派生，而不是又写一份硬编码字面量。
 */
class OverlayThemeTest {

    @Test
    fun `OverlayTheme 全部亮面色直接来自 NymphDesignTokens 真源`() {
        val t = NymphDesignTokens
        assertEquals(t.asArgb(t.OVERLAY_BG), OverlayTheme.PANEL_BG)
        assertEquals(t.asArgb(t.OVERLAY_BTN), OverlayTheme.BTN_BG)
        assertEquals(t.asArgb(t.OVERLAY_TEXT), OverlayTheme.BTN_TEXT)
        assertEquals(t.asArgb(t.OVERLAY_TEXT_DIM), OverlayTheme.TEXT_DIM)
        assertEquals(t.asArgb(t.OVERLAY_TEXT_BRIGHT), OverlayTheme.TEXT_BRIGHT)
        assertEquals(t.asArgb(t.OVERLAY_IDLE), OverlayTheme.IDLE)
        assertEquals(t.asArgb(t.OVERLAY_RUN), OverlayTheme.RUN)
        assertEquals(t.asArgb(t.OVERLAY_DANGER), OverlayTheme.DANGER_STRONG)
        assertEquals(t.asArgb(t.OVERLAY_ACCENT), OverlayTheme.ACCENT)
        assertEquals(t.asArgb(t.OVERLAY_ACCENT_SOFT), OverlayTheme.ACCENT_SOFT)
        assertEquals(t.asArgb(t.OVERLAY_STROKE_SOFT), OverlayTheme.STROKE_SOFT)
        assertEquals(t.asArgb(t.OVERLAY_PANEL_DARK), OverlayTheme.PANEL_DARK)
        assertEquals(t.asArgb(t.WARNING), OverlayTheme.WARNING)
        assertEquals(t.asArgb(t.DANGER), OverlayTheme.DANGER)
    }

    @Test
    fun `语义状态色彼此可区分 不退化成单一颜色`() {
        val set = setOf(
            OverlayTheme.DANGER, OverlayTheme.DANGER_STRONG,
            OverlayTheme.WARNING, OverlayTheme.ACCENT,
            OverlayTheme.ACCENT_SOFT, OverlayTheme.IDLE, OverlayTheme.RUN,
        )
        assertEquals(7, set.size)
        // 录制红 / 执行绿 / 警告黄 / 强调绿 各不相同
        assertNotEquals(OverlayTheme.DANGER, OverlayTheme.ACCENT)
        assertNotEquals(OverlayTheme.RUN, OverlayTheme.WARNING)
    }

    @Test
    fun `几何复用 token 槽位 不另起一套尺寸`() {
        assertEquals(NymphDesignTokens.RADIUS_CARD_DP, OverlayTheme.RADIUS_PANEL_DP)
        assertEquals(NymphDesignTokens.RADIUS_CONTROL_DP, OverlayTheme.RADIUS_BTN_DP)
    }
}

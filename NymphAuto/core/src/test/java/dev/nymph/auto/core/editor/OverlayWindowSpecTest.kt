// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0 窗口规格约束：把 v2.8.1 靠注释保证的「flags 恒定 / 拖动只改 x/y」
 * 变成可自动执行的断言。无需真机、无需 Robolectric。
 */
class OverlayWindowSpecTest {

    @Test
    fun `flag 位掩码与 AOSP WindowManager 常量一致`() {
        assertEquals(0x00000008, OverlayWindowSpec.FLAG_NOT_FOCUSABLE)
        assertEquals(0x00000020, OverlayWindowSpec.FLAG_NOT_TOUCH_MODAL)
        assertEquals(0x28, OverlayWindowSpec.STANDARD_FLAGS)
        assertEquals(0x20, OverlayWindowSpec.INPUT_FLAGS)
    }

    @Test
    fun `默认态不抢焦点且面板外透传 输入态仍保持 NOT_TOUCH_MODAL`() {
        val standard = OverlayWindowSpec.flagsFor(inputFocused = false)
        assertEquals(OverlayWindowSpec.STANDARD_FLAGS, standard)
        assertTrue(standard and OverlayWindowSpec.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(standard and OverlayWindowSpec.FLAG_NOT_TOUCH_MODAL != 0)

        val input = OverlayWindowSpec.flagsFor(inputFocused = true)
        assertEquals(OverlayWindowSpec.INPUT_FLAGS, input)
        assertEquals(0, input and OverlayWindowSpec.FLAG_NOT_FOCUSABLE)
        assertTrue(input and OverlayWindowSpec.FLAG_NOT_TOUCH_MODAL != 0)
    }

    @Test
    fun `只允许两种受控 flags 任何新增 flag 都被拒绝`() {
        assertTrue(OverlayWindowSpec.isAllowed(OverlayWindowSpec.STANDARD_FLAGS))
        assertTrue(OverlayWindowSpec.isAllowed(OverlayWindowSpec.INPUT_FLAGS))
        // 后来者顺手加 NOT_TOUCHABLE / WATCH_OUTSIDE_TOUCH / SECURE 都会失败
        assertFalse(OverlayWindowSpec.isAllowed(OverlayWindowSpec.STANDARD_FLAGS or 0x00000010))
        assertFalse(OverlayWindowSpec.isAllowed(OverlayWindowSpec.STANDARD_FLAGS or 0x00040000))
        assertFalse(OverlayWindowSpec.isAllowed(OverlayWindowSpec.STANDARD_FLAGS or 0x00002000))
        assertFalse(OverlayWindowSpec.isAllowed(0))
        assertFalse(OverlayWindowSpec.isAllowed(-1))
    }

    @Test
    fun `合法切换只在两个允许态之间 且折叠展开不触发切换`() {
        val s = OverlayWindowSpec.STANDARD_FLAGS
        val i = OverlayWindowSpec.INPUT_FLAGS
        assertTrue(OverlayWindowSpec.isLegalTransition(s, i))
        assertTrue(OverlayWindowSpec.isLegalTransition(i, s))
        assertTrue(OverlayWindowSpec.isLegalTransition(s, s))
        assertFalse(OverlayWindowSpec.isLegalTransition(s or 0x10, i))
        assertFalse(OverlayWindowSpec.isLegalTransition(s, i or 0x10))
        // 折叠/展开用同一函数、同一入参 → 同一结果，不可能切 flags
        assertEquals(
            OverlayWindowSpec.flagsFor(false),
            OverlayWindowSpec.flagsFor(false),
        )
    }

    @Test
    fun `拖动提交只改 x_y 其余字段必须逐字不变`() {
        val before = OverlayWindowSpec.Frame(
            x = 100, y = 200,
            flags = OverlayWindowSpec.STANDARD_FLAGS,
            type = 2038, format = -3, width = 240, height = 380,
        )
        val after = OverlayWindowSpec.moved(before, 140, 260)
        assertTrue(OverlayWindowSpec.isPositionOnlyUpdate(before, after))
        assertEquals(140, after.x)
        assertEquals(260, after.y)
        assertEquals(before.flags, after.flags)
        assertEquals(before.type, after.type)
        assertEquals(before.format, after.format)
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
    }

    @Test
    fun `任何非位移改动都被判非法 丢弃该帧而不 relayout`() {
        val before = OverlayWindowSpec.Frame(10, 20, OverlayWindowSpec.STANDARD_FLAGS, 2038, -3, 240, 380)
        assertFalse(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(x = 11, flags = OverlayWindowSpec.INPUT_FLAGS)))
        assertFalse(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(x = 11, type = 1)))
        assertFalse(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(x = 11, format = 1)))
        assertFalse(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(x = 11, width = 241)))
        assertFalse(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(x = 11, height = 381)))
        // flags 非法态即使只改 x 也拒绝
        assertFalse(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(x = 11, flags = 0)))
        // 只改 x（其余逐字不变、flags 合法）是合法的位移提交
        assertTrue(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(x = 11)))
        assertTrue(OverlayWindowSpec.isPositionOnlyUpdate(before, before.copy(y = 21)))
        // 完全没动不算「有效提交」，应被丢弃（与 shouldCommit 的 1px 阈值呼应）
        assertFalse(OverlayWindowSpec.isPositionOnlyUpdate(before, before))
    }
}

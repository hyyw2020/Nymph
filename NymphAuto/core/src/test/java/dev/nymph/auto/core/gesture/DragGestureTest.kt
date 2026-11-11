// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.4 录制面板拖动缺陷的纯 JVM 回归。
 *
 * 重点守住两条：空白区域按下（onTouchEvent 自己消费 DOWN）也能产生位移；
 * 从按钮上开始拖动（onInterceptTouchEvent 拦截）时首帧不跳变。
 */
class DragGestureTest {

    private fun g() = DragGesture(slopPx = 8)

    @Test
    fun `阈值内的移动不触发拖动 按钮点击不被误伤`() {
        val g = g()
        g.onDown(100f, 100f)
        assertFalse(g.onInterceptMove(104f, 103f))
        assertNull(g.onOwnMove(104f, 103f))
        assertFalse(g.isDragging)
        assertFalse("未拖动时 UP 不消费，交给按钮点击", g.onUp())
    }

    @Test
    fun `落点在空白区域 面板自己消费 DOWN 后仍能产生位移`() {
        // v2.9.3 的 bug：onTouchEvent(DOWN) 返回 false，MOVE 不再派发，
        // 这条路径完全拿不到增量，面板实际拖不动。
        val g = g()
        g.onDown(100f, 100f)
        assertNull("未跨阈值不移动", g.onOwnMove(105f, 100f))
        assertNull("首次跨阈值只建基准，不产生跳变", g.onOwnMove(130f, 110f))
        assertEquals(10 to 5, g.onOwnMove(140f, 115f))
        assertEquals(-4 to 0, g.onOwnMove(136f, 115f))
        assertTrue(g.isDragging)
        assertTrue("拖动过则消费 UP", g.onUp())
        assertFalse(g.isDragging)
    }

    @Test
    fun `从按钮上开始拖动 拦截后首帧无跳变`() {
        val g = g()
        g.onDown(100f, 100f)
        assertFalse(g.onInterceptMove(103f, 100f))
        assertTrue(g.onInterceptMove(120f, 100f)) // 拦截点 x=120
        assertEquals(5 to 0, g.onOwnMove(125f, 100f)) // 从拦截点算增量，而非 DOWN 点
        assertTrue(g.onUp())
    }

    @Test
    fun `拦截后继续的 MOVE 不再重复判定阈值`() {
        val g = g()
        g.onDown(0f, 0f)
        assertTrue(g.onInterceptMove(20f, 0f))
        assertEquals(1 to 0, g.onOwnMove(21f, 0f))
        assertEquals(1 to 0, g.onOwnMove(22f, 0f))
    }

    @Test
    fun `新的按下完整重置状态 取消后不残留拖动`() {
        val g = g()
        g.onDown(0f, 0f)
        g.onOwnMove(30f, 0f)
        assertTrue(g.isDragging)
        g.onDown(500f, 500f)
        assertFalse(g.isDragging)
        assertNull(g.onOwnMove(503f, 500f))
    }

    @Test
    fun `斜向与负方向增量正确`() {
        val g = g()
        g.onDown(200f, 200f)
        assertNull(g.onOwnMove(220f, 190f)) // 建立基准
        assertEquals(-7 to 12, g.onOwnMove(213f, 202f))
    }
}

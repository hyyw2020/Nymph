// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「危险边缘也能拖回」的几何契约。
 *
 * 这些用例对应真实的失败形态：准星拖到 x=0，抓手窗口被夹在屏内，圆心却落在
 * 15dp 处；手指落在返回手势热区，MOVE 被系统截走；命中区只有 30dp，边缘处
 * 实际可触面积不足 48dp 的一半。
 */
class PointerTouchTargetTest {

    private val w = 1280
    private val h = 2800
    private val touch = 144   // 48dp @ 3x
    private val excl = 288    // 96dp @ 3x

    @Test
    fun `屏幕中央时窗口居中且抓手压在准星上`() {
        val r = PointerTouchTarget.windowFor(640f, 1400f, w, h, touch)
        assertEquals(640 - touch / 2, r.l)
        assertEquals(1400 - touch / 2, r.t)
        assertEquals(touch, r.width)
        assertEquals(touch, r.height)
        // 中央时窗口中心与准星重合，抓手应正好画在窗口中心。
        val (mx, my) = PointerTouchTarget.markerIn(r, 640f, 1400f)
        assertEquals(touch / 2f, mx, 0.01f)
        assertEquals(touch / 2f, my, 0.01f)
    }

    @Test
    fun `准星贴左边缘时窗口不越界但抓手仍在真值处`() {
        val r = PointerTouchTarget.windowFor(0f, 1400f, w, h, touch)
        assertEquals(0, r.l)
        // 关键：抓手画在窗口左缘（x=0），而不是窗口中心。
        // 旧实现把圆心固定画在 w/2，准星在 0 时圆心却显示在 15dp，读数与视觉对不上。
        val (mx, _) = PointerTouchTarget.markerIn(r, 0f, 1400f)
        assertEquals(0f, mx, 0.01f)
    }

    @Test
    fun `准星贴右下角时窗口与抓手都在真值处`() {
        val r = PointerTouchTarget.windowFor((w - 1).toFloat(), (h - 1).toFloat(), w, h, touch)
        assertEquals(w - touch, r.l)
        assertEquals(h - touch, r.t)
        val (mx, my) = PointerTouchTarget.markerIn(r, (w - 1).toFloat(), (h - 1).toFloat())
        // 抓手落在窗口右下角附近，允许因取整差 1px。
        assertTrue(mx >= touch - 2f)
        assertTrue(my >= touch - 2f)
    }

    @Test
    fun `窗口永远完整落在屏幕内`() {
        for (x in listOf(-500f, 0f, 1f, 640f, (w - 1).toFloat(), (w + 500).toFloat())) {
            for (y in listOf(-500f, 0f, 1f, 1400f, (h - 1).toFloat(), (h + 500).toFloat())) {
                val r = PointerTouchTarget.windowFor(x, y, w, h, touch)
                assertTrue("l<0 at $x,$y", r.l >= 0)
                assertTrue("t<0 at $x,$y", r.t >= 0)
                assertTrue("r>w at $x,$y", r.r <= w)
                assertTrue("b>h at $x,$y", r.b <= h)
                assertEquals(touch, r.width)
            }
        }
    }

    @Test
    fun `手势排除区跟随准星并在边缘压住手势热区`() {
        // 左边缘：排除区必须从 x=0 开始，才能盖住返回手势热区。
        val left = PointerTouchTarget.exclusionFor(0f, 1400f, w, h, excl)
        assertNotNull(left)
        assertEquals(0, left!!.l)
        assertEquals(excl, left.width)

        // 右边缘：贴到屏幕右界。
        val right = PointerTouchTarget.exclusionFor((w - 1).toFloat(), 1400f, w, h, excl)
        assertNotNull(right)
        assertEquals(w, right!!.r)

        // 底部：贴近 Home 上滑区。
        val bottom = PointerTouchTarget.exclusionFor(640f, (h - 1).toFloat(), w, h, excl)
        assertNotNull(bottom)
        assertEquals(h, bottom!!.b)
    }

    @Test
    fun `手势排除区不大于屏幕且不为空`() {
        for (x in listOf(0f, 640f, (w - 1).toFloat())) {
            val r = PointerTouchTarget.exclusionFor(x, 1400f, w, h, excl)!!
            assertFalse(r.isEmpty)
            assertTrue(r.l >= 0 && r.t >= 0 && r.r <= w && r.b <= h)
        }
    }

    @Test
    fun `屏幕小于排除区时退化为整屏而不是空矩形`() {
        // 小屏 + 超大排除区：应裁到屏幕全尺寸，而不是返回空导致排除失效。
        val r = PointerTouchTarget.exclusionFor(50f, 50f, 100, 100, 800)
        assertNotNull(r)
        assertEquals(0, r!!.l)
        assertEquals(0, r.t)
        assertEquals(100, r.r)
        assertEquals(100, r.b)
    }

    @Test
    fun `零尺寸屏幕不产生非法矩形`() {
        // 防御：窗口尚未测量时 screenW/H 可能为 0，不能抛异常也不能给出负矩形。
        assertNull(PointerTouchTarget.exclusionFor(0f, 0f, 0, 0, 100))
        val r = PointerTouchTarget.windowFor(0f, 0f, 0, 0, 100)
        assertTrue(r.l >= 0 && r.t >= 0)
    }
}

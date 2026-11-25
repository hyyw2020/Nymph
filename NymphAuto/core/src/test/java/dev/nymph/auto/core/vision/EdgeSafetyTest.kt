// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定落点边缘安全的契约。
 *
 * 对应真实失败形态：脚本把落点写在 0%，运行时 `dispatchGesture` 返回成功，
 * 日志显示已点击，界面毫无反应——事件在系统手势层被改写，没有错误码可查。
 */
class EdgeSafetyTest {

    private val w = 1080
    private val h = 2400
    private val insets = EdgeSafety.Insets(left = 60, top = 90, right = 60, bottom = 80)

    @Test
    fun `屏幕中央的点不做任何调整`() {
        val r = EdgeSafety.adjust(540, 1200, w, h, insets)
        assertFalse(r.moved)
        assertEquals(540, r.x)
        assertEquals(1200, r.y)
        assertEquals("", r.reason)
    }

    @Test
    fun `左边缘内移到手势区边界而不是被静默丢弃`() {
        val r = EdgeSafety.adjust(0, 1200, w, h, insets)
        assertTrue(r.moved)
        assertEquals(60, r.x)
        assertEquals(1200, r.y)
        assertTrue(r.reason.contains("左边缘"))
    }

    @Test
    fun `右边缘内移`() {
        val r = EdgeSafety.adjust(w - 1, 1200, w, h, insets)
        assertTrue(r.moved)
        assertEquals(w - 1 - 60, r.x)
        assertTrue(r.reason.contains("右边缘"))
    }

    @Test
    fun `底部上滑区内移到安全边界`() {
        val r = EdgeSafety.adjust(540, h - 1, w, h, insets)
        assertTrue(r.moved)
        assertEquals(h - 1 - 80, r.y)
        assertTrue(r.reason.contains("底部"))
    }

    @Test
    fun `顶部状态栏区内移`() {
        val r = EdgeSafety.adjust(540, 0, w, h, insets)
        assertTrue(r.moved)
        assertEquals(90, r.y)
        assertTrue(r.reason.contains("顶部"))
    }

    @Test
    fun `左下角两轴同时越界时都收敛到安全区`() {
        val r = EdgeSafety.adjust(0, h - 1, w, h, insets)
        assertTrue(r.moved)
        assertEquals(60, r.x)
        assertEquals(h - 1 - 80, r.y)
    }

    @Test
    fun `无手势导航时边缘坐标保持原样`() {
        // insets 全 0 表示这台设备没有系统手势热区，0 与 w-1 都是合法落点，
        // 不能因为"看起来靠边"就改动用户意图。
        val none = EdgeSafety.Insets()
        assertFalse(EdgeSafety.adjust(0, 1200, w, h, none).moved)
        assertFalse(EdgeSafety.adjust(w - 1, 1200, w, h, none).moved)
        assertFalse(EdgeSafety.adjust(540, 0, w, h, none).moved)
        assertFalse(EdgeSafety.adjust(540, h - 1, w, h, none).moved)
    }

    @Test
    fun `越界坐标先夹到屏内再判安全`() {
        val r = EdgeSafety.adjust(99999, -99999, w, h, insets)
        assertTrue(r.moved)
        assertEquals(w - 1 - 60, r.x)
        assertEquals(90, r.y)
    }

    @Test
    fun `热区过大时不产生负值或反转`() {
        // 极端 insets（两侧相加已超过屏宽）不能让安全区反向，否则 coerceIn 抛异常。
        val huge = EdgeSafety.Insets(left = 900, top = 2000, right = 900, bottom = 2000)
        val r = EdgeSafety.adjust(500, 1200, w, h, huge)
        assertTrue(r.x >= 0 && r.x <= w - 1)
        assertTrue(r.y >= 0 && r.y <= h - 1)
    }

    @Test
    fun `调整后的点一定不再落在热区`() {
        val none = EdgeSafety.Insets()
        for (x in listOf(0, 1, 59, 60, 540, w - 61, w - 60, w - 1)) {
            for (y in listOf(0, 89, 90, 1200, h - 81, h - 80, h - 1)) {
                val a = EdgeSafety.adjust(x, y, w, h, insets)
                // 用零 insets 反查：调整后的点必须已经离开 insets 热区。
                assertFalse(
                    "调整后仍在热区: ($x,$y) -> (${a.x},${a.y})",
                    a.x < insets.left || a.x > w - 1 - insets.right ||
                        a.y < insets.top || a.y > h - 1 - insets.bottom,
                )
                assertEquals(false, EdgeSafety.isRisky(a.x, a.y, w, h, none))
            }
        }
    }

    @Test
    fun `dp 换算与兜底 insets`() {
        assertEquals(12, EdgeSafety.dpToPx(4, 3f))
        assertEquals(0, EdgeSafety.dpToPx(-5, 3f))
        val f = EdgeSafety.fallback(2.75f)
        val px = EdgeSafety.dpToPx(EdgeSafety.FALLBACK_DP, 2.75f)
        assertEquals(px, f.left)
        assertEquals(px, f.bottom)
    }
}

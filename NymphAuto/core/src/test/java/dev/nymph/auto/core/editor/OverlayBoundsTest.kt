// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.19 坐标空间契约：全屏绝对坐标下的边界收敛与配置变化重定位，
 * 以纯 JVM 断言替代真机观测。
 */
class OverlayBoundsTest {

    private val portrait = OverlayBounds.Size(1080, 2400)
    private val landscape = OverlayBounds.Size(2400, 1080)

    @Test
    fun `边界尺寸必须为正值`() {
        assertEquals(1080, portrait.width)
        assertEquals(2400, portrait.height)
        assertTrue(runCatching { OverlayBounds.Size(0, 2400) }.isFailure)
        assertTrue(runCatching { OverlayBounds.Size(1080, -1) }.isFailure)
    }

    @Test
    fun `收敛上界按全屏尺寸减去窗口尺寸`() {
        val (x, y) = OverlayBounds.clamp(9999, 9999, 140, 380, portrait)
        assertEquals(1080 - 140, x)
        assertEquals(2400 - 380, y)
    }

    @Test
    fun `负坐标收敛到原点`() {
        assertEquals(0 to 0, OverlayBounds.clamp(-40, -90, 140, 380, portrait))
    }

    @Test
    fun `窗口尺寸大于屏幕时上界归零且不产生负坐标`() {
        assertEquals(0 to 0, OverlayBounds.clamp(700, 1900, 1400, 3000, portrait))
        assertEquals(0 to 0, OverlayBounds.clamp(-1, -1, 1400, 3000, portrait))
    }

    @Test
    fun `全屏边界与应用可用区边界的收敛结果不同`() {
        // 全屏 2400、应用可用区 2280。窗口高 100 且位于下缘时，
        // 使用可用区会把上界错误压到 2180，全屏真源应为 2300。
        val full = OverlayBounds.Size(1080, 2400)
        val appArea = OverlayBounds.Size(1080, 2280)
        assertEquals(0 to 2300, OverlayBounds.clamp(0, 2360, 140, 100, full))
        assertEquals(0 to 2180, OverlayBounds.clamp(0, 2360, 140, 100, appArea))
    }

    @Test
    fun `横竖屏切换按相对锚点重定位且不越界`() {
        val w = 140
        val h = 380
        val originX = 900
        val originY = 400
        val (x, y) = OverlayBounds.reposition(originX, originY, w, h, portrait, landscape)
        val (limitX, limitY) = OverlayBounds.clamp(Int.MAX_VALUE, Int.MAX_VALUE, w, h, landscape)
        assertTrue(x in 0..limitX)
        assertTrue(y in 0..limitY)
        // 竖屏 x=900/1080=0.833 → 横屏 2000；y=400/2400=0.167 → 横屏 180
        assertEquals(2000, x)
        assertEquals(180, y)
    }

    @Test
    fun `边界不变时重定位不改变坐标`() {
        val (x, y) = OverlayBounds.reposition(300, 700, 140, 380, portrait, portrait)
        assertEquals(300, x)
        assertEquals(700, y)
    }

    @Test
    fun `旋转后靠近旧边界的坐标被收敛回新屏内`() {
        val (x, y) = OverlayBounds.reposition(1050, 2350, 140, 380, portrait, landscape)
        val (limitX, limitY) = OverlayBounds.clamp(Int.MAX_VALUE, Int.MAX_VALUE, 140, 380, landscape)
        assertTrue(x <= limitX)
        assertTrue(y <= limitY)
        assertEquals(2260, x)
        assertEquals(700, y)
    }
}

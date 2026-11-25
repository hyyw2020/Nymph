// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.simpletap.SimpleTapPlan
import dev.nymph.auto.core.simpletap.SimpleTapSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.0 SimpleTap 快路径回归。
 *
 * 锁死：定点计划字段默认值与边界、时间轴严格递增且与间隔一致、
 * 无限次按 limit 收口、百分比→像素单一换算与边缘钳制、越界构造失败。
 */
class V300SimpleTapTest {

    @Test
    fun `默认计划取值`() {
        val p = SimpleTapPlan(x = 30.0, y = 70.0)
        assertEquals(500L, p.intervalMs)
        assertEquals(1, p.repeat)
        assertEquals(80L, p.holdMs)
    }

    @Test
    fun `时间轴严格递增且与间隔一致`() {
        val p = SimpleTapPlan(x = 10.0, y = 20.0, intervalMs = 200, repeat = 4)
        val ticks = SimpleTapSchedule.expand(p)
        assertEquals(4, ticks.size)
        assertEquals(listOf(0L, 200L, 400L, 600L), ticks.map { it.offsetMs })
        assertEquals(listOf(0, 1, 2, 3), ticks.map { it.index })
    }

    @Test
    fun `无限次按limit收口不产生无界列表`() {
        val p = SimpleTapPlan(x = 50.0, y = 50.0, intervalMs = 40, repeat = 0)
        val ticks = SimpleTapSchedule.expand(p, limit = 5)
        assertEquals(5, ticks.size)
        assertEquals(160L, ticks.last().offsetMs)
    }

    @Test
    fun `百分比转像素与边缘钳制`() {
        assertEquals(100 to 300, SimpleTapSchedule.resolvePixels(10.0, 30.0, 1000, 1000))
        assertEquals(0 to 0, SimpleTapSchedule.resolvePixels(-5.0, -5.0, 1000, 1000))
        assertEquals(999 to 999, SimpleTapSchedule.resolvePixels(105.0, 105.0, 1000, 1000))
    }

    @Test
    fun `holdMs低于40构造失败`() {
        var threw = false
        try {
            SimpleTapPlan(x = 50.0, y = 50.0, holdMs = 1)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `坐标越界构造失败`() {
        SimpleTapPlan(x = 101.0, y = 50.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `间隔过小构造失败`() {
        SimpleTapPlan(x = 50.0, y = 50.0, intervalMs = 10)
    }
}

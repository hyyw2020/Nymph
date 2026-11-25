// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.vision.ImageFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.31 跨帧 NCC 下界的回归防线：少于两帧 / 尺寸不一致 / 采集异常一律
 * 返回「未测」并退回单帧校准；两帧齐备时取最低分，绝不拿同帧自比的 1.00
 * 当跨帧下界。
 */
class SceneSelfTestCrossFrameTest {

    private fun solid(w: Int, h: Int, argb: Int) =
        ImageFinder.Image(w, h, IntArray(w * h) { argb })

    private val fullRoi = ImageFinder.RegionPct(0.0, 0.0, 100.0, 100.0)

    @Test
    fun `空帧列表返回未测`() {
        assertEquals(-1.0, SceneSelfTest.crossFrameFloor(emptyList(), solid(8, 8, 0xFFFFFFFF.toInt()), fullRoi), 0.0)
    }

    @Test
    fun `单帧返回未测_同帧自比不算跨帧下界`() {
        val screen = solid(64, 64, 0xFF808080.toInt())
        val anchor = solid(8, 8, 0xFF808080.toInt())
        assertEquals(-1.0, SceneSelfTest.crossFrameFloor(listOf(screen), anchor, fullRoi), 0.0)
    }

    @Test
    fun `帧尺寸不一致返回未测`() {
        val a = solid(64, 64, 0xFF808080.toInt())
        val b = solid(64, 32, 0xFF808080.toInt())
        val anchor = solid(8, 8, 0xFF808080.toInt())
        assertEquals(-1.0, SceneSelfTest.crossFrameFloor(listOf(a, b), anchor, fullRoi), 0.0)
    }

    @Test
    fun `两帧相同时下界等于单帧实测分`() {
        val screen = solid(64, 64, 0xFF808080.toInt())
        val anchor = solid(8, 8, 0xFF808080.toInt())
        val expected = ImageFinder.bestScore(screen, anchor, 0.0, 0.0, 100.0, 100.0, 0.0, 1.0)
        val floor = SceneSelfTest.crossFrameFloor(listOf(screen, screen.copyOf()), anchor, fullRoi)
        assertTrue("下界应是实测分数而非 -1", floor >= 0.0)
        assertEquals(expected, floor, 1e-9)
    }

    @Test
    fun `后一帧内容变化时下界取两帧最低分`() {
        val first = solid(64, 64, 0xFF808080.toInt())
        val second = solid(64, 64, 0xFF000000.toInt())
        val anchor = solid(8, 8, 0xFF808080.toInt())
        val firstBest = ImageFinder.bestScore(first, anchor, 0.0, 0.0, 100.0, 100.0, 0.0, 1.0)
        val floor = SceneSelfTest.crossFrameFloor(listOf(first, second), anchor, fullRoi)
        assertTrue("下界必须不高于第一帧分数", floor <= firstBest + 1e-9)
        assertTrue("下界必须是有限非负分数", floor >= 0.0)
    }

    @Test
    fun `Result新增字段默认未测且可携带实测值`() {
        val legacy = SceneSelfTest.Result(-1.0, -1.0, -1.0, -1, -1)
        assertEquals(-1.0, legacy.crossFrameFloor, 0.0)
        val measured = SceneSelfTest.Result(1.0, 0.78, 0.2, 10, 20, 0.65)
        assertEquals(0.65, measured.crossFrameFloor, 0.0)
    }

    private fun ImageFinder.Image.copyOf() = ImageFinder.Image(width, height, argb.copyOf())
}

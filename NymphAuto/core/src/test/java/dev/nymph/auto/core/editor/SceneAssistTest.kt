// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拾取辅助素材与有效区域自动收敛的纯 JVM 回归防线。 */
class SceneAssistTest {

    private fun solidPixel(x: Int, y: Int): Int? = 0xFF3366CC.toInt()

    @Test
    fun `纯色邻域采样给出代表色与最小容差`() {
        val s = SceneAssist.sampleNeighborhood(::solidPixel, 100, 100, 800, 600)
        assertTrue(s.valid)
        assertEquals(0xFF3366CC.toInt(), s.argb)
        assertEquals(0, s.spread)
        assertEquals(SceneAssist.MIN_TOLERANCE, s.tolerance)
        assertTrue(s.sampleCount > 0)
    }

    @Test
    fun `越界像素返回 null 时采样健壮收敛`() {
        val read: (Int, Int) -> Int? = { x, y ->
            if (x in 0 until 200 && y in 0 until 200) 0xFF000000.toInt() else null
        }
        val s = SceneAssist.sampleNeighborhood(read, 100, 100, 200, 200, 12)
        assertTrue(s.valid)
        assertEquals(0, s.spread)
    }

    @Test
    fun `帧尺寸非法或全无像素时样本无效`() {
        assertFalse(SceneAssist.sampleNeighborhood(::solidPixel, 0, 0, 0, 0).valid)
        val none: (Int, Int) -> Int? = { _, _ -> null }
        assertFalse(SceneAssist.sampleNeighborhood(none, 10, 10, 100, 100).valid)
    }

    @Test
    fun `离散度越高容差单调放宽但有上界`() {
        val lo = SceneAssist.toleranceFor(0)
        val mid = SceneAssist.toleranceFor(60)
        val hi = SceneAssist.toleranceFor(255)
        assertTrue(lo < mid)
        assertTrue(mid < hi)
        assertEquals(SceneAssist.MAX_TOLERANCE, hi)
        assertEquals(SceneAssist.MIN_TOLERANCE, lo)
    }

    @Test
    fun `颜色距离亮度加权且自距为零`() {
        assertEquals(0, SceneAssist.colorDistance(0xFF102030.toInt(), 0xFF102030.toInt()))
        assertTrue(SceneAssist.colorDistance(0xFFFFFFFF.toInt(), 0xFF000000.toInt()) > 200)
    }

    @Test
    fun `自动区域以拾取点为中心且夹在屏内`() {
        val sample = SceneAssist.sampleNeighborhood(::solidPixel, 400, 300, 800, 600)
        val roi = SceneAssist.convergeRoi(50.0, 50.0, sample, 800, 600)
        assertTrue(roi.leftPct >= 0.0 && roi.rightPct <= 100.0)
        assertTrue(roi.topPct >= 0.0 && roi.bottomPct <= 100.0)
        assertTrue(roi.leftPct < 50.0 && roi.rightPct > 50.0)
        assertTrue(roi.topPct < 50.0 && roi.bottomPct > 50.0)
    }

    @Test
    fun `复杂纹理自动区域不窄于纯色场景`() {
        val calm = SceneAssist.ColorSample(0, 8, 0, 0, 1)
        val busy = SceneAssist.ColorSample(0, 96, 200, 0, 1)
        val a = SceneAssist.convergeRoi(50.0, 50.0, calm, 800, 600)
        val b = SceneAssist.convergeRoi(50.0, 50.0, busy, 800, 600)
        assertTrue((b.rightPct - b.leftPct) >= (a.rightPct - a.leftPct))
    }

    @Test
    fun `手动区域优先 清除后回退自动区域`() {
        val auto = SceneAssist.convergeRoi(50.0, 50.0,
            SceneAssist.sampleNeighborhood(::solidPixel, 400, 300, 800, 600), 800, 600)
        val manual = Region(10.0, 10.0, 20.0, 20.0)
        val overridden = SceneAssist.resolveRoi(auto, manual)
        assertEquals(SceneAssist.RoiMode.MANUAL, overridden.mode)
        assertTrue(overridden.overridden)
        assertEquals(manual, overridden.region)
        val fallback = SceneAssist.resolveRoi(auto, null)
        assertEquals(SceneAssist.RoiMode.AUTO, fallback.mode)
        assertFalse(fallback.overridden)
        assertEquals(auto, fallback.region)
    }

    @Test
    fun `空手动区域不产生覆盖`() {
        val auto = Region(40.0, 40.0, 60.0, 60.0)
        val degenerate = Region(50.0, 50.0, 50.0, 50.0)
        val r = SceneAssist.resolveRoi(auto, degenerate)
        assertEquals(SceneAssist.RoiMode.AUTO, r.mode)
        assertEquals(auto, r.region)
    }

    @Test
    fun `区域包含判定与空区域判定`() {
        val r = Region(10.0, 10.0, 30.0, 30.0)
        assertTrue(SceneAssist.contains(r, 20.0, 20.0))
        assertFalse(SceneAssist.contains(r, 40.0, 20.0))
        assertTrue(SceneAssist.isEmptyRoi(null))
        assertTrue(SceneAssist.isEmptyRoi(Region(50.0, 50.0, 50.0, 50.0)))
        assertFalse(SceneAssist.isEmptyRoi(r))
    }
}

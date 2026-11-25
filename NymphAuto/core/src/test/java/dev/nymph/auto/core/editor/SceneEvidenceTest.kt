// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.MaskRect
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.31 装配-运行同源判据回归防线：帧几何闭环、收敛区域、掩码覆盖、
 * 拾取点反推、分层判据的四条否决路径。全部纯 JVM，不依赖截图或时钟。
 */
class SceneEvidenceTest {

    private fun roi(l: Double, t: Double, r: Double, b: Double) = Region(l, t, r, b, "")

    private fun evidence(
        color: Int = 0xFF2A3444.toInt(),
        tol: Int = 35,
        roi: Region? = Region(79.0, 80.0, 83.0, 89.0, ""),
        streak: Int = 3,
    ) = SceneEvidence.Evidence(color, tol, roi, 2800, 1280, streak)

    private fun frame(
        hit: Boolean = false,
        score: Double = 0.65,
        best: Pair<Int, Int> = 2322 to 1050,
        observed: Int = 0xFF2B3545.toInt(),
        streak: Int = 3,
        geometryOk: Boolean = true,
    ) = SceneEvidence.Frame(
        hit = hit,
        score = score,
        cxPx = -1,
        cyPx = -1,
        bestCxPx = best.first,
        bestCyPx = best.second,
        frameW = 2800,
        frameH = 1280,
        observedArgb = observed,
        suspectStreak = streak,
        geometryOk = geometryOk,
    )

    // ------------------------------------------------------------ 帧几何闭环

    @Test
    fun `同尺寸方向比一致`() {
        assertTrue(SceneEvidence.geometryMatches(2800, 1280, 2800, 1280))
    }

    @Test
    fun `同方向缩放后方向比仍一致`() {
        // 1400×640 是 2800×1280 的等比缩放，方向比一致
        assertTrue(SceneEvidence.geometryMatches(2800, 1280, 1400, 640))
    }

    @Test
    fun `旋转90度判方向错配`() {
        assertFalse(SceneEvidence.geometryMatches(2800, 1280, 1280, 2800))
    }

    @Test
    fun `源尺寸缺失时不校验零回归`() {
        assertTrue(SceneEvidence.geometryMatches(0, 0, 1280, 2800))
        assertTrue(SceneEvidence.geometryMatches(2800, 1280, 0, 0))
    }

    // ------------------------------------------------------------ 收敛区域

    @Test
    fun `最佳窗口落在收敛区域内`() {
        // 2322/2800=82.9%、1050/1280=82.0%，落在 79~83 / 80~89
        assertTrue(SceneEvidence.inRoi(Region(79.0, 80.0, 83.0, 89.0, ""), 2322, 1050, 2800, 1280))
    }

    @Test
    fun `最佳窗口在收敛区域外被否决`() {
        assertFalse(SceneEvidence.inRoi(Region(10.0, 10.0, 20.0, 20.0, ""), 2322, 1050, 2800, 1280))
    }

    @Test
    fun `空区域或非法坐标一律否决`() {
        assertFalse(SceneEvidence.inRoi(null, 2322, 1050, 2800, 1280))
        assertFalse(SceneEvidence.inRoi(Region(0.0, 0.0, 0.0, 0.0, ""), 2322, 1050, 2800, 1280))
        assertFalse(SceneEvidence.inRoi(Region(79.0, 80.0, 83.0, 89.0, ""), -1, 1050, 2800, 1280))
    }

    // ------------------------------------------------------------ 掩码覆盖

    @Test
    fun `掩码盖住0块时计数为0供运行期跳过抑制`() {
        val rects = listOf(MaskRect(10.0, 10.0, 20.0, 20.0), MaskRect(30.0, 30.0, 40.0, 40.0))
        assertEquals(0, SceneEvidence.maskCovers(rects, 2322, 1050, 2800, 1280))
    }

    @Test
    fun `掩码盖住N块时返回实际块数`() {
        val rects = listOf(
            MaskRect(80.0, 80.0, 85.0, 86.0),
            MaskRect(82.0, 81.0, 84.0, 85.0),
            MaskRect(10.0, 10.0, 20.0, 20.0),
        )
        assertEquals(2, SceneEvidence.maskCovers(rects, 2322, 1050, 2800, 1280))
    }

    @Test
    fun `空掩码列表恒为0`() {
        assertEquals(0, SceneEvidence.maskCovers(emptyList(), 2322, 1050, 2800, 1280))
    }

    // ------------------------------------------------------------ 拾取点反推

    @Test
    fun `锚点中心加相对偏移还原拾取点`() {
        val rule = SceneRule(
            anchorRectPct = roi(78.0, 81.0, 82.0, 87.0),
            clickOffsetXPct = 0.1,
            clickOffsetYPct = 0.6,
        )
        val (x, y) = SceneEvidence.recoverPickPoint(rule)
        assertEquals(80.1, x, 1e-9)
        assertEquals(84.6, y, 1e-9)
    }

    @Test
    fun `无相对偏移时拾取点未知不采样`() {
        val (x, y) = SceneEvidence.recoverPickPoint(SceneRule())
        assertEquals(-1.0, x, 0.0)
        assertEquals(-1.0, y, 0.0)
    }

    // ------------------------------------------------------------ 分层判据

    @Test
    fun `NCC命中优先于分层且直接放行`() {
        val f = frame(hit = true, score = 0.94).copy(cxPx = 2294, cyPx = 1056)
        val v = SceneEvidence.assess(evidence(), f, 0.87)
        assertTrue(v.present)
        assertEquals(SceneEvidence.Tier.NCC, v.tier)
        assertEquals(2294, v.cxPx)
        assertFalse(v.suspect)
    }

    @Test
    fun `方向错配一票否决且不产生落点`() {
        val v = SceneEvidence.assess(
            evidence(), frame(hit = true, geometryOk = false).copy(cxPx = 2294, cyPx = 1056), 0.87,
        )
        assertFalse(v.present)
        assertEquals(SceneEvidence.Tier.NONE, v.tier)
        assertEquals(-1, v.cxPx)
        assertTrue(v.reason.contains("方向错配"))
    }

    @Test
    fun `四项证据齐备时分层放行并用最佳窗口中心`() {
        val v = SceneEvidence.assess(evidence(), frame(), 0.87)
        assertTrue(v.present)
        assertEquals(SceneEvidence.Tier.LAYERED, v.tier)
        assertEquals(2322, v.cxPx)
        assertEquals(1050, v.cyPx)
        assertTrue(v.colorOk && v.roiOk && v.stableOk)
        assertTrue(v.reason.contains("场景出现"))
    }

    @Test
    fun `证据不完整时回退单一NCC判据不放行`() {
        val incomplete = evidence(color = 0)
        val v = SceneEvidence.assess(incomplete, frame(), 0.87)
        assertFalse(v.present)
        assertEquals(SceneEvidence.Tier.NONE, v.tier)
        assertTrue(v.suspect)
    }

    @Test
    fun `取色超出容差被否决`() {
        // 目标 #2A3444，观测取纯白 → 亮度加权距离远大于 35
        val v = SceneEvidence.assess(evidence(), frame(observed = 0xFFFFFFFF.toInt()), 0.87)
        assertFalse(v.present)
        assertFalse(v.colorOk)
        assertTrue(v.roiOk && v.stableOk)
    }

    @Test
    fun `分层判据中最佳窗口落在收敛区域外被否决`() {
        val v = SceneEvidence.assess(evidence(), frame(best = 100 to 100), 0.87)
        assertFalse(v.present)
        assertFalse(v.roiOk)
    }

    @Test
    fun `连续帧数不足被否决`() {
        val v = SceneEvidence.assess(evidence(), frame(streak = 2), 0.87)
        assertFalse(v.present)
        assertFalse(v.stableOk)
        assertTrue(v.colorOk && v.roiOk)
    }

    @Test
    fun `低于疑似下界不进入分层`() {
        val v = SceneEvidence.assess(evidence(), frame(score = 0.30), 0.87)
        assertFalse(v.present)
        assertFalse(v.suspect)
        assertEquals(SceneEvidence.Tier.NONE, v.tier)
    }

    @Test
    fun `达到阈值却未命中不属于疑似在场`() {
        assertFalse(SceneEvidence.isSuspect(false, 0.90, 0.87))
        assertTrue(SceneEvidence.isSuspect(false, 0.65, 0.87))
        assertFalse(SceneEvidence.isSuspect(true, 0.94, 0.87))
    }

    @Test
    fun `分层放行仍带出有效中心供落点偏差校验`() {
        val v = SceneEvidence.assess(evidence(), frame(), 0.87)
        assertTrue("分层放行必须给 resolveSceneClick 一个中心，否则又退化成默认坐标", v.cxPx >= 0)
        assertTrue(v.cyPx >= 0)
    }

    @Test
    fun `证据可用性要求颜色容差与收敛区域同时齐备`() {
        assertTrue(evidence().usable)
        assertFalse(evidence(color = 0).usable)
        assertFalse(evidence(tol = 0).usable)
        assertFalse(evidence(roi = null).usable)
        assertFalse(evidence(roi = Region(0.0, 0.0, 0.0, 0.0, "")).usable)
        assertFalse(evidence(streak = 0).usable)
    }
}

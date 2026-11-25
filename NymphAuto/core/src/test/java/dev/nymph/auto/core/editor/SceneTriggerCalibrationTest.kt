// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneClickResolution
import dev.nymph.auto.core.model.SceneObservation
import dev.nymph.auto.core.model.SceneRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.31 两条修复的回归防线：
 *  1. 阈值校准同时受「空间干扰分」与「跨帧 NCC 下界」约束，且保留安全下限；
 *  2. 相对坐标语义下 `loc=-` 绝不回退 50/50，优先沿用上次有效落点，否则不派发。
 */
class SceneTriggerCalibrationTest {

    // ------------------------------------------------------------ 阈值校准

    @Test
    fun `旧单帧逻辑逐字保持_干扰分0_78得86`() {
        assertEquals(86, SceneTrigger.calibrateSimilarity(0.78))
    }

    @Test
    fun `旧单帧逻辑逐字保持_未测回退基线82`() {
        assertEquals(82, SceneTrigger.calibrateSimilarity(-1.0))
        assertEquals(82, SceneTrigger.calibrateSimilarity(Double.NaN))
    }

    @Test
    fun `跨帧下界0_65把阈值压到62`() {
        // spatial=87；cap = 65 - 3 = 62；取较小值 → 62
        assertEquals(62, SceneTrigger.calibrateSimilarity(0.78, crossFrameFloor = 0.65))
    }

    @Test
    fun `跨帧下界很高时不抬高阈值`() {
        // cap = 95 - 3 = 92 > spatial 86 → 仍为 86
        assertEquals(86, SceneTrigger.calibrateSimilarity(0.78, crossFrameFloor = 0.95))
    }

    @Test
    fun `跨帧下界极低时守住安全下限55`() {
        // cap = 30 - 3 = 27 → 抬到 55，不把阈值压到会放行误匹配的程度
        assertEquals(
            SceneEvidence.MIN_SAFE_SIMILARITY,
            SceneTrigger.calibrateSimilarity(0.78, crossFrameFloor = 0.30),
        )
    }

    @Test
    fun `跨帧下界未测或非法时退回旧逻辑`() {
        assertEquals(86, SceneTrigger.calibrateSimilarity(0.78, crossFrameFloor = -1.0))
        assertEquals(86, SceneTrigger.calibrateSimilarity(0.78, crossFrameFloor = 1.2))
        assertEquals(86, SceneTrigger.calibrateSimilarity(0.78, crossFrameFloor = Double.NaN))
    }

    @Test
    fun `跨帧约束对基线同样生效`() {
        // 干扰分未测 spatial=82，跨帧下界 0.70 → cap=67 → 67
        assertEquals(67, SceneTrigger.calibrateSimilarity(-1.0, crossFrameFloor = 0.70))
    }

    // ------------------------------------------------------------ 重试落点不退化

    private fun relativeRule() = SceneRule(
        anchorRectPct = Region(78.0, 81.0, 82.0, 87.0, ""),
        clickOffsetXPct = 0.1,
        clickOffsetYPct = 0.6,
    )

    private fun valid(cx: Int = 2294, cy: Int = 1056) =
        SceneObservation(true, 0.94, cx, cy, 2800, 1280)

    private fun invalid() = SceneObservation(false, 0.65, -1, -1, 2800, 1280)

    @Test
    fun `本帧有有效中心走相对匹配坐标`() {
        val r = SceneTrigger.resolveSceneClick(valid(), relativeRule(), 50.0, 50.0)
        assertTrue(r is SceneClickResolution.Hit)
        val hit = r as SceneClickResolution.Hit
        assertEquals("相对匹配坐标", hit.via)
        // 2294/2800=81.9% + 0.1 ≈ 82.0；1056/1280=82.5% + 0.6 ≈ 83.1
        assertTrue(hit.xPct > 50.0 && hit.yPct > 50.0)
    }

    @Test
    fun `本帧无中心但有上次有效落点时沿用且不回退默认坐标`() {
        val r = SceneTrigger.resolveSceneClick(invalid(), relativeRule(), 50.0, 50.0, valid())
        assertTrue(r is SceneClickResolution.Hit)
        val hit = r as SceneClickResolution.Hit
        assertEquals("沿用上次有效落点", hit.via)
        assertTrue("落点必须接近上次有效位置而非屏幕正中央", hit.xPct > 70.0)
    }

    @Test
    fun `本帧与上次都无有效中心时明确不派发`() {
        val r = SceneTrigger.resolveSceneClick(invalid(), relativeRule(), 50.0, 50.0, null, true)
        assertTrue(r is SceneClickResolution.NoValidLocation)
    }

    @Test
    fun `传入无效的上次落点等同于没有`() {
        val r = SceneTrigger.resolveSceneClick(invalid(), relativeRule(), 50.0, 50.0, invalid(), true)
        assertTrue(r is SceneClickResolution.NoValidLocation)
    }

    @Test
    fun `严格模式关闭时loc无效仍回退绝对坐标_老语义零回归`() {
        val r = SceneTrigger.resolveSceneClick(invalid(), relativeRule(), 50.0, 50.0, null)
        assertTrue(r is SceneClickResolution.Absolute)
    }

    @Test
    fun `老绝对坐标格式逐字零回归`() {
        val r = SceneTrigger.resolveSceneClick(invalid(), SceneRule(), 50.0, 50.0, null)
        assertTrue(r is SceneClickResolution.Absolute)
        val abs = r as SceneClickResolution.Absolute
        assertEquals(50.0, abs.xPct, 0.0)
        assertEquals(50.0, abs.yPct, 0.0)
    }

    @Test
    fun `沿用上次落点仍经过偏差二次校验`() {
        // 上次落点偏离锚点中心过远 → 判疑似错位，绝不放行
        val far = valid(100, 100)
        val r = SceneTrigger.resolveSceneClick(invalid(), relativeRule(), 50.0, 50.0, far)
        assertTrue(r is SceneClickResolution.SuspiciousMismatch)
    }
}

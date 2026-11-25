// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import dev.nymph.auto.core.design.SceneBoard.Observation
import dev.nymph.auto.core.design.SceneBoard.OrientationBinding
import dev.nymph.auto.core.design.SceneBoard.Feature
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Region
import org.junit.Assert.*
import org.junit.Test

/** 场景画板判定单测：锁定用户 4 条批评对应的语义，纯 JVM 无 Android 依赖。 */
class SceneBoardTest {

    private fun feat(id: String, sim: Int = 85) = Feature(id, "特征$id", Region(), sim)

    // ---- 诉求 1：横竖屏误点——方向绑定 fail-closed ----
    @Test
    fun `竖屏画板在横屏整板失活`() {
        assertFalse(SceneBoard.orientationAllows(OrientationBinding.PORTRAIT, 1))
        assertFalse(SceneBoard.orientationAllows(OrientationBinding.PORTRAIT, 3))
        assertTrue(SceneBoard.orientationAllows(OrientationBinding.PORTRAIT, 0))
        assertTrue(SceneBoard.orientationAllows(OrientationBinding.PORTRAIT, 2))
    }

    @Test
    fun `横屏画板在竖屏整板失活`() {
        assertFalse(SceneBoard.orientationAllows(OrientationBinding.LANDSCAPE, 0))
        assertFalse(SceneBoard.orientationAllows(OrientationBinding.LANDSCAPE, 2))
        assertTrue(SceneBoard.orientationAllows(OrientationBinding.LANDSCAPE, 1))
    }

    @Test
    fun `绑定方向不符时不触发且给可读原因`() {
        val v = SceneBoard.evaluate(
            bind = OrientationBinding.PORTRAIT,
            currentRotation = 1, // 横屏
            features = listOf(feat("a")),
            rawObservations = listOf(Observation("a", 0.99)),
            combine = Combine.ANY, minCount = 1,
            desiredX = 50.0, desiredY = 50.0, clickZone = Region(),
        )
        assertTrue(v.reason.contains("方向不符"))
        assertFalse(v.triggered)
    }

    @Test
    fun `ANY 绑定恒允许老脚本零回归`() {
        (0 until 4).forEach { assertTrue(SceneBoard.orientationAllows(OrientationBinding.ANY, it)) }
    }

    // ---- 诉求 2：多图同时出现才触发——ALL 组合 ----
    @Test
    fun `ALL 必须全部特征命中才触发`() {
        val features = listOf(feat("a"), feat("b"), feat("c"), feat("d"))
        // 只命中 3 个：不触发
        val partial = SceneBoard.combineHits(
            features.map { it.id }, listOf("a", "b", "c"), Combine.ALL,
        )
        assertTrue(partial.isEmpty())
        // 全命中 4 个：触发
        val full = SceneBoard.combineHits(
            features.map { it.id }, listOf("a", "b", "c", "d"), Combine.ALL,
        )
        assertEquals(4, full.size)
    }

    @Test
    fun `阈值折算后再组合`() {
        val features = listOf(feat("a", 85), feat("b", 90))
        val raw = listOf(
            Observation("a", 0.90), // 90 >= 85 命中
            Observation("b", 0.86), // 86 < 90 未命中
        )
        val passed = SceneBoard.applyThresholds(raw, features)
        assertEquals(1, passed.size)
        assertEquals("a", passed.first().featureId)
        val fired = SceneBoard.combineHits(features.map { it.id }, passed.map { it.featureId }, Combine.ALL)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `ANY 与 N 组合语义`() {
        val ids = listOf("a", "b", "c")
        assertEquals(1, SceneBoard.combineHits(ids, listOf("b"), Combine.ANY).size)
        assertTrue(SceneBoard.combineHits(ids, listOf("b"), Combine.N, 2).isEmpty())
        assertEquals(2, SceneBoard.combineHits(ids, listOf("a", "c"), Combine.N, 2).size)
    }

    // ---- 诉求 3：落点限制区——坐标约束不静默 ----
    @Test
    fun `落点越界钳到限制区边界`() {
        val zone = Region(20.0, 20.0, 60.0, 60.0)
        val (x, y) = SceneBoard.clampClick(90.0, 10.0, zone)
        assertEquals(60.0, x, 0.0001)
        assertEquals(20.0, y, 0.0001)
        assertFalse(SceneBoard.inClickZone(90.0, 10.0, zone))
        assertTrue(SceneBoard.inClickZone(40.0, 40.0, zone))
    }

    @Test
    fun `全屏限制区不约束落点`() {
        val (x, y) = SceneBoard.clampClick(73.5, 42.1, Region())
        assertEquals(73.5, x, 0.0001)
        assertEquals(42.1, y, 0.0001)
    }

    // ---- 诉求 4：阈值判定带——目标 vs 干扰可解释 ----
    @Test
    fun `可分场景给出中间建议阈值`() {
        val band = SceneBoard.thresholdBand(0.93, 0.71)
        assertTrue(band.separable)
        assertEquals(74, band.suggested) // 71+3=74，未超 93-3=90，故取 74
        assertTrue(band.advice.contains("目标"))
        assertTrue(band.advice.contains("干扰"))
    }

    @Test
    fun `不可分场景拒绝给阈值并提示重框`() {
        val band = SceneBoard.thresholdBand(0.76, 0.72)
        assertFalse(band.separable)
        assertTrue(band.advice.contains("重新框"))
    }

    @Test
    fun `无干扰样本沿用默认阈值`() {
        val band = SceneBoard.thresholdBand(0.90, -1.0)
        assertEquals(SceneBoard.DEFAULT_SIMILARITY, band.suggested)
        assertTrue(band.separable)
    }

    // ---- 整板判定：AND + 方向 + 落点一次算清 ----
    @Test
    fun `整板判定四特征齐了才在绑定方向触发`() {
        val features = listOf(feat("a"), feat("b"), feat("c"), feat("d"))
        val zone = Region(0.0, 0.0, 100.0, 100.0)
        val v = SceneBoard.evaluate(
            bind = OrientationBinding.LANDSCAPE, currentRotation = 1,
            features = features,
            rawObservations = listOf(
                Observation("a", 0.95), Observation("b", 0.93),
                Observation("c", 0.91), Observation("d", 0.90),
            ),
            combine = Combine.ALL, minCount = 4,
            desiredX = 55.0, desiredY = 45.0, clickZone = zone,
        )
        assertTrue(v.active)
        assertTrue(v.triggered)
        assertEquals(4, v.firedFeatureIds.size)
        assertTrue(v.reason.contains("4/4"))
    }
}

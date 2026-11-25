// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.SceneRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SceneVerdict] 判定层单测（core 纯 JVM）。
 *
 * 覆盖四态结论、判定优先级（按失败代价排序）、位置偏差预算可覆盖、主结论与
 * 技术明细的呈现分层、未测数值的占位符格式化。断言不依赖任何 Android 类型。
 */
class SceneVerdictTest {

    private fun result(best: Double, runnerUp: Double, dev: Double) =
        SceneSelfTest.Result(best, runnerUp, dev, 100, 200)

    @Test
    fun `未测结果判画面不可用`() {
        val a = SceneVerdict.ofUnmeasured()
        assertEquals(SceneVerdict.Kind.NO_FRAME, a.kind)
        assertFalse(a.usable)
        assertTrue(a.recommendRetry)
        assertNotNull(a.action)
    }

    @Test
    fun `低分画面判画面不可用`() {
        val a = SceneVerdict.assess(result(0.30, 0.10, 2.0), SceneRule.SIMILARITY_BASELINE)
        assertEquals(SceneVerdict.Kind.NO_FRAME, a.kind)
        assertFalse(a.usable)
        assertTrue(a.recommendRetry)
    }

    @Test
    fun `区分度不足判低区分度`() {
        val best = 0.80
        val runnerUp = best - SceneRule.CALIBRATION_MARGIN + 0.01
        val a = SceneVerdict.assess(result(best, runnerUp, 2.0), SceneRule.SIMILARITY_BASELINE)
        assertEquals(SceneVerdict.Kind.LOW_DISTINCTNESS, a.kind)
        assertFalse(a.usable)
        assertTrue(a.recommendRetry)
    }

    @Test
    fun `位置偏差超预算判落点不可信`() {
        val a = SceneVerdict.assess(result(0.95, 0.10, 30.0), SceneRule.SIMILARITY_BASELINE)
        assertEquals(SceneVerdict.Kind.LOCATION_DEVIATION, a.kind)
        assertFalse(a.usable)
    }

    @Test
    fun `位置偏差未测判落点不可信`() {
        val a = SceneVerdict.assess(result(0.95, 0.10, -1.0), SceneRule.SIMILARITY_BASELINE)
        assertEquals(SceneVerdict.Kind.LOCATION_DEVIATION, a.kind)
        assertFalse(a.usable)
    }

    @Test
    fun `各项达标判通过`() {
        val a = SceneVerdict.assess(result(0.95, 0.10, 2.0), 90)
        assertEquals(SceneVerdict.Kind.PASS, a.kind)
        assertTrue(a.usable)
        assertFalse(a.recommendRetry)
        assertNull(a.action)
    }

    @Test
    fun `主结论不呈现原始分数而明细保留技术数据`() {
        val a = SceneVerdict.assess(result(0.95, 0.10, 2.0), 90)
        assertFalse(a.headline.contains("0.95"))
        assertFalse(a.headline.contains("%"))
        assertTrue(a.detail.contains("0.95"))
        assertTrue(a.detail.contains("90"))
    }

    @Test
    fun `判定优先级按失败代价排序`() {
        // 同时满足低分 / 低区分度 / 位置偏差时，先判画面不可用
        val a = SceneVerdict.assess(result(0.20, 0.19, 99.0), SceneRule.SIMILARITY_BASELINE)
        assertEquals(SceneVerdict.Kind.NO_FRAME, a.kind)
        // 画面可用但区分度不足且偏差超预算时，先判区分度不足
        val b = SceneVerdict.assess(result(0.80, 0.79, 99.0), SceneRule.SIMILARITY_BASELINE)
        assertEquals(SceneVerdict.Kind.LOW_DISTINCTNESS, b.kind)
    }

    @Test
    fun `位置偏差预算可由规则覆盖`() {
        val strict = SceneVerdict.assess(result(0.95, 0.10, 8.0), 90, maxDeviationPct = 5.0)
        assertEquals(SceneVerdict.Kind.LOCATION_DEVIATION, strict.kind)
        val relaxed = SceneVerdict.assess(result(0.95, 0.10, 8.0), 90, maxDeviationPct = 15.0)
        assertEquals(SceneVerdict.Kind.PASS, relaxed.kind)
    }

    @Test
    fun `未测数值格式化为占位符且不呈现负数`() {
        val a = SceneVerdict.ofUnmeasured()
        assertFalse(a.detail.contains("-1.00"))
        assertTrue(a.detail.contains("未测"))
    }
}

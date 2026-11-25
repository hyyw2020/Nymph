// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 点击前门控 fail-closed 与异步代际隔离的纯 JVM 回归防线。 */
class TapGateTest {

    private val roi = Region(40.0, 40.0, 60.0, 60.0)
    private val sample = SceneAssist.ColorSample(0xFF3366CC.toInt(), 24, 0, 0, 1)

    @Test
    fun `判定中阶段不派发且给出判定中结论`() {
        val v = TapGate.evaluate(TapGate.Phase.PENDING, roi, 50.0, 50.0, sample, null)
        assertFalse(v.allowTap)
        assertEquals(TapGate.Reason.PENDING, v.reason)
    }

    @Test
    fun `异步失败按失败即停不派发`() {
        val v = TapGate.evaluate(TapGate.Phase.FAILED, roi, 50.0, 50.0, sample, 0xFF3366CC.toInt())
        assertFalse(v.allowTap)
        assertEquals(TapGate.Reason.ASYNC_FAILURE, v.reason)
    }

    @Test
    fun `区域为空或缺失不派发`() {
        assertFalse(TapGate.evaluate(TapGate.Phase.SETTLED, null, 50.0, 50.0, sample,
            0xFF3366CC.toInt()).allowTap)
        val empty = TapGate.evaluate(TapGate.Phase.SETTLED,
            Region(50.0, 50.0, 50.0, 50.0), 50.0, 50.0, sample, 0xFF3366CC.toInt())
        assertEquals(TapGate.Reason.EMPTY_ROI, empty.reason)
        assertFalse(empty.allowTap)
    }

    @Test
    fun `拾取点越界不派发`() {
        val v = TapGate.evaluate(TapGate.Phase.SETTLED, roi, 90.0, 90.0, sample,
            0xFF3366CC.toInt())
        assertEquals(TapGate.Reason.OUT_OF_BOUNDS, v.reason)
        assertFalse(v.allowTap)
    }

    @Test
    fun `颜色超出容差不派发`() {
        val v = TapGate.evaluate(TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample,
            0xFF000000.toInt(), tolerance = 24)
        assertEquals(TapGate.Reason.COLOR_MISMATCH, v.reason)
        assertFalse(v.allowTap)
    }

    @Test
    fun `区域内颜色匹配才允许触发`() {
        val v = TapGate.evaluate(TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample,
            0xFF3366CC.toInt(), tolerance = 24)
        assertEquals(TapGate.Reason.PASS, v.reason)
        assertTrue(v.allowTap)
        assertTrue(v.hit)
    }

    @Test
    fun `样本或观测缺失一律不派发`() {
        assertFalse(TapGate.evaluate(TapGate.Phase.SETTLED, roi, 50.0, 50.0, null,
            0xFF3366CC.toInt()).allowTap)
        assertFalse(TapGate.evaluate(TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample,
            null).allowTap)
    }

    @Test
    fun `仅 PASS 允许点击 其余原因一律 fail-closed`() {
        for (r in TapGate.Reason.values()) {
            val allow = TapGate.Verdict(r, r == TapGate.Reason.PASS,
                TapGate.messageFor(r)).allowTap
            assertEquals(r == TapGate.Reason.PASS, allow)
        }
    }

    @Test
    fun `代际令牌推进后旧任务结果被判为过期`() {
        val gen = TapGate.Generation()
        val first = gen.advance()
        assertTrue(gen.isCurrent(first))
        val second = gen.advance()
        assertFalse(gen.isCurrent(first))
        assertTrue(gen.isCurrent(second))
    }

    @Test
    fun `取消在途任务后旧代次不再覆盖新状态`() {
        val gen = TapGate.Generation()
        val inFlight = gen.advance()
        gen.cancel()
        assertFalse(gen.isCurrent(inFlight))
    }

    @Test
    fun `连续拾取后仅最新代次可回填`() {
        val gen = TapGate.Generation()
        val g1 = gen.advance()
        val g2 = gen.advance()
        val g3 = gen.advance()
        assertFalse(gen.isCurrent(g1))
        assertFalse(gen.isCurrent(g2))
        assertTrue(gen.isCurrent(g3))
    }

    @Test
    fun `初始代次零不视为可回填`() {
        val gen = TapGate.Generation()
        assertFalse(gen.isCurrent(0))
    }
}

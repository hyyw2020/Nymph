// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.video.ReplayFalsifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.47：离线回放证伪器纯 JVM 回归（不碰真机、无需模型）。 */
class V2947ReplayFalsifierTest {

    private fun frames() = listOf(
        ReplayFalsifier.FrameObs(0L, deltaEnergy = 0.0, matchScore = -1.0, sceneHash = 1L),
        ReplayFalsifier.FrameObs(100L, deltaEnergy = 0.0, matchScore = 0.30, sceneHash = 1L),
        ReplayFalsifier.FrameObs(300L, deltaEnergy = 0.0, matchScore = 0.95,
            texts = setOf("登录成功"), sceneHash = 2L),
        ReplayFalsifier.FrameObs(500L, deltaEnergy = 0.8, matchScore = 0.10, sceneHash = 3L),
        ReplayFalsifier.FrameObs(900L, deltaEnergy = 0.0, matchScore = 0.10, sceneHash = 4L),
    )

    @Test
    fun `前置从未满足的规则被判不可执行并丢弃`() {
        val rule = ReplayFalsifier.Rule(
            id = "r1", actionTMs = 600L,
            pre = ReplayFalsifier.Precondition.AppearImage(0.99),
        )
        val v = ReplayFalsifier.falsify(frames(), listOf(rule)).single()
        assertFalse(v.executable)
        assertEquals(0.0, v.probability, 0.001)
        assertTrue(v.needsReview)
        assertNull(v.preFirstSatisfiedTMs)
        assertTrue(v.reason.contains("从未满足"))
    }

    @Test
    fun `前置对齐且后置通过得高可执行概率`() {
        val rule = ReplayFalsifier.Rule(
            id = "r2", actionTMs = 400L,
            pre = ReplayFalsifier.Precondition.AppearImage(0.90),
            post = ReplayFalsifier.PostAssert.DeltaImage(0.5),
            tolerateBeforeMs = 200L, postWindowMs = 300L,
        )
        val v = ReplayFalsifier.falsify(frames(), listOf(rule)).single()
        assertTrue(v.executable)
        assertEquals(300L, v.preFirstSatisfiedTMs)
        // 前置在容忍窗口内（提前 100ms ≤ 200ms）→ 1.0；后置 500ms 帧差 0.8 ≥ 0.5 → 通过。
        assertEquals(1.0, v.probability, 0.001)
        assertFalse(v.needsReview)
        assertEquals(500L, v.postPassedTMs)
    }

    @Test
    fun `前置满足时机超出容忍窗口则概率下降并待确认`() {
        val rule = ReplayFalsifier.Rule(
            id = "r3", actionTMs = 900L,
            pre = ReplayFalsifier.Precondition.AppearImage(0.90),
            post = ReplayFalsifier.PostAssert.None,
            tolerateBeforeMs = 100L,
        )
        val v = ReplayFalsifier.falsify(frames(), listOf(rule)).single()
        assertTrue(v.executable)
        // 前置 300ms 满足，动作 900ms，提前 600ms 远超 100ms 容忍 → 前置分被压到 0。
        assertTrue("probability=${v.probability}", v.probability < ReplayFalsifier.REVIEW_THRESHOLD)
        assertTrue(v.needsReview)
    }

    @Test
    fun `后置断言在窗口内未通过标记待确认`() {
        val rule = ReplayFalsifier.Rule(
            id = "r4", actionTMs = 350L,
            pre = ReplayFalsifier.Precondition.AppearImage(0.90),
            post = ReplayFalsifier.PostAssert.DeltaImage(0.99),
            tolerateBeforeMs = 200L, postWindowMs = 100L,
        )
        val v = ReplayFalsifier.falsify(frames(), listOf(rule)).single()
        assertTrue(v.executable)
        assertNull(v.postPassedTMs)
        assertTrue(v.needsReview)
    }

    @Test
    fun `文本判据与场景变化后置均可证伪`() {
        val rule = ReplayFalsifier.Rule(
            id = "r5", actionTMs = 350L,
            pre = ReplayFalsifier.Precondition.TextPresent("登录成功"),
            post = ReplayFalsifier.PostAssert.SceneChanged,
            tolerateBeforeMs = 100L, postWindowMs = 300L,
        )
        val v = ReplayFalsifier.falsify(frames(), listOf(rule)).single()
        assertTrue(v.executable)
        assertEquals(300L, v.preFirstSatisfiedTMs)
        // 动作帧 300ms 场景哈希 2，后置窗口内 500ms 哈希 3 → 场景变化通过。
        assertNotNull(v.postPassedTMs)
        assertEquals(500L, v.postPassedTMs)
        assertFalse(v.needsReview)
    }

    @Test
    fun `DeltaImage 稳定帧判据要求连续达标`() {
        val noisy = listOf(
            ReplayFalsifier.FrameObs(0L, deltaEnergy = 0.9),
            ReplayFalsifier.FrameObs(100L, deltaEnergy = 0.1),
            ReplayFalsifier.FrameObs(200L, deltaEnergy = 0.9),
        )
        val rule = ReplayFalsifier.Rule(
            id = "r6", actionTMs = 250L,
            pre = ReplayFalsifier.Precondition.DeltaImage(0.5, stableFrames = 2),
        )
        // 没有连续 2 帧达标 → 前置从未满足 → 不可执行。
        assertFalse(ReplayFalsifier.falsify(noisy, listOf(rule)).single().executable)
    }

    @Test
    fun `无条件前置与无后置断言旧脚本零回归`() {
        val rule = ReplayFalsifier.Rule(id = "legacy", actionTMs = 100L)
        val v = ReplayFalsifier.falsify(frames(), listOf(rule)).single()
        assertTrue(v.executable)
        assertEquals(1.0, v.probability, 0.001)
        assertFalse(v.needsReview)
    }

    @Test
    fun `无帧观测时全部规则判不可执行不崩溃`() {
        val rule = ReplayFalsifier.Rule(id = "empty", actionTMs = 100L)
        val v = ReplayFalsifier.falsify(emptyList(), listOf(rule)).single()
        assertFalse(v.executable)
        assertTrue(v.needsReview)
    }

    @Test
    fun `候选批量证伪串联观测构造且不崩溃`() {
        // 构造帧序列：0 平、100 局部变化、200 场景变、300 局部变化。
        val frames = listOf(
            dev.nymph.auto.core.video.TapCandidateDetector.FrameSample(0L, 8, 8, IntArray(64) { 10 }),
            dev.nymph.auto.core.video.TapCandidateDetector.FrameSample(100L, 8, 8,
                IntArray(64) { 10 }.also { it[0] = 200; it[1] = 200 }),
            dev.nymph.auto.core.video.TapCandidateDetector.FrameSample(200L, 8, 8, IntArray(64) { 90 }),
            dev.nymph.auto.core.video.TapCandidateDetector.FrameSample(300L, 8, 8,
                IntArray(64) { 90 }.also { it[63] = 10 }),
        )
        val verdicts = ReplayFalsifier.falsifyTaps(frames, listOf(100L, 300L))
        assertEquals(2, verdicts.size)
        assertEquals("tap@100", verdicts[0].id)
        assertEquals("tap@300", verdicts[1].id)
        assertTrue(verdicts.all { it.probability in 0.0..1.0 })
    }

    @Test
    fun `observeFrames 在空输入下返回空不崩溃`() {
        assertTrue(ReplayFalsifier.observeFrames(emptyList()).isEmpty())
    }

    @Test
    fun `乱序帧观测被内部排序后正确回放`() {
        val shuffled = frames().reversed()
        val rule = ReplayFalsifier.Rule(
            id = "r7", actionTMs = 400L,
            pre = ReplayFalsifier.Precondition.AppearImage(0.90),
            post = ReplayFalsifier.PostAssert.DeltaImage(0.5),
            postWindowMs = 200L,
        )
        val v = ReplayFalsifier.falsify(shuffled, listOf(rule)).single()
        assertTrue(v.executable)
        assertEquals(1.0, v.probability, 0.001)
    }
}

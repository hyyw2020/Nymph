// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.video.TapCandidateDetector
import dev.nymph.auto.core.video.TapEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.47：B 通道四证据投票纯 JVM 回归（零模型、零 native）。 */
class V2947EvidenceVoteTest {

    private val W = 360

    @Test
    fun `两独立正证据同域对齐进入候选且记录来源`() {
        val votes = listOf(
            TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.9),
            TapEvidence.Vote(120L, 190, 125, TapEvidence.Kind.RIPPLE, 0.8),
        )
        val r = TapEvidence.fuse(votes, W)
        assertEquals(1, r.size)
        val c = r.single()
        assertEquals(2, c.votes)
        assertTrue(c.sources.contains(TapEvidence.Kind.TAP_FINGER))
        assertTrue(c.sources.contains(TapEvidence.Kind.RIPPLE))
        assertFalse(c.conflict)
    }

    @Test
    fun `四证据齐全置信度最高且不待确认`() {
        val votes = listOf(
            TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.95),
            TapEvidence.Vote(110L, 182, 122, TapEvidence.Kind.RIPPLE, 0.9),
            TapEvidence.Vote(105L, 181, 121, TapEvidence.Kind.OCCLUSION, 0.4),
            TapEvidence.Vote(108L, 180, 120, TapEvidence.Kind.SWIPE, 0.9, positive = true),
        )
        val c = TapEvidence.fuse(votes, W).single()
        assertEquals(4, c.votes)
        assertTrue("confidence=${c.confidence}", c.confidence >= 0.75)
        assertFalse(c.needsReview)
    }

    @Test
    fun `仅一票进入候选但标记低置信度待确认`() {
        // v2.9.48 语义升级：单票不再恒真待确认。弱单票（强度<0.6）仍待确认；
        // 强单票（强度≥0.6）视为正常候选。测试名保留，断言改为强度维度。
        val weak = TapEvidence.fuse(
            listOf(TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.3)), W,
        ).single()
        assertEquals(1, weak.votes)
        assertTrue("弱单票应待确认", weak.needsReview)

        val strong = TapEvidence.fuse(
            listOf(TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.9)), W,
        ).single()
        assertEquals(1, strong.votes)
        assertFalse("强单票应捞出待确认", strong.needsReview)
    }

    @Test
    fun `滑动负证据与点击同域标记冲突强制确认`() {
        val votes = listOf(
            TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.9),
            TapEvidence.Vote(110L, 182, 122, TapEvidence.Kind.RIPPLE, 0.8),
            TapEvidence.Vote(105L, 181, 121, TapEvidence.Kind.SWIPE, 0.9, positive = false),
        )
        val c = TapEvidence.fuse(votes, W).single()
        assertTrue(c.conflict)
        assertTrue(c.needsReview)
    }

    @Test
    fun `时空不对齐的两票不融合成单候选`() {
        val votes = listOf(
            TapEvidence.Vote(100L, 20, 20, TapEvidence.Kind.TAP_FINGER, 0.9),
            TapEvidence.Vote(100L, 300, 300, TapEvidence.Kind.RIPPLE, 0.8),
        )
        // 空间差远超 ±12% 屏宽（43px）→ 两个独立簇，各 1 票。
        val r = TapEvidence.fuse(votes, W)
        assertEquals(2, r.size)
        assertTrue(r.all { it.votes == 1 })
        // v2.9.48：两票自身强度 0.9/0.8 均 ≥0.6 → 强单票，不再恒真待确认。
        assertTrue("强单票应捞出", r.all { !it.needsReview })
    }

    @Test
    fun `关键帧稀疏时间表判定密集区与空表退化`() {
        val schedule = TapEvidence.KeyframeSchedule(listOf(100_000L, 500_000L), 1000L)
        assertTrue(TapEvidence.isDenseRegion(100L, schedule))
        assertTrue(TapEvidence.isDenseRegion(500L, schedule))
        assertFalse(TapEvidence.isDenseRegion(300L, schedule))
        // 空表退化为处处密集（老行为零回归）。
        assertTrue(TapEvidence.isDenseRegion(999L, TapEvidence.KeyframeSchedule()))
    }

    // ---- detectWithEvidence：增强入口，默认 detect 零回归另由旧测试覆盖 ----

    private fun framesWithPersistentSpot(): List<TapCandidateDetector.FrameSample> {
        fun flat(v: Int, t: Long) = TapCandidateDetector.FrameSample(
            t, 64, 64, IntArray(64 * 64) { v },
        )
        val a = flat(100, 0L)
        val b = flat(100, 100L)
        for (y in 30..34) for (x in 30..34) b.gray[y * 64 + x] = 250
        val c = flat(100, 200L)
        for (y in 30..34) for (x in 30..34) c.gray[y * 64 + x] = 250
        return listOf(a, b, c)
    }

    @Test
    fun `增强入口产出证据明细且默认单票待确认`() {
        val r = TapCandidateDetector.detectWithEvidence(framesWithPersistentSpot())
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.votes >= 1 })
        assertTrue("默认仅 TAP_FINGER 主干", r.all { it.sources == setOf(TapEvidence.Kind.TAP_FINGER) })
        // v2.9.48：该帧差峰 score 高（强度强）→ 强单票捞出；断言改为强度分级。
        assertTrue(r.all { it.strengthAvg >= 0.6 })
        assertTrue(r.all { !it.needsReview })
    }

    @Test
    fun `开启增强证据后涟漪票加入置信度提升`() {
        val opts = TapCandidateDetector.Options(enableEnhancedEvidence = true)
        val r = TapCandidateDetector.detectWithEvidence(framesWithPersistentSpot(), opts)
        assertTrue(r.isNotEmpty())
        assertTrue("应出现涟漪证据：${r.map { it.sources }}",
            r.any { it.sources.contains(TapEvidence.Kind.RIPPLE) })
        val withRipple = r.first { it.sources.contains(TapEvidence.Kind.RIPPLE) }
        assertTrue(withRipple.confidence > 0.5)
    }

    @Test
    fun `老 detect 入口不受增强影响逐字零回归`() {
        val old = TapCandidateDetector.detect(framesWithPersistentSpot())
        assertEquals(1, old.size)
        assertEquals(100L, old.single().tMs)
    }
}

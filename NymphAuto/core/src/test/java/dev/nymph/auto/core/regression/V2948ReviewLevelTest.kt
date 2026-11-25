// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.video.TapEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.48：needsReview 强度感知 + ReviewLevel 单一真源分级纯 JVM 回归。 */
class V2948ReviewLevelTest {

    @Test
    fun `冲突优先于其他所有等级`() {
        val lv = TapEvidence.levelOf(
            conflict = true, needsReview = true, votes = 4, strengthAvg = 0.99,
        )
        assertEquals(TapEvidence.ReviewLevel.CONFLICT, lv)
    }

    @Test
    fun `强单票捞出待确认后为正常档`() {
        val needsReview = false // fuse 新谓词：单票强度 0.9 >= 0.6 → 不待确认
        val lv = TapEvidence.levelOf(
            conflict = false, needsReview = needsReview, votes = 1, strengthAvg = 0.9,
        )
        assertEquals(TapEvidence.ReviewLevel.OK, lv)
    }

    @Test
    fun `弱单票落到弱证据档`() {
        val lv = TapEvidence.levelOf(
            conflict = false, needsReview = true, votes = 1, strengthAvg = 0.3,
        )
        assertEquals(TapEvidence.ReviewLevel.WEAK_SINGLE, lv)
    }

    @Test
    fun `多票待确认落到通用待确认档而非弱单证据`() {
        val lv = TapEvidence.levelOf(
            conflict = false, needsReview = true, votes = 3, strengthAvg = 0.4,
        )
        assertEquals(TapEvidence.ReviewLevel.NEEDS_REVIEW, lv)
    }

    @Test
    fun `强单票在 fuse 新谓词下不再待确认`() {
        val strong = TapEvidence.fuse(
            listOf(TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.85)), 360,
        ).single()
        assertFalse(strong.needsReview)
        assertTrue(strong.strengthAvg >= 0.6)
        assertEquals(TapEvidence.ReviewLevel.OK, TapEvidence.levelOf(
            strong.conflict, strong.needsReview, strong.votes, strong.strengthAvg,
        ))
    }

    @Test
    fun `弱单票在 fuse 新谓词下仍待确认且落弱证据档`() {
        val weak = TapEvidence.fuse(
            listOf(TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.25)), 360,
        ).single()
        assertTrue(weak.needsReview)
        assertEquals(TapEvidence.ReviewLevel.WEAK_SINGLE, TapEvidence.levelOf(
            weak.conflict, weak.needsReview, weak.votes, weak.strengthAvg,
        ))
    }

    @Test
    fun `单票强度恰在阈值边界视为强证据`() {
        val atThreshold = TapEvidence.fuse(
            listOf(TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.6)), 360,
        ).single()
        assertFalse("强度=阈值应视为强证据（不待确认）", atThreshold.needsReview)
    }

    @Test
    fun `自定义阈值可收紧强证据判定`() {
        val opts = TapEvidence.FuseOptions(strongSingleStrength = 0.95)
        val c = TapEvidence.fuse(
            listOf(TapEvidence.Vote(100L, 180, 120, TapEvidence.Kind.TAP_FINGER, 0.7)), 360, opts,
        ).single()
        assertTrue("阈值收紧到 0.95 时 0.7 应仍待确认", c.needsReview)
    }
}

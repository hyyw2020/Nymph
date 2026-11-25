// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.20 落点偏差护栏契约：以纯 JVM 断言锁定四段策略的边界、
 * 降频封顶、确定性中止上界与派发后复位语义。
 */
class MismatchGuardTest {

    @Test
    fun `阈值常量保持单调递增`() {
        assertTrue(MismatchGuard.WARN_AFTER < MismatchGuard.BACKOFF_AFTER)
        assertTrue(MismatchGuard.BACKOFF_AFTER < MismatchGuard.ABORT_AFTER)
        assertTrue(MismatchGuard.BACKOFF_STEP_MS > 0L)
        assertTrue(MismatchGuard.BACKOFF_MAX_STEPS > 0)
    }

    @Test
    fun `首次偏差仅累计不降频`() {
        val d = MismatchGuard.onMismatch(0)
        assertEquals(1, d.consecutive)
        assertEquals(MismatchGuard.Action.CONTINUE, d.action)
        assertEquals(0L, d.backoffMs)
        assertFalse(d.announce)
    }

    @Test
    fun `达到告警阈值时仅送一次前台提示`() {
        val d = MismatchGuard.onMismatch(MismatchGuard.WARN_AFTER - 1)
        assertEquals(MismatchGuard.WARN_AFTER, d.consecutive)
        assertEquals(MismatchGuard.Action.WARN, d.action)
        assertTrue(d.announce)
        assertEquals(0L, d.backoffMs)
        // 告警之后、降频之前仍维持累计，不提前让出调度
        assertEquals(
            MismatchGuard.Action.CONTINUE,
            MismatchGuard.onMismatch(d.consecutive).action,
        )
    }

    @Test
    fun `达到降频阈值后进入线性退避`() {
        val d = MismatchGuard.onMismatch(MismatchGuard.BACKOFF_AFTER - 1)
        assertEquals(MismatchGuard.Action.BACKOFF, d.action)
        assertEquals(MismatchGuard.BACKOFF_STEP_MS, d.backoffMs)
        assertEquals(8, MismatchGuard.onMismatch(7).consecutive)
    }

    @Test
    fun `退避时长线性增长并按档数封顶`() {
        assertEquals(400L, MismatchGuard.backoffMs(6))
        assertEquals(800L, MismatchGuard.backoffMs(7))
        assertEquals(2400L, MismatchGuard.backoffMs(11))
        assertEquals(2400L, MismatchGuard.backoffMs(23))
        assertEquals(0L, MismatchGuard.backoffMs(5))
    }

    @Test
    fun `降频阶段日志按间隔节流`() {
        val first = MismatchGuard.onMismatch(MismatchGuard.BACKOFF_AFTER - 1)
        assertTrue(first.announce)
        assertFalse(MismatchGuard.onMismatch(first.consecutive).announce)
        val nextAnnounceAt = MismatchGuard.BACKOFF_AFTER + MismatchGuard.ANNOUNCE_EVERY
        val eighth = MismatchGuard.onMismatch(nextAnnounceAt - 1)
        assertEquals(nextAnnounceAt, eighth.consecutive)
        assertTrue(eighth.announce)
    }

    @Test
    fun `达到上界时确定性中止并保持幂等`() {
        val d = MismatchGuard.onMismatch(MismatchGuard.ABORT_AFTER - 1)
        assertEquals(MismatchGuard.Action.ABORT, d.action)
        assertEquals(0L, d.backoffMs)
        assertTrue(d.announce)
        assertTrue(d.reason.contains("门控参数失配"))
        val beyond = MismatchGuard.onMismatch(d.consecutive)
        assertEquals(MismatchGuard.Action.ABORT, beyond.action)
        assertEquals(MismatchGuard.ABORT_AFTER + 1, beyond.consecutive)
    }

    @Test
    fun `有效动作派发后计数复位并重新累计`() {
        val escalated = MismatchGuard.onMismatch(MismatchGuard.BACKOFF_AFTER)
        assertEquals(MismatchGuard.Action.BACKOFF, escalated.action)
        val reset = MismatchGuard.onFired()
        assertEquals(0, reset)
        assertEquals(MismatchGuard.Action.CONTINUE, MismatchGuard.onMismatch(reset).action)
    }

    @Test
    fun `非正入参按零基线收敛`() {
        assertEquals(1, MismatchGuard.onMismatch(-7).consecutive)
        assertEquals(MismatchGuard.Action.CONTINUE, MismatchGuard.onMismatch(-7).action)
    }

    @Test
    fun `按规则独立计数不被其他规则派发掩盖`() {
        val t = MismatchGuard.Tracker()
        assertEquals(1, t.onMismatch(0).consecutive)
        assertEquals(1, t.onMismatch(1).consecutive)
        t.onFired(0)
        assertEquals(0, t.countFor(0))
        assertEquals(1, t.countFor(1))
        assertEquals(2, t.onMismatch(1).consecutive)
    }

    @Test
    fun `场景离场不打断连续性`() {
        val t = MismatchGuard.Tracker()
        repeat(MismatchGuard.WARN_AFTER) { t.onMismatch(3) }
        assertEquals(MismatchGuard.WARN_AFTER, t.countFor(3))
        t.onIdle()
        t.onIdle()
        assertEquals(MismatchGuard.WARN_AFTER, t.countFor(3))
        assertEquals(MismatchGuard.WARN_AFTER + 1, t.onMismatch(3).consecutive)
    }

    @Test
    fun `确定性中止上界按规则独立判定`() {
        val t = MismatchGuard.Tracker()
        repeat(MismatchGuard.ABORT_AFTER - 1) { t.onMismatch(7) }
        assertEquals(MismatchGuard.ABORT_AFTER - 1, t.countFor(7))
        // 另一条规则的高频成功派发不得把规则 7 的连续计数清零
        repeat(50) { t.onMismatch(1); t.onFired(1) }
        assertEquals(MismatchGuard.ABORT_AFTER - 1, t.countFor(7))
        assertEquals(MismatchGuard.Action.ABORT, t.onMismatch(7).action)
    }

    @Test
    fun `周期性出现但持续偏差的锚点仍可收敛到中止上界`() {
        val t = MismatchGuard.Tracker()
        var last = MismatchGuard.Action.CONTINUE
        repeat(MismatchGuard.ABORT_AFTER) {
            t.onIdle()          // 场景离场
            last = t.onMismatch(2).action   // 每次重新出现均判偏差
        }
        assertEquals(MismatchGuard.Action.ABORT, last)
        assertEquals(MismatchGuard.ABORT_AFTER, t.countFor(2))
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Phase = ConnectionState.Phase
private typealias Ev = ConnectionState.Event

/**
 * 连接所有权状态机契约（v2.9.30）。
 *
 * 锁定三件事：
 * 1. 只有 OWNED 允许注入/感知；
 * 2. Release 在任意相位都可达且幂等；
 * 3. RELEASE 只能经 Settled 回 IDLE，不能被其它事件偷偷抵消。
 */
class ConnectionStateTest {

    @Test
    fun `只有 OWNED 允许注入与感知`() {
        Phase.values().forEach { phase ->
            assertEquals(
                "phase=$phase",
                phase == Phase.OWNED,
                ConnectionState.allowsInjection(phase),
            )
            assertEquals(
                "phase=$phase",
                phase == Phase.OWNED,
                ConnectionState.allowsPerception(phase),
            )
        }
    }

    @Test
    fun `建立链路逐级推进`() {
        val step1 = ConnectionState.next(Phase.IDLE, Ev.Acquire)
        assertEquals(Phase.ACQUIRING, step1.to)
        assertFalse(step1.ignored)

        val step2 = ConnectionState.next(step1.to, Ev.Acquired)
        assertEquals(Phase.OWNED, step2.to)
        assertTrue(ConnectionState.allowsInjection(step2.to))
    }

    @Test
    fun `Release 在任意相位都收敛且幂等`() {
        Phase.values().forEach { phase ->
            val t = ConnectionState.next(phase, Ev.Release)
            assertEquals("from=$phase", Phase.RELEASE, t.to)
        }
        val again = ConnectionState.next(Phase.RELEASE, Ev.Release)
        assertEquals(Phase.RELEASE, again.to)
        assertTrue("重复 Release 应标记为忽略", again.ignored)
    }

    @Test
    fun `RELEASE 只能经 Settled 回到 IDLE`() {
        Ev.values().filter { it != Ev.Settled }.forEach { ev ->
            val t = ConnectionState.next(Phase.RELEASE, ev)
            assertEquals("event=$ev 不得离开 RELEASE", Phase.RELEASE, t.to)
        }
        val settled = ConnectionState.next(Phase.RELEASE, Ev.Settled)
        assertEquals(Phase.IDLE, settled.to)
        assertFalse(settled.ignored)
    }

    @Test
    fun `先释放再收到迟到的事件 不得复活连接`() {
        // 归还之后，一个迟到的 Acquired（旧连接的回调）不能把相位推回 OWNED。
        val afterRelease = ConnectionState.next(Phase.RELEASE, Ev.Acquired)
        assertEquals(Phase.RELEASE, afterRelease.to)
        assertFalse(ConnectionState.allowsInjection(afterRelease.to))
    }

    @Test
    fun `OWNED 收到 LinkLost 进入 SWITCHING 且立即停止注入`() {
        val t = ConnectionState.next(Phase.OWNED, Ev.LinkLost)
        assertEquals(Phase.SWITCHING, t.to)
        assertFalse(ConnectionState.allowsInjection(t.to))
        assertFalse(ConnectionState.allowsPerception(t.to))
    }

    @Test
    fun `SWITCHING 失败即收束到 RELEASE`() {
        val t = ConnectionState.next(Phase.SWITCHING, Ev.SwitchFailed)
        assertEquals(Phase.RELEASE, t.to)
    }

    @Test
    fun `ACQUIRING 期间同样不允许注入`() {
        assertFalse(ConnectionState.allowsInjection(Phase.ACQUIRING))
        assertFalse(ConnectionState.allowsPerception(Phase.ACQUIRING))
    }

    @Test
    fun `非法跃迁原地不动且标记忽略`() {
        val t = ConnectionState.next(Phase.IDLE, Ev.Acquired)
        assertEquals(Phase.IDLE, t.from)
        assertEquals(Phase.IDLE, t.to)
        assertTrue(t.ignored)
    }

    @Test
    fun `相位描述互不重复且非空`() {
        val texts = Phase.values().map { ConnectionState.describe(it) }
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(texts.size, texts.toSet().size)
    }
}

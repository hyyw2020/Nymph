// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.runner.RunToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * v2.9.46 回归：运行会话身份令牌。锁定「旧会话失效后其收尾不得提交」的契约——
 * 这是「启动→停止→立即重启」时旧收尾破坏新一轮的根因防护。
 */
class V2946RunTokenTest {

    @Test
    fun `初始无当前会话`() {
        val t = RunToken()
        assertEquals(-1L, t.current)
        assertFalse(t.isCurrent(-1L))
        assertFalse(t.isCurrent(0L))
    }

    @Test
    fun `next分配单调递增会话号并成为当前`() {
        val t = RunToken()
        val a = t.next()
        assertTrue(a >= 0L)
        assertTrue(t.isCurrent(a))
        val b = t.next()
        assertTrue(b > a)
        assertTrue(t.isCurrent(b))
        assertFalse("旧会话应随新会话分配而失效", t.isCurrent(a))
    }

    @Test
    fun `invalidate后旧会话失效且无当前`() {
        val t = RunToken()
        val id = t.next()
        assertTrue(t.isCurrent(id))
        t.invalidate()
        assertEquals(-1L, t.current)
        assertFalse(t.isCurrent(id))
    }

    @Test
    fun `重启后新会话生效旧会话仍失效`() {
        val t = RunToken()
        val old = t.next()
        t.invalidate()
        val fresh = t.next()
        assertTrue(t.isCurrent(fresh))
        assertFalse(t.isCurrent(old))
    }

    /** 并发 next：所有返回的 id 唯一且最大者成为当前。 */
    @Test
    fun `并发next返回唯一递增id`() {
        val t = RunToken()
        val n = 16
        val ids = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val ws = (0 until n).map {
            Thread {
                start.await()
                ids.add(t.next())
                done.countDown()
            }.apply { start() }
        }
        start.countDown()
        done.await()
        ws.forEach { it.join(1000) }
        assertEquals(n, ids.size)
        assertEquals(n, ids.toSet().size)
        assertEquals(ids.maxOrNull(), t.current)
        assertTrue(t.isCurrent(ids.maxOrNull()!!))
    }
}

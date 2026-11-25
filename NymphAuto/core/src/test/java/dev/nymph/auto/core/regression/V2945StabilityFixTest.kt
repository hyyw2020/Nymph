// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.runner.NativeHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.9.45 稳定性修复的纯 JVM 回归。
 *
 * 锁定 [NativeHandle] 的核心契约：原生句柄释放必须「恰好一次」，且线程安全。
 * 旧实现用普通 `Long` 做 check-then-act，并发停止路径下可 double-free / UAF。
 */
class V2945StabilityFixTest {

    @Test
    fun `初始句柄可读且未释放`() {
        val h = NativeHandle(1234L)
        assertEquals(1234L, h.get())
        assertFalse(h.isReleased())
    }

    @Test
    fun `disposeOnce传入真实句柄并置零`() {
        val h = NativeHandle(99L)
        val got = AtomicReference<Long>()
        h.disposeOnce { got.set(it) }
        assertEquals(99L, got.get())
        assertEquals(0L, h.get())
        assertTrue(h.isReleased())
    }

    @Test
    fun `重复disposeOnce只销毁一次`() {
        val h = NativeHandle(77L)
        val calls = AtomicInteger()
        repeat(10) { h.disposeOnce { calls.incrementAndGet() } }
        assertEquals(1, calls.get())
    }

    @Test
    fun `零初始句柄不触发销毁`() {
        val h = NativeHandle(0L)
        val calls = AtomicInteger()
        h.disposeOnce { calls.incrementAndGet() }
        assertEquals(0, calls.get())
    }

    @Test
    fun `销毁器抛异常时句柄仍已清零且异常上抛`() {
        val h = NativeHandle(55L)
        val thrown = runCatching {
            h.disposeOnce { throw IllegalStateException("destroy failed") }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(h.isReleased())
        // 再次调用不会重试（句柄已归零）
        val calls = AtomicInteger()
        h.disposeOnce { calls.incrementAndGet() }
        assertEquals(0, calls.get())
    }

    /**
     * 核心并发回归：N 条线程同时 disposeOnce 同一个非零句柄，销毁必须恰好一次。
     * 用 CyclicBarrier 式 latch 让所有线程尽量同时冲过检查点，放大旧实现的竞态窗口。
     * 重复 200 轮，覆盖调度不确定性。
     */
    @Test
    fun `并发disposeOnce恰好销毁一次`() {
        val threads = 8
        val rounds = 200
        for (round in 0 until rounds) {
            val h = NativeHandle(0xABCDEFL.toLong())
            val destroyed = AtomicInteger()
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            val ws = (0 until threads).map {
                Thread {
                    start.await()
                    h.disposeOnce { destroyed.incrementAndGet() }
                    done.countDown()
                }.apply { start() }
            }
            start.countDown()
            done.await()
            ws.forEach { it.join(1000) }
            assertEquals("round=$round 并发销毁次数应恰为 1", 1, destroyed.get())
            assertEquals(0L, h.get())
        }
    }

    /**
     * 混合场景：并发 get() 与 disposeOnce()，任何线程拿到的句柄要么是非零原始值，
     * 要么是 0（已释放）；绝不允许出现「读到非零后 dispose 已完成」之外的第三种值。
     * 这里主要断言 dispose 后 get 稳定为 0，且销毁仍恰好一次。
     */
    @Test
    fun `并发读取与销毁后句柄稳定为零`() {
        val h = NativeHandle(0x1234L)
        val destroyed = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val ws = (0 until 16).map { i ->
            Thread {
                start.await()
                if (i % 2 == 0) h.get() else h.disposeOnce { destroyed.incrementAndGet() }
                done.countDown()
            }.apply { start() }
        }
        start.countDown()
        done.await()
        ws.forEach { it.join(1000) }
        assertEquals(1, destroyed.get())
        assertEquals(0L, h.get())
    }
}

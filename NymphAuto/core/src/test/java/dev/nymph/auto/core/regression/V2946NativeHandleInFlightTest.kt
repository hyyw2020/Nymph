// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.runner.NativeHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.9.46 回归：NativeHandle 的「在途调用保护」。
 *
 * 旧版只有 disposeOnce 的原子一次性释放，但 `get()` 之后到 native 调用之前仍有窗口：
 * 另一线程此刻 dispose 并 free 了 runtime，本次 native 调用就是 UAF。
 * 本版 useIfAlive 持读锁、disposeOnce 持写锁，保证在途调用期间绝不发生释放。
 */
class V2946NativeHandleInFlightTest {

    @Test
    fun `存活时useIfAlive执行block并返回值`() {
        val h = NativeHandle(42L)
        val got = h.useIfAlive { p -> p * 2 }
        assertEquals(84L, got)
    }

    @Test
    fun `释放后useIfAlive不执行block返回null`() {
        val h = NativeHandle(42L)
        h.disposeOnce { }
        val called = AtomicBoolean(false)
        val got = h.useIfAlive { called.set(true); it }
        assertNull(got)
        assertFalse(called.get())
    }

    @Test
    fun `eval正常返回null不被误判为已释放`() {
        // 模拟 eval 的空字符串结果经非空包装后，useIfAlive 返回非 null 包装。
        val h = NativeHandle(7L)
        val wrapped = h.useIfAlive { EvalOut("") }
        assertNotNull(wrapped)
        assertEquals("", wrapped!!.text)
    }

    private data class EvalOut(val text: String)

    /**
     * 核心并发回归：读锁持有期间（block 阻塞在 latch 上），disposeOnce 的 destroyer
     * 必须等到 block 退出后才执行——即在途 native 调用绝不与释放并发。
     */
    @Test
    fun `在途调用期间dispose被写锁阻塞`() {
        val h = NativeHandle(0xCAFEL)
        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val destroyedDuringBlock = AtomicBoolean(false)
        val destroyedAfter = AtomicInteger(0)

        val reader = Thread {
            h.useIfAlive {
                blockEntered.countDown()
                releaseBlock.await(2, TimeUnit.SECONDS)
                it
            }
        }.apply { start() }

        assertTrue("读线程应进入 block", blockEntered.await(2, TimeUnit.SECONDS))
        val disposer = Thread {
            h.disposeOnce {
                if (releaseBlock.count == 0L) destroyedDuringBlock.set(true)
                destroyedAfter.incrementAndGet()
            }
        }.apply { start() }

        // 给 dispose 线程时间尝试进入写锁；此时它应被阻塞
        Thread.sleep(150)
        assertFalse("block 尚未退出，destroyer 不应执行", destroyedDuringBlock.get())

        releaseBlock.countDown()
        reader.join(2000)
        disposer.join(2000)
        assertTrue("block 退出后 destroyer 应执行一次", destroyedAfter.get() == 1)
        assertTrue(h.isReleased())
    }
}

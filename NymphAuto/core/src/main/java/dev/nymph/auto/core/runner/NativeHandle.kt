package dev.nymph.auto.core.runner

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 一次性原生句柄守卫（纯 JVM，可单测）。
 *
 * 背景：QuickJS 等 native runtime 的指针以 `jlong` 持有，释放必须「恰好一次」。
 * 同一实例的停止路径可能来自多个协程/线程（脚本协程收尾、停止按钮、连接交接）。
 * 普通 `Long` 的 `if (ptr != 0L) { destroy(ptr); ptr = 0L }` 是典型的 check-then-act：
 * 两条线程可同时读到同一个非 0 值并各自进入 destroy，造成 native double-free /
 * use-after-free，表现为随机 SIGSEGV，极难复现。
 *
 * 本类用 [AtomicLong.getAndSet] 的等价语义（[disposeOnce]）把「读取旧值 + 清零」合成为
 * 单个原子操作：无论多少线程并发调用，只有一个线程拿到非 0 旧值并执行 destroy，
 * 其余线程拿到 0 直接跳过。
 *
 * v2.9.46 追加「在途调用保护」：[get] 之后到 native 调用之前仍存在窗口——若另一线程此刻
 * dispose 并 free 了 runtime，本次 native 调用就是 use-after-free。为此引入读写锁：
 *  - [useIfAlive] 持**读锁**执行 native 调用，多个调用方可并发；
 *  - [disposeOnce] 持**写锁**，会等待所有在途调用退出后才 free。
 * 从根上消除「读到非 0 → 被 dispose → 仍调用」的残余 UAF 窗口。锁可重入，
 * 单线程约定（QuickJS runtime 本身单线程）下无额外竞争。
 */
class NativeHandle(initial: Long) {
    private val ref = java.util.concurrent.atomic.AtomicLong(initial)
    private val rw = ReentrantReadWriteLock()

    /** 当前句柄；已释放则为 0。 */
    fun get(): Long = ref.get()

    /** 句柄是否已释放。 */
    fun isReleased(): Boolean = ref.get() == 0L

    /**
     * 持读锁执行 [block]：只要句柄仍存活就调用它并把返回值包成非 null；句柄为 0 时
     * 返回 null 且不调用。读锁保证 [block] 执行期间 [disposeOnce] 的写锁无法进入，
     * 即在途 native 调用绝不会与释放并发。
     */
    fun <T> useIfAlive(block: (Long) -> T): T? {
        val read = rw.readLock()
        read.lock()
        try {
            val p = ref.get()
            return if (p != 0L) block(p) else null
        } finally {
            read.unlock()
        }
    }

    /**
     * 原子地取出并清零句柄；仅当取出的旧值非 0 时调用 [destroyer]，且全程恰好一次。
     * 持写锁，等待所有在途 [useIfAlive] 退出后才释放，杜绝 native UAF。
     * [destroyer] 抛异常时句柄仍已清零（不会二次释放），异常原样上抛由调用方记录。
     */
    fun disposeOnce(destroyer: (Long) -> Unit) {
        val write = rw.writeLock()
        write.lock()
        try {
            val old = ref.getAndSet(0L)
            if (old != 0L) destroyer(old)
        } finally {
            write.unlock()
        }
    }
}

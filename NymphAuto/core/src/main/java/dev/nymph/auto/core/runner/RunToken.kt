package dev.nymph.auto.core.runner

/**
 * 运行会话身份令牌（纯 JVM，可单测）。
 *
 * 背景：一次运行的资源（runtime / 悬浮层 / 看门狗 / 状态字段）在创建后，其「收尾」
 * 往往延迟到协程的 `finally`、`invokeOnCompletion`、主线程 `postUi` 才提交。若用户在
 * 旧收尾尚未执行完时就停止并立刻开启新一轮，旧收尾会把新一轮的字段清空、关掉新一轮
 * 的浮层/看门狗、改写新一轮的状态——表现为「开了新脚本立刻自己停 / 面板错乱」。
 *
 * 本令牌给每次运行分配一个单调递增的会话号 [next]。所有延迟提交都捕获自己的 id，
 * 提交前用 [isCurrent] 判断「自己是否仍是当前运行」：停止 / 交接时 [invalidate] 让旧
 * 会话立刻失效，旧收尾的字段写因此全部被跳过；而资源释放本身（dispose/close 本轮
 * 局部对象）不依赖本令牌，照常无条件执行，不会泄漏。
 *
 * 线程安全：[current] 用 `@Volatile`，[next] 同步递增；停止在主线程、收尾在脚本线程，
 * 双方看到的是同一份可见性一致的状态。
 */
class RunToken {
    private var seq = 0L

    /** 当前活跃运行 id；-1 = 无活跃运行。 */
    @Volatile
    var current: Long = -1L
        private set

    /** 分配一个新会话号并设为当前。 */
    @Synchronized
    fun next(): Long {
        current = ++seq
        return current
    }

    /** 令当前会话失效：此后所有旧 id 的 [isCurrent] 都返回 false。 */
    @Synchronized
    fun invalidate() {
        current = -1L
    }

    /** [id] 是否仍是当前活跃运行。 */
    fun isCurrent(id: Long): Boolean = id >= 0L && id == current
}

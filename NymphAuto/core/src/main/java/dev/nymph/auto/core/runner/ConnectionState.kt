// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

/**
 * 连接所有权状态机（v2.9.30，core 纯 JVM 单一真源）。
 *
 * ## 为什么把「连接」单独建模
 *
 * 上一版把「暂停 / 停止 / 归还 / 降级」四种语义都压在运行态
 * `IDLE / RUNNING / PAUSED` 上。这带来一个用户可感知、但代码里看不出来的缺口：
 * **暂停之后连接仍然被持有**——读屏仍被占着、截图通道仍开着，而界面上写的是
 * 「已暂停」。用户按了「归还」，实际什么都没归还。
 *
 * 本契约把「谁持有通道」独立成一维，与「脚本是否在跑」正交：
 *
 * - [Phase.OWNED] 是**唯一**允许注入与截图的相位；其余相位一律零动作。
 * - [Phase.SWITCHING]（感知方式切换中）同样零动作。切换期是最危险的半连接态：
 *   旧通道已释放、新通道未稳，任何派发都会落到不可预期的地方。
 * - [Phase.RELEASE] 是**单向收束**：进入后只允许经 [Event.Settled] 回 [Phase.IDLE]。
 *   这样「归还」不会被后续的自动重连逻辑悄悄抵消。
 *
 * ## 幂等
 *
 * 通知栏三键会被用户在任意时刻、任意次数点击，也可能重复投递。契约要求：
 * 任意相位收到 [Event.Release] 都收敛到 [Phase.RELEASE]（不抛错、不回退）；
 * 已在 [Phase.RELEASE] 时再收一次仍是 [Phase.RELEASE]。上层因此可以无脑重放。
 *
 * 纯函数：状态由调用方持有，本对象只做转移与判定，不读时钟、不持上下文。
 */
object ConnectionState {

    /** 连接所有权相位。 */
    enum class Phase {
        /** 未持有任何通道。可安全退出、不占用系统资源。 */
        IDLE,

        /** 正在建立（授权、能力探测、绑定）。**不可注入**。 */
        ACQUIRING,

        /** 持有通道。唯一允许注入与截图的相位。 */
        OWNED,

        /** 感知方式切换中（旧通道已断、新通道未稳）。**不可注入**。 */
        SWITCHING,

        /** 正在释放 / 已释放。**不可注入**，直到 [Event.Settled]。 */
        RELEASE,
    }

    /** 状态转移事件。 */
    enum class Event {
        /** 请求建立连接。 */
        Acquire,

        /** 连接建立完成且自检通过。 */
        Acquired,

        /** 通道被动丢失（服务被系统回收、对端进程退出、绑定被挤掉）。 */
        LinkLost,

        /** 请求切换感知方式（仅被动丢失且降级通道就绪时由上层发起）。 */
        Switch,

        /** 切换失败（新通道起不来）。 */
        SwitchFailed,

        /** 主动释放：暂停、退出、一键归还、切后台超时。 */
        Release,

        /** 释放流程执行完毕（注入器已置空、抓帧已停、引用已清）。 */
        Settled,
    }

    /** 一次转移的结果。 */
    data class Transition(
        val from: Phase,
        val to: Phase,
        val event: Event,
        /** 该事件在此相位下不改变状态（幂等重放或非法跃迁）。 */
        val ignored: Boolean,
    )

    /**
     * 转移表。
     *
     * 取值原则：非法跃迁**原地不动并标记 [Transition.ignored]**，不抛异常——
     * 状态机跑在服务主线程上，抛错会把「用户点了归还」变成崩溃。
     * 唯一无条件生效的是 [Event.Release]：释放必须永远可达，这是红线。
     */
    fun next(from: Phase, event: Event): Transition {
        if (event == Event.Release) {
            return Transition(from, Phase.RELEASE, event, ignored = from == Phase.RELEASE)
        }
        val to: Phase? = when (from) {
            Phase.IDLE -> when (event) {
                Event.Acquire -> Phase.ACQUIRING
                else -> null
            }
            Phase.ACQUIRING -> when (event) {
                Event.Acquired -> Phase.OWNED
                // 还没拿到就丢了：直接收束，不留半连接。
                Event.LinkLost -> Phase.RELEASE
                Event.Switch -> Phase.SWITCHING
                Event.SwitchFailed -> Phase.RELEASE
                // 重入保持，不叠加申请。
                Event.Acquire -> Phase.ACQUIRING
                else -> null
            }
            Phase.OWNED -> when (event) {
                // 注意：能否真的切，由上层用 DegradeChannel 判定；状态机只表达
                // 「此刻起不再按 OWNED 派发动作」。
                Event.LinkLost -> Phase.SWITCHING
                Event.Switch -> Phase.SWITCHING
                Event.Acquired -> Phase.OWNED
                else -> null
            }
            Phase.SWITCHING -> when (event) {
                Event.Acquired -> Phase.OWNED
                Event.SwitchFailed -> Phase.RELEASE
                Event.LinkLost -> Phase.RELEASE
                else -> null
            }
            Phase.RELEASE -> when (event) {
                Event.Settled -> Phase.IDLE
                else -> null
            }
        }
        return if (to == null) {
            Transition(from, from, event, ignored = true)
        } else {
            Transition(from, to, event, ignored = false)
        }
    }

    /** 是否允许注入手势。只有 [Phase.OWNED] 为真，其余一律拒绝。 */
    fun allowsInjection(phase: Phase): Boolean = phase == Phase.OWNED

    /** 是否允许抓帧 / 读节点树。与注入同源——不允许注入时也不该继续采集。 */
    fun allowsPerception(phase: Phase): Boolean = phase == Phase.OWNED

    /** 是否已经开始释放（用于 UI 显示「正在释放」而不是「已空闲」）。 */
    fun isReleasing(phase: Phase): Boolean = phase == Phase.RELEASE

    /** 相位的可读说明（状态卡 / 通知使用，不含内部术语）。 */
    fun describe(phase: Phase): String = when (phase) {
        Phase.IDLE -> "未连接"
        Phase.ACQUIRING -> "正在连接"
        Phase.OWNED -> "已连接"
        Phase.SWITCHING -> "正在切换感知方式"
        Phase.RELEASE -> "正在释放连接"
    }

    /** 相位对应的技术标签（日志 / 导出使用）。 */
    fun tag(phase: Phase): String = phase.name

    /** 相位排序权重，供 UI 判断「是否处于更收束的一侧」。 */
    fun weight(phase: Phase): Int = when (phase) {
        Phase.IDLE -> 0
        Phase.ACQUIRING -> 1
        Phase.OWNED -> 2
        Phase.SWITCHING -> 3
        Phase.RELEASE -> 4
    }
}

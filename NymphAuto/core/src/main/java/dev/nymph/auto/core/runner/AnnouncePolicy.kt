// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

/**
 * 预告分级策略（v2.9.30，core 纯 JVM 单一真源）。
 *
 * ## 要解决的真矛盾
 *
 * 「注入前预告 + 撤销窗口」如果对每一步都生效，自动点击器的吞吐会掉一个数量级：
 * 一个 40ms 的动作被 1200ms 的窗口拖成 30 倍慢放，高频循环（例如每秒数次的
 * 状态轮询点击）直接失去意义。上一版把「预告」当成全局开关，只能在「够快」和
 * 「能反悔」之间二选一——这是拿延迟换安心，不是设计。
 *
 * ## 分级而不是开关
 *
 * 反悔的价值随「信息量」衰减：用户第一次看到某个动作时需要确认，看到第 20 次时
 * 只需要知道它还在按预案跑。据此分三级：
 *
 * - [Level.FULL]：逐动作预告 + 撤销窗口。用于**首次运行、新场景首次触发、
 *   用户显式标记的高风险动作、本次运行尚未确认过的动作**。这几类都是「用户
 *   还没有证据判断这是否符合预期」的时刻。
 * - [Level.FOLDED]：不阻塞，只在常驻位置更新「下一步」。用于**已确认的重复
 *   循环**。此时反悔权从「逐步撤销」降级为「全局撤销」（随时暂停 / 归还连接），
 *   代价从 1200ms/步 变成 0ms/步。
 * - [Level.NONE]：连「下一步」都不必展示。用于没有落点的动作（等待、条件、
 *   全局动作），展示它只会制造噪音。
 *
 * ## 两个防叠加约束
 *
 * 1. **合并窗口**（[COALESCE_MS]）：两次 FULL 之间不足该间隔时，后一次降级为
 *    FOLDED。理由是物理的——用户来不及看完两个相隔 300ms 的预告，第二个窗口
 *    只是纯延迟。这条约束让 FULL 的密度有上界，吞吐不会被预告吃掉。
 * 2. **高风险不受合并限制**：显式标记的高风险动作通常稀疏，且每次都值得打断。
 *    这类动作宁可慢，不可错。
 *
 * 纯函数：不读时钟（间隔由调用方测量后传入），不持状态。
 */
object AnnouncePolicy {

    /** 预告级别。 */
    enum class Level {
        /** 逐动作预告 + 撤销窗口（阻塞）。 */
        FULL,

        /** 常驻「下一步」，不阻塞。 */
        FOLDED,

        /** 不预告。 */
        NONE,
    }

    /** 常规撤销窗口。 */
    const val UNDO_MS = 1_200L

    /** 首次运行 / 新场景的撤销窗口：信息量最大，给足反应时间。 */
    const val UNDO_FIRST_MS = 1_600L

    /** 用户标记的高风险动作撤销窗口。 */
    const val UNDO_RISK_MS = 2_000L

    /** 两次 FULL 的最小间隔；更密的后一次降级为 FOLDED。 */
    const val COALESCE_MS = 900L

    /**
     * 判定输入。
     *
     * @param undoable 该动作是否有可撤销的语义（有落点的手势为 true；等待、
     *   条件、全局动作、切应用为 false）。false 直接返回 [Level.NONE]。
     * @param highRisk 用户**显式**标记为高风险。本应用不猜应用类别、不做价值
     *   判断，这个值只能来自用户操作，默认 false。
     * @param firstRun 本脚本首次运行（从未成功跑完过）。
     * @param sceneFirstSeen 该场景在本次运行中首次触发。
     * @param confirmed 该动作已在本次运行中成功执行过至少一次。
     * @param sinceLastFullMs 距上一次 FULL 预告结束的毫秒数；从未预告过传
     *   [Long.MAX_VALUE]。
     */
    data class Fact(
        val undoable: Boolean = true,
        val highRisk: Boolean = false,
        val firstRun: Boolean = false,
        val sceneFirstSeen: Boolean = false,
        val confirmed: Boolean = false,
        val sinceLastFullMs: Long = Long.MAX_VALUE,
    )

    /** 判定结果。 */
    data class Decision(
        val level: Level,
        /** 需要阻塞的毫秒数；[Level.FOLDED] / [Level.NONE] 恒为 0。 */
        val undoWindowMs: Long,
        /** 常驻位置展示的「下一步」文案；[Level.NONE] 为空串。 */
        val nextLabel: String,
        /** 需要送达给用户的预告主文案；[Level.NONE] 为空串。 */
        val announce: String,
        /** 判定依据（日志用，不面向用户）。 */
        val reason: String,
    )

    /**
     * 分级。
     *
     * @param label 动作的可读描述（如「点击『确定』」）。为空时退化为「下一步」，
     *   不编造描述。
     */
    fun decide(fact: Fact, label: String): Decision {
        val text = label.trim()
        val shown = if (text.isEmpty()) "下一步" else "下一步：$text"

        if (!fact.undoable) {
            return Decision(Level.NONE, 0L, "", "", "无落点动作，不预告")
        }
        if (fact.highRisk) {
            val msg = if (text.isEmpty()) "即将执行（已标记为需确认）" else "即将执行：$text"
            return Decision(Level.FULL, UNDO_RISK_MS, shown, msg, "用户标记为高风险")
        }
        // 合并窗口：刚预告过，再弹一次只是纯延迟。
        if (fact.sinceLastFullMs < COALESCE_MS && !fact.sceneFirstSeen) {
            return Decision(Level.FOLDED, 0L, shown, "", "距上次预告 ${fact.sinceLastFullMs}ms，合并为常驻提示")
        }
        if (fact.firstRun && fact.sceneFirstSeen) {
            return Decision(Level.FULL, UNDO_FIRST_MS, shown, "首次运行，即将执行：$text", "首次运行 + 新场景")
        }
        if (fact.sceneFirstSeen) {
            return Decision(Level.FULL, UNDO_MS, shown, "新场景首次触发，即将执行：$text", "新场景首次触发")
        }
        if (!fact.confirmed) {
            return Decision(Level.FULL, UNDO_MS, shown, "即将执行：$text", "本次运行尚未确认过该动作")
        }
        return Decision(Level.FOLDED, 0L, shown, "", "已确认的重复循环")
    }

    /** 该级别是否需要阻塞。供调用方快速判定，避免误用 [Decision.undoWindowMs]。 */
    fun blocks(level: Level): Boolean = level == Level.FULL

    /** 该级别是否需要在常驻位置展示「下一步」。 */
    fun showsNext(level: Level): Boolean = level != Level.NONE

    /**
     * 该动作类型是否有可撤销的落点语义。
     *
     * 有落点才会「点错东西」，也就才值得预告；等待、条件、变量、全局动作没有
     * 落点，预告它们只是在刷屏。这里按动作语义判定，**不看目标应用是什么**——
     * 本应用不对目标应用做价值判断，只判断「这一步有没有可能点偏」。
     */
    fun isUndoable(type: dev.nymph.auto.core.model.ActionType): Boolean =
        when (type) {
            dev.nymph.auto.core.model.ActionType.TAP,
            dev.nymph.auto.core.model.ActionType.LONG_PRESS,
            dev.nymph.auto.core.model.ActionType.SWIPE,
            dev.nymph.auto.core.model.ActionType.CURVE,
            dev.nymph.auto.core.model.ActionType.MULTI_TAP,
            dev.nymph.auto.core.model.ActionType.MULTI_FINGER,
            dev.nymph.auto.core.model.ActionType.REPLAY,
            dev.nymph.auto.core.model.ActionType.PINCH_OUT,
            dev.nymph.auto.core.model.ActionType.PINCH_IN,
            dev.nymph.auto.core.model.ActionType.CLICK_IMAGE,
            dev.nymph.auto.core.model.ActionType.CLICK_TEXT,
            dev.nymph.auto.core.model.ActionType.CLICK_COLOR,
            dev.nymph.auto.core.model.ActionType.CLICK_NODE,
            dev.nymph.auto.core.model.ActionType.CLICK_AI,
            dev.nymph.auto.core.model.ActionType.KEY,
            dev.nymph.auto.core.model.ActionType.INPUT,
            -> true
            else -> false
        }

    /**
     * 撤销窗口内用户点了「撤销」时的处置文案。
     * 语义：本步不派发，停在原地，等用户决定继续或停止。
     */
    fun revokedNotice(label: String): String {
        val t = label.trim()
        return if (t.isEmpty()) "已撤销，本步未执行" else "已撤销，本步未执行：$t"
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

/**
 * 场景驱动落点偏差护栏（v2.9.20，core 纯 JVM 单一真源）。
 *
 * [dev.nymph.auto.core.editor.SceneTrigger.resolveSceneClick] 在「匹配中心偏离记录锚点
 * 超出位置偏差预算」时返回 `SuspiciousMismatch`，引擎侧不派发动作。该分支在 v2.9.19
 * 及之前仅累计跳过计数，事件循环持续运转但不产生有效动作派发，且无累计上界。
 * 当锚点因分辨率变更、显示方向变更或界面布局迁移而系统性偏移时，该状态可无限延续。
 *
 * 本契约把「连续落点偏差」收敛为四段可观测策略，与顺序链 `consecutiveMiss` 的
 * 线性退避保持同一设计语言，但补充确定性终止上界：顺序链的「目标未出现」属于
 * 正常等待语义，只降频不终止；场景链的「场景已匹配但落点持续超出预算」属于
 * 门控参数失配，是确定性失效，二者不应共用同一处置强度。
 *
 * 纯函数：不读系统时钟、不依赖 Android 全局状态，计数由调用方跨轮持有并传入。
 * [Tracker] 提供按规则独立的计数容器：仅本规则的有效派发复位本规则计数，
 * 穿插有效派发的偶发偏差不会累积为中止条件。
 */
object MismatchGuard {

    /** 连续偏差达到该次数时输出一次前台告警。 */
    const val WARN_AFTER = 3

    /** 连续偏差达到该次数后进入线性降频观察。 */
    const val BACKOFF_AFTER = 6

    /** 连续偏差达到该次数即确定性中止，避免事件循环无上界延续。 */
    const val ABORT_AFTER = 24

    /** 降频步长，与顺序链 `awaitFind` 的退避步长同量级。 */
    const val BACKOFF_STEP_MS = 400L

    /** 降频封顶档数，防止等待间隔无限增长。 */
    const val BACKOFF_MAX_STEPS = 6

    /** 降频阶段的日志节流间隔。 */
    const val ANNOUNCE_EVERY = 8

    enum class Action {
        /** 仅累计，维持既有逐轮告警。 */
        CONTINUE,

        /** 首次达到告警阈值，需前台送达一次。 */
        WARN,

        /** 线性降频观察，刷新心跳后按 [Decision.backoffMs] 让出调度。 */
        BACKOFF,

        /** 达到确定性失效上界，显式中止并给出复核指引。 */
        ABORT,
    }

    data class Decision(
        /** 本次累计后的连续偏差次数。 */
        val consecutive: Int,
        val action: Action,
        /** 本次应让出的毫秒数；非 [Action.BACKOFF] 恒为 0。 */
        val backoffMs: Long,
        /** 是否需要输出汇总日志；用于降频阶段的节流。 */
        val announce: Boolean,
        /** 面向运行日志的规范化归因说明。 */
        val reason: String,
    )

    /**
     * 登记一次落点偏差。
     *
     * @param prev 此前连续偏差次数；非正值按 0 处理，避免手写状态引入负计数。
     */
    fun onMismatch(prev: Int): Decision {
        val n = prev.coerceAtLeast(0) + 1
        val action = when {
            n >= ABORT_AFTER -> Action.ABORT
            n >= BACKOFF_AFTER -> Action.BACKOFF
            n == WARN_AFTER -> Action.WARN
            else -> Action.CONTINUE
        }
        val backoff = if (action == Action.BACKOFF) backoffMs(n) else 0L
        val announce = when (action) {
            Action.WARN, Action.ABORT -> true
            Action.BACKOFF -> (n - BACKOFF_AFTER) % ANNOUNCE_EVERY == 0
            Action.CONTINUE -> false
        }
        val reason = when (action) {
            Action.CONTINUE -> "连续 $n 次落点偏差超出预算，维持跳过并继续观测"
            Action.WARN -> "连续 $n 次落点偏差超出预算，事件循环持续但无有效动作派发，需复核锚点与当前显示参数"
            Action.BACKOFF -> "连续 $n 次落点偏差超出预算，进入降频观测 ${backoff}ms，恢复后自动复原"
            Action.ABORT -> "连续 $n 次落点偏差超出预算，判定为门控参数失配，确定性中止"
        }
        return Decision(n, action, backoff, announce, reason)
    }

    /** 有效动作派发后的计数复位值。 */
    fun onFired(): Int = 0

    /** 线性降频时长，封顶 [BACKOFF_MAX_STEPS] × [BACKOFF_STEP_MS]。 */
    fun backoffMs(consecutive: Int): Long {
        if (consecutive < BACKOFF_AFTER) return 0L
        val steps = (consecutive - BACKOFF_AFTER + 1).coerceAtMost(BACKOFF_MAX_STEPS)
        return steps * BACKOFF_STEP_MS
    }

    /**
     * 按规则独立的连续偏差计数容器（v2.9.20 重新发版，core 纯 JVM 单一真源）。
     *
     * 连续性定义为「自本规则上一次有效动作派发起的连续偏差次数」，两条约束：
     *
     * 1. **仅本规则的有效派发复位本规则计数**（[onFired]）。多规则脚本中，
     *    高频成功派发的规则不再掩盖另一条规则的确定性失配；此前全局单例计数
     *    会被任意一条规则的成功派发清零，使失配规则永远达不到 [ABORT_AFTER]。
     * 2. **场景离场不打断连续性**（[onIdle]）。锚点失配的成因不因场景暂时消失
     *    而消除；若离场即复位，「周期性出现 + 系统性偏移」的锚点每个出现周期
     *    仅累计一次随即归零，护栏的确定性中止上界将不可达。
     *
     * 由此，穿插有效派发的偶发偏差不会累积为中止条件；同一规则持续偏差即使
     * 跨场景离场仍按 [ABORT_AFTER] 收敛。容器不读系统时钟、不落盘，生命周期
     * 限定为单次运行。
     */
    class Tracker {
        private val perRule = HashMap<Int, Int>()

        /** 登记指定规则一次落点偏差，返回该规则的四段策略决策。 */
        fun onMismatch(ruleIndex: Int): Decision {
            val d = MismatchGuard.onMismatch(perRule[ruleIndex] ?: 0)
            perRule[ruleIndex] = d.consecutive
            return d
        }

        /** 指定规则有效动作派发后复位其连续计数。 */
        fun onFired(ruleIndex: Int) {
            perRule[ruleIndex] = 0
        }

        /**
         * 本轮无触发意图（场景未在场 / 冷却抑制 / 仲裁落空）时显式登记。
         * 保持各规则计数不变，不复位；语义见类注释第 2 条。
         */
        fun onIdle() {
            // 连续性不因场景离场而打断，故此处不修改 perRule。
        }

        /** 供取证与契约单测读取指定规则当前连续偏差次数。 */
        fun countFor(ruleIndex: Int): Int = perRule[ruleIndex] ?: 0
    }
}

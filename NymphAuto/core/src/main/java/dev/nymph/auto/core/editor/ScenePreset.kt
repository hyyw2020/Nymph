// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.TriggerMode

/**
 * **场景触发意图层**（v2.9.16 / A1，core 纯 JVM，可单测）。
 *
 * 目的：把 [SceneRule] 的带状态触发参数空间（triggerMode / 迟滞 / cooldown /
 * retryTimeout / priority / mutexGroup / 同轮 K 上限 / 位置偏差阈值）投影成三个
 * 用户可理解的意图，避免内核参数直接暴露到操作层。
 *
 * 设计约束：
 *  - **不新增落盘字段、不改 [SceneRule] 的任何已有语义**；本对象是纯投影层，
 *    输入/输出只经过 [SceneRule]，因此不引入新的迁移负担。
 *  - **双向可逆**：[toRule] 把意图展开成参数；[fromRule] 把参数折叠回意图。
 *    当参数组合落在任何预设之外时返回 null，界面据此降级为「自定义」，
 *    不误报为某个预设，也不擅自改写用户已调好的参数。
 *  - **维度耦合显式化**：[Timing.WHILE_PRESENT] 与 [Repetition.CONTINUOUS] 在
 *    [SceneRule] 上由同一组字段表达，二者并非正交。[Intent.normalized] 把矛盾组合
 *    收敛到可无损还原的自洽组合，[toRule] / [roundTrip] 均以规范化结果为基准。
 *  - 运行时仍以 [SceneRule] 为唯一真源；本对象只服务编辑界面与文档呈现。
 */
object ScenePreset {

    /** 触发时机：对应 [TriggerMode]。 */
    enum class Timing {
        /** 场景出现时触发一次。 */
        ON_APPEAR,

        /** 场景在场期间重复触发（受冷却约束）。 */
        WHILE_PRESENT,

        /** 场景消失时触发一次。 */
        ON_DISAPPEAR,
    }

    /** 防抖强度：对应迟滞帧数。 */
    enum class Debounce {
        /** 不做迟滞，单帧命中即判在场。 */
        OFF,

        /** 连续 2 帧一致才切换在场/离场状态。 */
        STANDARD,

        /** 连续 3 帧一致才切换，用于高频闪烁界面。 */
        STRONG,
    }

    /** 重复与重试策略：对应 cooldownMs / retryTimeoutMs。 */
    enum class Repetition {
        /** 仅触发一次，不主动重试。 */
        ONCE,

        /** 触发后若场景持续在场，超时后允许重试。 */
        RETRY_IF_PERSISTENT,

        /** 场景在场期间按冷却周期持续触发。 */
        CONTINUOUS,
    }

    /**
     * 一条完整的意图配置。
     *
     * @param maxFiringPerRound 同轮最多触发条数（对应 Script.sceneMaxFiringPerRound），
     *   单独列出是因为它属于脚本级而非单条规则；默认 1 最安全。
     */
    data class Intent(
        val timing: Timing = Timing.ON_APPEAR,
        val debounce: Debounce = Debounce.STANDARD,
        val repetition: Repetition = Repetition.RETRY_IF_PERSISTENT,
        val maxFiringPerRound: Int = 1,
    ) {
        /** 界面主层展示的一句话（不含参数名、不含内核术语）。 */
        val summary: String
            get() = buildString {
                append(
                    when (timing) {
                        Timing.ON_APPEAR -> "当画面出现时"
                        Timing.WHILE_PRESENT -> "画面在场期间"
                        Timing.ON_DISAPPEAR -> "当画面消失时"
                    },
                )
                append(
                    when (repetition) {
                        Repetition.ONCE -> "触发一次"
                        Repetition.RETRY_IF_PERSISTENT -> "触发一次；若画面未变化，稍后重试"
                        Repetition.CONTINUOUS -> "按间隔重复触发"
                    },
                )
            }

        /**
         * 收敛为**可被 [fromRule] 无损还原**的自洽组合。
         *
         * [Timing.WHILE_PRESENT]（在场期间）与 [Repetition.CONTINUOUS]（按冷却周期
         * 持续触发）在 [SceneRule] 上由同一组字段表达：`triggerMode = WHILE_PRESENT`
         * 且 `retryTimeoutMs = 0`，故两个维度并非正交：
         *  - 选择 CONTINUOUS 时触发时机归一为 WHILE_PRESENT；
         *  - 时机为 WHILE_PRESENT 且未启用重试时，重复策略只能是 CONTINUOUS。
         * 其余组合保持不变。[toRule] 与 [roundTrip] 均以本结果为基准，保证界面
         * 呈现的意图与落盘规则一一对应，不出现「已选择但未生效」的静默坍缩。
         */
        fun normalized(): Intent {
            val r = if (timing == Timing.WHILE_PRESENT && repetition == Repetition.ONCE)
                Repetition.CONTINUOUS
            else repetition
            val t = if (r == Repetition.CONTINUOUS) Timing.WHILE_PRESENT else timing
            return copy(timing = t, repetition = r)
        }
    }

    /** 默认意图：与 v2.9.15 拾取装配的保守默认值逐字对齐。 */
    val DEFAULT = Intent()

    /** 迟滞档位对应的连续帧数。 */
    fun streakOf(debounce: Debounce): Int = when (debounce) {
        Debounce.OFF -> 1
        Debounce.STANDARD -> 2
        Debounce.STRONG -> 3
    }

    /** 重复档位对应的冷却与重试时长（毫秒）。与 v2.9.15 默认常量对齐。 */
    fun repetitionMs(repetition: Repetition): Pair<Long, Long> = when (repetition) {
        Repetition.ONCE -> 400L to 0L
        Repetition.RETRY_IF_PERSISTENT -> 400L to 1500L
        Repetition.CONTINUOUS -> 400L to 0L
    }

    /**
     * 把意图展开成 [SceneRule] 的触发语义字段。
     *
     * 先经 [Intent.normalized] 消除维度耦合，再展开；因此任意 [Intent] 组合产出的规则
     * 都能被 [fromRule] 还原成同一个规范化意图。
     *
     * 只覆盖意图负责的字段（triggerMode / 迟滞 / cooldown / retry）。
     * 模板结构、坐标语义、相似度、掩码、优先级、互斥组、位置偏差阈值一律保留
     * [base] 原值，避免装配时把拾取阶段算出的稳定锚点数据覆盖掉。
     */
    fun toRule(intent: Intent, base: SceneRule = SceneRule()): SceneRule {
        val n = intent.normalized()
        val streak = streakOf(n.debounce)
        val (cooldown, retry) = repetitionMs(n.repetition)
        return base.copy(
            triggerMode = when (n.timing) {
                Timing.ON_APPEAR -> TriggerMode.ON_APPEAR
                Timing.WHILE_PRESENT -> TriggerMode.WHILE_PRESENT
                Timing.ON_DISAPPEAR -> TriggerMode.ON_DISAPPEAR
            },
            presentStreak = streak,
            absentStreak = streak,
            cooldownMs = cooldown,
            retryTimeoutMs = if (n.repetition == Repetition.CONTINUOUS) 0L else retry,
        )
    }

    /**
     * 把 [SceneRule] 折叠回意图；参数组合不在任何预设内时返回 null（界面显示「自定义」）。
     *
     * @param maxFiringPerRound 脚本级同轮上限，由调用方从 Script 读取。
     */
    fun fromRule(rule: SceneRule, maxFiringPerRound: Int = 1): Intent? {
        val streak = streakOf(Debounce.STANDARD)
        // 迟滞必须成对一致且落在已知档位，否则判自定义
        if (rule.presentStreak != rule.absentStreak) return null
        val debounce = when (rule.presentStreak) {
            streakOf(Debounce.OFF) -> Debounce.OFF
            streak -> Debounce.STANDARD
            streakOf(Debounce.STRONG) -> Debounce.STRONG
            else -> return null
        }
        // 冷却/重试必须匹配某个预设组合
        val expectedCooldown = 400L
        val (_, retryOnce) = repetitionMs(Repetition.ONCE)
        val (_, retryPersistent) = repetitionMs(Repetition.RETRY_IF_PERSISTENT)
        if (rule.cooldownMs != expectedCooldown) return null
        val repetition = when {
            rule.retryTimeoutMs == retryOnce &&
                rule.triggerMode != TriggerMode.WHILE_PRESENT -> Repetition.ONCE
            rule.retryTimeoutMs == retryPersistent -> Repetition.RETRY_IF_PERSISTENT
            rule.retryTimeoutMs == retryOnce &&
                rule.triggerMode == TriggerMode.WHILE_PRESENT -> Repetition.CONTINUOUS
            else -> return null
        }
        val timing = when (rule.triggerMode) {
            TriggerMode.ON_APPEAR -> Timing.ON_APPEAR
            TriggerMode.WHILE_PRESENT -> Timing.WHILE_PRESENT
            TriggerMode.ON_DISAPPEAR -> Timing.ON_DISAPPEAR
        }
        return Intent(timing, debounce, repetition, maxFiringPerRound)
    }

    /**
     * 判定某条规则是否可由预设表达，供界面决定是否展示高级参数区。
     * 与 [fromRule] 等价，保留独立命名以便调用点语义清晰。
     */
    fun isPreset(rule: SceneRule, maxFiringPerRound: Int = 1): Boolean =
        fromRule(rule, maxFiringPerRound) != null

    /**
     * 预设往返一致性（供单测与门禁使用）：以 [Intent.normalized] 后的意图为基准，
     * 断言任意意图组合展开成 [SceneRule] 后都能折叠回同一自洽意图。
     */
    fun roundTrip(intent: Intent, base: SceneRule = SceneRule(), k: Int = 1): Boolean =
        fromRule(toRule(intent, base), k) == intent.normalized().copy(maxFiringPerRound = k)

    // ---------------------------------------------------------------- 后果展开

    /**
     * **意图档位的后果说明**（v2.9.17 / D1，core 纯 JVM）。
     *
     * 意图层此前只提供档位名称，档位展开后的实际参数、运行时效果与权衡仍需
     * 操作者自行推断。本结构把四维意图逐项展开，使界面信息自足，不依赖真机
     * 回归确认档位语义；主层呈现 [Consequence.choice]，高级区呈现完整明细。
     * 本结构不参与落盘，也不改变 [SceneRule] 的任何字段。
     */
    data class Consequence(
        val dimension: String,
        val choice: String,
        val actual: String,
        val effect: String,
        val tradeoff: String,
    ) {
        /** 高级区单行明细：档位 + 效果 + 权衡 + 展开后的实际参数。 */
        val detailLine: String
            get() = "$choice · $effect · 权衡：$tradeoff · 实际生效：$actual"
    }

    data class Explanation(
        val headline: String,
        val consequences: List<Consequence>,
    ) {
        /** 主层单行摘要：只含档位名称，不呈现内核参数名与数值。 */
        val briefLine: String
            get() = consequences.joinToString(" · ") { "${it.dimension}：${it.choice}" }

        /** 高级区多行明细。 */
        val detailText: String
            get() = consequences.joinToString("\n") { "· ${it.dimension}：${it.detailLine}" }
    }

    private fun timingConsequence(t: Timing): Consequence = when (t) {
        Timing.ON_APPEAR -> Consequence(
            "触发时机", "画面出现时",
            "triggerMode = ON_APPEAR",
            "画面由未在场转为在场时判定一次",
            "画面持续在场不会再次触发",
        )
        Timing.WHILE_PRESENT -> Consequence(
            "触发时机", "画面在场期间",
            "triggerMode = WHILE_PRESENT",
            "画面在场期间按冷却周期判定",
            "在场持续期间会重复判定",
        )
        Timing.ON_DISAPPEAR -> Consequence(
            "触发时机", "画面消失时",
            "triggerMode = ON_DISAPPEAR",
            "画面由在场转为未在场时判定一次",
            "首次进入画面不触发",
        )
    }

    private fun debounceConsequence(d: Debounce): Consequence {
        val streak = streakOf(d)
        return when (d) {
            Debounce.OFF -> Consequence(
                "防抖强度", "不防抖",
                "presentStreak = absentStreak = $streak",
                "单帧命中即判定状态变化",
                "高频闪烁界面可能产生重复判定",
            )
            Debounce.STANDARD -> Consequence(
                "防抖强度", "标准防抖",
                "presentStreak = absentStreak = $streak",
                "连续 $streak 帧一致才判定状态变化",
                "响应较单帧判定延后约 1 帧",
            )
            Debounce.STRONG -> Consequence(
                "防抖强度", "强防抖",
                "presentStreak = absentStreak = $streak",
                "连续 $streak 帧一致才判定状态变化",
                "响应较单帧判定延后约 2 帧，抗闪烁能力最强",
            )
        }
    }

    private fun repetitionConsequence(r: Repetition): Consequence {
        val (cooldown, retry) = repetitionMs(r)
        val effectiveRetry = if (r == Repetition.CONTINUOUS) 0L else retry
        return when (r) {
            Repetition.ONCE -> Consequence(
                "重复与重试", "仅一次",
                "cooldownMs = $cooldown, retryTimeoutMs = $effectiveRetry",
                "触发后不主动重试",
                "动作未生效时不会自动补偿",
            )
            Repetition.RETRY_IF_PERSISTENT -> Consequence(
                "重复与重试", "未变化时重试",
                "cooldownMs = $cooldown, retryTimeoutMs = $effectiveRetry",
                "触发后画面持续在场超过 ${effectiveRetry}ms 允许再次触发",
                "在场期间按重试周期补偿",
            )
            Repetition.CONTINUOUS -> Consequence(
                "重复与重试", "按间隔重复",
                "cooldownMs = $cooldown, retryTimeoutMs = $effectiveRetry",
                "画面在场期间按冷却周期重复触发",
                "在场持续期间会持续产生动作",
            )
        }
    }

    private fun concurrencyConsequence(k: Int): Consequence = Consequence(
        "同轮上限", "同轮最多触发 $k 条",
        "sceneMaxFiringPerRound = $k",
        "单轮最多允许 $k 条规则触发，其余按优先级与互斥组仲裁",
        "多条场景同时出现时未入选的规则本轮不执行",
    )

    /**
     * 把意图展开为四维后果说明。以 [Intent.normalized] 为基准，保证呈现的档位
     * 与 [toRule] 实际写入的规则一致，不出现档位说明与实际生效参数不一致。
     */
    fun explain(intent: Intent, maxFiringPerRound: Int = intent.maxFiringPerRound): Explanation {
        val n = intent.normalized()
        val k = maxFiringPerRound.coerceAtLeast(1)
        return Explanation(
            headline = "${n.summary} · 同轮最多触发 $k 条",
            consequences = listOf(
                timingConsequence(n.timing),
                debounceConsequence(n.debounce),
                repetitionConsequence(n.repetition),
                concurrencyConsequence(k),
            ),
        )
    }

    /**
     * 全部档位的后果目录，供设置页静态对照使用。每条 [Consequence] 与 [explain]
     * 中同名档位的字段由同一组函数生成，避免两处各维护一套说明。
     */
    fun optionCatalog(): List<Consequence> =
        Timing.entries.map { timingConsequence(it) } +
            Debounce.entries.map { debounceConsequence(it) } +
            Repetition.entries.map { repetitionConsequence(it) }
}

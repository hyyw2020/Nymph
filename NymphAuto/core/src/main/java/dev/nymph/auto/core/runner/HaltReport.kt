// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

/**
 * 终止报告（v2.9.30，core 纯 JVM 单一真源）。
 *
 * ## 为什么终止要是一个「对象」而不是一句日志
 *
 * 上一版终止只产生一行 `onAbnormal` 文案。用户在别的应用界面里被停掉时，能看到的
 * 只有通知栏里消失的一行字；回到本应用，日志页里那句「已停止」既没说是哪一步停的，
 * 也没给回去的路径——于是只能从头再跑，或者重新授权一次。
 *
 * 本契约把终止收敛成结构化对象：**为什么停**（[kind]）、**停在哪**（[resumeStep] /
 * [resumeRule]）、**能做什么**（[actions]）。三件事都齐了，「不逼用户重启或重新授权」
 * 才不是一句口号。
 *
 * ## 可恢复性的判定规则
 *
 * - 用户主动停止 / 切后台超时 / 落点失配：界面状态还在，可以**从这一步继续**。
 * - 通道丢失：必须先重建连接，重建成功后才能继续——因此单列 [HaltAction.RESUME]，
 *   但由上层决定它是否需要先走一次 `ACQUIRING`。
 * - 脚本自身错误（语法、表达式、必填参数缺失）：继续只会再错一次，不提供恢复。
 *
 * 纯数据 + 纯函数，不含任何 UI 依赖。
 */
object HaltReport {

    /** 终止原因类别。 */
    enum class Kind {
        /** 用户点了「停止」。 */
        USER_STOP,

        /** 用户点了「归还连接」。 */
        USER_HANDOVER,

        /** 通道被动丢失，且降级通道未就绪 / 切换失败。 */
        LINK_LOST,

        /** 连续落点偏差达到确定性上界（门控参数失配）。 */
        GATE_MISMATCH,

        /** 脚本自身错误（致命，继续会重复失败）。 */
        SCRIPT_ERROR,

        /** 切到后台超时。 */
        BACKGROUND_TIMEOUT,

        /** 看门狗判定卡死。 */
        WATCHDOG,
    }

    /** 用户可执行的动作。 */
    enum class HaltAction {
        /** 查看原因（打开日志页并定位到本次终止）。 */
        VIEW_REASON,

        /** 从停下的这一步继续。 */
        RESUME,

        /** 结束，回到待机。 */
        STOP,
    }

    /**
     * 一份终止报告。
     *
     * @param resumeStep 顶层步号（1-based）；0 表示无步号可恢复。
     * @param resumeRule 场景驱动下的规则序号（1-based）；0 表示非场景驱动。
     * @param resumeLabel 停下那一步的可读描述。
     */
    data class Report(
        val kind: Kind,
        val headline: String,
        val detail: String,
        val resumeStep: Int,
        val resumeRule: Int,
        val resumeLabel: String,
        val actions: List<HaltAction>,
    ) {
        /** 是否有可恢复位置。 */
        val resumable: Boolean get() = HaltAction.RESUME in actions

        /** 通知栏副标题：一句话说清停在哪。 */
        val shortText: String
            get() = when {
                resumeRule > 0 -> "$headline（规则 $resumeRule）"
                resumeStep > 0 -> "$headline（第 $resumeStep 步）"
                else -> headline
            }
    }

    /**
     * 构造报告。
     *
     * @param detail 技术明细，进日志与导出；headline 由 [headlineOf] 生成，不覆盖。
     */
    fun of(
        kind: Kind,
        detail: String = "",
        resumeStep: Int = 0,
        resumeRule: Int = 0,
        resumeLabel: String = "",
    ): Report = Report(
        kind = kind,
        headline = headlineOf(kind),
        detail = detail,
        resumeStep = resumeStep.coerceAtLeast(0),
        resumeRule = resumeRule.coerceAtLeast(0),
        resumeLabel = resumeLabel,
        actions = actionsOf(kind, resumable = kind.resumable),
    )

    /** 可读主文案：说清「为什么停」，不出现内部术语。 */
    fun headlineOf(kind: Kind): String = when (kind) {
        Kind.USER_STOP -> "已按你的要求停止"
        Kind.USER_HANDOVER -> "连接已归还，不再占用读屏"
        Kind.LINK_LOST -> "连接已断开，且降级通道未就绪"
        Kind.GATE_MISMATCH -> "连续多次对不上画面，已停止"
        Kind.SCRIPT_ERROR -> "脚本本身有错误，已停止"
        Kind.BACKGROUND_TIMEOUT -> "切到后台太久，已停止"
        Kind.WATCHDOG -> "长时间没有进展，已停止"
    }

    /** 各类终止的可执行动作集合。 */
    fun actionsOf(kind: Kind, resumable: Boolean = kind.resumable): List<HaltAction> = buildList {
        add(HaltAction.VIEW_REASON)
        if (resumable) add(HaltAction.RESUME)
        add(HaltAction.STOP)
    }

    /** 该类终止是否可从未完成处继续。 */
    private val Kind.resumable: Boolean
        get() = when (this) {
            Kind.USER_STOP, Kind.BACKGROUND_TIMEOUT, Kind.GATE_MISMATCH, Kind.LINK_LOST -> true
            Kind.USER_HANDOVER, Kind.SCRIPT_ERROR, Kind.WATCHDOG -> false
        }

    /** 归还连接成功后的固定报告（供通知与面板共用，避免文案分叉）。 */
    fun handedOver(): Report = of(Kind.USER_HANDOVER, detail = "RELEASE 完成，注入器已置空")
}

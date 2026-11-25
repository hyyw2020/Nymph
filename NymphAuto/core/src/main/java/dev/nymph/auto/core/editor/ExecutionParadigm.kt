// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

/**
 * **执行范式同构呈现**（v2.9.17 / D2，core 纯 JVM，可单测）。
 *
 * 场景驱动与顺序执行此前在界面上采用两种信息结构：场景侧有意图摘要，顺序侧
 * 仅有一句静态说明。本对象把两种范式统一投影成同一组四字段卡片，使范式切换
 * 时操作者面对同一组维度，降低两种心智模型并存带来的认知负担。
 *
 * 设计边界：本对象只做呈现层投影，不触碰落盘字段、不改动 `ScriptRunner` 的
 * 执行循环。统一 `select-ready` 循环（B1）仍按既定边界后置，本版不实现。
 */
object ExecutionParadigm {

    enum class Kind {
        /** 画面条件命中时触发对应动作。 */
        SCENE_DRIVEN,

        /** 按动作序号顺序推进。 */
        SEQUENTIAL,
    }

    data class Field(val label: String, val value: String)

    data class Card(
        val kind: Kind,
        val title: String,
        val fields: List<Field>,
        val switchHint: String,
    ) {
        /** 面板高级区多行明细。 */
        val detailText: String
            get() = fields.joinToString("\n") { "· ${it.label}：${it.value}" }
    }

    /** 设置页范式对照表的一行。 */
    data class Row(val label: String, val sceneDriven: String, val sequential: String)

    private const val L_TRIGGER = "触发依据"
    private const val L_ORDER = "执行次序"
    private const val L_CONDITION = "画面条件"
    private const val L_FAILURE = "失败处理"

    private fun values(kind: Kind, maxFiringPerRound: Int, ruleCount: Int): List<Field> =
        when (kind) {
            Kind.SCENE_DRIVEN -> listOf(
                Field(L_TRIGGER, "画面条件匹配：锚点模板 + 带状态触发判定"),
                Field(L_ORDER, "无先后顺序；单轮最多 $maxFiringPerRound 条，按优先级与互斥组仲裁"),
                Field(L_CONDITION, "当前脚本 $ruleCount 条规则，各自持有锚点模板与搜索区域"),
                Field(L_FAILURE, "画面不可用时不触发该规则，并写入诊断日志（fail-closed）"),
            )
            Kind.SEQUENTIAL -> listOf(
                Field(L_TRIGGER, "动作序号推进（pc++）"),
                Field(L_ORDER, "按下标顺序逐条执行"),
                Field(L_CONDITION, "执行次序不依赖画面状态；动作自带的等待与找图条件单独求值"),
                Field(L_FAILURE, "按脚本设定的等待与重试策略处理"),
            )
        }

    /**
     * 生成当前动作的范式卡片。
     *
     * @param kind 当前脚本的执行范式，由 `Script.sceneDriven` 决定。
     * @param maxFiringPerRound 脚本级同轮上限，仅场景驱动侧呈现。
     * @param ruleCount 当前脚本中持有场景规则的条数，用于说明画面条件覆盖范围。
     */
    fun describe(
        kind: Kind,
        maxFiringPerRound: Int = 1,
        ruleCount: Int = 0,
    ): Card = Card(
        kind = kind,
        title = when (kind) {
            Kind.SCENE_DRIVEN -> "场景触发"
            Kind.SEQUENTIAL -> "顺序执行"
        },
        fields = values(kind, maxFiringPerRound.coerceAtLeast(1), ruleCount.coerceAtLeast(0)),
        switchHint = when (kind) {
            Kind.SCENE_DRIVEN -> "如需按步骤顺序执行，请在脚本设置中关闭场景驱动"
            Kind.SEQUENTIAL -> "如需按画面条件触发，请在脚本设置中开启场景驱动并为动作拾取锚点"
        },
    )

    /** 两种范式的四维对照，供设置页随时查阅，不依赖首次装配的一次性提示。 */
    fun comparison(maxFiringPerRound: Int = 1): List<Row> {
        val scene = values(Kind.SCENE_DRIVEN, maxFiringPerRound, 0)
        val seq = values(Kind.SEQUENTIAL, maxFiringPerRound, 0)
        return scene.indices.map { i ->
            Row(scene[i].label, scene[i].value, seq[i].value)
        }
    }

    /** 从落盘布尔值映射到范式枚举，供界面装配点使用。 */
    fun of(sceneDriven: Boolean): Kind =
        if (sceneDriven) Kind.SCENE_DRIVEN else Kind.SEQUENTIAL
}

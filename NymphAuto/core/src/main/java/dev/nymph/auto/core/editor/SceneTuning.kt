// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.SceneRule
import kotlin.math.abs

/**
 * **场景判据参数精调**（v2.9.32，core 纯 JVM 单一真源，零 Android 依赖）。
 *
 * ## 修的是什么
 *
 * 「拾取指针后灵活配置参数」此前只在界面呈现为只读文案：`FloatingEditorPanel`
 * 的触发策略区把落盘规则折叠回意图说明，但 `collect()` 只收集动作层字段，
 * `SceneRule` 的判据参数（相似度阈值、颜色容差、连续稳定帧数、冷却、重试、
 * 落点偏差）没有任何写回入口；装配期一律用 `ScenePreset.DEFAULT` 展开。
 * 更关键的是 `save()` 中 `pendingSceneRoi` 每次保存后即置空，用户拾取后
 * **仅调整参数再保存**时 `sceneRoi == null`，`sceneRule` 完全不更新，参数
 * 改动被静默丢弃。本对象把「可调判据参数」收敛成一份可往返、可单测的契约，
 * 由面板收集、Service 应用到 [SceneRule]，运行期 `ScriptRunner` 已在消费这些
 * 字段，故无需改动执行链即可生效。
 *
 * ## 设计约束
 *
 * - **不新增落盘字段**：全部参数映射到 [SceneRule] 既有字段，`schemaVersion`
 *   保持 1，历史脚本零迁移；不引入新的序列化形状。
 * - **留空沿用、填值覆盖**：装配期 `calibrateSimilarity` / `sampleNeighborhood`
 *   自校准出的阈值是 v2.9.31「装配-运行同源」的成果。空串代表「沿用装配值」，
 *   绝不把面板默认值（如 82）覆盖掉实测值；只有用户显式填写才钳制后覆盖。
 *   这同时保证老脚本、未展开高级区的用户、以及仅改场景名再保存的路径零回归。
 * - **意图层与判据层分层**：触发时机 / 防抖 / 重复策略仍由 [ScenePreset] 的
 *   三维意图单一真源展开（离散枚举，界面用档位选择）；本对象只负责连续数值
 *   判据（阈值、时长、偏差）。两层写入不重叠字段，避免互相覆盖。
 * - **纯函数**：不读时钟、不持位图、不依赖 Android；解析、钳制、步进、往返
 *   全部由入参显式驱动，便于 JVM 单测穷举边界。
 */
object SceneTuning {

    /** 一个可调判据字段：键、短标签、边界、步进、单位，以及与 [SceneRule] 的读写映射。 */
    data class Field(
        val key: String,
        val label: String,
        val short: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: String,
        /** 从落盘规则读出的当前值（用于回显与「沿用」摘要）。 */
        val read: (SceneRule) -> Double,
        /** 把钳制后的值写入规则；只改本字段，其余字段逐字保留。 */
        val write: (SceneRule, Double) -> SceneRule,
    )

    /**
     * 相似度阈值（百分制）。运行期由 `TemplateSpec.minSimilar` 消费，
     * 是「命中准确率 / 误触发率」的主权衡：过高漏检，过低误点。
     * 下界与 `SceneEvidence.MIN_SAFE_SIMILARITY` 对齐，防止阈值被压到误匹配区。
     */
    val SIMILARITY = Field(
        key = "similarity", label = "相似度阈值", short = "相似",
        min = 55.0, max = 99.0, step = 2.0, unit = "%",
        read = { it.similarity.toDouble() },
        write = { r, v -> r.copy(similarity = v.toInt()) },
    )

    /**
     * 颜色容差（亮度加权 RGB 距离 0~255 口径）。运行期由分层判据
     * `SceneEvidence.Evidence.colorTolerance` 消费。0 = 沿用装配采样值，
     * 不启用手工覆盖；显式填写时下限 1，避免把判据关闭成纯 NCC。
     */
    val COLOR_TOLERANCE = Field(
        key = "colorTolerance", label = "颜色容差", short = "色容",
        min = 1.0, max = 120.0, step = 5.0, unit = "",
        read = { it.colorTolerance.toDouble() },
        write = { r, v -> r.copy(colorTolerance = v.toInt()) },
    )

    /**
     * 分层判据要求的连续「疑似在场」帧数 N。运行期由
     * `SceneEvidence.Evidence.streak` 消费。增大抑制瞬态闪烁、降低误触发，
     * 代价是响应延后；减小提升点击效率，代价是抗抖动能力下降。
     */
    val EVIDENCE_STREAK = Field(
        key = "evidenceStreak", label = "连续稳定帧数", short = "稳定",
        min = 1.0, max = 10.0, step = 1.0, unit = "帧",
        read = { it.evidenceStreak.toDouble() },
        write = { r, v -> r.copy(evidenceStreak = v.toInt()) },
    )

    /**
     * 触发后冷却（毫秒）。运行期由 `SceneTrigger` 状态机消费，抑制
     * 「自己制造消失」后的连点。对应竞品的「等待时间可按机型微调」。
     */
    val COOLDOWN_MS = Field(
        key = "cooldownMs", label = "触发后冷却", short = "冷却",
        min = 0.0, max = 60_000.0, step = 100.0, unit = "ms",
        read = { it.cooldownMs.toDouble() },
        write = { r, v -> r.copy(cooldownMs = v.toLong()) },
    )

    /**
     * 触发后场景持续在场多久允许重试（毫秒）。运行期由 `SceneTrigger`
     * 消费，修复「触发后无新上升沿」的补偿路径；0 = 关闭重试。
     */
    val RETRY_TIMEOUT_MS = Field(
        key = "retryTimeoutMs", label = "失败重试等待", short = "重试",
        min = 0.0, max = 60_000.0, step = 100.0, unit = "ms",
        read = { it.retryTimeoutMs.toDouble() },
        write = { r, v -> r.copy(retryTimeoutMs = v.toLong()) },
    )

    /**
     * 落点偏差二次校验阈值（屏幕百分比）。运行期由
     * `SceneTrigger.resolveSceneClick` 消费，超过即判「场景疑似错位」不派发。
     * 收紧可降低误点误差，放宽可提升点击效率。
     */
    val LOC_DEVIATION_PCT = Field(
        key = "locMaxDeviationPct", label = "落点偏差阈值", short = "偏差",
        min = 1.0, max = 50.0, step = 1.0, unit = "%",
        read = { it.locMaxDeviationPct },
        write = { r, v -> r.copy(locMaxDeviationPct = v) },
    )

    /** 全部可调判据字段，顺序即面板呈现顺序。 */
    fun fields(): List<Field> = listOf(
        SIMILARITY, COLOR_TOLERANCE, EVIDENCE_STREAK, COOLDOWN_MS, RETRY_TIMEOUT_MS, LOC_DEVIATION_PCT,
    )

    fun field(key: String): Field? = fields().firstOrNull { it.key == key }

    /**
     * 面板收集到的判据参数（原始字符串，与 `FloatingEditorLayout.Form` 同风格：
     * 解析与容错收敛为纯函数，不在界面层做数值转换）。
     *
     * **空串 = 沿用装配值**；全部字段缺省空串，故 `Config()` 应用到任何规则
     * 都是恒等变换，老脚本与未展开高级区的路径零回归。
     */
    data class Config(
        val similarity: String = "",
        val colorTolerance: String = "",
        val evidenceStreak: String = "",
        val cooldownMs: String = "",
        val retryTimeoutMs: String = "",
        val locMaxDeviationPct: String = "",
    ) {
        /** 按字段键读取原始文本。 */
        fun raw(f: Field): String = when (f.key) {
            "similarity" -> similarity
            "colorTolerance" -> colorTolerance
            "evidenceStreak" -> evidenceStreak
            "cooldownMs" -> cooldownMs
            "retryTimeoutMs" -> retryTimeoutMs
            "locMaxDeviationPct" -> locMaxDeviationPct
            else -> ""
        }

        /** 返回把 [f] 的文本替换为 [v] 的新配置（不可变，便于单测）。 */
        fun withRaw(f: Field, v: String): Config = when (f.key) {
            "similarity" -> copy(similarity = v)
            "colorTolerance" -> copy(colorTolerance = v)
            "evidenceStreak" -> copy(evidenceStreak = v)
            "cooldownMs" -> copy(cooldownMs = v)
            "retryTimeoutMs" -> copy(retryTimeoutMs = v)
            "locMaxDeviationPct" -> copy(locMaxDeviationPct = v)
            else -> this
        }
    }

    /**
     * 从落盘规则读出全部当前值，用于面板回显（切场景 / 载入既有场景时）。
     *
     * 边界外的值（如 `colorTolerance = 0` 表示「未启用颜色判据」）回显为空串，
     * 即「沿用装配值」：否则 `text` 的钳制会把 0 显示成 1，用户不改动直接保存时
     * `applyTo` 反而把「未启用」改写成「启用」，构成静默回归。
     */
    fun from(rule: SceneRule): Config = fields().fold(Config()) { acc, f ->
        val raw = f.read(rule)
        val inRange = raw.isFinite() && raw >= f.min && raw <= f.max
        acc.withRaw(f, if (inRange) rawText(f, raw) else "")
    }

    /** 夹取到字段边界；非有限值返回 null 由调用方处理。 */
    fun coerce(f: Field, raw: Double): Double? {
        if (!raw.isFinite()) return null
        return raw.coerceIn(f.min, f.max)
    }

    /**
     * 解析面板原始文本：空串 / 空白 / 非数值 / 越界到不可解析 → null，
     * 表示「沿用装配值」；合法数值钳制到 `[min, max]`。
     */
    fun parse(f: Field, raw: String): Double? {
        val v = raw.trim().toDoubleOrNull() ?: return null
        return coerce(f, v)
    }

    /**
     * 把面板配置应用到 [base]。逐字段独立：空串沿用装配值，合法值钳制后覆盖。
     * 未涉及的 A1 模板结构 / A2 坐标语义 / A5 自测观测 / A6 同源证据 /
     * `pickRotation` 方向元数据全部逐字保留，装配-运行同源不破坏。
     */
    fun applyTo(base: SceneRule, config: Config): SceneRule =
        fields().fold(base) { rule, f ->
            val v = parse(f, config.raw(f)) ?: return@fold rule
            f.write(rule, v)
        }

    /** 该配置是否至少显式覆盖了一个判据字段（供日志与门禁判定接线是否生效）。 */
    fun hasOverride(config: Config): Boolean = fields().any { parse(it, config.raw(it)) != null }

    /** 步进一次：留空加号给「当前装配值 + 一档」，减号给下限；步进结果始终在边界内。 */
    fun bump(f: Field, raw: String, dir: Int, assembled: Double = f.min): Double {
        val parsed = raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        val base = parsed ?: assembled.coerceIn(f.min, f.max)
        val next = if (parsed == null && dir < 0) f.min else base + f.step * dir
        return next.coerceIn(f.min, f.max)
    }

    /** 数值写回输入框的文本（钳制后）：整数不带小数点，非整数保留一位小数。 */
    fun text(f: Field, v: Double): String = rawText(f, v.coerceIn(f.min, f.max))

    /** 数值格式化（不钳制）：用于回显与摘要，保证「所见即落盘值」。Locale 安全。 */
    fun rawText(f: Field, v: Double): String {
        if (!v.isFinite()) return ""
        val rounded = Math.round(v).toDouble()
        return if (abs(v - rounded) < 1e-9) rounded.toLong().toString() else
            String.format(java.util.Locale.US, "%.1f", v)
    }

    /** 输入框占位文本：明确「留空沿用装配值」，避免用户以为必须逐项填写。 */
    fun hintOf(f: Field, assembled: Double): String =
        "留空沿用 ${rawText(f, assembled)}${f.unit} · ${f.label}"

    /** 一行摘要：把全部当前生效值呈现为可核对清单，不含内核参数名。 */
    fun summary(rule: SceneRule): String = fields().joinToString(" · ") { f ->
        "${f.label} ${rawText(f, f.read(rule))}${f.unit}"
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import java.util.Locale

/**
 * 拾取结果的纠偏反馈契约（v2.9.22，纯 JVM）。
 *
 * 职责边界：把 [TapGate] 的判定结论翻译成「用户能看懂的一句话 + 一个主修复动作」，
 * 并给出自动派生结果的置信度分级与可复制的诊断摘要。本契约不做判定、不持有位图、
 * 不落盘、不联网，只做确定性的文案与映射，保证界面与文档共用同一真源。
 *
 * 设计约束：
 * - 未命中原因必须**每个都有唯一的主动作**，用户无需理解容差、颜色距离等内部参数；
 * - 置信度只是呈现分级，不改变自动区域的数值，也不阻断保存；
 * - 诊断摘要只含复现所需的中性事实，不含脚本名、场景名等可能涉及隐私的内容，
 *   也不含内部实现术语（见 [INTERNAL_TERMS] 与 [leaksInternalTerm]）；
 * - 统计只在进程内累加，用于诊断摘要，不写入任何持久化存储。
 */
object PickFeedback {

    /** 可一键执行的修复动作；[NONE] 表示该结论无需修复。 */
    enum class Action {
        NONE,
        REPICK,
        EDIT_ROI,
        CLEAR_MANUAL,
        RESET_AUTO,
        RESAMPLE,
        RETRY,
    }

    /**
     * 一条结论的呈现载荷。[primaryLabel] 为空表示无需修复，界面不呈现修复按钮。
     */
    data class Guidance(
        val headline: String,
        val primaryLabel: String?,
        val primary: Action,
    ) {
        val fixable: Boolean get() = primary != Action.NONE && !primaryLabel.isNullOrBlank()
    }

    /**
     * 判定结论到「用户可读结论 + 主修复动作」的单一真源。
     *
     * [hasManualRoi] 用于区分「区域本身为空」与「手动覆盖把区域框成了空」：
     * 后者应引导清除手动覆盖而不是重新拾取。
     */
    fun guidanceFor(reason: TapGate.Reason, hasManualRoi: Boolean = false): Guidance = when (reason) {
        TapGate.Reason.PASS -> Guidance("已命中：区域与颜色校验通过", null, Action.NONE)
        TapGate.Reason.PENDING -> Guidance("正在校验，请稍候", null, Action.NONE)
        TapGate.Reason.OUT_OF_BOUNDS -> Guidance(
            "拾取点落在有效区域之外", "扩大区域", Action.EDIT_ROI,
        )
        TapGate.Reason.EMPTY_ROI -> if (hasManualRoi) {
            Guidance("手动框选的区域为空", "清除手动区域", Action.CLEAR_MANUAL)
        } else {
            Guidance("有效区域为空，无法校验", "重新拾取", Action.REPICK)
        }
        TapGate.Reason.COLOR_MISMATCH -> Guidance(
            "该位置颜色与样本不符", "重新采样颜色", Action.RESAMPLE,
        )
        TapGate.Reason.CAPTURE_UNAVAILABLE -> Guidance(
            "截图未取到，未能校验颜色", "重试截图", Action.RETRY,
        )
        TapGate.Reason.ASSEMBLY_FAILED -> Guidance(
            "区域装配失败，未能建立校验依据", "重新拾取", Action.REPICK,
        )
        TapGate.Reason.SAMPLE_EMPTY -> Guidance(
            "拾取点邻域未采到像素", "重新采样颜色", Action.RESAMPLE,
        )
        TapGate.Reason.ASYNC_FAILURE -> Guidance(
            "校验未完成", "重试", Action.RETRY,
        )
    }

    /** 自动派生结果的置信度分级（仅呈现，不改变区域数值）。 */
    enum class Confidence { HIGH, MEDIUM, LOW }

    /**
     * 由颜色样本的邻域离散度与有效采样点数推导置信度：
     * 采样失败一律低置信；离散度低（纯色稳定）为高，中段为中，高离散为低。
     */
    fun confidenceOf(
        sample: SceneAssist.ColorSample?,
        highSpread: Int = 24,
        mediumSpread: Int = 72,
    ): Confidence {
        if (sample == null || !sample.valid) return Confidence.LOW
        return when {
            sample.spread <= highSpread -> Confidence.HIGH
            sample.spread <= mediumSpread -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
    }

    /** 置信度轻提示：不弹窗、不打断，低置信度时明确建议手动框选。 */
    fun confidenceHint(c: Confidence): String = when (c) {
        Confidence.HIGH -> "已自动生成区域"
        Confidence.MEDIUM -> "区域已生成，纹理较复杂，建议核对"
        Confidence.LOW -> "区域不确定，建议手动框选"
    }

    /** 置信度标签（状态行与诊断摘要共用）。 */
    fun confidenceLabel(c: Confidence): String = when (c) {
        Confidence.HIGH -> "高"
        Confidence.MEDIUM -> "中"
        Confidence.LOW -> "低"
    }

    /** 区域来源的常驻状态条文案；手动清除后明确回退自动。 */
    fun roiModeLabel(mode: SceneAssist.RoiMode): String = when (mode) {
        SceneAssist.RoiMode.AUTO -> "自动区域"
        SceneAssist.RoiMode.MANUAL -> "手动区域"
    }

    /** 手动覆盖存在时的补充说明，供状态条在「手动区域」后追加。 */
    fun manualOverlayNote(hasManual: Boolean): String =
        if (hasManual) "（手动覆盖中，可清除回退自动）" else ""

    /** 计数键：进程内诊断口径，不落盘、不联网。 */
    object Keys {
        const val PICK = "拾取"
        const val REPICK = "重新拾取"
        const val ROI_EDIT = "区域编辑"
        const val MANUAL_CLEAR = "清除手动"
        const val AUTO_RESET = "恢复自动"
        const val RESAMPLE = "重采颜色"
        const val STALE_DROP = "过期丢弃"
        const val GATE_PASS = "判定命中"
        const val GATE_FAIL = "判定未命中"
        const val DIAG_COPY = "诊断复制"
    }

    private val ALL_KEYS = listOf(
        Keys.PICK, Keys.REPICK, Keys.ROI_EDIT, Keys.MANUAL_CLEAR, Keys.AUTO_RESET,
        Keys.RESAMPLE, Keys.STALE_DROP, Keys.GATE_PASS, Keys.GATE_FAIL, Keys.DIAG_COPY,
    )

    /**
     * 进程内计数器：只服务诊断摘要，生命周期与进程一致。
     * 明确不写入 SharedPreferences、文件或任何持久化介质，也不上报网络。
     */
    class Counters {
        private val values = HashMap<String, Int>()

        fun inc(key: String, delta: Int = 1): Int {
            val next = (values[key] ?: 0) + delta
            values[key] = next
            return next
        }

        fun get(key: String): Int = values[key] ?: 0

        /** 固定键顺序的快照，缺失键补 0，保证摘要字段稳定可比。 */
        fun snapshot(): List<Pair<String, Int>> = ALL_KEYS.map { it to get(it) }

        fun reset() = values.clear()
    }

    /** 诊断摘要的分段入参；全部为中性事实，不含隐私文本。 */
    data class Diag(
        val versionName: String,
        val versionCode: Int,
        val gesture: String,
        val xPct: Double,
        val yPct: Double,
        val roiMode: String,
        val roiText: String,
        val colorText: String,
        val confidence: String,
        val reason: String,
        val focusState: String,
        val counters: List<Pair<String, Int>>,
        val extras: List<Pair<String, String>> = emptyList(),
    )

    /**
     * 生成可一键复制的诊断摘要（纯文本，逐行键值）。
     *
     * 字段刻意保持「版本 / 手势 / 坐标 / 区域 / 颜色 / 置信度 / 结论 / 焦点」的中性表述，
     * 使客服与开发者能凭摘要复现，同时不泄露脚本内容与命名。
     */
    fun diagnose(d: Diag): String = buildString {
        appendLine("NymphAuto 诊断摘要")
        appendLine("版本 ${d.versionName} (${d.versionCode})")
        appendLine("手势 ${d.gesture}")
        appendLine(
            String.format(
                Locale.US, "坐标 %.2f%% , %.2f%%", d.xPct, d.yPct,
            ),
        )
        appendLine("区域 ${d.roiMode} ${d.roiText}")
        appendLine("颜色 ${d.colorText}")
        appendLine("置信度 ${d.confidence}")
        appendLine("结论 ${d.reason}")
        appendLine("焦点 ${d.focusState}")
        d.extras.forEach { (k, v) -> appendLine("$k $v") }
        appendLine("计数 " + d.counters.joinToString(" · ") { "${it.first} ${it.second}" })
        append("（本摘要仅含复现所需事实，不含脚本名与场景名）")
    }

    /**
     * 内部实现术语黑名单：诊断摘要面向用户与客服，不得出现内核概念。
     * 单测与静态门禁据此校验摘要模板，防止后续改动把内部词带进用户视野。
     */
    val INTERNAL_TERMS = listOf(
        "SceneRule", "StableAnchor", "anchorB64", "searchRoi", "MismatchGuard",
        "schemaVersion", "featureHash", "similarity", "NCC", "argb",
    )

    fun leaksInternalTerm(text: String): Boolean =
        INTERNAL_TERMS.any { text.contains(it, ignoreCase = true) }
}

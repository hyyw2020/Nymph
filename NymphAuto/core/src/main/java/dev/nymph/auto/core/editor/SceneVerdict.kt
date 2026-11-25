// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import java.util.Locale
import dev.nymph.auto.core.model.SceneRule
import kotlin.math.abs

/**
 * **拾取自测结论层**（v2.9.16 / A2，core 纯 JVM，可单测）。
 *
 * 目的：把 [SceneSelfTest.Result] 的三项原始观测量（最高命中分 / 次高干扰分 /
 * 位置偏差）映射成四态结论，由系统给出判定与建议动作，不把校准决策转嫁给操作者。
 * 原始分数仍保留在 [Assessment.detail] 中供诊断视图查阅，但主界面只呈现
 * [Assessment.headline] 与 [Assessment.action]。
 *
 * 判定优先级按「失败代价」排序：先排除画面不可用，再排除锚点区分度不足，
 * 最后校验落点一致性；任一环节不通过即不落盘为可用门控（fail-closed）。
 * 本对象是纯函数，不修改 [SceneSelfTest.Result]，不改变 [SceneRule] 落盘格式。
 */
object SceneVerdict {

    enum class Kind {
        /** 未取得有效画面（secure 窗口黑帧 / 截图通道不可用 / 全屏无有效纹理）。 */
        NO_FRAME,

        /** 锚点与周边干扰项区分度不足，运行时易误判。 */
        LOW_DISTINCTNESS,

        /** 匹配位置与拾取点偏差超出预算，落点不可信。 */
        LOCATION_DEVIATION,

        /** 各项指标达标。 */
        PASS,
    }

    /**
     * 一条可执行的结论。
     *
     * @param headline 主界面一句话结论（不含原始分数、不含内核术语）。
     * @param detail 诊断视图中的技术明细（原始分数与阈值）。
     * @param action 建议动作；null 表示无需操作。
     * @param recommendRetry 是否提示重新拾取。
     * @param usable 该锚点是否可作为画面门控落盘（false 时运行时该规则不触发）。
     */
    data class Assessment(
        val kind: Kind,
        val headline: String,
        val detail: String,
        val action: String?,
        val recommendRetry: Boolean,
        val usable: Boolean,
    )

    /** 画面不可用判定阈值：连搜索 ROI 内都几乎匹配不上即可判定。 */
    const val BLANK_FRAME_THRESHOLD = 0.45

    /**
     * 生成结论。
     *
     * @param result 拾取即自测结果。
     * @param calibratedSimilarity 系统自校准后的相似度阈值（百分制），仅用于明细呈现。
     * @param maxDeviationPct 位置偏差预算（屏幕百分比），来自 [SceneRule.locMaxDeviationPct]。
     */
    fun assess(
        result: SceneSelfTest.Result,
        calibratedSimilarity: Int,
        maxDeviationPct: Double = SceneRule.DEFAULT_LOC_MAX_DEVIATION_PCT,
    ): Assessment {
        val unmeasured = result.best < 0.0
        val detail = buildString {
            append("最高命中分 ${fmt(result.best)} · 次高干扰分 ${fmt(result.runnerUp)}")
            append(" · 位置偏差 ${fmtPct(result.locDeviationPct)}")
            append(" · 相似度阈值 $calibratedSimilarity · 偏差预算 ${fmtPct(maxDeviationPct)}")
        }

        // 1) 画面不可用：未测或 ROI 内几乎无匹配
        if (unmeasured || result.best < BLANK_FRAME_THRESHOLD) {
            return Assessment(
                kind = Kind.NO_FRAME,
                headline = "未取得有效画面，锚点不可用",
                detail = detail,
                action = "检查无障碍截图权限后重新拾取",
                recommendRetry = true,
                usable = false,
            )
        }

        // 2) 区分度不足：最高分与次高干扰分未拉开安全余量
        val margin = result.best - result.runnerUp
        if (margin < SceneRule.CALIBRATION_MARGIN) {
            return Assessment(
                kind = Kind.LOW_DISTINCTNESS,
                headline = "当前位置特征不够独特，容易误判",
                detail = "$detail · 分数余量 ${fmt(margin)}",
                action = "改选图标或文字标签等更独特的局部，重新拾取",
                recommendRetry = true,
                usable = false,
            )
        }

        // 3) 落点一致性：无命中或匹配位置偏离拾取点超预算
        if (result.locDeviationPct < 0.0 || result.locDeviationPct > maxDeviationPct) {
            return Assessment(
                kind = Kind.LOCATION_DEVIATION,
                headline = "匹配位置与拾取点不一致，落点不可信",
                detail = detail,
                action = "重新拾取，使锚点与落点保持在同一区域",
                recommendRetry = true,
                usable = false,
            )
        }

        // 4) 达标：明确呈现系统已完成的阈值自校准，而非让操作者判断分数
        return Assessment(
            kind = Kind.PASS,
            headline = "锚点校验通过，可启用画面门控",
            detail = "$detail · 分数余量 ${fmt(margin)}",
            action = null,
            recommendRetry = false,
            usable = true,
        )
    }

    /** 兼容旧格式的便捷入口：未测结果直接判 NO_FRAME。 */
    fun ofUnmeasured(): Assessment = assess(
        SceneSelfTest.Result(-1.0, -1.0, -1.0, -1, -1),
        SceneRule.SIMILARITY_BASELINE,
    )

    private fun fmt(v: Double): String = if (v < 0.0) "未测" else String.format(Locale.US, "%.2f", v)
    private fun fmtPct(v: Double): String = if (v < 0.0) "未测" else String.format(Locale.US, "%.1f%%", abs(v))
}

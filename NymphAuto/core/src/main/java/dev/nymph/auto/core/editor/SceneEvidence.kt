// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.MaskRect
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule
import kotlin.math.abs

/**
 * **装配-运行同源判据**（v2.9.31，core 纯 JVM 单一真源，零 Android 依赖）。
 *
 * ## 修的是什么
 *
 * v2.9.30 真机 RCA 的确证结论：装配期用「取色样本 + 收敛有效区域 + 同帧自测
 * 命中 1.00」给出「PASS 允许点击」，但运行期只拿一张 40×40 锚点模板要求跨帧
 * NCC ≥ 阈值，且 `loc=-` 时连有效落点都不产生。装配证据与运行判据**不同源**
 * （train–serve skew），叠加同帧自证循环，导致目标场景完全呈现仍 0 触发。
 *
 * 本对象把「场景出现」重新定义为**分层判据**，并让装配证据真正进入运行期：
 *
 * ```
 * 场景出现 = NCC 命中
 *          ∨ (疑似在场 ∧ 取色在容差内 ∧ 最佳窗口落在收敛 ROI 内 ∧ 连续 N 帧稳定)
 * ```
 *
 * 三条结构性约束（缺一不可，保持 fail-closed）：
 *  1. **帧几何闭环**：`srcW/srcH` 与逐帧 `frameW/frameH` 的方向比不一致时直接判
 *     「方向错配、不触发」，绝不拿同一套百分比到另一几何里搜索；
 *  2. **证据完整才启用分层**：`colorArgb / colorTolerance / convergedRoi` 任一缺失
 *     （老脚本）→ 自动回退到纯 NCC 单一判据，**逐字零回归**；
 *  3. **落点护栏不删**：分层放行时用「全局最佳窗口中心」作为匹配中心，
 *     照旧经过 `SceneTrigger.resolveSceneClick` 的偏差二次校验，绝不裸点。
 *
 * 「疑似在场」的分数下界沿用 `diagnoseImage` 的既有口径 0.45；上界为本次运行
 * 实际生效的阈值，避免把「已达阈值却因其它原因未命中」误判成疑似。
 *
 * 纯函数：不读时钟、不持位图、不依赖 Android。所有状态（连续帧数、上次有效
 * 落点）由调用方持有并传入，便于 JVM 单测穷举。
 */
object SceneEvidence {

    /** 「元素疑似在场」的 NCC 下界（与 diagnoseImage / 未命中归因同一口径）。 */
    const val SUSPECT_FLOOR = 0.45

    /** 分层判据默认连续帧数 N；落盘字段 `SceneRule.evidenceStreak` 可覆盖。 */
    const val DEFAULT_STREAK = 3

    /** 帧几何方向比的相对容差。超过即判方向错配。 */
    const val GEOMETRY_REL_TOL = 0.02

    /** 阈值校准的安全下限：跨帧下界再低也不把阈值压到该值以下（防误匹配放行）。 */
    const val MIN_SAFE_SIMILARITY = 55

    /**
     * 一条规则的运行期证据。由 `ScriptRunner` 从 `SceneRule` 组装；
     * 老脚本可用 `Action.color / colorSimilarity / region` 回填（装配期本就写过）。
     */
    data class Evidence(
        val colorArgb: Int,
        val colorTolerance: Int,
        val convergedRoi: Region?,
        val srcW: Int,
        val srcH: Int,
        val streak: Int,
    ) {
        /** 证据是否完整到足以启用分层判据。缺任一要素即回退纯 NCC（零回归）。 */
        val usable: Boolean
            get() = colorArgb != 0 && colorTolerance > 0 &&
                convergedRoi != null && !SceneAssist.isEmptyRoi(convergedRoi) && streak > 0
    }

    /** 本帧的原始观测（由 `TemplateMatch` + 逐帧取色 + 连续帧计数组成）。 */
    data class Frame(
        val hit: Boolean,
        val score: Double,
        val cxPx: Int,
        val cyPx: Int,
        val bestCxPx: Int,
        val bestCyPx: Int,
        val frameW: Int,
        val frameH: Int,
        val observedArgb: Int,
        val suspectStreak: Int,
        val geometryOk: Boolean,
    )

    /** 判定层级，用于日志区分「为什么算出现」。 */
    /** NODE = v2.9.44 节点 fallback 命中（截图通道失败/未命中时由无障碍节点树判定）。 */
    enum class Tier { NCC, LAYERED, NODE, NONE }

    /** 判定结果。`cxPx/cyPx` 是交给落点解析的匹配中心。 */
    data class Verdict(
        val present: Boolean,
        val cxPx: Int,
        val cyPx: Int,
        val tier: Tier,
        val reason: String,
        val suspect: Boolean,
        val colorOk: Boolean,
        val roiOk: Boolean,
        val stableOk: Boolean,
    )

    /**
     * 帧几何一致性（条款 4）。
     *
     * 源尺寸为 0（老脚本未记录）时不校验并返回 true —— 零回归优先于「多一层保护」。
     * 横竖屏切换时宽高比互为倒数，相对误差远超 [GEOMETRY_REL_TOL]，直接判错配。
     */
    fun geometryMatches(srcW: Int, srcH: Int, frameW: Int, frameH: Int): Boolean {
        if (srcW <= 0 || srcH <= 0 || frameW <= 0 || frameH <= 0) return true
        val srcRatio = srcW.toDouble() / srcH.toDouble()
        val frameRatio = frameW.toDouble() / frameH.toDouble()
        if (srcRatio <= 0.0) return true
        return abs(srcRatio - frameRatio) / srcRatio <= GEOMETRY_REL_TOL
    }

    /** 屏幕像素中心是否落在收敛有效区域内（百分比坐标系）。 */
    fun inRoi(roi: Region?, cxPx: Int, cyPx: Int, frameW: Int, frameH: Int): Boolean {
        if (roi == null || SceneAssist.isEmptyRoi(roi)) return false
        if (cxPx < 0 || cyPx < 0 || frameW <= 0 || frameH <= 0) return false
        val xPct = 100.0 * cxPx / frameW
        val yPct = 100.0 * cyPx / frameH
        return SceneAssist.contains(roi, xPct, yPct)
    }

    /**
     * 最佳窗口落在多少块掩码内（条款 7 的运行期部分）。
     * 返回 0 时 `ScreenSource.findTemplates` 跳过抑制，避免把有效特征抹掉。
     */
    fun maskCovers(
        rects: List<MaskRect>,
        cxPx: Int,
        cyPx: Int,
        frameW: Int,
        frameH: Int,
    ): Int {
        if (rects.isEmpty() || cxPx < 0 || cyPx < 0 || frameW <= 0 || frameH <= 0) return 0
        val xPct = 100.0 * cxPx / frameW
        val yPct = 100.0 * cyPx / frameH
        return rects.count { r ->
            xPct >= r.leftPct && xPct <= r.rightPct && yPct >= r.topPct && yPct <= r.bottomPct
        }
    }

    /** 本帧是否处于「疑似在场」分数带（不含已达阈值的命中）。 */
    fun isSuspect(hit: Boolean, score: Double, threshold: Double): Boolean =
        !hit && score >= SUSPECT_FLOOR && score < threshold && score.isFinite()

    /**
     * 从落盘规则反推拾取点（锚点中心 + 相对偏移），供运行期逐帧取色。
     * 相对坐标未启用时返回 (-1,-1) 表示「拾取点未知，不采样」。
     */
    fun recoverPickPoint(rule: SceneRule): Pair<Double, Double> {
        if (!rule.usesRelativeClick) return -1.0 to -1.0
        val ox = rule.clickOffsetXPct ?: return -1.0 to -1.0
        val oy = rule.clickOffsetYPct ?: return -1.0 to -1.0
        val acx = (rule.anchorRectPct.leftPct + rule.anchorRectPct.rightPct) / 2.0
        val acy = (rule.anchorRectPct.topPct + rule.anchorRectPct.bottomPct) / 2.0
        return (acx + ox) to (acy + oy)
    }

    /**
     * 分层判定（条款 2）。返回 `present` 直接作为状态机的本轮观测。
     *
     * 优先级：几何错配 → NCC 命中 → 分层证据 → 未命中。任一分支都不产生
     * 「无匹配中心却放行」，分层放行的中心取全局最佳窗口。
     */
    fun assess(ev: Evidence, frame: Frame, threshold: Double): Verdict {
        if (!frame.geometryOk) {
            return Verdict(
                false, -1, -1, Tier.NONE,
                "方向错配（帧几何与拾取源尺寸不一致），本帧不触发",
                false, false, false, false,
            )
        }
        if (frame.hit) {
            return Verdict(
                true, frame.cxPx, frame.cyPx, Tier.NCC, "NCC 命中",
                false, false, false, false,
            )
        }
        val suspect = isSuspect(frame.hit, frame.score, threshold) && frame.bestCxPx >= 0
        if (!suspect || !ev.usable) {
            return Verdict(
                false, -1, -1, Tier.NONE,
                if (suspect) "证据不完整，按原 NCC 单一判据处理" else "未命中",
                suspect, false, false, false,
            )
        }
        val colorOk = frame.observedArgb != 0 &&
            SceneAssist.colorDistance(frame.observedArgb, ev.colorArgb) <= ev.colorTolerance
        val roiOk = inRoi(ev.convergedRoi, frame.bestCxPx, frame.bestCyPx, frame.frameW, frame.frameH)
        val stableOk = frame.suspectStreak >= ev.streak
        val ok = colorOk && roiOk && stableOk
        val parts = buildString {
            append("分层证据：取色")
            append(if (colorOk) "✓" else "✗")
            append(" 收敛区域")
            append(if (roiOk) "✓" else "✗")
            append(" 连续${ev.streak}帧")
            append(if (stableOk) "✓" else "✗(${frame.suspectStreak})")
        }
        return Verdict(
            present = ok,
            cxPx = if (ok) frame.bestCxPx else -1,
            cyPx = if (ok) frame.bestCyPx else -1,
            tier = if (ok) Tier.LAYERED else Tier.NONE,
            reason = if (ok) "$parts → 判为场景出现" else parts,
            suspect = true,
            colorOk = colorOk,
            roiOk = roiOk,
            stableOk = stableOk,
        )
    }
}

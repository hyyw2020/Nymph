// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import kotlin.math.abs
import kotlin.math.max

/**
 * **HUD 定位级联的判定真源（core 纯 JVM，零 Android / 零 native 依赖）**。
 *
 * 对应离线方案的三支路架构：一条确定性主干负责「在哪」，轻量模型只负责「是什么」，
 * 频域只在条件触发时介入；全链路可降级到纯 CV（V1）。
 *
 * ```
 * S0 门禁      gateScene(scene, inMatch)      —— 非对局场景直接不跑
 * S2b 条件频域 shouldUseFrequency(...)         —— IMU/帧差超阈值 + PNR/残差双校验才介入
 * S5 融合      fuse(...)                       —— 0.4·边沿 + 0.4·模型 + 0.2·拓扑
 * S5 时序      threeFrameConsistent / lowPass  —— 3 帧一致 + 一阶低通
 * S6 执行门    verifyBeforeClick(...)          —— 点击前再校验一帧
 * ```
 *
 * 所有函数都是纯函数：给定输入必得同一输出，无隐藏状态、无运行时推断。
 * 这样 UI 诊断面板显示的分支、各阶段分数与运行时判据**同源**，杜绝"界面显示 PASS、运行时另一套判据"。
 */
class HudCascade private constructor() {

    /** S0 场景门禁的输入：当前界面分类 + 是否允许触发。 */
    enum class Scene {
        /** 对局内——唯一允许跑定位级联的场景。 */
        IN_MATCH,
        /** 设置页 / 大厅 / 未知——直接短路，不进后续阶段。 */
        OTHER,
    }

    /** 定位结果来自哪条支路（诊断面板直接渲染）。 */
    enum class Branch {
        /** V1 确定性主干：闭合梯度环 + 双竖线箭头签名 + NCC。 */
        GEOM,
        /** 拓扑预测位：由标定页锚点/相对位置推出的先验位置，作为融合的弱证据。 */
        TOPO,
        /** V2 轻量模型裁决：96×96 crop 的 INT8 分类（只认类别，不找位置）。 */
        MODEL,
        /** V3 条件频域：ROI 内高通 + 相位相关（带 PNR / 残差校验）。 */
        FREQ,
        /** 纯 CV 降级链：模型/频域均不可用时的兜底。 */
        CV_ONLY,
    }

    /** 相位相关的质量指标。 */
    data class PhaseQuality(
        val pnr: Double,          /** 主峰/次峰比（Peak-to-Next-Ratio）。 */
        val residual: Double,     /** 残差（对齐后剩余误差，0..1）。 */
    ) {
        /** 相位结果可用：PNR 达标且残差达标，二者缺一不可。 */
        val reliable: Boolean get() = pnr >= PNR_MIN && residual <= RESIDUAL_MAX
    }

    /** 一帧的候选观测（某支路对某 HUD 元素的定位/置信）。 */
    data class Candidate(
        val branch: Branch,
        val score: Double,        /** 0..1，<0 表示无效（截图失败/几何错配/模型未加载）。 */
        val centerXPct: Double,   /** 元素中心，相对筛选帧的百分比。 */
        val centerYPct: Double,
        val cls: String = "",     /** 模型支路给出的类别（上下箭头/准星/手雷/跳跃/换弹…）。 */
    ) {
        val valid: Boolean get() = score >= 0.0 && score.isFinite() &&
            centerXPct.isFinite() && centerYPct.isFinite()
    }

    /** S5 融合后的单帧结论。 */
    data class Fused(
        val score: Double,
        val centerXPct: Double,
        val centerYPct: Double,
        val branch: Branch,
        val usedBranches: List<Branch>,
    )

    companion object {
        /** S5 融合权重：edge / model / topo，和为 1。 */
        const val W_EDGE = 0.4
        const val W_MODEL = 0.4
        const val W_TOPO = 0.2

        /** 相位相关门限：PNR 低于此值判"重复纹理/无纹理"，丢弃（图 5 六边形地砖的克星场景）。 */
        const val PNR_MIN = 2.0
        /** 残差高于此值判"遮挡破坏相位一致性"，丢弃。 */
        const val RESIDUAL_MAX = 0.35

        /** 触发门限（融合分低于此值不触发）。 */
        const val FIRE_THRESHOLD = 0.62
        /** IMU 角速度超阈值 → 背景全在变，启用条件频域并提高匹配门限。 */
        const val ANGULAR_SPEED_THRESHOLD = 0.35
        /** 帧差超阈值 → 画面发生显著变化，触发条件频域复查。 */
        const val FRAME_DIFF_THRESHOLD = 0.18
        /** 提高后的匹配门限（转场时收缩到拓扑预测位，只认高置信候选）。 */
        const val TURN_MATCH_FLOOR = 0.85

        /** 一阶低通系数（越大越跟手、越小越稳）。 */
        const val LOW_PASS_ALPHA = 0.35

        /** S0 场景门禁：非对局直接 false，级联不启动。 */
        fun gateScene(scene: Scene, inMatch: Boolean): Boolean = scene == Scene.IN_MATCH && inMatch

        /**
         * S2b 条件频域门控：仅当 IMU 角速度超阈值或帧差超阈值时才触发频域复查；
         * 且必须带 PNR / 残差双校验——重复纹理、无纹理区、遮挡都会让相位相关产出荒谬结果，
         * 不达标就丢弃，绝不让它污染确定性主干。
         *
         * IMU 只做门控，不做运动补偿：HUD 钉在屏幕坐标系，陀螺仪测的是相机运动，
         * 背景在动而 UI 静止；拿 IMU 预测按钮位置会把静止控件当运动物体抹掉。
         */
        fun shouldUseFrequency(
            angularSpeed: Double,
            frameDiff: Double,
            quality: PhaseQuality,
        ): Boolean {
            val triggered = angularSpeed > ANGULAR_SPEED_THRESHOLD || frameDiff > FRAME_DIFF_THRESHOLD
            return triggered && quality.reliable
        }

        /**
         * 转场门限：角速度超阈值时提高匹配门限、收缩到拓扑预测位。
         * 返回本帧候选必须通过的最低分。
         */
        fun matchFloor(angularSpeed: Double): Double =
            if (angularSpeed > ANGULAR_SPEED_THRESHOLD) TURN_MATCH_FLOOR else 0.0

        /**
         * S5 融合：S = 0.4·S_edge + 0.4·S_model + 0.2·S_topo。
         *
         * 缺失支路按 fail-closed 处理：无效候选（score<0）不参与加权，
         * 并把它的权重重新归一化到剩余有效支路——这样模型挂了主干照样工作（降级到纯 CV）。
         * 坐标取有效候选中分数最高者的中心（AI 只认类别、不找位置，位置由确定性主干定）。
         */
        fun fuse(
            edge: Candidate?,
            model: Candidate?,
            topo: Candidate?,
        ): Fused? {
            val valid = listOfNotNull(edge, model, topo).filter { it.valid }
            if (valid.isEmpty()) return null

            // 边沿：确定性主干的定位权重最高；模型：类别裁决；拓扑：预测位（用相似分代替距离）
            var wSum = 0.0
            var sSum = 0.0
            edge?.takeIf { it.valid }?.let { wSum += W_EDGE; sSum += W_EDGE * it.score }
            model?.takeIf { it.valid }?.let { wSum += W_MODEL; sSum += W_MODEL * it.score }
            topo?.takeIf { it.valid }?.let { wSum += W_TOPO; sSum += W_TOPO * it.score }
            val fused = if (wSum > 0.0) sSum / wSum else 0.0

            val best = valid.maxBy { it.score }
            val used = valid.map { it.branch }.distinct()
            val branch = when {
                model != null && model.valid && model.score >= best.score -> Branch.MODEL
                else -> best.branch
            }
            return Fused(fused, best.centerXPct, best.centerYPct, branch, used)
        }

        /** 三帧一致：连续三帧的融合结论均越过门限，且中心漂移在容差内。 */
        fun threeFrameConsistent(
            f1: Fused?, f2: Fused?, f3: Fused?,
            threshold: Double = FIRE_THRESHOLD,
            driftTolPct: Double = 3.0,
        ): Boolean {
            if (f1 == null || f2 == null || f3 == null) return false
            if (minOf(f1.score, f2.score, f3.score) < threshold) return false
            val dx = maxOf(abs(f1.centerXPct - f2.centerXPct), abs(f2.centerXPct - f3.centerXPct))
            val dy = maxOf(abs(f1.centerYPct - f2.centerYPct), abs(f2.centerYPct - f3.centerYPct))
            return dx <= driftTolPct && dy <= driftTolPct
        }

        /** 一阶低通：new = α·cur + (1-α)·prev；无历史时直接返回当前值。 */
        fun lowPass(prev: Double?, cur: Double, alpha: Double = LOW_PASS_ALPHA): Double =
            if (prev == null) cur else alpha * cur + (1.0 - alpha) * prev

        /** S6 执行门：点击前再校验一帧，分数仍达标才允许注入手势。 */
        fun verifyBeforeClick(f: Fused?, threshold: Double = FIRE_THRESHOLD): Boolean =
            f != null && f.score >= threshold
    }
}

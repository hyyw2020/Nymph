// SPDX-License-Identifier: MIT
package dev.nymph.auto.engine

import dev.nymph.auto.core.model.ImageFilter
import dev.nymph.auto.core.model.MaskRect
import dev.nymph.auto.core.model.Region

/**
 * **一轮一帧场景匹配的数据契约**（v2.9.15 / A6，app 层，零 Android 截图依赖）。
 *
 * 常驻监视下「一轮」必须是「一帧共享给 N 条规则」，而不是「N 次各自
 * screenshotBitmap()」—— 后者对功耗和「不打断其他 APP」是实打实的风险。
 * [VisionPipeline.findTemplates] 一次 `frame()` + 一份基础像素副本，N 个模板
 * 各自 copy 一份数组做掩码/滤镜/NCC，截图只抓一次。
 */

/** 单条规则本轮的模板匹配请求。 */
data class TemplateSpec(
    /** 稳定局部锚点小图（base64 PNG），不是整块 ROI。 */
    val templateB64: String,
    /** ROI 退回的「搜索范围」。 */
    val region: Region,
    /** 相似度阈值（百分制，拾取时自校准后落盘）。 */
    val minSimilar: Int,
    val filters: List<ImageFilter> = emptyList(),
    val refDpi: Double = 0.0,
    val curDensity: Double = 1.0,
    /** A1 动态区域掩码：剔除高方差 / 易变像素，作用于匹配副本。 */
    val maskRects: List<MaskRect> = emptyList(),

    // ------------------------------------------------ v2.9.31 装配-运行同源证据
    /** 拾取时屏幕像素尺寸；逐帧校验方向比，不一致即判方向错配不触发。 */
    val templateSrcW: Int = 0,
    val templateSrcH: Int = 0,
    /** 拾取点（屏幕百分比）。-1 = 未知，不逐帧取色。 */
    val pickXPct: Double = -1.0,
    val pickYPct: Double = -1.0,
    /** 拾取时邻域代表色与容差；0 = 无取色证据（回退纯 NCC 判据）。 */
    val colorArgb: Int = 0,
    val colorTolerance: Int = 0,
    /** 收敛有效区域；null = 无（回退纯 NCC 判据）。 */
    val convergedRoi: Region? = null,
    /** 分层判据连续帧数 N。 */
    val evidenceStreak: Int = 3,
    /** v2.9.32：拾取时方向（Surface.ROTATION_*）。0 且尺寸为横屏时按尺寸推断。 */
    val pickRotation: Int = 0,
)

/**
 * 一次模板匹配的结果 `{present, score, loc}`（A1：`findTemplate()` 的返回）。
 *
 * - [present] = 命中（分数过阈值）；未命中时仍带 [score]（本次 NCC 全局最佳分），
 *   供 A5 每轮日志区分「没匹配到（差多少分）」与「匹配到但没触发」。
 * - [cxPx]/[cyPx] 为命中中心（原图像素），未命中为 -1；[frameW]/[frameH] 供换算屏幕百分比。
 */
data class TemplateMatch(
    val present: Boolean,
    val score: Double,
    val cxPx: Int,
    val cyPx: Int,
    val frameW: Int,
    val frameH: Int,
    /** v2.9.26：未命中时全局最佳窗口的原图像素中心（-1 = 无可评窗口），供「元素疑似在场」归因。 */
    val bestCxPx: Int = -1,
    val bestCyPx: Int = -1,
    /** v2.9.31：本帧拾取点邻域代表色（0 = 未采集），供分层判据取色比对。 */
    val observedArgb: Int = 0,
    /** v2.9.31：本帧几何与拾取源尺寸是否一致。false 时上层直接判「方向错配、不触发」。 */
    val geometryOk: Boolean = true,
    /** v2.9.31：最佳窗口落在多少块掩码内。为 0 时运行期跳过抑制。 */
    val maskCovered: Int = 0,
    /**
     * v2.9.32：当前帧相对拾取参考系的顺时针四分之一转数（0..3）。
     * 本帧在匹配前已被旋回拾取几何，[cxPx]/[cyPx] 是拾取几何下的像素；
     * 上层注入落点时用 OrientationTransform.pointToCurrent 按本值旋回当前屏幕。
     */
    val orientationTurns: Int = 0,
)

/**
 * A5 调试覆盖层的一帧：每轮每规则一个框（debug 开关打开时绘制，默认关闭零开销）。
 * [hit] 决定颜色（命中/未命中），[score] / [clickXPct] / [clickYPct] 供角标文字。
 */
data class SceneDebugBox(
    val index: Int,
    val label: String,
    val roi: Region,
    val hit: Boolean,
    val score: Double,
    val clickXPct: Double,
    val clickYPct: Double,
    val decision: String,
)

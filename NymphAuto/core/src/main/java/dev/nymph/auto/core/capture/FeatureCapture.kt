// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.capture

import dev.nymph.auto.core.model.Region

/**
 * 图片特征截取的**纯 JVM 几何核心**（无 Android 依赖，可单测）。
 *
 * 竞品交互（视频 73–83s）：用户框选屏幕上一块原画质区域 → 截成"特征1"存本地 →
 * 保存脚本后仍可查看/复用。本类承担其中与平台无关的几何部分：
 *  - [cropRect]：把带百分比坐标的框选结果映射到实际截图帧的像素矩形，边界钳制；
 *  - [clampSimilarity]：相似度阈值收敛到有效区间；
 *  - [isUsable]：判定截出的特征是否可用于 NCC 匹配（尺寸/非空）。
 *
 * 真正的抓帧（AccessibilityService.takeScreenshot）与 Bitmap 编码在 app 层完成，
 * 且抓帧仍走 legacy/screenshot 的唯一出口 —— 本类不碰任何取屏 API。
 */
object FeatureCapture {

    /** 相似度阈值合法区间（百分制，与 Condition.imageSimilarity 同口径）。 */
    const val MIN_SIMILARITY = 50
    const val MAX_SIMILARITY = 100
    const val DEFAULT_SIMILARITY = 85

    /** 特征模板最小边长（像素）。过小则纹理不足，NCC 会大量误命中。 */
    const val MIN_TEMPLATE_SIDE = 8

    /** 截出的原画质矩形（截图帧像素坐标，左上闭、右下开）。 */
    data class CropRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val valid: Boolean get() = width > 0 && height > 0
    }

    /**
     * 把框选结果（像素矩形 + Picker 记录的原屏宽高）映射到截图帧像素坐标。
     *
     * 框选发生在悬浮拾取层，坐标系是屏幕宽 [pickerW]×[pickerH]；截图帧可能是另一
     * 分辨率（切换密度/横竖屏），故按百分比等比映射，绝不直接套用 Picker 的绝对像素。
     * 边界钳到 [0, frameW/frameH]，并保证右下严格大于左上（否则返回 null 让 UI 提示）。
     */
    fun cropRect(
        leftPx: Int,
        topPx: Int,
        rightPx: Int,
        bottomPx: Int,
        pickerW: Int,
        pickerH: Int,
        frameW: Int,
        frameH: Int,
    ): CropRect? {
        if (pickerW <= 0 || pickerH <= 0 || frameW <= 0 || frameH <= 0) return null
        val lp = (leftPx.toDouble() / pickerW * frameW).toInt()
        val tp = (topPx.toDouble() / pickerH * frameH).toInt()
        val rp = (rightPx.toDouble() / pickerW * frameW).toInt()
        val bp = (bottomPx.toDouble() / pickerH * frameH).toInt()
        val l = lp.coerceIn(0, frameW - 1)
        val t = tp.coerceIn(0, frameH - 1)
        val r = rp.coerceIn(l + 1, frameW)
        val b = bp.coerceIn(t + 1, frameH)
        val rect = CropRect(l, t, r, b)
        return rect.takeIf { it.valid }
    }

    /** 相似度收敛到 [MIN_SIMILARITY, MAX_SIMILARITY]。 */
    fun clampSimilarity(value: Int): Int = value.coerceIn(MIN_SIMILARITY, MAX_SIMILARITY)

    /** 截出的特征模板是否足以做匹配：非空 + 边长达下限。 */
    fun isUsable(rect: CropRect?): Boolean =
        rect != null && rect.valid && rect.width >= MIN_TEMPLATE_SIDE && rect.height >= MIN_TEMPLATE_SIDE

    /**
     * 框选矩形 → 落盘 [Region]（百分比四元组）。与运行时 RoiExpr 同口径，
     * 保证"截取时框的范围"就是"回放时搜索的范围"。
     */
    fun toRegion(rect: CropRect, frameW: Int, frameH: Int): Region? {
        if (frameW <= 0 || frameH <= 0 || !rect.valid) return null
        return Region(
            leftPct = rect.left * 100.0 / frameW,
            topPct = rect.top * 100.0 / frameH,
            rightPct = rect.right * 100.0 / frameW,
            bottomPct = rect.bottom * 100.0 / frameH,
        )
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import java.util.Locale
import kotlin.math.abs

/**
 * 拾取坐标自动派生的场景辅助素材与有效点击区域契约（v2.9.21，纯 JVM）。
 *
 * 主辅关系：坐标拾取为主流程；颜色样本与有效点击区域（ROI）由一次拾取
 * 自动派生，归入当前场景的辅助属性，不作为独立的用户配置概念存在。
 *
 * 采样与收敛均为无状态纯计算，不持有位图、不依赖 Android 运行时，可在 JVM
 * 单测中完整覆盖；异步调度与代际隔离由 app 层接入 [TapGate.Generation] 承担。
 * 颜色与区域最终写入既有落盘字段（Action.color / colorSimilarity / region），
 * 不引入新字段、不改变落盘结构版本。
 */
object SceneAssist {

    /** 邻域采样半径（物理像素）；采样以拾取点为圆心的离散栅格。 */
    const val DEFAULT_RADIUS_PX = 12

    /** 颜色容差的许可区间（RGB 亮度加权距离，0..255 口径）。 */
    const val MIN_TOLERANCE = 8
    const val MAX_TOLERANCE = 96

    /** 有效点击区域半径随邻域离散度收敛的下界 / 上界（相对屏幕短边比例）。 */
    const val MIN_RADIUS_FRAC = 0.02
    const val MAX_RADIUS_FRAC = 0.16

    /** 有效区域最小跨度（百分比）；低于该值视为空区域。 */
    const val MIN_SPAN_PCT = 0.1

    /**
     * 一次拾取的颜色辅助素材。
     * [argb] 为邻域代表色，[tolerance] 由离散度推导，[featureHash] 为必要像素
     * 特征摘要，[spread] 为各通道极差均值，[sampleCount] 为有效采样点数。
     */
    data class ColorSample(
        val argb: Int,
        val tolerance: Int,
        val spread: Int,
        val featureHash: Int,
        val sampleCount: Int,
    ) {
        val valid: Boolean get() = sampleCount > 0
    }

    /**
     * 拾取点邻域颜色采样。[pixelAt] 返回 ARGB 整数，越界或不可读取时返回 null。
     * 代表色取通道均值，离散度取通道极差均值，容差随离散度线性放宽并收敛到许可区间。
     */
    fun sampleNeighborhood(
        pixelAt: (Int, Int) -> Int?,
        cxPx: Int,
        cyPx: Int,
        frameW: Int,
        frameH: Int,
        radiusPx: Int = DEFAULT_RADIUS_PX,
    ): ColorSample {
        if (frameW <= 0 || frameH <= 0) return emptySample()
        val r = radiusPx.coerceAtLeast(1)
        var count = 0
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        var hash = 0x811c9dc5.toInt()
        val step = if (r > 8) 2 else 1
        var dy = -r
        while (dy <= r) {
            var dx = -r
            while (dx <= r) {
                if (dx * dx + dy * dy <= r * r) {
                    val px = pixelAt(cxPx + dx, cyPx + dy)
                    if (px != null) {
                        val cr = (px shr 16) and 0xFF
                        val cg = (px shr 8) and 0xFF
                        val cb = px and 0xFF
                        sumR += cr; sumG += cg; sumB += cb
                        if (cr < minR) minR = cr; if (cr > maxR) maxR = cr
                        if (cg < minG) minG = cg; if (cg > maxG) maxG = cg
                        if (cb < minB) minB = cb; if (cb > maxB) maxB = cb
                        hash = (hash xor px) * 0x01000193
                        count++
                    }
                }
                dx += step
            }
            dy += step
        }
        // 兜底：采样圆整窗越出帧时（极小帧或拾取点恰在角上），至少取中心像素，
        // 使「有帧但样本为空」不再发生；中心像素同样越界才判为真正的采样失败。
        if (count == 0) {
            val center = pixelAt(cxPx.coerceIn(0, frameW - 1), cyPx.coerceIn(0, frameH - 1))
            return if (center != null) {
                ColorSample(center, toleranceFor(0), 0, center, 1)
            } else {
                emptySample()
            }
        }
        val mr = (sumR / count).toInt()
        val mg = (sumG / count).toInt()
        val mb = (sumB / count).toInt()
        val spread = ((maxR - minR) + (maxG - minG) + (maxB - minB)) / 3
        val argb = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
        return ColorSample(argb, toleranceFor(spread), spread, hash, count)
    }

    private fun emptySample(): ColorSample = ColorSample(0, MIN_TOLERANCE, 0, 0, 0)

    /** 离散度到颜色容差的单调映射：纹理越复杂，允许的偏差越大，但不超过上界。 */
    fun toleranceFor(spread: Int): Int =
        (MIN_TOLERANCE + spread * (MAX_TOLERANCE - MIN_TOLERANCE) / 120)
            .coerceIn(MIN_TOLERANCE, MAX_TOLERANCE)

    /** 亮度加权 RGB 距离（0..255 口径），用于匹配判定。 */
    fun colorDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return (dr * 30 + dg * 59 + db * 11) / 100
    }

    /**
     * 依据颜色特征自动收敛有效点击区域：邻域离散度越小（纯色稳定）区域越紧，
     * 离散度越大（纹理复杂）区域适度放宽，始终以拾取点为中心并夹在屏内。
     */
    fun convergeRoi(
        centerXPct: Double,
        centerYPct: Double,
        sample: ColorSample,
        screenW: Int,
        screenH: Int,
    ): Region {
        val spread = if (sample.valid) sample.spread else 255
        val frac = (MIN_RADIUS_FRAC + spread * (MAX_RADIUS_FRAC - MIN_RADIUS_FRAC) / 255.0)
            .coerceIn(MIN_RADIUS_FRAC, MAX_RADIUS_FRAC)
        return SceneTrigger.autoRoi(centerXPct, centerYPct, screenW, screenH, frac)
    }

    /** 手动框选优先于自动区域；清除手动结果（null 或空区域）后回退自动区域。 */
    enum class RoiMode { AUTO, MANUAL }

    data class RoiResolution(
        val region: Region,
        val mode: RoiMode,
        val overridden: Boolean,
    )

    fun resolveRoi(auto: Region, manual: Region?): RoiResolution =
        if (manual != null && !isEmptyRoi(manual)) RoiResolution(manual, RoiMode.MANUAL, true)
        else RoiResolution(auto, RoiMode.AUTO, false)

    fun isEmptyRoi(r: Region?): Boolean {
        if (r == null) return true
        return r.rightPct - r.leftPct < MIN_SPAN_PCT || r.bottomPct - r.topPct < MIN_SPAN_PCT
    }

    fun contains(r: Region, xPct: Double, yPct: Double): Boolean =
        xPct >= r.leftPct && xPct <= r.rightPct && yPct >= r.topPct && yPct <= r.bottomPct

    /** 第二视觉层级状态条的区域摘要（Locale.US，单行）。 */
    fun roiSummary(r: Region): String = String.format(
        Locale.US, "%.1f,%.1f ~ %.1f,%.1f %%",
        r.leftPct, r.topPct, r.rightPct, r.bottomPct,
    )

    /** 颜色样本的轻量摘要（十六进制代表色 + 容差），供折叠状态条呈现。 */
    fun colorSummary(s: ColorSample): String = String.format(
        Locale.US, "#%06X · 容差 %d", s.argb and 0xFFFFFF, s.tolerance,
    )
}

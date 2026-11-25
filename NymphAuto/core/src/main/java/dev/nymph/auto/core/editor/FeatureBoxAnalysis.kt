// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.vision.PickerGeometry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 多特征框的纯分析函数（v2.9.49，纯 JVM 可单测，不碰 UI / 不解码 / 不联网）。
 *
 * 两个用途：
 *  - [overlapRatio]：拾取期判定两框是否冗余重叠（>0.5 提示用户），对应需求
 *    「重叠度 > 50% 时提示冗余」；
 *  - [textureStrength]：该框作为模板的**可辨识度参考**（区域灰度标准差归一化
 *    到 0–100），对应需求「滑杆旁标注历史匹配强度参考值，用数据引导而非拍数」。
 *    这是引导值，不是模型断言；用户仍可自由设置相似度。
 */
object FeatureBoxAnalysis {

    /** 冗余重叠阈值：交集 / 较小框面积 > 此值提示。 */
    const val OVERLAP_WARN = 0.5

    /**
     * 两框重叠度 = 交集面积 / 较小框面积，结果 0..1。
     * 用「较小框」为分母，能识别「小框完全落在大框内」这种典型冗余（IoU 会偏小）。
     */
    fun overlapRatio(a: PickerGeometry.Rect, b: PickerGeometry.Rect): Double {
        val iw = max(0, min(a.r, b.r) - max(a.l, b.l))
        val ih = max(0, min(a.b, b.b) - max(a.t, b.t))
        val inter = iw.toLong() * ih
        if (inter == 0L) return 0.0
        val areaA = (a.r - a.l).toLong() * max(1, a.b - a.t)
        val areaB = (b.r - b.l).toLong() * max(1, b.b - b.t)
        val smaller = minOf(areaA, areaB)
        return if (smaller <= 0L) 0.0 else (inter.toDouble() / smaller).coerceIn(0.0, 1.0)
    }

    /**
     * 区域灰度纹理强度：标准差 σ 归一化到 0..100（σ≈64 灰度级即满值）。
     * 纯色区返回 0（不适合做模板），高对比纹理区接近 100。
     *
     * @param argb ARGB_8888 像素数组，长度 ≥ w*h
     */
    fun textureStrength(argb: IntArray, w: Int, h: Int, rect: PickerGeometry.Rect): Int {
        if (w <= 0 || h <= 0 || argb.size < w.toLong() * h) return 0
        val l = rect.l.coerceIn(0, w - 1)
        val r = rect.r.coerceIn(l + 1, w)
        val t = rect.t.coerceIn(0, h - 1)
        val b = rect.b.coerceIn(t + 1, h)
        var n = 0L
        var sum = 0L
        var sumSq = 0L
        for (y in t until b) {
            val row = y * w
            for (x in l until r) {
                val c = argb[row + x]
                val gray = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                sum += gray
                sumSq += gray.toLong() * gray
                n++
            }
        }
        if (n <= 0) return 0
        val mean = sum.toDouble() / n
        val variance = max(0.0, sumSq.toDouble() / n - mean * mean)
        return ((sqrt(variance) / 64.0) * 100.0).toInt().coerceIn(0, 100)
    }
}

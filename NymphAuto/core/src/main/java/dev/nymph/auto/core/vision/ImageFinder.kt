package dev.nymph.auto.core.vision

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * NCC 归一化互相关模板匹配（从已验证实现移植）。
 *
 * - 积分图求窗口均值/方差（O(1)）+ NCC 相关系数，抗亮度变化
 * - 多尺度：ratio(按 dpi 比) 附近 0.75~1.25 六档
 * - 自适应降采样：按计算量上限 cost<=1e8 逐级 ds*=2；
 *   小模板（<64px）降采样上限收紧到 ds<=2 并放宽 cost 到 4e8，避免
 *   "40px 按钮被降到 10px 导致有元素也匹配不上"
 * - 返回命中的中心坐标（供 findAndTap 使用；Boolean 版见 [exists]）
 * - [bestScore] 诊断接口：返回全局最佳 NCC 分数（不套阈值），
 *   用于"为什么匹配不上"的日志归因
 */
object ImageFinder {

    data class Hit(val cx: Int, val cy: Int, val score: Double)

    data class RegionPct(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 100.0, val bottom: Double = 100.0)

    /** 找到即返回第一个命中点；找不到返回 null。 */
    fun find(
        screen: Image,
        template: Image,
        leftPct: Double, topPct: Double, rightPct: Double, bottomPct: Double,
        minSimilar: Int, refDpi: Double, curDensity: Double,
    ): Hit? {
        for (s in scalesOf(refDpi, curDensity)) {
            val r = matchAtScale(screen, template, leftPct, topPct, rightPct, bottomPct, minSimilar, s, wantBest = false)
                ?: continue
            if (r.first != null) return r.first
        }
        return null
    }

    /**
     * 诊断：各尺度下的全局最佳 NCC 分数（0~1，不套阈值）。
     * 归因参考：<0.45 多半是界面没有该元素或方向/分辨率错配；
     * 0.45~阈值 说明元素疑似在，但尺度/清晰度/相似度设置不佳。
     */
    fun bestScore(
        screen: Image,
        template: Image,
        leftPct: Double, topPct: Double, rightPct: Double, bottomPct: Double,
        refDpi: Double, curDensity: Double,
    ): Double {
        var best = 0.0
        for (s in scalesOf(refDpi, curDensity)) {
            val r = matchAtScale(screen, template, leftPct, topPct, rightPct, bottomPct, 100, s, wantBest = true)
                ?: continue
            if (r.second > best) best = r.second
        }
        return best
    }

    /** 只判断存在性（条件判断用，可提前退出）。 */
    fun exists(screen: Image, template: Image, region: RegionPct, minSimilar: Int): Boolean =
        find(screen, template, region.left, region.top, region.right, region.bottom, minSimilar, 0.0, 1.0) != null

    private fun scalesOf(refDpi: Double, curDensity: Double): List<Double> {
        val ratio = if (refDpi > 0.01) curDensity / refDpi else 1.0
        // ratio 附近细步进 + 固定档，覆盖制作端与运行端 DPI/分辨率差异
        return linkedSetOf(ratio, ratio * 0.9, 1.0, ratio * 0.75, ratio * 1.1, ratio * 1.25).toList()
    }

    /** 返回 (命中点或 null, 该尺度最佳分数)；尺度不适用返回 null。 */
    private fun matchAtScale(
        screen: Image, template: Image,
        leftPct: Double, topPct: Double, rightPct: Double, bottomPct: Double,
        minSimilar: Int, scale: Double, wantBest: Boolean,
    ): Pair<Hit?, Double>? {
        val area = screen.width.toLong() * screen.height.toLong()
        // 小模板保真：降采样上限随模板尺寸收紧，防止关键特征被抹掉
        val minTplSide = minOf(template.width, template.height)
        val dsCap = when {
            minTplSide >= 256 -> 16
            minTplSide >= 128 -> 8
            minTplSide >= 64 -> 4
            else -> 2
        }
        val costCap = if (dsCap <= 2) 400_000_000L else 100_000_000L
        var ds = when {
            area > 2_000_000L -> 4
            area > 400_000L -> 2
            else -> 1
        }.coerceAtMost(dsCap)
        while (true) {
            val sw = screen.width / ds
            val sh = screen.height / ds
            val tw = (template.width * scale / ds).roundToInt()
            val th = (template.height * scale / ds).roundToInt()
            if (tw < 4 || th < 4 || tw > sw || th > sh) return null
            val positions = ((sw - tw).toLong() * (sh - th).toLong()).coerceAtLeast(1L)
            val cost = positions * tw.toLong() * th.toLong()
            if (cost <= costCap) {
                return matchCore(screen, template, sw, sh, tw, th, leftPct, topPct, rightPct, bottomPct, minSimilar, wantBest)
            }
            if (ds >= dsCap) return null
            ds = min(ds * 2, dsCap)
        }
    }

    private fun matchCore(
        screen: Image, template: Image,
        sw: Int, sh: Int, tw: Int, th: Int,
        leftPct: Double, topPct: Double, rightPct: Double, bottomPct: Double,
        minSimilar: Int, wantBest: Boolean,
    ): Pair<Hit?, Double> {
        val grayScreen = grayOf(scaleImage(screen, sw, sh))
        val grayTpl = grayOf(scaleImage(template, tw, th))

        val s = (tw * th).toDouble()
        var sumT = 0.0
        for (v in grayTpl) sumT += v
        val meanT = sumT / s
        val t0 = DoubleArray(grayTpl.size) { grayTpl[it] - meanT }
        var sumSqT = 0.0
        for (v in t0) sumSqT += v * v
        val denomT = sqrt(sumSqT)
        if (denomT < 1e-9) return null to 0.0

        val iw = sw + 1
        val integral = DoubleArray(iw * (sh + 1))
        val integralSq = DoubleArray(iw * (sh + 1))
        for (y in 0 until sh) {
            var rowSum = 0.0
            var rowSumSq = 0.0
            val rowOff = y * sw
            val intOff = (y + 1) * iw
            val prevOff = y * iw
            for (x in 0 until sw) {
                val g = grayScreen[rowOff + x]
                rowSum += g
                rowSumSq += g * g
                integral[intOff + x + 1] = integral[prevOff + x + 1] + rowSum
                integralSq[intOff + x + 1] = integralSq[prevOff + x + 1] + rowSumSq
            }
        }

        var rx0 = (leftPct / 100.0 * sw).toInt()
        var ry0 = (topPct / 100.0 * sh).toInt()
        var rx1 = ceil(rightPct / 100.0 * sw).toInt()
        var ry1 = ceil(bottomPct / 100.0 * sh).toInt()
        rx0 = rx0.coerceIn(0, sw - 1)
        ry0 = ry0.coerceIn(0, sh - 1)
        rx1 = rx1.coerceIn(rx0 + 1, sw)
        ry1 = ry1.coerceIn(ry0 + 1, sh)
        val xEnd = rx1 - tw
        val yEnd = ry1 - th
        if (xEnd < rx0 || yEnd < ry0) return null to 0.0

        val threshold = minSimilar.coerceIn(1, 100) / 100.0
        var bestScore = 0.0
        for (wy in ry0..yEnd) {
            for (wx in rx0..xEnd) {
                val a = wy * iw
                val b = (wy + th) * iw
                val sum = integral[b + wx + tw] - integral[b + wx] - integral[a + wx + tw] + integral[a + wx]
                val sumSq = integralSq[b + wx + tw] - integralSq[b + wx] - integralSq[a + wx + tw] + integralSq[a + wx]
                val varW = sumSq - sum * sum / s
                if (varW <= 1e-6) continue
                val denomW = sqrt(varW)
                var num = 0.0
                for (j in 0 until th) {
                    val sOff = (wy + j) * sw + wx
                    val tOff = j * tw
                    for (i in 0 until tw) {
                        num += grayScreen[sOff + i] * t0[tOff + i]
                    }
                }
                val score = num / (denomW * denomT)
                if (score > bestScore) bestScore = score
                if (!wantBest && num >= threshold * denomW * denomT) {
                    return Hit(
                        cx = ((wx + tw / 2) * screen.width / sw),
                        cy = ((wy + th / 2) * screen.height / sh),
                        score = score,
                    ) to score
                }
            }
        }
        return null to bestScore
    }

    /** 平台无关图像载体（Android 层用 Bitmap 适配，JVM 测试用纯数组）。 */
    data class Image(val width: Int, val height: Int, val argb: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Image && width == other.width && height == other.height && argb.contentEquals(other.argb)
        override fun hashCode(): Int = (width * 31 + height) * 31 + argb.contentHashCode()
    }

    private fun scaleImage(src: Image, w: Int, h: Int): Image {
        if (src.width == w && src.height == h) return src
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = y * src.height / h
            for (x in 0 until w) {
                val sx = x * src.width / w
                out[y * w + x] = src.argb[sy * src.width + sx]
            }
        }
        return Image(w, h, out)
    }

    private fun grayOf(bmp: Image): DoubleArray {
        val px = bmp.argb
        val out = DoubleArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = r * 0.299 + g * 0.587 + b * 0.114
        }
        return out
    }
}

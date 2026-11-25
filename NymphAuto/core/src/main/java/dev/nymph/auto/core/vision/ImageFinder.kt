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
            if (r.hit != null) return r.hit
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
            if (r.bestScore > best) best = r.bestScore
        }
        return best
    }

    /** 一次匹配的完整结果：命中点（可能为 null）+ 本次 NCC 遍历得到的全局最佳分数（0~1）。 */
    data class ScoredHit(val hit: Hit?, val bestScore: Double) {
        /** v2.9.26：未命中时全局最佳窗口的**原图像素**中心（无可评窗口为 -1），供「元素疑似在场」归因。 */
        var bestCx: Int = -1
            private set

        /** 同 [bestCx] 的纵坐标。 */
        var bestCy: Int = -1
            private set

        internal fun withBestLoc(cx: Int, cy: Int): ScoredHit {
            bestCx = cx
            bestCy = cy
            return this
        }
    }

    /**
     * v2.9.15 / A5：**一次遍历同时给出「命中位置」与「最佳分数」**（不重写识别引擎，
     * 只把 [find] / [bestScore] 内部早已算出的 `matchAtScale` 结果完整暴露）。
     *
     * 动机：场景驱动每轮日志要求 present / score / loc 三者可观测 —— 用户要能区分
     * 「没匹配到（差多少分）」与「匹配到但没触发」与「触发点错位」。若命中用 [find]
     * （提前退出）、未命中再补一次 [bestScore]（全遍历），就是两次 NCC，违背 A6
     * 「一轮一帧」的省功耗初衷。本方法复用 [find] 同款 `wantBest=false` 单次遍历：
     * 命中即返回命中点及其分数；未命中时返回全局最佳分（用于诊断「差多少没过阈值」）。
     *
     * [find] / [bestScore] 原语义逐字不变（本方法是纯新增），406 例零回归。
     */
    fun findDetailed(
        screen: Image,
        template: Image,
        leftPct: Double, topPct: Double, rightPct: Double, bottomPct: Double,
        minSimilar: Int, refDpi: Double, curDensity: Double,
    ): ScoredHit {
        var best = 0.0
        var bestCx = -1
        var bestCy = -1
        for (s in scalesOf(refDpi, curDensity)) {
            val r = matchAtScale(
                screen, template, leftPct, topPct, rightPct, bottomPct,
                minSimilar, s, wantBest = false,
            ) ?: continue
            if (r.hit != null) return ScoredHit(r.hit, r.bestScore)
            if (r.bestScore > best) {
                best = r.bestScore
                bestCx = r.bestCx
                bestCy = r.bestCy
            }
        }
        return ScoredHit(null, best).withBestLoc(bestCx, bestCy)
    }

    /** 只判断存在性（条件判断用，可提前退出）。 */
    fun exists(screen: Image, template: Image, region: RegionPct, minSimilar: Int): Boolean =
        find(screen, template, region.left, region.top, region.right, region.bottom, minSimilar, 0.0, 1.0) != null

    private fun scalesOf(refDpi: Double, curDensity: Double): List<Double> {
        val ratio = if (refDpi > 0.01) curDensity / refDpi else 1.0
        // ratio 附近细步进 + 固定档，覆盖制作端与运行端 DPI/分辨率差异
        return linkedSetOf(ratio, ratio * 0.9, 1.0, ratio * 0.75, ratio * 1.1, ratio * 1.25).toList()
    }

    /** 单尺度匹配结果：命中点（或 null）+ 该尺度最佳分数 + 最佳窗口中心（原图像素，无可评窗口 -1）。 */
    private data class ScaleOutcome(
        val hit: Hit?,
        val bestScore: Double,
        val bestCx: Int,
        val bestCy: Int,
    )

    /** 返回命中点与该尺度最佳分数；尺度不适用返回 null。 */
    private fun matchAtScale(
        screen: Image, template: Image,
        leftPct: Double, topPct: Double, rightPct: Double, bottomPct: Double,
        minSimilar: Int, scale: Double, wantBest: Boolean,
    ): ScaleOutcome? {
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
            // v2.9.35：此前 ds 已达 dsCap 而 cost 仍超限时直接 return null，导致
            // 高分辨率屏 + 小模板（dsCap=2）在所有尺度上"降无可降"被整帧放弃——
            // 找图恒定失败且无任何日志。资源预算不足不应等同于"目标不存在"：
            // 到降采样极限后仍执行一次匹配（接受较高成本），宁可慢也不能找不到。
            if (cost <= costCap || ds >= dsCap) {
                return matchCore(screen, template, sw, sh, tw, th, leftPct, topPct, rightPct, bottomPct, minSimilar, wantBest)
            }
            ds = min(ds * 2, dsCap)
        }
    }

    private fun matchCore(
        screen: Image, template: Image,
        sw: Int, sh: Int, tw: Int, th: Int,
        leftPct: Double, topPct: Double, rightPct: Double, bottomPct: Double,
        minSimilar: Int, wantBest: Boolean,
    ): ScaleOutcome {
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
        if (denomT < 1e-9) return ScaleOutcome(null, 0.0, -1, -1)

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
        if (xEnd < rx0 || yEnd < ry0) return ScaleOutcome(null, 0.0, -1, -1)

        val threshold = minSimilar.coerceIn(1, 100) / 100.0
        var bestScore = 0.0
        var bestWx = -1
        var bestWy = -1
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
                if (score > bestScore) {
                    bestScore = score
                    bestWx = wx
                    bestWy = wy
                }
                if (!wantBest && num >= threshold * denomW * denomT) {
                    return ScaleOutcome(
                        Hit(
                            cx = ((wx + tw / 2) * screen.width / sw),
                            cy = ((wy + th / 2) * screen.height / sh),
                            score = score,
                        ),
                        score,
                        toOrigCx(screen, sw, wx, tw),
                        toOrigCy(screen, sh, wy, th),
                    )
                }
            }
        }
        return ScaleOutcome(
            null,
            bestScore,
            toOrigCx(screen, sw, bestWx, tw),
            toOrigCy(screen, sh, bestWy, th),
        )
    }

    /** 降采样窗口坐标 → 原图像素中心（窗口未评分为 -1）。 */
    private fun toOrigCx(screen: Image, sw: Int, wx: Int, tw: Int): Int =
        if (wx < 0) -1 else (wx + tw / 2) * screen.width / sw

    private fun toOrigCy(screen: Image, sh: Int, wy: Int, th: Int): Int =
        if (wy < 0) -1 else (wy + th / 2) * screen.height / sh

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

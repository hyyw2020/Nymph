// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import dev.nymph.auto.core.model.MaskRect
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **稳定局部锚点选取 + 动态区域掩码**（v2.9.15 / A1 根因修复，core 纯 JVM，可单测）。
 *
 * ## 修的是什么
 *
 * v2.9.14 把「整块 ROI」裁下来当 `APPEAR_IMAGE` 模板。但用户点击**就发生在 ROI 内**，
 * 点击后 ROI 内容改变 → 模板匹配判「消失」 → 稍恢复判「再现」 → 边沿再点火 →
 * 再点……这是**反馈回路自激振荡**，不是「快速闪烁待实测」的边角，边沿触发防不了
 * 「自己制造消失」。
 *
 * 本对象把模板换成 ROI 内一块**稳定局部锚点**（24–48px 的图标 / 文字标签等纹理丰富、
 * 位置固定的小块），ROI 退回「搜索范围」。点击改变的是锚点周边，锚点本身不随点击变化，
 * 从根上切断反馈回路；同时把 ROI 内「高方差 / 易变」的 cell 记成 [MaskRect]，运行时
 * 由 `ImageMask.suppress` 在匹配副本里抑制，避免数字/动效/波纹把 NCC 拉低。
 *
 * ## 能力边界（未验证项）（重要）
 *
 * core 是纯 JVM、拾取瞬间只有**一帧**，无法做「跨帧时域方差」来判定哪些像素真的会变。
 * 这里用**单帧空间统计**近似：梯度能量高的小块 = 纹理丰富、最适合做 NCC 锚点；
 * 空间方差极高的 cell = 可能含密集动态细节，作为掩码保守剔除。这是启发式而非真值，
 * 阈值默认值偏保守，且掩码只作用于「匹配副本」不改变点击、不改变存证。真机诊断数据
 * （A5 拾取即自测的最高命中分 / 次高干扰分 / 位置偏差）回来后再校准，不在无数据前
 * 引入运行时自适应行为。锚点选取失败（整块 ROI 近乎纯色）时返回实际值 [AnchorPick.stable=false]，
 * 由调用方显式降级，不误报稳定锚点命中。
 */
object StableAnchor {

    /** 像素矩形（左闭右开）。 */
    data class PxRect(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        val cx: Int get() = (l + r) / 2
        val cy: Int get() = (t + b) / 2
        val valid: Boolean get() = w > 0 && h > 0
    }

    /** 一次锚点选取结果。 */
    data class AnchorPick(
        /** 锚点像素矩形（相对全屏 Image）。 */
        val rect: PxRect,
        /** 辨识度（梯度能量 0~1）。 */
        val edgeEnergy: Double,
        /** 是否可信：整块 ROI 近乎纯色 / 太小 / 非法时为 false，调用方应降级。 */
        val stable: Boolean,
    ) {
        companion object {
            fun invalid() = AnchorPick(PxRect(0, 0, 0, 0), 0.0, false)
        }
    }

    /** 锚点目标边长（像素，取自「屏幕短边」的自适应值下限 ~24 / 上限 ~48）。 */
    const val ANCHOR_MIN_PX = 24
    const val ANCHOR_MAX_PX = 48

    /** 低于该梯度能量的锚点视为「几乎纯色、不适合 NCC」（0~1）。 */
    const val MIN_EDGE_ENERGY = 0.02

    /** 高于该空间方差的 cell 视为「高方差 / 易变」，纳入掩码（0~1，方差归一化）。 */
    const val HIGH_VARIANCE = 0.16

    /**
     * 依据屏幕短边算锚点边长（24~48px）：短边越大锚点略大，保证物理尺寸合理。
     * 纯函数，异形屏 / 横竖屏一致。
     */
    fun anchorSizePx(screenW: Int, screenH: Int): Int {
        val short = kotlin.math.min(screenW, screenH).coerceAtLeast(1)
        val raw = (short * 0.032).toInt() // 800 短边 → ~26px；1200 → ~38px
        return raw.coerceIn(ANCHOR_MIN_PX, ANCHOR_MAX_PX)
    }

    /**
     * 灰度方差（归一化 0~1）。用于「高方差 cell」判定。空/非法矩形返回 0。
     */
    fun variance(img: ImageFinder.Image, l: Int, t: Int, r: Int, b: Int): Double {
        val (lo, to, ro, bo) = clamp(img, l, t, r, b) ?: return 0.0
        var n = 0; var sum = 0.0; var sum2 = 0.0
        for (y in to until bo) {
            val row = y * img.width
            for (x in lo until ro) {
                val g = gray(img.argb[row + x])
                sum += g; sum2 += g * g; n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        val v = (sum2 / n) - mean * mean
        // 灰度 0..255 → 方差上界约 255^2/4 ≈ 16256，归一化到 0..1（再 clamp）
        return (v / 6500.0).coerceIn(0.0, 1.0)
    }

    /**
     * 梯度能量（相邻像素灰度差绝对值的平均，归一化 0~1）。越高 = 纹理越丰富、
     * 越适合当 NCC 锚点。空/非法矩形返回 0。
     */
    fun edgeEnergy(img: ImageFinder.Image, l: Int, t: Int, r: Int, b: Int): Double {
        val (lo, to, ro, bo) = clamp(img, l, t, r, b) ?: return 0.0
        if (ro - lo < 2 || bo - to < 2) return 0.0
        var n = 0L; var acc = 0.0
        for (y in to until bo) {
            val row = y * img.width
            for (x in lo until ro - 1) {
                acc += abs(gray(img.argb[row + x]) - gray(img.argb[row + x + 1])); n++
            }
        }
        for (y in to until bo - 1) {
            val row = y * img.width; val row2 = (y + 1) * img.width
            for (x in lo until ro) {
                acc += abs(gray(img.argb[row + x]) - gray(img.argb[row2 + x])); n++
            }
        }
        return if (n == 0L) 0.0 else (acc / n / 255.0).coerceIn(0.0, 1.0)
    }

    /**
     * 在 [roi] 内选一块稳定局部锚点（24–48px 的图标 / 文字标签等纹理丰富块）。
     *
     * 策略（单帧启发式）：按 [stepPx] 网格滑动 [anchorPx] 大小的候选窗口，打分 =
     * **梯度能量（纹理辨识度）为主** + 「靠近拾取点」的距离奖励 —— 用户点哪里，
     * 锚点优先落在那附近（那里通常正是要点的图标/标签）。取最高分窗口。
     *
     * **刻意不惩罚高方差**：单帧空间统计下，高方差恰恰是图标/文字标签的特征
     * （用户明确要拿它当锚点），把高方差当「不稳定」排除会把最该选的块剔除 ——
     * 这是 v2.9.15 首轮单测抓出的真实设计缺陷。「哪些像素会随点击变化」单帧无法
     * 判定，交给两件事：① [planMask] 把 ROI 内**除锚点外**的高方差 cell 作为掩码，
     * 在匹配副本里抑制；② A5 拾取即自测（最高命中分 / 次高干扰分 / 位置偏差）
     * 暴露 secure 黑帧 / 裁歪等静默失败，由真机诊断数据校准。绝不在无数据前
     * 用空间方差误标为「时域稳定性」。
     *
     * @return 全部分数都低于 [MIN_EDGE_ENERGY] 时返回 stable=false（整块 ROI 近乎纯色），
     *         调用方应显式降级为「仅 ROI 区域限定」，不误报稳定锚点命中。
     */
    fun pickAnchor(
        img: ImageFinder.Image,
        roi: PxRect,
        pickX: Int,
        pickY: Int,
        anchorPx: Int = ANCHOR_MIN_PX,
        stepPx: Int = 0,
    ): AnchorPick {
        val (lo, to, ro, bo) = clamp(img, roi.l, roi.t, roi.r, roi.b) ?: return AnchorPick.invalid()
        val side = anchorPx.coerceIn(ANCHOR_MIN_PX, ANCHOR_MAX_PX).coerceAtMost(kotlin.math.min(ro - lo, bo - to))
        if (side < 8) return AnchorPick.invalid()
        val step = if (stepPx > 0) stepPx else (side / 2).coerceAtLeast(4)
        val maxDist = sqrt(((ro - lo) * (ro - lo) + (bo - to) * (bo - to)).toDouble()).coerceAtLeast(1.0)
        var best: PxRect? = null
        var bestScore = -1.0
        var y = to
        while (y + side <= bo) {
            var x = lo
            while (x + side <= ro) {
                val rect = PxRect(x, y, x + side, y + side)
                val energy = edgeEnergy(img, x, y, x + side, y + side)
                val dx = (rect.cx - pickX).toDouble()
                val dy = (rect.cy - pickY).toDouble()
                val dist = sqrt(dx * dx + dy * dy)
                val proximity = 1.0 - (dist / maxDist).coerceIn(0.0, 1.0)
                val score = energy + 0.15 * proximity
                if (score > bestScore) { bestScore = score; best = rect }
                x += step
            }
            y += step
        }
        val rect = best ?: return AnchorPick.invalid()
        val energy = edgeEnergy(img, rect.l, rect.t, rect.r, rect.b)
        return AnchorPick(rect, energy, energy >= MIN_EDGE_ENERGY)
    }

    /**
     * 生成动态区域掩码：把 ROI 内「空间方差 > [highVar]」的 cell 记录为像素矩形，
     * **排除与锚点重叠的 cell**（绝不把锚点自己抑制掉）。运行时由调用方转成
     * [MaskRect] 屏幕百分比并交给 `ImageMask.suppress`。
     *
     * @param cellPx 网格边长（像素）；0 = 取锚点边长。
     */
    fun planMask(
        img: ImageFinder.Image,
        roi: PxRect,
        anchor: PxRect,
        cellPx: Int = 0,
        highVar: Double = HIGH_VARIANCE,
    ): List<PxRect> {
        val (lo, to, ro, bo) = clamp(img, roi.l, roi.t, roi.r, roi.b) ?: return emptyList()
        val cell = if (cellPx > 0) cellPx else anchor.w.coerceAtLeast(ANCHOR_MIN_PX)
        val out = ArrayList<PxRect>()
        var y = to
        while (y < bo) {
            var x = lo
            while (x < ro) {
                val cr = kotlin.math.min(x + cell, ro)
                val cb = kotlin.math.min(y + cell, bo)
                val c = PxRect(x, y, cr, cb)
                val overlapsAnchor = c.l < anchor.r && c.r > anchor.l && c.t < anchor.b && c.b > anchor.t
                if (!overlapsAnchor && variance(img, x, y, cr, cb) > highVar) out.add(c)
                x += cell
            }
            y += cell
        }
        return out
    }

    /** 时域掩码的默认帧间灰度差阈值（0~255 口径）。 */
    const val DEFAULT_TEMPORAL_DELTA = 12.0

    /**
     * **时域掩码**（v2.9.31，纯函数，可单测）：用**两帧差分**决定动态区，
     * 替代 [planMask] 的单帧空间方差启发式。
     *
     * 真机 RCA 确证：单帧空间方差挑出的「高方差 cell」在用户滑动后内容已变，
     * 抑制时把上一行像素复制下来可能反而抹掉有效特征；日志 `掩码盖住=0/8`
     * 说明最佳窗口压根不在这些区域，抑制对本次失配既无贡献也无挽救。
     *
     * 现在只把「帧间确实发生变化」的 cell 纳入掩码，且与锚点重叠的 cell
     * 一律排除。帧尺寸不一致 / 无第二帧时返回空列表，由装配期显式回退到
     * [planMask]（并在日志标注「单帧启发式」），不静默当成时域结果。
     */
    fun planMaskTemporal(
        base: ImageFinder.Image,
        later: ImageFinder.Image,
        roi: PxRect,
        anchor: PxRect,
        cellPx: Int = 0,
        deltaThreshold: Double = DEFAULT_TEMPORAL_DELTA,
    ): List<PxRect> {
        if (base.width != later.width || base.height != later.height) return emptyList()
        val (lo, to, ro, bo) = clamp(base, roi.l, roi.t, roi.r, roi.b) ?: return emptyList()
        val cell = if (cellPx > 0) cellPx else anchor.w.coerceAtLeast(ANCHOR_MIN_PX)
        val out = ArrayList<PxRect>()
        var y = to
        while (y < bo) {
            var x = lo
            while (x < ro) {
                val cr = kotlin.math.min(x + cell, ro)
                val cb = kotlin.math.min(y + cell, bo)
                val c = PxRect(x, y, cr, cb)
                val overlapsAnchor = c.l < anchor.r && c.r > anchor.l && c.t < anchor.b && c.b > anchor.t
                if (!overlapsAnchor && meanAbsDiff(base, later, x, y, cr, cb) > deltaThreshold) {
                    out.add(c)
                }
                x += cell
            }
            y += cell
        }
        return out
    }

    /** 两帧在同一像素矩形内的灰度绝对差均值（0~255）。尺寸不一致 / 空矩形返回 0。 */
    fun meanAbsDiff(
        a: ImageFinder.Image,
        b: ImageFinder.Image,
        l: Int,
        t: Int,
        r: Int,
        bo: Int,
    ): Double {
        if (a.width != b.width || a.height != b.height) return 0.0
        val ca = clamp(a, l, t, r, bo) ?: return 0.0
        val (lo, to, ro, bot) = ca
        var n = 0L
        var acc = 0.0
        for (y in to until bot) {
            val row = y * a.width
            for (x in lo until ro) {
                acc += abs(gray(a.argb[row + x]) - gray(b.argb[row + x]))
                n++
            }
        }
        return if (n == 0L) 0.0 else acc / n
    }

    /** 像素矩形 → 屏幕百分比 [MaskRect]（供落盘 / 运行时抑制）。 */
    fun toMaskPct(rect: PxRect, screenW: Int, screenH: Int): MaskRect {
        val w = screenW.coerceAtLeast(1).toDouble()
        val h = screenH.coerceAtLeast(1).toDouble()
        return MaskRect(
            leftPct = (100.0 * rect.l / w).coerceIn(0.0, 100.0),
            topPct = (100.0 * rect.t / h).coerceIn(0.0, 100.0),
            rightPct = (100.0 * rect.r / w).coerceIn(0.0, 100.0),
            bottomPct = (100.0 * rect.b / h).coerceIn(0.0, 100.0),
        )
    }

    /** 像素矩形 → 屏幕百分比 [dev.nymph.auto.core.model.Region]（供 anchorRectPct 落盘）。 */
    fun toRegionPct(rect: PxRect, screenW: Int, screenH: Int): dev.nymph.auto.core.model.Region {
        val w = screenW.coerceAtLeast(1).toDouble()
        val h = screenH.coerceAtLeast(1).toDouble()
        val l = (100.0 * rect.l / w).coerceIn(0.0, 100.0)
        val t = (100.0 * rect.t / h).coerceIn(0.0, 100.0)
        val r = (100.0 * rect.r / w).coerceIn(0.0, 100.0)
        val b = (100.0 * rect.b / h).coerceIn(0.0, 100.0)
        return dev.nymph.auto.core.model.Region(l, t, r, b, "")
    }

    private fun gray(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** 把矩形 clamp 到图像内；返回 null = 非法/空。 */
    private fun clamp(img: ImageFinder.Image, l: Int, t: Int, r: Int, b: Int): IntArray? {
        if (img.width <= 0 || img.height <= 0 || img.argb.size < img.width * img.height) return null
        val lo = l.coerceIn(0, img.width - 1)
        val to = t.coerceIn(0, img.height - 1)
        val ro = r.coerceIn(lo + 1, img.width)
        val bo = b.coerceIn(to + 1, img.height)
        if (ro - lo < 1 || bo - to < 1) return null
        return intArrayOf(lo, to, ro, bo)
    }
}

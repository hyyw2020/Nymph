package dev.nymph.auto.core.video

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视频触点候选检测（v2.7.0，纯 JVM 可测）。
 *
 * ## 回应的反馈与能力边界（未验证项）
 *
 * 用户希望「录一个 mp4 导入 NymphAuto 扫描，自动总结点击特征，直接生成脚本」。
 * **全自动不可靠**：视频里没有「这里是第 3 次点击」的标签，按压涟漪、
 * 按钮高亮、列表滚动、页面切换在像素上都表现为帧差，纯算法必然猜错。
 * 因此本类只做**候选检测**（高召回、允许误报），把结果交给半自动工作台：
 * 用户在时间轴上确认/校正 → 从对应帧截取模板 → 生成 CLICK_IMAGE 特征脚本。
 *
 * ## 算法（零依赖、不申请录屏权限）
 *
 * 输入是 Android 层用 MediaExtractor/MediaCodec 从**用户已导入的 mp4** 解出的
 * 降采样灰度帧（不涉及 `MediaProjection`，不抢系统录屏）：
 *  1. 相邻帧逐像素绝对差，超过阈值记为变化像素；
 *  2. 全帧变化占比过高 → 判为翻页/滚动/转场，整帧跳过；
 *  3. 变化能量按网格聚合，取 3×3 邻域局部峰（小范围突发 = 触点特征）；
 *  4. 以「该格能量 / 全帧变化能量」为集中度打分，越集中越像点击；
 *  5. 时空去重（相邻帧、邻近位置合并），按时间排序输出。
 *
 * 结果仅作候选，不宣称「自动总结点击特征」。
 */
object TapCandidateDetector {

    /** 单帧降采样灰度图（0..255），尺寸必须全序列一致。 */
    data class FrameSample(
        val tMs: Long,
        val width: Int,
        val height: Int,
        val gray: IntArray,
    )

    data class TapCandidate(
        val tMs: Long,
        val frameIndex: Int,
        val x: Int,
        val y: Int,
        val radiusPx: Int,
        val score: Double,
    )

    data class Options(
        val diffThreshold: Int = 26,
        /** 变化像素占比超过此值视为全局变化（翻页/滚动），跳过整帧。 */
        val rejectGlobalChangeRatio: Double = 0.35,
        val gridCells: Int = 24,
        val minCellEnergy: Int = 12,
        val mergeTimeMs: Long = 250L,
        /** 合并距离占屏幕短边比例。 */
        val mergeDistPct: Double = 0.06,
        val minScore: Double = 0.02,
        val maxCandidates: Int = 60,
        /**
         * v2.9.47：关键帧稀疏化时间表。默认空 = 均匀采样（老行为零回归）。
         * app 层用 MediaExtractor 读出 SAMPLE_FLAG_SYNC 后填入；仅在密集区
         * 参与块匹配判滑动，稀疏区只取代表帧，解码量降一个数量级。
         */
        val keyframeSchedule: TapEvidence.KeyframeSchedule = TapEvidence.KeyframeSchedule(),
        /**
         * v2.9.47：是否启用涟漪/遮挡增强证据参与融合。默认 false = 老行为逐字零回归；
         * 新链路（detectWithEvidence）显式开启，纯 Kotlin、无模型。
         */
        val enableEnhancedEvidence: Boolean = false,
        /**
         * v2.9.48：单票强证据阈值（透传给 fuse）。单票强度 ≥ 此值不再标记待确认。
         */
        val strongSingleStrength: Double = 0.6,
    )

    /**
     * 带证据明细的候选（产物格式超集）：[TapCandidate] 之外附上四证据投票结果。
     * 仅在 [detectWithEvidence] 产出；[detect] 仍返回老 [TapCandidate]，零回归。
     */
    data class EvidenceCandidate(
        val candidate: TapCandidate,
        val votes: Int,
        val confidence: Double,
        val needsReview: Boolean,
        val conflict: Boolean,
        val sources: Set<TapEvidence.Kind>,
        /** v2.9.48：簇内正证据平均自身强度，供 UI 单一真源分级。 */
        val strengthAvg: Double = 0.0,
    )

    fun detect(frames: List<FrameSample>, opts: Options = Options()): List<TapCandidate> {
        if (frames.size < 2) return emptyList()
        val w = frames[0].width
        val h = frames[0].height
        if (w <= 0 || h <= 0) return emptyList()
        if (frames.any { it.width != w || it.height != h || it.gray.size != w * h }) return emptyList()

        val cell = max(4, min(w, h) / max(1, opts.gridCells))
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val nCells = gw * gh
        val mergeDist = min(w, h) * opts.mergeDistPct
        val raw = ArrayList<TapCandidate>()

        for (i in 1 until frames.size) {
            val prev = frames[i - 1].gray
            val cur = frames[i].gray
            val energy = IntArray(nCells)
            val sumX = IntArray(nCells)
            val sumY = IntArray(nCells)
            var changed = 0
            var totalEnergy = 0L

            for (y in 0 until h) {
                val rowBase = y * w
                val gy = y / cell
                val gRow = gy * gw
                for (x in 0 until w) {
                    val d = abs(cur[rowBase + x] - prev[rowBase + x])
                    if (d < opts.diffThreshold) continue
                    changed++
                    totalEnergy += d
                    val g = gRow + x / cell
                    energy[g] += d
                    sumX[g] += x * d
                    sumY[g] += y * d
                }
            }
            if (changed == 0 || totalEnergy <= 0L) continue
            val ratio = changed.toDouble() / (w.toLong() * h)
            if (ratio > opts.rejectGlobalChangeRatio) continue

            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val g = gy * gw + gx
                    val e = energy[g]
                    if (e < opts.minCellEnergy) continue
                    if (!isLocalPeak(energy, gw, gh, gx, gy, e, g)) continue
                    // 用 3×3 邻域聚合能量与质心：真实触点涟漪会跨多个网格，
                    // 只算单格会低估集中度，质心也会偏到格边。
                    val neigh = neighborhood(energy, sumX, sumY, gw, gh, gx, gy)
                    val concentration = neigh.first.toDouble() / totalEnergy.toDouble()
                    val score = concentration * (1.0 - ratio)
                    if (score < opts.minScore) continue
                    val cx = (neigh.second.toDouble() / neigh.first).roundToInt().coerceIn(0, w - 1)
                    val cy = (neigh.third.toDouble() / neigh.first).roundToInt().coerceIn(0, h - 1)
                    raw += TapCandidate(frames[i].tMs, i, cx, cy, cell, score)
                }
            }
        }
        return dedupe(raw, opts.mergeTimeMs, mergeDist, opts.maxCandidates)
    }

    // ------------------------------------------------------------------ v2.9.47
    // 四证据投票增强入口。纯 Kotlin、零 native、零模型；默认 detect() 完全不走这里，
    // 老行为逐字零回归。新链路显式调用本方法，拿到带置信度/待确认标记的候选。

    /**
     * 四证据融合检测（产物格式超集）。
     *
     * 主干 [detect] 的帧差局部峰作为 TAP_FINGER 正证据；块匹配全局位移近似作为
     * SWIPE 负证据；可选开启 RIPPLE（帧差时间二阶导）与 OCCLUSION（局部对比度下降）
     * 两个增强正证据。经 [TapEvidence.fuse] 时空投票：
     *  - ≥2 独立证据源 → 正常候选；
     *  - 仅 1 票 → needsReview=true（低置信度待确认）；
     *  - 滑动负证据同域 → conflict=true（强制人确认）。
     *
     * 任一证据源缺席（如无关键帧时间表、无 RGB 无法测肤色）都不阻断流水线：
     * 退化为单 TAP_FINGER 票，仍产出候选，只是被标记待确认。
     */
    fun detectWithEvidence(
        frames: List<FrameSample>,
        opts: Options = Options(),
    ): List<EvidenceCandidate> {
        val base = detect(frames, opts)
        if (base.isEmpty()) return emptyList()
        val w = frames[0].width
        val h = frames[0].height
        val votes = ArrayList<TapEvidence.Vote>()

        // 证据②主干：帧差局部峰 → TAP_FINGER 正证据（score 即自身强度）。
        base.forEach {
            votes += TapEvidence.Vote(
                it.tMs, it.x, it.y, TapEvidence.Kind.TAP_FINGER,
                it.score.coerceIn(0.0, 1.0),
            )
        }

        // 证据①：关键帧稀疏化 + 块匹配判滑动 → SWIPE 负证据。
        // 全局变化帧（变化占比超阈值）= 整屏平移/翻页，其时段的点击候选标记冲突。
        val denseOnly = opts.keyframeSchedule.timesUs.isNotEmpty()
        for (i in 1 until frames.size) {
            val f = frames[i]
            if (denseOnly && !TapEvidence.isDenseRegion(f.tMs, opts.keyframeSchedule)) continue
            var changed = 0
            val prev = frames[i - 1].gray
            val cur = f.gray
            for (p in cur.indices) if (abs(cur[p] - prev[p]) >= opts.diffThreshold) changed++
            val ratio = changed.toDouble() / max(1, cur.size)
            if (ratio > opts.rejectGlobalChangeRatio) {
                votes += TapEvidence.Vote(
                    f.tMs, w / 2, h / 2, TapEvidence.Kind.SWIPE, ratio, positive = false,
                )
            }
        }

        if (opts.enableEnhancedEvidence) {
            // 证据③：按压涟漪瞬态——ROI 帧差能量时间二阶导先升后降（正负翻转）。
            for (c in base) {
                val e = rippleEnergy(frames, c, opts)
                if (e != null && e.second && e.first > 0.0) {
                    votes += TapEvidence.Vote(
                        c.tMs, c.x, c.y, TapEvidence.Kind.RIPPLE,
                        min(1.0, e.first * 2.0),
                    )
                }
            }
            // 证据④：手指遮挡弱证据——ROI 局部对比度相对前一帧下降（灰度近似，可选）。
            for (c in base) {
                if (contrastDrop(frames, c)) {
                    votes += TapEvidence.Vote(
                        c.tMs, c.x, c.y, TapEvidence.Kind.OCCLUSION, 0.3,
                    )
                }
            }
        }

        val fused = TapEvidence.fuse(
            votes, w,
            TapEvidence.FuseOptions(
                minVotes = 1,          // TAP_FINGER 主干必进，单票由 needsReview 标记
                lowConfidenceVotes = 2,
                strongSingleStrength = opts.strongSingleStrength,
            ),
        )
        // 融合候选按时间排序，与 base 中最近邻对齐，回填 score/radius（产物格式不变）。
        return fused.map { fu ->
            val matched = base.minByOrNull { abs(it.tMs - fu.tMs) }
            val score = matched?.score ?: fu.confidence
            val radius = matched?.radiusPx ?: min(w, h) / max(1, opts.gridCells)
            EvidenceCandidate(
                candidate = TapCandidate(
                    fu.tMs, matched?.frameIndex ?: -1, fu.x, fu.y, radius, score,
                ),
                votes = fu.votes,
                confidence = fu.confidence,
                needsReview = fu.needsReview,
                conflict = fu.conflict,
                sources = fu.sources,
                strengthAvg = fu.strengthAvg,
            )
        }
    }

    /** 候选邻域在 [frameIndex-1, frameIndex, frameIndex+1] 的 ROI 帧差能量，判是否出现瞬态峰。 */
    private fun rippleEnergy(
        frames: List<FrameSample>, c: TapCandidate, opts: Options,
    ): Pair<Double, Boolean>? {
        val idx = c.frameIndex
        if (idx <= 0 || idx >= frames.size) return null
        val r = max(3, c.radiusPx)
        fun energy(i: Int): Double {
            if (i <= 0 || i >= frames.size) return 0.0
            val prev = frames[i - 1]
            val cur = frames[i]
            val w = cur.width
            var changed = 0
            var total = 0
            for (y in max(0, c.y - r)..min(cur.height - 1, c.y + r)) {
                for (x in max(0, c.x - r)..min(w - 1, c.x + r)) {
                    val p = y * w + x
                    if (p >= prev.gray.size || p >= cur.gray.size) continue
                    total++
                    if (abs(cur.gray[p] - prev.gray[p]) >= opts.diffThreshold) changed++
                }
            }
            return if (total == 0) 0.0 else changed.toDouble() / total
        }
        val ePrev = energy(idx - 1)
        val eCur = energy(idx)
        val eNext = energy(idx + 1)
        // 二阶导翻转：当前帧能量高于前后两帧 = 局部瞬态（按下涟漪）。
        return eCur to (eCur > ePrev && eCur > eNext)
    }

    /** 候选邻域局部对比度（灰度方差）相对前一帧是否下降（手指遮挡灰度近似）。 */
    private fun contrastDrop(frames: List<FrameSample>, c: TapCandidate): Boolean {
        val idx = c.frameIndex
        if (idx <= 0 || idx >= frames.size) return false
        val r = max(4, c.radiusPx)
        fun variance(f: FrameSample): Double {
            val w = f.width
            var sum = 0L
            var sumSq = 0L
            var n = 0
            for (y in max(0, c.y - r)..min(f.height - 1, c.y + r)) {
                for (x in max(0, c.x - r)..min(w - 1, c.x + r)) {
                    val p = y * w + x
                    if (p >= f.gray.size) continue
                    val v = f.gray[p]
                    sum += v
                    sumSq += v.toLong() * v
                    n++
                }
            }
            if (n == 0) return 0.0
            val mean = sum.toDouble() / n
            return max(0.0, sumSq.toDouble() / n - mean * mean)
        }
        val prev = variance(frames[idx - 1])
        val cur = variance(frames[idx])
        return prev > 4.0 && cur < prev * 0.75
    }

    /** 3×3 邻域的 (能量和, x 加权和, y 加权和)；能量和至少 1 防除零。 */
    private fun neighborhood(
        energy: IntArray, sumX: IntArray, sumY: IntArray,
        gw: Int, gh: Int, gx: Int, gy: Int,
    ): Triple<Int, Int, Int> {
        var e = 0
        var sx = 0
        var sy = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = gx + dx
                val ny = gy + dy
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                val ng = ny * gw + nx
                e += energy[ng]
                sx += sumX[ng]
                sy += sumY[ng]
            }
        }
        return Triple(e.coerceAtLeast(1), sx, sy)
    }

    private fun isLocalPeak(
        energy: IntArray, gw: Int, gh: Int, gx: Int, gy: Int, e: Int, g: Int,
    ): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = gx + dx
                val ny = gy + dy
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                val ng = ny * gw + nx
                if (energy[ng] > e || (energy[ng] == e && ng < g)) return false
            }
        }
        return true
    }

    /**
     * v2.9.6：默认勾选建议——按时间分桶，每桶只取该阶段得分最高的一个，
     * 最多 [buckets] 个，返回的是候选列表下标集合。
     *
     * 含大量全屏转场特效的游戏视频会产出几十个动画噪声候选；若默认全选，
     * 门控「等到画面出现」模式下脚本会卡在某个永不再现的动画帧上（无限等待）。
     * 这里给一个「覆盖各阶段、数量可控」的起点，用户仍可在工作台里全改。
     */
    fun pickDefaults(candidates: List<TapCandidate>, buckets: Int = 6): Set<Int> {
        if (candidates.isEmpty() || buckets <= 0) return emptySet()
        if (candidates.size <= buckets) return candidates.indices.toSet()
        val t0 = candidates.minOf { it.tMs }
        val span = (candidates.maxOf { it.tMs } - t0).coerceAtLeast(1L)
        // bucket -> (下标, 得分)
        val best = HashMap<Int, Pair<Int, Double>>()
        candidates.forEachIndexed { i, c ->
            val b = (((c.tMs - t0).toDouble() / span) * buckets)
                .toInt().coerceIn(0, buckets - 1)
            val cur = best[b]
            if (cur == null || c.score > cur.second) best[b] = i to c.score
        }
        return best.values.map { it.first }.toSet()
    }

    /** 贪心去重：得分高者优先；时间/空间都接近的视为同一次点击。 */
    private fun dedupe(
        raw: List<TapCandidate>, mergeTimeMs: Long, mergeDist: Double, maxCount: Int,
    ): List<TapCandidate> {
        val kept = ArrayList<TapCandidate>(maxCount)
        for (c in raw.sortedByDescending { it.score }) {
            val conflict = kept.any { k ->
                kotlin.math.abs(k.tMs - c.tMs) <= mergeTimeMs &&
                    kotlin.math.hypot(k.x - c.x.toDouble(), k.y - c.y.toDouble()) <= mergeDist
            }
            if (!conflict) kept += c
            if (kept.size >= maxCount) break
        }
        return kept.sortedBy { it.tMs }
    }
}

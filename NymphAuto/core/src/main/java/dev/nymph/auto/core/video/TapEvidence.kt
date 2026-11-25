// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.video

import kotlin.math.max
import kotlin.math.min

/**
 * B 通道四证据投票的公共类型（v2.9.47，纯 JVM、零 native 依赖）。
 *
 * ## 不变式：产物格式永不变化
 *
 * 四证据（A 触摸真值 / B 纯 CV / C 端侧 IDM / D 零模型 OCR）无论走哪条路径，
 * 最终都汇总成同一套 [TapCandidate] 候选，再交给 [VideoScriptBuilder] 编译成
 * 同一 schema 的 sceneDriven 规则。本文件只定义 B 通道的**证据与融合中间态**，
 * 不触碰落盘格式、不改变对外 API。
 *
 * ## 四证据源（全部手写、确定性、可单测）
 *
 *  - [EvidenceKind.SWIPE]：编解码器关键帧稀疏化 + 帧差分块匹配判**滑动**（负证据）。
 *    录屏编码器的 SAMPLE_FLAG_SYNC 时间表标出「大变化」区间，只在关键帧附近密集
 *    解码；块匹配求全局位移中位数，整屏平移 = 滑动 → 抑制点击候选。
 *  - [EvidenceKind.TAP_FINGER]：帧差网格能量局部峰（正证据，现有的帧差主干）。
 *  - [EvidenceKind.RIPPLE]：按压态瞬态——ROI 帧差时间二阶导正负翻转（正证据）。
 *  - [EvidenceKind.OCCLUSION]：手指遮挡弱证据（肤色近似 + 局部对比度下降），只提供一票，
 *    精度不足以单独定位（可选、默认关闭）。
 *
 * ## 投票规则（对应设计文档）
 *
 *  ≥2 个证据源在同一时空邻域（±200ms、±12% 屏宽）对齐 → 进入候选；
 *  仅 1 票 → 低置信度（needsReview）；冲突（滑动负证据与点击正证据同域）→ 强制人确认。
 *  全过程离线、可解释、可单元测试，不依赖任何模型。
 */
object TapEvidence {

    /** 证据种类。可用性彼此独立——任意一条缺席不影响其余产出。 */
    enum class Kind {
        /** 滑动/翻页负证据（块匹配全局位移）。 */
        SWIPE,
        /** 帧差网格能量局部峰（点击正证据）。 */
        TAP_FINGER,
        /** 按压涟漪瞬态（帧差时间二阶导）。 */
        RIPPLE,
        /** 手指遮挡弱证据（可选）。 */
        OCCLUSION,
    }

    /**
     * 单个证据源在某一时空点的一票。
     *
     * @param tMs 证据时刻（毫秒）
     * @param x 证据像素 x（在采样帧坐标系）
     * @param y 证据像素 y
     * @param kind 证据种类
     * @param strength 该票自身强度 0~1（用于置信度标定，不是模型自述）
     * @param positive true=支持「此处有触点」，false=反对（滑动负证据）
     */
    data class Vote(
        val tMs: Long,
        val x: Int,
        val y: Int,
        val kind: Kind,
        val strength: Double,
        val positive: Boolean = true,
    )

    /** 关键帧稀疏化时间表：由 app 层 MediaExtractor 产出，core 只消费。 */
    data class KeyframeSchedule(
        /** 关键帧时间戳（微秒，MediaExtractor 口径）。 */
        val timesUs: List<Long> = emptyList(),
        /** 视频总时长（毫秒）。 */
        val durationMs: Long = 0L,
    )

    data class FuseOptions(
        /** 时间对齐窗口（毫秒）。 */
        val alignTimeMs: Long = 200L,
        /** 空间对齐窗口，占屏宽比例。 */
        val alignWidthPct: Double = 0.12,
        /**
         * 进入候选所需的最少独立正证据源数。默认 1 = 单票也保留（设计文档：
         * 「仅 1 票 → 标记低置信度待确认」，供人工确认而非直接丢弃）。
         * 调用方可提高门槛；提高后被过滤的簇即不再产出候选。
         */
        val minVotes: Int = 1,
        /** 低于该证据数 → 标记低置信度待确认。 */
        val lowConfidenceVotes: Int = 2,
        /**
         * v2.9.48：单票"强证据"阈值。旧语义下单票因 n<2 恒真待确认，标记退化为常量、
         * 53 个候选 53 个待确认。新语义：单票但自身强度 ≥ 此值 → 视为正常候选
         * （强帧差峰已足够可信），只有"单票且弱"才需要人确认。多票仍走 confidence 门槛。
         */
        val strongSingleStrength: Double = 0.6,
    )

    /** 融合后的一个候选（仍不是最终产物，最终产物是 [TapCandidate]）。 */
    data class Fused(
        val tMs: Long,
        val x: Int,
        val y: Int,
        /** 独立正证据源数。 */
        val votes: Int,
        /** 质量分 0~1（离线标定，非模型自述）。 */
        val confidence: Double,
        /** 是否低置信度待确认。 */
        val needsReview: Boolean,
        /** 是否时空冲突（滑动负证据与点击正证据同域）。 */
        val conflict: Boolean,
        /** 参与投票的证据种类（可观测，写入 meta）。 */
        val sources: Set<Kind>,
        /** v2.9.48：簇内正证据平均自身强度 0~1，供 UI 单一真源分级（弱单证据识别）。 */
        val strengthAvg: Double = 0.0,
    )

    /**
     * 四证据时空融合（纯函数）。
     *
     * 步骤：
     *  1. 只保留正证据，按时间邻域 + 空间邻域聚类（B 箱并查集式贪心，O(n·k)，n=票数、k簇数）；
     *  2. 每簇数独立证据源数量与加权强度；
     *  3. 负证据（SWIPE）若与某簇时空重叠 → 标记 conflict（强制人确认）；
     *  4. ≥[FuseOptions.minVotes] 票 → 候选，否则 needsReview；confidence = 加权标定。
     *
     * 不依赖顺序、不依赖浮点累加顺序之外的状态，确定性可单测。
     */
    fun fuse(votes: List<Vote>, width: Int, opts: FuseOptions = FuseOptions()): List<Fused> {
        if (votes.isEmpty() || width <= 0) return emptyList()
        val alignDist = max(1.0, width * opts.alignWidthPct)
        val positives = votes.filter { it.positive }.sortedBy { it.tMs }
        if (positives.isEmpty()) return emptyList()

        // 时空聚类：一票归入第一个「时间差与空间差都在窗口内」的簇。
        data class Cluster(
            var tMs: Long, var x: Int, var y: Int,
            val kinds: MutableSet<Kind> = LinkedHashSet(),
            var strengthSum: Double = 0.0,
            var count: Int = 0,
        ) {
            val cx: Int get() = x / max(1, count)
            val cy: Int get() = y / max(1, count)
            val ct: Long get() = tMs / max(1, count)
        }
        val clusters = ArrayList<Cluster>()
        for (v in positives) {
            var target: Cluster? = null
            for (c in clusters) {
                if (kotlin.math.abs(c.ct - v.tMs) <= opts.alignTimeMs &&
                    kotlin.math.hypot(c.cx - v.x.toDouble(), c.cy - v.y.toDouble()) <= alignDist
                ) {
                    target = c
                    break
                }
            }
            if (target == null) {
                // 以零值建空簇，统一走下方累加，避免首票坐标被加两次导致质心翻倍。
                clusters += Cluster(0L, 0, 0)
                target = clusters.last()
            }
            target.kinds += v.kind
            target.strengthSum += v.strength.coerceIn(0.0, 1.0)
            target.x += v.x
            target.y += v.y
            target.tMs += v.tMs
            target.count++
        }

        val negatives = votes.filter { !it.positive }
        val out = ArrayList<Fused>()
        for (c in clusters) {
            val n = c.kinds.size
            if (n < opts.minVotes) continue
            val conflict = negatives.any { nv ->
                kotlin.math.abs(nv.tMs - c.ct) <= opts.alignTimeMs &&
                    kotlin.math.hypot(nv.x - c.cx.toDouble(), nv.y - c.cy.toDouble()) <= alignDist
            }
            // 置信度：证据源数量占主导 + 强度加权，0~1 标定；冲突直接压到待确认。
            val strengthAvg = c.strengthSum / max(1, c.count)
            val confidence = min(1.0, (n.toDouble() / 4.0) * 0.7 + strengthAvg * 0.3)
            // v2.9.48：旧语义 `n < 2` 是独立硬门槛——单票即使强度 1.0、confidence
            // 也因公式上限 0.475 永远 < 0.75，导致 53/53 全待确认，标记退化为常量。
            // 新语义：待确认 = 冲突 OR (单票且自身强度弱)。单票强证据视为正常候选；
            // 多票 (>=2) 视为已完成交叉验证、不再需要人逐条裁决。
            // 注意保留 `needsReview = conflict` 前缀——v2947 静态门禁按此字符串锁定。
            out += Fused(
                tMs = c.ct,
                x = c.cx,
                y = c.cy,
                votes = n,
                confidence = confidence,
                needsReview = conflict ||
                    (n < opts.lowConfidenceVotes && strengthAvg < opts.strongSingleStrength),
                conflict = conflict,
                sources = LinkedHashSet(c.kinds),
                strengthAvg = strengthAvg,
            )
        }
        return out.sortedBy { it.tMs }
    }

    // ------------------------------------------------------------------ v2.9.48
    // 裁决分级的"单一真源"：列表行、时间轴、放大图三处共用同一函数推导颜色/文案，
    // 杜绝在呈现层各写一套判定导致漂移（方案核查指出绿档条件曾与 needsReview 不一致）。

    /** 候选裁决等级，优先级 CONFLICT > WEAK_SINGLE > NEEDS_REVIEW > OK。 */
    enum class ReviewLevel {
        /** 时空冲突（滑动负证据同域）——高风险，必确认。 */
        CONFLICT,
        /** 单票且自身强度弱——需确认。 */
        WEAK_SINGLE,
        /** 其他 needsReview（保留兼容：未来新增判据落此档）——需确认。 */
        NEEDS_REVIEW,
        /** 证据充分且无冲突——正常。 */
        OK,
    }

    /**
     * 从 fuse 输出推导裁决等级（纯函数，无 Android 依赖）。
     *
     * 判定顺序即优先级：冲突最高；其后用 [needsReview] 与 votes/strengthAvg 交叉，
     * 单票弱证据落到 [ReviewLevel.WEAK_SINGLE]，其余待确认落到 [ReviewLevel.NEEDS_REVIEW]。
     * UI 只负责把等级映射成颜色/标签，不得重写此处的判定。
     */
    fun levelOf(
        conflict: Boolean,
        needsReview: Boolean,
        votes: Int,
        strengthAvg: Double,
        strongSingleStrength: Double = 0.6,
    ): ReviewLevel = when {
        conflict -> ReviewLevel.CONFLICT
        !needsReview -> ReviewLevel.OK
        votes < 2 && strengthAvg < strongSingleStrength -> ReviewLevel.WEAK_SINGLE
        else -> ReviewLevel.NEEDS_REVIEW
    }

    /**
     * 关键帧稀疏化采样窗口（零解码预算辅助）：给定时间 t 是否落在关键帧附近密集区。
     *
     * 纯函数，app 层解法：关键帧 ±[windowMs] 内取密集帧，其余区间只取代表帧。
     * 时间表为空时返回 true（退化为均匀采样，零回归）。
     */
    fun isDenseRegion(tMs: Long, schedule: KeyframeSchedule, windowMs: Long = 150L): Boolean {
        if (schedule.timesUs.isEmpty()) return true
        return schedule.timesUs.any { kt -> kotlin.math.abs(kt / 1000L - tMs) <= windowMs }
    }
}

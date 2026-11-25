// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.video

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 离线回放证伪器（v2.9.47，纯 JVM、零依赖、不碰真机）。
 *
 * ## 「完美」在质量层面的唯一保证
 *
 * 候选动作生成后，在原视频的帧序列上**离线模拟执行**每一条规则：
 *  - 规则的前置条件在视频的哪一帧首次满足？与动作帧的偏差是否在容忍窗口内？
 *  - 动作触发后，后置断言是否在预期时间窗口内通过？
 *  - 前置条件在动作帧之前从未满足 → 该规则不可执行，直接丢弃。
 *
 * 每条规则输出一个**可执行概率**（0~1）——不是模型自述，而是离线实测标定。
 * 低于 [REVIEW_THRESHOLD] 自动置 needsReview=true，在时间轴编辑器中高亮。
 * 全程本地、确定性、可单元测试，用户看到的「N 条待确认」有明确依据。
 *
 * ## 分层
 *
 * core 只消费 [FrameObs] 数据契约做**时序逻辑判断**；帧差能量 / 模板匹配分 /
 * OCR 文本由 app 层从视频帧算出后填入（core 不解码、不 OCR、不联网）。
 * 因此本类在任何 Android 版本、任何机型、零模型权重下都能跑。
 */
object ReplayFalsifier {

    /** 低于该概率 → 标记待人工确认。 */
    const val REVIEW_THRESHOLD = 0.75

    /**
     * 一帧的**可观测信号**（app 层产出，core 只消费）。
     *
     * 每条信号都是可选证据，缺席即视为「该判据不满足」，不抛异常——
     * 对应「任一通道失效不影响闭环」的不变式。
     */
    data class FrameObs(
        val tMs: Long,
        /** ROI 帧差能量（0~1，app 层归一化）。DELTA_IMAGE 判据消费。 */
        val deltaEnergy: Double = 0.0,
        /** 模板匹配分（0~1，-1 = 未测）。APPEAR_IMAGE 判据消费。 */
        val matchScore: Double = -1.0,
        /** 本帧 OCR 出的文本集合（零文本 = 空）。TEXT_PRESENT 判据消费。 */
        val texts: Set<String> = emptySet(),
        /** 场景灰度签名哈希（用于后置 SCENE_CHANGED）。 */
        val sceneHash: Long = 0L,
    )

    /** 前置条件判据（对应规则契约 pre 的三种类型 + 无条件）。 */
    sealed class Precondition {
        /** 区域变化量门控：连续 [stableFrames] 帧 deltaEnergy ≥ [threshold]。 */
        data class DeltaImage(val threshold: Double, val stableFrames: Int = 1) : Precondition()

        /** 图像出现：matchScore ≥ [threshold]（兼容现有 APPEAR_IMAGE）。 */
        data class AppearImage(val threshold: Double) : Precondition()

        /** OCR 文本出现。 */
        data class TextPresent(val text: String) : Precondition()

        /** 无条件满足（前置可空的历史脚本，零回归）。 */
        data object Always : Precondition()
    }

    /** 后置断言判据（对应规则契约 post）。 */
    sealed class PostAssert {
        data class DeltaImage(val threshold: Double) : PostAssert()
        data class TextPresent(val text: String) : PostAssert()
        /** 场景签名相对动作帧发生变化。 */
        data object SceneChanged : PostAssert()
        /** 无后置断言（旧脚本零回归）：视为通过。 */
        data object None : PostAssert()
    }

    /** 一条待证伪的规则（离线对齐用绝对时间，绝对时间不进运行时规则）。 */
    data class Rule(
        val id: String,
        /** 动作在原视频中的时刻（毫秒）。 */
        val actionTMs: Long,
        val pre: Precondition = Precondition.Always,
        val post: PostAssert = PostAssert.None,
        /** 前置首次满足可早于动作帧的最大容忍窗口（毫秒）。 */
        val tolerateBeforeMs: Long = 600L,
        /** 后置断言窗口（毫秒）。 */
        val postWindowMs: Long = 2000L,
    )

    /** 一条规则的证伪结论。 */
    data class Verdict(
        val id: String,
        /** 前置是否在动作帧之前被满足过；false = 不可执行，应丢弃。 */
        val executable: Boolean,
        /** 可执行概率 0~1（离线标定）。 */
        val probability: Double,
        /** 是否待人工确认。 */
        val needsReview: Boolean,
        /** 前置首次满足时刻；null = 从未满足。 */
        val preFirstSatisfiedTMs: Long?,
        /** 后置断言通过时刻；null = 窗口内未通过 / 无断言。 */
        val postPassedTMs: Long?,
        /** 可读原因（编辑器高亮/诊断用）。 */
        val reason: String,
    )

    /**
     * 对一组候选触点离线证伪（视频导入期一次性跑完，纯 JVM）。
     *
     * 把每个候选编译成一条最小规则：
     *  - 前置 = 该候选时刻邻域的帧差能量突增（DELTA_IMAGE 低配代理，阈值 [deltaThreshold]）；
     *  - 后置 = 下一候选之前的场景是否变化（SCENE_CHANGED）。
     * 前置从未满足（候选落在平缓区）的规则判不可执行 → 调用方可据此丢弃。
     *
     * @param tapTimesMs 候选时刻（升序）
     * @return 与候选等长、同序的结论
     */
    fun falsifyTaps(
        frames: List<TapCandidateDetector.FrameSample>,
        tapTimesMs: List<Long>,
        deltaThreshold: Double = 0.005,
    ): List<Verdict> {
        val obs = observeFrames(frames)
        if (obs.isEmpty()) return tapTimesMs.map {
            Verdict("tap@$it", false, 0.0, true, null, null, "无帧观测")
        }
        val lastT = obs.last().tMs
        val rules = tapTimesMs.mapIndexed { idx, t ->
            val nextT = tapTimesMs.getOrNull(idx + 1) ?: (t + 2000L).coerceAtMost(lastT + 1L)
            Rule(
                id = "tap@$t",
                actionTMs = t,
                pre = Precondition.DeltaImage(deltaThreshold, stableFrames = 1),
                post = PostAssert.SceneChanged,
                tolerateBeforeMs = 300L,
                postWindowMs = (nextT - t).coerceIn(1L, 3000L),
            )
        }
        return falsify(obs, rules)
    }

    /**
     * 从视频帧序列构造离线观测（纯 JVM，app 层一行调用即可接线）。
     *
     * core 不解码、不 OCR、不联网：只从灰度帧算出两种零模型信号——
     *  - [FrameObs.deltaEnergy]：相邻帧变化像素占比（DELTA_IMAGE 判据的低配代理，
     *    app 若有更精确的 ROI 帧差可覆盖本值）；
     *  - [FrameObs.sceneHash]：稀疏采样的灰度签名（SCENE_CHANGED 后置判据）。
     * matchScore / texts 留空（-1 / 空集）——对应 C/D 通道缺席时该判据不满足，
     * 证伪器据此判「前置从未满足」，不阻断其余规则。
     */
    fun observeFrames(
        frames: List<TapCandidateDetector.FrameSample>,
        diffThreshold: Int = 26,
    ): List<FrameObs> {
        if (frames.isEmpty()) return emptyList()
        val out = ArrayList<FrameObs>(frames.size)
        for (i in frames.indices) {
            val f = frames[i]
            val energy = if (i == 0) 0.0 else {
                var changed = 0
                val prev = frames[i - 1].gray
                val step = max(1, f.gray.size / 4096) // 稀疏采样，控制开销
                var n = 0
                var p = 0
                while (p < f.gray.size) {
                    if (p < prev.size && abs(f.gray[p] - prev[p]) >= diffThreshold) changed++
                    n++
                    p += step
                }
                if (n == 0) 0.0 else changed.toDouble() / n
            }
            // 场景签名：每 64 像素取一个灰度，折叠成 Long。
            var hash = 1469598103934665603L
            var p = 0
            while (p < f.gray.size) {
                hash = (hash xor f.gray[p].toLong()) * 1099511628211L
                p += 64
            }
            out += FrameObs(f.tMs, deltaEnergy = energy, matchScore = -1.0, sceneHash = hash)
        }
        return out
    }

    /**
     * 逐条规则离线回放。纯函数，无副作用。
     *
     * @param frames 已按 tMs 升序排列的帧观测序列
     * @param rules 待证伪规则
     * @return 与 [rules] 等长、同序的结论
     */
    fun falsify(frames: List<FrameObs>, rules: List<Rule>): List<Verdict> {
        if (frames.isEmpty()) return rules.map {
            Verdict(it.id, false, 0.0, true, null, null, "无帧观测，无法回放")
        }
        val sorted = if (framesAreSorted(frames)) frames else frames.sortedBy { it.tMs }
        return rules.map { rule -> falsifyOne(sorted, rule) }
    }

    private fun framesAreSorted(frames: List<FrameObs>): Boolean {
        for (i in 1 until frames.size) if (frames[i].tMs < frames[i - 1].tMs) return false
        return true
    }

    private fun falsifyOne(frames: List<FrameObs>, rule: Rule): Verdict {
        // ① 前置条件首次满足帧（仅考虑 ≤ actionTMs 的帧——必须在动作之前满足）。
        val before = frames.filter { it.tMs <= rule.actionTMs }
        val firstPre = findFirstSatisfying(before, rule.pre)
        if (firstPre == null) {
            return Verdict(
                rule.id, executable = false, probability = 0.0, needsReview = true,
                preFirstSatisfiedTMs = null, postPassedTMs = null,
                reason = "前置条件在动作帧 ${rule.actionTMs}ms 之前从未满足 → 规则不可执行，丢弃",
            )
        }

        // ② 前置对齐：首次满足与动作帧偏差在容忍窗口内得满分，窗口外线性衰减。
        val lead = max(0L, rule.actionTMs - firstPre.tMs)
        val preScore = if (lead <= rule.tolerateBeforeMs) 1.0 else {
            val over = lead - rule.tolerateBeforeMs
            max(0.0, 1.0 - over.toDouble() / max(1L, rule.tolerateBeforeMs))
        }

        // ③ 后置断言：在 (action, action+window] 内找首个通过帧。
        val after = frames.filter { it.tMs > rule.actionTMs && it.tMs <= rule.actionTMs + rule.postWindowMs }
        val actionFrame = frames.lastOrNull { it.tMs <= rule.actionTMs }
        val firstPost = findFirstPost(after, rule.post, actionFrame)
        val postScore = when (rule.post) {
            PostAssert.None -> 1.0
            else -> if (firstPost != null) 1.0 else 0.0
        }

        // ④ 离线标定：前置对齐占 0.65、后置通过占 0.35。
        val probability = min(1.0, max(0.0, preScore * 0.65 + postScore * 0.35))
        val needsReview = probability < REVIEW_THRESHOLD || postScore < 1.0 && rule.post !is PostAssert.None
        val reason = buildString {
            append("前置首次满足 ${firstPre.tMs}ms（提前 ${lead}ms）")
            append(if (preScore >= 1.0) "在容忍窗口内" else "超出容忍窗口，前置分 ${String.format(Locale.US, "%.2f", preScore)}")
            append("；")
            append(if (rule.post is PostAssert.None) "无后置断言" else
                if (firstPost != null) "后置在 ${firstPost.tMs}ms 通过" else "后置在 ${rule.postWindowMs}ms 窗口内未通过")
            append("；可执行概率 ${String.format(Locale.US, "%.2f", probability)}")
            if (needsReview) append(" → 待人工确认")
        }
        return Verdict(
            rule.id, executable = true, probability = probability, needsReview = needsReview,
            preFirstSatisfiedTMs = firstPre.tMs, postPassedTMs = firstPost?.tMs, reason = reason,
        )
    }

    /** 找首个满足前置的帧。DeltaImage 要求连续 N 帧达标。 */
    private fun findFirstSatisfying(frames: List<FrameObs>, pre: Precondition): FrameObs? = when (pre) {
        Precondition.Always -> frames.firstOrNull()
        is Precondition.AppearImage -> frames.firstOrNull { it.matchScore >= pre.threshold }
        is Precondition.TextPresent -> frames.firstOrNull { pre.text in it.texts }
        is Precondition.DeltaImage -> {
            val n = max(1, pre.stableFrames)
            var run = 0
            var start: FrameObs? = null
            for (f in frames) {
                if (f.deltaEnergy >= pre.threshold) {
                    if (run == 0) start = f
                    run++
                    if (run >= n) return start
                } else {
                    run = 0
                    start = null
                }
            }
            null
        }
    }

    private fun findFirstPost(
        frames: List<FrameObs>, post: PostAssert, actionFrame: FrameObs?,
    ): FrameObs? = when (post) {
        PostAssert.None -> frames.firstOrNull()
        is PostAssert.DeltaImage -> frames.firstOrNull { it.deltaEnergy >= post.threshold }
        is PostAssert.TextPresent -> frames.firstOrNull { post.text in it.texts }
        PostAssert.SceneChanged -> {
            val base = actionFrame?.sceneHash
            frames.firstOrNull { it.sceneHash != base }
        }
    }
}

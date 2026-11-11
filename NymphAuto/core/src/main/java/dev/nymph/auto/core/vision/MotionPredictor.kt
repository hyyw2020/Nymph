package dev.nymph.auto.core.vision

import kotlin.math.hypot
import kotlin.math.min

/**
 * 运动外推预测器（v2.7.0，纯 JVM 可测）。
 *
 * ## 要解决什么
 *
 * 「跟不上高速移动的目标」有两个来源：采样间隔太长（见 [RecognitionTuning]）
 * 和**每一次都从全屏零先验重新找**。人眼跟踪移动物体靠的是速度外推，
 * 这里做同一件事：记录最近若干次命中的 (x, y, t)，估计速度向量，把下一帧
 * 的搜索起点外推到预测位置，并只在预测点周围开一个窄 ROI。
 *
 * 收益不是「1ms 找坐标」（物理不可能，见 ADR），而是：
 *  1. 目标移动时**不必等它重新进入全屏匹配窗口**；
 *  2. 窄 ROI 把 NCC 计算量从全屏降到约 (r/50)²，单帧耗时下降，
 *     等效于把「采样→命中」的闭环延迟压低；
 *  3. 预测失准时 ROI 会随置信度自动放大，最坏退回近全屏，不会漏找。
 *
 * 线程约束：仅在运行协程内单线程调用，故不加锁（无障碍回调不做重活）。
 */
class MotionPredictor(
    private val maxSamples: Int = 6,
    /** 速度上限（px/ms），抑制误匹配导致的瞬移把预测带飞。 */
    private val maxSpeedPxPerMs: Double = 3.0,
    /** 最多外推多久；超过则置信度归零，交回全屏搜索。 */
    private val maxExtrapolateMs: Long = 250L,
    private val minConfidence: Double = 0.15,
) {
    data class Sample(val x: Double, val y: Double, val tMs: Long)

    data class Prediction(
        val x: Double,
        val y: Double,
        val vx: Double,
        val vy: Double,
        val confidence: Double,
        val staleMs: Long,
    )

    private val samples = ArrayDeque<Sample>()

    val sampleCount: Int get() = samples.size

    /** 记录一次命中。时间戳必须严格递增；乱序/重复样本直接丢弃。 */
    fun observe(x: Double, y: Double, tMs: Long) {
        val last = samples.lastOrNull()
        if (last != null && tMs <= last.tMs) return
        samples.addLast(Sample(x, y, tMs))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    fun reset() {
        samples.clear()
    }

    /**
     * 外推到 [atMs] 时刻的位置。样本不足 2 个或过于陈旧时返回 null
     * （调用方应退回全屏/原 ROI，绝不硬用一个不可信的预测）。
     */
    fun predict(atMs: Long): Prediction? {
        if (samples.size < 2) return null
        val a = samples[samples.size - 2]
        val b = samples.last()
        val dt = (b.tMs - a.tMs).toDouble()
        if (dt <= 0.0) return null

        var vx = (b.x - a.x) / dt
        var vy = (b.y - a.y) / dt
        // 用更多样本做一次平滑：抑制单帧抖动造成的速度尖峰
        if (samples.size >= 3) {
            val c = samples[samples.size - 3]
            val dt2 = (b.tMs - c.tMs).toDouble()
            if (dt2 > 0.0) {
                vx = (vx + (b.x - c.x) / dt2) / 2.0
                vy = (vy + (b.y - c.y) / dt2) / 2.0
            }
        }
        val speed = hypot(vx, vy)
        if (speed > maxSpeedPxPerMs) {
            val k = maxSpeedPxPerMs / speed
            vx *= k
            vy *= k
        }

        val stale = (atMs - b.tMs).coerceAtLeast(0L)
        var conf = 1.0 - stale.toDouble() / (maxExtrapolateMs * 2.0)
        conf *= min(1.0, samples.size / 4.0)
        if (conf < minConfidence) return null

        val lead = stale.coerceAtMost(maxExtrapolateMs)
        return Prediction(
            x = b.x + vx * lead,
            y = b.y + vy * lead,
            vx = vx,
            vy = vy,
            confidence = conf.coerceIn(0.0, 1.0),
            staleMs = stale,
        )
    }

    /**
     * 以预测点为中心的窄 ROI（百分比坐标，可直接喂给 ImageFinder.find）。
     * 半径随速度与置信度自适应：越快、越不确定，框越大；下限 4%、上限 45%。
     */
    fun predictRoi(
        atMs: Long,
        screenW: Int,
        screenH: Int,
        baseRadiusPct: Double = 12.0,
    ): ImageFinder.RegionPct? {
        if (screenW <= 0 || screenH <= 0) return null
        val p = predict(atMs) ?: return null
        val leadPx = hypot(p.vx, p.vy) * p.staleMs.coerceAtMost(maxExtrapolateMs)
        val r = (baseRadiusPct + leadPx / screenW * 100.0 * 1.5)
            .div(0.5 + p.confidence * 0.5)
            .coerceIn(4.0, 45.0)
        val cx = (100.0 * p.x / screenW).coerceIn(0.0, 100.0)
        val cy = (100.0 * p.y / screenH).coerceIn(0.0, 100.0)
        return ImageFinder.RegionPct(
            left = (cx - r).coerceIn(0.0, 100.0),
            top = (cy - r).coerceIn(0.0, 100.0),
            right = (cx + r).coerceIn(0.0, 100.0),
            bottom = (cy + r).coerceIn(0.0, 100.0),
        )
    }
}

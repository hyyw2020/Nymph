// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.visual

/**
 * 动作可视化的纯状态机（纯 JVM，可单测，不引用 Android、不读系统时间）。
 *
 * 职责：
 *  - 维护当前活跃 cue（有上限，超出丢弃最老的）；
 *  - 按调用方传入的 nowMs 计算每个 cue 的进度（0..1），到期即移除；
 *  - 裁剪多指到前 N 指、裁剪轨迹到最近 N 点，过滤非法坐标；
 *  - 计算当前所有活跃元素的**一个合并像素包围盒**，供 app 层换算成
 *    ImageMask 抑制矩形，避免特效污染找图/找色。
 *
 * 时间语义：所有时间都是 elapsedRealtime 毫秒，由调用方统一传入。
 * 进度按「真实经过时间 / 时长」计算，因此掉帧只会让帧变少、不会让动效
 * 变慢或变快；无活跃元素时 [frame] 返回空，app 层据此彻底停帧。
 */
class VisualCueReducer(
    private var config: VisualConfig = VisualConfig(),
) {

    /** 当前帧的可渲染元素快照（不可变，渲染与抑制共用同一份，保证一致）。 */
    data class ActiveVisual(
        val cue: VisualCue,
        val progress: Float,
        val spec: VisualSpec,
        /** 已裁剪/清洗后的点位（成对 x,y）。 */
        val points: FloatArray,
    )

    /**
     * @param boundsPx 合并包围盒 [left, top, right, bottom]（像素）；空帧为 null。
     *        app 层在此基础上外扩 dp 边距后再转百分比矩形。
     */
    data class VisualFrame(
        val elements: List<ActiveVisual>,
        val boundsPx: FloatArray?,
    ) {
        val isEmpty: Boolean get() = elements.isEmpty()
    }

    private val active = ArrayList<Entry>(config.maxCues.coerceAtLeast(1))

    private data class Entry(val cue: VisualCue, val points: FloatArray)

    fun updateConfig(next: VisualConfig) {
        config = next
    }

    fun config(): VisualConfig = config

    fun clear() {
        active.clear()
    }

    fun size(): Int = active.size

    /** 压入一个 cue；非法点位直接丢弃，绝不把脏数据带进渲染。 */
    fun add(cue: VisualCue) {
        val pts = sanitize(cue.kind, cue.pointsPx, config)
        if (pts.size < 2) return
        val cap = config.maxCues.coerceAtLeast(1)
        while (active.size >= cap) active.removeAt(0)
        active.add(Entry(cue, pts))
    }

    /**
     * 求当前帧：剔除已结束的 cue，返回可渲染元素与合并包围盒。
     * 调用方用同一 nowMs 同时驱动渲染和抑制矩形，二者天然一致。
     */
    fun frame(nowMs: Long): VisualFrame {
        if (active.isEmpty()) return VisualFrame(emptyList(), null)
        val it = active.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (isDone(e.cue, nowMs)) it.remove()
        }
        if (active.isEmpty()) return VisualFrame(emptyList(), null)

        val out = ArrayList<ActiveVisual>(active.size)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (e in active) {
            val p = progressOf(e.cue, nowMs)
            out += ActiveVisual(e.cue, p, VisualConfig.specFor(e.cue.kind), e.points)
            val pts = e.points
            var i = 0
            while (i + 1 < pts.size) {
                val x = pts[i]; val y = pts[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += 2
            }
        }
        val bounds = if (minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
            floatArrayOf(minX, minY, maxX, maxY)
        } else null
        return VisualFrame(out, bounds)
    }

    private fun isDone(cue: VisualCue, nowMs: Long): Boolean {
        if (cue.durationMs <= 0L) return true
        return nowMs - cue.createdAtMs >= cue.durationMs
    }

    private fun progressOf(cue: VisualCue, nowMs: Long): Float {
        if (cue.durationMs <= 0L) return 1f
        val p = (nowMs - cue.createdAtMs).toFloat() / cue.durationMs.toFloat()
        return p.coerceIn(0f, 1f)
    }

    /**
     * 按手势类别裁剪点数并清洗非法值（NaN/Inf 直接丢该点）。
     * 约定见 VisualCue.pointsPx 注释：MULTI_TOUCH 每 2 个 float 一指，
     * SWIPE 每 2 个 float 一个轨迹点，其余为单点或少量点位。
     */
    private fun sanitize(kind: VisualKind, src: FloatArray, cfg: VisualConfig): FloatArray {
        val maxPoints = when (kind) {
            VisualKind.MULTI_TOUCH -> cfg.maxFingers.coerceAtLeast(1)
            VisualKind.SWIPE -> cfg.maxTrailPoints.coerceAtLeast(2)
            else -> Int.MAX_VALUE / 2
        }
        val pairs = src.size / 2
        val keep = minOf(pairs, maxPoints)
        val out = ArrayList<Float>(keep * 2)
        var i = 0
        var taken = 0
        while (i + 1 < src.size && taken < keep) {
            val x = src[i]; val y = src[i + 1]
            if (x.isFinite() && y.isFinite()) {
                out += x; out += y
                taken++
            }
            i += 2
        }
        return out.toFloatArray()
    }
}

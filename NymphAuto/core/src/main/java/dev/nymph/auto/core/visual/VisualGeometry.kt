// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.visual

import kotlin.math.sqrt

/**
 * 轨迹采样工具（纯 JVM，可单测）：把「起终点 / 二次贝塞尔 / 折线路径」
 * 统一采样成成对 x,y 的 FloatArray，交给渲染层画渐隐轨迹带。
 *
 * 为什么不直接用引擎的 Path：GestureEngine 的 Path 是 android.graphics.Path，
 * core 层不可引用；而且可视化只需要几何点，不需要复用手势对象。这里按
 * arc-length 近似均匀采样，避免直线插值在长滑动上出现「中间一段没点」，
 * 也让尾端渐隐在视觉上平滑。定长输出，绝不无限增长。
 */
object VisualGeometry {

    /** 线性插值：从 (x1,y1) 到 (x2,y2) 均匀采样 [n] 个点（含首尾）。 */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, n: Int = 12): FloatArray {
        val count = n.coerceIn(2, 32)
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            val t = i.toFloat() / (count - 1)
            out[i * 2] = x1 + (x2 - x1) * t
            out[i * 2 + 1] = y1 + (y2 - y1) * t
        }
        return out
    }

    /**
     * 二次贝塞尔 (p0 → 控制点 c → p1) 采样 [n] 个点。
     * 与 GestureEngine.planCurve 的控制点语义一致，保证特效轨迹贴合真实手势。
     */
    fun quad(
        x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, n: Int = 16,
    ): FloatArray {
        val count = n.coerceIn(3, 32)
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            val t = i.toFloat() / (count - 1)
            val u = 1f - t
            out[i * 2] = u * u * x0 + 2f * u * t * cx + t * t * x1
            out[i * 2 + 1] = u * u * y0 + 2f * u * t * cy + t * t * y1
        }
        return out
    }

    /**
     * 折线路径按累计弧长重采样为至多 [maxPoints] 个点，定点输出。
     * 输入为成对 x,y；点数不足时线性补齐，避免只有 1 点时无法成带。
     */
    fun resample(src: FloatArray, maxPoints: Int = 24): FloatArray {
        val pairs = src.size / 2
        if (pairs < 2) {
            val x = if (src.size >= 2) src[0] else 0f
            val y = if (src.size >= 2) src[1] else 0f
            return line(x, y, x, y, 2)
        }
        val cap = maxPoints.coerceIn(2, 32)
        // 累计弧长
        val cum = FloatArray(pairs)
        var total = 0f
        for (i in 1 until pairs) {
            val dx = src[i * 2] - src[(i - 1) * 2]
            val dy = src[i * 2 + 1] - src[(i - 1) * 2 + 1]
            total += sqrt(dx * dx + dy * dy)
            cum[i] = total
        }
        if (total <= 0.0001f) {
            // 所有点重合：退化为原地两点
            return line(src[0], src[1], src[0], src[1], 2)
        }
        val out = FloatArray(cap * 2)
        var seg = 1
        for (k in 0 until cap) {
            val target = total * k / (cap - 1)
            while (seg < pairs - 1 && cum[seg] < target) seg++
            val segStart = cum[seg - 1]
            val segLen = cum[seg] - segStart
            val t = if (segLen > 0.0001f) (target - segStart) / segLen else 0f
            val ax = src[(seg - 1) * 2]; val ay = src[(seg - 1) * 2 + 1]
            val bx = src[seg * 2]; val by = src[seg * 2 + 1]
            out[k * 2] = ax + (bx - ax) * t
            out[k * 2 + 1] = ay + (by - ay) * t
        }
        return out
    }
}

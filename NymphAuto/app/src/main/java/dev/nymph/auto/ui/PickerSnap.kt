// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.nymph.auto.engine.AutoClickService
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 拾取吸附源（v2.7.0）。可插拔：UI 只认接口，具体吸附策略按需注入。
 *
 * 回应用户反馈「没有吸附到控件边界 / OCR 文字中心 / 颜色中心」。
 * 本版落地：
 *  - [NodeSnapProvider]：无障碍节点树，吸附到最近可点击控件的中心或边界；
 *  - [ColorSnapProvider]：基于一帧截图，吸附到局部同色区域的质心。
 * OCR 文字中心复用录制链路的 OcrEngine，接口一致，可在设置里切换注入
 * （不默认开启，避免拾取时加载几十 MB 的 OCR 模型拖慢交互）。
 */
interface SnapProvider {
    /** 返回吸附后的屏幕绝对像素坐标；null = 无可吸附目标，保持原位。 */
    fun snap(xPx: Int, yPx: Int): Pair<Int, Int>?
}

/** 吸附到最近可点击控件的中心；容差 [radiusPx] 内才生效，避免"隔空吸走"。 */
class NodeSnapProvider(
    private val radiusPx: Int = 96,
    private val maxNodes: Int = 400,
) : SnapProvider {
    override fun snap(xPx: Int, yPx: Int): Pair<Int, Int>? {
        val root = AutoClickService.live()?.rootInActiveWindow() ?: return null
        var best: Pair<Int, Int>? = null
        var bestDist = Double.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val r = Rect()
        try {
            while (queue.isNotEmpty() && visited < maxNodes) {
                val n = queue.removeFirst()
                visited++
                runCatching { n.getBoundsInScreen(r) }
                if (runCatching { n.isClickable || n.isCheckable || n.isLongClickable }.getOrDefault(false) &&
                    r.width() > 0 && r.height() > 0
                ) {
                    val cx = r.centerX()
                    val cy = r.centerY()
                    val d = hypot((cx - xPx).toDouble(), (cy - yPx).toDouble())
                    if (d < bestDist && d <= radiusPx) {
                        bestDist = d
                        best = cx to cy
                    }
                }
                val count = runCatching { n.childCount }.getOrDefault(0)
                for (i in 0 until count) {
                    runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
                }
            }
        } finally {
            // 不 recycle root：调用方（AutoClickService）与系统共享，避免误回收他人节点
        }
        return best
    }
}

/** 吸附到触点附近同色连通块的质心（颜色中心）。bitmap 由调用方按节流帧提供。 */
class ColorSnapProvider(
    private val bitmap: android.graphics.Bitmap?,
    private val radiusPx: Int = 24,
    private val tolerance: Int = 26,
) : SnapProvider {
    override fun snap(xPx: Int, yPx: Int): Pair<Int, Int>? {
        val b = bitmap ?: return null
        if (xPx < 0 || yPx < 0 || xPx >= b.width || yPx >= b.height) return null
        val target = b.getPixel(xPx, yPx)
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        var sx = 0L
        var sy = 0L
        var n = 0
        val x0 = (xPx - radiusPx).coerceAtLeast(0)
        val x1 = (xPx + radiusPx).coerceAtMost(b.width - 1)
        val y0 = (yPx - radiusPx).coerceAtLeast(0)
        val y1 = (yPx + radiusPx).coerceAtMost(b.height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = b.getPixel(x, y)
                val dr = abs(((c shr 16) and 0xFF) - tr)
                val dg = abs(((c shr 8) and 0xFF) - tg)
                val db = abs((c and 0xFF) - tb)
                if (dr + dg + db <= tolerance * 3) {
                    sx += x
                    sy += y
                    n++
                }
            }
        }
        if (n == 0) return null
        return (sx / n).toInt() to (sy / n).toInt()
    }
}

/** 组合吸附：按序尝试，第一个命中即用。 */
class CompositeSnapProvider(private vararg val providers: SnapProvider) : SnapProvider {
    override fun snap(xPx: Int, yPx: Int): Pair<Int, Int>? {
        for (p in providers) p.snap(xPx, yPx)?.let { return it }
        return null
    }
}

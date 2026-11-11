// SPDX-License-Identifier: MIT
package dev.nymph.auto.visual

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.nymph.auto.core.visual.VisualAtom
import dev.nymph.auto.core.visual.VisualCue
import dev.nymph.auto.core.visual.VisualCueReducer
import dev.nymph.auto.core.visual.VisualKind
import dev.nymph.auto.core.visual.VisualSpec

/**
 * 动作可视化的绘制 View：纯 Canvas 矢量，硬件加速由窗口 flag 提供。
 *
 * 硬约束：
 *  - 绝不 drawText（角标全用 [VectorGlyphs] 的矢量折线）；
 *  - 绝不 BlurMaskFilter / 模糊后处理（辉光靠「宽淡外描边 + 窄亮内描边」）；
 *  - Paint / Path / RectF 全部预分配，onDraw 内不 new 对象（无动画时彻底停帧，
 *    有动画时也只有 draw 调用，无 GC 抖动）；
 *  - 只画 reducer 给出的活跃元素，不读屏幕像素、不做任何页面/安全窗口判断。
 *
 * 配色：不确定底层是深色还是浅色（读屏幕像素会与找色抢截图、也没必要），
 * 因此用「霓虹青 + 洋红主色 + 深色对比外描边」的高对比双描边，在深浅背景
 * 上都清晰，不引入背景检测分支。克制模式禁用辉光/角标/箭头装饰。
 */
class VisualizerView(context: Context) : View(context) {

    private val den = resources.displayMetrics.density
    private fun dp(v: Float) = v * den

    // 复用画笔：fill=低透明填充，halo=宽淡外描边（辉光），line=窄亮内描边
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private var frame: VisualCueReducer.VisualFrame? = null

    // 主色：青 / 洋红；对比色：深色外描边（浅背景也看得清）
    private val cyan = Color.argb(255, 0x38, 0xE8, 0xD6)
    private val magenta = Color.argb(255, 0xFF, 0x5C, 0xB8)
    private val contrast = Color.argb(200, 0x0A, 0x14, 0x22)

    private val dotRadius = dp(5f)
    private val ringStroke = dp(1.8f)
    private val haloStroke = dp(5f)
    private val trailStroke = dp(2.2f)
    private val ringExpand = dp(30f)

    /** 由 Layer 在每帧调用：设置数据后 invalidate，绝不自己启动循环。 */
    fun submit(f: VisualCueReducer.VisualFrame) {
        frame = f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        if (f.isEmpty) return
        val restrained = currentRestrained
        for (e in f.elements) {
            val cue = e.cue
            val spec = e.spec
            val alpha = (255 * (1f - e.progress)).toInt().coerceIn(0, 255)
            val pts = e.points
            when (cue.kind) {
                VisualKind.TAP -> drawTap(canvas, cue, spec, e.progress, pts, alpha, restrained)
                VisualKind.LONG_PRESS -> drawLongPress(canvas, e.progress, pts, alpha, restrained)
                VisualKind.SWIPE -> drawSwipe(canvas, spec, e.progress, pts, alpha, restrained)
                VisualKind.MULTI_TOUCH -> drawMulti(canvas, spec, e.progress, pts, alpha, restrained)
                VisualKind.PREVIEW -> drawPreview(canvas, cue, e.progress, pts, alpha, restrained)
            }
        }
    }

    // 当前是否克制模式（由 Layer 每帧同步，避免 View 反向依赖配置存储）
    @Volatile
    var currentRestrained: Boolean = false

    // ------------------------------------------------------------ 单击

    private fun drawTap(
        c: Canvas, cue: VisualCue, spec: VisualSpec, p: Float, pts: FloatArray,
        alpha: Int, restrained: Boolean,
    ) {
        if (pts.size < 2) return
        val x = pts[0]; val y = pts[1]
        // 中心圆点：低透明填充 + 细描边
        fillPaint.color = withAlpha(cyan, (alpha * 0.12f).toInt())
        c.drawCircle(x, y, dotRadius, fillPaint)
        drawRing(c, x, y, dotRadius, p, alpha, magenta, restrained, cue, spec, ringIndex = 0)
        // 双层环错时 60ms（第二层用偏移后的进度）
        if (spec.ringCount > 1 && !restrained) {
            val stagger = spec.ringStaggerMs.toFloat() / cue.durationMs.coerceAtLeast(1)
            val p2 = (p - stagger).coerceAtLeast(0f) / (1f - stagger).coerceAtLeast(0.01f)
            if (p2 > 0f) drawRing(c, x, y, dotRadius, p2, (alpha * 0.7f).toInt(), cyan, restrained, cue, spec, 1)
        }
    }

    private fun drawRing(
        c: Canvas, x: Float, y: Float, base: Float, p: Float, alpha: Int,
        color: Int, restrained: Boolean, @Suppress("UNUSED_PARAMETER") cue: VisualCue,
        @Suppress("UNUSED_PARAMETER") spec: VisualSpec, ringIndex: Int,
    ) {
        val r = base + ringExpand * p
        if (!restrained) {
            haloPaint.color = withAlpha(color, (alpha * 0.22f).toInt())
            haloPaint.strokeWidth = haloStroke
            c.drawCircle(x, y, r, haloPaint)
        }
        linePaint.color = color
        linePaint.alpha = alpha
        linePaint.strokeWidth = ringStroke
        linePaint.alpha = alpha
        c.drawCircle(x, y, r, linePaint)
        // 深色对比外描边（浅背景可读），仅非克制模式
        if (!restrained && ringIndex == 0) {
            linePaint.color = contrast
            linePaint.alpha = (alpha * 0.5f).toInt()
            linePaint.strokeWidth = ringStroke * 0.6f
            c.drawCircle(x, y, r + ringStroke, linePaint)
        }
    }

    // ------------------------------------------------------------ 长按

    private fun drawLongPress(
        c: Canvas, p: Float, pts: FloatArray, alpha: Int, restrained: Boolean,
    ) {
        if (pts.size < 2) return
        val x = pts[0]; val y = pts[1]
        fillPaint.color = withAlpha(cyan, (alpha * 0.12f).toInt())
        c.drawCircle(x, y, dotRadius, fillPaint)
        // 呼吸圆点（半径随进度轻微起伏）
        linePaint.color = magenta
        linePaint.alpha = alpha
        linePaint.strokeWidth = ringStroke
        c.drawCircle(x, y, dotRadius, linePaint)
        // 进度环：从 -90° 起扫过 360*p
        val rr = dotRadius + dp(10f)
        rect.set(x - rr, y - rr, x + rr, y + rr)
        if (!restrained) {
            arcPaint.color = withAlpha(cyan, (alpha * 0.25f).toInt())
            arcPaint.strokeWidth = haloStroke
            c.drawArc(rect, -90f, 360f * p, false, arcPaint)
        }
        arcPaint.color = cyan
        arcPaint.alpha = alpha
        arcPaint.strokeWidth = ringStroke + dp(0.6f)
        c.drawArc(rect, -90f, 360f * p, false, arcPaint)
        // 不满格时补一圈低饱和底环，让进度可读
        arcPaint.color = contrast
        arcPaint.alpha = (alpha * 0.35f).toInt()
        arcPaint.strokeWidth = ringStroke * 0.6f
        c.drawArc(rect, -90f + 360f * p, 360f * (1f - p), false, arcPaint)
    }

    // ------------------------------------------------------------ 滑动 / 曲线 / 回放

    private fun drawSwipe(
        c: Canvas, spec: VisualSpec, p: Float, pts: FloatArray,
        alpha: Int, restrained: Boolean,
    ) {
        val n = pts.size / 2
        if (n < 2) return
        // 轨迹带：只画到 progress 对应的进度，尾端渐隐（分段 alpha/宽度）
        val visible = (n * p).toInt().coerceIn(2, n)
        linePaint.strokeCap = Paint.Cap.ROUND
        for (i in 1 until visible) {
            val seg = i.toFloat() / visible
            val a = (alpha * (0.25f + 0.75f * seg)).toInt()
            val w = trailStroke * (0.5f + 0.5f * seg)
            val x1 = pts[(i - 1) * 2]; val y1 = pts[(i - 1) * 2 + 1]
            val x2 = pts[i * 2]; val y2 = pts[i * 2 + 1]
            if (!restrained) {
                haloPaint.color = withAlpha(cyan, (a * 0.2f).toInt())
                haloPaint.strokeWidth = w + dp(3f)
                c.drawLine(x1, y1, x2, y2, haloPaint)
            }
            linePaint.color = cyan
            linePaint.alpha = a
            linePaint.strokeWidth = w
            c.drawLine(x1, y1, x2, y2, linePaint)
        }
        // 起点圆点
        fillPaint.color = withAlpha(cyan, (alpha * 0.12f).toInt())
        c.drawCircle(pts[0], pts[1], dotRadius, fillPaint)
        // 方向箭头（在轨迹头部）
        if (VisualAtom.ARROW in spec.atoms && !restrained) {
            val hx = pts[(visible - 1) * 2]; val hy = pts[(visible - 1) * 2 + 1]
            val px = pts[(visible - 2) * 2]; val py = pts[(visible - 2) * 2 + 1]
            drawArrow(c, px, py, hx, hy, magenta, alpha)
        }
    }

    private fun drawArrow(
        c: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, color: Int, alpha: Int,
    ) {
        val ang = kotlin.math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        val size = dp(9f)
        val a1 = ang + 2.5; val a2 = ang - 2.5
        path.reset()
        path.moveTo(toX, toY)
        path.lineTo((toX + kotlin.math.cos(a1) * size).toFloat(), (toY + kotlin.math.sin(a1) * size).toFloat())
        path.lineTo((toX + kotlin.math.cos(a2) * size).toFloat(), (toY + kotlin.math.sin(a2) * size).toFloat())
        path.close()
        fillPaint.color = color
        fillPaint.alpha = alpha
        c.drawPath(path, fillPaint)
    }

    // ------------------------------------------------------------ 多指

    private fun drawMulti(
        c: Canvas, spec: VisualSpec, p: Float, pts: FloatArray,
        alpha: Int, restrained: Boolean,
    ) {
        val n = pts.size / 2
        if (n < 1) return
        // 同色相不同明度 + 数字角标，不给每指换色系
        for (i in 0 until n) {
            val x = pts[i * 2]; val y = pts[i * 2 + 1]
            val light = 1f - (i.toFloat() / n.coerceAtLeast(1)) * 0.45f
            val color = withAlpha(cyan, (255 * light).toInt())
            fillPaint.color = withAlpha(color, (alpha * 0.12f).toInt())
            c.drawCircle(x, y, dotRadius, fillPaint)
            linePaint.color = color
            linePaint.alpha = alpha
            linePaint.strokeWidth = ringStroke
            c.drawCircle(x, y, dotRadius, linePaint)
            if (!restrained && VisualAtom.BADGE in spec.atoms) {
                drawBadge(c, (i + 1).toString(), x + dotRadius + dp(4f), y - dp(10f), magenta, alpha)
            }
        }
        // 中点圆环（外接轮廓，不做两两连线，避免蜘蛛网）
        if (n >= 2 && !restrained) {
            var cx = 0f; var cy = 0f
            for (i in 0 until n) { cx += pts[i * 2]; cy += pts[i * 2 + 1] }
            cx /= n; cy /= n
            var rad = 0f
            for (i in 0 until n) {
                val dx = pts[i * 2] - cx; val dy = pts[i * 2 + 1] - cy
                rad = maxOf(rad, kotlin.math.sqrt(dx * dx + dy * dy))
            }
            linePaint.color = withAlpha(magenta, (alpha * 0.6f).toInt())
            linePaint.alpha = (alpha * 0.6f).toInt()
            linePaint.strokeWidth = ringStroke
            c.drawCircle(cx, cy, rad * (0.9f + 0.1f * p), linePaint)
        }
    }

    // ------------------------------------------------------------ 落点预告

    private fun drawPreview(
        c: Canvas, cue: VisualCue, p: Float, pts: FloatArray, alpha: Int, restrained: Boolean,
    ) {
        if (pts.size < 2) return
        val x = pts[0]; val y = pts[1]
        val base = dp(14f)
        // 虚线细环（低饱和半透明）
        dashPaint.color = withAlpha(cyan, (alpha * 0.55f).toInt())
        dashPaint.strokeWidth = ringStroke * 0.8f
        c.drawCircle(x, y, base, dashPaint)
        // 倒计时环：从大收拢到小，同时按进度扫过
        val rr = base * (1f + 0.6f * (1f - p))
        rect.set(x - rr, y - rr, x + rr, y + rr)
        arcPaint.color = magenta
        arcPaint.alpha = (alpha * 0.8f).toInt()
        arcPaint.strokeWidth = ringStroke
        c.drawArc(rect, -90f, 360f * p, false, arcPaint)
        // 十字准星
        val t = dp(9f)
        linePaint.color = withAlpha(cyan, (alpha * 0.5f).toInt())
        linePaint.strokeWidth = ringStroke * 0.7f
        c.drawLine(x - t, y, x + t, y, linePaint)
        c.drawLine(x, y - t, x, y + t, linePaint)
        // 步骤角标（预告期间叠加 3/12）
        if (!restrained && cue.step > 0) {
            val label = if (cue.totalSteps > 0) "${cue.step}/${cue.totalSteps}" else cue.step.toString()
            drawBadge(c, label, x + base + dp(3f), y - base, cyan, alpha)
        }
    }

    // ------------------------------------------------------------ 矢量角标

    /** 用 [VectorGlyphs] 逐字画折线，绝不 drawText。原点为字串左上角。 */
    private fun drawBadge(
        c: Canvas, text: String, ox: Float, oy: Float, color: Int, alpha: Int,
    ) {
        val cw = dp(7f)   // 单字宽
        val ch = dp(11f)  // 单字高
        var cursor = ox
        linePaint.color = color
        linePaint.alpha = alpha
        linePaint.strokeWidth = dp(1.6f)
        linePaint.strokeCap = Paint.Cap.ROUND
        for (ch0 in text) {
            val strokes = VectorGlyphs.strokes(ch0)
            for (s in strokes) {
                var i = 0
                while (i + 3 < s.size) {
                    c.drawLine(
                        cursor + s[i] * cw, oy + s[i + 1] * ch,
                        cursor + s[i + 2] * cw, oy + s[i + 3] * ch,
                        linePaint,
                    )
                    i += 2
                }
            }
            cursor += VectorGlyphs.advance(ch0) * cw
        }
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

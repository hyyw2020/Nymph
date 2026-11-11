// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 拾取模式：点拾取 / 框选区域（找图 ROI、找色范围、模板截取）。 */
enum class PickerMode { POINT, REGION }

/** 手柄工具条动作。 */
internal enum class HandleAction {
    CONFIRM, CANCEL, MODE, MAGNIFIER, SNAP, RULER,
    UP, DOWN, LEFT, RIGHT,
}

/**
 * 全屏**绘制层**（v2.7.0 非模态核心）。
 *
 * 与 v2.6.0 的模态层本质区别：
 *  - 所在窗口带 `FLAG_NOT_TOUCHABLE`，**不消费任何触摸**，点击全部穿透到底层 APP，
 *    因此用户可以「一边拾取坐标，一边操作指针外的任意区域」；
 *  - 不再铺全屏暗色蒙版（`dimPaint` 已移除），底层内容保持原样可见；
 *  - 标尺默认关闭，需要时从手柄展开工具条开启，避免遮挡。
 *
 * 准星位置由可拖动的独立手柄窗口驱动，本 View 只负责画。
 */
internal class PickerOverlayView(context: Context) : View(context) {

    var crossX = 0f
    var crossY = 0f
    var mode = PickerMode.POINT
    var regionA: FloatArray? = null
    var regionB: FloatArray? = null
    var rulersEnabled = false
    var magnifierEnabled = false
    var magnifierBitmap: Bitmap? = null
    var snapOn = false
    var screenW = 1
    var screenH = 1

    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99F87171.toInt(); strokeWidth = 1.5f
    }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFB7185.toInt(); strokeWidth = 2.5f
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFB7185.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FB7185.toInt(); style = Paint.Style.FILL
    }
    private val rulerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt(); strokeWidth = 1f
    }
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCE5E7EB.toInt(); textSize = 9f * d
    }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6111827.toInt() }
    private val tagStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FB7185.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF9FAFB.toInt(); textSize = 11f * d
    }
    private val tagSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt(); textSize = 9.5f * d
    }
    private val magBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF20B1220.toInt() }
    private val magStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFB7185.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val magPixel = Paint().apply { isFilterBitmap = false }
    private val magGrid = Paint().apply {
        color = 0x33000000.toInt(); strokeWidth = 1f
    }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (rulersEnabled) drawRulers(c, w, h)
        // 十字定位线用极细半透明，不遮底层
        c.drawLine(0f, crossY, w, crossY, line)
        c.drawLine(crossX, 0f, crossX, h, line)

        if (mode == PickerMode.REGION) drawRegion(c, w, h)
        else drawCross(c)

        drawReadout(c, w, h)
        if (magnifierEnabled) drawMagnifier(c, w, h)
    }

    private fun drawCross(c: Canvas) {
        c.drawCircle(crossX, crossY, 16f * d, fill)
        c.drawCircle(crossX, crossY, 11f * d, ring)
        c.drawCircle(crossX, crossY, 2.2f * d, strong)
        // 中心像素刻度：四向短线，便于像素级对齐
        val g = 4f * d
        val l = 9f * d
        c.drawLine(crossX - g - l, crossY, crossX - g, crossY, strong)
        c.drawLine(crossX + g, crossY, crossX + g + l, crossY, strong)
        c.drawLine(crossX, crossY - g - l, crossX, crossY - g, strong)
        c.drawLine(crossX, crossY + g, crossX, crossY + g + l, strong)
    }

    private fun drawRegion(c: Canvas, w: Float, h: Float) {
        val a = regionA ?: floatArrayOf(crossX, crossY)
        val b = regionB ?: floatArrayOf(crossX, crossY)
        val l = min(a[0], b[0])
        val t = min(a[1], b[1])
        val r = max(a[0], b[0])
        val bo = max(a[1], b[1])
        box.set(l, t, r, bo)
        c.drawRect(box, fill)
        c.drawRect(box, strong)
        val corner = 7f * d
        for (cx in listOf(l, r)) for (cy in listOf(t, bo)) {
            c.drawCircle(cx, cy, corner, tagBg)
            c.drawCircle(cx, cy, corner, ring)
        }
        val rw = (r - l).roundToInt()
        val rh = (bo - t).roundToInt()
        val label = "${rw}×${rh}px"
        val bw = tagText.measureText(label) + 16f * d
        val bx = ((l + r) / 2f - bw / 2f).coerceIn(2f, w - bw - 2f)
        val by = (t - 26f * d).coerceIn(2f, h - 24f * d)
        box.set(bx, by, bx + bw, by + 22f * d)
        c.drawRoundRect(box, 5f * d, 5f * d, tagBg)
        c.drawText(label, bx + 8f * d, by + 15f * d, tagText)
    }

    private fun drawReadout(c: Canvas, w: Float, h: Float) {
        val px = crossX.roundToInt()
        val py = crossY.roundToInt()
        val line1 = "X $px  Y $py"
        val xp = if (screenW > 0) 100.0 * px / screenW else 0.0
        val yp = if (screenH > 0) 100.0 * py / screenH else 0.0
        val line2 = "%.4f%%  %.4f%%".format(xp, yp) + if (snapOn) "  ⌖" else ""
        val bw = max(tagText.measureText(line1), tagSub.measureText(line2)) + 16f * d
        val bh = 40f * d
        var left = crossX + 20f * d
        if (left + bw > w - 2f) left = crossX - bw - 20f * d
        left = left.coerceIn(2f, max(2f, w - bw - 2f))
        var top = crossY + 20f * d
        if (top + bh > h - 2f) top = crossY - bh - 20f * d
        top = top.coerceIn(2f, max(2f, h - bh - 2f))
        box.set(left, top, left + bw, top + bh)
        c.drawRoundRect(box, 6f * d, 6f * d, tagBg)
        c.drawRoundRect(box, 6f * d, 6f * d, tagStroke)
        c.drawText(line1, left + 8f * d, top + 17f * d, tagText)
        c.drawText(line2, left + 8f * d, top + 32f * d, tagSub)
    }

    /** 局部像素放大镜：以准星为中心取 ±10px，8 倍最近邻放大（不做平滑）。 */
    private fun drawMagnifier(c: Canvas, w: Float, h: Float) {
        val size = 96f * d
        var left = crossX - size / 2f
        var top = crossY - size - 30f * d
        if (top < 2f) top = crossY + 30f * d
        left = left.coerceIn(2f, max(2f, w - size - 2f))
        top = top.coerceIn(2f, max(2f, h - size - 2f))
        box.set(left, top, left + size, top + size)
        c.drawRoundRect(box, 8f * d, 8f * d, magBg)
        val b = magnifierBitmap
        if (b != null) {
            val srcR = 10
            val sx = (crossX.roundToInt() - srcR).coerceIn(0, max(0, b.width - 1))
            val sy = (crossY.roundToInt() - srcR).coerceIn(0, max(0, b.height - 1))
            val sx2 = (sx + srcR * 2).coerceAtMost(b.width)
            val sy2 = (sy + srcR * 2).coerceAtMost(b.height)
            if (sx2 > sx && sy2 > sy) {
                val src = Rect(sx, sy, sx2, sy2)
                val inner = 6f * d
                val dst = RectF(left + inner, top + inner, left + size - inner, top + size - inner)
                c.drawBitmap(b, src, dst, magPixel)
                // 像素网格：每格对应 1 源像素
                val step = dst.width() / (src.width().coerceAtLeast(1))
                var gx = dst.left
                while (gx <= dst.right) { c.drawLine(gx, dst.top, gx, dst.bottom, magGrid); gx += step }
                var gy = dst.top
                while (gy <= dst.bottom) { c.drawLine(dst.left, gy, dst.right, gy, magGrid); gy += step }
                // 中心十字：精确指示当前像素
                val mx = (dst.left + dst.right) / 2f
                val my = (dst.top + dst.bottom) / 2f
                c.drawLine(mx, dst.top, mx, dst.bottom, strong)
                c.drawLine(dst.left, my, dst.right, my, strong)
            }
        } else {
            c.drawText("放大镜：截图不可用", left + 8f * d, top + size / 2f, tagSub)
        }
        c.drawRoundRect(box, 8f * d, 8f * d, magStroke)
    }

    private fun drawRulers(c: Canvas, w: Float, h: Float) {
        val len = 22f * d
        for (pct in 0..100 step 5) {
            val x = w * pct / 100f
            val l = if (pct % 10 == 0) len else len * 0.55f
            c.drawLine(x, 0f, x, l, rulerLine)
            if (pct % 10 == 0) c.drawText(pct.toString(), (x + 2f).coerceAtMost(w - 20f), len + 10f * d, rulerText)
        }
        for (pct in 0..100 step 5) {
            val y = h * pct / 100f
            val l = if (pct % 10 == 0) len else len * 0.55f
            c.drawLine(0f, y, l, y, rulerLine)
        }
    }
}

/**
 * 极小可拖动控制手柄（v2.7.0，替代 v2.6.0 的「傻大粗」全屏按钮组）。
 *
 * 收起态只有一个 30dp 半透明圆点，几乎不遮挡；点一下展开 196×156dp 的
 * 极简工具条（读数 / 模式 / 放大镜 / 吸附 / 标尺 / ±1px / 确认 / 取消）。
 * 手柄窗口之外的所有触摸由系统分发给底层 APP（窗口带 NOT_TOUCH_MODAL）。
 *
 * ±1px 微调支持**长按连发**：按住 400ms 后每 70ms 走 1px，解决
 * 「±1px 是点按不是长按连发」的吐槽。
 */
@SuppressLint("ViewConstructor")
internal class PickerHandleView(context: Context) : View(context) {

    var expanded = false
        private set
    /** 框选模式的第二角手柄不提供展开工具条，只负责拖动。 */
    var allowExpand = true
    var mode = PickerMode.POINT
    var magnifierOn = false
    var snapOn = false
    var rulersOn = false
    var readout = ""

    /**
     * v2.9.5：抓取通知（按下瞬间）。会话据此记录「手指屏幕绝对坐标 − 准星真值」
     * 的抓取偏移，拖动全程保持不变。
     */
    var onGrab: ((rawX: Float, rawY: Float) -> Unit)? = null

    /**
     * v2.9.5：**绝对定位**拖动（传手指的屏幕绝对坐标）。
     *
     * 旧版传的是相对窗口的增量（`e.x - lastX`），而手柄窗口自身在跟随手指
     * 移动 —— 窗口每动一次，下一帧的相对增量就被抵消掉，手柄于是「一顿一顿」
     * 地滞后跳动（真机录屏可复现：手柄与准星明显不重合，就是用户说的抽搐）。
     * 改为传屏幕绝对坐标，由会话用抓取偏移反算目标位置，与窗口位置无关。
     */
    var onDragTo: ((rawX: Float, rawY: Float) -> Unit)? = null

    /**
     * v2.9.5：抬手 / 取消通知。会话据此把待提交的那一帧**立即**落地，
     * 避免最后一小段位移被按帧合并吞掉。
     */
    var onDragEnd: (() -> Unit)? = null
    var onAction: ((HandleAction) -> Unit)? = null
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (OverlayTheme.ACCENT and 0x00FFFFFF) or 0xCC000000.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayTheme.ACCENT_SOFT; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayTheme.TEXT_BRIGHT; strokeWidth = 1.6f
    }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayTheme.PANEL_DARK }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayTheme.ACCENT_SOFT and 0x66FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val chipOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayTheme.BTN_BG }
    private val chipOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayTheme.ACCENT }
    private val chipConfirm = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayTheme.ACCENT }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayTheme.TEXT_BRIGHT; textSize = 10f * d
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayTheme.TEXT_DIM; textSize = 9f * d
    }
    private val r = RectF()
    private val hot = HashMap<HandleAction, RectF>()

    private var downX = 0f
    private var downY = 0f
    /** v2.9.5：屏幕绝对坐标基准（拖动判定与绝对定位都用它，与窗口位置无关）。 */
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var downHot: HandleAction? = null
    private var repeatAction: HandleAction? = null
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val a = repeatAction ?: return
            onAction?.invoke(a)
            postDelayed(this, 70L)
        }
    }

    companion object {
        fun collapsedSize(d: Float): Int = (30 * d).toInt()
        fun expandedW(d: Float): Int = (196 * d).toInt()
        fun expandedH(d: Float): Int = (156 * d).toInt()
    }

    fun setExpanded(v: Boolean) {
        if (expanded == v) return
        expanded = v
        cancelRepeat()
        onExpandedChanged?.invoke(v)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!expanded) {
            val cx = w / 2f
            val cy = h / 2f
            c.drawCircle(cx, cy, min(cx, cy) - 2f, dot)
            c.drawCircle(cx, cy, min(cx, cy) - 2f, dotRing)
            c.drawLine(cx - 5f * d, cy, cx + 5f * d, cy, cross)
            c.drawLine(cx, cy - 5f * d, cx, cy + 5f * d, cross)
            return
        }
        c.drawRoundRect(1f, 1f, w - 1f, h - 1f, 10f * d, 10f * d, panel)
        c.drawRoundRect(1f, 1f, w - 1f, h - 1f, 10f * d, 10f * d, panelStroke)
        // 顶部拖动条 + 读数
        c.drawText("⠿ 拖动移动准星", 8f * d, 15f * d, sub)
        c.drawText(readout, 8f * d, 30f * d, text)
        hot.clear()
        var y = 38f * d
        y = chipRow(c, y, listOf(
            Triple(HandleAction.MODE, if (mode == PickerMode.POINT) "点" else "框", mode == PickerMode.REGION),
            Triple(HandleAction.MAGNIFIER, "放大镜", magnifierOn),
            Triple(HandleAction.SNAP, "吸附", snapOn),
            Triple(HandleAction.RULER, "标尺", rulersOn),
        ))
        // ±1px 十字（长按连发）
        val cs = 30f * d
        val cx = w / 2f
        chip(c, RectF(cx - cs / 2, y + cs + 2f * d, cx + cs / 2, y + 2 * cs + 2f * d), HandleAction.DOWN, "▼", false)
        chip(c, RectF(cx - cs / 2, y, cx + cs / 2, y + cs), HandleAction.UP, "▲", false)
        chip(c, RectF(cx - cs * 1.6f, y + cs, cx - cs * 0.6f, y + 2 * cs), HandleAction.LEFT, "◀", false)
        chip(c, RectF(cx + cs * 0.6f, y + cs, cx + cs * 1.6f, y + 2 * cs), HandleAction.RIGHT, "▶", false)
        c.drawText("±1px 长按连发", 8f * d, y + cs + 10f * d, sub)
        y += 2 * cs + 8f * d
        // 确认 / 取消
        val bw = (w - 20f * d) / 2f
        chip(c, RectF(8f * d, y, 8f * d + bw - 3f * d, y + 26f * d), HandleAction.CANCEL, "取消", false, chipOff)
        chip(c, RectF(12f * d + bw, y, 12f * d + 2 * bw, y + 26f * d), HandleAction.CONFIRM, "确认", false, chipConfirm)
    }

    private fun chipRow(
        c: Canvas, y: Float, items: List<Triple<HandleAction, String, Boolean>>,
    ): Float {
        val pad = 8f * d
        val gap = 4f * d
        val cw = (width - 2 * pad - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (a, label, on) ->
            val rect = RectF(pad + i * (cw + gap), y, pad + i * (cw + gap) + cw, y + 22f * d)
            chip(c, rect, a, label, on, if (on) chipOn else chipOff)
        }
        return y + 28f * d
    }

    private fun chip(c: Canvas, rect: RectF, a: HandleAction, label: String, on: Boolean, paint: Paint = if (on) chipOn else chipOff) {
        c.drawRoundRect(rect, 5f * d, 5f * d, paint)
        hot[a] = rect
        c.drawText(label, rect.centerX() - text.measureText(label) / 2f, rect.centerY() + 3.5f * d, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                downRawX = e.rawX; downRawY = e.rawY
                dragging = false
                // v2.9.5：把「手指屏幕坐标」交给会话去记抓取偏移。
                onGrab?.invoke(e.rawX, e.rawY)
                downHot = if (expanded) hot.entries.firstOrNull { it.value.contains(e.x, e.y) }?.key else null
                if (downHot in setOf(HandleAction.UP, HandleAction.DOWN, HandleAction.LEFT, HandleAction.RIGHT)) {
                    repeatAction = downHot
                    postDelayed(repeatRunnable, 400L)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // v2.9.5：位移判定与拖动一律用屏幕绝对坐标。原来用 e.x/e.y
                // （相对正在移动的窗口），窗口一动阈值忽大忽小，拖动状态反复切换。
                val moved = abs(e.rawX - downRawX) + abs(e.rawY - downRawY)
                // 展开态只有顶部 24dp 拖动条可拖；收起态整体可拖
                val canDrag = !expanded || downY <= 24f * d
                if (canDrag && moved > 4f * d) {
                    dragging = true
                    cancelRepeat()
                    onDragTo?.invoke(e.rawX, e.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelRepeat()
                val moved = abs(e.rawX - downRawX) + abs(e.rawY - downRawY)
                if (dragging) onDragEnd?.invoke()
                if (!dragging && moved < 8f * d) {
                    if (!expanded) {
                        if (allowExpand) setExpanded(true)
                    } else downHot?.let { onAction?.invoke(it) } ?: setExpanded(false)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun cancelRepeat() {
        repeatAction = null
        removeCallbacks(repeatRunnable)
    }
}

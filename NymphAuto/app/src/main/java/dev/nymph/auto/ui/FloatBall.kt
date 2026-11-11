package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.nymph.auto.service.RunnerService
import kotlin.math.min

/** 悬浮球：状态指示（灰=就绪 / 绿=执行中 / 黄=暂停）+ 拖动 + 点击切换。 */
class FloatBall(context: Context) : View(context) {

    private var state = RunnerService.State.IDLE
    private val density = resources.displayMetrics.density

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayTheme.IDLE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x66FFFFFF
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    fun setState(s: RunnerService.State) {
        state = s
        circlePaint.color = when (s) {
            RunnerService.State.IDLE -> OverlayTheme.IDLE
            RunnerService.State.RUNNING -> OverlayTheme.RUN
            RunnerService.State.PAUSED -> OverlayTheme.WARNING
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 2f * density
        if (r <= 0f) return
        canvas.drawCircle(cx, cy, r, circlePaint)
        canvas.drawCircle(cx, cy, r, borderPaint)
        val ir = r * 0.36f
        if (state == RunnerService.State.RUNNING) {
            val bw = r * 0.24f
            canvas.drawRoundRect(cx - ir, cy - ir, cx - ir + bw, cy + ir, bw / 2f, bw / 2f, iconPaint)
            canvas.drawRoundRect(cx + ir - bw, cy - ir, cx + ir, cy + ir, bw / 2f, bw / 2f, iconPaint)
        } else {
            val path = android.graphics.Path().apply {
                moveTo(cx - ir * 0.7f, cy - ir)
                lineTo(cx + ir, cy)
                lineTo(cx - ir * 0.7f, cy + ir)
                close()
            }
            canvas.drawPath(path, iconPaint)
        }
    }
}

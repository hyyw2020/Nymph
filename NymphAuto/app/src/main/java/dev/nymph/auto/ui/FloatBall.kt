package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.nymph.auto.service.RunnerService
import kotlin.math.min

/**
 * 运行球：脚本启停状态指示（灰=就绪 / 绿=执行中 / 黄=暂停 / 红=终止待恢复）+ 拖动 + 点击启停。
 *
 * v3.1.1 交互重构（A+B+C 方案）——单一语义，不再承载相机取景：
 *  - 点击 → 直接 [onToggle]（开始/暂停/继续，A 方案「点击即启停」）；
 *  - 长按 → [onShowPanel] 展开紧凑控制条，长按时长内移动则判为拖动、不出面板；
 *  - 拖动 → 移动位置，面板跟随。
 * 相机取景已拆分为独立的 [CameraBall] 控件，二者在进程内并存、互不引用，
 * 杜绝 ADR-075 时代「一个球两种互斥模式」带来的误触与语义耦合。
 */
class FloatBall(context: Context) : View(context) {

    /** 点击启停回调。 */
    var onToggle: (() -> Unit)? = null
    /** 长按展开紧凑面板回调。 */
    var onShowPanel: (() -> Unit)? = null

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
            // 终止待恢复：用与运行中不同的警示色，让「停在这一步」在悬浮球上可见，
            // 不必展开面板才知道脚本已经停了。
            RunnerService.State.HALTED -> OverlayTheme.DANGER
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

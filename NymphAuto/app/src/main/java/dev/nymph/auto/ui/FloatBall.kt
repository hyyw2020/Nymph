package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.nymph.auto.service.RunnerService
import kotlin.math.min

/**
 * 悬浮球：状态指示（灰=就绪 / 绿=执行中 / 黄=暂停）+ 拖动 + 点击切换。
 *
 * ADR-075 / 裁决 4：双语义互斥标志 [mode] 存实例字段、仅内存态、不持久化、不进 Script。
 * - [Mode.RUN_STATUS]（默认）：点击展开面板走启停，长按需停——运行态逐字零回归。
 * - [Mode.EDIT_CAMERA]：点击只回调 [onCapture]（抓当前帧建档，走无障碍链），
 *   屏蔽 onToggle / onStop 启停，点一下绝不误触启停；退出立即复位 RUN_STATUS。
 * 两条回调互斥、不复用；[setMode] 是本标志的唯一写入口。
 */
class FloatBall(context: Context) : View(context) {

    /** 双语义互斥模式。 */
    enum class Mode { EDIT_CAMERA, RUN_STATUS }

    /** 相机态点击回调（抓帧建档）。运行态为 null。 */
    var onCapture: (() -> Unit)? = null

    private var mode: Mode = Mode.RUN_STATUS
    private var state = RunnerService.State.IDLE
    private val density = resources.displayMetrics.density

    /** 当前模式（只读）。 */
    fun currentMode(): Mode = mode

    /**
     * 唯一写入口：进入编辑建档态置 EDIT_CAMERA，退出立即复位 RUN_STATUS。
     * 复位时一并清空 onCapture，杜绝回调泄漏到运行态导致球无法启停。
     */
    fun setMode(m: Mode) {
        mode = m
        if (m == Mode.RUN_STATUS) onCapture = null
        invalidate()
    }

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
    /** 相机镜头孔：用球体底色，形成「方框挖孔」的相机轮廓。 */
    private val cameraLensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayTheme.IDLE
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
        // 编辑建档态：画相机方框 + 镜头圆点，与运行态三角/暂停条明显区分。
        if (mode == Mode.EDIT_CAMERA) {
            val hw = r * 0.42f
            val hh = r * 0.32f
            canvas.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, 3f * density, 3f * density, iconPaint)
            canvas.drawCircle(cx, cy, r * 0.13f, cameraLensPaint)
            return
        }
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

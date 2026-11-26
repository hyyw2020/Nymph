package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/**
 * 建档取景球：独立于运行球的悬浮控件，仅在场景建档期存在。
 *
 * 语义单一：点击只触发 [onCapture]（抓当前目标界面帧，走无障碍取帧链），
 * 不参与脚本启停、不展开任何控制面板、不持有运行状态。与 [FloatBall]
 * 在进程内并存但互不引用——运行球按 A 方案「点击启停、长按出面板」
 * 工作，本球只服务建档，退出建档即移除，杜绝语义耦合与误触。
 */
class CameraBall(context: Context) : View(context) {

    /** 点击抓帧回调；由建档工作台以进程内中转方式绑定。 */
    var onCapture: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayTheme.IDLE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x66FFFFFF
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    /** 镜头孔用球体底色，形成「方框挖孔」的相机轮廓。 */
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayTheme.IDLE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 2f * density
        if (r <= 0f) return
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)
        val hw = r * 0.42f
        val hh = r * 0.32f
        canvas.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, 3f * density, 3f * density, bodyPaint)
        canvas.drawCircle(cx, cy, r * 0.13f, lensPaint)
    }
}

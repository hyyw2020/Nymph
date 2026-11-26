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

    // v3.1.2 视觉重做：深色底 + 强调绿描边环，与运行球（灰/状态色实心）一眼可分，
    // 明确「这是建档抓帧球」。取景框图标保留相机语义，镜头孔用底色挖空。
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayTheme.PANEL_DARK
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = OverlayTheme.ACCENT
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFFFFFFF.toInt()
    }
    /** 镜头孔用球体底色，形成「方框挖孔」的相机轮廓。 */
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayTheme.ACCENT
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 2f * density
        if (r <= 0f) return
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)
        val hw = r * 0.44f
        val hh = r * 0.34f
        // 取景框：白色描边圆角矩形（四角留缺口感由圆角模拟），中心强调色镜头点。
        canvas.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, 3f * density, 3f * density, bodyPaint)
        canvas.drawCircle(cx, cy, r * 0.15f, lensPaint)
        // 取景框顶部小凸起（相机快门），强化相机识别。
        canvas.drawRoundRect(
            cx - r * 0.16f, cy - hh - r * 0.12f, cx + r * 0.16f, cy - hh + r * 0.02f,
            1.5f * density, 1.5f * density, bodyPaint,
        )
    }
}

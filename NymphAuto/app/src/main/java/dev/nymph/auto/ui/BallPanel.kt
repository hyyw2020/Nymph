package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.nymph.auto.core.runner.HaltReport
import dev.nymph.auto.service.RunnerService

/**
 * 悬浮球长按展开的紧凑控制条（v3.1.1 A+B+C 方案）。
 *
 * 交互约定（与 [FloatBall] 配合）：
 *  - 点击运行球 → 直接启停（A 方案，不展开本面板）；
 *  - 长按运行球 → 展开本条；操作任一按钮或点击外部 → 自动收起（C 方案）；
 *  - 拖动运行球 → 本条跟随。
 *
 * 形态（B 方案）：由旧版 9 行竖排（约 200dp 高、遮挡目标应用视野）压缩为
 * 「一行操作按钮 + 至多一行状态文本」，静止高度约 44dp。所有运行时信息
 * （等待/告警/下一步/终止卡/档位）合并进单行状态文本，超长省略；同一份
 * 信息仍进前台通知（见 RunnerService.refreshForeground），不因压缩而丢失
 * 可达性。背景半透明（C 方案），透出下层应用，进一步降低遮挡感。
 *
 * 公开 API 全部保留，调用方零改动；仅内部布局与可见性策略变化。
 */
class BallPanel(context: Context) : LinearLayout(context) {

    var onToggle: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onCollapse: (() -> Unit)? = null

    /** 从终止位置继续（预留；当前由通知栏承载，面板不暴露，避免不可用的死按钮）。 */
    var onResume: (() -> Unit)? = null

    /** 任一按钮点击后由宿主收起（C 方案：操作后自动收起）。 */
    var onAction: (() -> Unit)? = null

    private val btnToggle: Button
    private val btnStop: Button
    private val btnCollapse: Button
    private val tvStatus: TextView

    /** 状态文本优先级：终止卡 > 等待 > 告警 > 下一步 > 档位。一次只显示一条。 */
    private var haltText = ""
    private var waitText = ""
    private var suspectText = ""
    private var nextText = ""
    private var tierText = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val d = resources.displayMetrics.density
        // C 方案：PANEL_BG 保留品牌色，覆以 0.92 alpha——足以辨认控件，又能透出下层应用。
        val bg = OverlayTheme.PANEL_BG
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OverlayTheme.RADIUS_PANEL_DP * d
            setColor(Color.argb(235, Color.red(bg), Color.green(bg), Color.blue(bg)))
            setStroke((1 * d).toInt(), OverlayTheme.STROKE_SOFT)
        }
        setPadding((8 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())

        fun compactButton(text: String, color: Int): Button = Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 12f
            setTextColor(color)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = OverlayTheme.RADIUS_BTN_DP * d
                setColor(OverlayTheme.BTN_BG)
            }
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f).apply {
                marginStart = (2 * d).toInt()
            }
            setOnClickListener { onAction?.invoke() }
        }

        // v3.1.2：一行三个等宽按钮（启停 / 停止 / 收起）。
        // 旧版「撤销」按钮的 onUndo 从未接线（核心层无单步撤销 API），点了没反应，
        // 属虚假交互，移除以降低视觉噪音与误触；继续由通知栏承载恢复能力。
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        btnToggle = compactButton("开始", OverlayTheme.BTN_TEXT)
        btnStop = compactButton("停止", OverlayTheme.DANGER)
        btnCollapse = compactButton("收起", OverlayTheme.BTN_TEXT)
        row.addView(btnToggle)
        row.addView(btnStop)
        row.addView(btnCollapse)
        addView(row)

        tvStatus = TextView(context).apply {
            textSize = 10f
            setTextColor(OverlayTheme.TEXT_DIM)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (2 * d).toInt() }
            visibility = View.GONE
        }
        addView(tvStatus)

        btnToggle.setOnClickListener { onToggle?.invoke() }
        btnStop.setOnClickListener { onStop?.invoke() }
        btnCollapse.setOnClickListener { onCollapse?.invoke() }
    }

    /** 合并状态文本到单行：按优先级取第一条，无内容则隐藏。 */
    private fun refreshStatus() {
        val text = haltText.ifBlank { waitText }.ifBlank { suspectText }
            .ifBlank { nextText }.ifBlank { tierText }
        tvStatus.text = text
        tvStatus.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /** 按运行状态刷新主按钮文案。 */
    fun setState(s: RunnerService.State) {
        btnToggle.text = when (s) {
            RunnerService.State.IDLE -> "开始"
            RunnerService.State.RUNNING -> "暂停"
            RunnerService.State.PAUSED -> "继续"
            RunnerService.State.HALTED -> "继续"
        }
    }

    /** 能力档位说明（空串=不显示）。 */
    fun setTier(text: String) {
        tierText = text
        refreshStatus()
    }

    /** 等待浮层：null=隐藏；非空=显示"正在等待 X Ns / Ms"。 */
    fun setWait(status: dev.nymph.auto.core.runner.WaitStatus?) {
        waitText = status?.render() ?: ""
        refreshStatus()
    }

    /**
     * v2.9.31：「疑似在场」持续未达阈值时的可见告警。空串清除。
     * 同一份文案也进前台通知，保证切到任何界面都够得着「重新拾取」。
     */
    fun setSuspect(text: String) {
        suspectText = text
        refreshStatus()
    }

    /** 常驻「下一步」。FOLDED 级预告不阻塞，只刷新单行状态文本。 */
    fun setNext(text: String) {
        nextText = text
        refreshStatus()
    }

    /**
     * 撤销预告入口（v3.1.2 起为 no-op）：核心层无单步撤销 API，按钮已移除，
     * 保留方法签名使 RunnerService 调用点零改动，避免「勾了/点了没反应」的虚假交互。
     */
    fun showUndo(@Suppress("UNUSED_PARAMETER") windowMs: Long) = Unit

    /** 兼容旧调用点；撤销键已移除，无状态可清。 */
    fun dismissUndo() = Unit

    /** 终止卡：null=清除；非空=显示原因与位置。 */
    fun setHalt(report: HaltReport.Report?) {
        if (report != null) suspectText = ""
        if (report == null) {
            haltText = ""
            refreshStatus()
            return
        }
        val where = when {
            report.resumeRule > 0 -> " · 规则 ${report.resumeRule}"
            report.resumeStep > 0 -> " · 第 ${report.resumeStep} 步"
            else -> ""
        }
        haltText = report.headline + where
        refreshStatus()
    }
}

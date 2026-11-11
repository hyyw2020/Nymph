package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.nymph.auto.R
import dev.nymph.auto.service.RunnerService

/**
 * 悬浮球展开的控制面板：开始/暂停、停止、收起 + 等待浮层行。
 *
 * 交互约定（与悬浮球配合）：
 *  - 点悬浮球 → 展开/收起本面板
 *  - 长按悬浮球 → 停止并移除（快捷退出）
 *  - 拖动悬浮球 → 移动位置（面板跟随）
 *
 * 等待浮层（裁定阶段 3.5）：主界面只显示"正在等待'确认'按钮 3.2s / 15s"，
 * 隐藏时让位给状态行；绝对时间轴、注入模式等不进主界面。
 */
class BallPanel(context: Context) : LinearLayout(context) {

    var onToggle: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onCollapse: (() -> Unit)? = null

    private val btnToggle: Button
    private val btnStop: Button
    private val btnCollapse: Button
    private val tvWait: TextView
    private val tvTier: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val d = resources.displayMetrics.density
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OverlayTheme.RADIUS_PANEL_DP * d
            setColor(OverlayTheme.PANEL_BG)
            setStroke((1 * d).toInt(), OverlayTheme.STROKE_SOFT)
        }
        setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())

        fun button(text: String): Button = Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setTextColor(OverlayTheme.BTN_TEXT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = OverlayTheme.RADIUS_BTN_DP * d
                setColor(OverlayTheme.BTN_BG)
            }
            minWidth = 0
            minimumWidth = 0
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * d).toInt()
            }
        }

        fun label(): TextView = TextView(context).apply {
            textSize = 11f
            setTextColor(OverlayTheme.TEXT_DIM)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
            }
        }

        // 能力档位行（裁定阶段 0：档位与限制明示）
        tvTier = label()
        // 等待浮层行（默认隐藏）
        tvWait = label().apply {
            setTextColor(OverlayTheme.WARNING)
            visibility = View.GONE
        }
        btnToggle = button("开始")
        btnStop = button("停止").apply { setTextColor(OverlayTheme.DANGER) }
        btnCollapse = button("收起")
        addView(tvTier)
        addView(tvWait)
        addView(btnToggle)
        addView(btnStop)
        addView(btnCollapse)

        btnToggle.setOnClickListener { onToggle?.invoke() }
        btnStop.setOnClickListener { onStop?.invoke() }
        btnCollapse.setOnClickListener { onCollapse?.invoke() }
    }

    /** 按运行状态刷新主按钮文案。 */
    fun setState(s: RunnerService.State) {
        btnToggle.text = when (s) {
            RunnerService.State.IDLE -> "开始"
            RunnerService.State.RUNNING -> "暂停"
            RunnerService.State.PAUSED -> "继续"
        }
    }

    /** 能力档位说明（空串=不显示）。 */
    fun setTier(text: String) {
        tvTier.text = text
        tvTier.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 等待浮层：null=隐藏；非空=显示"正在等待 X Ns / Ms"。 */
    fun setWait(status: dev.nymph.auto.core.runner.WaitStatus?) {
        if (status == null) {
            tvWait.visibility = View.GONE
        } else {
            tvWait.text = status.render()
            tvWait.visibility = View.VISIBLE
        }
    }
}

package dev.nymph.auto.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.nymph.auto.core.runner.HaltReport
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
 *
 * v2.9.30 新增两块：
 *  - **常驻「下一步」**（[setNext]）：不阻塞地显示即将执行的动作用于已确认的
 *    重复循环。它与 FULL 级预告的分工见 core.runner.AnnouncePolicy。
 *  - **撤销键**（[showUndo]）：只在预告窗口内出现，窗口结束自动消失。
 *    撤销只影响本步，不影响后续——「全局撤销」是通知栏的暂停/归还/停止。
 *  - **终止卡**（[setHalt]）：停在哪、为什么停、能不能继续。面板可被目标应用
 *    遮挡，因此同一份信息也进通知栏（见 RunnerService.refreshForeground）。
 */
class BallPanel(context: Context) : LinearLayout(context) {

    var onToggle: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onCollapse: (() -> Unit)? = null

    /** 撤销本步（仅预告窗口内可见）。 */
    var onUndo: (() -> Unit)? = null

    /** 从终止位置继续。 */
    var onResume: (() -> Unit)? = null

    private val btnToggle: Button
    private val btnStop: Button
    private val btnCollapse: Button
    private val btnUndo: Button
    private val tvWait: TextView
    private val tvSuspect: TextView
    private val tvTier: TextView
    private val tvNext: TextView
    private val tvHalt: TextView

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
        // v2.9.31：模板可能失效的常驻告警。目标应用全屏时面板可能被遮，
        // 同一份信息同时进通知栏（见 RunnerService.refreshForeground）。
        tvSuspect = label().apply {
            setTextColor(OverlayTheme.WARNING)
            visibility = View.GONE
        }
        // 常驻「下一步」：FOLDED 级预告的落点，不阻塞
        tvNext = label().apply {
            setTextColor(OverlayTheme.TEXT_DIM)
            visibility = View.GONE
        }
        // 终止卡：原因 + 位置
        tvHalt = label().apply {
            setTextColor(OverlayTheme.DANGER)
            visibility = View.GONE
        }
        btnUndo = button("撤销本步").apply {
            setTextColor(OverlayTheme.WARNING)
            visibility = View.GONE
        }
        btnToggle = button("开始")
        btnStop = button("停止").apply { setTextColor(OverlayTheme.DANGER) }
        btnCollapse = button("收起")
        addView(tvTier)
        addView(tvWait)
        addView(tvSuspect)
        addView(tvNext)
        addView(tvHalt)
        addView(btnUndo)
        addView(btnToggle)
        addView(btnStop)
        addView(btnCollapse)

        btnToggle.setOnClickListener { onToggle?.invoke() }
        btnStop.setOnClickListener { onStop?.invoke() }
        btnCollapse.setOnClickListener { onCollapse?.invoke() }
        btnUndo.setOnClickListener { dismissUndo(); onUndo?.invoke() }
    }

    /** 按运行状态刷新主按钮文案。 */
    fun setState(s: RunnerService.State) {
        btnToggle.text = when (s) {
            RunnerService.State.IDLE -> "开始"
            RunnerService.State.RUNNING -> "暂停"
            RunnerService.State.PAUSED -> "继续"
            RunnerService.State.HALTED -> "从这步继续"
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

    /**
     * v2.9.31：「疑似在场」持续未达阈值时的可见告警。空串清除。
     * 只更新文本、不阻塞、不改变运行状态；同一份文案也进前台通知，
     * 保证用户切到任何界面都够得着「重新拾取」。
     */
    fun setSuspect(text: String) {
        if (text.isBlank()) {
            tvSuspect.visibility = View.GONE
        } else {
            tvSuspect.text = text
            tvSuspect.visibility = View.VISIBLE
        }
    }

    /**
     * 常驻「下一步」。空串清除。
     *
     * 这是 FOLDED 级预告的唯一出口：它必须**不阻塞**，因此这里只更新文本，
     * 不做任何等待、不触发布局抖动（文本长度变化由单行省略处理）。
     */
    fun setNext(text: String) {
        if (text.isBlank()) {
            tvNext.visibility = View.GONE
        } else {
            tvNext.text = text
            tvNext.visibility = View.VISIBLE
        }
    }

    /**
     * 显示撤销键并在 [windowMs] 后自动收起。
     *
     * 窗口时长由 core.runner.AnnouncePolicy 决定，面板不自行延长——否则
     * 「预告多久」会有两个真源。
     */
    fun showUndo(windowMs: Long) {
        btnUndo.visibility = View.VISIBLE
        btnUndo.removeCallbacks(hideUndo)
        btnUndo.postDelayed(hideUndo, windowMs.coerceAtLeast(0L))
    }

    private val hideUndo = Runnable { btnUndo.visibility = View.GONE }

    /** 主动收起撤销键（用户已点或窗口被新的预告替换）。 */
    fun dismissUndo() {
        btnUndo.removeCallbacks(hideUndo)
        btnUndo.visibility = View.GONE
    }

    /**
     * 终止卡：null=清除；非空=显示原因与位置。
     * 「从这步继续」同时出现在主按钮（[setState] HALTED 档）与通知栏 action。
     */
    fun setHalt(report: HaltReport.Report?) {
        dismissUndo()
        if (report != null) setSuspect("")
        if (report == null) {
            tvHalt.visibility = View.GONE
            return
        }
        val where = when {
            report.resumeRule > 0 -> " · 规则 ${report.resumeRule}"
            report.resumeStep > 0 -> " · 第 ${report.resumeStep} 步"
            else -> ""
        }
        tvHalt.text = report.headline + where
        tvHalt.visibility = View.VISIBLE
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.recorder

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.nymph.auto.core.gesture.DragGesture
import dev.nymph.auto.ui.OverlayTheme

/**
 * 录制悬浮控制面板。
 *
 * **触摸契约**：由 [dev.nymph.auto.service.RecordingService] 以
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` 添加，只消费落在本面板区域内的点击。
 * 不使用 WATCH_OUTSIDE_TOUCH、不使用全屏透明层，因此其他 APP 的触摸完全不受影响。
 *
 * **v2.9.3 拖动**：面板本体（状态/提示文字所在空白区）可拖动，复用坐标拾取手柄的
 * 拖动手感（触摸斜率超过阈值才认定为拖动，不误伤按钮点击）。拖动只改窗口的 `x/y`
 * 并夹在屏幕内，不改任何 flags、不拦截外部触摸 ——「不干扰其他 APP」的承诺对录制面板
 * 同样成立。
 */
class RecordPanel(context: Context) : LinearLayout(context) {

    var onStop: (() -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    /** 拖动回调：返回相对上一次的像素位移，由服务更新窗口位置并 clamp 到屏幕内。 */
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null

    private val tvStatus: TextView
    private val tvHint: TextView

    private val d = resources.displayMetrics.density
    /** v2.9.4：拖动手感收敛到 core 纯 JVM 状态机，本类只转发事件、只改窗口 x/y。 */
    private val drag = DragGesture((8 * d).toInt())

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OverlayTheme.RADIUS_PANEL_DP * d
            setColor(OverlayTheme.PANEL_BG)
            setStroke((1 * d).toInt(), OverlayTheme.STROKE_SOFT)
        }
        setPadding(
            (12 * d).toInt(), (10 * d).toInt(),
            (12 * d).toInt(), (10 * d).toInt(),
        )

        fun button(text: String, color: Int): Button = Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setTextColor(OverlayTheme.BTN_TEXT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = OverlayTheme.RADIUS_BTN_DP * d
                setColor(color)
            }
            minWidth = 0
            minimumWidth = 0
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * d).toInt()
            }
        }

        tvStatus = TextView(context).apply {
            text = "● 录制中 · 0 步"
            textSize = 13f
            setTextColor(OverlayTheme.DANGER)
            gravity = Gravity.CENTER
        }
        tvHint = TextView(context).apply {
            text = DEFAULT_HINT
            textSize = 10f
            setTextColor(OverlayTheme.TEXT_DIM)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * d).toInt()
                bottomMargin = (4 * d).toInt()
            }
        }
        val btnUndo = button("撤销上一步", OverlayTheme.BTN_BG)
        val btnStop = button("停止并保存", OverlayTheme.DANGER_STRONG)

        addView(tvStatus)
        addView(tvHint)
        addView(btnUndo)
        addView(btnStop)
        btnUndo.setOnClickListener { onUndo?.invoke() }
        btnStop.setOnClickListener { onStop?.invoke() }
    }

    fun setCount(n: Int, elapsedSec: Long) {
        tvStatus.text = "● 录制中 · $n 步 · ${elapsedSec}s"
    }

    /**
     * v2.9.2：录制可行性告警（自绘界面 / 零事件）显示在面板上，
     * 与日志页、toast 构成三通道必达。空文本恢复默认提示。
     * 提示只是文案，不改变任何窗口 flags / 触摸分发，符合不打断其他 APP 的契约。
     */
    fun setHint(text: String) {
        if (text.isBlank()) {
            tvHint.text = DEFAULT_HINT
            tvHint.setTextColor(OverlayTheme.TEXT_DIM)
        } else {
            tvHint.text = text
            tvHint.setTextColor(OverlayTheme.WARNING)
        }
    }

    // ---- v2.9.3 拖动 / v2.9.4 修缺陷：复用拾取手柄手感，绝不误伤按钮点击 ----
    //
    // v2.9.4 修复两处真 bug（状态机细节见 core DragGesture）：
    //  1. onTouchEvent 对 ACTION_DOWN 必须返回 true。落点在状态/提示文字这类
    //     非 clickable 区域时，DOWN 若不消费，面板不会成为 touch target，后续
    //     MOVE 根本不再派发过来 —— 旧版面板实际拖不动；
    //  2. 增量基准只在真正产生位移时更新，不再被 onInterceptTouchEvent 反复覆盖。

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            drag.onDown(e.rawX, e.rawY)
            false // 让按钮等子 View 正常接收按下
        }
        MotionEvent.ACTION_MOVE -> drag.onInterceptMove(e.rawX, e.rawY)
        else -> false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            drag.onDown(e.rawX, e.rawY)
            true // 接住空白区域的按下，后续 MOVE 才会派发到本面板
        }
        MotionEvent.ACTION_MOVE -> {
            val delta = drag.onOwnMove(e.rawX, e.rawY)
            if (delta != null) onDrag?.invoke(delta.first, delta.second)
            true // 已消费 DOWN，MOVE 必须持续消费以保持手势
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
            drag.onUp() // 拖动过则消费抬起，避免误触发按钮
        else -> false
    }

    companion object {
        private const val DEFAULT_HINT = "照常操作其他 APP\n不占用录屏权限"
    }
}

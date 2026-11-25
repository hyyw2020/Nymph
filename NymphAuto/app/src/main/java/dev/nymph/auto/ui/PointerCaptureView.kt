// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View

/**
 * 拾取时的全屏滑动捕获层（v2.9.29）。
 *
 * ## 为什么需要它
 *
 * v2.9.28 及以前，拖动准星只有一个入口：折叠态那个 30dp 的抓手圆。命中区小于
 * Android 无障碍最小触摸目标 48dp，贴边时窗口还会被 `coerceIn` 夹到屏内，与准星
 * 真值分离。实际表现是：只有落在该 30dp 圆内才能开始拖动，偏离即无响应，
 * 贴边后窗口与真值不再重合，回拉方向与视觉方向不一致。
 *
 * 本层把「拖准星」的入口从 30dp 扩到整屏：任意位置按下即建立抓取偏移，移动即
 * 拖动准星，抬手即提交。抓取偏移仍由 [dev.nymph.auto.core.vision.PickerGeometry]
 * 计算，因此手指按在准星的任何相对位置都不会让准星跳格。
 *
 * ## 代价与出口
 *
 * 全屏可触摸意味着背后应用在这段时间收不到触摸。拾取本身是短时操作（先让目标
 * 界面出现，再取一个点），需要翻页时用长按抓手「放行 3 秒」临时让路，不需要退出
 * 拾取重来。
 *
 * ## 与系统手势的关系
 *
 * 边缘的 `MOVE` 会被系统返回手势截获，仅靠本层收不到事件。排除区由上层
 * （拾取会话）通过 `setSystemGestureExclusionRects` 跟随准星声明，本层只负责派发原始坐标。
 */
@SuppressLint("ClickableViewAccessibility")
class PointerCaptureView(context: Context) : View(context) {

    /** 按下：屏幕绝对坐标，由会话记录抓取偏移。 */
    var onDown: ((rawX: Float, rawY: Float) -> Unit)? = null

    /** 移动：屏幕绝对坐标，由会话换算准星真值。 */
    var onMove: ((rawX: Float, rawY: Float) -> Unit)? = null

    /** 抬手（拖动结束）：提交最后一帧。 */
    var onUp: (() -> Unit)? = null

    /** 系统取消（来电、切后台）：按抬手处理，不让准星停在中间态。 */
    var onCancel: (() -> Unit)? = null

    /**
     * 轻点：把准星**放到**按下的那个点（绝对定位）。
     *
     * 与拖动的区别是这一步不做任何增量换算：用户指向哪里，准星就去哪里。
     * 取**按下点**而不是抬起点——用户瞄准的是按下那一刻，抬手时常有 1~8px 漂移，
     * 用抬起点会让落点系统性地偏离瞄准位置。这也与 Android 自身的 click 判定一致。
     */
    var onTap: ((rawX: Float, rawY: Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                onDown?.invoke(e.rawX, e.rawY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    // 超过触摸 slop 才进入拖动：否则手指的轻微抖动会把「点一下」
                    // 变成「拖一段」，落点被拽偏。
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) dragging = true
                }
                if (dragging) onMove?.invoke(e.rawX, e.rawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onUp?.invoke() else onTap?.invoke(downX, downY)
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onCancel?.invoke()
                dragging = false
                return true
            }
        }
        // 多指按下不参与拾取：只跟第一根手指，避免第二指把准星拽走。
        return true
    }
}

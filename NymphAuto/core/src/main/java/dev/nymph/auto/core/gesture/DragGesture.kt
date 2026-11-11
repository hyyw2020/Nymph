// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.gesture

import kotlin.math.hypot

/**
 * 浮窗拖动手势状态机（v2.9.4，纯 JVM、可单测）。
 *
 * ## 为什么抽到 core
 *
 * v2.9.3 给录制面板加了拖动，但触摸逻辑直接写在 Android View 里，无法单测，
 * 结果留下两个真实缺陷：
 *  1. `onTouchEvent(ACTION_DOWN)` 返回 false。落点在状态文字这类非 clickable
 *     区域时 DOWN 未被任何 View 消费，面板不会成为 touch target，后续 MOVE
 *     根本不再派发过来 —— 面板实际拖不动；
 *  2. `onInterceptTouchEvent` 每次 MOVE 都覆盖 `lastX/lastY`，与 onTouchEvent
 *     共用的增量基准互相打架，拦截路径下首帧位移丢失或突跳。
 *
 * 这里把「按下 → 超过阈值 → 增量 → 抬起」收敛成无 Android 依赖的状态机，
 * app 层只做事件转发，行为由 core 单测锁死。
 *
 * ## 契约
 *
 * - 超过 [slopPx] 才认定为拖动，短距离点击不会被误伤（按钮照常响应）；
 * - 首次进入拖动只更新基准、不产生位移，避免窗口瞬移；
 * - 位移是「相对上一次 MOVE」的像素增量，由调用方累加到窗口 x/y 并 clamp；
 * - 本类不碰窗口、不碰 flags、不拦截外部触摸，纯计算。
 */
class DragGesture(private val slopPx: Int) {

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    val isDragging: Boolean get() = dragging

    /** 按下（含落在子 View 上的按下，由 onInterceptTouchEvent 转发）。 */
    fun onDown(x: Float, y: Float) {
        downX = x; downY = y
        lastX = x; lastY = y
        dragging = false
    }

    /**
     * 子 View 仍持有手势时的 MOVE：返回是否应当拦截并转交给面板自己处理。
     * 拦截瞬间把基准同步到当前点，保证随后第一个位移不出现跳变。
     */
    fun onInterceptMove(x: Float, y: Float): Boolean {
        if (!dragging && hypot(x - downX, y - downY) > slopPx) {
            dragging = true
            lastX = x; lastY = y
        }
        return dragging
    }

    /**
     * 面板自己消费手势时的 MOVE：返回本次应施加的位移增量。
     * null = 还未进入拖动（或刚跨过阈值、仅建立基准），不产生窗口移动。
     */
    fun onOwnMove(x: Float, y: Float): Pair<Int, Int>? {
        if (!dragging) {
            if (hypot(x - downX, y - downY) <= slopPx) return null
            dragging = true
            lastX = x; lastY = y
            return null
        }
        val dx = (x - lastX).toInt()
        val dy = (y - lastY).toInt()
        lastX = x; lastY = y
        return dx to dy
    }

    /** 抬起 / 取消：返回本手势是否发生过拖动（决定 UP 是否该被消费）。 */
    fun onUp(): Boolean {
        val was = dragging
        dragging = false
        return was
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 拾取手柄的触摸几何（v2.9.29，纯 JVM 可单测）。
 *
 * ## 修的是什么
 *
 * v2.9.28 及以前，抓手的窗口只有 30dp，且定位时用
 * `crossX - width/2` 再 `coerceIn(0, screenW - width)`：
 *
 * 1. **命中区不达标**：30dp 小于 Android 无障碍最小触摸目标 48dp，边缘处实际可触
 *    面积更小。用户必须精确摸到那个小圆才能拖动，贴边时几乎摸不到。
 * 2. **视觉与真值分离**：准星在 x=0 时，窗口被夹到 `x=0`，圆心落在 15dp 处。
 *    用户看到圆在离边 15dp 的位置，读数却是 0%，同一屏上出现两个"位置"。
 * 3. **边缘手势争用**：窗口贴边时落在系统返回手势热区内，`MOVE` 事件被系统截获，
 *    手指仍在移动、应用收不到事件，表现为拖动无响应、贴边后无法回拉。
 *
 * 本类只负责几何，不做任何 Android 调用：
 * - [windowFor] 给手柄窗口的屏内矩形（边长由调用方按 48dp 换算）；
 * - [markerIn] 给抓手在窗口内的绘制位置，让视觉圆始终压在准星真值上；
 * - [exclusionFor] 给跟随准星的手势排除矩形，交给
 *   `View.setSystemGestureExclusionRects`，让边缘拖动不被系统手势抢走。
 */
object PointerTouchTarget {

    /** Android 无障碍与 Material 一致的最小触摸目标（dp）。 */
    const val MIN_TOUCH_DP = 48

    /** 手势排除区的边长（dp）。取 2 倍命中区，覆盖手指接触椭圆与预判抖动。 */
    const val EXCLUSION_DP = 96

    /** 屏内矩形，左闭右开（与 Canvas/Rect 语义一致）。 */
    data class Rect(val l: Int, val t: Int, val r: Int, val b: Int) {
        val width: Int get() = max(0, r - l)
        val height: Int get() = max(0, b - t)
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /**
     * 手柄窗口矩形：以准星为中心、边长 [sizePx]，整体夹在屏幕内。
     *
     * 夹取是必要的——越出屏幕的窗口部分既不显示也收不到触摸。代价是贴边时
     * 窗口中心与准星真值不再重合，因此绘制必须走 [markerIn] 而不是画在窗口中心。
     */
    fun windowFor(crossX: Float, crossY: Float, screenW: Int, screenH: Int, sizePx: Int): Rect {
        val size = sizePx.coerceAtLeast(1)
        val w = screenW.coerceAtLeast(1)
        val h = screenH.coerceAtLeast(1)
        val left = (crossX.roundToInt() - size / 2).coerceIn(0, max(0, w - size))
        val top = (crossY.roundToInt() - size / 2).coerceIn(0, max(0, h - size))
        val r = min(w, left + size)
        val b = min(h, top + size)
        return Rect(left, top, r, b)
    }

    /**
     * 抓手在窗口内的绘制坐标：`准星真值 − 窗口左上角`。
     *
     * 贴边时该值会接近 0 或 [Rect.width]，抓手圆可能有一半落在窗口外被裁剪——
     * 这是刻意的：**宁可让抓手显示不全，也不让它偏离准星真值**。用户判断位置
     * 看的是贯穿全屏的十字线，抓手只是拖动入口。
     */
    fun markerIn(window: Rect, crossX: Float, crossY: Float): Pair<Float, Float> =
        (crossX - window.l) to (crossY - window.t)

    /**
     * 交给系统的"这块别当返回手势"矩形，跟随准星，边长 [sizePx]。
     *
     * 为什么跟随准星而不是固定贴三条屏边：
     * - 系统对排除区总量有上限（每屏高度折算后约 200dp），三条屏边常驻会超限被裁；
     * - 用户真正需要拖动的那块区域就是准星附近，排除区跟着走既能覆盖需求又不超限；
     * - 准星贴边时排除区自然压在手势热区上，边缘拖动才不会被系统截走。
     *
     * @return 合法矩形；屏幕小到放不下任何面积时返回 null，调用方跳过本次设置。
     */
    fun exclusionFor(crossX: Float, crossY: Float, screenW: Int, screenH: Int, sizePx: Int): Rect? {
        // 窗口尚未测量（0×0）时排除区没有意义，直接跳过，避免给系统一个非法矩形。
        if (screenW <= 0 || screenH <= 0) return null
        val w = screenW.coerceAtLeast(1)
        val h = screenH.coerceAtLeast(1)
        val size = sizePx.coerceIn(1, max(w, h))
        val left = (crossX.roundToInt() - size / 2).coerceIn(0, max(0, w - size))
        val top = (crossY.roundToInt() - size / 2).coerceIn(0, max(0, h - size))
        val r = min(w, left + size)
        val b = min(h, top + size)
        val rect = Rect(left, top, r, b)
        return if (rect.isEmpty) null else rect
    }
}

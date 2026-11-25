// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

/**
 * **系统悬浮窗口坐标空间的边界契约**（v2.9.19，纯 JVM）。
 *
 * ## 适用前提
 *
 * 窗口声明 `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` 后，其 `x/y`
 * 以**显示屏全屏绝对坐标**为基准。此时边界收敛必须取自同一坐标空间的
 * 窗口指标，不得使用应用窗口可用区尺寸（`resources.displayMetrics` 的
 * `widthPixels/heightPixels` 不含系统装饰区与显示切口区）。两类尺寸在
 * 沉浸式、异型屏与横屏场景下存在差异，混用会造成边界收敛上界偏小、
 * 初始位置系统性偏移。
 *
 * ## 为什么放 core
 *
 * 本环境没有真机与 Robolectric。把「边界来源的数据结构、边界收敛、
 * 边界变化后的重定位」抽成纯数据 + 纯函数后，可用 JVM 单测断言
 * 「收敛上界与窗口坐标空间一致」「配置变化后窗口不越界」，这是唯一
 * 能自动执行的防线。
 */
object OverlayBounds {

    /** 显示屏全屏绝对坐标下的边界尺寸。 */
    data class Size(val width: Int, val height: Int) {
        init {
            require(width > 0 && height > 0) {
                "显示屏边界尺寸必须为正值"
            }
        }
    }

    /**
     * 把窗口左上坐标收敛到屏内。窗口尺寸大于屏幕时上界归零，
     * 不产生负上界，也不产生负坐标。
     */
    fun clamp(x: Int, y: Int, w: Int, h: Int, size: Size): Pair<Int, Int> {
        val maxX = (size.width - w).coerceAtLeast(0)
        val maxY = (size.height - h).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * 屏幕边界变化（横竖屏切换、折叠屏展开、外接显示切换）后保持相对锚点
     * 并重收敛。以旧边界下的占比映射至新边界，再按新边界收敛，避免窗口
     * 停留在旧坐标导致越界不可见。
     */
    fun reposition(
        x: Int, y: Int, w: Int, h: Int,
        from: Size, to: Size,
    ): Pair<Int, Int> {
        val ratioX = x.toDouble() / from.width.toDouble()
        val ratioY = y.toDouble() / from.height.toDouble()
        val nx = kotlin.math.round(ratioX * to.width).toInt()
        val ny = kotlin.math.round(ratioY * to.height).toInt()
        return clamp(nx, ny, w, h, to)
    }
}

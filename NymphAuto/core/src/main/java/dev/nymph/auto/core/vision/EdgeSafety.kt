// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import kotlin.math.max
import kotlin.math.min

/**
 * 点击落点的边缘安全判定（v2.9.29，纯 JVM 可单测）。
 *
 * ## 为什么需要
 *
 * 手势导航下，屏幕左右边缘与底部属于**系统手势热区**：落在其中的触摸会被系统
 * 当作「返回」「Home」消费掉，应用根本收不到。脚本把落点写在 0% 或 100% 时，
 * 无障碍 `dispatchGesture` 仍然会"成功派发"，但事件在系统层就被改写——
 * 用户看到的是「脚本执行了、日志显示点了、界面没反应」，且没有任何错误码。
 *
 * ## 与「拾取」的分工
 *
 * 拾取阶段的边缘问题由 [PointerTouchTarget] 的捕获层与手势排除区解决（让手能拖、
 * 事件不被抢）。本类解决的是**已经存进脚本的那个坐标**在运行时会不会被吃掉——
 * 两者是不同的问题：前者是输入通道，后者是落点合法性。
 *
 * ## 为什么不直接改脚本里的坐标
 *
 * 坐标是用户的意图。若在拾取时就静默改写，用户看到的百分比与脚本里存的不一致，
 * 也无法解释为什么"我点的位置变成了另一个位置"。因此：
 * - 拾取阶段只**提示**，不修改；
 * - 执行阶段在派发前做一次内移，并在结果里记一次「边缘修正」，可事后诊断。
 *
 * ## 内移的方向与幅度
 *
 * 只沿越界的那个轴内移，另一轴保持不变；内移量取「恰好离开热区」的最小值，
 * 不多移一像素——多移会把点击推到相邻控件上，比点空更危险。
 */
object EdgeSafety {

    /** 屏幕四边的保留区（像素）。由调用方从系统 insets 换算，拿不到时用 [FALLBACK_DP]。 */
    data class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

    /** 一次调整的结果。[moved] 为 false 时 [reason] 为空。 */
    data class Adjustment(
        val x: Int,
        val y: Int,
        val moved: Boolean,
        val reason: String = "",
    )

    /** 拿不到系统 insets 时的保守保留区（dp）：只避开必然被拦截的最外沿。 */
    const val FALLBACK_DP = 4

    /**
     * 把落点收敛到安全区。
     *
     * @param x 目标像素 x（调用方应已夹到屏内，越界值也会被再次夹取）
     * @param insets 四边热区宽度；全 0 表示该设备无手势导航或无 insets 信息
     */
    fun adjust(
        x: Int,
        y: Int,
        screenW: Int,
        screenH: Int,
        insets: Insets,
    ): Adjustment {
        val w = screenW.coerceAtLeast(1)
        val h = screenH.coerceAtLeast(1)
        val maxX = max(0, w - 1)
        val maxY = max(0, h - 1)

        // 先夹到屏内：脚本可能存了越界值（不同分辨率之间迁移时常见）。
        val cx = x.coerceIn(0, maxX)
        val cy = y.coerceIn(0, maxY)

        // 热区不能把屏幕吃光：两侧相加必须留出至少 1px 可点区。
        val l = insets.left.coerceIn(0, maxX)
        val r = insets.right.coerceIn(0, maxX)
        val safeLeft = min(l, maxX)
        val safeRight = max(safeLeft, maxX - r)
        val t = insets.top.coerceIn(0, maxY)
        val b = insets.bottom.coerceIn(0, maxY)
        val safeTop = min(t, maxY)
        val safeBottom = max(safeTop, maxY - b)

        val nx = cx.coerceIn(safeLeft, safeRight)
        val ny = cy.coerceIn(safeTop, safeBottom)
        if (nx == cx && ny == cy) return Adjustment(cx, cy, false)

        val side = when {
            cx < safeLeft -> "左边缘手势区"
            cx > safeRight -> "右边缘手势区"
            cy < safeTop -> "顶部状态栏区"
            else -> "底部手势区"
        }
        return Adjustment(nx, ny, true, side)
    }

    /**
     * 该点是否落在热区内（不改值，用于拾取阶段提示）。
     *
     * 与 [adjust] 共用同一套边界计算，避免"提示说危险、执行却认为安全"。
     */
    fun isRisky(x: Int, y: Int, screenW: Int, screenH: Int, insets: Insets): Boolean =
        adjust(x, y, screenW, screenH, insets).moved

    /**
     * dp → px 的保留区换算。
     *
     * 单独提供是因为调用方拿到的 insets 有时是 dp 常量（旧版本系统拿不到真实值），
     * 换算必须集中在一处，避免两处 density 处理不一致。
     */
    fun dpToPx(dp: Int, density: Float): Int = (dp * density).toInt().coerceAtLeast(0)

    /** 全边等宽的保守 insets（拿不到系统值时的兜底）。 */
    fun fallback(density: Float): Insets {
        val px = dpToPx(FALLBACK_DP, density)
        return Insets(px, px, px, px)
    }
}

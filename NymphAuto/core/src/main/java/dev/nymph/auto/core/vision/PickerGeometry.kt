// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 坐标拾取的纯几何核心（v2.7.1，纯 JVM 可单测）。
 *
 * ## 修的 v2.7.0 真 bug
 *
 * 1. **准星坐标从「手柄窗口中心」反算**：工具条展开成 196×156dp、或手柄
 *    拖到屏幕边缘被 `coerceIn` 夹逼时，窗口中心会跳变，`confirm()` 取到的
 *    坐标与用户拖动时的十字位置不一致。本类把准星抽象成**独立像素真值**，
 *    拖动只做「累加 + clamp」，与窗口几何彻底解耦（app 层 `PickerSession`
 *    持真值，窗口 LayoutParams 只负责呈现）。
 * 2. **框选矩形归一化内联且各算一套**：`confirm()` 与绘制层分别 min/max +
 *    coerce，负向拖动 / 零面积 / 贴边时可能不一致。统一到 [normalizeRegion]。
 * 3. **Locale 不安全**：v2.7.0 的 `"%.4f".format(...)` 用设备默认 Locale，
 *    在 de/fr 等地区会输出 `47,4074`（逗号小数点），导致坐标文本框
 *    `toDoubleOrNull()` 解析失败、ROI 四元组表达式被逗号切碎。统一走
 *    [fmt4]（强制 `Locale.US`）。
 */
object PickerGeometry {

    /** 把像素坐标夹到 [0, extent-1]（extent 最小 1，避免空屏崩溃）。 */
    fun clamp(v: Float, extent: Int): Float = v.coerceIn(0f, max(0, extent - 1).toFloat())

    /**
     * 拖动累加：dx/dy 为本次手指增量，返回夹逼后的新绝对像素坐标。
     * 这是准星移动的唯一真源，不依赖任何窗口尺寸。
     */
    fun drag(x: Float, y: Float, dx: Int, dy: Int, w: Int, h: Int): Pair<Float, Float> =
        clamp(x + dx, w) to clamp(y + dy, h)

    /**
     * v2.9.5：抓取偏移（按下瞬间记录）。
     *
     * `grabOffset = 按下时手指的屏幕绝对坐标 − 目标真值坐标`。
     * 拖动期间该偏移保持不变，于是手柄相对手指的相对位置恒定，
     * 手柄既不会「追不上」也不会「跳格」。
     */
    fun grabOffset(downRaw: Float, target: Float): Float = downRaw - target

    /**
     * v2.9.5：**绝对定位**（拖动不累加）——真机「抽搐」的根因修复。
     *
     * 旧实现用手柄 View 的**相对坐标**（`MotionEvent.x/y`）算增量，而手柄
     * 窗口本身在跟随手指移动：窗口一动，下一帧的相对坐标增量就被抵消掉，
     * 手柄因此「一顿一顿」地滞后跳动（真机录屏可复现：手柄与准星明显不重合）。
     *
     * 这里改成「目标 = 手指屏幕绝对坐标 − 抓取偏移」：结果与窗口自身位置
     * 完全无关，既不丢增量也不累积漂移（与悬浮编辑器 ADR-012 的
     * `dragAbsolute` 同一治理思路，此前只治了悬浮编辑器、漏了拾取链路）。
     */
    fun placeFromGrab(raw: Float, grabOffset: Float, extent: Int): Float =
        clamp(raw - grabOffset, extent)

    /**
     * v2.9.5：提交阈值——位移不足 [threshold] 像素就不提交窗口更新。
     *
     * 触控采样率（120Hz+）远高于刷新率，每个 MOVE 都
     * `updateViewLayout` 会让同一帧内多次改窗口位置，视觉上就是抖动。
     * 低于 1px 的抖动直接丢弃，既省一次 IPC 也不会丢精度。
     */
    fun shouldCommit(dx: Float, dy: Float, threshold: Float = 1f): Boolean =
        kotlin.math.abs(dx) >= threshold || kotlin.math.abs(dy) >= threshold

    /** 像素 → 百分比（0..100，全精度 Double）。 */
    fun pct(px: Int, total: Int): Double = if (total > 0) 100.0 * px / total else 0.0

    /** 百分比 → 像素（把已有坐标作为拾取起点）。 */
    fun px(pct: Double, total: Int): Float = clamp((total * pct / 100.0).toFloat(), total)

    /** Locale 安全的小数格式化（强制小数点，逗号只用于分隔）。 */
    fun fmt(v: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)

    /** Locale 安全的 4 位小数格式化。 */
    fun fmt4(v: Double): String = fmt(v, 4)

    /** Locale 安全的 2 位小数格式化（动作摘要用）。 */
    fun fmt2(v: Double): String = fmt(v, 2)

    /** 归一化的像素矩形（l<r、t<b，最小 1px，整体夹在屏幕内）。 */
    data class Rect(val l: Int, val t: Int, val r: Int, val b: Int)

    /**
     * 归一化框选矩形：两角任意顺序、任意越界都收敛成合法矩形。
     * 供 `PickerRegion` 构造、ROI 表达式生成、绘制层共用唯一真源。
     */
    fun normalizeRegion(x1: Float, y1: Float, x2: Float, y2: Float, w: Int, h: Int): Rect {
        val maxX = max(0, w - 1)
        val maxY = max(0, h - 1)
        var l = min(x1, x2).roundToInt().coerceIn(0, maxX)
        var t = min(y1, y2).roundToInt().coerceIn(0, maxY)
        var r = max(x1, x2).roundToInt().coerceIn(0, maxX)
        var b = max(y1, y2).roundToInt().coerceIn(0, maxY)
        if (r <= l) {
            if (l < maxX) r = l + 1 else l = (r - 1).coerceAtLeast(0)
        }
        if (b <= t) {
            if (t < maxY) b = t + 1 else t = (b - 1).coerceAtLeast(0)
        }
        return Rect(l, t, r, b)
    }

    /** 四元组百分比表达式（Locale 安全），可直接填 `Region.expr`。 */
    fun regionExpr(rect: Rect, w: Int, h: Int): String = listOf(
        fmt4(pct(rect.l, w)), fmt4(pct(rect.t, h)),
        fmt4(pct(rect.r, w)), fmt4(pct(rect.b, h)),
    ).joinToString(",")
}

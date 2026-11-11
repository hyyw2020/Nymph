// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.design.NymphDesignTokens as T
import kotlin.math.abs

/**
 * 悬浮动作编辑器的视觉 token（v2.8.1 引入，**v2.9.0 起降级为投影层**）。
 *
 * ## v2.9.0 的变化：不再是真源
 *
 * v2.8.1 把颜色/圆角/间距/字号从 `GradientDrawable` 的散落硬编码收敛成了本对象，
 * 但**它仍是第二套定义**：Compose 主界面 `Theme.kt` 另有一份
 * `Color(0xFF5EEAD4)` 之类的字面量，同一个「警告色」两处各写一遍、值还可能
 * 漂移，纯靠约定维持。
 *
 * 现在所有值统一来自 [dev.nymph.auto.core.design.NymphDesignTokens]（core 纯 JVM
 * 唯一真源），本对象只做两件事：
 *  1. 把 `Long(0xAARRGGBB)` 投影成纯 View 层需要的 packed `Int`；
 *  2. 保留 v2.8.1 的全部公开常量名 / 方法名，让 `FloatingEditorPanel` 与既有
 *     单测零改动、视觉值一字节不变。
 *
 * 配色取向不变：**深墨蓝玻璃卡片 + 单一翡翠绿强调色**；语义色钉死、不启用
 * 动态取色（保留 v2.8.1 的正确决定）。
 */
object FloatingEditorTheme {

    // ------------------------------------------------------------ 色板（ARGB Int）
    val BG: Int = T.asArgb(T.GLASS_BG)
    val BG_ELEVATED: Int = T.asArgb(T.GLASS_ELEVATED)
    val STROKE: Int = T.asArgb(T.GLASS_STROKE)
    val STROKE_FOCUS: Int = T.asArgb(T.GLASS_STROKE_FOCUS)
    val ACCENT: Int = T.asArgb(T.ACCENT)
    val ACCENT_SOFT: Int = T.asArgb(T.GLASS_ACCENT_SOFT)
    val CONTROL: Int = T.asArgb(T.GLASS_CONTROL)
    val TEXT: Int = T.asArgb(T.TEXT_GLASS)
    val TEXT_DIM: Int = T.asArgb(T.TEXT_GLASS_DIM)
    val TEXT_MUTED: Int = T.asArgb(T.TEXT_GLASS_MUTED)
    val OK: Int = T.asArgb(T.OK)
    val WARN: Int = T.asArgb(T.WARNING)
    val DANGER: Int = T.asArgb(T.DANGER)
    val BALL: Int = T.asArgb(T.GLASS_BALL)
    val BALL_RING: Int = T.asArgb(T.ACCENT_BRIGHT)

    // ------------------------------------------------------------ 尺寸（dp）
    val RADIUS_CARD_DP: Int = T.RADIUS_CARD_DP
    val RADIUS_CONTROL_DP: Int = T.RADIUS_CONTROL_DP
    val RADIUS_FIELD_DP: Int = T.RADIUS_FIELD_DP
    val PAD_CARD_DP: Int = T.PAD_CARD_DP
    val PAD_CONTROL_H_DP: Int = T.PAD_CONTROL_H_DP
    val PAD_CONTROL_V_DP: Int = T.PAD_CONTROL_V_DP
    val GAP_DP: Int = T.GAP_DP
    val BAR_H_DP: Int = T.BAR_H_DP
    val FIELD_MIN_H_DP: Int = T.FIELD_MIN_H_DP
    val ICON_BTN_DP: Int = T.ICON_BTN_DP

    /** 状态行固定最小高度：窗口高度恒定，避免 `WRAP_CONTENT` 随文本行数跳动。 */
    val STATUS_MIN_DP: Int = T.STATUS_MIN_DP

    // ------------------------------------------------------------ 字号（sp）
    val TEXT_TITLE_SP: Float = T.TEXT_TITLE_SP
    val TEXT_BODY_SP: Float = T.TEXT_BODY_SP
    val TEXT_LABEL_SP: Float = T.TEXT_LABEL_SP
    val TEXT_HINT_SP: Float = T.TEXT_HINT_SP

    // ------------------------------------------------------------ 约束（委托真源）
    fun fontSizeHierarchyOk(): Boolean = T.fontSizeHierarchyOk()

    fun geometryOk(): Boolean = T.geometryOk()

    fun luminance(argb: Int): Double = T.luminance(argb)

    fun contrast(fg: Int, bg: Int): Double = abs(luminance(fg) - luminance(bg))

    fun textReadable(): Boolean = T.glassTextReadable()
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import dev.nymph.auto.core.design.NymphDesignTokens as T

/**
 * **所有浮窗的统一视觉投影**（v2.9.3，纯 Int 常量，无 Android 依赖）。
 *
 * ## 为什么要有它
 *
 * v2.9.2 之前，录制控制面板、运行悬浮球、坐标拾取手柄三套浮窗各写各的硬编码
 * 颜色字面量（面板红 `#EF4444`、手柄绿 `#059669`、悬浮球灰 `#6B7280`），视觉
 * 语言分裂。它们在 `NymphDesignTokens` 里没有归口，改一处不会联动。
 *
 * 本对象把 [T] 的浮窗色板与几何一次性投影成 `Int`（纯 View 层 `setColor` 用的
 * packed ARGB），三套浮窗只引用这里，**不允许在消费方另写颜色字面量**。改
 * `NymphDesignTokens` 一处，所有浮窗同时生效。
 *
 * ## 不破坏的契约
 *
 * - 不引入任何 Android / Compose 运行时依赖，保持纯 JVM、可单测、可锁死；
 * - 语义色钉死（录制红、执行绿、警告琥珀、强调绿），不让状态色随壁纸变化；
 * - 圆角 / 字号直接复用 [T] 的几何槽位，避免又出现一套独立尺寸。
 */
object OverlayTheme {

    // ------------------------------------------------------------ 表面 / 描边
    val PANEL_BG: Int = T.asArgb(T.OVERLAY_BG)
    val STROKE_SOFT: Int = T.asArgb(T.OVERLAY_STROKE_SOFT)
    val PANEL_DARK: Int = T.asArgb(T.OVERLAY_PANEL_DARK)

    // ------------------------------------------------------------ 控件
    val BTN_BG: Int = T.asArgb(T.OVERLAY_BTN)
    val BTN_TEXT: Int = T.asArgb(T.OVERLAY_TEXT)

    // ------------------------------------------------------------ 文本
    val TEXT: Int = T.asArgb(T.OVERLAY_TEXT)
    val TEXT_DIM: Int = T.asArgb(T.OVERLAY_TEXT_DIM)
    val TEXT_BRIGHT: Int = T.asArgb(T.OVERLAY_TEXT_BRIGHT)

    // ------------------------------------------------------------ 语义状态
    /** 录制中 / 危险（录制面板状态点、运行面板停止）。 */
    val DANGER: Int = T.asArgb(T.DANGER)
    /** 强危险（停止并保存按钮）。 */
    val DANGER_STRONG: Int = T.asArgb(T.OVERLAY_DANGER)
    /** 警告（能力边界告警、等待浮层）。 */
    val WARNING: Int = T.asArgb(T.WARNING)
    /** 强调绿（拾取手柄圆点 / 确认 / 工具条开启态）。 */
    val ACCENT: Int = T.asArgb(T.OVERLAY_ACCENT)
    /** 强调绿浅描边（手柄环）。 */
    val ACCENT_SOFT: Int = T.asArgb(T.OVERLAY_ACCENT_SOFT)
    /** 悬浮球就绪灰。 */
    val IDLE: Int = T.asArgb(T.OVERLAY_IDLE)
    /** 悬浮球执行中绿。 */
    val RUN: Int = T.asArgb(T.OVERLAY_RUN)

    // ------------------------------------------------------------ 几何（复用 token 槽位）
    val RADIUS_PANEL_DP: Int = T.RADIUS_CARD_DP
    val RADIUS_BTN_DP: Int = T.RADIUS_CONTROL_DP
}

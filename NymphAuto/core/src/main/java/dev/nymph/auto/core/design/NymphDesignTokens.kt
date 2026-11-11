// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

/**
 * **全工程唯一的视觉设计 token 真源**（v2.9.0，纯 JVM）。
 *
 * ## 为什么要有它
 *
 * v2.8.1 之前存在两套各自硬编码的配色：Compose 主界面在 `Theme.kt` 里写
 * `Color(0xFF5EEAD4)`，悬浮编辑器在 `FloatingEditorTheme` 里写 `0xFF10B981.toInt()`。
 * 「同一个语义（警告色）在两处各写一遍、值还不一样」是纯靠约定维持的，
 * 没有编译期约束，也没有单测能发现漂移。
 *
 * 本对象把所有语义槽收敛成**一处定义**：
 *  - Compose 侧 `NymphTheme` 用 [Color] 需要的 `Long`（0xAARRGGBB）字面量；
 *  - 悬浮层纯 View 侧 `FloatingEditorTheme` 用 [asArgb] 转成 packed `Int`；
 *  - 两侧引用的是**同一组常量**，改一处两边同时生效。
 *
 * ## 刻意保留的设计边界
 *
 * 1. **语义色钉死，不启用动态取色**：主界面「录制中」红、警告琥珀、错误红是
 *    状态语义，不能随壁纸变化。中性色也不做动态取色。
 * 2. **载体差异允许两套色板**：M3 `ColorScheme` 是不透明全屏界面，玻璃卡片是
 *    浮在别人 APP 上的半透明浮层，对比度需求不同，因此 ACCENT（翡翠绿）与
 *    ACCENT_BRIGHT（青绿）等槽位分开定义，但**不允许在消费方另写字面量**。
 * 3. **纯 JVM**：不依赖 `androidx.compose.ui.graphics` / Android，可在没有真机、
 *    没有 Compose 运行时的环境下用单测锁死对比度与层级。
 *
 * 值一律用 `Long`（0xAARRGGBB），与 Compose `Color(0xFF...)` 的字面量类型一致；
 * [asArgb] 是给纯 View 层用的唯一转换入口。
 */
object NymphDesignTokens {

    // ------------------------------------------------------------ 中性底 / 表面
    const val INK: Long = 0xFF070B10
    const val SURFACE_0: Long = 0xFF0D1319
    const val SURFACE_1: Long = 0xFF131B23
    const val SURFACE_2: Long = 0xFF1A242E
    const val OUTLINE: Long = 0xFF2B3947

    // ------------------------------------------------------------ 语义色（钉死）
    /** 主品牌青绿：Compose primary、悬浮小球亮环共用。 */
    const val ACCENT_BRIGHT: Long = 0xFF5EEAD4
    const val ACCENT_DEEP: Long = 0xFF0B4F49

    /** 悬浮玻璃卡片强调色（翡翠绿），比主界面青绿更沉，避免浮在亮色 APP 上刺眼。 */
    const val ACCENT: Long = 0xFF10B981

    const val WARNING: Long = 0xFFFBBF24
    /** M3 主界面错误色（偏暖、低饱和）。 */
    const val DANGER_SOFT: Long = 0xFFFF8A80
    /** 悬浮层危险色（更纯），载体不同故单独一槽。 */
    const val DANGER: Long = 0xFFF87171
    const val OK: Long = 0xFF34D399

    // ------------------------------------------------------------ 文本
    const val TEXT_PRIMARY: Long = 0xFFE6EDF3
    const val TEXT_ON_SURFACE_DIM: Long = 0xFF9FB0C0
    const val TEXT_GLASS: Long = 0xFFF1F5F9
    const val TEXT_GLASS_DIM: Long = 0xFFA2B0C4
    const val TEXT_GLASS_MUTED: Long = 0xFF6B7A90

    // ------------------------------------------------------------ 玻璃卡片专用
    /** 卡片底：69% 不透明，明显透出底层 APP。 */
    // v2.9.7：0xED(93%) → 0xD9(85%)。v2.9.8：0xD9(85%) → 0xB0(69%)。
    // 用户第二次反馈「悬浮窗还是这么大」：85% 不透明 + 近半屏面积，视觉上
    // 仍是一整块死黑板。继续降低 alpha 到底层内容可辨；RGB 完全不变，
    // luminance/contrast 只取 RGB 分量，文字可读性自检不受影响。
    const val GLASS_BG: Long = 0xB00C1422
    const val GLASS_ELEVATED: Long = 0xFF151F30
    const val GLASS_STROKE: Long = 0x2A35506E
    /** 输入框聚焦描边：刻意与 [OK] 同色（聚焦 = 可用），但不参与状态色去重集合。 */
    const val GLASS_STROKE_FOCUS: Long = 0xFF34D399
    const val GLASS_CONTROL: Long = 0x1A24344C
    const val GLASS_ACCENT_SOFT: Long = 0x2210B981
    const val GLASS_BALL: Long = 0xE6059669

    // ------------------------------------------------------------ 浮窗统一色板（v2.9.3）
    // 录制控制面板 / 运行悬浮球 / 坐标拾取手柄 三套浮窗此前各写各的硬编码字面量，
    // 视觉语言分裂。这里把浮窗专属色收敛成一处，由 app 层 OverlayTheme 投影为 Int，
    // 三套浮窗统一引用 —— 改一处，所有浮窗同时变。
    /** 浮窗暗玻璃底：录制面板、运行面板、拾取手柄工具条共用。 */
    const val OVERLAY_BG: Long = 0xF21F2430
    /** 浮窗中性按钮底（撤销/停止/收起/档位）。 */
    const val OVERLAY_BTN: Long = 0xFF374151
    /** 浮窗按钮主文字。 */
    const val OVERLAY_TEXT: Long = 0xFFE5E7EB
    /** 浮窗次文字 / 提示（默认态）。 */
    const val OVERLAY_TEXT_DIM: Long = 0xFF9CA3AF
    /** 浮窗高亮白（读数 / 工具条标签）。 */
    const val OVERLAY_TEXT_BRIGHT: Long = 0xFFF9FAFB
    /** 悬浮球就绪灰。 */
    const val OVERLAY_IDLE: Long = 0xFF6B7280
    /** 悬浮球执行中绿。 */
    const val OVERLAY_RUN: Long = 0xFF4ADE80
    /** 强危险（停止并保存按钮）。 */
    const val OVERLAY_DANGER: Long = 0xFFB91C1C
    /** 浮窗强调绿（拾取手柄圆点 / 确认 / 工具条开启态）。 */
    const val OVERLAY_ACCENT: Long = 0xFF059669
    /** 强调绿浅描边（手柄环 / 面板描边）。 */
    const val OVERLAY_ACCENT_SOFT: Long = 0xFF34D399
    /** 浮窗柔和描边（面板边框）。 */
    const val OVERLAY_STROKE_SOFT: Long = 0x33FFFFFF
    /** 拾取手柄展开工具条底。 */
    const val OVERLAY_PANEL_DARK: Long = 0xF2111827

    // ------------------------------------------------------------ Compose 专属容器色
    const val ON_PRIMARY: Long = 0xFF00201D
    const val ON_PRIMARY_CONTAINER: Long = 0xFFB9FFF4
    const val SECONDARY: Long = 0xFF93C5FD
    const val ON_SECONDARY: Long = 0xFF08243F
    const val SECONDARY_CONTAINER: Long = 0xFF16324C
    const val ON_SECONDARY_CONTAINER: Long = 0xFFD6E8FF
    const val ON_TERTIARY: Long = 0xFF2A1D00
    const val TERTIARY_CONTAINER: Long = 0xFF4A3600
    const val ON_TERTIARY_CONTAINER: Long = 0xFFFFE9AE
    const val SURFACE_CONTAINER_LOW: Long = 0xFF0A1015
    const val SURFACE_CONTAINER_LOWEST: Long = 0xFF05080C
    const val ON_ERROR: Long = 0xFF3B0906
    const val ERROR_CONTAINER: Long = 0xFF5C1A15
    const val ON_ERROR_CONTAINER: Long = 0xFFFFDAD5
    const val OUTLINE_VARIANT: Long = 0xFF1E2A36
    const val INVERSE_SURFACE: Long = 0xFFE6EDF3
    const val INVERSE_ON_SURFACE: Long = 0xFF11181F

    // ------------------------------------------------------------ 尺寸 / 字号
    const val RADIUS_CARD_DP = 18
    const val RADIUS_CONTROL_DP = 11
    const val RADIUS_FIELD_DP = 9

    // v2.9.8 紧凑化：面板宽 220→168dp 后，按原间距会把控件挤成竖排，
    // 因此同步收紧卡片内边距 / 控件间距 / 控件最小高度；字号层级不变。
    const val PAD_CARD_DP = 10
    const val PAD_CONTROL_H_DP = 8
    const val PAD_CONTROL_V_DP = 6
    const val GAP_DP = 5

    const val BAR_H_DP = 34
    const val FIELD_MIN_H_DP = 30
    const val ICON_BTN_DP = 28
    const val STATUS_MIN_DP = 18

    const val TEXT_TITLE_SP = 13f
    const val TEXT_BODY_SP = 12.5f
    const val TEXT_LABEL_SP = 11f
    const val TEXT_HINT_SP = 10.5f

    // ------------------------------------------------------------ 转换与约束

    /** Long(0xAARRGGBB) → packed ARGB Int，供纯 View 层 `setColor` 等使用。 */
    fun asArgb(value: Long): Int = value.toInt()

    /** sRGB 感知亮度近似（0~255）。 */
    fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** 前景/背景亮度差（0~255），用于构造性地保证可读性，而非事后猜。 */
    fun contrast(fg: Long, bg: Long): Double =
        kotlin.math.abs(luminance(asArgb(fg)) - luminance(asArgb(bg)))

    /** 玻璃卡片正文/次文字与卡片底必须有足够亮度差。 */
    fun glassTextReadable(): Boolean =
        contrast(TEXT_GLASS, GLASS_BG) > 100.0 && contrast(TEXT_GLASS_DIM, GLASS_BG) > 60.0

    /** 字号层级必须严格递减。 */
    fun fontSizeHierarchyOk(): Boolean =
        TEXT_TITLE_SP > TEXT_BODY_SP && TEXT_BODY_SP > TEXT_LABEL_SP && TEXT_LABEL_SP > TEXT_HINT_SP

    /** 圆角/间距为正且卡片圆角大于控件圆角。 */
    fun geometryOk(): Boolean =
        RADIUS_CARD_DP > RADIUS_CONTROL_DP && RADIUS_CONTROL_DP > 0 &&
            PAD_CARD_DP > 0 && GAP_DP > 0 && STATUS_MIN_DP > 0

    /** 语义状态色必须彼此可区分（不允许两槽悄悄写成同一个值）。 */
    fun semanticColorsDistinct(): Boolean =
        listOf(ACCENT, OK, WARNING, DANGER, ACCENT_BRIGHT).toSet().size == 5

    /** 浮窗色板必须彼此可区分（不允许 new 一个槽却写成和已有槽相同的值）。 */
    fun overlayTokensDistinct(): Boolean =
        listOf(
            OVERLAY_BG, OVERLAY_BTN, OVERLAY_TEXT, OVERLAY_TEXT_DIM, OVERLAY_TEXT_BRIGHT,
            OVERLAY_IDLE, OVERLAY_RUN, OVERLAY_DANGER, OVERLAY_ACCENT,
            OVERLAY_ACCENT_SOFT, OVERLAY_STROKE_SOFT, OVERLAY_PANEL_DARK,
        ).toSet().size == 12

    /** 浮窗强调绿与品牌绿必须不同（刻意分两套，避免浮在亮色 APP 上刺眼）。 */
    fun overlayAccentDistinctFromAccent(): Boolean = OVERLAY_ACCENT != ACCENT
}

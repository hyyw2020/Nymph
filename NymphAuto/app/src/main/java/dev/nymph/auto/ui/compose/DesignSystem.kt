package dev.nymph.auto.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Nymph UI 设计系统 —— "Quiet Precision"（安静的精确）。
 *
 * ## 为什么单独一层
 *
 * `NymphDesignTokens` 是 core 纯 JVM 的**唯一语义色真源**，被单测逐值锁死
 * （字号 13/12.5/11/10.5、间距 10/8/6/5、圆角与对比度关系），它服务的是
 * 「主界面 + 纯 View 悬浮层同源」，改动会破坏构造性色板约束。
 *
 * 本对象是 **Compose 主界面的排版与节奏真源**：它与 token 不冲突——token 管
 * 语义色与浮窗尺寸，本对象管主界面「看起来的样子」。二者分工明确：
 *  - 颜色仍从 `NymphTheme` 的 `colorScheme` 取（其值来自 token 投影）；
 *  - 排版、间距、圆角、字重在此集中定义，避免每个 composable 各写 sp/dp。
 *
 * ## 设计判断（art direction）
 *
 * 这是 **Operate 型工具界面**（用户在别的 APP 之上完成「录制 → 编辑 → 运行」），
 * 不是展示型官网。因此：
 *  - **扫描性 > 表达**：清晰的层级、稳定的节奏、可预期的位置；
 *  - **品牌在精确细节**：青绿强调色克制使用，不作为大面积填充；
 *  - **拒绝 AI 模板感**：不用等尺寸卡片网格、不用彩色小标题（eyebrow）、
 *    不用圆角盒子单侧竖条、不用渐变文字、不用 emoji 当图标。
 *
 * craft floor（对照 impeccable）：
 *  - 对比度：正文/次文字 ≥4.5:1（由 token 的构造性约束 + colorScheme 保证）；
 *  - 间距：组内紧凑、组间宽松，标题上方留白 > 下方；
 *  - 排版：明显的 scale 与 weight 阶梯，行高可读，标题负字距收紧；
 *  - 状态：每个可交互件都有 enabled/disabled/loading 语义；
 *  - 对齐：左右边缘一致，卡片内部节奏统一。
 */
object Ds {

    // ------------------------------------------------------------ 间距节奏（4dp 基线）
    /** 组内紧间距：图标与文字、标签与值。 */
    val XS = 4.dp
    /** 控件内间距。 */
    val SM = 8.dp
    /** 元素之间的标准间距。 */
    val MD = 12.dp
    /** 卡片内边距 / 分组间距。 */
    val LG = 16.dp
    /** 屏幕边缘 / 大分组之间。 */
    val XL = 24.dp
    /** 二级页顶部留白。 */
    val XXL = 32.dp

    // ------------------------------------------------------------ 圆角
    val RadiusSm = RoundedCornerShape(8.dp)
    val RadiusMd = RoundedCornerShape(12.dp)
    val RadiusLg = RoundedCornerShape(18.dp)
    val RadiusPill = RoundedCornerShape(50)

    // ------------------------------------------------------------ 排版阶梯
    // 字号刻意拉开：展示级 / 标题 / 正文 / 标签 每级都有清晰的 scale 与 weight 差。
    // 字重而非颜色承担强调（craft floor：emphasis comes from weight or size）。

    /** 页级大标题：稀疏字距、紧行高、半粗。 */
    val Display = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.02).em,
    )

    /** 区块标题：比正文明显大一档，负字距收紧。 */
    val Title = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
    )

    /** 卡片标题 / 列表项主文字。 */
    val ItemTitle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 正文：标准阅读尺寸。 */
    val Body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 次正文：说明、副标题。 */
    val BodySecondary = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 标签 / 元信息：小字但保持可读。 */
    val Label = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.01.em,
    )

    /** 数值 / 代码：等宽，避免数字跳动（设计稿的 mono 元信息规范）。 */
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.0.em,
    )

    // ------------------------------------------------------------ 行高样式
    /** 标题统一的行高居中策略，避免不同字号下基线漂移。 */
    val TitleLineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
}

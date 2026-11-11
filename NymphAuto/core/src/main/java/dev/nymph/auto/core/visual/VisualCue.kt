// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.visual

/**
 * 动作可视化的数据模型与纯配置表（纯 JVM，不引用任何 Android 类）。
 *
 * 设计目标：把「手势 → 视觉元素组合」做成一张只读配置表，新增一种手势
 * 只需在 [VisualConfig.specFor] 里加一行，渲染代码完全不动。渲染端
 * （app 层 VisualizerView）只消费 [VisualSpec] 里的原子组合，不认识具体手势。
 *
 * 坐标口径：所有点位都是**引擎实际派发的最终像素坐标**（已含 jitter、
 * offset、命中修正），由 app 层 ScriptRunner 在 gesture.dispatch() 前填入。
 * 本模块不做任何坐标猜测、不从无障碍事件反推触摸位置。
 */

/** 手势类别（决定用哪套原子组合）。 */
enum class VisualKind {
    TAP,          // 单击 / 多击 / 各类找图找色命中后的点击
    LONG_PRESS,   // 长按：进度环跟手
    SWIPE,        // 滑动 / 曲线 / 轨迹回放：轨迹带 + 箭头
    MULTI_TOUCH,  // 双指捏合 / 多指：多圆点 + 编号
    PREVIEW,      // 落点预告：虚线细环 + 倒计时（默认关闭）
}

/** 五个原子图形，所有特效都由它们组合。 */
enum class VisualAtom { DOT, RING, TRAIL, ARROW, BADGE }

/**
 * 一个手势的视觉规格（配置表的一行）。时长不在这里——由真实动作时长
 * 决定（长按进度跟手、滑动与手势同长），写进 [VisualCue.durationMs]。
 */
data class VisualSpec(
    val atoms: List<VisualAtom>,
    val ringCount: Int = 0,
    val ringStaggerMs: Long = 0L,
    val showBadge: Boolean = false,
)

/**
 * 一次动作的可视化事件。
 *
 * @param createdAtMs 由调用方用 SystemClock.elapsedRealtime() 填入，
 *        与渲染端的进度计算使用同一时间源；本模块自身不读系统时间。
 * @param pointsPx 成对存放 x,y 的像素坐标。约定：
 *        - TAP/LONG_PRESS/PREVIEW：2 个 float（单点）
 *        - SWIPE：>=4 个 float；恰好 6 个时第 3、4 个视为二次贝塞尔控制点
 *        - MULTI_TOUCH：每 2 个 float 代表一指的起点
 */
data class VisualCue(
    val id: Long,
    val kind: VisualKind,
    val createdAtMs: Long,
    val durationMs: Long,
    val pointsPx: FloatArray,
    val step: Int = 0,
    val totalSteps: Int = 0,
)

/**
 * 可视化总配置（开关 + 预算上限），纯数据表。默认值保证「不改变原有功能」：
 * 总开关关闭、预告关闭，此时 ScriptRunner 不产生任何回调、不增加任何延迟。
 */
data class VisualConfig(
    val enabled: Boolean = false,
    val tapEnabled: Boolean = true,
    val trailEnabled: Boolean = true,
    val stepBadgeEnabled: Boolean = true,
    val previewEnabled: Boolean = false,
    val restrained: Boolean = false,
    val previewMs: Long = 350L,
    val tapMs: Long = 320L,
    val maxCues: Int = 16,
    val maxFingers: Int = 4,
    val maxTrailPoints: Int = 24,
) {
    /** 该手势当前是否允许出特效（配置表驱动，不改渲染代码）。 */
    fun accepts(kind: VisualKind): Boolean = when (kind) {
        VisualKind.TAP, VisualKind.LONG_PRESS -> tapEnabled
        VisualKind.SWIPE, VisualKind.MULTI_TOUCH -> trailEnabled
        VisualKind.PREVIEW -> true
    }

    companion object {
        /** 手势 → 原子组合的纯配置表；新增手势只加一行。 */
        fun specFor(kind: VisualKind): VisualSpec = when (kind) {
            VisualKind.TAP -> VisualSpec(
                atoms = listOf(VisualAtom.DOT, VisualAtom.RING),
                ringCount = 2, ringStaggerMs = 60L, showBadge = false,
            )
            VisualKind.LONG_PRESS -> VisualSpec(
                atoms = listOf(VisualAtom.DOT, VisualAtom.RING),
                ringCount = 1, showBadge = false,
            )
            VisualKind.SWIPE -> VisualSpec(
                atoms = listOf(VisualAtom.DOT, VisualAtom.TRAIL, VisualAtom.ARROW),
                showBadge = false,
            )
            VisualKind.MULTI_TOUCH -> VisualSpec(
                atoms = listOf(VisualAtom.DOT, VisualAtom.RING, VisualAtom.BADGE),
                ringCount = 1, showBadge = true,
            )
            VisualKind.PREVIEW -> VisualSpec(
                atoms = listOf(VisualAtom.RING, VisualAtom.BADGE),
                ringCount = 1, showBadge = true,
            )
        }
    }
}

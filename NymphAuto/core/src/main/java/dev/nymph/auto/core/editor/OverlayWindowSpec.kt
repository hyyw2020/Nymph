// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

/**
 * **系统悬浮编辑器窗口规格的单一真源**（v2.9.0，纯 JVM）。
 *
 * ## 回应的缺陷
 *
 * v2.8.1 用「flags 全程恒定」修掉了抽搐，但：
 *  1. 这个约束只写在注释里，靠自觉——没有任何代码或测试能阻止后来者
 *     在 `setCollapsed()` 里顺手加一个 `FLAG_NOT_TOUCHABLE`。
 *  2. 文档与代码其实不一致：`setInputFocusable()` 为了让 `EditText` 能弹键盘，
 *     会临时把 flags 从 `NOT_FOCUSABLE|NOT_TOUCH_MODAL` 切成 `NOT_TOUCH_MODAL`。
 *     README/ADR-012 却写「flags 全程恒定」。这是**必要的受控切换**，
 *     但表述必须诚实：不是「恒定」，而是「只允许两种受控状态，且只由输入焦点触发」。
 *
 * 本对象把位掩码与允许的状态集合抽成纯数据 + 纯函数，`FloatingEditorService`
 * 的 `baseParams()` / `setInputFocusable()` 都从这里取值，任何新增 flag 都必须
 * 先过 [isAllowed]，否则编译期/单测直接失败。
 *
 * ## 为什么放 core
 *
 * 不依赖 `android.view.WindowManager`，可在 JVM 单测里断言「拖动提交只改 x/y、
 * 不动 flags/type/format」——这正是抽搐最容易回归的地方，而本环境没有真机、
 * 没有 Robolectric，纯 JVM 断言是唯一能自动跑的防线。
 */
object OverlayWindowSpec {

    // 位掩码值与 AOSP WindowManager.LayoutParams 保持一致（不 import Android）。
    const val FLAG_NOT_FOCUSABLE = 0x00000008
    const val FLAG_NOT_TOUCH_MODAL = 0x00000020

    /** 默认/常规状态：不抢焦点、不弹键盘，面板外触摸透传给底层 APP。 */
    const val STANDARD_FLAGS = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL

    /**
     * 用户主动点输入框时的临时状态：允许聚焦以弹软键盘，但**仍保持
     * NOT_TOUCH_MODAL**（面板外照常操作底层 APP）。失焦立即回到 [STANDARD_FLAGS]。
     */
    const val INPUT_FLAGS = FLAG_NOT_TOUCH_MODAL

    /** 唯一允许的两个 flags 取值。 */
    fun isAllowed(flags: Int): Boolean = flags == STANDARD_FLAGS || flags == INPUT_FLAGS

    /**
     * 按「输入框是否获焦」返回唯一合法的 flags。
     * 这是 `FloatingEditorService` 唯一应当使用的 flags 来源。
     */
    fun flagsFor(inputFocused: Boolean): Int = if (inputFocused) INPUT_FLAGS else STANDARD_FLAGS

    /** 切换是否合法：从任一允许态到另一允许态，且确实发生了焦点变化。 */
    fun isLegalTransition(from: Int, to: Int): Boolean =
        isAllowed(from) && isAllowed(to)

    /**
     * 窗口在某一帧的完整规格快照（纯数据，供单测断言）。
     * type/format/width/height 属于布局参数，拖动时**不得**改变。
     */
    data class Frame(
        val x: Int,
        val y: Int,
        val flags: Int,
        val type: Int,
        val format: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * 一帧拖动提交是否只改了位置：x/y 可变，其余字段必须逐字不变，
     * 且 flags 必须是允许态之一。返回 false 就应当丢弃这次 relayout。
     */
    fun isPositionOnlyUpdate(before: Frame, after: Frame): Boolean =
        after.flags == before.flags &&
            after.type == before.type &&
            after.format == before.format &&
            after.width == before.width &&
            after.height == before.height &&
            isAllowed(after.flags) &&
            (after.x != before.x || after.y != before.y)

    /** 构造一次「仅位移」的新规格；供调用方替换手写赋值。 */
    fun moved(before: Frame, newX: Int, newY: Int): Frame = before.copy(x = newX, y = newY)
}

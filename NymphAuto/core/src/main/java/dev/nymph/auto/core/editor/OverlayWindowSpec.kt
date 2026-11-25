// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

/**
 * **系统悬浮编辑器窗口规格的单一真源**（v2.9.0，纯 JVM）。
 *
 * ## 回应的缺陷
 *
 * v2.8.1 用「flags 全程恒定」修掉了抽搐，但：
 *  1. 这个约束只写在注释里，靠自觉——没有任何代码或测试能阻止后来者
 *     在 `setCollapsed()` 里一并添加一个 `FLAG_NOT_TOUCHABLE`。
 *  2. 文档与代码存在不一致：`setInputFocusable()` 为了让 `EditText` 能弹键盘，
 *     会临时把 flags 从 `NOT_FOCUSABLE|NOT_TOUCH_MODAL` 切成 `NOT_TOUCH_MODAL`。
 *     README/ADR-012 却写「flags 全程恒定」。这是**必要的受控切换**，
 *     但表述必须按实际状态：不是「恒定」，而是「只允许两种受控状态，且只由输入焦点触发」。
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
    // v2.9.18：布局坐标空间标志。FLAG_LAYOUT_IN_SCREEN 使窗口以**全屏绝对坐标**
    // 布局（而非所属 APP 窗口坐标），在 OEM ROM / 游戏空间等第三方悬浮层叠加场景下，
    // 可消除「视觉位置」与「触摸命中区域」的错位——该错位正是悬浮窗「可见但位移交互
    // 未生效」的根因（坐标拾取器自带这两个标志，故拾取器可位移、运行球与编辑面板此前不可位移）。
    // FLAG_LAYOUT_NO_LIMITS 与拾取器配置保持一致，允许窗口抵近屏幕边缘而不被裁剪。
    const val FLAG_LAYOUT_IN_SCREEN = 0x00000100
    const val FLAG_LAYOUT_NO_LIMITS = 0x00000200

    /** 默认/常规状态：不抢焦点、不弹键盘，面板外触摸透传给底层 APP，全屏绝对坐标布局。 */
    const val STANDARD_FLAGS =
        FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS

    /**
     * 用户主动点输入框时的临时状态：允许聚焦以弹软键盘，但**仍保持
     * NOT_TOUCH_MODAL**（面板外照常操作底层 APP）。失焦立即回到 [STANDARD_FLAGS]。
     * 布局坐标空间标志与原状态一致，切换过程中坐标空间不变。
     */
    const val INPUT_FLAGS = FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS

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

    // ------------------------------------------------------------ v2.9.21 输入焦点位级切换

    /**
     * 在当前窗口标志位上按位切换「是否允许聚焦」，保留其它全部标志位。
     *
     * v2.8.1～v2.9.20 的 `setInputFocusable` 用 [flagsFor] 整体覆盖并先过
     * [isLegalTransition] 的严格相等闸。系统在 `addView` 之后可能向
     * `LayoutParams.flags` 追加标志位（OEM ROM 常见），一旦当前 flags 不再
     * 逐位等于 [STANDARD_FLAGS] / [INPUT_FLAGS]，切换被判定为非法而直接返回，
     * 窗口始终带 [FLAG_NOT_FOCUSABLE]，输入框无法取得窗口焦点，输入法无法唤起。
     *
     * 本函数只增删 [FLAG_NOT_FOCUSABLE] 一位，其余位（含系统追加位）逐位保留，
     * 因此不受「严格相等」影响；折叠/展开不改 flags 的抗抽搐约束保持不变，
     * 且切换前后仍处于「非模态透传」语义（[FLAG_NOT_TOUCH_MODAL] 不被移除）。
     */
    fun focusableFlags(current: Int, focusable: Boolean): Int =
        if (focusable) current and FLAG_NOT_FOCUSABLE.inv()
        else current or FLAG_NOT_FOCUSABLE

    /** 当前标志位是否允许窗口聚焦（供界面状态与诊断使用）。 */
    fun isFocusable(current: Int): Boolean = current and FLAG_NOT_FOCUSABLE == 0

    /** 保真校验：位级切换不得改动除 [FLAG_NOT_FOCUSABLE] 外的任何位。 */
    fun isFocusToggleOnly(before: Int, after: Int): Boolean =
        before xor after == FLAG_NOT_FOCUSABLE
}

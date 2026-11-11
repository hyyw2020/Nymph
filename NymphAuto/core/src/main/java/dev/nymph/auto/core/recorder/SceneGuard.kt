// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import dev.nymph.auto.core.model.ActionType

/**
 * 回放起始界面守卫（纯 JVM，不引用 Android）。
 *
 * ## 为什么需要
 *
 * 被动无障碍录制只记录「做了什么」，不记录「在哪个界面做的」。真机录屏中，
 * 用户在应用抽屉里连续翻页录制，回放时却停在桌面/日历，15 次翻页全部作用在
 * 错误界面上（月份被翻动、误触打开内容页）——脚本每一步都"执行成功"，
 * 但整体与实际需求完全不符。
 *
 * 无障碍事件无法还原滚动位置/页面状态（这是能力边界，不猜），因此本守卫只做
 * 一件诚实的事：**在动作开始前比对录制起始包名与当前前台包名，不一致就明确
 * 拦截并提示用户回到正确界面**，而不是把一堆动作打在错误界面上还报「成功」。
 *
 * ## v2.9.1 真机补强：同包名 ≠ 同界面
 *
 * v2.5 的守卫只比包名，但 2026-11-10 真机调试包证明它在最典型的场景里
 * **结构性失效**：用户在「应用抽屉」录制 97 个 SCROLL → 编译成 38 个 SWIPE，
 * 回放却停在桌面主屏 / 日历小组件 / 搜索历史页（还误触搜索框弹出键盘）。
 * 应用抽屉、桌面主屏、搜索页**同属一个 launcher 包名**，于是
 * `recorded == current` → [Decision.PROCEED] 静默放行；而 `rootInActiveWindow()`
 * 取不到包名、或回放瞬间前台仍是 NymphAuto 自己时 → [Decision.SKIP] 同样静默放行。
 * 两条路径都不会输出任何提示，用户只看到「命中 0、失败 0」却不知动作全打错了地方。
 *
 * 因此新增 [hasPositionalActions] 与 [Decision.PROCEED_WITH_WARNING]：当脚本含
 * **不带目标校验、直接按坐标派发的手势**（翻页/点击等）时，即便包名一致或无法判定，
 * 也必须把「被动录制无法还原页面与滚动位置、请确认当前已回到录制起始界面」
 * 作为**执行前必达日志**送达用户。刻意不阻断执行——用户可能确实已手动回到正确
 * 界面，而同包名在原理上无法证明界面一致，阻断会误伤正常回放；误报一条警告的
 * 代价远小于拦死脚本。老调用（默认 false）行为逐字不变。
 *
 * 决策与 Android 完全解耦，便于单测；app 层只负责取包名并执行 [Decision]。
 */
object SceneGuard {

    enum class Decision {
        /** 不校验：老脚本无起始包名、当前包名取不到、或录制即在桌面（无法判定）。 */
        SKIP,
        /** 界面一致，正常执行。 */
        PROCEED,
        /** 放行，但必须把界面风险作为执行前警告送达用户（v2.9.1）。 */
        PROCEED_WITH_WARNING,
        /** 界面不一致且目标可启动：建议先拉起目标应用一次。 */
        RESTORE_APP,
        /** 界面不一致但目标是桌面/系统组件（无启动入口）：只能提示用户手动切回。 */
        REQUIRE_MANUAL,
    }

    /**
     * 不带目标校验、直接按坐标派发的手势：一旦界面/滚动位置不对，就会打在错误
     * 控件上（真机现象：翻页手势误触搜索框、弹出输入法）。查找类动作
     * （CLICK_IMAGE/TEXT/COLOR/NODE/AI）有目标校验兜底，不计入本集合。
     */
    val POSITIONAL_TYPES: Set<ActionType> = setOf(
        ActionType.SWIPE, ActionType.CURVE, ActionType.REPLAY,
        ActionType.MULTI_FINGER, ActionType.PINCH_IN, ActionType.PINCH_OUT,
        ActionType.TAP, ActionType.LONG_PRESS, ActionType.MULTI_TAP,
    )

    /** 脚本是否含依赖「当前页面/滚动位置」的直接派发手势（纯 JVM，供单测与 app 层共用）。 */
    fun hasPositionalActions(types: List<ActionType>): Boolean = types.any { it in POSITIONAL_TYPES }

    data class Result(
        val decision: Decision,
        val message: String = "",
    )

    /**
     * 位置敏感脚本的执行前警告（core 单一真源，app 层复用，避免两处各写一遍）。
     * 如实说明能力边界，并给出可操作的动作；[recorded] 为空时也保持语句通顺。
     */
    fun positionalWarning(recorded: String, why: String): String {
        val pkgHint = if (recorded.isBlank()) "" else
            "，同包名不等于同界面：应用抽屉 / 桌面主屏 / 搜索页可能都是 $recorded"
        return "本脚本含翻页/点击等坐标手势，被动无障碍录制无法记录当时的页面与滚动位置" +
            "（$why$pkgHint）。" +
            "若当前不在录制起始界面，请停止回放并手动回到该界面及滚动位置后重试，" +
            "否则手势会作用在错误界面上。"
    }

    /**
     * @param recordedPackage 脚本记录的录制起始包名（空 = 老脚本，跳过校验）。
     * @param currentPackage  回放开始时的前台包名（空 = 取不到，跳过校验）。
     * @param launchable      该包名是否有标准启动入口（由 app 层传入 TargetPackages 判定）。
     * @param selfPackage     本应用包名：录制/回放界面是自家 UI 时不算「录制的界面」。
     * @param hasPositionalActions 脚本是否含直接派发的坐标手势；默认 false = v2.5 旧行为。
     */
    fun decide(
        recordedPackage: String,
        currentPackage: String?,
        launchable: (String) -> Boolean,
        selfPackage: String = "dev.nymph.auto",
        hasPositionalActions: Boolean = false,
    ): Result {
        val recorded = recordedPackage.trim()
        val current = currentPackage?.trim().orEmpty()
        // 老脚本无起始包名：无论是否含位置动作都保持 SKIP，行为与 v2.4.0 完全一致。
        if (recorded.isEmpty()) return Result(Decision.SKIP)
        if (current.isEmpty()) return if (hasPositionalActions) {
            Result(
                Decision.PROCEED_WITH_WARNING,
                positionalWarning(recorded, "当前取不到前台包名，无法核对界面"),
            )
        } else Result(Decision.SKIP)
        if (recorded == selfPackage || current == selfPackage) return if (hasPositionalActions) {
            Result(
                Decision.PROCEED_WITH_WARNING,
                positionalWarning(recorded, "回放启动时前台仍是 NymphAuto 自身界面"),
            )
        } else Result(Decision.SKIP)
        if (recorded.equals(current, ignoreCase = true)) return if (hasPositionalActions) {
            Result(
                Decision.PROCEED_WITH_WARNING,
                positionalWarning(recorded, "当前包名与录制时一致，但无法区分同一应用内的不同页面"),
            )
        } else Result(Decision.PROCEED)

        val canLaunch = launchable(recorded)
        return if (canLaunch) {
            Result(
                Decision.RESTORE_APP,
                "录制起始于 $recorded，当前为 $current；界面不一致，先回到目标应用再执行",
            )
        } else {
            Result(
                Decision.REQUIRE_MANUAL,
                "录制起始于 $recorded（桌面/系统界面，无启动入口），当前为 $current；" +
                    "被动录制无法还原当时的页面与滚动位置，请手动回到录制时的界面再运行",
            )
        }
    }
}

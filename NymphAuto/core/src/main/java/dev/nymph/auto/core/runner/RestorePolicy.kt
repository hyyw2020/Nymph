// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

/**
 * 应用回切失败后的处置策略（core 纯 JVM 单一真源，v2.9.33）。
 *
 * ## 背景
 *
 * 场景守卫裁定 `RESTORE_APP` 后，执行层调用 `AppLauncher.run` 尝试拉起录制起始应用。
 * `com.hihonor.gameassistant` 一类厂商聚合应用没有可被 `getLaunchIntentForPackage` 解析的
 * 标准入口，或受 Android 11+ 包可见性限制，拉起必然失败。旧实现把「拉起失败」直接等同于
 * 「中止脚本」，用户只能手动进入目标应用并反复重跑——真机日志出现同因中止二十余次。
 *
 * ## 策略（本对象唯一裁决）
 *
 * 拉起失败后重新读取前台包名，再决定：
 *
 * - [Outcome.PROCEED]：用户已手动进入录制应用（当前包名 == 录制包名），消除拉起失败的影响后
 *   放行；若脚本含坐标手势，文案追加界面一致性提示。
 * - [Outcome.ABORT]：确实不在目标应用且无法拉起，中止；文案一次性点名要打开的应用，
 *   不再出现「请手动打开后重试」这种会被反复触发的模糊循环话术。
 *
 * 纯 JVM、无 Android 依赖：谓词与文案均可单测，app 层只负责重新读取包名并执行 [Outcome]。
 */
object RestorePolicy {

    enum class Outcome { PROCEED, ABORT }

    data class Result(
        val outcome: Outcome,
        val message: String,
    )

    /**
     * @param recordedPackage 录制起始包名。
     * @param currentPackage  拉起失败后重新读取的前台包名；null / 空白表示取不到。
     * @param hasPositionalActions 脚本是否含依赖页面与滚动位置的直接派发手势。
     */
    fun decide(
        recordedPackage: String,
        currentPackage: String?,
        hasPositionalActions: Boolean,
    ): Result {
        val recorded = recordedPackage.trim()
        val current = currentPackage?.trim().orEmpty()
        val alreadyThere = recorded.isNotEmpty() &&
            recorded.equals(current, ignoreCase = true)

        if (alreadyThere) {
            val msg = if (hasPositionalActions) {
                "已检测到当前处于录制应用（$recorded），继续运行；本脚本含坐标手势，" +
                    "请手动确认页面与滚动位置与录制时一致"
            } else {
                "已检测到当前处于录制应用（$recorded），继续运行"
            }
            return Result(Outcome.PROCEED, msg)
        }

        val who = if (recorded.isEmpty()) "录制时的应用" else recorded
        val extra = if (hasPositionalActions) {
            "该脚本含坐标手势，界面或滚动位置不一致会导致误触；请一并确认页面状态。"
        } else {
            "进入后重新运行，脚本会自动检测并继续。"
        }
        return Result(
            Outcome.ABORT,
            "无法自动打开录制时的应用（$who），已中止。请手动打开 $who；$extra",
        )
    }
}

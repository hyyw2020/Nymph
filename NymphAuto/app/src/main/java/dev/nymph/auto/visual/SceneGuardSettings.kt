// SPDX-License-Identifier: MIT
package dev.nymph.auto.visual

import android.content.Context
import dev.nymph.auto.service.RunnerService

/**
 * v2.5.0「回放界面校验」开关。
 *
 * 被动无障碍录制不记录「当时在哪个界面」，回放时若停在不同界面，所有坐标
 * 动作都会打在错误界面上（真机录屏：应用抽屉里录的翻页，回放却在日历里
 * 翻月份并误触打开内容页）。开启后回放前比对录制起始包名，不一致就明确
 * 中止并告知，不再「跑完还报成功」。
 *
 * 默认开启；老脚本 / 手写脚本没有起始包名，守卫自动跳过，行为与 v2.4.0
 * 完全一致。关闭此开关可退回旧行为（不校验、不中止）。
 */
object SceneGuardSettings {

    private const val K_ENABLED = "scene_guard_enabled"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(RunnerService.PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, true)

    fun save(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(K_ENABLED, enabled).apply()
    }
}

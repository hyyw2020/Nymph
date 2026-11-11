// SPDX-License-Identifier: MIT
package dev.nymph.auto.visual

import android.content.Context
import dev.nymph.auto.core.visual.VisualConfig
import dev.nymph.auto.service.RunnerService

/**
 * 动作可视化的开关持久化。
 *
 * 复用 RunnerService 既有的 "nymphauto" SharedPreferences，不引入 DataStore、
 * 不新增任何权限、不新增任何文件存储。默认值保证「彻底关闭」：默认关闭时
 * 引擎不产生任何回调、不增加任何延迟，与 v2.3.0 行为完全一致。
 *
 * 运行中的开关变更在「下一个动作」生效：RunnerService 把 [load] 作为
 * configProvider 传给 VisualizerLayer，每个 cue 入队时刷新一次配置，
 * 成本可忽略，也不需要监听器/广播。
 */
object VisualSettings {

    private const val K_ENABLED = "visual_enabled"
    private const val K_TAP = "visual_tap"
    private const val K_TRAIL = "visual_trail"
    private const val K_BADGE = "visual_badge"
    private const val K_PREVIEW = "visual_preview"
    private const val K_RESTRAINED = "visual_restrained"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(RunnerService.PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): VisualConfig {
        val p = prefs(ctx)
        return VisualConfig(
            enabled = p.getBoolean(K_ENABLED, false),
            tapEnabled = p.getBoolean(K_TAP, true),
            trailEnabled = p.getBoolean(K_TRAIL, true),
            stepBadgeEnabled = p.getBoolean(K_BADGE, true),
            previewEnabled = p.getBoolean(K_PREVIEW, false),
            restrained = p.getBoolean(K_RESTRAINED, false),
        )
    }

    fun save(ctx: Context, cfg: VisualConfig) {
        prefs(ctx).edit()
            .putBoolean(K_ENABLED, cfg.enabled)
            .putBoolean(K_TAP, cfg.tapEnabled)
            .putBoolean(K_TRAIL, cfg.trailEnabled)
            .putBoolean(K_BADGE, cfg.stepBadgeEnabled)
            .putBoolean(K_PREVIEW, cfg.previewEnabled)
            .putBoolean(K_RESTRAINED, cfg.restrained)
            .apply()
    }
}

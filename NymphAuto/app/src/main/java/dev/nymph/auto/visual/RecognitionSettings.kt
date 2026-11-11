// SPDX-License-Identifier: MIT
package dev.nymph.auto.visual

import android.content.Context
import dev.nymph.auto.core.runner.PollMode
import dev.nymph.auto.core.runner.RecognitionTuning
import dev.nymph.auto.service.RunnerService

/**
 * 识别调度参数持久化（v2.7.0）。
 *
 * 复用 RunnerService 既有 "nymphauto" SharedPreferences，不新增权限/文件。
 * 默认是帧同步 33ms + 运动预测 + 窄 ROI（相对 v2.6.0 的 300ms 明显提速）；
 * 设置页可一键退回 [RecognitionTuning.LEGACY] 的旧行为。
 */
object RecognitionSettings {

    private const val K_MODE = "recog_poll_mode"
    private const val K_CUSTOM = "recog_custom_ms"
    private const val K_MOTION = "recog_motion"
    private const val K_NARROW = "recog_narrow"
    private const val K_RETRY = "recog_retry_window"
    private const val K_COND_WAIT = "recog_cond_wait"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(RunnerService.PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): RecognitionTuning {
        val p = prefs(ctx)
        val mode = runCatching {
            PollMode.valueOf(p.getString(K_MODE, PollMode.FRAME_33.name) ?: PollMode.FRAME_33.name)
        }.getOrDefault(PollMode.FRAME_33)
        return RecognitionTuning(
            pollMode = mode,
            customIntervalMs = p.getLong(K_CUSTOM, 33L),
            motionPrediction = p.getBoolean(K_MOTION, true),
            narrowRoi = p.getBoolean(K_NARROW, true),
            retryWindowMs = p.getLong(K_RETRY, 2000L),
            conditionFailWaitMs = p.getLong(K_COND_WAIT, 0L),
        ).sanitized()
    }

    fun save(ctx: Context, cfg: RecognitionTuning) {
        val c = cfg.sanitized()
        prefs(ctx).edit()
            .putString(K_MODE, c.pollMode.name)
            .putLong(K_CUSTOM, c.customIntervalMs)
            .putBoolean(K_MOTION, c.motionPrediction)
            .putBoolean(K_NARROW, c.narrowRoi)
            .putLong(K_RETRY, c.retryWindowMs)
            .putLong(K_COND_WAIT, c.conditionFailWaitMs)
            .apply()
    }
}

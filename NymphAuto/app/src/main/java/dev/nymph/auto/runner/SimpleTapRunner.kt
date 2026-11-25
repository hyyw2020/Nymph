// SPDX-License-Identifier: MIT
package dev.nymph.auto.runner

import android.graphics.Path
import android.os.SystemClock
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.simpletap.ChannelDecision
import dev.nymph.auto.core.simpletap.InjectionChannel
import dev.nymph.auto.core.simpletap.InjectionPolicy
import dev.nymph.auto.core.simpletap.SimpleTapPlan
import dev.nymph.auto.core.simpletap.SimpleTapSchedule
import dev.nymph.auto.engine.AutoClickService
import dev.nymph.auto.engine.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 轻量定点回放器（v3.0.0，快路径）。
 *
 * 与 [ScriptRunner] 完全解耦：不读 SceneRule、不做截图、不做 NCC、不跑判据。
 * 只按 [SimpleTapPlan] 的时间轴，在 [screenW]×[screenH] 坐标系下把百分比
 * 换算成像素，经决策出的通道派发点击。注入时钟独立于任何感知/判据。
 *
 * ## fail-loud
 *
 * - 无障碍服务未连接 → 立即停止并回报原因（不静默空转）。
 * - 真值模式但 Shizuku 不可用 → 拒绝（[InjectionPolicy] 决策），不回退截图猜。
 * - 每次注入结果经 [onStatus] 上报，UI 可显示「第 N 次 / 共 M 次」与失败原因。
 */
class SimpleTapRunner(
    private val scope: CoroutineScope,
    private val shizuku: ShizukuBridge,
    private val onStatus: (String) -> Unit = {},
) {
    @Volatile
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /**
     * 启动回放。
     *
     * @param plan 定点计划
     * @param requested 用户选择的注入通道
     * @param screenW/screenH 当前屏幕像素尺寸（百分比换算基准，调用方每次启动刷新）
     */
    fun start(plan: SimpleTapPlan, requested: InjectionChannel, screenW: Int, screenH: Int) {
        stop()
        val decision = InjectionPolicy.decide(requested, shizukuReady = shizuku.bind())
        if (!decision.accepted) {
            onStatus("⛔ ${decision.reason}")
            RunLog.w("SimpleTap", decision.reason)
            return
        }
        if (decision.channel == InjectionChannel.GESTURE && AutoClickService.live() == null) {
            val msg = "⛔ 无障碍服务未连接，无法注入点击；请在系统设置开启 NymphAuto 无障碍服务。"
            onStatus(msg)
            RunLog.w("SimpleTap", msg)
            return
        }
        job = scope.launch {
            runLoop(plan, decision, screenW, screenH)
        }
    }

    private suspend fun runLoop(
        plan: SimpleTapPlan,
        decision: ChannelDecision,
        screenW: Int,
        screenH: Int,
    ) {
        val (px, py) = SimpleTapSchedule.resolvePixels(plan.x, plan.y, screenW, screenH)
        val ticks = SimpleTapSchedule.expand(plan)
        onStatus("▶ 开始定点回放：${decision.channel} · 共 ${ticks.size} 次 · @($px,$py)")
        val startAt = SystemClock.elapsedRealtime()
        for (tick in ticks) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // 对齐到计划时间轴：注入时钟独立，不受任何截图/判据影响。
            val due = startAt + tick.offsetMs
            val wait = due - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)

            val ok = dispatchOne(decision.channel!!, px, py, plan.holdMs)
            if (!ok) {
                onStatus("⛔ 第 ${tick.index + 1} 次注入失败，已停止（不静默跳过）。")
                RunLog.w("SimpleTap", "第 ${tick.index + 1} 次注入失败")
                return
            }
            onStatus("第 ${tick.index + 1}/${ticks.size} 次 · @($px,$py)")
        }
        onStatus("✓ 定点回放完成：共 ${ticks.size} 次")
    }

    /** 单次注入：Shizuku 走 shell input，GESTURE 走 dispatchGesture（moveTo+lineTo 到同点）。 */
    private fun dispatchOne(channel: InjectionChannel, x: Int, y: Int, holdMs: Long): Boolean = when (channel) {
        InjectionChannel.SHIZUKU -> shizuku.tap(x, y).ok
        InjectionChannel.GESTURE -> dispatchGestureTap(x, y, holdMs)
        InjectionChannel.LEGACY -> false // 快路径不走取证链
    }

    private fun dispatchGestureTap(x: Int, y: Int, holdMs: Long): Boolean {
        val svc = AutoClickService.live() ?: return false
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            // 必须 lineTo 到同一点：只有 moveTo 的 StrokeDescription 不产生有效点击。
            lineTo(x.toFloat(), y.toFloat())
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0L, holdMs.coerceIn(40L, 120L),
                ),
            )
            .build()
        return svc.dispatchGesture(
            gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: android.accessibilityservice.GestureDescription?) { /* 已派发 */ }
                override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { /* 由布尔返回值体现 */ }
            },
            null,
        )
    }

    fun stop() {
        job?.cancel()
        job = null
        onStatus("■ 已停止")
    }
}

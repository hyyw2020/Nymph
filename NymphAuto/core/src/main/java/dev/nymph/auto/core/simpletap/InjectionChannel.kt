// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.simpletap

/**
 * 输入注入通道与降级决策（v3.0.0，core 纯 JVM，无 Android 依赖）。
 *
 * ## 三层通道（能力从强到弱）
 *
 * - [SHIZUKU]：经 Shizuku shell 权限注入完整 MotionEvent（down/move/up +
 *   pressure/size），可覆盖自绘界面，拟人维度完整。用户需安装并授权 Shizuku。
 * - [GESTURE]：AccessibilityService.dispatchGesture（系统官方 API，API 24+），
 *   无需额外授权，覆盖自绘界面的坐标点按，但无 pressure/size。作为 Shizuku
 *   不可用时的默认降级。
 * - [LEGACY]：现有取证链（截图 + NCC + 场景规则），保留为高级模式 / 未授权兼容。
 *
 * ## 不回退红线
 *
 * 用户显式选择 [SHIZUKU]（真值模式）后，若 Shizuku 运行时不可用，**不静默降级**
 * 到截图猜坐标——按 [SHIZUKU_UNAVAILABLE] 明确告知，由用户决定是否降级。
 * 这条等价于 fail-loud：真值模式绝不悄悄退化成取证链。
 */
enum class InjectionChannel {
    SHIZUKU,
    GESTURE,
    LEGACY,
}

/** 通道选择结果：要么给出实际通道，要么给出必须呈现给用户的拒绝原因。 */
data class ChannelDecision(
    val channel: InjectionChannel?,
    val reason: String,
) {
    val accepted: Boolean get() = channel != null
}

object InjectionPolicy {

    const val REASON_SHIZUKU_UNAVAILABLE =
        "已选择真值模式，但 Shizuku 不可用；为避免退回到截图猜坐标，已停止回放。" +
            "请授权 Shizuku 后重试，或明确切换到标准模式。"

    const val REASON_GESTURE_FALLBACK =
        "Shizuku 不可用，已按标准模式用系统手势注入（坐标点按可用，无压力/尺寸拟人维度）。"

    /**
     * 按用户选择的模式 [requested] 与运行时 Shizuku 可用性 [shizukuReady] 决策通道。
     *
     * - requested=SHIZUKU 且 ready → SHIZUKU（真值）
     * - requested=SHIZUKU 但 !ready → 拒绝，[REASON_SHIZUKU_UNAVAILABLE]（不静默降级）
     * - requested=GESTURE → GESTURE（无论 Shizuku 状态；若 ready 附带提示可升级）
     * - requested=LEGACY → LEGACY（高级模式，原样保留）
     */
    fun decide(requested: InjectionChannel, shizukuReady: Boolean): ChannelDecision = when {
        requested == InjectionChannel.SHIZUKU && shizukuReady ->
            ChannelDecision(InjectionChannel.SHIZUKU, "真值模式：Shizuku 注入已就绪。")
        requested == InjectionChannel.SHIZUKU && !shizukuReady ->
            ChannelDecision(null, REASON_SHIZUKU_UNAVAILABLE)
        requested == InjectionChannel.GESTURE && shizukuReady ->
            ChannelDecision(InjectionChannel.GESTURE, "$REASON_GESTURE_FALLBACK 检测到 Shizuku，可切换真值模式。")
        requested == InjectionChannel.GESTURE ->
            ChannelDecision(InjectionChannel.GESTURE, REASON_GESTURE_FALLBACK)
        else ->
            ChannelDecision(InjectionChannel.LEGACY, "高级模式：沿用现有取证链。")
    }
}

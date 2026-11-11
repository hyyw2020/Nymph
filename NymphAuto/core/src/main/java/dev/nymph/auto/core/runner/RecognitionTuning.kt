package dev.nymph.auto.core.runner

import kotlinx.serialization.Serializable

/**
 * 识别调度参数（v2.7.0，纯 JVM 可测）。
 *
 * ## 为什么存在
 *
 * v2.6.0 及以前，找图/找色目标未命中后的重试间隔硬编码为 300ms
 * （[ScriptRunner.retryIntervalMs]），条件失败等待默认 100ms。用户实测反馈
 * 「100 毫秒的识别效率低，跟不上高速移动的目标」——本质不是算法慢，而是
 * **采样率被默认参数锁死在 3~10Hz**。真实约束是无障碍 `takeScreenshot()`
 * 一帧本身就要 16~60ms，因此把间隔做成可配置、并默认对齐显示帧率，
 * 再叠加运动外推（见 core.vision.MotionPredictor），才能在物理上限内
 * 尽量跟上移动目标。
 *
 * ## 诚实边界（写进 ADR，不粉饰）
 *
 * 「1ms 内通过特征自动找坐标」在 Android 无障碍通道上**不可能**：一次全屏
 * 截图 + 灰度化 + NCC 匹配的端到端延迟下限由帧率决定（典型 16~60ms），
 * 任何特征算法都突破不了采样本身。本类提供的是「帧同步高频采样 + 预测补偿
 * + 窄 ROI 局部匹配」的等效方案：让目标在**感知与命中率**上跟得上，
 * 而不是宣称 1ms。
 *
 * [LEGACY] 保留 v2.6.0 行为，供设置里「退回旧版调度」一键回滚。
 */
@Serializable
enum class PollMode {
    /** ≈60Hz，Choreographer 帧同步上限档（高刷机更吃电）。 */
    FRAME_16,

    /** ≈30Hz，默认档：命中率/功耗平衡。 */
    FRAME_33,

    /** ≈20Hz，省电档。 */
    FRAME_50,

    /** 旧版条件失败等待档（用户吐槽的「100ms」）。 */
    LEGACY_100,

    /** 旧版未命中重试档（v2.6.0 默认 300ms）。 */
    LEGACY_300,

    /** 自定义间隔（[customIntervalMs]，会被钳制到合法区间）。 */
    CUSTOM,
}

@Serializable
data class RecognitionTuning(
    val pollMode: PollMode = PollMode.FRAME_33,
    val customIntervalMs: Long = 33L,
    /** 用历史命中点外推下一帧位置，优先在预测点附近开窄 ROI 搜索。 */
    val motionPrediction: Boolean = true,
    /** 命中后把搜索范围收窄到预测点周围，减少全屏 NCC 计算量。 */
    val narrowRoi: Boolean = true,
    val retryWindowMs: Long = 2000L,
    /** 条件失败等待的全局覆盖（0 = 沿用动作自身 conditionFailWaitMs）。 */
    val conditionFailWaitMs: Long = 0L,
) {
    /** 实际生效的轮询间隔（毫秒），已钳制到 [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS]。 */
    val intervalMs: Long
        get() = when (pollMode) {
            PollMode.FRAME_16 -> 16L
            PollMode.FRAME_33 -> 33L
            PollMode.FRAME_50 -> 50L
            PollMode.LEGACY_100 -> 100L
            PollMode.LEGACY_300 -> 300L
            PollMode.CUSTOM -> customIntervalMs
        }.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    /** 落盘前归一化，避免用户/导入脚本写入非法值把调度打挂。 */
    fun sanitized(): RecognitionTuning = copy(
        customIntervalMs = customIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
        retryWindowMs = retryWindowMs.coerceIn(0L, 60_000L),
        conditionFailWaitMs = conditionFailWaitMs.coerceIn(0L, 10_000L),
    )

    companion object {
        /** 低于 8ms 会退化成无意义空转（截图回调根本跟不上）。 */
        const val MIN_INTERVAL_MS = 8L
        const val MAX_INTERVAL_MS = 2000L

        /** v2.6.0 行为：300ms 重试、无预测、无窄 ROI。 */
        val LEGACY = RecognitionTuning(
            pollMode = PollMode.LEGACY_300,
            motionPrediction = false,
            narrowRoi = false,
        )
    }
}

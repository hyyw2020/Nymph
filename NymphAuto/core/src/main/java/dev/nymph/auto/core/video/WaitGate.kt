package dev.nymph.auto.core.video

import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.ImageFilter
import dev.nymph.auto.core.model.Region

/**
 * 「等待目标画面出现再执行」门控的单一真源（v2.9.6，纯 JVM 可测）。
 *
 * 背景：横屏手游「战斗 → 随机时长开箱动画 → 结算领奖 → 再战」这类循环，
 * 阶段时长不可预测。视频导入默认生成 `waitMs = gap`（≤3s）的固定延时动作，
 * 找图动作自身也只重试 `retryWindowMs`（默认 2s）——两者都会在 10 秒级的
 * 转场动画期间超时，脚本随即空转 / 乱点，这就是「不支持复杂操作」的体感来源。
 *
 * 引擎其实早已有 [ConditionKind.APPEAR_IMAGE] + `repeatCheckUntilOk` 轮询能力，
 * 且轮询里带脚本中止条件检查点、等待浮层上报与看门狗心跳；缺的只是「把这条
 * 条件写进脚本」。本对象把它固化成可序列化、可单测、可导出的 [Condition]，
 * 由 [VideoScriptBuilder] 生成、ScriptRunner 原样执行，**零引擎语义改动**。
 */
object WaitGate {

    /** `repeatCheckMax = 0` = 无限等待，直到目标出现或脚本中止条件命中。 */
    const val INFINITE = 0

    /**
     * 默认轮询间隔。「等结算界面出现」是慢场景，300ms 一次足够；
     * 不做 33ms 全屏 NCC，避免长时间等待时无谓耗电发热。
     */
    const val DEFAULT_POLL_MS = 300L

    /** 有可视目标可等的动作类型。无模板的坐标点击（TAP）不在其中。 */
    private val GATED = setOf(
        ActionType.CLICK_IMAGE,
        ActionType.CLICK_COLOR,
        ActionType.CLICK_TEXT,
        ActionType.CLICK_NODE,
        ActionType.CLICK_AI,
    )

    fun supports(type: ActionType): Boolean = type in GATED

    /** 生成「等到这张模板出现在屏幕上」的运行条件。 */
    fun image(
        templateB64: String,
        similarity: Int = 80,
        region: Region = Region(),
        filters: List<ImageFilter> = emptyList(),
    ): Condition = Condition(
        kind = ConditionKind.APPEAR_IMAGE,
        imageData = templateB64,
        imageSimilarity = similarity.coerceIn(1, 100),
        region = region,
        filters = filters,
        repeatCheckUntilOk = true,
        repeatCheckIntervalMs = DEFAULT_POLL_MS,
        repeatCheckMax = INFINITE,
    )

    /**
     * 条件轮询间隔选择（纯函数，供 app 层 `repeatCheck` 调用）。
     *
     * 旧实现无条件取 `minOf(脚本配置, 调参间隔)`：在帧同步档（33ms）下会把
     * 「等结算界面出现」这种十几秒的长等待压成 30fps 全屏找图，纯耗电。
     * 无限等待是慢场景，改用脚本配置节奏；有限等待保持既有加速语义不变。
     *
     * @param accelerated 识别调参是否允许加速（非 LEGACY 档为 true）
     */
    fun pollIntervalMs(
        scriptIntervalMs: Long,
        tunedIntervalMs: Long,
        infinite: Boolean,
        accelerated: Boolean,
    ): Long {
        val script = scriptIntervalMs.coerceAtLeast(50L)
        val tuned = tunedIntervalMs.coerceAtLeast(1L)
        return when {
            infinite -> script                       // 慢场景：不被调参压快
            !accelerated -> script                   // 旧档位：沿用脚本值
            else -> minOf(script, tuned)             // 有限等待：保留加速语义
        }
    }

    fun description(): String =
        "等到目标画面出现再执行（无限等待；请用脚本中止条件或通知栏停止）"
}

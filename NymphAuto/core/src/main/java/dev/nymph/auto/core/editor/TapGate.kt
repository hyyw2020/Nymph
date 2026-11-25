// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

/**
 * 点击前门控与异步任务代际契约（v2.9.21，纯 JVM）。
 *
 * 判定职责：仅在有效点击区域内，按当前场景的颜色规则（含容差）对拾取点做匹配；
 * 命中才允许触发点击。任何未命中都不派发，并给出可归因的结论
 * （越界 / 区域为空 / 颜色不匹配 / 异步失败 / 判定中）。
 *
 * 该契约只负责「判定结论」这一确定性逻辑，不持有协程、不持有位图、不阻塞调用方；
 * 找色与判定由 app 层放在后台协程执行，主线程只呈现 [Phase] 对应的状态。
 * [Generation] 提供代际令牌：拾取 / 保存 / 取消都会推进代次，异步回填前比对，
 * 过期结果直接丢弃，旧任务结果不得覆盖新状态。
 *
 * 与运行引擎既有画面门控的关系：本契约是拾取装配期的辅助门控结论，供界面与
 * 诊断呈现；运行期稳定锚点画面门控、落点偏差护栏、失败即停判定均保持不变。
 */
object TapGate {

    /** 一次判定的执行阶段。PENDING 对应界面「判定中」。 */
    enum class Phase { PENDING, SETTLED, FAILED }

    /**
     * 装配期失败的具体阶段（v2.9.23）。
     *
     * 此前所有失败统一归为「异步取色失败」，无法区分「截图未取到」与「采到了但
     * 采样为空」，会把定位方向引偏。此处把失败点显式化，供界面、诊断摘要与
     * 主修复动作各自取用；判定结论本身仍是 fail-closed，不放宽任何放行条件。
     */
    enum class Failure {
        /** 截图调用返回空，且无可用错误码。 */
        CAPTURE_NULL,

        /** 目标窗口为安全窗口，系统拒绝截图。 */
        CAPTURE_SECURE,

        /** 截图调用超时。 */
        CAPTURE_TIMEOUT,

        /** 截图可用，但裁剪矩形或稳定锚点装配失败。 */
        ASSEMBLY,

        /** 截图像素不可读（帧缓冲区为空或尺寸为 0）。 */
        PIXEL_READ,

        /** 像素可读，但拾取点邻域采样点数为 0。 */
        SAMPLE_EMPTY,
    }

    /** 判定结论；仅 [Reason.PASS] 允许点击，其余一律不派发（fail-closed）。 */
    enum class Reason {
        PASS,
        PENDING,
        OUT_OF_BOUNDS,
        EMPTY_ROI,
        COLOR_MISMATCH,

        /** 截图未能取到（返回空 / 安全窗口 / 超时）。 */
        CAPTURE_UNAVAILABLE,

        /** 截图可用，但区域或锚点装配失败。 */
        ASSEMBLY_FAILED,

        /** 截图可用，但拾取点邻域未采到像素。 */
        SAMPLE_EMPTY,

        /** 未归类的兜底失败（保持向后兼容）。 */
        ASYNC_FAILURE,
    }

    data class Verdict(
        val reason: Reason,
        val allowTap: Boolean,
        val message: String,
    ) {
        val hit: Boolean get() = reason == Reason.PASS
    }

    /** 失败阶段到判定原因的映射。 */
    fun reasonFor(failure: Failure): Reason = when (failure) {
        Failure.CAPTURE_NULL, Failure.CAPTURE_SECURE, Failure.CAPTURE_TIMEOUT -> Reason.CAPTURE_UNAVAILABLE
        Failure.ASSEMBLY -> Reason.ASSEMBLY_FAILED
        Failure.PIXEL_READ, Failure.SAMPLE_EMPTY -> Reason.SAMPLE_EMPTY
    }

    /** 结论到用户可见原因文案的单一真源（专业术语，无口语化措辞）。 */
    fun messageFor(reason: Reason): String = when (reason) {
        Reason.PASS -> "命中：区域内颜色匹配，允许触发"
        Reason.PENDING -> "判定中：颜色校验后台执行"
        Reason.OUT_OF_BOUNDS -> "未命中：拾取点越出有效点击区域"
        Reason.EMPTY_ROI -> "未命中：有效点击区域为空"
        Reason.COLOR_MISMATCH -> "未命中：颜色不匹配（超出容差）"
        Reason.CAPTURE_UNAVAILABLE -> "未命中：截图未取到，无法校验颜色，按失败即停不派发"
        Reason.ASSEMBLY_FAILED -> "未命中：稳定锚点未装配，该场景运行时不会触发"
        Reason.SAMPLE_EMPTY -> "未命中：拾取点邻域未采到像素，按失败即停不派发"
        Reason.ASYNC_FAILURE -> "未命中：异步校验失败，按失败即停不派发"
    }

    private fun verdict(reason: Reason): Verdict =
        Verdict(reason, reason == Reason.PASS, messageFor(reason))

    /**
     * 点击前判定。判定顺序固定，任一环节不通过即返回对应原因，绝不落到点击。
     *
     * @param phase        异步任务阶段；FAILED / PENDING 直接给出对应结论
     * @param roi          有效点击区域（自动收敛或手动覆盖后的最终区域）
     * @param sample       拾取时自动采集的颜色素材
     * @param observedArgb 判定时刻在拾取点实测到的颜色（后台找色结果）
     * @param tolerance    容差上界（来自样本，可由场景规则覆盖）
     * @param failure      汇编期失败的具体阶段；为空时按兜底原因呈现
     * @param anchorReady  稳定锚点是否已装配成可挂载的画面模板（v2.9.25）
     *
     * [anchorReady] 存在的理由：颜色匹配与「运行时会不会触发」是两件事。此前的判定
     * 只校验颜色，于是出现过「锚点 0×0px、掩码 0 块、结论 NO_FRAME」却仍返回
     * [Reason.PASS] 的矛盾状态：界面说「命中」，保存后运行时因无画面模板 fail-closed
     * 一步不动。锚点不可用时，无论颜色是否匹配，本函数一律不给出 PASS —— 判定结论
     * 必须与运行时可触发性一致，否则界面的「命中」就是一句谎话。
     */
    fun evaluate(
        phase: Phase,
        roi: dev.nymph.auto.core.model.Region?,
        xPct: Double,
        yPct: Double,
        sample: SceneAssist.ColorSample?,
        observedArgb: Int?,
        tolerance: Int = SceneAssist.MAX_TOLERANCE,
        failure: Failure? = null,
        anchorReady: Boolean = true,
    ): Verdict {
        if (phase == Phase.FAILED) {
            return verdict(failure?.let { reasonFor(it) } ?: Reason.ASYNC_FAILURE)
        }
        if (phase == Phase.PENDING) return verdict(Reason.PENDING)
        if (roi == null || SceneAssist.isEmptyRoi(roi)) return verdict(Reason.EMPTY_ROI)
        if (!SceneAssist.contains(roi, xPct, yPct)) return verdict(Reason.OUT_OF_BOUNDS)
        // v2.9.25：锚点未装配 → 运行时不会触发，颜色再匹配也不能判命中。
        if (!anchorReady) return verdict(Reason.ASSEMBLY_FAILED)
        if (sample == null || !sample.valid || observedArgb == null) {
            return verdict(failure?.let { reasonFor(it) } ?: Reason.SAMPLE_EMPTY)
        }
        val limit = tolerance.coerceIn(SceneAssist.MIN_TOLERANCE, SceneAssist.MAX_TOLERANCE)
        val dist = SceneAssist.colorDistance(sample.argb, observedArgb)
        return if (dist <= limit) verdict(Reason.PASS) else verdict(Reason.COLOR_MISMATCH)
    }

    /**
     * 异步任务代际令牌（单线程可见即可，纯 JVM 无需锁）。
     *
     * 每次拾取 / 取消 / 保存调用 [advance] 推进代次；后台任务持有发起时的代次，
     * 回填前用 [isCurrent] 比对，过期即丢弃。这样连续拾取或取消后，旧任务的
     * 结果不会覆盖新状态，界面不会停留在上一轮的「判定中」或上一轮的命中结论。
     */
    class Generation {
        private var current: Int = 0

        fun current(): Int = current

        /** 推进并返回新代次（用于一次新的拾取 / 判定）。 */
        fun advance(): Int = ++current

        /** 取消在途任务：推进代次但不发起新任务。 */
        fun cancel() { current++ }

        fun isCurrent(gen: Int): Boolean = gen == current && current > 0
    }

    /** 判定节流：同一代次内相同拾取点的最小判定间隔（毫秒）由 app 层用本常量对齐。 */
    const val MIN_EVAL_INTERVAL_MS = 120L
}

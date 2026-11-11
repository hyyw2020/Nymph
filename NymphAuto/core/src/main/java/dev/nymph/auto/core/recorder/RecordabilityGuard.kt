// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

/**
 * 录制可行性守卫（v2.9.2，core 纯 JVM 单一真源）。
 *
 * ## 背景：这不是 bug，是能力边界
 *
 * 被动无障碍录制只订阅语义事件（VIEW_CLICKED / SCROLLED / TEXT_CHANGED …），
 * 且受硬性契约约束**不拦截、不消费、不重排触摸**。自绘界面
 * （Unity / Cocos 游戏、视频播放器、地图、Flutter / SurfaceView 全绘页面）里的
 * 「按钮」不是 Android View，系统不会派发这些语义事件——于是用户照常操作几十秒，
 * 停止后只得到「未录到有效动作」，却完全不知道为什么，进而认为「不能实现类似操作」。
 *
 * 这个边界**不能**靠加 `FLAG_WATCH_OUTSIDE_TOUCH`、全屏透明覆盖层或触摸浏览来
 * 「修复」，那会打断其他 APP 的正常触摸分发（违反第 5 条硬契约）。因此本类选择
 * 把「录制期到底发生了什么」判定为可解释的状态，并给出**必达且可操作**的替代路径：
 * 这类重复操作应改用「视频导入 → 生成找图脚本 → 设置循环」实现。
 *
 * 找图点击会轮询等待目标出现，不依赖固定延时；配合无限循环 / 中止条件，
 * 可以覆盖游戏里「扫荡 → 结算 → 领取 → 再扫荡」这类界面结果随机、循环次数不固定的流程。
 *
 * 本类不接触任何 Android API，判定与文案由 core 单测锁死，app 层只负责送达。
 */
object RecordabilityGuard {

    enum class Verdict {
        /** 还在本应用内待命，用户尚未切到目标 APP。 */
        WAITING,

        /** 正常收到可编译成动作的交互事件。 */
        RECORDABLE,

        /** 系统一个事件都没派发：无障碍服务未开启，或目标 APP 完全静默。 */
        NO_EVENTS,

        /** 有窗口事件、零交互事件：自绘界面高嫌疑（游戏 / 视频 / 全绘页面）。 */
        SELF_DRAWN,

        /** 交互事件极少而窗口事件很多：混合界面，脚本大概率不完整。 */
        LOW_SIGNAL,
    }

    /**
     * 录制期信号快照。由 app 层 [dev.nymph.auto.recorder.RecorderBus] 采集后传入，
     * core 不关心事件来自哪个系统 API。
     */
    data class Signal(
        /** 是否已解除待命（用户已切到目标 APP）。 */
        val armed: Boolean = false,
        /** 可编译成动作的交互事件数：点击 / 长按 / 滚动 / 文本输入。 */
        val interactionEvents: Int = 0,
        /** 窗口状态 / 层级变化事件数（不携带可回放语义，但能证明服务在收事件）。 */
        val windowEvents: Int = 0,
        /** 系统派发的全部事件数（含未映射类型）。 */
        val totalEvents: Int = 0,
        /** 自录制开始经过的毫秒数。 */
        val elapsedMs: Long = 0L,
    )

    /** 进入目标 APP 后，持续无有效交互多久开始提前提示：8s，短于一次典型试错。 */
    const val EARLY_WARN_AFTER_MS = 8_000L

    /** 交互事件低于等于此数、且窗口事件占绝对多数时判为「信号不足」。 */
    const val LOW_SIGNAL_MAX_INTERACTIONS = 2

    /** 窗口事件至少是交互事件的几倍才算「占绝对多数」。 */
    const val LOW_SIGNAL_WINDOW_RATIO = 3

    /** 纯函数判定：同样的信号永远得到同样的结论，便于单测与日志复现。 */
    fun diagnose(s: Signal): Verdict {
        if (!s.armed) return Verdict.WAITING
        if (s.totalEvents <= 0) return Verdict.NO_EVENTS
        // 服务确实在收事件，却一条可编译的交互都没有：正是自绘界面的特征。
        // 窗口事件数量不作为门槛——短时（刚解除待命还没操作）由
        // shouldWarnDuring 的 8s 时间门槛兜住，不会提前误报。
        if (s.interactionEvents <= 0) return Verdict.SELF_DRAWN
        if (s.interactionEvents <= LOW_SIGNAL_MAX_INTERACTIONS &&
            s.windowEvents >= s.interactionEvents * LOW_SIGNAL_WINDOW_RATIO
        ) {
            return Verdict.LOW_SIGNAL
        }
        return Verdict.RECORDABLE
    }

    /**
     * 是否该在录制过程中提前提示：仅针对「正在白操作」的状态，正常录制不打扰。
     * 零事件是独立分支——即便用户一直没切出本应用（armed=false），长时间收不到
     * 任何事件也说明无障碍服务没派发事件，不该让用户白操作几十秒。
     */
    fun shouldWarnDuring(s: Signal): Boolean =
        s.elapsedMs >= EARLY_WARN_AFTER_MS &&
            (s.totalEvents <= 0 || diagnose(s) in setOf(Verdict.SELF_DRAWN, Verdict.LOW_SIGNAL))

    /**
     * 取「该用哪套文案」：零事件一律归为 [Verdict.NO_EVENTS]（提示检查无障碍服务），
     * 其余按 [diagnose]。避免未解除待命时误给「请切到目标 APP」这种与实际操作
     * （用户明明在游戏里）矛盾的指引。
     */
    fun warningVerdict(s: Signal): Verdict =
        if (s.totalEvents <= 0) Verdict.NO_EVENTS else diagnose(s)

    /** 根因一句话：用于日志页与收尾 toast 的主干，如实说明边界而非含糊带过。 */
    fun reason(v: Verdict): String = when (v) {
        Verdict.WAITING -> "录制仍在待命：请切到目标 APP 后再操作，本应用内的点击不会记入脚本。"
        Verdict.NO_EVENTS -> "系统未向无障碍服务派发任何事件，请确认已在系统设置中开启 NymphAuto 无障碍服务。"
        Verdict.SELF_DRAWN -> "当前界面疑似自绘界面（游戏 / 视频 / 全绘页面）：按钮不是 Android View，被动无障碍录制收不到点击事件，这是能力边界而非脚本错误。"
        Verdict.LOW_SIGNAL -> "只录到极少交互事件、窗口事件却很多，界面可能混有自绘区域，脚本大概率不完整。"
        Verdict.RECORDABLE -> "本次录制正常收到交互事件。"
    }

    /**
     * 可操作的替代路径（必达文案的核心）。指向工程里真实存在的入口名「视频导入」，
     * 并说明为什么找图 + 循环能覆盖这类界面结果随机、循环次数不固定的重复操作。
     */
    fun alternative(v: Verdict): String = when (v) {
        Verdict.SELF_DRAWN, Verdict.LOW_SIGNAL ->
            "替代路径：先录一段操作视频 → 主界面点「视频导入」勾选触点生成找图脚本 → " +
                "把循环设为指定次数或无限循环。找图点击会轮询等待按钮出现（不依赖固定延时），" +
                "因此结算动画时长随机、领取弹窗时有时无也能自动重复执行。"
        Verdict.NO_EVENTS ->
            "开启无障碍服务后重试；若目标 APP 仍不派发事件，同样可改用「视频导入」生成找图脚本。"
        Verdict.WAITING -> "切到目标 APP 后照常操作即可。"
        Verdict.RECORDABLE -> ""
    }

    /** 录制过程中的短提示：面板 hint / toast 用，不宜过长。 */
    fun duringHint(v: Verdict): String = when (v) {
        Verdict.SELF_DRAWN -> "⚠ 该界面收不到点击（自绘界面）\n请用「视频导入 + 循环」实现"
        Verdict.LOW_SIGNAL -> "⚠ 交互事件过少\n建议改用「视频导入 + 循环」"
        Verdict.NO_EVENTS -> "⚠ 未收到任何无障碍事件\n请检查无障碍服务是否开启"
        Verdict.WAITING, Verdict.RECORDABLE -> ""
    }

    /**
     * 录制收尾的完整指引：空脚本时不再只说「未录到有效动作，已取消」，
     * 而是把根因、替代路径与系统诊断一起送达（日志页 + toast 共用同一真源）。
     */
    fun finishGuidance(v: Verdict, diagnostics: String): String = buildString {
        append(reason(v))
        val alt = alternative(v)
        if (alt.isNotBlank()) append(" ").append(alt)
        append(" [诊断：").append(diagnostics).append("]")
    }

    /** toast 用短版：完整版太长会被系统截断，日志页保留 [finishGuidance] 全文。 */
    fun finishToast(v: Verdict): String = when (v) {
        Verdict.SELF_DRAWN -> "该界面为自绘界面，无障碍录制收不到点击；请用「视频导入」生成找图脚本并设循环"
        Verdict.LOW_SIGNAL -> "交互事件过少，建议用「视频导入」生成找图脚本并设循环"
        Verdict.NO_EVENTS -> "未收到任何无障碍事件，请检查无障碍服务；也可改用「视频导入」"
        Verdict.WAITING -> "录制期间未切到目标 APP，请切到目标 APP 后再操作"
        Verdict.RECORDABLE -> "未录到有效动作，已取消"
    }
}

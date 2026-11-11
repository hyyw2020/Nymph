package dev.nymph.auto.runner

import dev.nymph.auto.core.expr.ConditionEvaluator
import dev.nymph.auto.core.expr.ExprEngine
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ListOrder
import dev.nymph.auto.core.model.LoopPolicy
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.model.TargetPackages
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.recorder.SceneGuard
import dev.nymph.auto.core.runner.CapabilityMatrix
import dev.nymph.auto.core.runner.CapabilityTier
import dev.nymph.auto.core.runner.DriftTracker
import dev.nymph.auto.core.runner.PollMode
import dev.nymph.auto.core.runner.RecognitionTuning
import dev.nymph.auto.core.runner.FailReason
import dev.nymph.auto.core.runner.JitterSampler
import dev.nymph.auto.core.runner.RunStats
import dev.nymph.auto.core.runner.WaitStatus
import dev.nymph.auto.core.visual.VisualConfig
import dev.nymph.auto.core.visual.VisualCue
import dev.nymph.auto.core.visual.VisualGeometry
import dev.nymph.auto.core.visual.VisualKind
import dev.nymph.auto.core.video.WaitGate
import dev.nymph.auto.core.vision.MotionPredictor
import dev.nymph.auto.core.vision.RoiExpr
import dev.nymph.auto.engine.AutoClickService
import dev.nymph.auto.engine.Frame
import dev.nymph.auto.engine.GestureEngine
import dev.nymph.auto.engine.NodeFinder
import dev.nymph.auto.engine.VisionPipeline
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * 脚本执行器：动作 / 条件 / 监听 / 变量 / 跳转 / 子脚本 的统一编排。
 *
 * 关键语义（对齐自动精灵）：
 *  - 每个动作前检查运行条件（不满足则等待 condFailWait 后跳过）
 *  - 条件可配置"重复检查直到满足"（间隔 + 上限）
 *  - 监听动作 10 个时机
 *  - 重复次数 0 = 无限；乱序/同时模式
 *  - goto 按 label 跳转；子脚本深度 ≤ 8
 *  - 中止条件（对标自动精灵 break）：每个动作前 / 组内子动作 / 重复迭代 /
 *    等待轮询处检查，载荷命中即刹停整个脚本（预期内中止，归因 ABORT_HIT）。
 *    已知平台限制：已注入系统的手势无法中途取消，中止在下一次检查点生效。
 *
 * 失败恢复（不"失去目标就罢工"）：
 *  - 点击类动作未命中时在 retryWindowMs 内轮询重试（默认 2s / 300ms 间隔）
 *  - 图片类重试耗尽时 dump 当前屏幕帧到 debugDumpDir 供事后排查
 *  - 命中统计 + 组级/整轮执行总结，替换无信息的"执行完毕"
 */
class ScriptRunner(
    private val gesture: GestureEngine,
    private val vision: VisionPipeline,
    private val nodeFinder: NodeFinder,
    private val js: ExprEngine,
    private val onLog: (String) -> Unit = {},
) {
    val paused = MutableStateFlow(false)
    var onAbnormal: ((String) -> Unit)? = null

    /**
     * v2.9.1：执行前的**非阻断**界面风险提示（区别于 [onAbnormal] 的错误中止）。
     * 用户可继续运行，但必须被告知「同包名不等于同界面、被动录制无法还原页面」。
     * app 层接线为日志 + toast，确保告警真正送达而不是只写进脚本 description。
     */
    var onSceneNotice: ((String) -> Unit)? = null

    /** 点击类动作未命中时的轮询重试窗口（毫秒）；0 = 不重试（保持旧秒跳行为）。 */
    var retryWindowMs: Long = 2000

    /**
     * v2.7.0 识别调度参数：默认 33ms 帧同步（旧版固定 300ms），可一键退回
     * [RecognitionTuning.LEGACY]。见 core.runner.RecognitionTuning 的诚实边界说明。
     */
    var recognitionTuning: RecognitionTuning = RecognitionTuning()

    /** 旧字段保留兼容（RunnerService 历史接线），实际轮询间隔由 [recognitionTuning] 决定。 */
    var retryIntervalMs: Long = 300

    /** 每个模板目标独立跟踪运动，避免不同目标互相污染速度估计。 */
    private val motionByKey = HashMap<String, MotionPredictor>()

    /** 失败截图 dump 目录（null = 不存证）。 */
    var debugDumpDir: File? = null

    /**
     * 形态/时间随机（裁定阶段 3.2）：间隔对数正态扰动 + DOWN-UP 亚像素 MOVE。
     * 默认开；调试时置 false 可复现精确回放（同种子同序列）。
     */
    var jitterEnabled: Boolean = true
    var jitterSeed: Long = 0x5EEDL

    /** 能力矩阵档位（RunnerService 预检后注入）；受限档不跑检查点闭环（裁定：不承诺）。 */
    var capabilityTier: CapabilityTier = CapabilityTier.FULL

    /** 等待浮层回调（裁定阶段 3.5）："正在等待'确认'按钮 3.2s / 15s"；null=清除。 */
    var onWaitStatus: ((WaitStatus?) -> Unit)? = null

    /**
     * 动作失败且未配置 onActionFail 监听时是否暂停（对标自动精灵"失败即暂停"）。
     * 默认 false——我方策略是未命中年轮重试而非罢工；开启则复刻对方语义，
     * 且配置了失败监听时监听替代暂停（见 [handleActionFail]）。
     */
    var pauseOnFail: Boolean = false

    /**
     * 失败四类归因回调（裁定阶段 4）：分类 + 人话说明 + 顶层步号（1-based，0=监听/子级上下文）。
     * UI 据此给出对应动作，并能一键定位到失败步（定点重拾取的第一步）。
     */
    var onFail: ((FailReason, String, Int) -> Unit)? = null

    /** 子脚本加载回调（app 层装配，避免 core/runner 直接依赖存储）。 */
    var subScriptLoader: (suspend (file: String, args: String) -> Unit)? = null

    // ---- 动作可视化（v2.4.0）----
    // 数据来自本执行器即将派发的真实手势坐标，绝不从无障碍事件反推手指位置。
    // 两项默认值都保证「彻底关闭」：onVisualCue=null 时 visualize() 首行即返回，
    // 不产生任何回调、不增加任何延迟，与 v2.3.0 行为完全一致。
    /** 可视化配置提供者（由 RunnerService 从 SharedPreferences 注入，默认全关）。 */
    var visualConfigProvider: () -> VisualConfig = { VisualConfig() }

    /** 可视化 cue 出口；null = 未接线/关闭，所有可视化调用立即短路。 */
    var onVisualCue: ((VisualCue) -> Unit)? = null

    private var cueSeq = 0L
    private var totalSteps = 0

    /**
     * 进度心跳（elapsedRealtime）。每个顶层动作开始/结束时刷新，
     * 供 RunnerService 的看门狗区分「脚本在慢慢跑」与「协程已卡死」。
     * volatile：看门狗在另一条协程读，写入在脚本线程。
     */
    @Volatile
    var lastProgressAt: Long = android.os.SystemClock.elapsedRealtime()
        private set

    /** 当前步的人话描述（卡死时写进日志，便于定位是哪一步）。 */
    @Volatile
    var currentStepDesc: String = ""
        private set

    private fun pulse(desc: String = "") {
        lastProgressAt = android.os.SystemClock.elapsedRealtime()
        if (desc.isNotEmpty()) currentStepDesc = desc
    }

    /**
     * 变量表（JS API 与条件求值共用）。
     * 公开引用：RunnerZdjlApi 的 zdjl.vars / JS 读写都落在这一张表上。
     */
    val vars = VariableStore(js)
    private val evaluator = ConditionEvaluator(js)
    private val runCounts = mutableMapOf<String, Int>()
    private var frame: Frame? = null
    private var depth = 0
    private val stats = RunStats()
    private var scriptStartAt = 0L

    /** 连续「重试窗口耗尽仍未命中」次数：无限循环里全失败时用于空转降频。 */
    private var consecutiveMiss = 0
    /** 同一失败目标本次运行已 dump 的现场张数（防止无限循环反复压缩写盘）。 */
    private val missDumpCounts = HashMap<String, Int>()

    /** 当前执行的脚本（zdjl.check 按动作描述查找条件用）。 */
    private var currentScript: Script? = null

    // ---- 裁定阶段 3.4：检查点闭环 + 漂移预算 ----
    private var jitter: JitterSampler = JitterSampler()
    private val drift = DriftTracker()
    /** 锚点模板 → 上次命中位置（同模板连续命中位置差即漂移）。 */
    private val anchorLast = HashMap<String, Pair<Int, Int>>()

    /** 当前顶层步号（1-based；失败定位用，0=监听/子级上下文）。 */
    private var currentStep = 0

    suspend fun run(script: Script): Boolean {
        currentScript = script
        frame = AutoClickService.live()?.frame()
        if (frame == null) {
            onAbnormal?.invoke("无法获取屏幕尺寸（无障碍服务未连接或显示信息不可用）")
            return false
        }
        warnIfOrientationMismatch(script)
        if (!checkStartScene(script)) return false
        scriptRefW = script.refWidth
        scriptRefH = script.refHeight
        script.variables.forEach { v ->
            vars.declare(v.name, v.type, v.expression, v.value)
        }
        val actions = script.actions
        if (actions.isEmpty()) {
            onAbnormal?.invoke("脚本为空，已中止")
            return false
        }
        totalSteps = actions.size
        stats.reset()
        consecutiveMiss = 0
        missDumpCounts.clear()
        scriptStartAt = android.os.SystemClock.elapsedRealtime()
        jitter = JitterSampler(jitterSeed)
        drift.reset()
        anchorLast.clear()
        motionByKey.clear()
        onLog("开始执行，共 ${actions.size} 组动作${when {
            script.repeatCount == 0 -> "，无限循环（停止条件见控制执行动作）"
            script.repeatCount > 1 -> "，重复 ${script.repeatCount} 轮"
            else -> ""
        }}")
        runListeners(script.listeners.beforeScript)
        val labelIndex = actions.withIndex()
            .filter { it.value.label.isNotEmpty() }
            .associate { it.value.label to it.index }

        var loops = 0
        var pc = 0
        try {
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                awaitIfPaused()
                // 旋转校验：参考方向不一致时中止（百分比坐标在旋转后会错位）
                frame?.let { f ->
                    if (script.refRotation != 0 && f.rotation != script.refRotation) {
                        onAbnormal?.invoke("屏幕方向与脚本不一致，已中止")
                        return false
                    }
                }
                if (pc >= actions.size) {
                    // v2.9.4：空脚本 + 无限循环曾在此处无限空转 —— pc 永远 >= 0、
                    // repeatCount=0 永远不满足结束条件，协程不结束、afterScript
                    // 不执行、通知常驻。空脚本没有可回放动作，直接正常收尾。
                    if (actions.isEmpty()) return true
                    loops++
                    if (script.repeatCount > 1) {
                        onLog("第 $loops/${script.repeatCount} 轮完成：已执行 ${stats.executed} 步，${stats.resultText()}")
                    }
                    // 负 repeatCount（手写 JSON）此前被 in 1..loops 判成无限循环；
                    // 统一交给 core LoopPolicy 归一，边界由 core 单测锁死。
                    if (LoopPolicy.isFinished(loops, script.repeatCount)) return true
                    pc = 0
                    continue
                }
                val action = actions[pc]
                pc++
                currentStep = pc
                // v2.9.1：顶层动作组计数。此前 stats 只在查找类命中/失败时自增，
                // 纯 SWIPE 脚本整轮跑完仍报「命中 0、跳过 0、失败 0」，用户误以为
                // 脚本没执行。executed 由主循环无条件自增，反映真实执行步数。
                stats.executed++
                pulse(action.desc.ifBlank { action.type.name })
                // 中止条件（对标自动精灵 break）：每个动作前检查，命中即刹停。
                // 含 GOTO（否则纯跳转循环永远等不到检查点）。
                script.abortCondition?.let { evaluateCondition(it, "") }
                when (action.type) {
                    ActionType.GOTO -> pc = labelIndex[action.label] ?: pc
                    else -> runAction(action, script)
                }
            }
        } catch (e: AbortHit) {
            // 中止条件命中（对标自动精灵 break）：预期内停止。
            // 已由 evaluateCondition 记日志 + onFail(ABORT_HIT) 归因，
            // 这里收尾（afterScript 监听与总结在 finally 执行）并标记未跑完。
            onLog(e.message ?: "中止条件命中，脚本中止")
            return false
        } catch (e: ScriptAbort) {
            // 致命错误（如屏幕尺寸不可用）：已由 onAbnormal 提示，中止并标记未完成
            return false
        } finally {
            onWaitStatus?.invoke(null) // 清除等待浮层
            runListeners(script.listeners.afterScript)
            if (script.repeatCount != 1 || pc > 0) {
                onLog(roundSummary(loops + 1, script.repeatCount, final = true))
            }
        }
    }

    /** 屏幕方向/比例与脚本参考差异大时预警（模板大量匹配失败的常见根因）。 */
    private fun warnIfOrientationMismatch(script: Script) {
        val f = frame ?: return
        if (script.refWidth <= 0 || script.refHeight <= 0) return
        val refPortrait = script.refHeight > script.refWidth
        val curPortrait = f.height > f.width
        val desc = "当前屏幕 ${f.width}x${f.height}（${if (curPortrait) "竖屏" else "横屏"}）"
        if (refPortrait != curPortrait) {
            onLog("⚠ 警告：$desc 与脚本参考 ${script.refWidth}x${script.refHeight}（${if (refPortrait) "竖屏" else "横屏"}）方向相反，图片模板匹配大概率失败")
            RunLog.w("Script", "屏幕方向与脚本参考相反：$desc vs ${script.refWidth}x${script.refHeight}")
            return
        }
        val refRatio = script.refWidth.toDouble() / script.refHeight
        val curRatio = f.width.toDouble() / f.height
        if (abs(refRatio - curRatio) / refRatio > 0.25) {
            onLog("⚠ 警告：$desc 与脚本参考 ${script.refWidth}x${script.refHeight} 宽高比差异较大（${"%.2f".format(refRatio)} vs ${"%.2f".format(curRatio)}），图片模板匹配可能失败")
        }
    }

    /** 执行总结：口径与格式化集中在 core [RunStats]，保证「已执行 N 步」可被单测锁死。 */
    private fun roundSummary(round: Int, total: Int, final: Boolean = false): String {
        val secs = (android.os.SystemClock.elapsedRealtime() - scriptStartAt) / 1000.0
        return stats.summary(secs, round, total)
    }

    // ------------------------------------------------------------ 单动作执行

    /**
     * 单动作执行。@return true=动作体成功（条件满足且执行/断言通过）。
     *
     * 回调时机（对齐自动精灵 10 钩子中与本层相关的 7 个）：
     *  beforeAction → beforeConditionCheck → [条件] → 执行体 →
     *  成功: onActionOk / 失败: onActionFail；条件不满足: onConditionFail；
     *  最后 afterAction。
     */
    private suspend fun runAction(action: Action, script: Script, inListener: Boolean = false): Boolean {
        val desc = action.desc.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""
        val prefix = if (inListener) "[监听] " else ""
        if (action.disabled) {
            stats.skip++
            onLog("${prefix}跳过 ${action.type}$desc（已禁用）")
            return false
        }
        onLog("${prefix}执行 ${action.type}$desc")
        runListeners(action.listeners.beforeAction)

        // v2.9.6：动作显式配置的 condition 优先；否则若开启 waitAppear 且有模板，
        // 由 WaitGate 生成「等到目标画面出现」门控。游戏「扫荡 → 随机时长动画 →
        // 结算确认 → 再扫荡」这类循环因此变成事件驱动，不再靠固定延时猜时长。
        // 老脚本无 waitAppear 字段（默认 false），此分支完全不生效。
        val gateCond: Condition? = when {
            action.condition != null -> action.condition
            action.waitAppear && action.imageData.isNotEmpty() && WaitGate.supports(action.type) ->
                WaitGate.image(action.imageData, action.imageSimilarity, action.region, action.filters)
            else -> null
        }
        // 运行条件（含重复检查）；beforeConditionCheck 在条件判断前触发
        val condOk = gateCond?.let { cond ->
            runListeners(action.listeners.beforeConditionCheck)
            if (!evaluateCondition(cond, action.desc)) {
                if (cond.repeatCheckUntilOk) repeatCheck(cond, script, action.desc) else false
            } else true
        } ?: true

        var ok = false
        if (condOk) {
            ok = repeatActionBody(action, script)
            runCounts[action.desc] = (runCounts[action.desc] ?: 0) + 1
            // 次数同步变量（对标自动精灵）：把"本动作累计运行次数"写进
            // vars.__runCount，JS 条件 / RUN_JS 代码可读：
            //   if (vars.__runCount >= 3) { ... }
            vars.set(RUN_COUNT_VAR, runCounts[action.desc])
            if (ok) {
                // 后置断言（裁定阶段 3.3）：坐标命中 ≠ 点击产生了正确结果。
                // 断言失败与坐标未命中是两类问题：前者是目标 App 界面逻辑变了，
                // 归因 ASSERT_FAIL（定点重拾取），后者才是 COORD_MISS。
                val post = action.postCondition
                if (post != null && !evaluateCondition(post, action.desc)) {
                    ok = false
                    stats.fail++
                    val msg = "后置断言失败（坐标命中但结果不符）：${condDesc(post)}"
                    onLog("⚠ $msg")
                    onFail?.invoke(FailReason.ASSERT_FAIL, msg, currentStep)
                    onAbnormal?.invoke(msg)
                    handleActionFail(action, msg)
                }
            }
            if (ok) {
                runListeners(action.listeners.onActionOk)
                // 一次性变量（执行后删变量）：动作成功结束后即删除值，声明保留
                if (action.deleteVarAfter) vars.clearValue(action.varName)
            } else if (action.postCondition == null) {
                handleActionFail(action, null)
            }
        } else {
            stats.skip++
            onLog("${prefix}条件不满足（${condDesc(gateCond)}），跳过 ${action.type}$desc")
            // v2.7.0：全局调参可覆盖动作自带的条件失败等待（0 = 沿用脚本原值）
            val condWait = if (recognitionTuning.conditionFailWaitMs > 0) {
                recognitionTuning.conditionFailWaitMs
            } else action.conditionFailWaitMs
            if (condWait > 0) delay(condWait)
            runListeners(action.listeners.onConditionFail)
        }
        runListeners(action.listeners.afterAction)
        return ok
    }

    /**
     * 动作失败处理（对标自动精灵）：配置了 onActionFail 监听 → 执行监听，
     * **替代默认暂停**；未配置监听且 [pauseOnFail] 开启 → 暂停等待用户。
     * 默认不暂停（与"未命中年轮重试、不罢工"的回放哲学一致）。
     */
    private suspend fun handleActionFail(action: Action, msg: String?) {
        if (action.listeners.onActionFail.isNotEmpty()) {
            runListeners(action.listeners.onActionFail)
            return
        }
        if (pauseOnFail) {
            paused.value = true
            onLog("动作失败且未配置失败监听，已暂停（可在悬浮球继续）${msg?.let { "：$it" } ?: ""}")
        }
    }

    /**
     * 重复执行动作体。@return 至少一次迭代成功则为 true。
     *
     * - timesExp：__vars 表达式控制重复次数（执行期求值）
     * - fastRepeatMode：跳过 repeat 间的 gapMs 延迟（对标自动精灵快速重复）
     * - 固定节奏的时间抖动（对数正态，可一键关闭复现精确回放）
     */
    private suspend fun repeatActionBody(action: Action, script: Script): Boolean {
        val times = if (action.timesExp.isNotEmpty()) {
            runCatching { js.eval(action.timesExp, vars.live()) }.getOrNull()
                ?.toString()?.trim()?.toDoubleOrNull()?.toInt() ?: action.times
        } else action.times
        if (action.waitMs > 0 || action.randomWaitMs > 0) {
            // v2.9.4：randomWaitMs = Long.MAX_VALUE 时 +1 溢出成负数，
            // Random.nextLong(负数) 抛 IllegalArgumentException 直接崩脚本。
            val randomBound = if (action.randomWaitMs == Long.MAX_VALUE) Long.MAX_VALUE else action.randomWaitMs + 1
            val base = action.waitMs + if (action.randomWaitMs > 0) Random.nextLong(randomBound) else 0L
            delay(if (jitterEnabled) jitter.intervalMs(base) else base)
        }
        var anyOk = false
        var n = 0
        while (times == 0 || n < times) {
            currentCoroutineContext().ensureActive()
            awaitIfPaused()
            // 中止条件：每次重复迭代前检查（times=0 无限重复时必须靠这里刹停）
            script.abortCondition?.let { evaluateCondition(it, "") }
            val ok = when (action.type) {
                ActionType.RUN_ACTIONS -> runChildren(action, script)
                ActionType.RUN_SCRIPT -> { runSubScript(action); true }
                ActionType.RUN_JS -> {
                    val r = runCatching { js.run(action.jsCode, vars.live()) }
                    r.exceptionOrNull()?.let { onAbnormal?.invoke("JS 执行失败：${it.message}") }
                    true
                }
                else -> executeSingle(action)
            }
            if (ok) anyOk = true
            val gap = if (action.fastRepeatMode) 0L else action.gapMs
            if (gap > 0 && (times == 0 || n < times - 1)) {
                delay(if (jitterEnabled) jitter.uniformDeltaMs(gap) else gap)
            }
            n++
        }
        return anyOk
    }

    private suspend fun runChildren(action: Action, script: Script): Boolean {
        val list = action.children.takeIf { it.isNotEmpty() } ?: return false
        val effective = if (action.limitCount in 1..list.size) list.take(action.limitCount) else list
        val ordered = when (action.order) {
            ListOrder.RANDOM -> effective.shuffled()
            ListOrder.PARALLEL -> effective   // 手势层面串行近似（系统手势本身串行）
            ListOrder.SEQUENTIAL -> effective
        }
        onLog("展开 ${ordered.size} 个子动作${if (action.order != ListOrder.SEQUENTIAL) "（${action.order}）" else ""}")
        val beforeHit = stats.hit; val beforeMiss = stats.miss; val beforeSkip = stats.skip
        runListeners(action.listeners.beforeList)
        var anyOk = false
        ordered.forEach { child ->
            if (child.type != ActionType.GOTO) {
                // 组内每个子动作前也检查中止条件（RUN_ACTIONS 展开后子动作
                // 不走主循环，漏检会让整组跑完才刹停）
                script.abortCondition?.let { evaluateCondition(it, "") }
                if (runAction(child, script)) anyOk = true
            }
        }
        runListeners(action.listeners.afterList)
        val gHit = stats.hit - beforeHit; val gMiss = stats.miss - beforeMiss; val gSkip = stats.skip - beforeSkip
        if (gHit == 0 && gMiss == 0) {
            onLog("本组 ${ordered.size} 个子动作全部跳过（无任何命中）")
        } else {
            onLog("本组 ${ordered.size} 个子动作完成：命中 $gHit、跳过 $gSkip、失败 $gMiss")
        }
        return anyOk
    }

    private suspend fun runSubScript(action: Action) {
        if (depth >= MAX_DEPTH) {
            onAbnormal?.invoke("子脚本运行路径过深（>$MAX_DEPTH 层），已中止")
            return
        }
        depth++
        try {
            // 子脚本由 ScriptStore 层加载后回调执行；此处经回调钩子解耦
            subScriptLoader?.invoke(action.scriptFile, action.runArgs)
        } finally {
            depth--
        }
    }

    // ------------------------------------------------------------ 条件判断

    private suspend fun evaluateCondition(cond: Condition, runCountKey: String = ""): Boolean {
        // 中止条件（对标自动精灵 break）：载荷命中即中止整个脚本。
        // 必须在组合/原生分支之前拦截——ConditionEvaluator 对未知 kind 兜底 false，
        // 漏到这里会让中止条件永远"不命中"而静默失效。
        if (cond.kind == dev.nymph.auto.core.model.ConditionKind.ABORT) {
            if (abortPayloadHit(cond) != cond.invert) {
                val msg = "中止条件命中（${abortDesc(cond)}），脚本中止"
                onLog("⚠ $msg")
                // 归因 ABORT_HIT（属预期内中止，区别于失败），UI 给"确认/调整后重跑"
                onFail?.invoke(FailReason.ABORT_HIT, msg, currentStep)
                throw AbortHit(msg)
            }
            return false
        }
        // 组合条件：递归原生求值子条件。
        // （曾经让组合条件整体走纯表达式引擎，其原生兜底为 false，
        //  导致含图片/文字/颜色子条件的 COMBINE 永远不可能满足——
        //  "第一步就全部跳过"的重大嫌疑根因。）
        if (cond.kind == dev.nymph.auto.core.model.ConditionKind.COMBINE) {
            val results = cond.children.map { evaluateCondition(it, runCountKey) }
            val ok = when (cond.combine) {
                dev.nymph.auto.core.model.Combine.ALL -> results.all { it }
                dev.nymph.auto.core.model.Combine.ANY -> results.any { it }
                dev.nymph.auto.core.model.Combine.N -> results.count { it } >= cond.runCountLimit.coerceAtLeast(1)
            }
            return ok != cond.invert
        }
        // 视觉/节点类条件先在原生侧求值，结果注入 vars 再走表达式（组合条件）
        val native = when (cond.kind) {
            dev.nymph.auto.core.model.ConditionKind.APPEAR_IMAGE ->
                run {
                    val (rd, cd) = imageScale()
                    vision.imageExists(cond.imageData, RoiExpr.resolveRegion(cond.region), cond.imageSimilarity, cond.filters, rd, cd)
                }
            dev.nymph.auto.core.model.ConditionKind.APPEAR_NODE ->
                vision.findNode(cond.nodeId, cond.nodeClass, cond.nodeText, cond.nodeDepthMin, cond.nodeDepthMax) != null
            // 文字/颜色找图找色：走视觉管线（对标自动精灵的文字与颜色条件）
            dev.nymph.auto.core.model.ConditionKind.APPEAR_TEXT ->
                vision.findTextNode(cond.text, cond.textRegex) != null
            // AI 找目标：文字描述映射到检测类别后出现即满足（离线 TFLite）
            dev.nymph.auto.core.model.ConditionKind.APPEAR_AI ->
                vision.findAiObject(cond.aiLabel, cond.aiThreshold, RoiExpr.resolveRegion(cond.region)) != null
            dev.nymph.auto.core.model.ConditionKind.APPEAR_COLOR ->
                vision.findColor(cond.color, cond.colorSimilarity, RoiExpr.resolveRegion(cond.region), cond.coordX, cond.coordY) != null
            // 时间门：系统时间超过设定时刻才满足（对标 timeAfter）
            dev.nymph.auto.core.model.ConditionKind.AFTER_TIME ->
                cond.afterTimeMs > 0 && System.currentTimeMillis() >= cond.afterTimeMs
            else -> null
        }
        if (native != null) return native != cond.invert
        return evaluator.eval(cond, vars.live(), { key -> runCounts[key] ?: 0 }, runCountKey) != cond.invert
    }

    /**
     * 中止条件的载荷求值。载荷经导入器规范化为 children（单个原生条件：
     * 图片/文字/颜色/节点/JS，或组合条件），递归复用 evaluateCondition
     * 的全部求值能力（含 vars 注入后的 JS 表达式）。
     *
     * 无载荷（导入期未识别出任何字段）视为永不命中：空条件意外刹停
     * 整个脚本比"条件失效"更难排查，宁可不触发。
     */
    private suspend fun abortPayloadHit(cond: Condition): Boolean =
        cond.children.isNotEmpty() && cond.children.all { evaluateCondition(it, "") }

    /** 中止条件的人话描述（命中日志用：告诉用户是哪个屏幕元素触发的中止）。 */
    private fun abortDesc(cond: Condition): String =
        cond.children.firstOrNull()?.let { condDesc(it) } ?: "（无载荷）"

    /**
     * zdjl.check(name)：按动作描述（desc）查找其运行条件并求值。
     *
     * 语义对齐自动精灵 JS API 的 check（条件门控）：JS 里可写
     * `if (zdjl.check('等待登录完成')) { ... }`。查找覆盖子动作；
     * 后置断言条件同名时也可命中。未找到返回 false 并记日志（不抛异常，
     * 避免一个拼写错误炸掉整段脚本）。
     */
    suspend fun checkCondition(name: String): Boolean {
        val s = currentScript ?: return false
        val cond = findCondition(s.actions, name)
        if (cond == null) {
            RunLog.w("Script", "check('$name')：未找到同名条件（按动作描述 desc 匹配运行条件）")
            return false
        }
        return evaluateCondition(cond, name)
    }

    private fun findCondition(actions: List<Action>, name: String): Condition? {
        for (a in actions) {
            if (a.desc == name) {
                a.condition?.let { return it }
                a.postCondition?.let { return it }
            }
            findCondition(a.children, name)?.let { return it }
        }
        return null
    }

    /** 条件类型的人话描述（失败日志用，直接告诉用户是哪类条件挂的）。 */
    private fun condDesc(cond: Condition): String = when (cond.kind) {
        dev.nymph.auto.core.model.ConditionKind.APPEAR_IMAGE -> "图片未出现"
        dev.nymph.auto.core.model.ConditionKind.APPEAR_TEXT -> "文字未出现"
        dev.nymph.auto.core.model.ConditionKind.APPEAR_COLOR -> "颜色未出现"
        dev.nymph.auto.core.model.ConditionKind.APPEAR_NODE -> "节点未出现"
        dev.nymph.auto.core.model.ConditionKind.AFTER_TIME -> "未到设定时间"
        dev.nymph.auto.core.model.ConditionKind.COMBINE ->
            "组合条件未满足（${cond.children.size} 个子条件）"
        dev.nymph.auto.core.model.ConditionKind.JS_EXPR -> "JS 表达式为假"
        dev.nymph.auto.core.model.ConditionKind.RANDOM -> "随机未中"
        dev.nymph.auto.core.model.ConditionKind.INTERVAL -> "间隔未到"
        dev.nymph.auto.core.model.ConditionKind.RUN_COUNT -> "运行次数条件未满足"
        dev.nymph.auto.core.model.ConditionKind.ABORT -> "中止条件"
        dev.nymph.auto.core.model.ConditionKind.APPEAR_AI -> "AI 目标未出现"
    }

    /** 条件重复检查（等待浮层数据源：上报"正在等待 X 已等待 Ns / 超时 Ms"）。 */
    private suspend fun repeatCheck(cond: Condition, script: Script, runCountKey: String = ""): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        // v2.7.0：非旧版档位时，用帧同步间隔加速条件轮询（取脚本配置与调参中更快者）。
        // 用户吐槽的「100ms 跟不上高速目标」主要就卡在这个循环里。
        val accelerated = recognitionTuning.pollMode != PollMode.LEGACY_300 &&
            recognitionTuning.pollMode != PollMode.LEGACY_100
        // v2.9.6：无限等待（等待画面出现的门控模式）是慢场景，不再被帧同步档
        // 压成 33ms 全屏 NCC；有限等待保持既有加速语义不变。选择逻辑放在 core
        // WaitGate.pollIntervalMs，纯函数可单测。
        val interval = WaitGate.pollIntervalMs(
            cond.repeatCheckIntervalMs, recognitionTuning.intervalMs,
            infinite = cond.repeatCheckMax == 0, accelerated = accelerated,
        )
        val timeoutMs = if (cond.repeatCheckMax > 0) cond.repeatCheckMax * interval else 0L
        val desc = condDesc(cond)
        var tried = 0
        try {
            while (cond.repeatCheckMax == 0 || tried < cond.repeatCheckMax) {
                currentCoroutineContext().ensureActive()
                awaitIfPaused()
                // 中止条件：等待轮询每轮也检查（repeatCheckMax=0 是无限等待，
                // 没有这个检查点就可能永远等不到刹停）
                script.abortCondition?.let { evaluateCondition(it, "") }
                onWaitStatus?.invoke(
                    WaitStatus(desc, android.os.SystemClock.elapsedRealtime() - t0, timeoutMs),
                )
                if (evaluateCondition(cond, runCountKey)) return true
                delay(interval)
                pulse(desc)
                tried++
            }
            return false
        } finally {
            onWaitStatus?.invoke(null)
        }
    }

    // ------------------------------------------------------------ 单步动作派发

    /** 单步执行。@return true=成功（供 onActionOk/onActionFail 与组级判定）。 */
    private suspend fun executeSingle(action: Action): Boolean {
        val f = frame ?: run {
            onAbnormal?.invoke("无法获取屏幕尺寸（无障碍服务未连接或显示信息不可用），已中止")
            throw ScriptAbort("无法获取屏幕尺寸")
        }
        val (x, y) = resolveXY(action.x, action.y, action.unit, f)
        val (x2, y2) = resolveXY(action.x2, action.y2, action.unit, f)
        val jx = jitter(action.jitterPx, f.width)
        val jy = jitter(action.jitterPx, f.height)
        val cfg = visualConfigProvider()
        // 最终派发坐标（含 jitter / offset），可视化与真实手势共用，零误差
        val fx = (x + jx).toFloat(); val fy = (y + jy).toFloat()
        val f2x = (x2 + jx).toFloat(); val f2y = (y2 + jy).toFloat()
        fun pointCue(kind: VisualKind, dur: Long) = Cue(kind, floatArrayOf(fx, fy), dur)

        when (action.type) {
            ActionType.TAP -> return dispatch(
                gesture.planTap(x + jx, y + jy, action.holdMs), pointCue(VisualKind.TAP, cfg.tapMs),
            )
            ActionType.LONG_PRESS -> return dispatch(
                gesture.planLongPress(x + jx, y + jy, action.holdMs),
                pointCue(VisualKind.LONG_PRESS, action.holdMs.coerceAtLeast(1L)),
            )
            ActionType.MULTI_TAP -> {
                val planned = gesture.planMultiTap(x + jx, y + jy, action.holdMs, action.gapMs, action.times.coerceAtLeast(1))
                if (planned != null) return dispatch(planned, pointCue(VisualKind.TAP, cfg.tapMs))
                var ok = true
                repeat(action.times.coerceAtLeast(1)) {
                    if (!dispatch(gesture.planTap(x + jx, y + jy, action.holdMs), pointCue(VisualKind.TAP, cfg.tapMs))) ok = false
                    if (action.gapMs > 0) delay(action.gapMs)
                }
                if (!ok) onAbnormal?.invoke("多击执行失败")
                return ok
            }
            ActionType.SWIPE -> return dispatch(
                gesture.planSwipe(x + jx, y + jy, x2 + jx, y2 + jy, action.durationMs),
                Cue(VisualKind.SWIPE, VisualGeometry.line(fx, fy, f2x, f2y), action.durationMs),
            )
            ActionType.CURVE -> {
                val cx = resolveCoord(action.cx, f.width, action.unit, scriptRefW).toFloat()
                val cy = resolveCoord(action.cy, f.height, action.unit, scriptRefH).toFloat()
                return dispatch(
                    gesture.planCurve(
                        x, y,
                        resolveCoord(action.cx, f.width, action.unit, scriptRefW),
                        resolveCoord(action.cy, f.height, action.unit, scriptRefH),
                        x2, y2, action.durationMs,
                    ),
                    Cue(VisualKind.SWIPE, VisualGeometry.quad(x.toFloat(), y.toFloat(), cx, cy, x2.toFloat(), y2.toFloat()), action.durationMs),
                )
            }
            ActionType.PINCH_OUT, ActionType.PINCH_IN -> return dispatch(
                gesture.planPinch(x, y, x2, y2, action.durationMs, action.type == ActionType.PINCH_IN),
                Cue(VisualKind.MULTI_TOUCH, floatArrayOf(x.toFloat(), y.toFloat(), x2.toFloat(), y2.toFloat()), action.durationMs),
            )
            ActionType.REPLAY -> {
                val pts = action.path.map { resolveXY(it.x, it.y, action.unit, f).let { (px, py) -> px + jx to py + jy } }
                val raw = FloatArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].first.toFloat() else pts[i / 2].second.toFloat() }
                visualize(VisualKind.SWIPE, VisualGeometry.resample(raw, cfg.maxTrailPoints), action.durationMs)
                val ok = gesture.dispatch(gesture.planReplay(pts, action.durationMs))
                if (!ok) onAbnormal?.invoke("手势回放失败")
                return ok
            }
            ActionType.MULTI_FINGER -> {
                val fingers = action.fingers.map { finger ->
                    finger.startDelayMs to finger.points.map { resolveXY(it.x, it.y, action.unit, f).let { (px, py) -> px to py } }
                }
                val planned = gesture.planMultiFinger(fingers, action.durationMs)
                if (planned == null) {
                    onAbnormal?.invoke("多指手势超出系统限制")
                    return false
                }
                // 每指取起点；最多前 4 指由 reducer 裁剪
                val mf = FloatArray(fingers.size * 2) { i ->
                    val fp = fingers[i / 2].second.firstOrNull() ?: (0 to 0)
                    if (i % 2 == 0) fp.first.toFloat() else fp.second.toFloat()
                }
                return dispatch(planned, Cue(VisualKind.MULTI_TOUCH, mf, action.durationMs))
            }
            ActionType.CLICK_IMAGE -> {
                val key = imageKey(action.imageData)
                val hit = withRetry(
                    target = "目标图片「$key」",
                    isImage = true,
                    find = {
                        val (rd, cd) = imageScale()
                        // v2.7.0：命中历史外推 → 优先在预测点周围开窄 ROI，
                        // 大幅降低全屏 NCC 计算量，跟上高速移动目标。
                        val roi = predictedRegion(key, RoiExpr.resolveRegion(action.region))
                        vision.findImage(action.imageData, roi, action.imageSimilarity, rd, cd, action.filters)
                            ?.also { observeHit(key, it.cx, it.cy) }
                    },
                    diagnose = {
                        val (rd, cd) = imageScale()
                        vision.diagnoseImage(action.imageData, RoiExpr.resolveRegion(action.region), action.filters, rd, cd)
                    },
                    threshold = action.imageSimilarity,
                )
                if (hit == null) {
                    onFail?.invoke(FailReason.COORD_MISS, "未找到目标图片：$key", currentStep)
                    onAbnormal?.invoke("未找到目标图片（已重试至超时）：$key")
                    return false
                }
                // 检查点闭环（裁定阶段 3.4）：同锚点命中位置漂移超预算 → 布局已变，
                // 直接失败引导定点重拾取，而不是悄悄挪 20px 继续点。
                // 受限档（secure/sensitive 页）不承诺闭环，跳过。
                if (CapabilityMatrix.closedLoopUsable(capabilityTier)) {
                    val last = anchorLast[key]
                    if (last != null) {
                        val dx = (hit.cx - last.first).toDouble()
                        val dy = (hit.cy - last.second).toDouble()
                        val d = kotlin.math.sqrt(dx * dx + dy * dy)
                        val over = drift.record(dx, dy)
                        if (over != null) {
                            val msg = "布局已变：$over（锚点「$key」）"
                            onLog("⚠ $msg")
                            onFail?.invoke(FailReason.COORD_MISS, msg, currentStep)
                            onAbnormal?.invoke(msg)
                            return false
                        }
                        if (d > 0.5) {
                            onLog("锚点「$key」漂移修正 ${"%.1f".format(d)}px（累计 ${"%.1f".format(drift.cumulative)}px）")
                        }
                    }
                    anchorLast[key] = hit.cx to hit.cy
                }
                val hx = (hit.cx + action.offsetX).toFloat(); val hy = (hit.cy + action.offsetY).toFloat()
                return dispatch(
                    gesture.planTap(hit.cx + action.offsetX, hit.cy + action.offsetY, action.holdMs),
                    Cue(VisualKind.TAP, floatArrayOf(hx, hy), cfg.tapMs),
                )
            }
            ActionType.CLICK_TEXT -> {
                // 文字目标支持 /正则/ 字面量约定（与自动精灵一致）
                val regex = action.text.startsWith("/") && action.text.endsWith("/") && action.text.length > 2
                val hit = withRetry(
                    target = "目标文字「${action.text}」",
                    isImage = false,
                    find = { vision.findTextNode(action.text, regex) },
                )
                if (hit == null) {
                    onFail?.invoke(FailReason.COORD_MISS, "未找到目标文字：${action.text}", currentStep)
                    onAbnormal?.invoke("未找到目标文字（已重试至超时）：${action.text}")
                    return false
                }
                val tx = (hit.centerX + action.offsetX).toFloat(); val ty = (hit.centerY + action.offsetY).toFloat()
                return dispatch(
                    gesture.planTap(hit.centerX + action.offsetX, hit.centerY + action.offsetY, action.holdMs),
                    Cue(VisualKind.TAP, floatArrayOf(tx, ty), cfg.tapMs),
                )
            }
            ActionType.CLICK_COLOR -> {
                val hit = withRetry(
                    target = "目标颜色 #%06X".format(action.color and 0xFFFFFF),
                    isImage = true,
                    find = { vision.findColor(action.color, action.colorSimilarity, RoiExpr.resolveRegion(action.region), action.coordX, action.coordY) },
                )
                if (hit == null) {
                    onFail?.invoke(FailReason.COORD_MISS, "未找到目标颜色 #%06X".format(action.color and 0xFFFFFF), currentStep)
                    onAbnormal?.invoke("未找到目标颜色（已重试至超时）")
                    return false
                }
                val cx = (hit.first + action.offsetX).toFloat(); val cyy = (hit.second + action.offsetY).toFloat()
                return dispatch(
                    gesture.planTap(hit.first + action.offsetX, hit.second + action.offsetY, action.holdMs),
                    Cue(VisualKind.TAP, floatArrayOf(cx, cyy), cfg.tapMs),
                )
            }
            ActionType.CLICK_NODE -> {
                val hit = withRetry(
                    target = "目标节点 ${nodeDesc(action)}",
                    isImage = false,
                    find = { vision.findNode(action.nodeId, action.nodeClass, action.nodeText, action.nodeDepthMin, action.nodeDepthMax) },
                )
                if (hit == null) {
                    onFail?.invoke(FailReason.COORD_MISS, "未找到目标节点：${nodeDesc(action)}", currentStep)
                    onAbnormal?.invoke("未找到目标节点（已重试至超时）：${nodeDesc(action)}")
                    return false
                }
                val nx = (hit.centerX + action.offsetX).toFloat(); val ny = (hit.centerY + action.offsetY).toFloat()
                return dispatch(
                    gesture.planTap(hit.centerX + action.offsetX, hit.centerY + action.offsetY, action.holdMs),
                    Cue(VisualKind.TAP, floatArrayOf(nx, ny), cfg.tapMs),
                )
            }
            ActionType.CLICK_AI -> {
                // AI 找目标（对标自动精灵"AI识别"）：文字描述 → COCO 类别 → 检测框。
                // 描述映射不到离线模型类别时 AiDetector 内部已记归因日志，这里
                // 统一按 AI_NO_MATCH 报（重试用图片/文字拾取更可靠）。
                val hit = withRetry(
                    target = "AI 目标「${action.aiLabel}」",
                    isImage = false,
                    find = { vision.findAiObject(action.aiLabel, action.aiThreshold, RoiExpr.resolveRegion(action.region)) },
                )
                if (hit == null) {
                    onFail?.invoke(FailReason.AI_NO_MATCH, "AI 未识别到目标：${action.aiLabel}", currentStep)
                    onAbnormal?.invoke("AI 未识别到目标（已重试至超时）：${action.aiLabel}")
                    return false
                }
                onLog("AI 命中 ${hit.label}（${"%.2f".format(hit.score)}）@ ${hit.cx},${hit.cy}")
                val ax = (hit.cx + action.offsetX).toFloat(); val ay = (hit.cy + action.offsetY).toFloat()
                return dispatch(
                    gesture.planTap(hit.cx + action.offsetX, hit.cy + action.offsetY, action.holdMs),
                    Cue(VisualKind.TAP, floatArrayOf(ax, ay), cfg.tapMs),
                )
            }
            ActionType.KEY -> return KeyMapper.perform(action.keyName)
            ActionType.INPUT -> {
                val ok = nodeFinder.setText(action.text)
                if (!ok) onAbnormal?.invoke("未找到输入框")
                return ok
            }
            ActionType.OPEN_APP, ActionType.CLOSE_APP -> {
                if (!TargetPackages.isLaunchableTarget(action.packageName)) {
                    // 旧版录制脚本可能残留系统组件窗口动作：无启动入口、无需打开，
                    // 跳过而非失败，避免回放第一步就中断。
                    RunLog.i("Runner", "跳过系统组件窗口动作：${action.packageName}")
                    return true
                }
                return AppLauncher.run(action.packageName, action.restart)
            }
            ActionType.OPEN_LINK -> return AppLauncher.openLink(action.linkUrl)
            ActionType.SET_VAR -> { vars.set(action.varName, resolveVarValue(action)); return true }
            ActionType.CAPTURE_TO_VAR -> { captureToVar(action); return true }
            ActionType.WAIT -> {
                // 长等待是脚本的正常语��，不能让看门狗误判为卡死：
                // 分段睡眠，每段刷新心跳（同时保持取消及时响应）。
                var left = action.waitMs.coerceAtLeast(0L)
                while (left > 0) {
                    val slice = minOf(left, 1000L)
                    delay(slice)
                    left -= slice
                    pulse("等待 ${action.waitMs}ms")
                }
                return true
            }
            ActionType.TOAST -> { onLog("[提示] ${action.text}"); return true }
            ActionType.STOP -> { onAbnormal?.invoke("脚本已停止"); throw StopScript() }
            else -> return true
        }
    }

    private suspend fun captureToVar(action: Action) {
        val value: Any? = when (action.varType) {
            dev.nymph.auto.core.model.VarType.COORD, dev.nymph.auto.core.model.VarType.REGION ->
                run {
                    val (rd, cd) = imageScale()
                    vision.findImage(action.imageData, RoiExpr.resolveRegion(action.region), action.imageSimilarity, rd, cd, action.filters)
                }?.let { "${it.cx},${it.cy}" }
            dev.nymph.auto.core.model.VarType.NODE ->
                vision.findNode(action.nodeId, action.nodeClass, action.nodeText, action.nodeDepthMin, action.nodeDepthMax)
                    ?.let { mapOf("x" to it.centerX, "y" to it.centerY, "text" to it.text) }
            else -> null
        }
        vars.set(action.varName, value ?: "")
    }

    private fun resolveVarValue(action: Action): Any? {
        val expr = action.varValue.trim()
        return if (expr.startsWith("=")) {
            runCatching { js.eval(expr.drop(1), vars.live()) }.getOrNull()
        } else expr
    }

    // ------------------------------------------------------------ 工具

    /** 模板标识：base64 太长，取长度 + 短 hash 供日志定位。 */
    private fun imageKey(b64: String): String =
        if (b64.isEmpty()) "（无模板数据）"
        else "${b64.length}B#${b64.hashCode().toUInt().toString(16).take(6)}"

    private fun nodeDesc(a: Action): String =
        listOf(
            a.nodeText.takeIf { it.isNotEmpty() }?.let { "文本=$it" },
            a.nodeId.takeIf { it.isNotEmpty() }?.let { "id=$it" },
            a.nodeClass.takeIf { it.isNotEmpty() }?.let { "class=$it" },
        ).filterNotNull().joinToString(" ").ifEmpty { "（无节点条件）" }

    /**
     * v2.7.0：把一次像素命中换算成**百分比**后喂给该目标的运动预测器。
     * 百分比单位与 [predictedRegion] 的 100×100 参考系一致，避免依赖具体分辨率。
     */
    private fun observeHit(key: String, cxPx: Int, cyPx: Int) {
        val f = frame ?: return
        if (f.width <= 0 || f.height <= 0) return
        motionByKey.getOrPut(key) { MotionPredictor() }.observe(
            100.0 * cxPx / f.width,
            100.0 * cyPx / f.height,
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    /**
     * v2.7.0：用历史命中点外推下一帧位置，把搜索范围收窄到预测点周围。
     * 与脚本自带 region 取**交集**，绝不越出用户限定的搜索区域；关闭调参、
     * 样本不足或预测陈旧时原样返回，行为与 v2.6.0 完全一致。
     */
    private fun predictedRegion(key: String, fallback: Region): Region {
        if (!recognitionTuning.narrowRoi || !recognitionTuning.motionPrediction) return fallback
        val roi = motionByKey[key]
            ?.predictRoi(android.os.SystemClock.elapsedRealtime(), 100, 100, 12.0)
            ?: return fallback
        val l = kotlin.math.max(roi.left, fallback.leftPct)
        val t = kotlin.math.max(roi.top, fallback.topPct)
        val r = kotlin.math.min(roi.right, fallback.rightPct)
        val b = kotlin.math.min(roi.bottom, fallback.bottomPct)
        if (r - l < 1.0 || b - t < 1.0) return fallback
        return Region(l, t, r, b)
    }

    /**
     * 点击类目标的轮询重试：首次未命中后在 retryWindowMs 内按 retryIntervalMs
     * 间隔重试，而不是"失去目标就罢工"秒跳。耗尽才记未命中 + dump 失败截图 +
     * 输出最佳匹配分数归因。
     */
    private suspend fun <T> withRetry(
        target: String,
        isImage: Boolean,
        find: suspend () -> T?,
        diagnose: (suspend () -> Double)? = null,
        threshold: Int = 0,
    ): T? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var attempt = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                awaitIfPaused()
                val r = find()
                if (r != null) {
                    stats.hit++
                    consecutiveMiss = 0
                    if (attempt > 0) {
                        val ms = android.os.SystemClock.elapsedRealtime() - t0
                        onLog("$target 命中（第 ${attempt + 1} 次尝试，${ms}ms）")
                    }
                    return r
                }
                attempt++
                val elapsed = android.os.SystemClock.elapsedRealtime() - t0
                if (retryWindowMs <= 0 || elapsed >= retryWindowMs) {
                    stats.miss++
                    val sb = StringBuilder("$target 未命中（${attempt} 次尝试 / ${elapsed}ms，窗口 ${retryWindowMs}ms）")
                    if (isImage) {
                        dumpMissFrame(target)
                        val best = diagnose?.invoke() ?: -1.0
                        if (best >= 0) {
                            val th = threshold.coerceIn(1, 100) / 100.0
                            sb.append("；最佳 NCC ${"%.2f".format(best)} / 阈值 ${"%.2f".format(th)}")
                            sb.append(
                                when {
                                    best < 0.45 -> "（界面大概率没有该元素，或屏幕方向/分辨率与脚本错配）"
                                    best < th -> "（元素疑似在但匹配参数不佳：可重截模板或调低相似度）"
                                    else -> ""
                                },
                            )
                        }
                    }
                    onLog(sb.toString())
                    // v2.2.0 真机修复：脚本无限循环且目标一直不出现时，旧版每
                    // 2s 就重跑一轮、每次都压缩一张失败截图，90 秒刷几十张 PNG，
                    // 空转烧电且写满调试目录。这里按「连续未命中」次数线性退避
                    // 并封顶：只降频、绝不终止脚本（等待目标出现的正常场景不受影响），
                    // 一旦命中立即清零恢复。退避前刷新心跳，避免被 120s 看门狗误杀。
                    consecutiveMiss++
                    if (consecutiveMiss >= MISS_BACKOFF_AFTER) {
                        val backoffMs = ((consecutiveMiss - MISS_BACKOFF_AFTER + 1)
                            .coerceAtMost(6) * 400L)
                        if (consecutiveMiss == MISS_BACKOFF_AFTER || consecutiveMiss % 8 == 0) {
                            onLog("连续 $consecutiveMiss 次未命中，空转降频 ${backoffMs}ms（脚本未中止，目标出现即恢复）")
                        }
                        pulse()
                        delay(backoffMs)
                    }
                    return null
                }
                // 等待浮层：上报"正在等待 X 已等待 Ns / 窗口 Mms"
                onWaitStatus?.invoke(WaitStatus(target, elapsed, retryWindowMs))
                // v2.7.0：由 RecognitionTuning 决定采样间隔（默认 33ms 帧同步，
                // 旧版为硬编码 300ms）。物理下限见 ADR，不宣称 1ms。
                delay(recognitionTuning.intervalMs)
            }
        } finally {
            onWaitStatus?.invoke(null)
        }
    }

    /** 图片类重试耗尽：dump 当前屏幕帧到 debugDumpDir（限最近 5 张），返回路径或 null。 */
    private suspend fun dumpMissFrame(target: String): String? {
        val dir = debugDumpDir ?: return null
        // 同一目标最多留 MAX_MISS_DUMPS_PER_TARGET 张现场：无限循环里
        // 每次未命中都全尺寸 PNG 压缩写盘是纯浪费，调试包只需代表性快照。
        val n = missDumpCounts[target] ?: 0
        if (n >= MAX_MISS_DUMPS_PER_TARGET) return null
        missDumpCounts[target] = n + 1
        val path = vision.dumpFrame(dir, target) ?: return null
        val tail = if (n + 1 == MAX_MISS_DUMPS_PER_TARGET)
            "（该目标后续失败截图已抑制，避免无限循环刷盘）" else ""
        onLog("已保存失败现场截图：$path$tail")
        RunLog.i("Script", "失败现场截图：$path")
        return path
    }

    /** 一次手势的可视化描述；null = 该动作不画特效（无触摸动作）。 */
    private class Cue(val kind: VisualKind, val points: FloatArray, val durationMs: Long)

    /**
     * 动作派发统一入口：先（可选）画可视化，再真正执行手势。
     * cue 为 null 时与 v2.3.0 完全一致，零额外开销。
     */
    private suspend fun dispatch(planned: GestureEngine.PlannedGesture, cue: Cue? = null): Boolean {
        if (cue != null) visualize(cue.kind, cue.points, cue.durationMs)
        if (!gesture.dispatch(planned)) {
            onAbnormal?.invoke("手势执行失败，已中止")
            return false
        }
        return true
    }

    /**
     * 发送可视化 cue（数据来自最终手势坐标，绝不猜用户手指）。
     * 落点预告仅当用户主动开启 previewEnabled 时才插入 delay——默认配置
     * 不增加任何等待。所有回调都 runCatching 包裹，可视化异常绝不影响脚本。
     */
    private suspend fun visualize(kind: VisualKind, points: FloatArray, durationMs: Long) {
        val sink = onVisualCue ?: return // 未接线：短路，零开销
        val cfg = visualConfigProvider()
        if (!cfg.enabled || !cfg.accepts(kind)) return
        if (cfg.previewEnabled && cfg.previewMs > 0 && kind != VisualKind.PREVIEW) {
            emitCue(sink, VisualKind.PREVIEW, points, cfg.previewMs, cfg)
            pulse("动作落点预告") // 刷新看门狗心跳，避免预告等待被误杀
            delay(cfg.previewMs)
        }
        emitCue(sink, kind, points, durationMs, cfg)
    }

    private fun emitCue(
        sink: (VisualCue) -> Unit,
        kind: VisualKind,
        points: FloatArray,
        durationMs: Long,
        cfg: VisualConfig,
    ) {
        // 关闭数字角标时把 step 置 0，渲染层据此跳过角标（配置表驱动，不改渲染）。
        val step = if (cfg.stepBadgeEnabled) currentStep else 0
        runCatching {
            sink(
                VisualCue(
                    id = ++cueSeq,
                    kind = kind,
                    // 与 VisualizerLayer.doFrame(frameTimeNanos/1e6) 同为 nanoTime 毫秒基准
                    createdAtMs = System.nanoTime() / 1_000_000L,
                    durationMs = durationMs,
                    pointsPx = points,
                    step = step,
                    totalSteps = totalSteps,
                ),
            )
        }.onFailure { RunLog.w("Visual", "可视化 cue 发送失败：${it.javaClass.simpleName}") }
    }

    /**
     * v2.5.0 回放起始界面校验。
     *
     * 真机录屏中，用户在应用抽屉里连续翻页录制，回放却停在日历界面，
     * 15 次翻页全部作用在错误界面上，且每步都「执行成功」——脚本整体
     * 与实际需求完全不符。被动无障碍录制无法还原页面状态（滚动位置、
     * 列表层级都拿不到），因此这里不猜、不硬凑，只在动作开始前比对
     * 录制起始包名与当前前台包名：不一致就明确中止并告知用户该怎么做，
     * 而不是把一堆动作打在错误界面上还报成功。
     *
     * 老脚本 / 手写脚本没有 startPackage，直接 SKIP，行为与 v2.4.0 完全一致。
     */
    var sceneGuardEnabled: Boolean = true

    private suspend fun checkStartScene(script: Script): Boolean {
        // v2.9.1：结构化判定脚本是否含直接派发的坐标手势。此前守卫只比包名，
        // 应用抽屉 / 桌面主屏 / 搜索页同属一个 launcher 包名 → PROCEED 静默放行，
        // 或 rootInActiveWindow() 取不到 / 停在自家界面 → SKIP 静默放行；
        // 38 个翻页手势全部打在错误界面，日志里连一行界面校验提示都没有。
        val positional = SceneGuard.hasPositionalActions(script.actions.map { it.type })
        if (!sceneGuardEnabled || script.startPackage.isBlank()) {
            // 守卫关闭 / 老脚本无起始包名：仍把位置风险作为非阻断提示送达，
            // 不再像旧版那样让编译期告警只躺在 description 里。
            if (positional) {
                emitSceneNotice(
                    SceneGuard.positionalWarning(
                        script.startPackage,
                        "界面校验已关闭或脚本未记录起始包名",
                    ),
                )
            }
            return true
        }
        val current = runCatching {
            AutoClickService.live()?.rootInActiveWindow()?.packageName?.toString()
        }.getOrNull()
        val verdict = SceneGuard.decide(
            recordedPackage = script.startPackage,
            currentPackage = current,
            launchable = { TargetPackages.isLaunchableTarget(it) },
            hasPositionalActions = positional,
        )
        return when (verdict.decision) {
            SceneGuard.Decision.SKIP, SceneGuard.Decision.PROCEED -> true
            SceneGuard.Decision.PROCEED_WITH_WARNING -> {
                // 放行，但告警必达：onLog 进日志页 + RunLog.w 落运行日志 +
                // onSceneNotice 弹 toast，用户不必主动翻日志就能看到。
                emitSceneNotice(verdict.message)
                true
            }
            SceneGuard.Decision.RESTORE_APP -> {
                onLog("界面校验：${verdict.message}")
                val ok = AppLauncher.run(script.startPackage, false)
                if (ok) {
                    delay(450L) // 等目标 Activity 起来，避免首批动作打在旧界面
                    // 拉起的只是应用入口页，仍无法还原录制时的具体页面/滚动位置。
                    if (positional) emitSceneNotice(
                        SceneGuard.positionalWarning(
                            script.startPackage,
                            "已自动打开该应用，但页面与滚动位置仍需手动确认",
                        ),
                    )
                    true
                } else {
                    onAbnormal?.invoke("无法自动返回录制时的应用（${script.startPackage}），已中止；请手动打开后重试")
                    false
                }
            }
            SceneGuard.Decision.REQUIRE_MANUAL -> {
                onAbnormal?.invoke("界面与录制时不一致，已中止。${verdict.message}")
                false
            }
        }
    }

    /**
     * 界面风险告警必达：日志页（onLog）+ 运行日志（RunLog.w）+ 悬浮提示（onSceneNotice）。
     * 只告警不阻断——同包名在原理上无法证明界面一致，误报的代价远小于拦死脚本。
     */
    private fun emitSceneNotice(msg: String) {
        onLog("⚠ 界面提示：$msg")
        RunLog.w("Runner", "界面提示：$msg")
        runCatching { onSceneNotice?.invoke(msg) }
    }

    private suspend fun runListeners(list: List<Action>) {
        list.forEach { l -> if (l.type != ActionType.GOTO) runAction(l, Script(), inListener = true) }
    }

    private suspend fun awaitIfPaused() {
        if (paused.value) paused.first { !it }
    }

    /** 坐标解析：% / dp / px + 参考分辨率映射（修复导入脚本换机错位）。 */
    private fun resolveXY(v: Double, v2: Double, unit: CoordUnit, f: Frame): Pair<Int, Int> {
        val x = resolveCoord(v, f.width, unit, scriptRefW)
        val y = resolveCoord(v2, f.height, unit, scriptRefH)
        return x to y
    }

    private var scriptRefW: Int = 0
    private var scriptRefH: Int = 0

    private fun resolveCoord(v: Double, size: Int, unit: CoordUnit, refSize: Int): Int {
        val raw = when (unit) {
            CoordUnit.PCT -> (v / 100.0 * (size - 1)).toInt()
            CoordUnit.PX -> {
                // 绝对像素：按参考分辨率等比映射到当前屏幕（无参考信息时直接用）
                if (refSize > 0) (v * size / refSize).toInt() else v.toInt()
            }
            CoordUnit.DP -> (v * density()).toInt()
        }
        return raw.coerceIn(0, size - 1)
    }

    private fun jitter(j: Int, size: Int): Int =
        if (j <= 0) 0 else Random.nextInt(-j, j + 1)

    private fun density(): Float =
        AutoClickService.live()?.resources?.displayMetrics?.density ?: 3f

    /**
     * 图片模板的尺度基准（v2.3.0 真 bug 修复）。
     *
     * 旧版所有 findImage / diagnoseImage / imageExists 都把 refDpi 传 0.0，
     * [ImageFinder] 的 scalesOf 里 `ratio = if (refDpi > 0.01) curDensity/refDpi
     * else 1.0` 恒等于 1.0 —— 也就是说脚本里记录的 refWidth/refHeight
     * （导入器会写入，坐标映射也用）**从未参与模板缩放**，只在方向告警里
     * 露过面。跨分辨率导入的脚本，模板尺寸与当前屏幕不匹配时必然全灭，
     * 真机日志反复出现「分辨率与脚本错配 / NCC 0.00」正是这个症状。
     *
     * 这里把 (脚本参考屏宽, 当前屏宽) 作为 (refDpi, curDensity) 传入，
     * ratio 即模板应缩放的倍数。脚本未记录参考分辨率时退回 (0.0, 1.0)，
     * 保持原有固定尺度档，不引入回归。
     */
    private fun imageScale(): Pair<Double, Double> {
        val f = frame
        return if (f != null && f.width > 0 && scriptRefW > 0) {
            scriptRefW.toDouble() to f.width.toDouble()
        } else 0.0 to 1.0
    }

    class StopScript : Exception()

    /**
     * 中止条件命中（对标自动精灵 break 条件）：预期内停止整个脚本。
     * 与 [ScriptAbort]（致命错误）区分——命中方在 UI 归因为"预期内中止"，
     * 对应动作是"确认中止条件是否符合预期，或调整后重跑"。
     */
    class AbortHit(message: String) : Exception(message)

    /** 致命错误：继续执行无意义（如屏幕尺寸不可用），中止整个脚本。 */
    class ScriptAbort(message: String) : Exception(message)

    companion object {
        /** 连续未命中达到该值后开始空转退避（每次 +400ms，封顶 2.4s）。 */
        private const val MISS_BACKOFF_AFTER = 3
        /** 同一失败目标本次运行最多保留的现场截图张数。 */
        private const val MAX_MISS_DUMPS_PER_TARGET = 2
        const val MAX_DEPTH = 8

        /**
         * 次数同步变量名（对标自动精灵）：每个动作执行后写入"该动作累计
         * 运行次数"，JS 条件 / RUN_JS 代码可读 vars.__runCount。
         */
        const val RUN_COUNT_VAR = "__runCount"
    }
}

/** 按键映射（IME 未启用时支持 7 个全局键；其余走 IME）。 */
object KeyMapper {
    fun perform(name: String): Boolean {
        val svc = AutoClickService.live() ?: return false
        val g = when (name) {
            "返回键" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "主页键" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "任务键" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "通知栏" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "电源键" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return false // 其余按键需 IME
        }
        return svc.global(g)
    }
}

/** 应用打开/关闭（显式 Intent + 包可见性已声明）。 */
object AppLauncher {
    fun run(pkg: String, restart: Boolean): Boolean {
        val ctx = AutoClickService.live() ?: return false
        return runCatching {
            val pm = ctx.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (restart) {
                val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
            }
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 打开链接（对标自动精灵"打开链接"动作）：Intent.ACTION_VIEW。
     * 无scheme 时补 https://（用户常只写域名）；无任何应用可处理时返回
     * false（由上层记失败日志），不弹系统选择器循环。
     */
    fun openLink(url: String): Boolean {
        val ctx = AutoClickService.live() ?: return false
        val raw = url.trim()
        if (raw.isEmpty()) return false
        val full = if (raw.contains("://")) raw else "https://$raw"
        return runCatching {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(full),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // 先探测再启动：不可处理时不走 startActivity 的抛异常路径，
            // 失败原因可区分（无浏览器 vs 其他）
            if (intent.resolveActivity(ctx.packageManager) == null) return false
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

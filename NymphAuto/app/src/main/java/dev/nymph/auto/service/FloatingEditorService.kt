// SPDX-License-Identifier: MIT
package dev.nymph.auto.service

import java.util.Locale
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import dev.nymph.auto.NymphApp
import dev.nymph.auto.R
import dev.nymph.auto.core.editor.ActionEditGuard
import dev.nymph.auto.core.editor.ExecutionParadigm
import dev.nymph.auto.core.editor.NodeFallback
import dev.nymph.auto.core.editor.FloatingEditorLayout
import dev.nymph.auto.core.editor.OverlayBounds
import dev.nymph.auto.core.editor.OverlayWindowSpec
import dev.nymph.auto.core.editor.PickFeedback
import dev.nymph.auto.core.editor.RoiApplier
import dev.nymph.auto.core.editor.SceneAssist
import dev.nymph.auto.core.editor.SceneList
import dev.nymph.auto.core.editor.ScenePreset
import dev.nymph.auto.core.editor.SceneSelfTest
import dev.nymph.auto.core.editor.SceneTuning
import dev.nymph.auto.core.editor.SceneTrigger
import dev.nymph.auto.core.editor.SceneVerdict
import dev.nymph.auto.core.editor.TapGate
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.store.ScriptRepository
import dev.nymph.auto.core.store.ScriptStore
import dev.nymph.auto.core.vision.ImageFinder
import dev.nymph.auto.core.vision.StableAnchor
import dev.nymph.auto.engine.AutoClickService
import dev.nymph.auto.engine.NodeFinder
import dev.nymph.auto.engine.toCoreImage
import dev.nymph.auto.ui.CoordinatePicker
import dev.nymph.auto.ui.FloatingEditorPanel
import dev.nymph.auto.ui.OverlayWindowMetrics
import dev.nymph.auto.ui.PickerMode
import dev.nymph.auto.ui.PickerRegion
import dev.nymph.auto.ui.PickerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * **系统级悬浮动作编辑器**（v2.8.0 引入，v2.8.1 抗抽搐 + 视觉重做）——
 * 让「编辑动作」脱离 NymphAuto 主界面，可以在其他 APP 界面之上显示、后台可呼出。
 *
 * ## v2.8.1 窗口重排抑制与视觉重做
 *
 * 录屏逐帧可见面板边缘跳动、内容忽多忽少，根因是窗口被高频反复重新测量：
 *  1. 拖动用局部坐标增量 `e.x-lastX` 累加、每个 MOVE 都 `updateViewLayout`
 *     → 改为 **屏幕绝对坐标 `rawX/rawY`** + 1px 阈值（v2.9.8 起同步直提交，
 *        见 [onDragAbsolute]：按帧合并的 `frameScheduled` 自锁会让悬浮球位移能力完全未生效）；
 *  2. `MaxHeightScrollView` 把 `UNSPECIFIED` 压成 `AT_MOST(0)` → core `measureHeight`
 *     按 mode 正确处理；
 *  3. 折叠/展开展来回切 `FLAG_NOT_FOCUSABLE` 引发软键盘弹出/收起循环
 *     → **flags 恒定 NOT_FOCUSABLE**，只在用户主动点输入框时临时清除、失焦恢复；
 *  4. 每次 MOVE 重读 `displayMetrics`（全屏游戏状态栏切换会让 heightPixels 跳）
 *     → 尺寸只缓存一次，配置变化时才刷新；
 *  5. 状态文本空/一行/两行让 `WRAP_CONTENT` 高度变 → 状态行固定两行高度。
 *
 * ## 评估结论所涉缺陷的根因与修法（用资料里的术语，但以本工程源码为准）
 *
 * v2.7.1 的 `ActionEditDialog` 是 Compose `AlertDialog`，画在 `MainActivity`
 * 的视图树里 —— **Activity 生命周期绑定的前台界面**，切到其他 APP 就随
 * `onStop` 消失，也没法在别的 APP 之上呼出。资料里说的「前台绑定悬浮窗」
 * 窗口层级均高于本实现（那些仍属于 Dialog/PopupWindow 窗口）；本工程这个连独立
 * 窗口都不是。
 *
 * 本服务改用真正的 **System Overlay**：
 *  - [WindowManager.addView] 以 `TYPE_APPLICATION_OVERLAY` 添加面板（Android 8+，
 *    旧版回退 `TYPE_PHONE`），与 `FloatBall` / `CoordinatePicker` 同一机制；
 *  - 自身是 `specialUse` **前台服务**，负责维持窗口生命周期：切后台、切到
 *    任意第三方 APP 都持续存在；通知栏提供「呼出 / 收起 / 关闭」入口，
 *    即使本 Activity 已被销毁也能重新呼出；
 *  - 面板 `FLAG_NOT_TOUCH_MODAL` + `FLAG_NOT_FOCUSABLE`，只消费落在自身区域
 *    的触摸，**面板之外照常操作底层 APP**（延续 v2.7.0 非模态穿透原则）。
 *
 * ## 复用而非重写
 *
 * 脚本读写走 `ScriptStore` / `ScriptRepository`（修订+历史，与主界面同一真源）；
 * 动作保存走 `ActionEditGuard.apply`（`copy` 保真，不丢 children/listeners）；
 * 框选 ROI 走 `RoiApplier.apply`（唯一真源，命中数反馈实际值）；
 * 坐标拾取复用 `CoordinatePicker`（同为系统 Overlay，非模态穿透）；
 * 窗口尺寸/折叠/clamp/表单解析走纯 JVM [FloatingEditorLayout]（有单测）。
 *
 * ## 合规
 *
 * 复用既有 `SYSTEM_ALERT_WINDOW`（8 项权限零新增）、零 MediaProjection /
 * VirtualDisplay / MediaCodec / FLAG_SECURE / RECORD_AUDIO、
 * 不订阅无障碍事件、不加 `FLAG_WATCH_OUTSIDE_TOUCH`。
 */
class FloatingEditorService : Service() {

    private lateinit var wm: WindowManager
    private var panel: FloatingEditorPanel? = null
    private var params: WindowManager.LayoutParams? = null

    /** 当前编辑的脚本（内存态；保存时写回 ScriptRepository）。 */
    private var script: Script? = null
    private var actions = mutableListOf<Action>()
    private var index = 0
    private var state = FloatingEditorLayout.State.EXPANDED
    private var winX = 0
    private var winY = 0

    /**
     * v2.8.1：屏幕尺寸只在这里缓存。v2.8.0 每次 MOVE 都重读
     * `resources.displayMetrics`，而沉浸式全屏游戏里状态栏 / 导航栏切换会让
     * `heightPixels` 在两个值之间跳，clamp 边界跟着跳 → 窗口 y 反复跳（抽搐）。
     */
    private var screenW = 0
    private var screenH = 0
    private var density = 0f

    /**
     * v2.9.8：删除 `pendingX/pendingY/frameScheduled` 与 `Choreographer`。
     * 拖动改为同步直提交，位置真源只有 [params] 的 x/y（见 [onDragAbsolute]）。
     */
    private var foregroundStarted = false

    /** v2.9.14：拾取截图在主线程回调后异步跑（无障碍截图是 suspend），Service 生命周期内有效。 */
    private val sceneScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * v2.9.15：一次拾取自动产出的「场景规则」暂存，保存时装配到动作。
     * 新格式三处落盘锁定项（稳定锚点模板 / 相对坐标 / 触发配置）+ 拾取即自测结果。
     * 老流程（手动框 ROI）完全不走这里，行为逐字不变。
     */
    private var pendingSceneRoi: Region? = null
    private var pendingSceneRule: SceneRule? = null
    private var pendingAnchorB64: String = ""
    /**
     * v2.9.16：拾取即自测的**判定结论**。`SceneVerdict` 把三项原始观测量映射成
     * 四态结论与建议动作，界面只呈现结论；原始分数写入日志与诊断明细供复现，
     * 不要求操作者解读分数后自行决定是否重录。
     */
    private var pendingVerdict: SceneVerdict.Assessment? = null
    private var pendingSelfTest: SceneSelfTest.Result? = null
    /**
     * v2.9.25：当前拾取是否装配出了可挂载的稳定锚点。
     *
     * 它的唯一用途是让「点击前门控」与「运行时会不会触发」保持一致：锚点为空时
     * 运行期没有任何画面模板，规则必然 fail-closed，此时颜色匹配再准也不能判命中。
     * 此前判定只看颜色，于是界面出现过「命中」与「未取得有效画面」同屏的矛盾。
     */
    private var pendingAnchorReady: Boolean = false

    /**
     * 拾取代次：截图 + 锚点选取是异步的。用户可能在异步回填前就点「保存」，
     * 或连续拾取两次。每次拾取自增、每次保存消费后自增，异步回填前比对代次，
     * 过期结果直接丢弃，绝不把上一轮的场景载荷污染到下一次保存。
     */
    private var pendingSceneGen: Int = 0

    /**
     * v2.9.21：一次拾取自动派生的辅助素材与有效区域（不新增落盘字段）。
     * 颜色样本写入既有 Action.color / colorSimilarity；有效区域写入既有
     * Action.region / SceneRule.searchRoi。手动框选结果优先于自动收敛结果，
     * 清除后回退自动；两者都随保存落盘、随保存消费。
     */
    private var pendingColorSample: SceneAssist.ColorSample? = null
    private var pendingAutoRoi: Region? = null
    private var pendingManualRoi: Region? = null
    private var lastPickPct: Pair<Double, Double>? = null
    /**
     * v2.9.29：最近一次在画面上确认过的落点，**跨场景保留**。
     *
     * 与 [lastPickPct] 的区别：后者是「当前场景的拾取结果」，保存或切换场景后
     * 会被清空（它参与门控与结果卡，必须与当前场景同生命周期）。而「用当前位置」
     * 要做的是复用上一个场景已经点过的位置，所以必须活得更久。
     */
    private var lastAnyPickPct: Pair<Double, Double>? = null

    /**
     * v2.9.24：装配期在拾取点**实测**到的颜色（ARGB）。
     *
     * 门控复算（清除手动覆盖 / 编辑区域后）必须拿它当观测值。旧实现用
     * `sample.argb` 当观测值，与代表色自己比距离恒为 0，任何区域改动后都会
     * 无条件判「命中」——这不是校验，是自证。缺失时按失败即停处理，不判命中。
     */
    private var pendingObservedArgb: Int? = null

    /** v2.9.21：点击前门控的异步代际令牌，旧任务结果不得覆盖新状态。 */
    private val gateGeneration = TapGate.Generation()

    /** 最近一次门控结论（供状态行与诊断呈现，未命中不派发点击）。 */
    private var lastGateVerdict: TapGate.Verdict? = null
    /** v2.9.22：进程内诊断计数，只服务诊断摘要，不落盘、不联网。 */
    private val fbCounters = PickFeedback.Counters()

    /** 一次拾取自动装配的载荷（只在 [captureSceneAnchor] 与 [save] 之间流转）。 */
    private data class ScenePickOutcome(
        val rule: SceneRule,
        val anchorB64: String,
        val selfTest: SceneSelfTest.Result,
        val verdict: SceneVerdict.Assessment,
        val colorSample: SceneAssist.ColorSample?,
        val convergedRoi: Region?,
        val observedArgb: Int?,
        /** v2.9.31：掩码生成方式，仅用于装配日志（时域差分 / 单帧启发式）。 */
        val maskSource: String = "单帧启发式",
    )

    /**
     * v2.9.23：装配结果显式携带失败阶段。
     *
     * 此前装配失败统一以 null 返回，界面只能呈现「取色失败」，无法区分截图未取到、
     * 裁剪不可用、锚点编码为空与真实采样为空，定位方向会被引偏。此处把阶段作为
     * 一等结果返回，判定仍 fail-closed，不放宽任何放行条件。
     */
    private sealed interface BuildResult {
        data class Ok(val outcome: ScenePickOutcome) : BuildResult
        data class Fail(val failure: TapGate.Failure, val detail: String) : BuildResult
    }

    /** 最近一次装配上下文，只用于诊断摘要与状态呈现。 */
    private var lastCaptureError: Int = -1
    private var lastCaptureMs: Long = -1
    private var lastFrameText: String = "-"
    private var lastSampleCount: Int = -1
    private var lastStageText: String = "-"

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshMetrics()
        RunLog.i("FloatEditor", "悬浮编辑服务已创建")
    }

    private fun refreshMetrics() {
        // v2.9.19：窗口坐标空间为全屏绝对坐标，边界尺寸必须取自窗口指标真源；
        // 沿用「仅在创建 / 配置变更时刷新缓存」的既有约束，拖动热路径不重读。
        val size = OverlayWindowMetrics.screenSize(wm)
        screenW = size.width
        screenH = size.height
        density = resources.displayMetrics.density
    }

    /**
     * 横竖屏 / 折叠屏变化时刷新缓存并按相对锚点重定位，拖动过程本身绝不重读。
     * v2.9.19：边界尺寸改为全屏绝对坐标真源后，配置变化会同时改变边界数值与
     * 宽高方向；先按旧边界占比映射，再交由 [setCollapsed] 做最终边界收敛，
     * 避免窗口停留在旧坐标导致越界不可见。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val previous = OverlayBounds.Size(screenW.coerceAtLeast(1), screenH.coerceAtLeast(1))
        refreshMetrics()
        val current = OverlayBounds.Size(screenW, screenH)
        val p = panel
        val lp = params
        if (previous != current && p != null && lp != null) {
            val h = if (state == FloatingEditorLayout.State.COLLAPSED)
                FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, density)
            else p.measuredExpandedHeight()
            val (nx, ny) = OverlayBounds.reposition(winX, winY, lp.width, h, previous, current)
            winX = nx
            winY = ny
        }
        p?.updateMetrics(screenH, density)
        setCollapsed(state == FloatingEditorLayout.State.COLLAPSED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_TOGGLE -> togglePanel()
            ACTION_COLLAPSE -> setCollapsed(true)
            ACTION_SHOW -> {
                val name = intent.getStringExtra(EXTRA_SCRIPT_NAME)
                if (!name.isNullOrBlank()) load(name, intent.getIntExtra(EXTRA_ACTION_INDEX, 0))
                setCollapsed(false)
            }
        }
        refreshForeground()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ 脚本读写

    private fun load(name: String, at: Int) {
        val s = runCatching { store().load(name) }.getOrNull()
        if (s == null) {
            toast("脚本「$name」不存在或已损坏")
            return
        }
        script = s
        actions = s.actions.toMutableList()
        index = FloatingEditorLayout.step(at, 0, actions.size)
        // v2.9.21：用户可见层统一以「场景」表述，不呈现编号、不使用「动作」叫法。
        RunLog.i("FloatEditor", "载入「$name」${actions.size} 个场景，当前定位 ${SceneList.currentTitle(actions.getOrNull(index)?.desc)}")
        bindCurrent()
    }

    private fun store() = ScriptStore(File(filesDir, "scripts"))

    private fun repo() = ScriptRepository(store(), File(filesDir, "scripts/.history"))

    private fun bindCurrent() {
        val p = panel ?: return
        refreshSceneList()
        if (actions.isEmpty()) {
            p.bind(FloatingEditorLayout.Form(), SceneList.TITLE_CURRENT, true)
            p.setStatus("脚本暂无场景，点「${SceneList.LABEL_NEW}」创建")
            p.showAssist("待生成", "待生成", "自动")
            p.setTuningBaseline(null)
            p.showGuide(true)
            return
        }
        p.showGuide(false)
        val a = actions[index]
        // v2.9.21：标题用场景名（未命名回落到「当前场景」），不呈现编号、
        // 不出现「动作 / 场景 N / M」。下标仅用于列表定位，不构成执行顺序。
        p.bind(
            FloatingEditorLayout.formOf(a),
            SceneList.currentTitle(a.desc),
            ActionEditGuard.supportsSimpleEdit(a),
        )
        // v2.9.17：先呈现执行范式同构卡片（两种范式共用四字段），再把落盘的带状态
        // 触发参数折叠回意图层主层摘要与四维后果明细；界面主层不呈现内核参数名。
        val kind = ExecutionParadigm.of(script?.sceneDriven == true)
        val k = script?.sceneMaxFiringPerRound ?: 1
        val ruleCount = actions.count { it.sceneRule != null }
        p.setParadigm(ExecutionParadigm.describe(kind, k, ruleCount))
        p.setSceneIntent(sceneIntentSummary(a))
        p.setIntentDetail(sceneExplanation(a)?.detailText)
        // v2.9.32：把当前落盘判据的生效值作为「留空沿用」基线呈现，
        // 用户据此决定是否需要显式覆盖，而不是面对一个无参照的空输入框。
        p.setTuningBaseline(a.sceneRule)
        renderAssist()
        renderResult()
    }

    /**
     * v2.9.22：把判定结论、自动派生素材与纠偏动作收敛到同一张结果卡。
     *
     * 卡片是「一键纠偏」的落点：结论、坐标、颜色、区域、置信度与四个动作同卡可见，
     * 未命中时主修复按钮随原因变化（扩大区域 / 重新采样 / 重试 / 重新拾取）。
     * 仅在存在拾取结果时呈现，不改变无拾取状态下的默认窗口高度。
     */
    private fun renderResult() {
        val p = panel ?: return
        // 判定中尚无自动收敛结果时，用拾取瞬间的初步区域占位，保证「判定中」可见。
        val auto = pendingAutoRoi ?: pendingSceneRoi
        val pick = lastPickPct
        val v = lastGateVerdict
        if (auto == null || pick == null || v == null) {
            p.showResult(null)
            return
        }
        val effective = SceneAssist.resolveRoi(auto, pendingManualRoi)
        val manual = effective.mode == SceneAssist.RoiMode.MANUAL
        val sample = pendingColorSample
        val conf = PickFeedback.confidenceOf(sample)
        val g = PickFeedback.guidanceFor(v.reason, manual)
        val colorText = sample?.let { SceneAssist.colorSummary(it) } ?: "未采集"
        val meta = "坐标 ${SceneList.coordinateLabel(pick.first, pick.second)}\n" +
            "颜色 $colorText\n区域 ${SceneAssist.roiSummary(effective.region)}"
        val mode = PickFeedback.roiModeLabel(effective.mode) +
            " · 置信度 ${PickFeedback.confidenceLabel(conf)} · " +
            PickFeedback.confidenceHint(conf) + PickFeedback.manualOverlayNote(manual)
        p.showResult(
            FloatingEditorPanel.Result(
                headline = g.headline,
                meta = meta,
                mode = mode,
                fixLabel = g.primaryLabel,
                manual = manual,
                pending = v.reason == TapGate.Reason.PENDING,
                hit = v.reason == TapGate.Reason.PASS,
            ),
        )
    }

    /** v2.9.21：场景列表 = 只读坐标摘要行，点行进详情；单位恒为「场景」。 */
    private fun refreshSceneList() {
        val labels = actions.map { SceneList.coordinateLabel(it.x, it.y) }
        panel?.bindSceneList(SceneList.rows(labels, index))
    }

    /** 第二视觉层级状态条：颜色样本 / 容差 / 有效区域（手动优先，可回退）。 */
    private fun renderAssist() {
        val auto = pendingAutoRoi ?: actions.getOrNull(index)?.region
        val sample = pendingColorSample
        val resolved = if (auto != null) SceneAssist.resolveRoi(auto, pendingManualRoi) else null
        val colorText = sample?.let { SceneAssist.colorSummary(it) } ?: "拾取后自动生成"
        val roiText = resolved?.let { SceneAssist.roiSummary(it.region) } ?: "待生成"
        val modeText = when (resolved?.mode) {
            SceneAssist.RoiMode.MANUAL -> "手动覆盖"
            else -> "自动"
        }
        panel?.showAssist(colorText, roiText, modeText)
    }

    /**
     * v2.9.17：把 [Action.sceneRule] 折叠回意图后果说明。无场景规则或参数组合
     * 落在预设之外时返回 null，由面板降级为明确说明，不误报为某个预设档位。
     * 本方法只读，不参与任何执行语义，也不改写已调好的参数。
     */
    private fun sceneExplanation(a: Action): ScenePreset.Explanation? {
        val rule = a.sceneRule ?: return null
        val k = script?.sceneMaxFiringPerRound ?: 1
        val intent = ScenePreset.fromRule(rule, k) ?: return null
        return ScenePreset.explain(intent, k)
    }

    /** 主层一句话摘要；非预设组合降级为明确说明。 */
    private fun sceneIntentSummary(a: Action): String? {
        if (a.sceneRule == null) return null
        return sceneExplanation(a)?.headline
            ?: "自定义触发策略：参数已在源码视图调整，界面不改写"
    }

    private fun typeName(a: Action): String = when (a.type.name) {
        "TAP" -> "点击"; "LONG_PRESS" -> "长按"; "MULTI_TAP" -> "双击"
        "SWIPE" -> "滑动"; "CLICK_IMAGE" -> "找图"; "CLICK_TEXT" -> "找文字"
        "CLICK_COLOR" -> "找色"; "CLICK_AI" -> "AI"; "CLICK_NODE" -> "节点"
        "WAIT" -> "等待"; else -> a.type.name
    }

    /** 保存当前表单到脚本（保真 + ROI 应用 + 修订历史）。 */
    private fun save(form: FloatingEditorLayout.Form) {
        val base = script
        if (base == null) { toast("未载入脚本"); return }
        if (actions.isEmpty()) { toast("脚本为空，先点「＋ 新增」"); return }
        val original = actions[index]
        // 非简易类型：apply 原样返回，绝不静默改动作 / 丢字段
        val edited = ActionEditGuard.apply(original, FloatingEditorLayout.toPatch(form))
        if (!ActionEditGuard.supportsSimpleEdit(original)) {
            toast("场景「${SceneList.currentTitle(original.desc)}」不在简易编辑范围，未改动（请到源码视图编辑）")
            return
        }
        // 框选的 ROI 作用到脚本内所有真正读取 region 的位置（唯一真源）
        var next = base.copy(actions = actions.toList().toMutableList().also { it[index] = edited })
        // v2.9.13：全新脚本（ref 尚未初始化）以当前屏幕方向为参考，避免竖屏默认值
        // 在用户横屏手输/拾取时被预检「屏幕方向与脚本相反」误拦。
        if (next.refWidth <= 0 || next.refHeight <= 0) {
            next = next.copy(refWidth = screenW, refHeight = screenH)
        }
        val region = form.region
        val sceneRoi = pendingSceneRoi
        var roiHits = 0
        var sceneAttached = false
        var sceneGated = false
        when {
            // v2.9.15：本次经过「屏幕拾取」→ 装配「稳定局部锚点 + 相对匹配坐标 +
            // 带状态触发」的新格式 SceneRule（三处落盘语义一次定死）。自动 ROI 只作用于
            // 这一条动作，绝不跑 RoiApplier 全局应用（否则会污染脚本内其它找图动作）。
            //
            // 截图异步尚未回填（pendingSceneRule=null）时仍写一条只有 searchRoi 的规则，
            // anchorB64 为空 → attachSceneRule 返回 hasGate=false → 运行时该规则
            // fail-closed 不触发（A4），不回退至无画面校验的顺序触发，也不误报启用场景判定。
            sceneRoi != null -> {
                val rule = pendingSceneRule ?: SceneRule(searchRoi = sceneRoi)
                val (gated, hasGate) = SceneTrigger.attachSceneRule(
                    next.actions[index], rule, pendingAnchorB64,
                )
                // v2.9.21：颜色辅助素材与有效点击区域写入既有字段，落盘结构零改动。
                // 有效区域 = 手动覆盖 > 颜色收敛自动区域（清除手动即回退自动）。
                // SceneRule.searchRoi 沿用稳定锚点模板搜索范围，运行时画面门控语义不变。
                val effectiveRoi = SceneAssist.resolveRoi(
                    pendingAutoRoi ?: sceneRoi, pendingManualRoi,
                ).region
                val sample = pendingColorSample
                val armed = if (sample != null && sample.valid) {
                    gated.copy(
                        color = sample.argb.toLong() and 0xFFFFFFFFL,
                        colorSimilarity = sample.tolerance,
                        region = effectiveRoi,
                    )
                } else {
                    gated.copy(region = effectiveRoi)
                }
                next = next.copy(
                    actions = next.actions.toMutableList().also { it[index] = armed },
                    sceneDriven = true,
                )
                sceneAttached = true
                sceneGated = hasGate
                RunLog.i(
                    "FloatEditor",
                    "装配场景规则：ROI=${sceneRoi.expr} 有效区域=${effectiveRoi.expr} " +
                        "稳定锚点门控=$hasGate 颜色样本=${sample?.let { SceneAssist.colorSummary(it) } ?: "未采集"} " +
                        "相似度=${rule.similarity} 相对坐标=${rule.usesRelativeClick} " +
                        "触发=${rule.triggerMode} 掩码=${rule.maskRects.size} 块 " +
                        "取色证据=${if (rule.colorArgb != 0) "已落盘并被运行期消费" else "无"} " +
                        "跨帧下界=${rule.crossFrameFloor} 源尺寸=${rule.templateSrcW}×${rule.templateSrcH}",
                )
            }
            // 手动框选 ROI：保持 v2.7.1 起的既有「应用到脚本内所有读取 region 处」语义
            region != null -> {
                val (withRoi, hits) = RoiApplier.apply(next, region)
                next = withRoi
                roiHits = hits
            }
        }
        // v2.9.32 拾取后判据参数精调：统一在写盘前把表单显式覆盖钳制后写回
        // [SceneRule]。置于所有分支之后，是为覆盖「拾取后仅调整参数再保存」路径——
        // 该路径下 pendingSceneRoi 已在上一轮置空，旧实现的 sceneRule 完全不更新，
        // 参数改动被静默丢弃。空配置时本块整体跳过，未展开高级区 / 老脚本零回归。
        // 门控条件的相似度与场景判据同源同步，避免源码视图出现两个不一致的阈值。
        if (SceneTuning.hasOverride(form.tuning)) {
            val cur = next.actions[index]
            val base = cur.sceneRule
            if (base != null) {
                val tunedRule = SceneTuning.applyTo(base, form.tuning)
                // formOf 会回显全部当前值，故回显后未修改时 applyTo 是恒等变换。
                // 仅在参数实际变化时才写回，避免每次保存都重建规则 / 门控并记录冗余日志。
                if (tunedRule != base) {
                    val synced = cur.copy(
                        sceneRule = tunedRule,
                        condition = cur.condition?.copy(
                            imageSimilarity = tunedRule.similarity.coerceIn(1, 100),
                        ),
                    )
                    next = next.copy(actions = next.actions.toMutableList().also { it[index] = synced })
                    RunLog.i("FloatEditor", "应用场景判据参数：${SceneTuning.summary(tunedRule)}")
                }
            }
        }
        val err = runCatching { repo().save(next) }.exceptionOrNull()
        if (err != null) {
            RunLog.e("FloatEditor", "保存失败：${err.javaClass.simpleName}: ${err.message}")
            toast("保存失败：${err.javaClass.simpleName}")
            return
        }
        // 同步内存态：script 的 actions 与 ROI 结果都要跟上，避免连续保存丢改动
        script = next
        actions = next.actions.toMutableList()
        val verdict = pendingVerdict
        // 场景载荷是一次性的，消费掉，避免下一次保存重复装配；代次自增让在途的
        // 异步截图/锚点回填直接作废（否则会把上一轮载荷写进下一次保存）。
        pendingSceneRoi = null
        pendingSceneRule = null
        pendingAnchorB64 = ""
        pendingAnchorReady = false
        pendingVerdict = null
        pendingSelfTest = null
        pendingSceneGen++
        // v2.9.21：辅助载荷随保存消费；推进门控代次作废在途判定，旧结果不覆盖新状态。
        pendingColorSample = null
        pendingObservedArgb = null
        pendingAutoRoi = null
        pendingManualRoi = null
        lastPickPct = null
        lastGateVerdict = null
        gateGeneration.cancel()
        val label = SceneList.currentTitle(actions.getOrNull(index)?.desc)
        val msg = buildString {
            append("✓ 已保存「$label」")
            if (sceneAttached) {
                when {
                    verdict != null && verdict.usable -> append(" · ${verdict.headline}")
                    verdict != null -> append(" · ${verdict.headline}${verdict.action?.let { "：$it" } ?: ""}")
                    sceneGated -> append(" · 已启用稳定锚点画面门控")
                    else -> append(" · 未取到稳定锚点：该场景不触发，请重新拾取")
                }
            } else if (roiHits > 0) append(" · 区域应用到 $roiHits 处")
            else if (region != null) append(" · 区域未命中目标（脚本内无可应用目标）")
        }
        // v2.9.16 / 决策点引导：首次装配场景规则时，在用户做出选择的当下解释
        // 「场景触发」与「顺序执行」的差异，并给出显式退路（仅提示一次，不打断保存）。
        //
        // v2.9.25：这个引导语此前会无条件覆盖保存结论，于是首次保存一个「没有稳定
        // 锚点」的场景时，用户看到的仍是「已按场景触发保存」—— 而他刚保存的东西
        // 运行时一步都不会触发。失败结论的优先级必须高于任何引导文案。
        if (sceneAttached && (!sceneGated || verdict != null && !verdict.usable)) {
            // v2.9.36：判别度不足（usable=false，如命中分低于干扰分）与「未装配」
            // 同样会导致运行期恒不触发。旧版只看 sceneGated（锚点字节非空），于是
            // 装配出 LOW_DISTINCTNESS 锚点时仍显示「已按场景触发保存」并提示运行，
            // 实际 48 帧 0 命中。这里把两类不可用统一为强告警。
            val why = verdict?.action ?: "请重新点击「拾取坐标」"
            val cause = if (sceneGated) "锚点判别度不足" else "稳定锚点未装配"
            panel?.setStatus("⚠ 场景已保存，但$cause，运行不会触发：$why")
            RunLog.w("FloatEditor", "场景已保存但$cause，运行时该规则 fail-closed 不触发：$why")
        } else if (sceneAttached && !sceneGuideShown()) {
            markSceneGuideShown()
            panel?.setStatus(
                "已按「场景触发」保存：画面出现才执行对应场景，不按先后顺序。" +
                    "如需按序执行，请在脚本设置中关闭场景驱动",
            )
            RunLog.i("FloatEditor", "首次场景装配，已呈现范式说明与按序执行退路")
        } else {
            panel?.setStatus(msg)
        }
        toast(msg)
        RunLog.i("FloatEditor", "保存「${next.name}」$label（场景装配=$sceneAttached 画面门控=$sceneGated 区域命中=$roiHits）")
        refreshForeground()
        renderAssist()
        renderResult()
    }

    /**
     * v2.9.16：场景范式引导的一次性标记。复用 `nymphauto` SharedPreferences，
     * 不新增文件、不新增权限；仅控制首次装配时的状态行提示，不参与任何执行语义。
     */
    private fun sceneGuideShown(): Boolean =
        getSharedPreferences(RunnerService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCENE_GUIDE_SHOWN, false)

    private fun markSceneGuideShown() {
        getSharedPreferences(RunnerService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SCENE_GUIDE_SHOWN, true).apply()
    }

    /** v2.9.21：点场景列表行进详情，位置仅用于定位，不构成执行顺序。 */
    private fun selectAt(target: Int) {
        if (actions.isEmpty()) return
        index = SceneList.selectAt(target, actions.size)
        // 切换场景时作废在途判定与上一场景的辅助载荷，旧结果不覆盖新状态。
        gateGeneration.cancel()
        pendingColorSample = null
        pendingObservedArgb = null
        pendingAutoRoi = null
        pendingManualRoi = null
        lastGateVerdict = null
        bindCurrent()
    }

    private fun addNew() {
        val fresh = Action()
        actions.add(fresh)
        index = FloatingEditorLayout.afterAppend(actions.size - 1)
        // 新增场景尚未落盘，先只更新内存与界面；点「保存」才写回
        panel?.bind(
            FloatingEditorLayout.formOf(fresh),
            SceneList.TITLE_CURRENT,
            true,
        )
        panel?.setEdgeWarn(null)
        panel?.setTuningBaseline(null)
        panel?.setStatus("新场景尚未保存，编辑完点「${SceneList.LABEL_SAVE}」写入脚本")
        refreshSceneList()
    }

    // ------------------------------------------------------------ 窗口

    private fun ensurePanel() {
        if (panel != null) return
        if (!Settings.canDrawOverlays(this)) {
            RunLog.e("FloatEditor", "悬浮窗权限缺失")
            toast("缺少悬浮窗权限，无法在其他 APP 上显示编辑器")
            stopSelf()
            return
        }
        val p = FloatingEditorPanel(this).apply {
            onSave = { form -> this@FloatingEditorService.save(form) }
            onPickPoint = { target -> pickPoint(target) }
            // v2.9.29：复用上一处已确认落点，不进入拾取模式、不弹键盘。
            onUseCurrentPoint = { lastAnyPickPct }
            onPickRegion = { pickRegion() }
            onApplyRoi = { applyRoi() }
            onSelectScene = { target -> selectAt(target) }
            onClearManualRoi = { clearManualRoi() }
            onResetAutoRoi = { resetAutoRoi() }
            // v2.9.22：结果卡的重新拾取 / 主修复 / 诊断摘要复制。
            onRepick = { repick() }
            onRunPrimaryFix = { runPrimaryFix() }
            onCopyDiag = { copyDiagnostic() }
            onAdd = { addNew() }
            onClose = { shutdown() }
            onCollapse = { setCollapsed(true) }
            onToggle = { togglePanel() }
            onDragAbsolute = { sx, sy, drx, dry, rx, ry ->
                onDragAbsolute(sx, sy, drx, dry, rx, ry)
            }
            onRequestInputFocus = { setInputFocusable(true) }
            onReleaseInputFocus = { setInputFocusable(false) }
        }
        val lp = baseParams()
        runCatching { wm.addView(p, lp) }.onFailure {
            RunLog.e("FloatEditor", "添加悬浮窗失败：${it.javaClass.simpleName}: ${it.message}")
            toast("悬浮窗创建失败：${it.javaClass.simpleName}")
            stopSelf()
            return
        }
        panel = p
        params = lp
        winX = lp.x; winY = lp.y
        // v2.8.1：把初始窗口位置同步给面板，否则首次拖动起点是 (0,0) 会跳到左上角
        p.setWindowPos(winX, winY)
        p.updateMetrics(screenH, density)
        p.applyState(state)
        bindCurrent()
        RunLog.i("FloatEditor", "系统悬浮编辑器已显示（可跨 APP）")
    }

    private fun baseParams(): WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = overlayType()
        format = PixelFormat.TRANSLUCENT
        // v2.8.1：折叠 / 展开之间绝不来回切 flags（那会触发软键盘弹出→
        // relayout→失焦→收起的循环，即录屏里的「一直抽搐」）。
        // v2.9.0：flags 取值统一来自 core 的 OverlayWindowSpec 单一真源，
        // 不再在此处手写位掩码——任何新增 flag 都必须先过 isAllowed 单测。
        // 默认 = NOT_FOCUSABLE|NOT_TOUCH_MODAL；只有用户主动点输入框时才由
        // setInputFocusable(true) 临时切到 NOT_TOUCH_MODAL，失焦立即恢复。
        flags = OverlayWindowSpec.flagsFor(inputFocused = false)
        // 不设 softInputMode：SOFT_INPUT_ADJUST_* 在 API 36 已弃用，且 overlay 上
        // 各 ROM 行为不稳。面板内部 ScrollView 负责滚动。
        gravity = Gravity.TOP or Gravity.START
        width = FloatingEditorLayout.dp(FloatingEditorLayout.EXPANDED_W_DP, density)
        height = WindowManager.LayoutParams.WRAP_CONTENT
        // 默认靠右侧、屏高 1/5 处，避开左下导航区（尺寸用缓存，不实时读取）
        x = (screenW - width - dp(12)).coerceAtLeast(0)
        y = (screenH / 5)
    }

    private fun setCollapsed(collapsed: Boolean) {
        ensurePanel()
        val p = panel ?: return
        val next = if (collapsed) FloatingEditorLayout.State.COLLAPSED
        else FloatingEditorLayout.State.EXPANDED
        state = next
        p.applyState(next)
        val lp = params ?: return
        val size = FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, density)
        lp.width = if (collapsed) size
        else FloatingEditorLayout.dp(FloatingEditorLayout.EXPANDED_W_DP, density)
        lp.height = if (collapsed) size else WindowManager.LayoutParams.WRAP_CONTENT
        // v2.8.1：flags 保持 baseParams 里的恒定组合，这里绝不改（见其注释）。
        // 用面板实测高度而非「假设 62% 屏高」做 clamp，且尺寸全部来自缓存。
        val h = if (collapsed) size else p.measuredExpandedHeight()
        val (cx, cy) = FloatingEditorLayout.clampPosition(winX, winY, lp.width, h, screenW, screenH)
        winX = cx; winY = cy
        lp.x = cx; lp.y = cy
        runCatching { wm.updateViewLayout(p, lp) }
        p.setWindowPos(cx, cy)
        refreshForeground()
    }

    private fun togglePanel() = setCollapsed(state == FloatingEditorLayout.State.EXPANDED)

    /**
     * v2.9.8 **同步直提交**拖动（修复折叠态悬浮球位移能力未生效）。
     *
     * ## 真因
     *
     * v2.8.1～v2.9.7 把窗口位移**唯一地**交给 `Choreographer.postFrameCallback`
     * 异步提交，并用 `frameScheduled` 布尔量做自锁，而该标志只在 VSYNC 回调
     * 开头复位。前台 Service 的 overlay 窗口静止 / 系统省电策略下，VSYNC 回调
     * 可能整段不触发，或回调内被 `shouldCommit` 丢弃后没有后续 MOVE 续投 ——
     * 于是 `frameScheduled` 永久为 true，之后每个 MOVE 都在入口 `return`，
     * 窗口再也不动。真机表现就是「悬浮球位移能力未生效」；主悬浮球
     * `RunnerService.attachBallTouch` 一直同步 `updateViewLayout`，从未被投诉，
     * 正好反证这条异步链路是坏的。
     *
     * ## 现在
     *
     * `FloatingEditorLayout.commitDrag` 是**无状态纯函数**（算位置 + 1px 节流），
     * 返回值立即同步提交；位置真源只有 [params].x/y，不存在任何可卡住的锁。
     * 绝对坐标（不累加、不漂移）与 1px 节流都保留 —— v2.8.1 抗抽搐的真因是
     * 「局部坐标增量累加」，不是 update 频率，所以同步提交不会让抽搐回归。
     */
    private fun onDragAbsolute(
        startX: Int, startY: Int,
        downRawX: Float, downRawY: Float,
        rawX: Float, rawY: Float,
    ) {
        val lp = params ?: return
        val p = panel ?: return
        // v2.9.17 / D3：提交前记录窗口坐标，供位移自诊断旁路取证；不改变提交逻辑。
        val beforeX = lp.x
        val beforeY = lp.y
        val h = if (state == FloatingEditorLayout.State.COLLAPSED)
            FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, density)
        else p.measuredExpandedHeight()
        // v2.9.12：与 RunnerService.attachBallTouch 同款——**直接在热路径
        // updateViewLayout**，彻底去掉 commitDrag 的 `?: return` 与
        // isPositionOnlyUpdate 这两道中间闸（它们不改变真实拖动结果，但为排除一切
        // 「中间层吞位移」嫌疑而移除，对齐运行球最简路径）。commitDrag 仍被引用，
        // 保留 1px 节流单测符号；但其返回值不再决定是否移动窗口。
        FloatingEditorLayout.commitDrag(
            startX, startY, downRawX, downRawY, rawX, rawY,
            lp.width, h, screenW, screenH, lp.x, lp.y,
        )
        val (nx, ny) = FloatingEditorLayout.dragAbsolute(
            startX, startY, downRawX, downRawY, rawX, rawY, lp.width, h, screenW, screenH,
        )
        lp.x = nx; lp.y = ny
        winX = nx; winY = ny
        runCatching { wm.updateViewLayout(p, lp) }
        p.setWindowPos(nx, ny)
        // v2.9.17 / D3：旁路记录本次提交的前后坐标，聚合进四态诊断结论。
        p.onDragCommit(beforeX, beforeY, lp.x, lp.y)
    }

    /**
     * 用户主动点输入框时临时允许聚焦；失焦立即恢复，杜绝软键盘弹出/收起循环。
     * v2.9.0：唯一合法的两种 flags 状态由 [OverlayWindowSpec] 定义并校验，
     * 这里不再手写位掩码；若规格判定非法则拒绝提交（防御性，宁可不切也不抽搐）。
     */
    private fun setInputFocusable(focusable: Boolean) {
        val p = panel ?: return
        val lp = params ?: return
        // v2.9.21：位级切换，只增删 FLAG_NOT_FOCUSABLE 一位，保留系统在 addView
        // 之后追加的全部标志位。旧实现用 flagsFor 整体覆盖并先过严格相等闸，
        // 一旦 lp.flags 含系统追加位就被判非法直接返回，窗口恒不可聚焦、输入法
        // 无法唤起。位级切换不受该闸影响，且仍是「非模态透传」语义不变。
        val want = OverlayWindowSpec.focusableFlags(lp.flags, focusable)
        if (lp.flags == want) return
        lp.flags = want
        runCatching { wm.updateViewLayout(p, lp) }
    }

    // ------------------------------------------------------------ 拾取 / ROI

    private fun pickPoint(target: Int) {
        val form = panel?.collect() ?: return
        val initX = FloatingEditorLayout.num(if (target == 1) form.x2 else form.x, 50.0)
        val initY = FloatingEditorLayout.num(if (target == 1) form.y2 else form.y, 50.0)
        // 拾取期间把编辑面板收成小球，避免遮挡目标 APP / 被截进放大镜
        // v2.9.29：上一次拾取的边缘警告在这里清掉，避免切换场景后仍挂着旧结论。
        panel?.setEdgeWarn(null)
        setCollapsed(true)
        val ok = CoordinatePicker.show(
            this,
            initialPct = initX to initY,
            onPicked = { r: PickerResult ->
                panel?.setPoint(target, r.xPct, r.yPct)
                lastAnyPickPct = r.xPct to r.yPct
                // v2.9.29：落点原本在系统手势热区，已内移到安全边界。这里必须说清
                // 「你点的位置」与「将来点到的位置」不是同一个，并给出实际落点，
                // 否则用户会在运行时看到点击无效却找不到原因。
                panel?.setEdgeWarn(
                    if (!r.edgeAdjusted) null else {
                        val note = r.edgeNote.ifBlank { "屏幕边缘" }
                        "落点位于${note}，已内移到安全边界 " +
                            "${FloatingEditorLayout.fmt(r.xPct)}% , " +
                            "${FloatingEditorLayout.fmt(r.yPct)}%（原 " +
                            "${String.format(Locale.US, "%.2f", 100.0 * r.rawXPx / r.widthPx)}% , " +
                            "${String.format(Locale.US, "%.2f", 100.0 * r.rawYPx / r.heightPx)}%）"
                    },
                )
                adoptRefFromPicker(r.widthPx, r.heightPx)
                // v2.9.15：拾取坐标的同时自动框选以该点为中心的场景 ROI，并在 ROI
                // 内选稳定局部锚点、跑拾取即自测、自校准阈值 —— 这就是「什么场景
                // 出现了，该触发某个高精度位置的点击」里的「场景」。
                val roi = SceneTrigger.autoRoi(r.xPct, r.yPct, r.widthPx, r.heightPx)
                pendingSceneRoi = roi
                pendingSceneRule = null
                pendingAnchorB64 = ""
                pendingAnchorReady = false
                pendingVerdict = null
                pendingSelfTest = null
                // v2.9.21：颜色素材 / 有效区域 / 门控结论在后台异步派生，拾取瞬间
                // 立即呈现「判定中」；推进门控代次作废在途判定，旧结果不覆盖新状态。
                pendingColorSample = null
                pendingAutoRoi = null
                pendingManualRoi = null
                pendingObservedArgb = null
                lastPickPct = r.xPct to r.yPct
                val gen = ++pendingSceneGen
                val gateGen = gateGeneration.advance()
                fbCounters.inc(PickFeedback.Keys.PICK)
                lastGateVerdict = TapGate.evaluate(TapGate.Phase.PENDING, null, 0.0, 0.0, null, null)
                panel?.setStatus(TapGate.messageFor(TapGate.Reason.PENDING))
                renderResult()
                // 拾取浮层已在 deliverPoint 里 dismiss 后回调，此刻截图不含浮层。
                captureSceneAnchor(roi, r.xPct, r.yPct, gen, gateGen)
                setCollapsed(false)
            },
        )
        if (!ok) {
            panel?.setStatus("无悬浮窗权限，无法拾取（可手输坐标）")
            setCollapsed(false)
        }
    }

    /**
     * v2.9.15 / A1+A2+A5：拾取瞬间用无障碍全屏截图，在自动 ROI 内选**稳定局部锚点**
     * 当模板，生成动态区域掩码、跑「拾取即自测」、按干扰分自校准阈值，最后落一条
     * [SceneRule]（三处落盘语义一次定死）。
     *
     * 走既有 `AutoClickService.screenshotBitmap()`（无障碍 `takeScreenshot`），
     * **不新建 MediaProjection / VirtualDisplay / MediaCodec**，五条硬契约不破。
     *
     * 任何一步失败（截图不可用 / 服务未连接 / SDK<30 / secure 黑帧 / ROI 近乎纯色 /
     * 裁剪异常）都**显式降级**：不写 anchorB64，`SceneTrigger.attachSceneRule` 返回
     * hasGate=false，运行时该规则 fail-closed 不触发（A4），状态栏明确提示，绝不
     * 误报启用场景判定、不回退至无画面校验的顺序触发。
     *
     * [gen] 是拾取代次：异步回填前若用户已保存（pendingSceneRoi=null）或又拾取了一次，
     * 直接丢弃本次结果，避免把上一轮的场景载荷污染到下一次保存。
     */
    private fun captureSceneAnchor(
        roi: Region,
        pickXPct: Double,
        pickYPct: Double,
        gen: Int,
        gateGen: Int,
    ) {
        // 截图调用留在主线程，与 CoordinatePicker.scheduleShot 已验证的通道逐字对齐
        // （takeScreenshot 的 callback 由传入 Executor 同步执行，线程亲和更稳）；
        // 只有位图→core 像素、锚点选取、掩码、NCC 自测、颜色采样这种 CPU 活放到 IO，
        // 主线程只呈现状态，不参与计算（判定中 → 命中 / 原因）。
        sceneScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            // v2.9.24：服务未连接与「取到空帧」是两件事。旧实现直接用
            // `live()?.lastScreenshotError ?: 0`，服务掉线时错误码落在 0，
            // 于是一次「无障碍服务已断开」被记成「返回空帧 / 取色失败」。
            //
            // v2.9.25：服务重连是秒级过程（刚打开悬浮窗、系统刚重启无障碍服务时）。
            // 此前一进来就判「未连接」，把「正在重连」说成「请去系统设置重新启用」，
            // 用户被推去做完全不必要的操作。先做有界等待，等到了就正常采集。
            val svc = awaitServiceReady(SERVICE_WAIT_MS)
            var bmp = if (svc == null) null else runCatching { svc.screenshotBitmap() }.getOrNull()
            var shotError = if (svc == null) ERR_NO_SERVICE else svc.lastScreenshotError
            // v2.9.23：部分 ROM 首次 takeScreenshot 返回空，短间隔后即可成功。
            // 补一次采集，避免把一次瞬时失败直接判成「取色失败」。
            if (bmp == null) {
                delay(140L)
                val retrySvc = AutoClickService.live()
                bmp = if (retrySvc == null) null else runCatching { retrySvc.screenshotBitmap() }.getOrNull()
                shotError = if (retrySvc == null) ERR_NO_SERVICE else retrySvc.lastScreenshotError
            }
            // v2.9.31：装配期跨帧采样。间隔 ≥100ms 再抓一帧，用于测跨帧 NCC
            // 下界（阈值校准的第二个约束）与生成时域掩码。第二帧失败或尺寸
            // 变化都不影响主装配：crossFrameFloor=-1、掩码回退单帧启发式，
            // 属显式降级并在日志标注来源，不静默伪装成时域结果。
            val first = bmp
            var secondBmp: Bitmap? = null
            if (first != null) {
                delay(120L)
                val svc2 = AutoClickService.live()
                val s2 = if (svc2 == null) null else runCatching { svc2.screenshotBitmap() }.getOrNull()
                if (s2 != null) {
                    if (s2.width == first.width && s2.height == first.height) {
                        secondBmp = s2
                    } else {
                        runCatching { s2.recycle() }
                    }
                }
            }
            lastCaptureMs = SystemClock.elapsedRealtime() - startedAt
            lastCaptureError = shotError
            lastFrameText = bmp?.let { "${it.width}×${it.height}" } ?: "-"
            lastSampleCount = -1
            lastStageText = "采集"
            val build: BuildResult = if (first == null) {
                BuildResult.Fail(captureFailure(shotError), captureDetail(shotError))
            } else {
                val shot = first
                val shot2 = secondBmp
                withContext(Dispatchers.IO) {
                    try {
                        buildSceneRule(shot, roi, pickXPct, pickYPct, shot2)
                    } catch (t: Throwable) {
                        RunLog.w("FloatEditor", "场景锚点装配异常：${t.javaClass.simpleName}: ${t.message}")
                        BuildResult.Fail(TapGate.Failure.ASSEMBLY, "装配异常 ${t.javaClass.simpleName}")
                    } finally {
                        // 全屏 ARGB_8888 位图约 8MB，用完立刻回收，避免每次拾取泄漏。
                        runCatching { shot.recycle() }
                        shot2?.let { runCatching { it.recycle() } }
                    }
                }
            }
            // 代次/消费检查：保存后或新拾取后，本次异步结果作废
            if (gen != pendingSceneGen || pendingSceneRoi == null) {
                RunLog.i("FloatEditor", "场景拾取结果已过期（代次 $gen），丢弃")
                return@launch
            }
            // v2.9.21：门控代次检查，用户切换场景 / 取消 / 重新拾取后旧判定不得覆盖新状态。
            if (!gateGeneration.isCurrent(gateGen)) {
                fbCounters.inc(PickFeedback.Keys.STALE_DROP)
                RunLog.i("FloatEditor", "门控判定已过期（代次 $gateGen），丢弃")
                return@launch
            }
            if (build is BuildResult.Fail) {
                pendingSceneRule = null
                pendingAnchorB64 = ""
                pendingAnchorReady = false
                pendingVerdict = SceneVerdict.ofUnmeasured()
                pendingSelfTest = null
                pendingColorSample = null
                pendingObservedArgb = null
                pendingAutoRoi = roi
                lastSampleCount = 0
                lastStageText = build.detail
                val failed = TapGate.evaluate(
                    TapGate.Phase.FAILED, roi, pickXPct, pickYPct, null, null,
                    failure = build.failure,
                )
                lastGateVerdict = failed
                panel?.setStatus(failed.message)
                renderAssist()
                renderResult()
                RunLog.w(
                    "FloatEditor",
                    "拾取装配失败：阶段=${build.failure} 明细=${build.detail} " +
                        "截图错误码=$shotError 耗时=${lastCaptureMs}ms 帧=$lastFrameText",
                )
                return@launch
            }
            val outcome = (build as BuildResult.Ok).outcome
            lastSampleCount = outcome.colorSample?.sampleCount ?: 0
            lastStageText = "完成"
            pendingSceneRule = outcome.rule
            pendingAnchorB64 = outcome.anchorB64
            pendingAnchorReady = outcome.anchorB64.isNotBlank()
            pendingVerdict = outcome.verdict
            pendingSelfTest = outcome.selfTest
            // v2.9.21：回填自动颜色素材与收敛区域，做点击前门控判定（fail-closed）。
            pendingColorSample = outcome.colorSample
            pendingObservedArgb = outcome.observedArgb
            pendingAutoRoi = outcome.convergedRoi ?: roi
            val effective = SceneAssist.resolveRoi(pendingAutoRoi!!, pendingManualRoi)
            // v2.9.25：锚点未装配 → 运行期无画面模板 → 必然 fail-closed。
            // 此时颜色匹配不构成「命中」，否则界面会与运行时互相打脸。
            val anchorReady = outcome.anchorB64.isNotBlank()
            val gate = TapGate.evaluate(
                TapGate.Phase.SETTLED,
                effective.region,
                pickXPct,
                pickYPct,
                outcome.colorSample,
                outcome.observedArgb,
                outcome.colorSample?.tolerance ?: SceneAssist.MAX_TOLERANCE,
                failure = when {
                    !anchorReady -> TapGate.Failure.ASSEMBLY
                    outcome.observedArgb == null || outcome.colorSample?.valid == false ->
                        TapGate.Failure.SAMPLE_EMPTY
                    else -> null
                },
                anchorReady = anchorReady,
            )
            lastGateVerdict = gate
            if (gate.allowTap) fbCounters.inc(PickFeedback.Keys.GATE_PASS)
            else fbCounters.inc(PickFeedback.Keys.GATE_FAIL)
            // v2.9.32：拾取装配完成后刷新判据基线，把本次自校准出的相似度、
            // 颜色容差与冷却等生效值呈现为「留空沿用」的参照。
            panel?.setTuningBaseline(outcome.rule)
            renderAssist()
            renderResult()
            val st = outcome.selfTest
            val v = outcome.verdict
            // v2.9.16：状态栏只呈现判定结论与建议动作，原始分数进入日志与诊断明细。
            // v2.9.20：判定结论与门控挂载是两条独立依据。结论不可用时锚点小图仍可能
            // 已装配，运行时依旧挂载画面门控；此处显式呈现挂载状态，避免把「建议
            // 重新拾取」理解为「门控未启用」。运行时挂载依据保持零改动。
            // v2.9.21：状态行以「是否命中」为第一视觉层级。门控命中才呈现稳定锚点
            // 结论；未命中直接给出可归因原因，绝不显示任何「可触发」暗示。
            val gateText = gate.message
            // v2.9.25：三种状态各自说自己的事，不再出现「命中 · 锚点不可用」这种自相矛盾句。
            panel?.setStatus(
                when {
                    // v2.9.36：判别度不足（usable=false）时，即便颜色门控命中也不得
                    // 显示「可触发」。本例实测：命中分 0.51 < 干扰分 0.60、跨帧下界
                    // 0.51 < 阈值 0.55，锚点在数学上永远不可能触发；旧文案「可触发…
                    // （存在误判风险）」会让操作者保存后运行 48 帧 0 命中、白等 57s。
                    // 现在明确给出不可用结论与重拾动作，fail-closed 一致。
                    !v.usable && v.kind != SceneVerdict.Kind.PASS ->
                        "⚠ ${v.headline}${v.action?.let { "：$it" } ?: ""}（该锚点运行期不会触发）"
                    gate.hit && v.usable ->
                        "命中 · 锚点校验通过 · 相似度阈值已校准至 ${outcome.rule.similarity}"
                    gate.hit ->
                        "⚠ ${v.headline} · 画面门控已挂载，但该锚点运行期可能不触发"
                    else -> gateText
                },
            )
            RunLog.i(
                "FloatEditor",
                "稳定锚点装配：${outcome.rule.anchorTemplateW}×${outcome.rule.anchorTemplateH}px " +
                    "ROI=${roi.expr} 命中=${String.format(Locale.US, "%.2f", st.best)} 干扰=${String.format(Locale.US, "%.2f", st.runnerUp)} " +
                    "阈值=${outcome.rule.similarity} 偏差=${String.format(Locale.US, "%.1f", st.locDeviationPct)}% " +
                    "掩码=${outcome.rule.maskRects.size} 块(${outcome.maskSource}) " +
                    "跨帧下界=${String.format(Locale.US, "%.2f", outcome.selfTest.crossFrameFloor)} " +
                    "取色证据=${if (outcome.rule.colorArgb != 0) "已落盘" else "无"} " +
                    "收敛区域=${SceneAssist.roiSummary(outcome.rule.convergedRoi ?: roi)} " +
                    "结论=${v.kind} ${v.detail}",
            )
            RunLog.i(
                "FloatEditor",
                "点击前门控：结论=${gate.reason} 允许点击=${gate.allowTap} " +
                    "颜色样本=${outcome.colorSample?.let { SceneAssist.colorSummary(it) } ?: "未采集"} " +
                    "有效区域=${SceneAssist.roiSummary(effective.region)} 模式=${effective.mode}",
            )
        }
    }

    /**
     * v2.9.23：截图错误码到装配失败阶段的映射。
     *
     * 系统错误码语义（AccessibilityService）：
     * 1 内部错误 / 2 超时 / 3 安全窗口拒绝 / 4 显示不可用 / 5 无障碍未授权。
     * 0 表示无错误但返回空帧，按「返回空」处理。
     */
    /**
     * v2.9.25：有界等待无障碍服务就绪。
     *
     * 只等 [timeoutMs]，超时返回 null 由调用方按「未连接」处理；不重试采集、
     * 不放宽任何判定，只是把「正在连接」与「确实不可用」区分开。
     */
    private suspend fun awaitServiceReady(timeoutMs: Long): AutoClickService? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            AutoClickService.live()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(SERVICE_POLL_MS)
        }
    }

    private fun captureFailure(errorCode: Int): TapGate.Failure = when (errorCode) {
        3 -> TapGate.Failure.CAPTURE_SECURE
        2, AutoClickService.SHOT_ERROR_TIMEOUT -> TapGate.Failure.CAPTURE_TIMEOUT
        else -> TapGate.Failure.CAPTURE_NULL
    }

    private fun captureDetail(errorCode: Int): String = when (errorCode) {
        -1 -> "未执行"
        ERR_NO_SERVICE -> "无障碍服务未连接（若刚开启无障碍，请等数秒后重试）"
        AutoClickService.SHOT_ERROR_TIMEOUT -> "截图超时（回调未在 3s 内返回）"
        AutoClickService.SHOT_ERROR_UNSUPPORTED -> "系统不支持无障碍截图（需 Android 11+）"
        0 -> "返回空帧"
        1 -> "系统内部错误"
        2 -> "截图超时"
        3 -> "安全窗口拒绝截图"
        4 -> "显示不可用"
        5 -> "无障碍截图未授权"
        else -> "错误码 $errorCode"
    }

    /**
     * CPU 侧装配（IO 线程）：ROI 内选稳定锚点 → 掩码 → 编码小图 → 拾取即自测 →
     * 阈值自校准 → 相对偏移 → [SceneRule]。返回 [BuildResult.Fail] 时携带失败阶段。
     */
    /**
     * v2.9.44：拾取瞬间读锚点位置的无障碍节点语义（零截图，只读 `rootInActiveWindow`）。
     * 供运行期「截图通道被系统录屏占用 / NCC 未命中」时改走节点 fallback 判定。
     * 只接受有 text 或 viewId 的语义节点（className 太泛、不足以判别），拿不到即
     * 返回 null → 规则不启用 fallback → 与旧版逐字一致。全部 runCatching 兜底：
     * 节点树读取异常绝不阻断装配。
     */
    private fun captureNodeSignal(px: Int, py: Int): NodeFallback.NodeSignal? = runCatching {
        val hit = NodeFinder().findSmallestNodeAt(px, py) ?: return@runCatching null
        val text = hit.text.trim()
        val viewId = hit.viewId.trim()
        if (text.isEmpty() && viewId.isEmpty()) return@runCatching null
        NodeFallback.NodeSignal(
            text = text,
            viewId = viewId,
            className = hit.className.trim(),
            centerX = hit.centerX,
            centerY = hit.centerY,
        )
    }.onFailure {
        RunLog.w("FloatEditor", "节点语义捕获失败，跳过节点 fallback：${it.javaClass.simpleName}")
    }.getOrNull()

    private fun buildSceneRule(
        bmp: Bitmap,
        roi: Region,
        pickXPct: Double,
        pickYPct: Double,
        second: Bitmap? = null,
    ): BuildResult {
        val img = bmp.toCoreImage()
        // v2.9.32：拾取瞬间的屏幕方向随规则落盘，运行期据此做方向无关坐标/帧变换。
        // 取不到显示信息时退化为 0（老脚本语义），不阻断装配。
        val pickRot = dev.nymph.auto.core.runner.OrientationTransform.norm(
            AutoClickService.live()?.frame()?.rotation ?: 0,
        )
        // 第二帧只用于跨帧 NCC 下界与时域差分，不参与锚点编码、不改存证。
        val img2 = second?.toCoreImage()
        val crop = SceneTrigger.cropPx(roi, img.width, img.height)
        if (crop.size != 4) return BuildResult.Fail(TapGate.Failure.PIXEL_READ, "裁剪参数不可用")
        val roiPx = StableAnchor.PxRect(crop[0], crop[1], crop[2], crop[3])
        if (!roiPx.valid) return BuildResult.Fail(TapGate.Failure.ASSEMBLY, "区域像素矩形无效")
        val pickXPx = (pickXPct / 100.0 * img.width).toInt().coerceIn(0, img.width - 1)
        val pickYPx = (pickYPct / 100.0 * img.height).toInt().coerceIn(0, img.height - 1)
        // v2.9.44：拾取瞬间读锚点位置的无障碍节点语义，供运行期「截图通道被录屏占用」
        // 时改走零截图的节点 fallback。只读节点树、不截图；拿不到语义节点（纯图像 /
        // 游戏 / 桌面色块）就留空、不启用，行为与旧版逐字一致。
        val nodeSignal = captureNodeSignal(pickXPx, pickYPx)
        // v2.9.21：拾取点邻域颜色采样（辅助素材）+ 依据离散度收敛的有效点击区域。
        // 观测色取拾取点单像素，代表色取邻域均值，二者差与容差构成颜色门控判定。
        val pixelAt: (Int, Int) -> Int? = { x, y ->
            if (x in 0 until img.width && y in 0 until img.height) {
                img.argb.getOrNull(y * img.width + x)
            } else null
        }
        val colorSample = SceneAssist.sampleNeighborhood(
            pixelAt, pickXPx, pickYPx, img.width, img.height,
        )
        val observedArgb = pixelAt(pickXPx, pickYPx)
        val convergedRoi = SceneAssist.convergeRoi(
            pickXPct, pickYPct, colorSample, img.width, img.height,
        )
        val anchorPx = StableAnchor.anchorSizePx(img.width, img.height)
        val pick = StableAnchor.pickAnchor(img, roiPx, pickXPx, pickYPx, anchorPx)
        val emptySelfTest = SceneSelfTest.Result(-1.0, -1.0, -1.0, -1, -1)
        if (!pick.stable || !pick.rect.valid) {
            // 仍写一条只有 searchRoi 的规则供可观测，但无锚点门控 → 运行时 fail-closed（A4）
            return BuildResult.Ok(
                ScenePickOutcome(
                    SceneRule(
                        searchRoi = roi, templateSrcW = img.width, templateSrcH = img.height,
                        pickRotation = pickRot,
                        colorArgb = colorSample.argb,
                        colorTolerance = colorSample.tolerance,
                        convergedRoi = convergedRoi,
                        // v2.9.44：图像锚点失效但节点语义有效时，运行期仍可靠节点 fallback 触发。
                        nodeText = nodeSignal?.text.orEmpty(),
                        nodeViewId = nodeSignal?.viewId.orEmpty(),
                        nodeClass = nodeSignal?.className.orEmpty(),
                        nodeFallbackEnabled = nodeSignal != null,
                    ),
                    "",
                    emptySelfTest,
                    SceneVerdict.assess(emptySelfTest, SceneRule.SIMILARITY_BASELINE),
                    colorSample,
                    convergedRoi,
                    observedArgb,
                ),
            )
        }
        val anchor = cropCoreImage(img, pick.rect)
            ?: return BuildResult.Fail(TapGate.Failure.ASSEMBLY, "锚点裁剪失败")
        val anchorB64 = encodeAnchor(bmp, pick.rect)
        if (anchorB64.isEmpty()) return BuildResult.Fail(TapGate.Failure.ASSEMBLY, "锚点编码为空")

        // 拾取即自测（三项：ROI 内最高命中分 / ROI 外次高干扰分 / 命中位置偏差）。
        // 先用基线阈值跑一遍拿到命中位置，再按干扰分自校准阈值；三项都落盘，
        // 保存前后显示给用户，一眼暴露 secure 黑帧 / dismiss race / 裁歪。
        val roiPct = ImageFinder.RegionPct(roi.leftPct, roi.topPct, roi.rightPct, roi.bottomPct)
        val st = SceneSelfTest.run(
            img, anchor, roiPct, pickXPct, pickYPct, SceneRule.SIMILARITY_BASELINE,
        )
        // v2.9.31：跨帧 NCC 下界。只有两帧齐备才测得出来；单帧时 -1，
        // calibrateSimilarity 逐字退回旧的「干扰分 + 余量」逻辑。
        val floor = SceneSelfTest.crossFrameFloor(
            if (img2 != null) listOf(img, img2) else listOf(img), anchor, roiPct,
        )
        val similarity = SceneTrigger.calibrateSimilarity(st.runnerUp, crossFrameFloor = floor)
        val stWithFloor = st.copy(crossFrameFloor = floor)
        val anchorRectPct = StableAnchor.toRegionPct(pick.rect, img.width, img.height)
        // 时域掩码：有两帧就用帧间差分，只有一帧才回退单帧空间方差启发式。
        val maskSource = if (img2 != null) "时域差分" else "单帧启发式"
        val maskRects = (if (img2 != null) {
            StableAnchor.planMaskTemporal(img, img2, roiPx, pick.rect)
        } else {
            StableAnchor.planMask(img, roiPx, pick.rect)
        }).map { StableAnchor.toMaskPct(it, img.width, img.height) }
        // A2：点击落点 = 锚点匹配中心 + 相对偏移（拾取点 − 记录锚点中心）
        val (ox, oy) = SceneTrigger.computeClickOffset(pickXPct, pickYPct, anchorRectPct)
        // v2.9.16：带状态触发参数由意图层 ScenePreset.DEFAULT 单一真源展开，
        // 装配点与编辑界面不再各自维护一套常量。A1 模板结构 / A2 坐标语义 /
        // A5 自测观测量全部保留在 base 中，toRule 只覆盖意图负责的五个字段。
        val rule = ScenePreset.toRule(
            ScenePreset.DEFAULT,
            SceneRule(
                searchRoi = roi,
                anchorRectPct = anchorRectPct,
                maskRects = maskRects,
                anchorTemplateW = pick.rect.w,
                anchorTemplateH = pick.rect.h,
                templateSrcW = img.width,
                templateSrcH = img.height,
                pickRotation = pickRot,
                clickOffsetXPct = ox,
                clickOffsetYPct = oy,
                similarity = similarity,
                selfTestBest = st.best,
                selfTestRunnerUp = st.runnerUp,
                selfTestLocDeviationPct = st.locDeviationPct,
                // ---- v2.9.31 装配-运行同源证据（全部带默认值，老脚本零迁移）----
                colorArgb = colorSample.argb,
                colorTolerance = colorSample.tolerance,
                convergedRoi = convergedRoi,
                crossFrameFloor = floor,
                // ---- v2.9.44 无障碍节点 fallback（有语义节点才启用）----
                nodeText = nodeSignal?.text.orEmpty(),
                nodeViewId = nodeSignal?.viewId.orEmpty(),
                nodeClass = nodeSignal?.className.orEmpty(),
                nodeFallbackEnabled = nodeSignal != null,
            ),
        )
        // v2.9.16：结论由系统判定，仅驱动界面提示与建议动作；门控是否挂载仍由
        // 截图/锚点可用性决定（attachSceneRule 依据 anchorB64 判定），运行时行为零回归。
        val verdict = SceneVerdict.assess(stWithFloor, similarity, rule.locMaxDeviationPct)
        return BuildResult.Ok(
            ScenePickOutcome(
                rule, anchorB64, stWithFloor, verdict,
                colorSample, convergedRoi, observedArgb, maskSource,
            ),
        )
    }

    /** 从 core 全屏图裁出锚点小图（独立数组，供 NCC 自测，绝不改原图）。 */
    private fun cropCoreImage(img: ImageFinder.Image, r: StableAnchor.PxRect): ImageFinder.Image? {
        if (!r.valid || r.l < 0 || r.t < 0 || r.r > img.width || r.b > img.height) return null
        if (img.argb.size < img.width * img.height) return null
        val px = IntArray(r.w * r.h)
        for (y in 0 until r.h) {
            System.arraycopy(img.argb, (r.t + y) * img.width + r.l, px, y * r.w, r.w)
        }
        return ImageFinder.Image(r.w, r.h, px)
    }

    /**
     * 锚点小图**原分辨率** PNG 编码（不缩放：24–48px 缩放会破坏纹理，反而不利 NCC）。
     * v2.9.14 的「整块 ROI 压到 160px」已废弃 —— 那正是自激振荡的模板语义源头。
     */
    private fun encodeAnchor(bmp: Bitmap, r: StableAnchor.PxRect): String {
        if (!r.valid || r.l < 0 || r.t < 0 || r.r > bmp.width || r.b > bmp.height) return ""
        val cut = try {
            Bitmap.createBitmap(bmp, r.l, r.t, r.w, r.h)
        } catch (t: Throwable) {
            RunLog.w("FloatEditor", "锚点裁剪失败：${t.message}")
            return ""
        }
        return try {
            val bytes = ByteArrayOutputStream().use { bos ->
                if (!cut.compress(Bitmap.CompressFormat.PNG, 100, bos)) return ""
                bos.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            RunLog.w("FloatEditor", "锚点编码失败：${t.message}")
            ""
        } finally {
            if (cut !== bmp) runCatching { cut.recycle() }
        }
    }

    /** v2.9.21：手动框选作为有效区域的覆盖层，优先级高于颜色收敛的自动区域。 */
    private fun pickRegion() {
        setCollapsed(true)
        val ok = CoordinatePicker.show(
            this,
            mode = PickerMode.REGION,
            onRegionPicked = { r: PickerRegion ->
                val region = Region(r.leftPct, r.topPct, r.rightPct, r.bottomPct, r.expr)
                pendingManualRoi = region
                fbCounters.inc(PickFeedback.Keys.ROI_EDIT)
                panel?.setRegion(region)
                adoptRefFromPicker(r.widthPx, r.heightPx)
                // 手动覆盖后用现有颜色样本重新做一次门控判定（纯 JVM，主线程无阻塞）。
                reEvaluateGate()
                renderAssist()
                setCollapsed(false)
            },
        )
        if (!ok) {
            panel?.setStatus("无悬浮窗权限，无法框选")
            setCollapsed(false)
        }
    }

    /** 清除手动覆盖，回退到颜色收敛的自动区域。 */
    private fun clearManualRoi() {
        pendingManualRoi = null
        fbCounters.inc(PickFeedback.Keys.MANUAL_CLEAR)
        val auto = pendingAutoRoi
        if (auto != null) panel?.setRegion(auto)
        reEvaluateGate()
        renderAssist()
        panel?.setStatus("已清除手动覆盖，回退自动区域")
        RunLog.i("FloatEditor", "有效区域回退为自动收敛结果")
    }

    /** 重置为自动区域：清除手动覆盖；若无自动结果则提示重新拾取坐标。 */
    private fun resetAutoRoi() {
        pendingManualRoi = null
        fbCounters.inc(PickFeedback.Keys.AUTO_RESET)
        val auto = pendingAutoRoi
        if (auto == null) {
            panel?.setStatus("暂无自动区域，请先${SceneList.LABEL_PICK}")
            renderAssist()
            return
        }
        panel?.setRegion(auto)
        reEvaluateGate()
        renderAssist()
        panel?.setStatus("已重置为自动区域（手动覆盖已清除）")
    }

    /**
     * 用当前颜色样本 / 有效区域 / 拾取坐标复算一次点击前门控结论。
     * 纯 JVM 判定，主线程只承载极小的算术与一次状态行刷新；找色与重采样等
     * CPU 活已经在 [captureSceneAnchor] 的 IO 线程完成。
     */
    private fun reEvaluateGate() {
        val auto = pendingAutoRoi
        val pick = lastPickPct
        val sample = pendingColorSample
        if (auto == null || pick == null) {
            lastGateVerdict = TapGate.evaluate(TapGate.Phase.FAILED, null, 0.0, 0.0, null, null)
            return
        }
        val effective = SceneAssist.resolveRoi(auto, pendingManualRoi)
        // v2.9.24：观测值只用装配期实测色。此前把 sample.argb 当观测值，
        // 与代表色自己比距离恒为 0 —— 清除手动覆盖 / 改区域后必然「命中」，
        // 是一次自证式假校验。实测色缺失即按失败即停，不判命中。
        val observed = pendingObservedArgb
        val verdict = if (observed == null || sample == null || !sample.valid) {
            TapGate.evaluate(
                TapGate.Phase.FAILED,
                effective.region,
                pick.first,
                pick.second,
                sample,
                null,
                failure = TapGate.Failure.SAMPLE_EMPTY,
            )
        } else {
            TapGate.evaluate(
                TapGate.Phase.SETTLED,
                effective.region,
                pick.first,
                pick.second,
                sample,
                observed,
                sample.tolerance,
                anchorReady = pendingAnchorReady,
            )
        }
        lastGateVerdict = verdict
        if (verdict.allowTap) fbCounters.inc(PickFeedback.Keys.GATE_PASS)
        else fbCounters.inc(PickFeedback.Keys.GATE_FAIL)
        panel?.setStatus(verdict.message)
        renderResult()
    }

    /** 结果卡「重新拾取」：坐标重置走主流程，辅助素材随新拾取重新派生。 */
    private fun repick() {
        fbCounters.inc(PickFeedback.Keys.REPICK)
        pickPoint(0)
    }

    /**
     * 执行当前结论的主修复动作。动作由 [PickFeedback.guidanceFor] 单一真源决定，
     * 界面只呈现标签、不自行推断，避免「提示文案」与「实际修复」漂移。
     */
    private fun runPrimaryFix() {
        if (panel?.isPickBusy() == true) return
        val v = lastGateVerdict ?: return
        val manual = pendingManualRoi != null
        when (PickFeedback.guidanceFor(v.reason, manual).primary) {
            PickFeedback.Action.REPICK -> repick()
            PickFeedback.Action.EDIT_ROI -> pickRegion()
            PickFeedback.Action.CLEAR_MANUAL -> clearManualRoi()
            PickFeedback.Action.RESET_AUTO -> resetAutoRoi()
            PickFeedback.Action.RESAMPLE, PickFeedback.Action.RETRY -> resampleColor()
            PickFeedback.Action.NONE -> Unit
        }
    }

    /**
     * 重采样当前坐标颜色：复用最近一次拾取坐标与自动区域，重走一次
     * 「截图 → 邻域采样 → 收敛 → 判定」链路。不改变坐标、不改变手动覆盖。
     */
    private fun resampleColor() {
        val auto = pendingAutoRoi
        val pick = lastPickPct
        if (auto == null || pick == null) {
            panel?.setStatus("暂无可重采的拾取点，请先${SceneList.LABEL_PICK}")
            return
        }
        fbCounters.inc(PickFeedback.Keys.RESAMPLE)
        val gen = ++pendingSceneGen
        val gateGen = gateGeneration.advance()
        lastGateVerdict = TapGate.evaluate(TapGate.Phase.PENDING, null, 0.0, 0.0, null, null)
        panel?.setStatus(TapGate.messageFor(TapGate.Reason.PENDING))
        renderResult()
        captureSceneAnchor(pendingSceneRoi ?: auto, pick.first, pick.second, gen, gateGen)
    }

    /**
     * 复制诊断摘要到剪贴板。摘要只含复现所需的中性事实（版本、手势、坐标、
     * 区域、颜色、置信度、结论、焦点、进程内计数），不含脚本名与场景名。
     * 剪贴板写入不需要任何权限，也不产生网络行为。
     */
    @Suppress("DEPRECATION")
    private fun copyDiagnostic() {
        val v = lastGateVerdict
        val pick = lastPickPct
        val auto = pendingAutoRoi
        val sample = pendingColorSample
        val effective = if (auto != null) SceneAssist.resolveRoi(auto, pendingManualRoi) else null
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val versionName = info?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info?.longVersionCode?.toInt() ?: 0
        } else {
            info?.versionCode ?: 0
        }
        val flags = params?.flags ?: 0
        val text = PickFeedback.diagnose(
            PickFeedback.Diag(
                versionName = versionName,
                versionCode = versionCode,
                gesture = typeName(actions.getOrNull(index) ?: Action()),
                xPct = pick?.first ?: 0.0,
                yPct = pick?.second ?: 0.0,
                roiMode = effective?.let { PickFeedback.roiModeLabel(it.mode) } ?: "未生成",
                roiText = effective?.let { SceneAssist.roiSummary(it.region) } ?: "-",
                colorText = sample?.let { SceneAssist.colorSummary(it) } ?: "未采集",
                confidence = PickFeedback.confidenceLabel(PickFeedback.confidenceOf(sample)),
                reason = v?.let {
                    PickFeedback.guidanceFor(it.reason, pendingManualRoi != null).headline
                } ?: "尚未拾取",
                focusState = if (OverlayWindowSpec.isFocusable(flags)) "可聚焦" else "不可聚焦",
                counters = fbCounters.snapshot(),
                extras = buildList {
                    add(
                        "采集" to when {
                            lastCaptureError == 0 && lastFrameText != "-" ->
                                "帧 $lastFrameText · ${lastCaptureMs}ms"
                            // v2.9.24：只有 -1 是「未执行」；-2/-3 是本次采集的失败码，
                            // 旧判断 `error < 0` 会把自己合成的超时码误标成「未执行」。
                            lastCaptureError == -1 -> "未执行"
                            else -> "错误码 $lastCaptureError（${captureDetail(lastCaptureError)}）· ${lastCaptureMs}ms"
                        },
                    )
                    add("阶段" to lastStageText)
                    add("采样" to if (lastSampleCount >= 0) "$lastSampleCount 点" else "未执行")
                },
            ),
        )
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast("当前设备不支持剪贴板")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("NymphAuto 诊断摘要", text))
        fbCounters.inc(PickFeedback.Keys.DIAG_COPY)
        toast("诊断摘要已复制，可直接粘贴发送")
        RunLog.i("FloatEditor", "诊断摘要已复制，${text.length} 字符")
    }

    /**
     * v2.9.13：拾取时把脚本参考分辨率对齐为拾取瞬间的真实屏幕方向。
     *
     * 旧实现只保留百分比坐标、丢弃 PickerResult/PickerRegion 携带的屏幕宽高，
     * 于是脚本 refWidth/refHeight 停留在「创建脚本时」的方向（常为竖屏 800×1768）。
     * 用户在横屏下拾取坐标时，坐标本身是百分比（对当前屏解析、方向无关，运行时正确），
     * 但预检 Preflight 比对 ref 与当前方向，发现「竖屏 vs 横屏」即判定
     * 「屏幕方向与脚本相反」并拦截 —— 一个本可正常运行的横屏脚本被误判拦截。
     *
     * 这里让 ref 反映作者实际操作的方向：每次拾取都把它同步为当时的屏幕宽高并落盘，
     * 预检方向与图片缩放比都随之对齐，横屏拾取 → 横屏运行不再被误拦。
     */
    private fun adoptRefFromPicker(widthPx: Int, heightPx: Int) {
        val base = script ?: return
        if (widthPx <= 0 || heightPx <= 0) return
        if (base.refWidth == widthPx && base.refHeight == heightPx) return
        val next = base.copy(refWidth = widthPx, refHeight = heightPx)
        script = next
        runCatching { store().save(next) }
            .onFailure { RunLog.e("FloatEditor", "拾取同步参考分辨率失败：${it.message}") }
    }

    private fun applyRoi(): Int {
        // v2.9.21：应用的是「有效区域」= 手动覆盖 > 自动收敛；清除手动即回退自动。
        val auto = pendingAutoRoi ?: pendingSceneRoi
        val effective = if (auto != null) {
            SceneAssist.resolveRoi(auto, pendingManualRoi ?: panel?.currentRegion()).region
        } else {
            panel?.currentRegion()
        }
        if (effective == null || SceneAssist.isEmptyRoi(effective)) {
            panel?.setStatus("尚未生成有效区域，请先${SceneList.LABEL_PICK}")
            return 0
        }
        val base = script ?: return 0
        val (next, hits) = RoiApplier.apply(base, effective)
        if (hits <= 0) {
            panel?.setStatus("脚本内暂无可应用区域的目标（0 处）：请到源码视图添加")
            return 0
        }
        val err = runCatching { repo().save(next) }.exceptionOrNull()
        if (err != null) { panel?.setStatus("保存失败：${err.javaClass.simpleName}"); return 0 }
        script = next
        actions = next.actions.toMutableList()
        refreshSceneList()
        RunLog.i("FloatEditor", "有效区域已应用到脚本内 $hits 处")
        return hits
    }

    // ------------------------------------------------------------ 前台通知 / 生命周期

    private fun refreshForeground() {
        val open = PendingIntent.getActivity(
            this, 21,
            Intent(this, dev.nymph.auto.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, 22,
            Intent(this, FloatingEditorService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 23,
            Intent(this, FloatingEditorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = script?.name ?: "未载入脚本"
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NymphApp.CHANNEL_STATUS)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("悬浮场景编辑器")
            .setContentText("$name · 可浮在其他 APP 上，点通知呼出/收起")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null,
                if (state == FloatingEditorLayout.State.COLLAPSED) "展开" else "收起", toggle).build())
            .addAction(Notification.Action.Builder(null, "关闭", stop).build())
            .build()
        // v2.8.1：首次 startForeground，之后只 notify 更新通知。v2.8.0 折叠/展开、
        // 保存都重复 startForeground，与 updateViewLayout 叠加制造额外闪动。
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java).notify(3, n)
        } else {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(3, n)
            }
            foregroundStarted = true
        }
    }

    private fun shutdown() {
        CoordinatePicker.dismiss()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        params = null
        liveActive = false
        // v2.9.35：此前 shutdown 只 stopForeground 不复位 foregroundStarted，同一
        // 服务实例再收到 ACTION_SHOW 时只 notify(3) 不 startForeground —— 由
        // startForegroundService 拉起却 5s 内未 startForeground → 崩溃/僵尸通知。
        foregroundStarted = false
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        stopSelf()
        RunLog.i("FloatEditor", "悬浮编辑器已关闭")
    }

    override fun onDestroy() {
        sceneScope.cancel()          // v2.9.14：取消在途的拾取截图协程，避免持有已销毁面板
        CoordinatePicker.dismiss()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        params = null
        liveActive = false
        foregroundStarted = false
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_SHOW = "dev.nymph.auto.action.FLOAT_EDITOR_SHOW"
        const val ACTION_TOGGLE = "dev.nymph.auto.action.FLOAT_EDITOR_TOGGLE"
        const val ACTION_COLLAPSE = "dev.nymph.auto.action.FLOAT_EDITOR_COLLAPSE"
        const val ACTION_STOP = "dev.nymph.auto.action.FLOAT_EDITOR_STOP"
        /** v2.9.25：拾取前等待无障碍服务就绪的上限。 */
        const val SERVICE_WAIT_MS = 1200L

        /** 等待期间的轮询间隔。 */
        const val SERVICE_POLL_MS = 100L

        const val EXTRA_SCRIPT_NAME = "scriptName"
        const val EXTRA_ACTION_INDEX = "actionIndex"

        /**
         * v2.9.24：无障碍服务未连接时的采集失败码（区别于系统错误码与「未执行」）。
         * 旧实现此时报「错误码 0（返回空帧）」，把「服务掉线」说成「取帧为空」，
         * 归因方向直接偏到取色上。
         */
        private const val ERR_NO_SERVICE = -4
        /** v2.9.16：场景范式引导一次性标记键。 */
        private const val KEY_SCENE_GUIDE_SHOWN = "scene_guide_shown"

        /**
         * v2.9.16：拾取装配的默认带状态触发参数收敛到 [ScenePreset.DEFAULT]
         * 单一真源（连续 2 帧迟滞 / 冷却 400ms / 持续在场 1500ms 后重试）。
         * 下列只读别名保留供诊断与兼容读取，取值由意图层推导，不再作为装配真源，
         * 避免装配点与编辑界面各自维护一套字面量。
         */
        val DEFAULT_PRESENT_STREAK: Int
            get() = ScenePreset.streakOf(ScenePreset.DEFAULT.debounce)
        val DEFAULT_ABSENT_STREAK: Int
            get() = DEFAULT_PRESENT_STREAK
        val DEFAULT_COOLDOWN_MS: Long
            get() = ScenePreset.repetitionMs(ScenePreset.DEFAULT.repetition).first
        val DEFAULT_RETRY_TIMEOUT_MS: Long
            get() = ScenePreset.repetitionMs(ScenePreset.DEFAULT.repetition).second

        /** 服务是否活跃（供主界面显示状态）。 */
        @Volatile
        var liveActive = false
            private set

        /**
         * 呼出系统悬浮编辑器。切后台 / 切到任何第三方 APP 后窗口依然存在，
         * 由前台服务保活；通知栏可随时呼出/收起/关闭。
         */
        fun show(context: Context, scriptName: String, actionIndex: Int = 0) {
            val i = Intent(context, FloatingEditorService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_SCRIPT_NAME, scriptName)
                putExtra(EXTRA_ACTION_INDEX, actionIndex)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
            liveActive = true
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingEditorService::class.java).setAction(ACTION_STOP))
            liveActive = false
        }
    }
}

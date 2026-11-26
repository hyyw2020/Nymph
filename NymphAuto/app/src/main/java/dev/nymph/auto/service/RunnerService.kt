package dev.nymph.auto.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.nymph.auto.NymphApp
import dev.nymph.auto.R
import dev.nymph.auto.core.editor.OverlayBounds
import dev.nymph.auto.core.editor.SceneTrigger
import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.runner.CapabilityMatrix
import dev.nymph.auto.core.runner.CapabilityTier
import dev.nymph.auto.core.runner.EnvironmentSnapshot
import dev.nymph.auto.core.runner.Preflight
import dev.nymph.auto.core.runner.dependencies
import dev.nymph.auto.engine.AccessibilityScreenshotSource
import dev.nymph.auto.engine.AiDetector
import dev.nymph.auto.engine.AutoClickService
import dev.nymph.auto.engine.CapabilityProbe
import dev.nymph.auto.engine.GestureEngine
import dev.nymph.auto.engine.OcrEngineFactory
import dev.nymph.auto.engine.NodeFinder
import dev.nymph.auto.engine.ScreenSource
import dev.nymph.auto.engine.VisionPipeline
import dev.nymph.auto.core.store.ScriptGrant
import dev.nymph.auto.core.store.ScriptGrants
import dev.nymph.auto.core.store.ScriptStore
import dev.nymph.auto.log.LogStore
import dev.nymph.auto.runner.QuickJsAndroidBinding
import dev.nymph.auto.runner.RunnerZdjlApi
import dev.nymph.auto.runner.ScriptRunner
import dev.nymph.auto.ui.BallPanel
import dev.nymph.auto.ui.CoordinatePicker
import dev.nymph.auto.ui.DragDiagnostics
import dev.nymph.auto.ui.FloatBall
import dev.nymph.auto.ui.OverlayWindowMetrics
import dev.nymph.auto.visual.RecognitionSettings
import dev.nymph.auto.visual.SceneGuardSettings
import dev.nymph.auto.visual.VisualSettings
import dev.nymph.auto.visual.VisualizerLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlin.math.abs
import java.io.File

/**
 * 运行编排服务：状态机 + 悬浮球 + 通知栏控制。
 *
 * 录屏兼容性：全程无 MediaProjection；前台通知与悬浮窗均不与录屏会话交互。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerService : Service() {

    /**
     * 运行态。与「连接相位」（[dev.nymph.auto.core.runner.ConnectionState]）正交：
     * PAUSED 仍持有连接但零动作；HALTED 表示已停在某一步且可从未完成处继续；
     * 归还连接（[handover]）会离开本状态机、回到 IDLE 并释放采集与注入。
     */
    enum class State { IDLE, RUNNING, PAUSED, HALTED }

    companion object {
        const val ACTION_START = "dev.nymph.auto.action.START"
        const val ACTION_TOGGLE = "dev.nymph.auto.action.TOGGLE"
        const val ACTION_STOP = "dev.nymph.auto.action.STOP"

        /** 归还连接：停止一切采集与注入，回 IDLE。通知栏常驻入口。 */
        const val ACTION_HANDOVER = "dev.nymph.auto.action.HANDOVER"

        /** 从终止位置继续（HALTED → RUNNING）。 */
        const val ACTION_RESUME = "dev.nymph.auto.action.RESUME"
        /** ADR-075 裁决4：进入建档取景态——仅创建悬浮球并切相机模式，不加载脚本。 */
        const val ACTION_ENTER_CAMERA = "dev.nymph.auto.action.ENTER_CAMERA"
        const val EXTRA_SCRIPT_JSON = "script_json"
        const val EXTRA_SCRIPT_NAME = "script_name"
        const val PREFS = "nymphauto"

        /** 已成功跑完过的脚本名集合键（决定「首次运行」预告窗口长度）。 */
        private const val KEY_COMPLETED = "completed_scripts"

        /**
         * 进程内实时状态快照（供 Compose 主界面只读展示）。
         *
         * 为什么用静态字段而不是广播/Binder：服务与界面同进程，状态只有
         * 「当前是否在跑、跑的是哪个脚本」两个标量；广播在 Android 14+ 有
         * 隐式广播限制，Binder 又需要额外 AIDL。静态字段 + 1s 轮询是这里
         * 最轻、最不易失效的方案，且不会让服务被界面意外持有。
         */
        @Volatile
        var liveState: State = State.IDLE
            private set

        @Volatile
        var liveScriptName: String = ""
            private set

        /**
         * v2.9.33：离线 OCR 通道不可用告警的进程内一次性标志。避免每次运行都弹提示；
         * 只在脚本确实依赖文字锚点且两路 OCR 均不可用时提示一次。
         */
        @Volatile
        var ocrUnavailableWarned: Boolean = false
            private set

        fun markOcrUnavailableWarned() {
            ocrUnavailableWarned = true
        }

        /**
         * ADR-075 裁决 4 收尾：进程内服务实例引用（只内存、不持久化、不进 Script）。
         * 供同进程的 Compose 工作台在建档进入/退出时调用相机球置位 API。
         * 服务未启动时为 null——调用方必须判空、静默降级，绝不能因此启动/停止脚本。
         */
        @Volatile
        private var liveInstance: RunnerService? = null

        /** 取当前服务实例；服务未启动返回 null（fail-soft，调用方判空即可）。 */
        fun live(): RunnerService? = liveInstance

        /**
         * ADR-075 裁决4 修复：建档取景态的抓帧回调无法随 Intent 跨 Binder 传递
         * （lambda 是 Compose 侧闭包），故以进程内静态变量中转，仅内存、不进 Script。
         */
        @Volatile
        private var pendingCameraCapture: (() -> Unit)? = null

        /**
         * ADR-075 裁决4 修复：进入工作台建档时主动拉起服务并切入相机取景态。
         *
         * 修复此前「服务未启动 → live() == null → 静默降级 → 相机球从不出现」的断链：
         *  - 服务已在运行：直接置相机模式并绑定抓帧回调；
         *  - 服务未运行：以 [ACTION_ENTER_CAMERA] 启动前台服务（不加载任何脚本），
         *    由 [onStartCommand] 创建悬浮球后取用 [pendingCameraCapture] 完成置位。
         *
         * 全程不加载脚本、不触发 ACTION_START、不改变脚本运行状态。
         */
        fun enterCameraCapture(context: Context, onCapture: () -> Unit) {
            pendingCameraCapture = onCapture
            val svc = liveInstance
            if (svc != null) {
                svc.showCameraBall(onCapture)
                return
            }
            val intent = Intent(context, RunnerService::class.java).setAction(ACTION_ENTER_CAMERA)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /** 退出建档取景态：清除中转回调并移除独立相机球，避免回调滞留。 */
        fun exitCameraCapture() {
            pendingCameraCapture = null
            liveInstance?.hideCameraBall()
        }

        /** 看门狗轮询间隔 / 判定卡死阈值。长 WAIT 与条件轮询会刷新心跳，不会误判终止。 */
        private const val WATCHDOG_TICK_MS = 5_000L
        private const val WATCHDOG_STALL_MS = 120_000L

        internal fun publishLive(state: State, scriptName: String) {
            liveState = state
            liveScriptName = scriptName
        }

        /**
         * 按脚本名启动：脚本体经本机 ScriptStore 加载，不经 Intent 传输。
         *
         * 为什么不用 Intent extra 传整份脚本 JSON：含图片模板的脚本编码后
         * 常超 1MB，会触发 Android 12+ 的 Binder 事务上限
         * （TransactionTooLargeException）导致应用直接崩溃。
         */
        fun startByName(context: Context, name: String) {
            val intent = Intent(context, RunnerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCRIPT_NAME, name)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * v2.9.35：脚本执行专用单线程。JS 的 QuickJS runtime 非线程安全且同一实例必须
     * 单线程调用；此前脚本跑在 Dispatchers.Main，JS 里一个 while(true) 会占死主线程，
     * 使停止按钮（点击回调派发到主线程）永远无法执行 requestInterrupt。迁到专用线程后：
     *  - JS 死循环只占该线程，主线程空闲 → 停止可响应 → interrupt（原子标志，任意线程可调）
     *    能在下一个字节码边界中断 JS；
     *  - 单线程满足 QuickJS 的线程约定；
     *  - 所有 UI/面板回调经 [postUi] 切回主线程，避免跨线程操作 View。
     * 看门狗、状态机、停止链路仍在 [scope]（主线程），不受影响。
     */
    private val scriptThread = newSingleThreadContext("nymph-script")
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 把 UI/面板/通知操作切回主线程；已在主线程则直接执行（幂等）。 */
    private fun postUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private var script: Script? = null
    private var runner: ScriptRunner? = null
    private var runnerJob: Job? = null
    private var watchdogJob: Job? = null

    /**
     * v2.9.46：运行会话身份守卫。每次启动分配单调 id；停止/交接先失效旧 id，
     * 使旧协程的 finally / postUi / invokeOnCompletion 中的字段提交全部跳过，
     * 杜绝「旧收尾破坏新一轮」（关掉新浮层、取消新看门狗、清空新引用、改写新状态）。
     */
    private val runToken = dev.nymph.auto.core.runner.RunToken()

    /**
     * 本次运行的 QuickJS 原生运行时（停止时中断 + 释放；null=内存引擎）。
     * v2.9.45：跨线程访问（主线程停止链路 / 脚本线程收尾 / completion 兜底），
     * 标 @Volatile 保证引用可见；真正的 native 销毁集中在脚本协程收尾完成，
     * 主线程绝不直接 destroy 正在 eval 的 runtime。
     */
    @Volatile private var quickJs: QuickJsAndroidBinding? = null

    /** 脚本运行授权（三档权限，与 UI 询问同一存储）。 */
    private val grants by lazy { ScriptGrants(File(filesDir, "scripts")) }
    private var state: State = State.IDLE
        set(value) {
            field = value
            publishLive(value, script?.name.orEmpty())
        }

    /**
     * 最近的终止报告（null=无）。HALTED 时通知栏与面板共用同一份，避免文案分叉。
     * 由 ScriptRunner.onHalt 写入；「从这步继续」读取 resumeStep。
     */
    private var lastHalt: dev.nymph.auto.core.runner.HaltReport.Report? = null

    /**
     * v2.9.31：连续「疑似在场」未达阈值的可见告警（null = 无）。
     * 面板与通知共用同一份文案；运行开始 / 归还 / 停止时清空。
     */
    private var suspectNotice: String? = null

    /** 继续时的起始步（0-based，-1=从头）。只在 toggle IDLE 分支消费一次。 */
    private var pendingResumePc: Int = -1

    /**
     * 连接相位（v2.9.30）。IDLE→ACQUIRING→OWNED，释放走 RELEASE→IDLE。
     * 只有 OWNED 允许注入与采集；其余相位 RunnerService 不派发任何动作。
     */
    private var phase: dev.nymph.auto.core.runner.ConnectionState.Phase =
        dev.nymph.auto.core.runner.ConnectionState.Phase.IDLE

    /** 相位迁移的唯一入口：保证 UI 与通知同步刷新。 */
    private fun movePhase(event: dev.nymph.auto.core.runner.ConnectionState.Event) {
        val t = dev.nymph.auto.core.runner.ConnectionState.next(phase, event)
        if (t.ignored) {
            RunLog.d("Runner", "连接相位忽略事件 ${event.name}（当前 ${phase.name}）")
            return
        }
        phase = t.to
        RunLog.i("Runner", "连接相位 ${t.from.name} -> ${t.to.name}（${event.name}）")
        refreshForeground()
    }
    private lateinit var wm: WindowManager
    private var ball: FloatBall? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: BallPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    // v3.1.1：建档相机球独立于运行球，二者进程内并存、互不引用。
    private var cameraBall: dev.nymph.auto.ui.CameraBall? = null
    private var cameraParams: WindowManager.LayoutParams? = null
    /** 紧凑面板自动收起任务（C 方案：展开后无操作超时收起）。 */
    private val panelAutoHide = Runnable { hidePanel() }
    /** 动作可视化层（v2.4.0）；默认关闭时全程为 null，零开销。 */
    private var visualizer: VisualizerLayer? = null

    /**
     * v2.9.19：显示屏全屏绝对坐标边界缓存。窗口带 `FLAG_LAYOUT_IN_SCREEN |
     * FLAG_LAYOUT_NO_LIMITS`，其 x/y 与边界收敛必须使用同一坐标空间。
     * 仅在服务创建、悬浮球创建与配置变更时刷新，拖动热路径不重读。
     */
    private var screenW = 1
    private var screenH = 1

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshBounds()
        liveInstance = this
        RunLog.i("Runner", "服务已创建（SDK ${Build.VERSION.SDK_INT}）")
    }

    private fun refreshBounds() {
        val size = OverlayWindowMetrics.screenSize(wm)
        screenW = size.width
        screenH = size.height
    }

    /**
     * v2.9.19：横竖屏切换、折叠屏展开或外接显示切换后，按旧边界下的相对锚点
     * 重定位运行球与控制面板，再提交布局更新，避免窗口停留在旧坐标导致
     * 越界不可见。边界未变化时不做任何 relayout。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val previous = OverlayBounds.Size(screenW.coerceAtLeast(1), screenH.coerceAtLeast(1))
        refreshBounds()
        val current = OverlayBounds.Size(screenW, screenH)
        if (previous == current) return
        val bp = ballParams
        val b = ball
        if (bp != null && b != null) {
            val (nx, ny) = OverlayBounds.reposition(
                bp.x, bp.y, bp.width, bp.height, previous, current,
            )
            bp.x = nx
            bp.y = ny
            runCatching { wm.updateViewLayout(b, bp) }
            layoutPanel(nx, ny)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RunLog.d("Runner", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                // v2.9.46：运行中不接受新的启动请求——此前会直接覆盖 script 字段，
                // 让悬浮球指向新脚本而后台仍在跑旧脚本，状态与执行体错位。
                if (state != State.IDLE) {
                    RunLog.w("Runner", "忽略启动请求：当前状态 ${state.name}（先停止再启动其它脚本）")
                    toast("脚本正在运行，请先停止后再启动其它脚本")
                    return START_NOT_STICKY
                }
                val name = intent.getStringExtra(EXTRA_SCRIPT_NAME)
                val parsed = if (name != null) {
                    RunLog.i("Runner", "从脚本库加载「$name」")
                    ScriptStore(File(filesDir, "scripts")).load(name)
                } else {
                    // 回退通道：仅用于无图片模板的小脚本直传
                    intent.getStringExtra(EXTRA_SCRIPT_JSON)?.let { ScriptCodec.decode(it) }
                }
                if (parsed == null) {
                    RunLog.e("Runner", "脚本加载失败：name=$name（文件缺失或已损坏）")
                    toast("脚本加载失败")
                    stopAll()
                    return START_NOT_STICKY
                }
                script = parsed
                RunLog.i("Runner", "已加载脚本「${parsed.name}」${parsed.totalActionCount()} 步")
                showBall()
                toast("已就绪：${parsed.name}，点悬浮球启停，长按展开面板")
            }
            ACTION_TOGGLE -> toggle()
            // v3.1.1：建档取景专用入口——显示独立的相机球并绑定抓帧回调，
            // 绝不加载脚本、不进入 toggle/start 链、不改变运行球状态。
            // 悬浮窗权限缺失时 showCameraBall 内部 toast + 静默降级，绝不抛异常。
            ACTION_ENTER_CAMERA -> {
                showCameraBall(pendingCameraCapture)
                RunLog.i("Runner", "建档相机球已就绪：点球抓帧")
                // 不提前 return：必须 fall-through 到末尾 refreshForeground() 完成
                // startForeground，否则 startForegroundService 启动后 5s 内未挂前台会崩溃。
            }
            // v2.9.30：通知栏三键之一。归还是「立刻不占读屏」，因此不经过
            // toggle 的暂停语义（暂停仍持有通道，只是不派发动作）。
            ACTION_HANDOVER -> {
                handover()
                return START_NOT_STICKY
            }
            // 「从这步继续」：HALTED 状态下重建执行器并从记录步开始。
            ACTION_RESUME -> {
                if (state == State.HALTED) {
                    lastHalt?.let { report ->
                        if (report.resumeStep > 0) pendingResumePc = report.resumeStep - 1
                    }
                    lastHalt = null
                    state = State.IDLE
                    toggle()
                } else {
                    RunLog.d("Runner", "忽略继续请求：当前 ${state.name}")
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
        }
        refreshForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (liveInstance === this) liveInstance = null
        visualizer?.close()
        visualizer = null
        // v2.9.35：系统直接回收服务（不走 stopAll）时，浮窗与前台通知此前不清理，
        // 造成窗口泄漏/通知残留。这里兜底移除全部窗口并撤前台。
        panel?.let { v -> runCatching { wm.removeView(v) } }
        panel = null
        panelParams = null
        ball?.let { v -> runCatching { wm.removeView(v) } }
        ball = null
        ballParams = null
        stopForegroundCompat()
        scope.cancel()
        // v2.9.35：释放脚本专用线程（否则其线程与 Looper 长期存活）。
        scriptThread.close()
        super.onDestroy()
    }

    private fun toggle() {
        val s = script ?: run { toast("没有可执行的脚本"); return }
        // 三档权限服务端校验（UI 询问之外的第二道闸：通知栏/悬浮球路径同样拦截）
        val grant = grants.effective(s.name)
        if (grant == ScriptGrant.DENY_ONCE || grant == ScriptGrant.DENY_ALWAYS) {
            RunLog.e("Runner", "脚本「${s.name}」已被拒绝运行（$grant）")
            toast("该脚本已被拒绝运行（可在主界面重新授权）")
            return
        }
        when (state) {
            State.IDLE -> {
                val svc = AutoClickService.live()
                if (svc == null) {
                    toast("无障碍服务未开启，请先到系统设置开启")
                    return
                }
                val f = svc.frame()
                if (f == null) {
                    RunLog.e("Runner", "无法获取屏幕尺寸，已取消执行（请检查无障碍服务是否正常连接）")
                    toast("无法获取屏幕尺寸，请重新开关无障碍服务")
                    return
                }
                // ---- 能力矩阵（裁定阶段 0）：先定档，档位决定能跑成什么样 ----
                val nodeFinder = NodeFinder()
                val screenSource: ScreenSource = AccessibilityScreenshotSource(svc)
                val cap = CapabilityProbe.snapshot(this, screenSource, nodeFinder)
                val tier = CapabilityMatrix.tierOf(cap)
                panel?.setTier(CapabilityMatrix.describe(tier))
                RunLog.i("Runner", "能力矩阵定档：${CapabilityMatrix.describe(tier)}")
                if (tier == CapabilityTier.UNAVAILABLE) {
                    // AAPM 开启或无无障碍：明确告知，不做任何绕过（裁定：架构不可用）
                    val why = if (cap.aapmEnabled) {
                        "高级保护模式（AAPM）已开启，自动化工具无豁免，请关闭后重试"
                    } else {
                        "无障碍服务不可用"
                    }
                    RunLog.e("Runner", "能力矩阵判不可用：$why")
                    toast(why)
                    return
                }
                // ---- v2.9.25 场景模板前置检查 ----
                // 场景驱动脚本里，没有画面模板的规则运行期一律 fail-closed。全部规则
                // 都没有模板时，runSceneDriven 会在第一帧立刻中止 —— 此前仍会起服务、
                // 建悬浮球、定档能力矩阵、加载 OCR，然后界面闪一下「已结束」，用户看到
                // 的是「点了运行，什么都没发生」。现在在消耗任何资源之前就把结论讲清楚。
                if (s.sceneDriven) {
                    val triggerable = SceneTrigger.triggerableCount(s.actions)
                    if (triggerable == 0) {
                        RunLog.e(
                            "Runner",
                            "已拦截：场景驱动脚本共 ${s.actions.size} 条规则，可用画面模板 0 条（fail-closed 必然不触发）",
                        )
                        toast("这个脚本没有可用的场景模板，运行不会产生任何动作。请在悬浮编辑器中重新拾取坐标后再试")
                        return
                    }
                }
                // ---- OCR（ONNX Runtime 离线优先 / ML Kit bundled 静态打包兜底）+ AI 检测 ----
                // 均为可选通道：不可用时 VisionPipeline 内部降级，不影响其他能力。
                // 提前到预检之前创建，供 EnvironmentSnapshot.aiSupported 判定。
                val ocr = OcrEngineFactory.create(this@RunnerService)
                // v2.9.33：离线 OCR 通道需用户自备可插拔模型，未就绪时静默回退会让
                // 文字锚点脚本失效而用户不知情。仅当脚本确实用到文字锚点、且两路 OCR
                // 均不可用时，做一次可见告警（toast + 运行日志），说明降级影响与补齐
                // 方式；进程内只弹一次，不打扰无关脚本。ML Kit bundled 通道随包可用，
                // 故"两路皆不可用"只出现在极端构造失败场景，文案不臆测 GMS 状态。
                if (!ocr.available && s.dependsOnText() && !ocrUnavailableWarned) {
                    val msg = "本脚本含文字识别步骤，但 OCR 通道未就绪（可插拔模型未检测到，bundled 引擎亦不可用）：" +
                        "文字锚点可能匹配失败。可在应用文件目录的 ocr/ 下放置 det.onnx / rec.onnx / keys.txt 启用离线通道。"
                    RunLog.w("Runner", msg)
                    toast(msg)
                    markOcrUnavailableWarned()
                }
                val aiDetector = AiDetector(this)
                // ---- 预检（裁定阶段 3.1）：跑之前就知道缺什么，不匹配即拦截 ----
                val report = Preflight.check(
                    s,
                    EnvironmentSnapshot(
                        a11yOnline = cap.a11yOnline,
                        screenshotSupported = cap.screenshotSupported,
                        screenWidth = f.width,
                        screenHeight = f.height,
                        screenRotation = f.rotation,
                        installedPackages = installedPackages(s),
                        packageVisibilityRestricted = Build.VERSION.SDK_INT >= 30,
                        aapmEnabled = cap.aapmEnabled,
                        aiSupported = aiDetector.available,
                    ),
                )
                if (report.warnings.isNotEmpty()) {
                    RunLog.w("Runner", "预检提示（不拦截）：${report.warningsText()}")
                }
                if (!report.ok) {
                    RunLog.e("Runner", "预检拦截：${report.explain()}")
                    toast(report.explain())
                    ocr.close(); aiDetector.close()
                    return
                }
                RunLog.i("Runner", "开始执行「${s.name}」（屏幕 ${f.width}x${f.height}，档位 $tier）")
                // v2.9.30：新一轮运行清空上一次的终止报告，否则「继续」会读到旧步号。
                lastHalt = null
                panel?.setHalt(null)
                movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Acquire)
                // 坐标拾取是全屏可触摸模态层，若残留会混进 takeScreenshot 污染找图。
                // 回放启动即强制关闭（幂等），保证截图帧里没有编辑器浮层。
                CoordinatePicker.dismiss()
                val gesture = GestureEngine()
                // 注入屏幕边界：手势坐标（含 jitter / 拟人抖动 / 贝塞尔控制点）
                // 统一钳制在屏内，杜绝负 Path bounds 打断脚本（v2.2.0 真机修复）。
                gesture.setScreenBounds(f.width, f.height)
                // v2.9.29：注入系统手势热区。落点落在返回 / Home 热区时，
                // 系统会静默消费触摸而 dispatchGesture 仍返回成功——不收敛就会
                // 出现「日志显示已点击、界面没反应」。滑动不做内移，见 GestureEngine。
                gesture.setEdgeInsets(
                    runCatching { AutoClickService.live()?.gestureInsets() }.getOrNull()
                        ?: dev.nymph.auto.core.vision.EdgeSafety.fallback(
                            resources.displayMetrics.density,
                        ),
                )
                // ---- 动作可视化（v2.4.0）----
                // 只依赖 Context + WindowManager 的独立组件，零录屏权限。
                // 默认关闭时 start() 内部不 addView，configProvider 每动作读一次，
                // 运行中在设置页改开关会在下一个动作生效。
                val vis = VisualizerLayer(this@RunnerService) { VisualSettings.load(this@RunnerService) }
                vis.start()
                visualizer = vis
                val vision = VisionPipeline(
                    screenSource, nodeFinder, ocr, aiDetector,
                    // 全屏截图会带上自家悬浮球/控制面板/等待浮层，匹配前抑制；
                    // 再加上「当前活跃可视化元素」的合并包围盒：录屏看得见特效、
                    // 匹配副本里当它不存在（v2.3 ImageMask 设计哲学）。
                    selfOverlayRects = {
                        AutoClickService.live()?.selfOverlayRectsPct().orEmpty() +
                            listOfNotNull(visualizer?.activeBoundsPct(f.width, f.height))
                    },
                )
                // ---- 表达式引擎：QuickJS（vendored quickjs-ng，现场编译）优先；
                // so 缺失/创建失败时降级内存引擎（JS 条件与 RUN_JS 受限，其余功能不受影响）----
                val zdjlApi = RunnerZdjlApi(
                    vision = vision,
                    nodeFinder = nodeFinder,
                    onToast = { msg -> toast(msg) },
                    deviceInfoProvider = { deviceInfo() },
                )
                val quickJs: dev.nymph.auto.runner.QuickJsAndroidBinding? =
                    if (dev.nymph.auto.runner.QuickJsNative.available) {
                        runCatching {
                            dev.nymph.auto.runner.QuickJsAndroidBinding(zdjlApi)
                        }.onFailure {
                            RunLog.e("Runner", "QuickJS 运行时创建失败，降级内存引擎：${it.message}")
                        }.getOrNull()
                    } else null
                if (quickJs == null) {
                    RunLog.w("Runner", "QuickJS 不可用，已降级内存表达式引擎（JS 条件 / 运行 JS 代码动作受限）")
                }
                val js: dev.nymph.auto.core.expr.ExprEngine =
                    quickJs?.let { dev.nymph.auto.runner.QuickJsEngine(it) }
                        ?: dev.nymph.auto.runner.InMemoryEngine()
                this.quickJs = quickJs
                val r = ScriptRunner(gesture, vision, nodeFinder, js) { msg -> RunLog.i("Script", msg) }
                // 可视化接线：配置由 SharedPreferences 实时读，cue 出口指向叠加层。
                // 两项都可空/可关，关闭时 ScriptRunner 的 visualize() 首行即返回。
                r.visualConfigProvider = { VisualSettings.load(this@RunnerService) }
                r.onVisualCue = { cue -> postUi { visualizer?.onCue(cue) } }
                // 场景规则诊断覆盖层（每规则 ROI 框 + 命中状态 + 落点 + 序号），
                // 复用同一 overlay 窗口，零新增权限。
                // v2.9.16：回调恒定挂载；是否绘制由 VisualizerLayer 依据独立开关
                // sceneDebugEnabled 判定，不再依赖「动作可视化」总开关。
                r.onSceneDebug = { boxes -> postUi { visualizer?.onSceneDebug(boxes) } }
                // v2.5.0：回放前界面校验，避免动作打在错误界面上还报成功。
                // 老脚本无起始包名会自动跳过；关闭开关可退回旧行为。
                r.sceneGuardEnabled = SceneGuardSettings.load(this@RunnerService)
                zdjlApi.bind(r) // JS API 接到本次运行的变量表与条件树
                // v2.7.0：识别调度调参（帧同步间隔 / 运动预测 / 窄 ROI），
                // 设置页可一键退回 v2.6.0 的 300ms 旧行为。
                val tuning = RecognitionSettings.load(this@RunnerService)
                r.recognitionTuning = tuning
                // 失败恢复：点击类未命中轮询重试窗口（而非秒跳）；失败现场截图存 filesDir/debug
                r.retryWindowMs = tuning.retryWindowMs
                r.debugDumpDir = File(filesDir, "debug")
                r.capabilityTier = tier
                // v2.9.31：新一轮运行清空上一轮的模板失效告警。
                suspectNotice = null
                panel?.setSuspect("")
                // 等待浮层（裁定阶段 3.5）：主界面只显示"正在等待 X Ns / Ms"
                r.onWaitStatus = { st -> postUi { panel?.setWait(st) } }
                // v2.9.31：「疑似在场」升级为面板 + 通知可见状态，不静默轮询。
                r.onSuspectedPresent = { msg ->
                    postUi {
                        suspectNotice = msg
                        panel?.setSuspect(msg)
                        RunLog.w("Runner", "疑似在场持续未触发：$msg")
                        refreshForeground()
                    }
                }
                // 四类归因（裁定阶段 4）：按类给出对应动作，并定位到失败步
                r.onFail = { reason, msg, step ->
                    // v2.9.14：中性话术——场景驱动下 step 是命中的规则下标，
                    // 顺序链下是顶层动作号；统一「第 N 条」不再引入「步」概念。
                    val where = if (step > 0) "第 $step 条 · " else ""
                    RunLog.e("Script", "[${reason.label}] $where$msg")
                    toast("$where${reason.label}：${reason.action}")
                }
                r.onAbnormal = { msg ->
                    RunLog.e("Script", msg)
                    toast(msg)
                }
                // v2.9.1：界面风险为非阻断提示，用 WARN 级 + toast 送达，
                // 不写成 ERROR、不中止回放（同包名无法证明界面一致，阻断会误伤）。
                r.onSceneNotice = { msg ->
                    RunLog.w("Runner", "界面提示：$msg")
                    toast(msg)
                }
                // ---- v2.9.30 预告 / 终止 / 续跑 ----
                // 首次运行 = 该脚本从未成功跑完过（持久化事实，不是启发式猜测）。
                r.firstRun = !completedScripts().contains(s.name)
                // 从终止位置继续：pendingResumePc 为 0-based，-1 表示从头。
                r.resumeFromPc = pendingResumePc.coerceAtLeast(0).also { pendingResumePc = -1 }
                r.onNext = { text -> postUi { panel?.setNext(text) } }
                r.onAnnounce = { d, msg ->
                    postUi { panel?.showUndo(d.undoWindowMs) }
                    RunLog.i("Runner", "预告[${d.level}] $msg（${d.reason}）")
                    toast(msg)
                }
                r.onHalt = { rep -> postUi { onHalt(rep) } }
                // v2.9.46：分配本次运行的会话身份。下面所有延迟收尾（finally / postUi /
                // invokeOnCompletion）都捕获 myRunId，只有它仍是 current 时才提交字段与状态。
                val myRunId = runToken.next()
                runner = r
                state = State.RUNNING
                movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Acquired)
                startWatchdog()
                // v2.9.46：捕获本轮看门狗的局部引用，收尾时无条件取消自己那份——
                // 既不让旧看门狗泄漏空转，也不会误取消新一轮的看门狗。
                val myWatchdog = watchdogJob
                // v2.9.35：脚本跑在专用线程，JS 死循环不再冻结主线程，停止按钮可响应。
                runnerJob = scope.launch(scriptThread) {
                    val ok = try {
                        r.run(s)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ScriptRunner.StopScript) {
                        RunLog.i("Script", "脚本被停止动作终止")
                        false
                    } catch (e: Exception) {
                        RunLog.e("Script", "执行异常：${e.message ?: e.javaClass.simpleName}")
                        toast("执行异常：${e.message}")
                        false
                    } finally {
                        // v2.9.46：本轮资源一律释放（用局部引用，无条件，防泄漏）。
                        // 但对共享字段的改写只在「本轮仍是当前运行」时提交——否则会破坏
                        // 用户已开启的新一轮（关掉新浮层 / 清空新 runtime / 取消新看门狗）。
                        val isCurrentRun = runToken.isCurrent(myRunId)
                        myWatchdog?.cancel() // 无条件取消本轮看门狗；字段置空下面守卫
                        if (isCurrentRun && this@RunnerService.watchdogJob === myWatchdog) {
                            this@RunnerService.watchdogJob = null
                        }
                        // 可视化层：移除窗口、注销帧回调、清空活跃 cue（停帧）。用局部 vis，
                        // 绝不 close 字段——字段此时可能已指向新一轮的浮层。
                        vis.close()
                        if (isCurrentRun && this@RunnerService.visualizer === vis) {
                            this@RunnerService.visualizer = null
                        }
                        // 原生运行时随本次运行创建、随本次运行释放（局部引用，无条件）。
                        quickJs?.dispose()
                        if (isCurrentRun && this@RunnerService.quickJs === quickJs) {
                            this@RunnerService.quickJs = null
                        }
                        // OCR/AI 会话同理：识别器与检测器随本次运行关闭
                        ocr.close()
                        aiDetector.close()
                        // 视觉管线的缓存帧（最多一帧）随运行结束释放
                        vision.close()
                        // "允许一次"只覆盖本次运行：结束后清除，下次运行重新询问
                        if (grants.effective(s.name) == ScriptGrant.ALLOW_ONCE) {
                            grants.clearOnce(s.name)
                        }
                    }
                    // v2.9.46：runner/runnerJob 字段同样只在当前运行收尾时清空。
                    if (runToken.isCurrent(myRunId)) {
                        runner = null
                        runnerJob = null
                    }
                    // v2.9.35：脚本运行在专用线程，收尾涉及 View/状态/通知的段落统一
                    // 切回主线程，保持与迁移前（全程主线程）完全一致的执行语义。
                    postUi {
                        // v2.9.46：旧收尾的 UI/状态提交不得覆盖新一轮。
                        if (!runToken.isCurrent(myRunId)) return@postUi
                        panel?.setNext("")
                        panel?.setWait(null)
                        // v2.9.30：有可恢复的终止报告 → 停在 HALTED，「从这步继续」才有落点；
                        // 正常跑完或不可恢复（脚本自身错误/看门狗）则回 IDLE 并清空报告。
                        val halt = lastHalt
                        state = if (halt != null && halt.resumable) State.HALTED else State.IDLE
                        if (halt == null || !halt.resumable) {
                            panel?.setHalt(null)
                            lastHalt = null
                        }
                        // 释放连接相位：一次运行结束不再持有注入与采集。
                        movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Release)
                        movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Settled)
                        RunLog.i("Runner", if (ok) "脚本执行完毕" else "脚本执行结束（未跑完）")
                        if (ok) {
                            markCompleted(s.name)
                            toast("脚本执行完毕")
                        }
                        updateBall()
                        refreshForeground()
                    }
                // v2.9.45：原生 runtime 释放兜底。脚本线程的 finally 是正常释放路径；
                // 本回调覆盖「协程尚未启动即被取消、finally 未执行」的边界。两条路径
                // 都走 QuickJsAndroidBinding.dispose() 的原子一次性句柄，重复调用安全。
                // 捕获本次运行的局部 quickJs（而非字段），避免误伤下一轮新建的运行时。
                }.also { job ->
                    job.invokeOnCompletion {
                        // 局部 quickJs 无条件释放（幂等），字段置空仅在仍是当前运行时提交。
                        quickJs?.dispose()
                        if (runToken.isCurrent(myRunId) && this@RunnerService.quickJs === quickJs) {
                            this@RunnerService.quickJs = null
                        }
                    }
                }
            }
            State.RUNNING -> {
                runner?.paused?.value = true
                state = State.PAUSED
                RunLog.i("Runner", "已暂停")
            }
            State.PAUSED -> {
                runner?.paused?.value = false
                state = State.RUNNING
                RunLog.i("Runner", "已继续")
            }
            State.HALTED -> {
                // 面板主按钮在 HALTED 下是「从这步继续」，与通知栏 action 同语义。
                val report = lastHalt
                if (report != null && report.resumeStep > 0) {
                    pendingResumePc = report.resumeStep - 1
                }
                lastHalt = null
                state = State.IDLE
                panel?.setHalt(null)
                toggle()
                return
            }
        }
        vibrate(30L)
        updateBall()
        refreshForeground()
    }

    /**
     * zdjl.deviceInfo() 数据源：屏幕尺寸/密度/系统版本/旋转/机型。
     * 供 JS 脚本按设备能力自适应（如按 density 换算坐标）。
     */
    private fun deviceInfo(): Map<String, Any> {
        val f = AutoClickService.live()?.frame()
        return buildMap {
            put("width", f?.width ?: 0)
            put("height", f?.height ?: 0)
            put("density", resources.displayMetrics.density.toDouble())
            put("dpi", resources.displayMetrics.densityDpi)
            put("rotation", f?.rotation ?: 0)
            put("sdk", Build.VERSION.SDK_INT)
            put("model", Build.MODEL)
        }
    }

    /**
     * 目标应用可启动性探测（包可见性过滤后仍准确：OPEN_APP 只需要能启动）。
     * 不可启动的包名（未安装/不可见）会在预检里被拦下并说明。
     */
    /**
     * v2.9.27：判定「包是否存在」，不再用「能否取得 launcher Intent」冒充已安装。
     *
     * 旧实现把「没有启动入口」翻译成「没有安装」：本应用 <queries> 只声明
     * ACTION_MAIN，游戏助手 / 覆盖层类应用没有 launcher 入口或不在可见范围，
     * getLaunchIntentForPackage 返回 null，于是一个明明装着的包被判未安装，
     * 预检拦下整个录制脚本（v1.2.1 同类问题、v2.9.27 现场回归）。现在以
     * getPackageInfo 存在性为准，launch intent 仅作兜底。
     */
    private fun installedPackages(s: Script): Set<String> = runCatching {
        val pm = packageManager
        s.dependencies().packages.filter { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false) ||
                pm.getLaunchIntentForPackage(pkg) != null
        }.toSet()
    }.getOrDefault(emptySet())

    // ------------------------------------------------------------ 前台通知

    private fun refreshForeground() {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, dev.nymph.auto.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, 2,
            Intent(this, RunnerService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, RunnerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // v2.9.30：归还连接。切到任何界面都够得着——这正是「随时可收回」的落点，
        // 悬浮面板在目标应用全屏时可能被遮、也可能被系统限制，通知栏不会。
        val handover = PendingIntent.getService(
            this, 4,
            Intent(this, RunnerService::class.java).setAction(ACTION_HANDOVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 从终止位置继续（仅 HALTED 时有意义）。
        val resume = PendingIntent.getService(
            this, 5,
            Intent(this, RunnerService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = script
        val text = when {
            suspectNotice != null && state == State.RUNNING ->
                "模板可能失效 · 点通知回到应用重新拾取"
            state == State.RUNNING -> "执行中：${s?.name ?: ""}"
            state == State.PAUSED -> "已暂停：${s?.name ?: ""}"
            state == State.HALTED -> lastHalt?.shortText ?: "已停止"
            s != null -> "已就绪：${s.name} · 点悬浮球开始"
            else -> "NymphAuto"
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NymphApp.CHANNEL_STATUS)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        // 三键常驻：暂停/继续（或恢复）、归还连接、停止。
        // 顺序按「最常用 → 最收束」，用户在任何界面下拉通知即可收回控制。
        val primaryLabel = when (state) {
            State.RUNNING -> "暂停"
            State.PAUSED -> "继续"
            State.HALTED -> "从这步继续"
            State.IDLE -> "开始"
        }
        val primaryIntent = if (state == State.HALTED) resume else toggle
        builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NymphAuto")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, primaryLabel, primaryIntent).build())
            .addAction(Notification.Action.Builder(null, "归还连接", handover).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
        // v2.9.31：仅当「疑似在场」持续未触发时追加第四键。点击回到主界面，
        // 由用户重新拾取锚点 —— 复用既有 open Intent，零新增权限、零新 Activity。
        if (suspectNotice != null) {
            val repick = PendingIntent.getActivity(
                this, 6,
                Intent(this, dev.nymph.auto.ui.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, "重新拾取", repick).build())
        }
        val n = builder.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------ 悬浮球 + 控制面板

    private fun showBall() {
        if (ball != null) return
        refreshBounds()
        if (!Settings.canDrawOverlays(this)) {
            RunLog.e("Runner", "悬浮窗权限缺失，无法创建悬浮球")
            toast("缺少悬浮窗权限")
            return
        }
        RunLog.d("Runner", "创建悬浮球")
        val v = FloatBall(this).apply {
            onToggle = { toggle() }
            onShowPanel = { showPanel() }
        }
        val p = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            // v2.9.18：补齐 FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS，与坐标拾取器
            // 窗口配置对齐。缺这两项时，OEM ROM / 游戏空间下悬浮窗的视觉位置与触摸命中
            // 区域会错位，表现为悬浮窗可见但位移交互未生效；补上后运行球以全屏绝对坐标布局，
            // 触摸落点回到窗口本体。拖动逻辑（attachBallTouch 的 updateViewLayout）不变。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = dp(52)
            height = dp(52)
            gravity = Gravity.TOP or Gravity.START
            // v2.9.19：初始位置与边界收敛使用同一全屏绝对坐标真源。
            x = (screenW - dp(72)).coerceAtLeast(0)
            y = screenH / 3
        }
        attachBallTouch(v, p)
        runCatching { wm.addView(v, p) }.onFailure {
            RunLog.e("Runner", "悬浮球添加失败：${it.javaClass.simpleName}: ${it.message}")
            toast("悬浮窗创建失败")
            return
        }
        ball = v
        ballParams = p
        RunLog.i("Runner", "悬浮球已显示：点击启停，长按展开紧凑面板")

        // v3.1.1 B+C 方案：紧凑控制条（默认隐藏，长按球展开）。
        val panel = BallPanel(this).apply {
            onToggle = { toggle() }
            onStop = { stopAll() }
            onCollapse = { hidePanel() }
            // C 方案：任一按钮操作后立即收起，避免持续遮挡。
            onAction = { hidePanel() }
            visibility = View.GONE
        }
        val pp = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            // v2.9.18：与运行球同款补齐布局坐标空间标志（见上方说明）。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            // B 方案：4 等宽按钮单行，宽度按紧凑条重算；高度自适应（按钮行+单行状态）。
            width = dp(176)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { wm.addView(panel, pp) }.onFailure {
            runCatching { wm.removeView(v) }
            ball = null; ballParams = null
            toast("控制面板创建失败")
            return
        }
        this.panel = panel
        this.panelParams = pp
        layoutPanel(p.x, p.y)
        updateBall()
    }

    /** 面板贴在球下方（贴底则上方），并做边界收敛。 */
    private fun layoutPanel(ballX: Int, ballY: Int) {
        val p = panelParams ?: return
        val v = panel ?: return
        // v3.1.1 B 方案：紧凑条仅一行按钮 + 至多一行状态，高度按 56dp 估算。
        val panelH = dp(56)
        val preferredY = if (ballY + dp(56) + panelH <= screenH) ballY + dp(56)
        else (ballY - panelH - dp(8)).coerceAtLeast(0)
        // v2.9.19：边界收敛统一走全屏绝对坐标契约，与运行球、编辑面板一致。
        val (cx, cy) = OverlayBounds.clamp(
            ballX, preferredY, p.width, panelH, OverlayBounds.Size(screenW, screenH),
        )
        p.x = cx
        p.y = cy
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun hidePanel() {
        panel?.removeCallbacks(panelAutoHide)
        panel?.visibility = View.GONE
    }

    /** v3.1.1 A 方案：长按球展开紧凑面板，并在 4s 无操作后自动收起（C 方案）。 */
    private fun showPanel() {
        val p = panel ?: return
        p.visibility = View.VISIBLE
        layoutPanel(ballParams?.x ?: 0, ballParams?.y ?: 0)
        p.removeCallbacks(panelAutoHide)
        p.postDelayed(panelAutoHide, 4_000L)
    }

    /**
     * v3.1.1 双球分离：创建独立的建档相机球（与运行球并存、互不引用）。
     * 点球只调 [onCapture]（抓当前整页帧），不展开面板、不触发任何启停；
     * 悬浮窗权限缺失时 toast + 静默降级，绝不抛异常。
     */
    fun showCameraBall(onCapture: (() -> Unit)?) {
        if (cameraBall != null) return
        if (!Settings.canDrawOverlays(this)) {
            RunLog.e("Runner", "悬浮窗权限缺失，无法创建相机球")
            toast("缺少悬浮窗权限，无法使用相机球抓帧")
            return
        }
        val v = dev.nymph.auto.ui.CameraBall(this)
        v.onCapture = onCapture
        // 默认位置：运行球右下方错开 60dp，避免两球重叠；无运行球则屏幕右侧 1/2 高度。
        val baseX = ballParams?.x ?: (screenW - dp(72))
        val baseY = ballParams?.y ?: (screenH / 2)
        val p = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = dp(52)
            height = dp(52)
            gravity = Gravity.TOP or Gravity.START
            val (cx, cy) = OverlayBounds.clamp(
                baseX + dp(60), baseY + dp(48), dp(52), dp(52),
                OverlayBounds.Size(screenW, screenH),
            )
            x = cx
            y = cy
        }
        attachCameraBallTouch(v, p)
        runCatching { wm.addView(v, p) }.onFailure {
            RunLog.e("Runner", "相机球添加失败：${it.javaClass.simpleName}: ${it.message}")
            toast("相机球创建失败")
            return
        }
        cameraBall = v
        cameraParams = p
        RunLog.i("Runner", "相机球已显示：点击抓整页帧")
    }

    /** v3.1.1：退出建档态立即移除相机球并清空回调。 */
    fun hideCameraBall() {
        cameraBall?.let { v ->
            v.onCapture = null
            runCatching { wm.removeView(v) }
        }
        cameraBall = null
        cameraParams = null
    }

    /** 相机球触摸：拖动收敛边界；点击（未移动）只抓帧，无长按/启停语义。 */
    private fun attachCameraBallTouch(v: dev.nymph.auto.ui.CameraBall, p: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var offX = 0f
        var offY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    offX = p.x - e.rawX
                    offY = p.y - e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downRawX) > dp(8) || abs(e.rawY - downRawY) > dp(8)) {
                        moved = true
                    }
                    if (moved) {
                        val (cx, cy) = OverlayBounds.clamp(
                            (e.rawX + offX).toInt(), (e.rawY + offY).toInt(),
                            p.width, p.height, OverlayBounds.Size(screenW, screenH),
                        )
                        p.x = cx
                        p.y = cy
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.onCapture?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun attachBallTouch(v: FloatBall, p: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var offX = 0f
        var offY = 0f
        var moved = false
        var longPressed = false
        // v3.1.1 A 方案：长按球展开紧凑面板（不再是停止并移除）。
        // 停止动作收进面板「停止」按钮 / 通知栏；与点击启停语义分离，杜绝误触。
        val longPressRunnable = Runnable {
            longPressed = true
            vibrate(40L)
            RunLog.i("Runner", "长按悬浮球：展开紧凑控制面板")
            v.onShowPanel?.invoke()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    DragDiagnostics.onDown(DragDiagnostics.Target.RUNNER_BALL)
                    downRawX = e.rawX
                    downRawY = e.rawY
                    offX = p.x - e.rawX
                    offY = p.y - e.rawY
                    moved = false
                    longPressed = false
                    v.postDelayed(longPressRunnable, 700L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    DragDiagnostics.onMove(DragDiagnostics.Target.RUNNER_BALL)
                    if (abs(e.rawX - downRawX) > dp(8) || abs(e.rawY - downRawY) > dp(8)) {
                        moved = true
                        v.removeCallbacks(longPressRunnable)
                        // v2.9.4：旧版无边界收敛，球可被拖出屏幕后越界不可见
                        // （gravity=TOP|START，x/y 即左上偏移）。与录制面板同理 clamp。
                        // v2.9.19：e.rawX/rawY 为全屏绝对坐标，边界收敛必须取同一
                        // 坐标空间的窗口指标真源，不再使用应用窗口可用区尺寸。
                        val bx = p.x
                        val by = p.y
                        val (cx, cy) = OverlayBounds.clamp(
                            (e.rawX + offX).toInt(), (e.rawY + offY).toInt(),
                            p.width, p.height, OverlayBounds.Size(screenW, screenH),
                        )
                        p.x = cx
                        p.y = cy
                        runCatching { wm.updateViewLayout(v, p) }
                        // v2.9.17 / D3：旁路记录本次提交前后坐标，不改变位移逻辑。
                        DragDiagnostics.onCommit(DragDiagnostics.Target.RUNNER_BALL, bx, by, p.x, p.y)
                        layoutPanel(p.x, p.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    DragDiagnostics.onEnd(DragDiagnostics.Target.RUNNER_BALL)
                    v.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed) {
                        // v3.1.1 A 方案：点击运行球直接启停（不弹面板）；
                        // 长按已在 longPressRunnable 展开面板，此处不再处理。
                        hidePanel()
                        v.onToggle?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    DragDiagnostics.onEnd(DragDiagnostics.Target.RUNNER_BALL, wasCancelled = true)
                    v.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateBall() {
        ball?.setState(state)
        panel?.setState(state)
    }

    // ------------------------------------------------------------ 看门狗

    /**
     * 进度看门狗：脚本协程「还活着」不等于「还在推进」。
     *
     * 已注入系统的手势、图片 NCC 扫描、第三方 JS 都可能在无障碍/主线程之外
     * 长时间不返回；用户看到的现象是悬浮球一直在转、脚本却永远停在同一步。
     * 这里以 [ScriptRunner.lastProgressAt] 为心跳：连续 [WATCHDOG_STALL_MS]
     * 没有任何动作推进（且不处于暂停、不在等待条件）才判定卡死并停止，
     * 避免将长 WAIT / 长条件轮询误判终止——那两类在 ScriptRunner 里都会刷新心跳。
     *
     * 这是兜底而非主路径：正常脚本永远不会触发。触发时保留最后一步描述，
     * 让用户知道卡在哪，而不是只看到「停止了」。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val r = runner ?: continue
                if (state != State.RUNNING) continue
                val idle = android.os.SystemClock.elapsedRealtime() - r.lastProgressAt
                if (idle >= WATCHDOG_STALL_MS) {
                    val step = r.currentStepDesc.ifBlank { "未知动作" }
                    RunLog.e("Runner", "看门狗：${idle / 1000}s 无推进（最后动作：$step），判定卡死并停止")
                    toast("脚本 ${idle / 1000}s 无推进，已自动停止（$step）")
                    stopAll()
                    return@launch
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * 收到终止报告：把「为什么停 / 停在哪 / 能做什么」同时写进日志、面板与通知。
     *
     * 停在 HALTED 而不是直接回 IDLE，是因为用户此刻通常不在本应用界面——
     * 通知栏会保留「从这步继续」，回来时不必从头再跑或重新授权。
     */
    private fun onHalt(rep: dev.nymph.auto.core.runner.HaltReport.Report) {
        lastHalt = rep
        state = if (rep.resumable) State.HALTED else State.IDLE
        RunLog.w("Runner", "终止：${rep.headline}｜${rep.detail}")
        suspectNotice = null
        panel?.setSuspect("")
        panel?.setWait(null)
        panel?.setNext("")
        panel?.setHalt(rep)
        toast(rep.shortText)
        updateBall()
        refreshForeground()
    }

    /** 已成功跑完过的脚本名集合。读失败按空集处理（等价于「首次运行」，更保守）。 */
    private fun completedScripts(): Set<String> = runCatching {
        getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_COMPLETED, emptySet())
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    /** 记录一次成功跑完，供下次判定「首次运行」。 */
    private fun markCompleted(name: String) {
        if (name.isBlank()) return
        runCatching {
            val cur = completedScripts().toMutableSet()
            if (cur.add(name)) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putStringSet(KEY_COMPLETED, cur).apply()
            }
        }
    }

    /**
     * 归还连接（v2.9.30）。
     *
     * 与暂停的区别是**是否继续占用通道**：暂停保留连接、只是不派发动作；
     * 归还立即停止采集与注入，把相位收束到 RELEASE 再回 IDLE。
     *
     * 边界（如实说明，不美化）：无障碍通道由系统持有，本应用无法像 Shizuku 的
     * `disconnect()` 那样真正解绑系统服务。因此这里的「归还」精确含义是
     * **本进程不再读屏、不再截图、不再注入**；系统设置里的服务开关保持开启，
     * 以免用户每次都要回设置重开。状态卡与文案不得写成「已断开系统服务」。
     */
    private fun handover() {
        RunLog.i("Runner", "归还连接：停止采集与注入，回 IDLE")
        // v2.9.46：先让旧会话失效，旧协程收尾的字段写会被守卫跳过；随后用户可立刻
        // 开启新一轮，不会被这次收尾破坏。资源释放仍在旧协程 finally 里无条件完成。
        runToken.invalidate()
        movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Release)
        // 先让执行器停在检查点，再取消，避免注入链路在取消瞬间仍持有通道。
        runner?.paused?.value = true
        // v2.9.45：只发中断 + 取消协程。native runtime 的销毁交给脚本线程 finally
        // 与 invokeOnCompletion 兜底，主线程绝不 dispose 正在 eval 的 runtime（UAF）。
        quickJs?.requestStop()
        stopWatchdog() // v2.9.46：同步取消本轮看门狗（旧协程 finally 还会用局部引用兜底 cancel）
        runnerJob?.cancel()
        runnerJob = null
        runner = null
        quickJs = null
        visualizer?.close()
        visualizer = null
        lastHalt = dev.nymph.auto.core.runner.HaltReport.handedOver()
        suspectNotice = null
        state = State.IDLE
        panel?.setSuspect("")
        panel?.setWait(null)
        panel?.setNext("")
        panel?.setHalt(lastHalt)
        movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Settled)
        toast("已归还连接，不再占用读屏")
        updateBall()
        refreshForeground()
    }

    private fun stopAll() {
        RunLog.i("Runner", "停止运行并关闭悬浮球")
        // v2.9.46：先失效旧会话，旧收尾的字段提交全部跳过，允许立即重新运行。
        runToken.invalidate()
        movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Release)
        lastHalt = null
        suspectNotice = null
        panel?.setSuspect("")
        panel?.setNext("")
        panel?.setHalt(null)
        // 可视化层：与 ball/panel 一起清理，避免服务停止后残留浮层
        visualizer?.close()
        visualizer = null
        // 先中断正在执行的 JS（指令级），再取消协程——否则 JS 死循环
        // 会挡住取消检查点，停止按钮无响应。
        // v2.9.45：主线程只中断 + 取消，不直接 dispose 正在 eval 的 native runtime；
        // 销毁由脚本协程 finally + invokeOnCompletion 幂等兜底，杜绝 UAF / double-free。
        quickJs?.requestStop()
        stopWatchdog() // v2.9.46：同步取消本轮看门狗（旧协程 finally 还会用局部引用兜底 cancel）
        runnerJob?.cancel()
        runnerJob = null
        runner = null
        quickJs = null
        state = State.IDLE
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        hideCameraBall()
        ball?.let { v ->
            runCatching { wm.removeView(v) }
            ball = null
            ballParams = null
        }
        // v2.9.35：此前先撤前台再 movePhase(Settled)，而 movePhase 末尾会
        // refreshForeground→startForeground 又把前台挂回，导致服务销毁后通知残留。
        // 调换顺序：先让状态机收尾（如需前台则在此刷新），最后统一撤掉前台。
        movePhase(dev.nymph.auto.core.runner.ConnectionState.Event.Settled)
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * 停止前台服务的兼容写法：API 33+ 用 remove 语义，旧版用 boolean 重载
     * （避免 stopForeground(int) 在旧系统上 NoSuchMethodError）。
     */
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        runCatching {
            val vb = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vb.vibrate(ms)
            }
        }
    }

    private fun toast(msg: String) {
        // v2.9.35：脚本迁到专用线程后，脚本内回调（onFail/onAbnormal/zdjl.toast 等）
        // 也会走这里；Toast 必须在有 Looper 的主线程构造。postUi 幂等，主线程路径
        // 保持同步，脚本线程路径自动切回，一并修复"zdjl.toast 静默失效"。
        postUi {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

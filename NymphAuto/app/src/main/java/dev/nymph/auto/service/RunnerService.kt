package dev.nymph.auto.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
import dev.nymph.auto.ui.FloatBall
import dev.nymph.auto.visual.RecognitionSettings
import dev.nymph.auto.visual.SceneGuardSettings
import dev.nymph.auto.visual.VisualSettings
import dev.nymph.auto.visual.VisualizerLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.File

/**
 * 运行编排服务：状态机 + 悬浮球 + 通知栏控制。
 *
 * 录屏兼容性：全程无 MediaProjection；前台通知与悬浮窗均不与录屏会话交互。
 */
class RunnerService : Service() {

    enum class State { IDLE, RUNNING, PAUSED }

    companion object {
        const val ACTION_START = "dev.nymph.auto.action.START"
        const val ACTION_TOGGLE = "dev.nymph.auto.action.TOGGLE"
        const val ACTION_STOP = "dev.nymph.auto.action.STOP"
        const val EXTRA_SCRIPT_JSON = "script_json"
        const val EXTRA_SCRIPT_NAME = "script_name"
        const val PREFS = "nymphauto"

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

        /** 看门狗轮询间隔 / 判定卡死阈值。长 WAIT 与条件轮询会刷新心跳，不会误杀。 */
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
    private var script: Script? = null
    private var runner: ScriptRunner? = null
    private var runnerJob: Job? = null
    private var watchdogJob: Job? = null

    /** 本次运行的 QuickJS 原生运行时（停止时中断 + 释放；null=内存引擎）。 */
    private var quickJs: QuickJsAndroidBinding? = null

    /** 脚本运行授权（三档权限，与 UI 询问同一存储）。 */
    private val grants by lazy { ScriptGrants(File(filesDir, "scripts")) }
    private var state: State = State.IDLE
        set(value) {
            field = value
            publishLive(value, script?.name.orEmpty())
        }
    private lateinit var wm: WindowManager
    private var ball: FloatBall? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: BallPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    /** 动作可视化层（v2.4.0）；默认关闭时全程为 null，零开销。 */
    private var visualizer: VisualizerLayer? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        RunLog.i("Runner", "服务已创建（SDK ${Build.VERSION.SDK_INT}）")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RunLog.d("Runner", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
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
                toast("已就绪：${parsed.name}，点悬浮球展开控制面板")
            }
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
        }
        refreshForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        visualizer?.close()
        visualizer = null
        scope.cancel()
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
                    // AAPM 开启或无无障碍：诚实告知，不做任何绕过（裁定：架构不可用）
                    val why = if (cap.aapmEnabled) {
                        "高级保护模式（AAPM）已开启，自动化工具无豁免，请关闭后重试"
                    } else {
                        "无障碍服务不可用"
                    }
                    RunLog.e("Runner", "能力矩阵判不可用：$why")
                    toast(why)
                    return
                }
                // ---- OCR（ONNX Runtime 离线优先 / ML Kit GMS 兜底）+ AI 检测 ----
                // 均为可选通道：不可用时 VisionPipeline 内部降级，不影响其他能力。
                // 提前到预检之前创建，供 EnvironmentSnapshot.aiSupported 判定。
                val ocr = OcrEngineFactory.create(this@RunnerService)
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
                        installedPackages = launchablePackages(s),
                        aapmEnabled = cap.aapmEnabled,
                        aiSupported = aiDetector.available,
                    ),
                )
                if (!report.ok) {
                    RunLog.e("Runner", "预检拦截：${report.explain()}")
                    toast(report.explain())
                    ocr.close(); aiDetector.close()
                    return
                }
                RunLog.i("Runner", "开始执行「${s.name}」（屏幕 ${f.width}x${f.height}，档位 $tier）")
                // 坐标拾取是全屏可触摸模态层，若残留会混进 takeScreenshot 污染找图。
                // 回放启动即强制关闭（幂等），保证截图帧里没有编辑器浮层。
                CoordinatePicker.dismiss()
                val gesture = GestureEngine()
                // 注入屏幕边界：手势坐标（含 jitter / 拟人抖动 / 贝塞尔控制点）
                // 统一钳制在屏内，杜绝负 Path bounds 打断脚本（v2.2.0 真机修复）。
                gesture.setScreenBounds(f.width, f.height)
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
                r.onVisualCue = { cue -> visualizer?.onCue(cue) }
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
                // 等待浮层（裁定阶段 3.5）：主界面只显示"正在等待 X Ns / Ms"
                r.onWaitStatus = { st -> panel?.setWait(st) }
                // 四类归因（裁定阶段 4）：按类给出对应动作，并定位到失败步
                r.onFail = { reason, msg, step ->
                    val where = if (step > 0) "第 $step 步 · " else ""
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
                runner = r
                state = State.RUNNING
                startWatchdog()
                runnerJob = scope.launch {
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
                        stopWatchdog()
                        // 可视化层：移除窗口、注销帧回调、清空活跃 cue（停帧）
                        visualizer?.close()
                        visualizer = null
                        // 原生运行时随本次运行创建、随本次运行释放（防止多轮累积泄漏）
                        quickJs?.dispose()
                        this@RunnerService.quickJs = null
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
                    runner = null
                    runnerJob = null
                    state = State.IDLE
                    panel?.setWait(null)
                    RunLog.i("Runner", if (ok) "脚本执行完毕" else "脚本执行结束（未跑完）")
                    if (ok) toast("脚本执行完毕")
                    updateBall()
                    refreshForeground()
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
    private fun launchablePackages(s: Script): Set<String> = runCatching {
        val pm = packageManager
        s.dependencies().packages.filter { pm.getLaunchIntentForPackage(it) != null }.toSet()
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
        val s = script
        val text = when {
            state == State.RUNNING -> "执行中：${s?.name ?: ""}"
            state == State.PAUSED -> "已暂停：${s?.name ?: ""}"
            s != null -> "已就绪：${s.name} · 点悬浮球开始"
            else -> "NymphAuto"
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NymphApp.CHANNEL_STATUS)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NymphAuto")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (state == State.RUNNING) "暂停" else "继续", toggle).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------ 悬浮球 + 控制面板

    private fun showBall() {
        if (ball != null) return
        if (!Settings.canDrawOverlays(this)) {
            RunLog.e("Runner", "悬浮窗权限缺失，无法创建悬浮球")
            toast("缺少悬浮窗权限")
            return
        }
        RunLog.d("Runner", "创建悬浮球")
        val v = FloatBall(this)
        val p = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = dp(52)
            height = dp(52)
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - dp(72)).coerceAtLeast(0)
            y = resources.displayMetrics.heightPixels / 3
        }
        attachBallTouch(v, p)
        runCatching { wm.addView(v, p) }.onFailure {
            RunLog.e("Runner", "悬浮球添加失败：${it.javaClass.simpleName}: ${it.message}")
            toast("悬浮窗创建失败")
            return
        }
        ball = v
        ballParams = p
        RunLog.i("Runner", "悬浮球已显示，点击展开控制面板")

        // 控制面板（默认隐藏，点球展开）
        val panel = BallPanel(this).apply {
            onToggle = { toggle() }
            onStop = { stopAll() }
            onCollapse = { hidePanel() }
            visibility = View.GONE
        }
        val pp = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = dp(112)
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
        val dm = resources.displayMetrics
        // 高度含能力档位行与等待浮层行（5 行）
        val panelH = dp(200)
        p.x = ballX.coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
        p.y = if (ballY + dp(56) + panelH <= dm.heightPixels) ballY + dp(56)
        else (ballY - panelH - dp(8)).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun hidePanel() {
        panel?.visibility = View.GONE
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (p.visibility == View.VISIBLE) layoutPanel(ballParams?.x ?: 0, ballParams?.y ?: 0)
    }

    private fun attachBallTouch(v: FloatBall, p: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var offX = 0f
        var offY = 0f
        var moved = false
        var longPressed = false
        val longPressRunnable = Runnable {
            longPressed = true
            vibrate(60L)
            RunLog.i("Runner", "长按悬浮球：停止并关闭")
            toast("已停止")
            stopAll()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                    if (abs(e.rawX - downRawX) > dp(8) || abs(e.rawY - downRawY) > dp(8)) {
                        moved = true
                        v.removeCallbacks(longPressRunnable)
                        // v2.9.4：旧版无边界收敛，球可被拖出屏幕后永久失联
                        // （gravity=TOP|START，x/y 即左上偏移）。与录制面板同理 clamp。
                        val dm = resources.displayMetrics
                        p.x = (e.rawX + offX).toInt()
                            .coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
                        p.y = (e.rawY + offY).toInt()
                            .coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, p) }
                        layoutPanel(p.x, p.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed) togglePanel()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
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
     * 避免把长 WAIT / 长条件轮询误杀——那两类在 ScriptRunner 里都会刷新心跳。
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

    private fun stopAll() {
        RunLog.i("Runner", "停止运行并关闭悬浮球")
        // 可视化层：与 ball/panel 一起清理，避免服务停止后残留浮层
        visualizer?.close()
        visualizer = null
        // 先中断正在执行的 JS（指令级），再取消协程——否则 JS 死循环
        // 会挡住取消检查点，停止按钮无响应
        quickJs?.requestInterrupt()
        quickJs?.dispose()
        quickJs = null
        runnerJob?.cancel()
        runnerJob = null
        runner = null
        state = State.IDLE
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        ball?.let { v ->
            runCatching { wm.removeView(v) }
            ball = null
            ballParams = null
        }
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
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}

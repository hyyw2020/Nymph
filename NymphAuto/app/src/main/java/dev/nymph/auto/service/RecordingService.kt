// SPDX-License-Identifier: MIT
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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import dev.nymph.auto.NymphApp
import kotlin.math.max
import dev.nymph.auto.R
import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.recorder.RecordCodec
import dev.nymph.auto.core.recorder.RecordCompiler
import dev.nymph.auto.core.recorder.RecordabilityGuard
import dev.nymph.auto.core.store.ScriptStore
import dev.nymph.auto.engine.AutoClickService
import dev.nymph.auto.engine.OcrEngine
import dev.nymph.auto.recorder.RecordPanel
import dev.nymph.auto.recorder.RecorderBus
import dev.nymph.auto.recorder.RecordingSession
import dev.nymph.auto.ui.CoordinatePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录制编排服务：前台通知 + 悬浮控制面板 + 会话生命周期。
 *
 * ## 硬性约束：不和系统录屏抢权限 / 不打断其他 APP
 *
 * 1. **零 MediaProjection**：录制期间唯一截图能力是
 *    [AutoClickService.screenshotBitmap]（内部 `takeScreenshot()`），
 *    不申请录屏权限、不创建 VirtualDisplay、不与用户正在进行的录屏会话冲突。
 * 2. **不消费外部触摸**：控制浮窗用 `FLAG_NOT_TOUCH_MODAL`，只消费落在
 *    浮窗自身区域的点击；**刻意不加** `FLAG_WATCH_OUTSIDE_TOUCH`、
 *    不加全屏透明覆盖层、不加 `onTouchEvent` 拦截。落在其他 APP 上的触摸
 *    照常由系统分发给该 APP。
 * 3. **不启用触摸浏览**：无障碍服务配置中 `canRequestTouchExplorationMode=false`，
 *    用户单击不会被重解释为双击。
 * 4. **回调不阻塞**：无障碍事件经 [RecorderBus] 立即转投，截图/OCR 在后台协程，
 *    不占用系统窗口分发线程。
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: RecordingSession? = null
    /** 录制期创建的 OCR 引擎：OcrEngineFactory.create 契约要求调用方 close()。 */
    private var ocrEngine: OcrEngine? = null
    private var panel: RecordPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var wm: WindowManager
    private var startedAt = 0L
    private var pendingName: String = ""
    /** 最近一次收到录制事件的时刻（elapsedRealtime），空闲守卫据此自动收尾。 */
    private var lastEventAt = 0L
    private var idleJob: kotlinx.coroutines.Job? = null
    /** v2.9.2：录制可行性守卫协程与「只提示一次」标志。 */
    private var capabilityJob: kotlinx.coroutines.Job? = null
    private var capabilityWarned = false
    /** 通知栏刷新节流：高频事件不逐条重建通知，但保证最后一次补刷。 */
    private var lastNotifyAt = 0L
    private var notifyTailScheduled = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        RunLog.i("Record", "录制服务已创建")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_NAME))
            ACTION_UNDO -> {
                val ok = session?.undo() ?: false
                toast(if (ok) "已撤销上一步" else "没有可撤销的步骤")
                refreshPanel()
                refreshForeground()
            }
            ACTION_STOP -> finishAndStop()
        }
        return START_NOT_STICKY
    }

    private fun begin(name: String?) {
        if (session != null) { toast("已在录制中"); return }
        // 编辑器坐标拾取浮层不参与录制，启动即强制关闭（幂等），
        // 避免全屏可触摸层常驻干扰其他 APP 或污染后续截图。
        CoordinatePicker.dismiss()
        val svc = AutoClickService.live()
        if (svc == null) {
            RunLog.e("Record", "无障碍服务未连接，无法录制")
            toast("请先开启无障碍服务")
            stopSelf()
            return
        }
        startedAt = SystemClock.elapsedRealtimeCompat()
        pendingName = name?.takeIf { it.isNotBlank() } ?: defaultName()
        val frame = runCatching { svc.frame() }.getOrNull()
        // v2.7.0：传入本应用包名 → 启用「过滤自扰事件 + 待命到切出本应用」。
        // 用户点"开始录制"后不用急着切后台，点击器内的一切操作都不会被记进脚本。
        RecorderBus.start(frame, packageName)
        RecorderBus.onArmedChanged = { on ->
            liveArmed = on
            RunLog.i("Record", if (on) "已进入目标 APP，开始记录" else "录制待命中")
            refreshPanel()
            refreshForeground()
        }

        val s = RecordingSession(
            scope = scope,
            enhancer = buildEnhancer(),
            // v1.2.1：无悬浮窗权限时 panel 为 null，旧版这里只刷新浮窗，
            // 通知栏步数永远是 0，用户完全看不到录制是否在采事件。
            onEventCount = { n ->
                liveCount = n
                lastEventAt = SystemClock.elapsedRealtimeCompat()
                scope.launch(Dispatchers.Main.immediate) {
                    refreshPanel(n)
                    scheduleNotify()
                }
            },
        )
        session = s
        RecorderBus.attach { e -> s.accept(e) }
        s.start()

        // 进程内状态快照，供 Compose 主界面显示「待命 / 录制中 · N 步」
        liveActive = true
        liveArmed = RecorderBus.armed
        liveCount = 0
        liveStartedAt = startedAt
        liveName = pendingName
        lastEventAt = startedAt
        startIdleGuard()
        startCapabilityGuard()

        showPanel()
        refreshForeground()
        RunLog.i(
            "Record",
            "开始录制「$pendingName」：参考屏幕 ${frame?.width}×${frame?.height}；" +
                "待命中（本应用内操作不记录，切到目标 APP 自动开始）",
        )
        // 打印系统实际生效的无障碍配置，用于确认 eventTypes 订阅没有被运行时覆盖
        RunLog.i("Record", "无障碍生效配置：${svc.effectiveConfig()}")
        toast("录制中：请照常操作其他 APP，点浮窗停止并保存")
    }

    /**
     * 增强器：无障碍截图 + 离线 OCR。
     * OCR 引擎由 [dev.nymph.auto.engine.OcrEngineFactory] 提供，
     * 优先 ONNX Runtime 可插拔通道，不可用时优雅降级（仅录语义/模板锚点）。
     */
    private fun buildEnhancer(): RecordingSession.Enhancer? {
        val svc = AutoClickService.live() ?: return null
        closeOcr() // 防御：异常路径下重复 begin 不遗留旧引擎
        val ocr = runCatching { dev.nymph.auto.engine.OcrEngineFactory.create(this) }.getOrNull()
        ocrEngine = ocr
        return object : RecordingSession.Enhancer {
            override suspend fun screenshot() = runCatching { svc.screenshotBitmap() }.getOrNull()

            override suspend fun ocrNear(bmp: android.graphics.Bitmap, x: Int, y: Int): String? {
                val engine = ocr ?: return null
                if (!engine.available) return null
                val boxes = runCatching { engine.recognize(bmp) }.getOrElse { return null }
                // 取离锚点最近、且非空的文字行
                return boxes.filter { it.text.isNotBlank() }
                    .minByOrNull { b ->
                        val dx = (b.cx - x).toLong()
                        val dy = (b.cy - y).toLong()
                        dx * dx + dy * dy
                    }
                    ?.takeIf { b ->
                        val dx = (b.cx - x).toLong()
                        val dy = (b.cy - y).toLong()
                        // 只在锚点附近 160px 内采纳，避免误取远处标题
                        dx * dx + dy * dy <= 160L * 160L
                    }?.text
            }
        }
    }

    /**
     * 释放录制期创建的 OCR 引擎。OcrEngineFactory.create 的契约明确
     * 「调用方负责 close」：OnnxOcrEngine 释放两个 OrtSession、
     * MlKitOcrEngine 释放 TextRecognizer，均为原生资源。旧版录制服务从不释放，
     * 每录一轮新建一套，反复录制会持续累积泄漏（RunnerService 已在 finally 中
     * 关闭，仅录制链路遗漏）。必须在增强协程排空之后调用，避免增强中途关闭引擎。
     */
    private fun closeOcr() {
        ocrEngine?.let { runCatching { it.close() } }
        ocrEngine = null
    }

    private fun finishAndStop() {
        val s = session ?: run { stopSelf(); return }
        // v1.2.1：结束时给出「系统到底派发了什么」的完整统计。即便脚本为空，
        // 也能从这一行判断是「目标 APP 不发被动语义事件」还是「本工程丢弃了事件」。
        // v2.9.2：必须在 stop() 之前采集 signal —— stop 会重置 gate（armed 丢失）
        // 和 sink，之后就无法判定「用户是否已进入目标 APP」。
        val capability = RecorderBus.signal()
        val verdict = RecordabilityGuard.warningVerdict(capability)
        val diagnostics = RecorderBus.diagnostics()
        RunLog.i("Record", "录制诊断：$diagnostics")
        capabilityJob?.cancel()
        capabilityJob = null
        RecorderBus.stop()
        // finish() 会先等后台增强排空（增强期间仍在用 ocrEngine），故必须在其后释放。
        // 编译可能因超大脚本 OOM 抛异常：无论成败都必须复位前台状态，
        // 否则界面会一直显示「录制中」而服务其实已经不可用。
        val compiled = runCatching { s.finish(pendingName, RecordCompiler()) }
        closeOcr()
        session = null
        liveActive = false
        liveArmed = false
        val (script, report) = compiled.getOrElse { e ->
            RunLog.e("Record", "编译失败：${e.javaClass.simpleName}: ${e.message}")
            toast("保存失败：${e.message ?: e.javaClass.simpleName}")
            hidePanel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (script.actions.isEmpty()) {
            // v2.9.2：不再只给一句「未录到有效动作，已取消」——用户由此完全
            // 不知道为什么、更不知道该改用什么方式，正是本次真机差评的根源。
            // 完整指引（根因 + 替代路径 + 诊断）写进日志页，toast 用短版。
            RunLog.w("Record", RecordabilityGuard.finishGuidance(verdict, diagnostics))
            toast(RecordabilityGuard.finishToast(verdict))
        } else {
            // 事件流明文留档，便于事后审计「录制器到底记了什么」
            runCatching {
                File(filesDir, "recordings").mkdirs()
                File(filesDir, "recordings/${safeStamp()}.events.json")
                    .writeText(RecordCodec.encode(s.eventsForAudit()))
            }
            val store = ScriptStore(File(filesDir, "scripts"))
            runCatching { store.save(script) }
                .onSuccess {
                    RunLog.i("Record", "已保存「${script.name}」：${report.oneLine()}")
                    toast("已保存「${script.name}」：${report.oneLine()}")
                }
                .onFailure { e ->
                    RunLog.e("Record", "保存失败：${e.message}")
                    toast("保存失败：${e.message}")
                }
        }
        hidePanel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ 悬浮控制面板

    /**
     * 空闲守卫：录制会话长时间收不到任何事件时自动停止并保存。
     *
     * 典型场景是用户点了「开始录制」后切走忘了停——前台服务 + 通知常驻
     * 会一直耗电，且用户下次打开 App 会看到「已在录制中」而困惑。
     * 阈值取 10 分钟：远长于正常的阅读/思考停顿，又不会让忘关持续整晚。
     */
    private fun startIdleGuard() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_TICK_MS)
                if (session == null) return@launch
                val idleMs = SystemClock.elapsedRealtimeCompat() - lastEventAt
                if (idleMs >= IDLE_STOP_MS) {
                    RunLog.w("Record", "录制空闲 ${idleMs / 60_000} 分钟无操作，自动停止并保存")
                    toast("长时间无操作，已自动停止录制并保存")
                    finishAndStop()
                    return@launch
                }
            }
        }
    }

    /**
     * v2.9.2：录制可行性守卫 —— 自绘界面（游戏 / 视频 / 全绘页面）里被动无障碍
     * 录制收不到任何点击，用户会白操作几十秒才在收尾看到「未录到有效动作」。
     *
     * 这里在 8 秒后判定一次并**只提示一次**（面板 hint + WARN 日志 + toast 三通道必达），
     * 绝不阻断录制、绝不改变窗口 flags 或触摸分发：用户可能正在用标准控件做有效录制，
     * 也可能稍后才切到目标 APP。命中后不再反复打扰。
     */
    private fun startCapabilityGuard() {
        capabilityJob?.cancel()
        capabilityWarned = false
        capabilityJob = scope.launch {
            while (session != null && !capabilityWarned) {
                delay(2_000L)
                if (session == null) return@launch
                val sig = RecorderBus.signal()
                if (!RecordabilityGuard.shouldWarnDuring(sig)) continue
                capabilityWarned = true
                val v = RecordabilityGuard.warningVerdict(sig)
                val hint = RecordabilityGuard.duringHint(v)
                RunLog.w("Record", "录制可行性告警：${RecordabilityGuard.reason(v)}")
                scope.launch(Dispatchers.Main.immediate) {
                    panel?.setHint(hint)
                    refreshForeground()
                }
                if (hint.isNotBlank()) toast(hint.replace('\n', ' '))
                return@launch
            }
        }
    }

    private fun showPanel() {
        if (panel != null) return
        if (!Settings.canDrawOverlays(this)) {
            RunLog.w("Record", "无悬浮窗权限，改用通知栏控制（不影响录制）")
            return
        }
        val v = RecordPanel(this).apply {
            onStop = { finishAndStop() }
            onUndo = { session?.undo(); refreshPanel(); refreshForeground() }
        }
        val p = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            // 关键：NOT_TOUCH_MODAL 只消费浮窗自身区域；不加 WATCH_OUTSIDE_TOUCH
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(8)
        }
        val (sw, sh) = screenSize()
        v.onDrag = { dx, dy ->
            // 只改 x/y，夹在屏幕内；不改 flags、不拦截外部触摸（保持非模态契约）
            val w = v.width.coerceAtLeast(1)
            val h = v.height.coerceAtLeast(1)
            // v2.9.4：gravity=TOP|END 时 p.x 是「距右边缘的偏移」，x 增大面板左移。
            // 旧版写 p.x + dx 让水平拖动方向与手指相反，这里改为减，clamp 区间不变。
            p.x = (p.x - dx).coerceIn(0, max(0, sw - w))
            p.y = (p.y + dy).coerceIn(0, max(0, sh - h))
            runCatching { wm.updateViewLayout(v, p) }
        }
        runCatching { wm.addView(v, p) }.onFailure {
            RunLog.e("Record", "录制面板添加失败：${it.message}")
            return
        }
        panel = v
        panelParams = p
        refreshPanel()
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
    }

    private fun refreshPanel(count: Int = session?.count() ?: 0) {
        panel?.setCount(count, elapsedSec())
    }

    /**
     * 通知栏步数刷新（v1.2.1）。高频事件（滚动/文本输入）不逐条重建通知：
     * 400ms 内合并，并安排一次尾刷保证最终显示真实步数。
     * 无悬浮窗权限时通知栏是唯一的可见进度反馈，因此不能省略。
     */
    private fun scheduleNotify() {
        val now = SystemClock.elapsedRealtimeCompat()
        if (now - lastNotifyAt >= 400L) {
            refreshForeground()
        } else if (!notifyTailScheduled) {
            notifyTailScheduled = true
            scope.launch {
                delay(450L)
                notifyTailScheduled = false
                if (session != null) refreshForeground()
            }
        }
    }

    // ------------------------------------------------------------ 通知

    private fun refreshForeground() {
        lastNotifyAt = SystemClock.elapsedRealtimeCompat()
        val open = PendingIntent.getActivity(
            this, 11,
            Intent(this, dev.nymph.auto.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 12,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val undo = PendingIntent.getService(
            this, 13,
            Intent(this, RecordingService::class.java).setAction(ACTION_UNDO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = session?.count() ?: 0
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NymphApp.CHANNEL_STATUS)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("录制中：$pendingName")
            .setContentText(
                if (capabilityWarned) "⚠ 该界面可能收不到点击，详见日志页的替代路径"
                else if (!liveArmed) "待命：切到目标 APP 自动开始 · 点击器内操作不记录"
                else if (count == 0) "请照常操作其他 APP · 不占用录屏权限"
                else "已记录 $count 个事件 · 不占用录屏权限"
            )
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "撤销", undo).build())
            .addAction(Notification.Action.Builder(null, "停止并保存", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, n)
        }
    }

    private fun elapsedSec(): Long =
        (SystemClock.elapsedRealtimeCompat() - startedAt) / 1000

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 屏幕像素尺寸（拖动 clamp 用）。 */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun toast(text: String) {
        scope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(this@RecordingService, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun defaultName(): String = "录制 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

    private fun safeStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    override fun onDestroy() {
        RecorderBus.stop()
        hidePanel()
        liveActive = false
        liveArmed = false
        idleJob?.cancel()
        idleJob = null
        capabilityJob?.cancel()
        capabilityJob = null
        scope.cancel()      // 先停后台增强协程，避免其仍在使用 OCR 引擎
        closeOcr()          // 服务异常终止/被杀时也要释放，不能只依赖正常 finish
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.nymph.auto.action.RECORD_START"
        const val ACTION_STOP = "dev.nymph.auto.action.RECORD_STOP"
        const val ACTION_UNDO = "dev.nymph.auto.action.RECORD_UNDO"
        const val EXTRA_NAME = "record_name"

        /** 空闲守卫：每 30s 检查一次，连续 10 分钟零事件即自动收尾。 */
        private const val IDLE_TICK_MS = 30_000L
        private const val IDLE_STOP_MS = 10 * 60_000L

        /**
         * 进程内实时录制状态（供 Compose 主界面只读展示）。
         * 与 RunnerService 的 live 字段同一思路：同进程标量，不做 Binder/广播。
         */
        @Volatile
        var liveActive: Boolean = false
            private set

        /** v2.7.0：false = 待命（还在点击器里），true = 已进入目标 APP 正在记录。 */
        @Volatile
        var liveArmed: Boolean = false
            private set

        @Volatile
        var liveCount: Int = 0
            private set

        @Volatile
        var liveStartedAt: Long = 0L
            private set

        @Volatile
        var liveName: String = ""
            private set

        fun start(context: Context, name: String? = null) {
            val i = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, name)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        /**
         * 从界面停止录制。用 startService 而非 startForegroundService：
         * 服务已在运行且处于前台，投递一条命令即可；若误用
         * startForegroundService 而服务恰好已销毁，会在 5s 内等不到
         * startForeground 而抛 ForegroundServiceDidNotStartInTimeException。
         */
        fun stop(context: Context) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}

/** 避免与 android.os.SystemClock 混淆的小工具（同一实现，集中一处便于替换）。 */
private object SystemClock {
    fun elapsedRealtimeCompat(): Long = android.os.SystemClock.elapsedRealtime()
}

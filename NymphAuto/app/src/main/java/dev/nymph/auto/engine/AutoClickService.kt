// SPDX-License-Identifier: MIT
// NymphAuto 修改：onAccessibilityEvent 由空实现改为「被动事件转发」，
// 供离线脚本录制器使用。仅观察、不消费触摸；无录制会话时零开销返回。
// 详见 accessibility_service_config.xml 与 UPSTREAM_LICENSES.md 第 7 节。
package dev.nymph.auto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.vision.EdgeSafety
import dev.nymph.auto.core.vision.ImageMask
import dev.nymph.auto.legacy.screenshot.CompatFallback
import dev.nymph.auto.legacy.screenshot.FallbackPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * 无障碍服务桥接：手势注入的承载体 + 截图 + 节点树 + 全局动作。
 *
 * 设计要点：
 * - instance 静态持有仅用于跨组件（RunnerService）发现服务，**执行前必须重新校验**，
 *   防止服务解绑重连后 GestureEngine 持有失效实例（PureClick 的已知缺口 R4）。
 * - takeScreenshot 仅 API 30+；更低版本明确返回 null 由上层降级。
 */
class AutoClickService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // v1.2.1：不再在运行时 setServiceInfo()——XML 已声明全部所需 flags 与
        // eventTypes，动态覆盖反而有把配置写没的风险。这里只打印系统实际生效的
        // 订阅集合，便于诊断「录制收不到事件」时确认系统到底给不给我们派发。
        RunLog.i("A11y", "无障碍服务已连接：${effectiveConfig()}")
    }

    /**
     * 系统实际生效的无障碍配置摘要（诊断用，非敏感信息）。
     * eventTypes 为空或缺少 windowState/windows 即说明订阅没生效。
     */
    fun effectiveConfig(): String {
        val i = runCatching { serviceInfo }.getOrNull()
            ?: return "serviceInfo=null（请重新开关无障碍服务）"
        val names = mutableListOf<String>()
        fun mark(mask: Int, name: String) {
            if (i.eventTypes and mask != 0) names += name
        }
        mark(AccessibilityEvent.TYPE_VIEW_CLICKED, "clicked")
        mark(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, "longClicked")
        mark(AccessibilityEvent.TYPE_VIEW_SCROLLED, "scrolled")
        mark(AccessibilityEvent.TYPE_VIEW_FOCUSED, "focused")
        mark(AccessibilityEvent.TYPE_VIEW_SELECTED, "selected")
        mark(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "textChanged")
        mark(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "windowState")
        mark(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "windows")
        mark(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "windowContent")
        val screenshot = if (Build.VERSION.SDK_INT >= 30) {
            (i.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
        } else false
        return "eventTypes=0x${Integer.toHexString(i.eventTypes)}" +
            "[${names.joinToString("|").ifBlank { "无" }}] " +
            "flags=0x${Integer.toHexString(i.flags)} canTakeScreenshot=$screenshot"
    }

    override fun onUnbind(intent: Intent): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * 被动语义事件转发。
     *
     * **性能契约**：本回调与系统窗口分发同处一条调用链，必须立即返回。
     * 仅在录制活跃时转投 [dev.nymph.auto.recorder.RecorderBus]，后者也只做
     * 轻量字段读取后入队；截图/OCR/落盘全部由录制会话的后台协程承担。
     * 未在录制时这里是一个 null 判断即返回，回放路径零额外开销。
     *
     * **权限契约**：只观察事件，不消费、不拦截、不重排任何触摸；
     * 零 MediaProjection；服务配置未启用触摸浏览，不改变其他 APP 的点击语义。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val bus = dev.nymph.auto.recorder.RecorderBus
        if (!bus.active) return
        bus.onEvent(event)
    }

    override fun onInterrupt() { /* 无语音反馈，无需处理 */ }

    /**
     * 最近一次截图失败的错误码（0=成功或未失败）。
     * ERROR_TAKE_SCREENSHOT_SECURE_WINDOW 表示当前窗口带 FLAG_SECURE
     * （截图黑帧）——能力矩阵据此判 RESTRICTED 档。
     */
    @Volatile
    var lastScreenshotError: Int = 0
        private set

    /**
     * v2.9.36：截图请求单飞标志。takeScreenshot 的底层请求在超时后不会被系统取消，
     * 若每帧（16ms 同步）都提交新请求，回调迟到时无人消费、请求在系统截图队列里
     * 不断堆积，合成器被淹没 → 帧间隔从 280ms 逐步劣化到 27s，表现为主界面假死。
     *
     * 这里用 CAS 保证同一时刻只有一个在途请求：上一帧回调（成功/失败/超时）未
     * 落地前，新的截图调用直接快速返回 null（丢帧而非排队）。场景驱动逐帧判定
     * 对偶发丢帧是 fail-closed 的（该轮全规则未在场），宁可少判一帧也不堆积。
     *
     * 注意超时路径必须复位本标志（见 [awaitShot]），否则一次永久迟到的回调会把
     * 截图通道永久锁死——这比堆积更糟。
     */
    private val shotInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 无障碍全屏截图（API 30+）。最多重试 1 次，3s 超时。
     *
     * v2.2.0 回归修复：v2.0 曾把 Android 14+ 的 `takeScreenshotOfWindow`
     * 设为优先通道，真机（SDK 37）实测未通过——它返回的是**活动窗口自身的
     * 静态快照**：尺寸 1128×1378、带透明通道、多轮抓取内容完全不变（5 张
     * 失败现场截图 SHA-256 相同），而脚本的模板锚点是按 960×2100 全屏基准
     * 录制的。坐标系与内容都错配，NCC 恒为 0.00~0.24，一次运行 11 轮 32 次
     * 全失败。故回退为**全屏 takeScreenshot 唯一通道**，与模板录制基准一致。
     *
     * 单一通道还有个好处：帧缓存不会把某个窗口的冻结快照反复复用。
     *
     * 仍是无障碍截图：**不创建 MediaProjection、不占用系统录屏**。
     */
    @FallbackPath(
        since = "v3.0.0",
        forRemoval = true,
        reason = "截图取证委托给 legacy/screenshot/CompatFallback；新链禁止调用",
    )
    @Deprecated(
        message = "截图取证降级路径，计划移除；新代码请走真值录制/SimpleTap",
        level = DeprecationLevel.WARNING,
    )
    suspend fun screenshotBitmap(): Bitmap? {
        // v2.9.24：错误码是「本次调用」的事实，不是「上次调用」的残留。
        // 不清零会让超时路径读到上一轮的 3（安全窗口），把归因彻底带偏。
        lastScreenshotError = SHOT_ERROR_NONE
        if (!CompatFallback.screenshotSupported()) {
            lastScreenshotError = SHOT_ERROR_UNSUPPORTED
            return null
        }
        // v3.0.0 / ADR-068：唯一 takeScreenshot 调用点已物理隔离到
        // legacy/screenshot/CompatFallback；本方法仅委托，调用方显式标注降级。
        return awaitShot { callback ->
            @Suppress("DEPRECATION")
            CompatFallback.submitShot(this, callback = callback)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun awaitShot(submit: (TakeScreenshotCallback) -> Unit): Bitmap? {
        // 双保险：即使将来有调用方绕过 screenshotBitmap 直接进来，低版本也只会
        // 得到 null + 明确错误码，而不是 NoClassDefFoundError。
        if (Build.VERSION.SDK_INT < 30) {
            lastScreenshotError = SHOT_ERROR_UNSUPPORTED
            return null
        }
        // v2.9.36：单飞——上一请求的回调（成功/失败/超时）未落地前不再提交新请求。
        // 底层 takeScreenshot 请求在超时后不会被系统取消，16ms 连发会让请求在
        // 系统队列里堆积、合成器被淹没，帧间隔劣化到 27s（主界面假死）。丢帧是
        // 可接受的（场景判定 fail-closed），堆积不可接受。
        if (!shotInFlight.compareAndSet(false, true)) {
            lastScreenshotError = SHOT_ERROR_BUSY
            return null
        }
        lastScreenshotError = SHOT_ERROR_NONE
        val done = CompletableDeferred<Bitmap?>()
        // 代次令牌：超时/取消后本请求作废；迟到回调不得消费结果、不得覆盖错误码。
        val token = java.util.concurrent.atomic.AtomicBoolean(true)
        // v2.9.37：统一解锁，代次隔离 + 幂等。只有本请求 token 仍为 true 时才能释放
        // 单飞锁——避免「超时已放行新请求、旧回调迟到又 set(false)」误释放新请求的锁。
        // 所有结束路径（成功/失败/超时/取消/提交异常）都经此一处，杜绝永久 BUSY。
        fun release() {
            if (token.compareAndSet(true, false)) shotInFlight.set(false)
        }
        try {
            submit(object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = result.hardwareBuffer
                    try {
                        if (!token.get()) {
                            // 本请求已超时/取消作废：只释放硬件缓冲，不消费结果、不改状态。
                            return
                        }
                        lastScreenshotError = SHOT_ERROR_NONE
                        val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                        val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        runCatching { wrapped?.recycle() }
                        done.complete(software)
                    } catch (e: Throwable) {
                        // v2.9.35：catch(Throwable) 覆盖 OOM（原 catch(Exception) 捕不住
                        // OutOfMemoryError 导致 done 永不 complete）。
                        if (token.get()) done.complete(null)
                    } finally {
                        // v2.9.35：hardware.close() 用 finally 保证释放，避免 fd 泄漏。
                        runCatching { hardware.close() }
                        release()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    try {
                        if (!token.get()) return
                        // 外传错误码：secure 窗口（黑帧）与普通失败要区分——
                        // 前者判能力矩阵 RESTRICTED 档，后者走熔断。
                        lastScreenshotError = errorCode
                        done.complete(null)
                    } finally {
                        release()
                    }
                }
            })
            // v2.9.36：单次等待，不再固定重试 2 次。重试由上层 AccessibilityScreenshotSource
            // 的连续失败熔断统一负责；此处若再重试只会让单帧最坏耗时回到 6.4s，
            // 与「不堆积」的目标直接冲突。超时阈值从 3s 收敛到 1.5s，加快丢帧节奏。
            val bmp = withTimeoutOrNull(SHOT_TIMEOUT_MS) { done.await() }
            if (bmp != null) return bmp
            // 超时：本请求作废，release() 由 finally 统一执行。
            if (lastScreenshotError == SHOT_ERROR_NONE) {
                lastScreenshotError = SHOT_ERROR_TIMEOUT
            }
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v2.9.37：脚本停止/暂停取消协程时 done.await() 抛此异常。重抛以保留取消
            // 语义（不吞信号）；单飞锁由 finally 释放，杜绝「取消后永久 BUSY」。
            throw e
        } catch (e: Exception) {
            // 提交本身抛异常（服务已解绑 / 系统拒绝调用）。
            if (lastScreenshotError == SHOT_ERROR_NONE) {
                lastScreenshotError = SHOT_ERROR_TIMEOUT
            }
            return null
        } finally {
            // v2.9.37：所有路径兜底解锁。release() 幂等——回调已解锁时 token=false，
            // 不会再动；取消/超时/异常时在此解锁。这是根治永久 BUSY 的关键。
            release()
        }
    }

    /** 全局动作：返回/主页/任务/通知/锁屏等。 */
    fun global(action: Int): Boolean = performGlobalAction(action)

    /** 当前前台窗口根节点。 */
    fun rootInActiveWindow(): AccessibilityNodeInfo? =
        runCatching { rootInActiveWindow }.getOrNull()

    /**
     * 屏幕真实尺寸与旋转。
     *
     * 注意（Android 12 崩溃根因）：不能用 `Context.getDisplay()`——Service 是
     * 非 UI context，该方法在 Android 12 抛 UnsupportedOperationException
     * （低版本抛出的时机更晚，靠 runCatching 掩盖了）。正确做法：
     *  1. API 30+：WindowManager.maximumWindowMetrics.bounds（与 display context 无关）
     *  2. 回退：defaultDisplay.getRealMetrics（弃用但 Service 下可用）
     *  3. 兜底：前台窗口根节点的屏幕 bounds（无障碍服务下最可靠）
     */
    /**
     * v2.9.29：当前设备的系统手势热区（像素）。
     *
     * 取 `systemGestures` 与 `systemBars` 的并集：手势导航的返回热区在左右边缘、
     * Home 热区在底部；三键导航没有手势热区，但导航栏本身也不可点，同样要避开。
     *
     * 拿不到时退回 [EdgeSafety.fallback]（4dp）。**不用更大的保守值**：过度内移
     * 会把点击推到相邻控件上，比点空更难排查。
     */
    fun gestureInsets(): EdgeSafety.Insets {
        val d = resources.displayMetrics.density
        if (Build.VERSION.SDK_INT < 30) return EdgeSafety.fallback(d)
        return runCatching {
            val wm = getSystemService(WindowManager::class.java)
            val metrics = wm.currentWindowMetrics
            val i = metrics.windowInsets.getInsets(
                WindowInsets.Type.systemGestures() or WindowInsets.Type.systemBars(),
            )
            EdgeSafety.Insets(i.left, i.top, i.right, i.bottom)
        }.getOrElse { EdgeSafety.fallback(d) }
    }

    fun frame(): Frame? {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val size = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) b.width() to b.height() else null
            } else null
        }.getOrNull() ?: runCatching {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.widthPixels to dm.heightPixels else null
        }.getOrNull() ?: runCatching {
            val r = Rect()
            rootInActiveWindow?.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) r.width() to r.height() else null
        }.getOrNull() ?: return null
        val rotation = runCatching {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation
        }.getOrDefault(0)
        return Frame(size.first, size.second, rotation)
    }

    /**
     * 自家悬浮窗口在屏幕上的百分比矩形（v2.3.0）。
     *
     * 全屏 `takeScreenshot()` 会把悬浮球、控制面板、等待浮层一起截进去，
     * 真机失败现场截图已实锤。这里通过无障碍窗口列表精确取回**只属于本
     * 应用**、且**非全屏**的窗口（悬浮球 / 控制面板 / 浮层），交给 [ImageMask]
     * 在匹配用图像副本上抑制。严格按 root 节点包名 == 自身包名、且窗口面积
     * 明显小于屏幕两个条件过滤，绝不碰其他 App 的窗口，也不会误抑制自家
     * 全屏主界面；取不到窗口时返回空列表（能力不降级、无副作用）。
     *
     * 说明：悬浮窗在系统里以 WindowManager 的 TYPE_APPLICATION_OVERLAY 添加，
     * 但在无障碍窗口列表中通常仍上报为普通应用窗口，且 AccessibilityWindowInfo
     * 并未提供 TYPE_APPLICATION_OVERLAY 常量，故不按 window.type 过滤，改用
     * 「包名 + 面积」双条件，跨系统版本更鲁棒。
     */
    fun selfOverlayRectsPct(): List<ImageMask.RectPct> {
        val f = frame() ?: return emptyList()
        if (f.width <= 0 || f.height <= 0) return emptyList()
        val screenArea = f.width.toLong() * f.height.toLong()
        return runCatching {
            windows.orEmpty().mapNotNull { w ->
                val rootPkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
                if (rootPkg != packageName) return@mapNotNull null
                val r = Rect()
                w.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0) return@mapNotNull null
                // 全屏主界面不抑制：面积达到屏幕 60% 以上视为全屏窗口跳过
                val area = r.width().toLong() * r.height().toLong()
                if (area * 100 >= screenArea * 60) return@mapNotNull null
                ImageMask.RectPct(
                    100.0 * r.left / f.width,
                    100.0 * r.top / f.height,
                    100.0 * r.right / f.width,
                    100.0 * r.bottom / f.height,
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** 采集成功 / 本调用内尚未失败。 */
        const val SHOT_ERROR_NONE = 0

        /**
         * v2.9.24：本地合成的截图错误码（系统只定义 1..5，负值与系统码不冲突）。
         *
         * 此前「回调根本没返回」与「回调返回了错误码」共用同一个字段，超时后读到的
         * 是**上一次调用残留的错误码**，会把一次超时误判成安全窗口拒绝。现在每次
         * 采集开始都把字段清零，超时单独记 [SHOT_ERROR_TIMEOUT]，低版本单独记
         * [SHOT_ERROR_UNSUPPORTED]，归因不再跨调用串味。
         */
        const val SHOT_ERROR_TIMEOUT = -2

        /** v2.9.36：上一截图请求仍在途，本次被单飞机制丢弃（非失败，仅本帧无新画面）。 */
        const val SHOT_ERROR_BUSY = -4

        /** 系统版本不支持无障碍截图（需 Android 11 / API 30）。 */
        const val SHOT_ERROR_UNSUPPORTED = -3

        /**
         * v2.9.36：单次截图等待上限。原 3s×2 次重试使单帧最坏 6.4s，是帧堆积的
         * 直接来源；现在单飞 + 单次等待，时序收敛到 1.5s 上限，超时即丢帧由上层
         * 连续失败熔断处理。
         */
        const val SHOT_TIMEOUT_MS = 1500L

        @Volatile
        var instance: AutoClickService? = null
            private set

        /** 当前引擎实例是否有效（服务已连接）。 */
        fun live(): AutoClickService? = instance

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expected = ComponentName(context, AutoClickService::class.java)
                val raw = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: return false
                // v2.9.35：设置项可能是全格式 "pkg/pkg.Class" 或短格式 "pkg/.Class"
                // （ADB / 部分 OEM 写入），此前只按 flattenToString 全格式精确比较，
                // 短格式恒 false 误报"无障碍未启用"。用 unflattenFromString 归一比较。
                raw.split(':').any { entry ->
                    val cn = ComponentName.unflattenFromString(entry) ?: return@any false
                    cn.packageName.equals(expected.packageName, ignoreCase = true) &&
                        cn.className.equals(expected.className, ignoreCase = true)
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}

/** 屏幕帧信息。 */
data class Frame(val width: Int, val height: Int, val rotation: Int)

/** 节点 bounds → 中心点工具。 */
fun AccessibilityNodeInfo.centerInScreen(): Pair<Int, Int> {
    val r = Rect().also { getBoundsInScreen(it) }
    return (r.left + r.right) / 2 to (r.top + r.bottom) / 2
}

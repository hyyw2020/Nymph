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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.vision.ImageMask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
     * 无障碍全屏截图（API 30+）。最多重试 1 次，3s 超时。
     *
     * v2.2.0 回归修复：v2.0 曾把 Android 14+ 的 `takeScreenshotOfWindow`
     * 设为优先通道，真机（SDK 37）实测翻车——它返回的是**活动窗口自身的
     * 静态快照**：尺寸 1128×1378、带透明通道、多轮抓取内容完全不变（5 张
     * 失败现场截图 SHA-256 相同），而脚本的模板锚点是按 960×2100 全屏基准
     * 录制的。坐标系与内容都错配，NCC 恒为 0.00~0.24，一次运行 11 轮 32 次
     * 全失败。故回退为**全屏 takeScreenshot 唯一通道**，与模板录制基准一致。
     *
     * 单一通道还有个好处：帧缓存不会把某个窗口的冻结快照反复复用。
     *
     * 仍是无障碍截图：**不创建 MediaProjection、不占用系统录屏**。
     */
    suspend fun screenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return awaitShot { callback ->
            takeScreenshot(Display.DEFAULT_DISPLAY, Executor { it.run() }, callback)
        }
    }

    private suspend fun awaitShot(submit: (TakeScreenshotCallback) -> Unit): Bitmap? {
        var attempt = 0
        while (attempt < 2) {
            val done = CompletableDeferred<Bitmap?>()
            try {
                submit(object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        lastScreenshotError = 0
                        try {
                            val hardware = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            runCatching { wrapped?.recycle() }
                            hardware.close()
                            done.complete(software)
                        } catch (e: Exception) {
                            done.complete(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // 外传错误码：secure 窗口（黑帧）与普通失败要区分——
                        // 前者判能力矩阵 RESTRICTED 档，后者走熔断重试
                        lastScreenshotError = errorCode
                        done.complete(null)
                    }
                })
            } catch (e: Exception) {
                return null
            }
            val bmp = withTimeoutOrNull(3000L) { done.await() }
            if (bmp != null) return bmp
            attempt++
            if (attempt < 2) delay(400L)
        }
        return null
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
        @Volatile
        var instance: AutoClickService? = null
            private set

        /** 当前引擎实例是否有效（服务已连接）。 */
        fun live(): AutoClickService? = instance

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expected = ComponentName(context, AutoClickService::class.java).flattenToString()
                val raw = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: return false
                raw.split(':').any { it.equals(expected, ignoreCase = true) }
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

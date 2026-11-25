// SPDX-License-Identifier: MIT
package dev.nymph.auto.legacy.screenshot

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import dev.nymph.auto.core.log.RunLog
import java.util.concurrent.Executor

/**
 * 截图取证链的**唯一合法出口**（v3.0.0 / 迁移期 ADR-068）。
 *
 * ## 隔离契约（编译期可验证）
 *
 * 全工程只有本类允许真实调用 [takeScreenshot] / 读屏幕像素。规则：
 * - `legacy/screenshot/` 目录**外**的任何源码真实调用截图取屏 API → 门禁红；
 * - 本类的每个公共出口都标 [@FallbackPath]（降级可见）+ [@Deprecated]（计划移除）；
 * - 调用方必须显式标注 `@FallbackPath`，表示「我知道这是兼容降级，不是默认链」。
 *
 * ## 为什么不直接删
 *
 * 截图找图/找字是无 root、无 Shizuku 用户当前唯一的可视化落点来源。用户约束
 * 「旧链作为降级保留、不能一刀切删除导致无授权用户不可用」。因此这里不是删除，
 * 而是**物理隔离到 legacy 目录 + 显式降级标记**，等真值录制链成熟后整体移除。
 *
 * ## 不抢录屏（v2943 永久红线，保持）
 *
 * 仍走 `AccessibilityService.takeScreenshot()`，**不创建 MediaProjection、
 * 不占用系统录屏**；`canTakeScreenshot=true` 从默认路径断言降级为兼容模式专属。
 */
@FallbackPath(
    since = "v3.0.0",
    forRemoval = true,
    reason = "截图取证的单一出口；新链（真值录制/SimpleTap）禁止引用",
)
@Deprecated(
    message = "截图取证兼容降级路径，计划移除；新代码请走真值录制/SimpleTap",
    level = DeprecationLevel.WARNING,
)
object CompatFallback {

    /** 供 `AutoClickService` 委托：拿到底层截图提交函数。 */
    data class ShotRequest(
        val displayId: Int,
        val executor: Executor,
    )

    /**
     * 构造截图请求参数（不含真实调用，调用由持有 AccessibilityService 的
     * `AutoClickService` 完成——因为 takeScreenshot 是该服务的方法）。
     *
     * 这样真实 `takeScreenshot(...)` 调用点仍物理落在 legacy 目录内：
     * [submitShot] 接收 service 回调在此完成唯一调用。
     */
    @FallbackPath(reason = "唯一 takeScreenshot 调用点；降级出口")
    @JvmStatic
    fun submitShot(
        service: android.accessibilityservice.AccessibilityService,
        displayId: Int = Display.DEFAULT_DISPLAY,
        executor: Executor = Executor { it.run() },
        callback: android.accessibilityservice.AccessibilityService.TakeScreenshotCallback,
    ) {
        // 全工程唯一真实 takeScreenshot 调用点。
        service.takeScreenshot(displayId, executor, callback)
    }

    /**
     * 读屏幕尺寸的降级工具（找图换算是历史债务的一部分）。
     * 新链一律用注入时拿到的屏幕尺寸，不在这里读。
     */
    @FallbackPath(reason = "截图取证链路的屏幕尺寸读取，降级工具")
    @JvmStatic
    @Suppress("DEPRECATION")
    fun realDisplayMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    @FallbackPath(reason = "API 门槛判定，降级工具")
    @JvmStatic
    fun screenshotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.nymph.auto.core.editor.OverlayBounds

/**
 * **系统悬浮窗口边界尺寸的 Android 侧单一真源**（v2.9.19）。
 *
 * `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` 使窗口坐标空间成为显示屏
 * 全屏绝对坐标；边界尺寸必须取自 [WindowManager] 的窗口指标，不得取自
 * `resources.displayMetrics`（应用窗口可用区，受系统装饰区与显示切口影响）。
 *
 * API 30+ 使用 `currentWindowMetrics.bounds`，低版本回退
 * `defaultDisplay.getRealMetrics`，与坐标拾取器既有可用口径逐字一致。
 * 所有可位移悬浮窗口（运行球、运行控制面板、编辑面板、录制面板、坐标拾取器）
 * 统一经此取值，消除同一进程内多套坐标基准。
 */
object OverlayWindowMetrics {

    @Suppress("DEPRECATION")
    fun screenSize(wm: WindowManager): OverlayBounds.Size {
        val bounds: Rect = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return OverlayBounds.Size(
            width = maxOf(1, bounds.width()),
            height = maxOf(1, bounds.height()),
        )
    }

    fun screenSize(ctx: Context): OverlayBounds.Size =
        screenSize(ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
}

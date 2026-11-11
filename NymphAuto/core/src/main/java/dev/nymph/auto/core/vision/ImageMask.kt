// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

/**
 * 图像区域抑制（纯 JVM，可单测）。
 *
 * 背景：全屏无障碍 `takeScreenshot()` 会把本工具自己的悬浮球、控制面板、
 * 等待浮层一并截进去。这些高对比、不透明的自家 UI 覆盖在目标 App 之上，
 * 会污染 NCC 模板匹配（拉低分数或造成误命中），真机失败现场截图里能直接
 * 看到暂停球与「正在等待目标图片」浮层。
 *
 * v2.0 曾用 `takeScreenshotOfWindow` 规避，但真机（SDK 37）实测该 API 返回
 * 冻结的活动窗口静态快照，v2.2 已回退全屏截图。本类用「匹配前抑制自家浮窗
 * 区域」的方式解决：**不隐藏浮窗（零闪烁、不影响用户交互）、不改截图通道、
 * 不改存证图**，只作用于匹配用的图像副本。
 *
 * 抑制策略：把矩形内像素替换为矩形上方紧邻一行的真实像素（背景自然延伸，
 * 保留正常纹理方差，避免纯色区域让 NCC 分母退化）；贴顶时取下方紧邻一行。
 * 取不到背景（覆盖全高）则跳过该矩形，宁可不处理也不制造坏数据。
 */
object ImageMask {

    /** 百分比矩形（0~100），与脚本 Region 坐标系一致，避免依赖具体屏幕尺寸。 */
    data class RectPct(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    )

    /**
     * 原地抑制 [image] 中若干矩形区域，返回实际处理的矩形数。
     * 越界坐标自动裁剪；空矩形、空列表、非法图像一律安全返回 0。
     */
    fun suppress(image: ImageFinder.Image, rects: List<RectPct>): Int {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || rects.isEmpty()) return 0
        var done = 0
        for (r in rects) {
            val l = (r.left / 100.0 * w).toInt().coerceIn(0, w - 1)
            val t = (r.top / 100.0 * h).toInt().coerceIn(0, h - 1)
            val rr = (r.right / 100.0 * w).toInt().coerceIn(l + 1, w)
            val b = (r.bottom / 100.0 * h).toInt().coerceIn(t + 1, h)
            // 背景取样行：优先矩形上方一行，贴顶则用下方一行
            val srcY = when {
                t > 0 -> t - 1
                b < h -> b
                else -> continue // 覆盖全高，取不到背景，跳过
            }
            val src = srcY * w
            for (y in t until b) {
                val row = y * w
                for (x in l until rr) {
                    image.argb[row + x] = image.argb[src + x]
                }
            }
            done++
        }
        return done
    }
}

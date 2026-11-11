// SPDX-License-Identifier: MIT
package dev.nymph.auto.visual

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.visual.VisualConfig
import dev.nymph.auto.core.visual.VisualCue
import dev.nymph.auto.core.visual.VisualCueReducer
import dev.nymph.auto.core.vision.ImageMask

/**
 * 动作可视化的窗口层：只依赖 Context + WindowManager 的独立组件。
 *
 * 硬约束（绝不越界）：
 *  - 只用 TYPE_APPLICATION_OVERLAY；FLAG_NOT_FOCUSABLE / NOT_TOUCHABLE /
 *    LAYOUT_IN_SCREEN / LAYOUT_NO_LIMITS / HARDWARE_ACCELERATED；TRANSLUCENT；
 *  - 绝不设置 FLAG_SECURE，绝不引用任何 MediaProjection / VirtualDisplay /
 *    MediaCodec，不申请任何权限，不创建前台服务；
 *  - FLAG_NOT_TOUCHABLE：不拦截用户操作，代价是窗口收不到 MotionEvent——
 *    手势数据全部由 ScriptRunner 主动上报（本类不监听任何输入）；
 *  - 不做任何页面识别 / 安全窗口检测 / 白黑名单 / 特殊隐藏分支，
 *    所有界面同一套渲染、同一套暂停、同一套可见性规则。
 *
 * 生命周期：总开关关闭时不 addView；无活跃动画时 View=GONE 且注销
 * Choreographer 回调（彻底停帧，CPU 接近零）；有 cue 时按 vsync 推进。
 * 动画进度用 frameTimeNanos（与 cue.createdAtMs 同为 nanoTime 毫秒基准），
 * 因此掉帧时进度按真实时间走，动效速度不变。
 *
 * 找色保护：本类不改截图通道，只向 VisionPipeline 提供「当前活跃元素的
 * 合并包围盒」百分比矩形，供匹配副本 ImageMask.suppress() 抑制。
 */
class VisualizerLayer(
    private val ctx: Context,
    private val configProvider: () -> VisualConfig,
) : Choreographer.FrameCallback {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val reducer = VisualCueReducer(configProvider())
    private var view: VisualizerView? = null
    private var attached = false
    private var frameCallbackPosted = false
    private var enabled = false

    /** 最近一帧的合并包围盒（像素），供跨线程读取 activeBoundsPct。 */
    @Volatile
    private var lastBoundsPx: FloatArray? = null

    private val padPx = 44f * ctx.resources.displayMetrics.density // 外扩覆盖扩散环+辉光

    // ------------------------------------------------------------ 配置与生命周期

    /** 运行中切换配置：从关到开补 addView，从开到关立即清理。 */
    fun updateConfig(cfg: VisualConfig) {
        reducer.updateConfig(cfg)
        if (cfg.enabled && !enabled) {
            enabled = true
            ensureAttached()
        } else if (!cfg.enabled && enabled) {
            enabled = false
            detach()
        }
        view?.currentRestrained = cfg.restrained
    }

    /** 运行开始时调用：按当前配置决定是否挂载窗口。 */
    fun start() {
        val cfg = configProvider()
        reducer.clear()
        lastBoundsPx = null
        enabled = cfg.enabled
        if (enabled) ensureAttached()
        view?.currentRestrained = cfg.restrained
    }

    private fun ensureAttached() {
        if (attached) return
        if (!Settings.canDrawOverlays(ctx)) {
            // 没权限只关闭自身，绝不阻塞脚本执行
            RunLog.w("Visual", "缺少悬浮窗权限，动作可视化已静默关闭（脚本不受影响）")
            enabled = false
            return
        }
        val v = VisualizerView(ctx)
        v.visibility = View.GONE
        val lp = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        }
        val ok = runCatching { wm.addView(v, lp) }
            .onFailure { RunLog.w("Visual", "可视化窗口添加失败：${it.javaClass.simpleName}，已静默关闭") }
            .isSuccess
        if (!ok) { enabled = false; return }
        view = v
        attached = true
    }

    /** 运行结束 / 服务销毁：移除窗口、注销帧回调、清空状态。 */
    fun close() {
        enabled = false
        stopFrameLoop()
        reducer.clear()
        lastBoundsPx = null
        detach()
    }

    private fun detach() {
        stopFrameLoop()
        view?.let { v ->
            runCatching { if (v.isAttachedToWindow) wm.removeView(v) }
                .onFailure { RunLog.w("Visual", "移除可视化窗口失败：${it.javaClass.simpleName}") }
        }
        view = null
        attached = false
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ------------------------------------------------------------ cue 入队

    /** 由 ScriptRunner 在动作派发前调用（主线程）；异常只影响可视化自身。 */
    fun onCue(cue: VisualCue) {
        if (!enabled || !attached) return
        val cfg = configProvider()
        reducer.updateConfig(cfg)
        view?.currentRestrained = cfg.restrained
        if (!cfg.accepts(cue.kind)) return
        runCatching {
            reducer.add(cue)
            view?.visibility = View.VISIBLE
            startFrameLoop()
        }.onFailure { RunLog.w("Visual", "cue 入队失败：${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 帧循环

    private fun startFrameLoop() {
        if (frameCallbackPosted) return
        frameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopFrameLoop() {
        if (frameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(this)
            frameCallbackPosted = false
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameCallbackPosted = false
        if (!enabled || !attached) return
        val nowMs = frameTimeNanos / 1_000_000L
        val f = runCatching { reducer.frame(nowMs) }.getOrNull()
            ?: VisualCueReducer.VisualFrame(emptyList(), null)
        lastBoundsPx = f.boundsPx
        val v = view
        if (v == null) return
        v.currentRestrained = configProvider().restrained
        runCatching { v.submit(f) }
        if (f.isEmpty) {
            v.visibility = View.GONE // 无动画：停帧，不再 post 下一帧
        } else {
            startFrameLoop()
        }
    }

    // ------------------------------------------------------------ 找色抑制

    /**
     * 当前活跃元素的合并包围盒（百分比矩形），外扩 padPx 覆盖扩散环与辉光。
     * 无活跃动画返回 null（不产生任何抑制）。屏幕尺寸由调用方传入，
     * 避免本组件反向依赖 AutoClickService。
     */
    fun activeBoundsPct(screenW: Int, screenH: Int): ImageMask.RectPct? {
        if (!enabled) return null
        val b = lastBoundsPx ?: return null
        if (screenW <= 0 || screenH <= 0) return null
        val l = (b[0] - padPx).coerceAtLeast(0f)
        val t = (b[1] - padPx).coerceAtLeast(0f)
        val r = (b[2] + padPx).coerceAtMost(screenW.toFloat())
        val bo = (b[3] + padPx).coerceAtMost(screenH.toFloat())
        if (r <= l || bo <= t) return null
        return ImageMask.RectPct(
            100.0 * l / screenW, 100.0 * t / screenH,
            100.0 * r / screenW, 100.0 * bo / screenH,
        )
    }
}

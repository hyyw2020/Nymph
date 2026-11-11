// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.nymph.auto.core.vision.PickerGeometry
import dev.nymph.auto.engine.AutoClickService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一次点拾取的结果：屏幕绝对像素 + 该屏幕的宽高（用于换算百分比坐标）。 */
data class PickerResult(
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
) {
    /** 百分比坐标（0..100，全精度 Double，编辑器按需格式化）。 */
    val xPct: Double get() = if (widthPx > 0) 100.0 * xPx / widthPx else 0.0
    val yPct: Double get() = if (heightPx > 0) 100.0 * yPx / heightPx else 0.0
}

/**
 * 一次框选区域的结果（v2.7.0，v2.7.1 加 [expr]）：找图 ROI / 找色范围 / 模板截取都用它。
 * 坐标为屏幕绝对像素，已归一化（left<right、top<bottom）。
 */
data class PickerRegion(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    /** ROI 表达式（百分比四元组，Locale 安全）；运行时由 RoiExpr 优先解析。 */
    val expr: String = "",
) {
    val leftPct: Double get() = pct(leftPx, widthPx)
    val topPct: Double get() = pct(topPx, heightPx)
    val rightPct: Double get() = pct(rightPx, widthPx)
    val bottomPct: Double get() = pct(bottomPx, heightPx)

    /** 可直接填进 ROI 表达式/Region 的四元组文本（4 位小数百分比，Locale.US）。 */
    fun toSpec(): String = if (expr.isNotBlank()) expr else listOf(
        PickerGeometry.fmt4(leftPct), PickerGeometry.fmt4(topPct),
        PickerGeometry.fmt4(rightPct), PickerGeometry.fmt4(bottomPct),
    ).joinToString(",")

    private fun pct(v: Int, total: Int): Double = if (total > 0) 100.0 * v / total else 0.0
}

/**
 * 超高精度坐标拾取浮层（v2.7.0 **非模态穿透**重构；v2.7.1 修准星/会话 bug）。
 *
 * ## 与 v2.6.0 的本质区别（回应用户第一诉求）
 *
 * v2.6.0 是一个 `MATCH_PARENT` 且**可触摸**的全屏模态层：盖上之后底层 APP
 * 完全收不到触摸，用户没法「一边拾取坐标，一边操作指针外的任意区域」，
 * 底部两大颗按钮还经常正好挡住要拾取的位置。
 *
 * 本版拆成**两个独立 WindowManager 窗口**：
 *  1. **绘制层**：全屏，但 `FLAG_NOT_TOUCHABLE` —— 只画标尺/准星/读数/
 *     放大镜，**不消费任何触摸**；点击全部由系统分发给底层 APP。
 *     同时去掉全屏暗色蒙版，底层内容原样可见，可「边看边微调」。
 *  2. **控制手柄**：默认只有一个 30dp 圆点，可拖动、可点开极简工具条。
 *     窗口带 `FLAG_NOT_TOUCH_MODAL`，只消费落在自身区域的触摸。
 *
 * 由此「手柄之外 = 底层 APP 正常操作区，手柄之上 = 拾取控制区」，
 * 用户可以在目标 APP 里翻页、点按钮，同时手柄一直浮着，随时拖到新位置。
 *
 * ## v2.7.1 修的真 bug
 *
 * 1. **准星坐标跳变**：v2.7.0 把准星 `crossX/crossY` 从「手柄窗口中心」
 *    反算（`handleLp.x + width/2`），工具条展开成 196×156dp、或手柄拖到
 *    屏幕边缘被 coerce 时，中心会漂移，确认瞬间坐标与拖动时不一致。
 *    本版把准星抽成**独立像素真值**（[PickerSession.crossX] 等），拖动只
 *    走 [PickerGeometry.drag] 累加 + clamp，窗口 LayoutParams 只负责呈现。
 * 2. **会话内 MODE 切换丢结果**：v2.7.0 的 `show` 只按初始 mode 绑定一个
 *    回调，用户点「点/框」切模式再确认，结果因对应回调为空被静默丢弃。
 *    本版用**统一投递回调** [deliver]，两种结果都能被最初传入的任一
 *    回调消费（缺的那个自动做点↔区域中心转换），绝不空转。
 * 3. **幂等守卫吞新请求**：`show()` 里 `session != null` 直接 return，重复
 *    点/切模式不刷新 mode 与回调。本版改为「先 dismiss 旧会话再重建」。
 * 4. **框选矩形归一化 + Locale**：统一走 [PickerGeometry.normalizeRegion] /
 *    [PickerGeometry.regionExpr]，负向拖动、零面积、贴边都收敛成合法矩形，
 *    且百分比一律 `Locale.US`（de/fr 区不会输出逗号小数点导致回填失败）。
 *
 * ## 不变的契约
 *
 * - 零录屏权限、零 `FLAG_WATCH_OUTSIDE_TOUCH`、零外部触摸监听；
 *   仅 `TYPE_APPLICATION_OVERLAY`（合规扫描仍是 0）。
 * - 浮层是同进程窗口**不会**触发 Activity `ON_RESUME`，故回填走「回调 +
 *   单调序号」（[resultSeq] / [lastResult]），不依赖生命周期。
 * - 确认后立即回调并 [dismiss]；运行/录制启动前服务会强制 [dismiss]，
 *   避免全屏绘制层被 `takeScreenshot()` 截进去污染找图。
 * - 放大镜用的截图来自无障碍 `takeScreenshot()`（不申请 MediaProjection）。
 */
object CoordinatePicker {

    @Volatile
    var lastResult: PickerResult? = null
        private set

    @Volatile
    var lastRegion: PickerRegion? = null
        private set

    /** 每次确认自增，供编辑器判断「这次结果还没消费」。 */
    @Volatile
    var resultSeq: Int = 0
        private set

    /**
     * 统一投递回调：`(点结果?, 区域结果?) -> Unit`。两种结果都能被消费，
     * 与会话内当前 mode 解耦（修 MODE 切换丢结果）。
     */
    @Volatile
    private var deliver: ((PickerResult?, PickerRegion?) -> Unit)? = null

    @Volatile
    private var session: PickerSession? = null

    /**
     * v2.9.5：活跃会话登记表（防残留窗口）。
     *
     * 旧版只持有 [session] 一个引用：一旦某条路径在 `start()` 中途抛异常
     * （权限被撤销、窗口数量超限），已经 `addView` 成功的窗口就成了「无主窗口」——
     * 没有任何引用能把它移除，旧浮层便永久留在屏幕上，与新会话叠加，
     * 表现为多串坐标读数 / 多个准星互相打架。
     *
     * 这里登记全部会话：`show()` 前强制拆干净，残留无处可藏。
     */
    private val liveSessions = java.util.Collections.synchronizedList(mutableListOf<PickerSession>())

    val isShowing: Boolean get() = session != null

    /** 取走最近一次拾取结果（读后不清空）。 */
    val peek: PickerResult? get() = lastResult

    /**
     * 显示拾取浮层。无悬浮窗权限时静默失败返回 false（编辑器仍可手输坐标）。
     *
     * 已有会话会**先关闭再按新参数重建**（修 v2.7.0 幂等守卫吞请求）。
     * 回调签名与 v2.6.0/v2.7.0 调用点二进制/源码兼容。
     */
    fun show(
        context: Context,
        initialPct: Pair<Double, Double>? = null,
        mode: PickerMode = PickerMode.POINT,
        snap: SnapProvider? = null,
        onRegionPicked: ((PickerRegion) -> Unit)? = null,
        onPicked: ((PickerResult) -> Unit)? = null,
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        // v2.9.5：不管登记表里还剩几个会话，先全部拆掉再建新的。
        // 原来只拆 session 指向的那一个，其余残留窗口会永远留在屏幕上。
        dismissAll()
        this.deliver = buildDeliver(onPicked, onRegionPicked)
        val s = PickerSession(context.applicationContext, mode, snap)
        return try {
            s.start(initialPct)
            session = s
            liveSessions.add(s)
            true
        } catch (t: Throwable) {
            // v2.9.5：半成品窗口必须当场拆掉，否则成为无主残留窗口。
            runCatching { s.detach() }
            this.deliver = null
            false
        }
    }

    /** 关闭浮层（幂等；结果保留，回调清空避免持有已销毁的 Composable）。 */
    fun dismiss() {
        this.deliver = null
        dismissAll()
    }

    /** v2.9.5：拆掉全部会话（含登记表里的残留），幂等。 */
    private fun dismissAll() {
        session = null
        val list = synchronized(liveSessions) { liveSessions.toList().also { liveSessions.clear() } }
        list.forEach { runCatching { it.detach() } }
    }

    /** 由 [PickerSession] 在确认时调用：保证回调顺序与序号递增一致。 */
    internal fun deliverPoint(r: PickerResult) {
        lastResult = r
        resultSeq++
        val cb = this.deliver
        this.deliver = null
        dismiss()
        cb?.invoke(r, null)
    }

    internal fun deliverRegion(r: PickerRegion) {
        lastRegion = r
        resultSeq++
        val cb = this.deliver
        this.deliver = null
        dismiss()
        cb?.invoke(null, r)
    }

    /**
     * 把两种可选 lambda 合成统一回调：MODE 切换后若只有其中一个回调，
     * 自动做点↔区域中心的等价转换，确认结果绝不因回调缺失被丢弃。
     */
    private fun buildDeliver(
        onPicked: ((PickerResult) -> Unit)?,
        onRegionPicked: ((PickerRegion) -> Unit)?,
    ): ((PickerResult?, PickerRegion?) -> Unit)? {
        if (onPicked == null && onRegionPicked == null) return null
        return { p, r ->
            when {
                p != null && onPicked != null -> onPicked(p)
                p != null && onRegionPicked != null -> onRegionPicked(p.toSingleRegion())
                r != null && onRegionPicked != null -> onRegionPicked(r)
                r != null && onPicked != null -> onPicked(r.toCenterPoint())
            }
        }
    }

    private fun PickerResult.toSingleRegion(): PickerRegion = PickerRegion(
        xPx, yPx,
        min(widthPx, xPx + 1), min(heightPx, yPx + 1),
        widthPx, heightPx,
    )

    private fun PickerRegion.toCenterPoint(): PickerResult = PickerResult(
        (leftPx + rightPx) / 2, (topPx + bottomPx) / 2, widthPx, heightPx,
    )

    /**
     * 一次拾取会话：持有两个（框选时三个）窗口与状态。
     * 只用注入的 WindowManager 移除窗口（View.parent 是 ViewRootImpl，
     * 强转 WindowManager 永远失败 —— v2.6.0 曾踩过这个坑）。
     */
    private class PickerSession(
        private val ctx: Context,
        private val mode: PickerMode,
        private val snap: SnapProvider?,
    ) {
        private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val d = ctx.resources.displayMetrics.density
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val screenW: Int
        private val screenH: Int

        private val overlay = PickerOverlayView(ctx)
        private val overlayLp: WindowManager.LayoutParams
        private val handle = PickerHandleView(ctx)
        private val handleLp: WindowManager.LayoutParams
        private var second: PickerHandleView? = null
        private var secondLp: WindowManager.LayoutParams? = null

        // ---- 准星真值（屏幕绝对像素）：与窗口几何彻底解耦，v2.7.1 ----
        private var crossX = 0f
        private var crossY = 0f
        private var bX = 0f  // 框选第二角真值
        private var bY = 0f

        private var addedOverlay = false
        private var addedHandle = false
        private var addedSecond = false
        private var lastShotAt = 0L

        // ---- v2.9.5：抓取偏移（手指屏幕绝对坐标 − 目标真值），拖动全程不变 ----
        private var grabOffX = 0f
        private var grabOffY = 0f
        private var regionGrabOffX = 0f
        private var regionGrabOffY = 0f

        // ---- v2.9.5：按帧合并（触控采样率 > 刷新率时避免同帧多次改窗口）----
        private var pendingX = 0f
        private var pendingY = 0f
        private var pendingIsRegion = false
        private var pendingValid = false
        private var frameScheduled = false
        private val frameCallback = Choreographer.FrameCallback {
            frameScheduled = false
            commitPending()
        }

        init {
            val bounds = if (Build.VERSION.SDK_INT >= 30) {
                wm.currentWindowMetrics.bounds
            } else {
                @Suppress("DEPRECATION")
                android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
                    .let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
            }
            screenW = max(1, bounds.width())
            screenH = max(1, bounds.height())

            overlay.mode = mode
            overlay.screenW = screenW
            overlay.screenH = screenH
            // 默认准星在屏幕中心；start(initialPct) 可覆盖
            crossX = screenW / 2f
            crossY = screenH / 2f
            bX = PickerGeometry.clamp(crossX + 110f * d, screenW)
            bY = PickerGeometry.clamp(crossY + 110f * d, screenH)

            overlayLp = baseParams().apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
            val cs = PickerHandleView.collapsedSize(d)
            handleLp = baseParams().apply {
                width = cs
                height = cs
                gravity = Gravity.TOP or Gravity.START
            }
            handle.mode = mode
            if (mode == PickerMode.REGION) ensureSecond(addNow = false)
            positionParams()
        }

        private fun baseParams() = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        }

        fun start(initialPct: Pair<Double, Double>?) {
            // v2.9.5：绝对定位拖动（屏幕绝对坐标 + 抓取偏移），不再用
            // 「相对窗口增量」——后者会被手柄窗口自身的移动抵消，造成抽搐。
            handle.onGrab = { rawX, rawY ->
                grabOffX = PickerGeometry.grabOffset(rawX, crossX)
                grabOffY = PickerGeometry.grabOffset(rawY, crossY)
            }
            handle.onDragTo = { rawX, rawY -> moveCrossTo(rawX, rawY) }
            handle.onDragEnd = { flushPending() }
            handle.onAction = ::onAction
            handle.onExpandedChanged = { expanded ->
                if (expanded) {
                    handleLp.width = PickerHandleView.expandedW(d)
                    handleLp.height = PickerHandleView.expandedH(d)
                } else {
                    val cs = PickerHandleView.collapsedSize(d)
                    handleLp.width = cs; handleLp.height = cs
                }
                // 只改窗口尺寸，准星真值不变；重新以真值定位，绝不跳变
                placeHandles(); applyCross()
            }

            initialPct?.let { (xp, yp) ->
                crossX = PickerGeometry.px(xp, screenW)
                crossY = PickerGeometry.px(yp, screenH)
                bX = PickerGeometry.clamp(crossX + 110f * d, screenW)
                bY = PickerGeometry.clamp(crossY + 110f * d, screenH)
            }
            positionParams()

            wm.addView(overlay, overlayLp); addedOverlay = true
            wm.addView(handle, handleLp); addedHandle = true
            second?.let { s -> secondLp?.let { lp -> wm.addView(s, lp); addedSecond = true } }
            applyCross()
        }

        /** 纯计算：把各窗口 lp.x/y 设成「中心对准对应真值」并夹在屏幕内。 */
        private fun positionParams() {
            handleLp.x = (crossX.roundToInt() - handleLp.width / 2)
                .coerceIn(0, max(0, screenW - handleLp.width))
            handleLp.y = (crossY.roundToInt() - handleLp.height / 2)
                .coerceIn(0, max(0, screenH - handleLp.height))
            secondLp?.let { lp ->
                lp.x = (bX.roundToInt() - lp.width / 2)
                    .coerceIn(0, max(0, screenW - lp.width))
                lp.y = (bY.roundToInt() - lp.height / 2)
                    .coerceIn(0, max(0, screenH - lp.height))
            }
        }

        /** 真值变化后同步窗口位置（视图已 attach 才能调 updateViewLayout）。 */
        private fun placeHandles() {
            positionParams()
            if (addedHandle) runCatching { wm.updateViewLayout(handle, handleLp) }
            if (addedSecond) second?.let { v -> secondLp?.let { lp -> runCatching { wm.updateViewLayout(v, lp) } } }
        }

        /** 真值驱动绘制层；读数用 Locale.US 格式化。 */
        private fun applyCross() {
            overlay.crossX = crossX
            overlay.crossY = crossY
            overlay.regionA = floatArrayOf(crossX, crossY)
            overlay.regionB = floatArrayOf(bX, bY)
            handle.readout = "X ${crossX.roundToInt()}  Y ${crossY.roundToInt()} · " +
                "${PickerGeometry.fmt4(PickerGeometry.pct(crossX.roundToInt(), screenW))}% / " +
                "${PickerGeometry.fmt4(PickerGeometry.pct(crossY.roundToInt(), screenH))}%"
            overlay.invalidate()
            handle.invalidate()
            scheduleShot()
        }

        private fun onAction(a: HandleAction) {
            when (a) {
                HandleAction.CONFIRM -> confirm()
                HandleAction.CANCEL -> CoordinatePicker.dismiss()
                HandleAction.MODE -> {
                    val next = if (overlay.mode == PickerMode.POINT) PickerMode.REGION else PickerMode.POINT
                    setMode(next)
                }
                HandleAction.MAGNIFIER -> {
                    overlay.magnifierEnabled = !overlay.magnifierEnabled
                    handle.magnifierOn = overlay.magnifierEnabled
                    if (overlay.magnifierEnabled) scheduleShot(force = true)
                    overlay.invalidate(); handle.invalidate()
                }
                HandleAction.SNAP -> {
                    overlay.snapOn = !overlay.snapOn
                    handle.snapOn = overlay.snapOn
                    overlay.invalidate(); handle.invalidate()
                }
                HandleAction.RULER -> {
                    overlay.rulersEnabled = !overlay.rulersEnabled
                    handle.rulersOn = overlay.rulersEnabled
                    overlay.invalidate(); handle.invalidate()
                }
                HandleAction.UP -> nudge(0, -1)
                HandleAction.DOWN -> nudge(0, 1)
                HandleAction.LEFT -> nudge(-1, 0)
                HandleAction.RIGHT -> nudge(1, 0)
            }
        }

        private fun nudge(dx: Int, dy: Int) {
            val (nx, ny) = PickerGeometry.drag(crossX, crossY, dx, dy, screenW, screenH)
            crossX = nx; crossY = ny
            placeHandles(); applyCross()
        }

        private fun setMode(next: PickerMode) {
            overlay.mode = next
            handle.mode = next
            if (next == PickerMode.REGION) ensureSecond(addNow = true)
            else if (second != null) {
                if (addedSecond) second?.let { runCatching { wm.removeViewImmediate(it) } }
                second = null; secondLp = null; addedSecond = false
            }
            placeHandles(); applyCross()
            handle.invalidate()
        }

        /** 创建第二角手柄；addNow=true 时立即 addView（会话内切模式用）。 */
        private fun ensureSecond(addNow: Boolean) {
            if (second != null) return
            val cs = PickerHandleView.collapsedSize(d)
            val s = PickerHandleView(ctx).apply { allowExpand = false; mode = PickerMode.REGION }
            val lp = baseParams().apply {
                width = cs; height = cs
                gravity = Gravity.TOP or Gravity.START
            }
            s.onGrab = { rawX, rawY ->
                regionGrabOffX = PickerGeometry.grabOffset(rawX, bX)
                regionGrabOffY = PickerGeometry.grabOffset(rawY, bY)
            }
            s.onDragTo = { rawX, rawY -> moveRegionTo(rawX, rawY) }
            s.onDragEnd = { flushPending() }
            second = s; secondLp = lp
            positionParams()
            if (addNow) {
                runCatching { wm.addView(s, lp) }
                addedSecond = true
            }
        }

        private fun confirm() {
            var px = crossX.roundToInt()
            var py = crossY.roundToInt()
            if (overlay.snapOn) snap?.snap(px, py)?.let { px = it.first; py = it.second }
            px = px.coerceIn(0, screenW - 1)
            py = py.coerceIn(0, screenH - 1)
            if (overlay.mode == PickerMode.REGION) {
                val rect = PickerGeometry.normalizeRegion(
                    px.toFloat(), py.toFloat(), bX, bY, screenW, screenH,
                )
                CoordinatePicker.deliverRegion(
                    PickerRegion(
                        rect.l, rect.t, rect.r, rect.b, screenW, screenH,
                        PickerGeometry.regionExpr(rect, screenW, screenH),
                    ),
                )
            } else {
                CoordinatePicker.deliverPoint(PickerResult(px, py, screenW, screenH))
            }
        }

        /** 放大镜节流：150ms 最多一次截图，避免拖动时把无障碍截图通道打爆。 */
        private fun scheduleShot(force: Boolean = false) {
            if (!overlay.magnifierEnabled) return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastShotAt < 150L) return
            lastShotAt = now
            scope.launch {
                val bmp = runCatching {
                    AutoClickService.live()?.screenshotBitmap()
                }.getOrNull()
                overlay.magnifierBitmap = bmp
                overlay.invalidate()
            }
        }

        /** v2.9.5：绝对定位 + 1px 阈值 + 按帧合并的准星移动。 */
        private fun moveCrossTo(rawX: Float, rawY: Float) {
            val nx = PickerGeometry.placeFromGrab(rawX, grabOffX, screenW)
            val ny = PickerGeometry.placeFromGrab(rawY, grabOffY, screenH)
            if (!PickerGeometry.shouldCommit(nx - crossX, ny - crossY)) return
            pendingX = nx; pendingY = ny; pendingIsRegion = false; pendingValid = true
            scheduleFrame()
        }

        /** v2.9.5：框选第二角同款（绝对定位，不受窗口移动影响）。 */
        private fun moveRegionTo(rawX: Float, rawY: Float) {
            val nx = PickerGeometry.placeFromGrab(rawX, regionGrabOffX, screenW)
            val ny = PickerGeometry.placeFromGrab(rawY, regionGrabOffY, screenH)
            if (!PickerGeometry.shouldCommit(nx - bX, ny - bY)) return
            pendingX = nx; pendingY = ny; pendingIsRegion = true; pendingValid = true
            scheduleFrame()
        }

        /** 同一帧内只提交一次窗口更新：触控 120Hz vs 刷新 60Hz 时能砍掉一半更新。 */
        private fun scheduleFrame() {
            if (frameScheduled) return
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun commitPending() {
            if (!pendingValid) return
            pendingValid = false
            if (pendingIsRegion) { bX = pendingX; bY = pendingY } else { crossX = pendingX; crossY = pendingY }
            placeHandles(); applyCross()
        }

        /** 抬手前把最后一帧立即落地，避免丢掉最后的位移。 */
        private fun flushPending() {
            if (frameScheduled) {
                runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
                frameScheduled = false
            }
            commitPending()
        }

        fun detach() {
            scope.cancel()
            if (frameScheduled) {
                runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
                frameScheduled = false
            }
            pendingValid = false
            liveSessions.remove(this)
            if (addedSecond) second?.let { runCatching { wm.removeViewImmediate(it) } }
            if (addedHandle) runCatching { wm.removeViewImmediate(handle) }
            if (addedOverlay) runCatching { wm.removeViewImmediate(overlay) }
            addedSecond = false; addedHandle = false; addedOverlay = false
        }
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.service

import android.app.Notification
import android.app.NotificationManager
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
import android.view.WindowManager
import android.widget.Toast
import dev.nymph.auto.NymphApp
import dev.nymph.auto.R
import dev.nymph.auto.core.editor.ActionEditGuard
import dev.nymph.auto.core.editor.FloatingEditorLayout
import dev.nymph.auto.core.editor.OverlayWindowSpec
import dev.nymph.auto.core.editor.RoiApplier
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.store.ScriptRepository
import dev.nymph.auto.core.store.ScriptStore
import dev.nymph.auto.ui.CoordinatePicker
import dev.nymph.auto.ui.FloatingEditorPanel
import dev.nymph.auto.ui.PickerMode
import dev.nymph.auto.ui.PickerRegion
import dev.nymph.auto.ui.PickerResult
import java.io.File

/**
 * **系统级悬浮动作编辑器**（v2.8.0 引入，v2.8.1 抗抽搐 + 视觉重做）——
 * 让「编辑动作」脱离 NymphAuto 主界面，可以在其他 APP 界面之上显示、后台可呼出。
 *
 * ## v2.8.1 修「一直在抽搐」
 *
 * 录屏逐帧可见面板边缘跳动、内容忽多忽少，根因是窗口被高频反复重新测量：
 *  1. 拖动用局部坐标增量 `e.x-lastX` 累加、每个 MOVE 都 `updateViewLayout`
 *     → 改为 **屏幕绝对坐标 `rawX/rawY`** + 1px 阈值（v2.9.8 起同步直提交，
 *        见 [onDragAbsolute]：按帧合并的 `frameScheduled` 自锁会让球彻底拖不动）；
 *  2. `MaxHeightScrollView` 把 `UNSPECIFIED` 压成 `AT_MOST(0)` → core `measureHeight`
 *     按 mode 正确处理；
 *  3. 折叠/展开展来回切 `FLAG_NOT_FOCUSABLE` 引发软键盘弹出/收起循环
 *     → **flags 恒定 NOT_FOCUSABLE**，只在用户主动点输入框时临时清除、失焦恢复；
 *  4. 每次 MOVE 重读 `displayMetrics`（全屏游戏状态栏切换会让 heightPixels 跳）
 *     → 尺寸只缓存一次，配置变化时才刷新；
 *  5. 状态文本空/一行/两行让 `WRAP_CONTENT` 高度变 → 状态行固定两行高度。
 *
 * ## 差评根因与修法（用资料里的术语，但以本工程源码为准）
 *
 * v2.7.1 的 `ActionEditDialog` 是 Compose `AlertDialog`，画在 `MainActivity`
 * 的视图树里 —— **Activity 生命周期绑定的前台界面**，切到其他 APP 就随
 * `onStop` 消失，也没法在别的 APP 之上呼出。资料里说的「前台绑定悬浮窗」
 * 其实都比它强一层（那些好歹是 Dialog/PopupWindow 窗口）；本工程这个连独立
 * 窗口都不是。
 *
 * 本服务改用真正的 **System Overlay**：
 *  - [WindowManager.addView] 以 `TYPE_APPLICATION_OVERLAY` 添加面板（Android 8+，
 *    旧版回退 `TYPE_PHONE`），与 `FloatBall` / `CoordinatePicker` 同一机制；
 *  - 自身是 `specialUse` **前台服务**，负责维持窗口生命周期：切后台、切到
 *    任意第三方 APP 都持续存在；通知栏提供「呼出 / 收起 / 关闭」入口，
 *    即使本 Activity 已被销毁也能重新呼出；
 *  - 面板 `FLAG_NOT_TOUCH_MODAL` + `FLAG_NOT_FOCUSABLE`，只消费落在自身区域
 *    的触摸，**面板之外照常操作底层 APP**（延续 v2.7.0 非模态穿透原则）。
 *
 * ## 复用而非重写
 *
 * 脚本读写走 `ScriptStore` / `ScriptRepository`（修订+历史，与主界面同一真源）；
 * 动作保存走 `ActionEditGuard.apply`（`copy` 保真，不丢 children/listeners）；
 * 框选 ROI 走 `RoiApplier.apply`（唯一真源，命中数如实反馈）；
 * 坐标拾取复用 `CoordinatePicker`（同为系统 Overlay，非模态穿透）；
 * 窗口尺寸/折叠/clamp/表单解析走纯 JVM [FloatingEditorLayout]（有单测）。
 *
 * ## 合规
 *
 * 复用既有 `SYSTEM_ALERT_WINDOW`（8 项权限零新增）、零 MediaProjection /
 * VirtualDisplay / MediaCodec / FLAG_SECURE / RECORD_AUDIO、
 * 不订阅无障碍事件、不加 `FLAG_WATCH_OUTSIDE_TOUCH`。
 */
class FloatingEditorService : Service() {

    private lateinit var wm: WindowManager
    private var panel: FloatingEditorPanel? = null
    private var params: WindowManager.LayoutParams? = null

    /** 当前编辑的脚本（内存态；保存时写回 ScriptRepository）。 */
    private var script: Script? = null
    private var actions = mutableListOf<Action>()
    private var index = 0
    private var state = FloatingEditorLayout.State.EXPANDED
    private var winX = 0
    private var winY = 0

    /**
     * v2.8.1：屏幕尺寸只在这里缓存。v2.8.0 每次 MOVE 都重读
     * `resources.displayMetrics`，而沉浸式全屏游戏里状态栏 / 导航栏切换会让
     * `heightPixels` 在两个值之间跳，clamp 边界跟着跳 → 窗口 y 反复跳（抽搐）。
     */
    private var screenW = 0
    private var screenH = 0
    private var density = 0f

    /**
     * v2.9.8：删除 `pendingX/pendingY/frameScheduled` 与 `Choreographer`。
     * 拖动改为同步直提交，位置真源只有 [params] 的 x/y（见 [onDragAbsolute]）。
     */
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshMetrics()
        RunLog.i("FloatEditor", "悬浮编辑服务已创建")
    }

    private fun refreshMetrics() {
        val m = resources.displayMetrics
        screenW = m.widthPixels
        screenH = m.heightPixels
        density = m.density
    }

    /** 横竖屏 / 折叠屏变化时刷新缓存并重新 clamp，拖动过程本身绝不重读。 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics()
        panel?.updateMetrics(screenH, density)
        setCollapsed(state == FloatingEditorLayout.State.COLLAPSED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_TOGGLE -> togglePanel()
            ACTION_COLLAPSE -> setCollapsed(true)
            ACTION_SHOW -> {
                val name = intent.getStringExtra(EXTRA_SCRIPT_NAME)
                if (!name.isNullOrBlank()) load(name, intent.getIntExtra(EXTRA_ACTION_INDEX, 0))
                setCollapsed(false)
            }
        }
        refreshForeground()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ 脚本读写

    private fun load(name: String, at: Int) {
        val s = runCatching { store().load(name) }.getOrNull()
        if (s == null) {
            toast("脚本「$name」不存在或已损坏")
            return
        }
        script = s
        actions = s.actions.toMutableList()
        index = FloatingEditorLayout.step(at, 0, actions.size)
        RunLog.i("FloatEditor", "载入「$name」${actions.size} 步，从第 ${index + 1} 步开始")
        bindCurrent()
    }

    private fun store() = ScriptStore(File(filesDir, "scripts"))

    private fun repo() = ScriptRepository(store(), File(filesDir, "scripts/.history"))

    private fun bindCurrent() {
        val p = panel ?: return
        if (actions.isEmpty()) {
            p.bind(FloatingEditorLayout.Form(), "脚本为空 · 点「新增」添加动作", true)
            p.setStatus("脚本暂无动作，点「＋ 新增」创建第一个")
            return
        }
        val a = actions[index]
        p.bind(
            FloatingEditorLayout.formOf(a),
            "第 ${index + 1} / ${actions.size} 步 · ${typeName(a)}",
            ActionEditGuard.supportsSimpleEdit(a),
        )
    }

    private fun typeName(a: Action): String = when (a.type.name) {
        "TAP" -> "点击"; "LONG_PRESS" -> "长按"; "MULTI_TAP" -> "双击"
        "SWIPE" -> "滑动"; "CLICK_IMAGE" -> "找图"; "CLICK_TEXT" -> "找文字"
        "CLICK_COLOR" -> "找色"; "CLICK_AI" -> "AI"; "CLICK_NODE" -> "节点"
        "WAIT" -> "等待"; else -> a.type.name
    }

    /** 保存当前表单到脚本（保真 + ROI 应用 + 修订历史）。 */
    private fun save(form: FloatingEditorLayout.Form) {
        val base = script
        if (base == null) { toast("未载入脚本"); return }
        if (actions.isEmpty()) { toast("脚本为空，先点「＋ 新增」"); return }
        val original = actions[index]
        // 非简易类型：apply 原样返回，绝不静默改动作 / 丢字段
        val edited = ActionEditGuard.apply(original, FloatingEditorLayout.toPatch(form))
        if (!ActionEditGuard.supportsSimpleEdit(original)) {
            toast("「${typeName(original)}」不在简易编辑范围，未改动（请到源码视图编辑）")
            return
        }
        // 框选的 ROI 作用到脚本内所有真正读取 region 的位置（唯一真源）
        var next = base.copy(actions = actions.toList().toMutableList().also { it[index] = edited })
        val region = form.region
        var roiHits = 0
        if (region != null) {
            val (withRoi, hits) = RoiApplier.apply(next, region)
            next = withRoi
            roiHits = hits
        }
        val err = runCatching { repo().save(next) }.exceptionOrNull()
        if (err != null) {
            RunLog.e("FloatEditor", "保存失败：${err.javaClass.simpleName}: ${err.message}")
            toast("保存失败：${err.javaClass.simpleName}")
            return
        }
        // 同步内存态：script 的 actions 与 ROI 结果都要跟上，避免连续保存丢改动
        script = next
        actions = next.actions.toMutableList()
        val msg = buildString {
            append("✓ 已保存第 ${index + 1} 步")
            if (roiHits > 0) append(" · ROI 应用到 $roiHits 处")
            else if (region != null) append(" · ROI 未命中目标（脚本内无找图类动作）")
        }
        panel?.setStatus(msg)
        toast(msg)
        RunLog.i("FloatEditor", "保存「${next.name}」第 ${index + 1} 步（ROI 命中 $roiHits 处）")
        refreshForeground()
    }

    private fun move(delta: Int) {
        if (actions.isEmpty()) return
        index = FloatingEditorLayout.step(index, delta, actions.size)
        bindCurrent()
    }

    private fun addNew() {
        val fresh = Action()
        actions.add(fresh)
        index = FloatingEditorLayout.afterAppend(actions.size - 1)
        // 新增动作尚未落盘，先只更新内存与界面；点「保存」才写回
        panel?.bind(FloatingEditorLayout.formOf(fresh), "新动作（未保存）· 第 ${index + 1} 步", true)
        panel?.setStatus("新动作尚未保存，编辑完点「保存」写入脚本")
    }

    // ------------------------------------------------------------ 窗口

    private fun ensurePanel() {
        if (panel != null) return
        if (!Settings.canDrawOverlays(this)) {
            RunLog.e("FloatEditor", "悬浮窗权限缺失")
            toast("缺少悬浮窗权限，无法在其他 APP 上显示编辑器")
            stopSelf()
            return
        }
        val p = FloatingEditorPanel(this).apply {
            onSave = { form -> this@FloatingEditorService.save(form) }
            onPickPoint = { target -> pickPoint(target) }
            onPickRegion = { pickRegion() }
            onApplyRoi = { applyRoi() }
            onPrev = { move(-1) }
            onNext = { move(1) }
            onAdd = { addNew() }
            onClose = { shutdown() }
            onCollapse = { setCollapsed(true) }
            onToggle = { togglePanel() }
            onDragAbsolute = { sx, sy, drx, dry, rx, ry ->
                onDragAbsolute(sx, sy, drx, dry, rx, ry)
            }
            onRequestInputFocus = { setInputFocusable(true) }
            onReleaseInputFocus = { setInputFocusable(false) }
        }
        val lp = baseParams()
        runCatching { wm.addView(p, lp) }.onFailure {
            RunLog.e("FloatEditor", "添加悬浮窗失败：${it.javaClass.simpleName}: ${it.message}")
            toast("悬浮窗创建失败：${it.javaClass.simpleName}")
            stopSelf()
            return
        }
        panel = p
        params = lp
        winX = lp.x; winY = lp.y
        // v2.8.1：把初始窗口位置同步给面板，否则首次拖动起点是 (0,0) 会跳到左上角
        p.setWindowPos(winX, winY)
        p.updateMetrics(screenH, density)
        p.applyState(state)
        bindCurrent()
        RunLog.i("FloatEditor", "系统悬浮编辑器已显示（可跨 APP）")
    }

    private fun baseParams(): WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = overlayType()
        format = PixelFormat.TRANSLUCENT
        // v2.8.1：折叠 / 展开之间绝不来回切 flags（那会触发软键盘弹出→
        // relayout→失焦→收起的循环，即录屏里的「一直抽搐」）。
        // v2.9.0：flags 取值统一来自 core 的 OverlayWindowSpec 单一真源，
        // 不再在此处手写位掩码——任何新增 flag 都必须先过 isAllowed 单测。
        // 默认 = NOT_FOCUSABLE|NOT_TOUCH_MODAL；只有用户主动点输入框时才由
        // setInputFocusable(true) 临时切到 NOT_TOUCH_MODAL，失焦立即恢复。
        flags = OverlayWindowSpec.flagsFor(inputFocused = false)
        // 不设 softInputMode：SOFT_INPUT_ADJUST_* 在 API 36 已弃用，且 overlay 上
        // 各 ROM 行为不稳。面板内部 ScrollView 负责滚动。
        gravity = Gravity.TOP or Gravity.START
        width = FloatingEditorLayout.dp(FloatingEditorLayout.EXPANDED_W_DP, density)
        height = WindowManager.LayoutParams.WRAP_CONTENT
        // 默认靠右侧、屏高 1/5 处，避开左下导航区（尺寸用缓存，不实时读取）
        x = (screenW - width - dp(12)).coerceAtLeast(0)
        y = (screenH / 5)
    }

    private fun setCollapsed(collapsed: Boolean) {
        ensurePanel()
        val p = panel ?: return
        val next = if (collapsed) FloatingEditorLayout.State.COLLAPSED
        else FloatingEditorLayout.State.EXPANDED
        state = next
        p.applyState(next)
        val lp = params ?: return
        val size = FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, density)
        lp.width = if (collapsed) size
        else FloatingEditorLayout.dp(FloatingEditorLayout.EXPANDED_W_DP, density)
        lp.height = if (collapsed) size else WindowManager.LayoutParams.WRAP_CONTENT
        // v2.8.1：flags 保持 baseParams 里的恒定组合，这里绝不改（见其注释）。
        // 用面板实测高度而非「假设 62% 屏高」做 clamp，且尺寸全部来自缓存。
        val h = if (collapsed) size else p.measuredExpandedHeight()
        val (cx, cy) = FloatingEditorLayout.clampPosition(winX, winY, lp.width, h, screenW, screenH)
        winX = cx; winY = cy
        lp.x = cx; lp.y = cy
        runCatching { wm.updateViewLayout(p, lp) }
        p.setWindowPos(cx, cy)
        refreshForeground()
    }

    private fun togglePanel() = setCollapsed(state == FloatingEditorLayout.State.EXPANDED)

    /**
     * v2.9.8 **同步直提交**拖动（修「收起后悬浮球拖不动」）。
     *
     * ## 真因
     *
     * v2.8.1～v2.9.7 把窗口位移**唯一地**交给 `Choreographer.postFrameCallback`
     * 异步提交，并用 `frameScheduled` 布尔量做自锁，而该标志只在 VSYNC 回调
     * 开头复位。前台 Service 的 overlay 窗口静止 / 系统省电策略下，VSYNC 回调
     * 可能整段不触发，或回调内被 `shouldCommit` 丢弃后没有后续 MOVE 续投 ——
     * 于是 `frameScheduled` 永久为 true，之后每个 MOVE 都在入口 `return`，
     * 窗口再也不动。真机表现就是「悬浮球完全拖不动」；主悬浮球
     * `RunnerService.attachBallTouch` 一直同步 `updateViewLayout`，从未被投诉，
     * 正好反证这条异步链路是坏的。
     *
     * ## 现在
     *
     * `FloatingEditorLayout.commitDrag` 是**无状态纯函数**（算位置 + 1px 节流），
     * 返回值立即同步提交；位置真源只有 [params].x/y，不存在任何可卡住的锁。
     * 绝对坐标（不累加、不漂移）与 1px 节流都保留 —— v2.8.1 抗抽搐的真因是
     * 「局部坐标增量累加」，不是 update 频率，所以同步提交不会让抽搐回归。
     */
    private fun onDragAbsolute(
        startX: Int, startY: Int,
        downRawX: Float, downRawY: Float,
        rawX: Float, rawY: Float,
    ) {
        val lp = params ?: return
        val p = panel ?: return
        val h = if (state == FloatingEditorLayout.State.COLLAPSED)
            FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, density)
        else p.measuredExpandedHeight()
        // core 无状态决策：相对「当前已提交位置」变化 >=1px 才需要一次提交。
        val next = FloatingEditorLayout.commitDrag(
            startX, startY, downRawX, downRawY, rawX, rawY,
            lp.width, h, screenW, screenH, lp.x, lp.y,
        ) ?: return
        // v2.9.0 规格校验保留：x/y 可变，flags/type/format/width/height 逐字不变。
        val before = OverlayWindowSpec.Frame(
            lp.x, lp.y, lp.flags, lp.type, lp.format, lp.width, lp.height,
        )
        val after = OverlayWindowSpec.moved(before, next.first, next.second)
        if (!OverlayWindowSpec.isPositionOnlyUpdate(before, after)) return
        lp.x = after.x; lp.y = after.y
        winX = after.x; winY = after.y
        runCatching { wm.updateViewLayout(p, lp) }
        p.setWindowPos(after.x, after.y)
    }

    /**
     * 用户主动点输入框时临时允许聚焦；失焦立即恢复，杜绝软键盘弹出/收起循环。
     * v2.9.0：唯一合法的两种 flags 状态由 [OverlayWindowSpec] 定义并校验，
     * 这里不再手写位掩码；若规格判定非法则拒绝提交（防御性，宁可不切也不抽搐）。
     */
    private fun setInputFocusable(focusable: Boolean) {
        val p = panel ?: return
        val lp = params ?: return
        val want = OverlayWindowSpec.flagsFor(focusable)
        if (!OverlayWindowSpec.isLegalTransition(lp.flags, want)) return
        if (lp.flags == want) return
        lp.flags = want
        runCatching { wm.updateViewLayout(p, lp) }
    }

    // ------------------------------------------------------------ 拾取 / ROI

    private fun pickPoint(target: Int) {
        val form = panel?.collect() ?: return
        val initX = FloatingEditorLayout.num(if (target == 1) form.x2 else form.x, 50.0)
        val initY = FloatingEditorLayout.num(if (target == 1) form.y2 else form.y, 50.0)
        // 拾取期间把编辑面板收成小球，避免遮挡目标 APP / 被截进放大镜
        setCollapsed(true)
        val ok = CoordinatePicker.show(
            this,
            initialPct = initX to initY,
            onPicked = { r: PickerResult ->
                panel?.setPoint(target, r.xPct, r.yPct)
                setCollapsed(false)
            },
        )
        if (!ok) {
            panel?.setStatus("无悬浮窗权限，无法拾取（可手输坐标）")
            setCollapsed(false)
        }
    }

    private fun pickRegion() {
        setCollapsed(true)
        val ok = CoordinatePicker.show(
            this,
            mode = PickerMode.REGION,
            onRegionPicked = { r: PickerRegion ->
                val region = Region(r.leftPct, r.topPct, r.rightPct, r.bottomPct, r.expr)
                panel?.setRegion(region)
                setCollapsed(false)
            },
        )
        if (!ok) {
            panel?.setStatus("无悬浮窗权限，无法框选")
            setCollapsed(false)
        }
    }

    private fun applyRoi(): Int {
        val region = panel?.currentRegion()
        if (region == null) {
            panel?.setStatus("尚未框选区域")
            return 0
        }
        val base = script ?: return 0
        val (next, hits) = RoiApplier.apply(base, region)
        if (hits <= 0) {
            panel?.setStatus("脚本内暂无找图/找色目标（0 处）：请到源码视图添加")
            return 0
        }
        val err = runCatching { repo().save(next) }.exceptionOrNull()
        if (err != null) { panel?.setStatus("保存失败：${err.javaClass.simpleName}"); return 0 }
        script = next
        actions = next.actions.toMutableList()
        RunLog.i("FloatEditor", "ROI 已应用到脚本内 $hits 处")
        return hits
    }

    // ------------------------------------------------------------ 前台通知 / 生命周期

    private fun refreshForeground() {
        val open = PendingIntent.getActivity(
            this, 21,
            Intent(this, dev.nymph.auto.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, 22,
            Intent(this, FloatingEditorService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 23,
            Intent(this, FloatingEditorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = script?.name ?: "未载入脚本"
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NymphApp.CHANNEL_STATUS)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("悬浮动作编辑器")
            .setContentText("$name · 可浮在其他 APP 上，点通知呼出/收起")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null,
                if (state == FloatingEditorLayout.State.COLLAPSED) "展开" else "收起", toggle).build())
            .addAction(Notification.Action.Builder(null, "关闭", stop).build())
            .build()
        // v2.8.1：首次 startForeground，之后只 notify 更新通知。v2.8.0 折叠/展开、
        // 保存都重复 startForeground，与 updateViewLayout 叠加制造额外闪动。
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java).notify(3, n)
        } else {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(3, n)
            }
            foregroundStarted = true
        }
    }

    private fun shutdown() {
        CoordinatePicker.dismiss()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        params = null
        liveActive = false
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        stopSelf()
        RunLog.i("FloatEditor", "悬浮编辑器已关闭")
    }

    override fun onDestroy() {
        CoordinatePicker.dismiss()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        params = null
        liveActive = false
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_SHOW = "dev.nymph.auto.action.FLOAT_EDITOR_SHOW"
        const val ACTION_TOGGLE = "dev.nymph.auto.action.FLOAT_EDITOR_TOGGLE"
        const val ACTION_COLLAPSE = "dev.nymph.auto.action.FLOAT_EDITOR_COLLAPSE"
        const val ACTION_STOP = "dev.nymph.auto.action.FLOAT_EDITOR_STOP"
        const val EXTRA_SCRIPT_NAME = "scriptName"
        const val EXTRA_ACTION_INDEX = "actionIndex"

        /** 服务是否活跃（供主界面显示状态）。 */
        @Volatile
        var liveActive = false
            private set

        /**
         * 呼出系统悬浮编辑器。切后台 / 切到任何第三方 APP 后窗口依然存在，
         * 由前台服务保活；通知栏可随时呼出/收起/关闭。
         */
        fun show(context: Context, scriptName: String, actionIndex: Int = 0) {
            val i = Intent(context, FloatingEditorService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_SCRIPT_NAME, scriptName)
                putExtra(EXTRA_ACTION_INDEX, actionIndex)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
            liveActive = true
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingEditorService::class.java).setAction(ACTION_STOP))
            liveActive = false
        }
    }
}

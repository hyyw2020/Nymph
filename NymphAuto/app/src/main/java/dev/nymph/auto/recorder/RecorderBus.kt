// SPDX-License-Identifier: MIT
package dev.nymph.auto.recorder

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.recorder.RecordBounds
import dev.nymph.auto.core.recorder.RecordEvent
import dev.nymph.auto.core.recorder.RecordEventKind
import dev.nymph.auto.core.recorder.RecordGate
import dev.nymph.auto.core.recorder.RecordNode
import dev.nymph.auto.core.recorder.RecordabilityGuard
import dev.nymph.auto.engine.Frame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 无障碍事件 → 录制事件桥（app 层）。
 *
 * ## 性能契约：回调必须立即返回
 *
 * `AccessibilityService.onAccessibilityEvent` 运行在与系统窗口分发同一条调用链上。
 * 在这里做任何耗时操作（截图、OCR、磁盘 IO）都会**拖慢其他 APP 的界面响应**，
 * 那本身就是「打断其他 APP 的正常运行」。因此本桥只做：
 *  1. 读取事件自带的基本字段（无 IPC 或极轻量 IPC）
 *  2. 读一次 source 节点的静态属性
 *  3. 投递给当前活跃会话后立即返回
 *
 * 截图裁剪、OCR 增强、序列化全部交给会话的后台协程。
 *
 * ## 可观测性（v1.2.1）
 *
 * 早期版本事件链路完全静默：只要没有悬浮窗权限，用户既看不到步数、也看不到日志，
 * 无法区分「系统没发事件」和「事件被丢弃」。这里补上**严格节流**的诊断计数与日志：
 * 前 5 条逐条打印，之后每 20 条打印一次汇总，结束时由 [diagnostics] 给出完整统计。
 * 计数用原子量、日志用固定间隔，不在回调里做任何可能阻塞的操作。
 *
 * ## 权限契约
 * 只读被动语义事件；不消费、不拦截、不重排触摸；零 MediaProjection。
 * 不启用触摸浏览，不订阅 `TYPE_TOUCH_INTERACTION_*`，不使用任何覆盖层或触摸拦截。
 */
object RecorderBus {

    /** 当前活跃会话的事件接收器；null = 未在录制，此时 AutoClickService 零开销直接返回。 */
    @Volatile
    var sink: ((RecordEvent) -> Unit)? = null
        private set

    /** 录制参考屏幕坐标系，由会话启动时设置。 */
    @Volatile
    var frame: Frame? = null
        private set

    /**
     * v2.7.0：本应用包名。来自本包的无障碍事件（点"开始录制"、点控制浮窗、
     * 在编辑器里翻页……）一律丢弃——「一个点击器里能有什么自己对自己的
     * 自动化」，这些事件录进脚本就是必然失败的首步。
     */
    /** 策略下沉到纯 JVM [RecordGate]，逻辑由 core 单测覆盖，不靠真机反馈。 */
    @Volatile
    private var gate: RecordGate = RecordGate()

    val selfPackage: String get() = gate.selfPackage
    val armed: Boolean get() = gate.armed

    /** 待命→录制的状态变化（主线程回调，供通知/浮窗刷新）。 */
    @Volatile
    var onArmedChanged: ((Boolean) -> Unit)? = null

    @Volatile
    private var startedAtMs: Long = 0L

    @Volatile
    private var seq: Long = 0L

    /** 上一次滚动绝对位置，用于推算增量（TYPE_VIEW_SCROLLED 只给绝对位置）。 */
    @Volatile
    private var lastScrollX = 0

    @Volatile
    private var lastScrollY = 0

    // ---- 诊断计数（原子量，回调里只做 increment，不做格式化/IO）----
    private val received = AtomicLong()
    private val converted = AtomicLong()
    private val receivedByType = ConcurrentHashMap<String, AtomicLong>()
    private val droppedByType = ConcurrentHashMap<String, AtomicLong>()

    /**
     * v2.9.2：录制可行性信号计数。
     * - [interactionEvents]：可编译成动作的交互事件（点击 / 长按 / 滚动 / 输入 / 按键）。
     * - [windowEvents]：窗口状态 / 层级变化 + 焦点噪声，能证明「服务在收事件」，
     *   但不携带任何可回放语义。
     * 两者由 [signal] 交给 core 的 [RecordabilityGuard] 判定，app 层不做策略判断。
     */
    private val interactionEvents = AtomicLong()
    private val windowEvents = AtomicLong()

    /**
     * [selfPkg] 非空时启用「过滤本应用事件 + 待命」双保险。
     * 老调用点不传则完全等同 v2.6.0。
     */
    fun start(frame: Frame?, selfPkg: String = "") {
        this.frame = frame
        gate = RecordGate(selfPkg)
        startedAtMs = SystemClock.elapsedRealtime()
        seq = 0
        lastScrollX = 0
        lastScrollY = 0
        received.set(0)
        converted.set(0)
        receivedByType.clear()
        droppedByType.clear()
        interactionEvents.set(0)
        windowEvents.set(0)
    }

    fun attach(sink: (RecordEvent) -> Unit) {
        this.sink = sink
    }

    /** 只清活跃状态；诊断计数保留到 finish 打印完为止，由下次 start() 重置。 */
    fun stop() {
        sink = null
        frame = null
        gate = RecordGate()
        onArmedChanged = null
    }

    val active: Boolean get() = sink != null

    /** 由 AutoClickService.onAccessibilityEvent 调用；保证不抛异常、不阻塞。 */
    fun onEvent(event: AccessibilityEvent?) {
        val receiver = sink ?: return
        if (event == null) return

        // v2.7.0：自扰过滤 + 待命，策略见 RecordGate（纯 JVM 单测覆盖）。
        // 解除待命的那个事件本身要保留（通常是 WINDOW_CHANGED，正好供
        // RecordCompiler 写入 startPackage；也可能是目标 APP 的第一次点击）。
        val pkg = event.packageName?.toString().orEmpty()
        when (gate.accept(pkg)) {
            RecordGate.Decision.DROP_SELF, RecordGate.Decision.DROP_WAITING -> return
            RecordGate.Decision.ARMED -> runCatching { onArmedChanged?.invoke(true) }
            RecordGate.Decision.ACCEPT -> Unit
        }

        val typeName = eventTypeName(event.eventType)
        val n = noteIncoming(typeName)

        val e = runCatching { convert(event) }.getOrNull()
        if (e == null) {
            droppedByType.getOrPut(typeName) { AtomicLong() }.incrementAndGet()
            if (n <= 5) RunLog.w("Record", "事件 $typeName 未映射到录制事件，已丢弃")
            return
        }
        converted.incrementAndGet()
        // v2.9.2：只做原子自增，不引入任何耗时操作，保持回调立即返回的契约。
        when (e.kind) {
            RecordEventKind.CLICK, RecordEventKind.LONG_CLICK, RecordEventKind.SCROLL,
            RecordEventKind.TEXT_INPUT, RecordEventKind.KEY -> interactionEvents.incrementAndGet()
            RecordEventKind.WINDOW_CHANGED, RecordEventKind.FOCUS -> windowEvents.incrementAndGet()
        }
        runCatching { receiver(e) }
    }

    /**
     * v2.9.2：采集当前录制可行性信号，交给 core 的 [RecordabilityGuard] 判定。
     * 必须在 [stop]（会重置 gate 与 armed）之前调用，否则丢失「是否已进入目标 APP」。
     */
    fun signal(): RecordabilityGuard.Signal = RecordabilityGuard.Signal(
        armed = gate.armed,
        interactionEvents = interactionEvents.get().saturatedInt(),
        windowEvents = windowEvents.get().saturatedInt(),
        totalEvents = received.get().saturatedInt(),
        elapsedMs = if (startedAtMs > 0L) SystemClock.elapsedRealtime() - startedAtMs else 0L,
    )

    /**
     * v2.9.4：AtomicLong 直接 toInt() 在超过 Int.MAX_VALUE 后翻转成负数，
     * 会让 RecordabilityGuard 把「事件极多」误判成「零事件 / 自绘界面」并误报。
     * 饱和到 Int.MAX_VALUE，正常录制计数完全不变。
     */
    private fun Long.saturatedInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** 计数 + 节流日志：前 5 条逐条可见，之后每 20 条一次汇总，避免刷屏拖慢回调。 */
    private fun noteIncoming(typeName: String): Long {
        val n = received.incrementAndGet()
        receivedByType.getOrPut(typeName) { AtomicLong() }.incrementAndGet()
        if (n <= 5) {
            RunLog.i("Record", "收到无障碍事件 #$n type=$typeName")
        } else if (n % 20L == 0L) {
            RunLog.i("Record", "已收到 $n 个无障碍事件（转换 ${converted.get()}）")
        }
        return n
    }

    /** 供录制结束打印，回答「系统到底发没发事件、发了哪些、丢了多少」。 */
    fun diagnostics(): String = buildString {
        val drop = droppedByType.values.sumOf { it.get() }
        append("原始事件 ${received.get()} / 转换 ${converted.get()} / 未映射丢弃 $drop")
        val selfDropped = gate.droppedSelfCount()
        if (selfPackage.isNotEmpty() || selfDropped > 0L) {
            append(" / 本应用自扰过滤 $selfDropped")
        }
        if (receivedByType.isNotEmpty()) {
            append("；按类型 ")
            append(receivedByType.entries.sortedByDescending { it.value.get() }
                .joinToString(" ") { "${it.key}=${it.value.get()}" })
        }
        if (drop > 0) {
            append("；未映射明细 ")
            append(droppedByType.entries.joinToString(" ") { "${it.key}=${it.value.get()}" })
        }
        append("；交互事件 ${interactionEvents.get()} / 窗口事件 ${windowEvents.get()}")
        // v2.9.2：空录制不再只给一句「请确认无障碍服务」，而是把 core 判定的
        // 根因 + 可操作替代路径一起写进日志页（必达通道之一）。
        val v = RecordabilityGuard.warningVerdict(signal())
        if (v != RecordabilityGuard.Verdict.RECORDABLE) {
            append("。⚠ ").append(RecordabilityGuard.reason(v))
            val alt = RecordabilityGuard.alternative(v)
            if (alt.isNotBlank()) append(" ").append(alt)
        }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "ANNOUNCEMENT"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
        else -> "TYPE_$type"
    }

    private fun convert(event: AccessibilityEvent): RecordEvent? {
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> RecordEventKind.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> RecordEventKind.LONG_CLICK
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> RecordEventKind.SCROLL
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> RecordEventKind.TEXT_INPUT
            // 窗口状态变化与窗口列表变化都用于包守卫；后者在切换 APP 时最稳定，
            // v1.2.0 曾订阅 XML 却漏了映射，导致整类事件被静默丢弃。
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> RecordEventKind.WINDOW_CHANGED
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> RecordEventKind.FOCUS
            // typeViewSelected 视作点击（列表项选择）
            AccessibilityEvent.TYPE_VIEW_SELECTED -> RecordEventKind.CLICK
            else -> return null
        }

        val pkg = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        val node = runCatching { readNode(event.source, pkg, className) }.getOrNull()

        val bounds = node?.bounds
        val x = bounds?.centerX ?: 0
        val y = bounds?.centerY ?: 0

        val text = when (kind) {
            RecordEventKind.TEXT_INPUT -> event.text?.joinToString("") { it.toString() }.orEmpty()
            else -> ""
        }

        var dx = 0
        var dy = 0
        if (kind == RecordEventKind.SCROLL) {
            val sx = runCatching { event.scrollX }.getOrDefault(0)
            val sy = runCatching { event.scrollY }.getOrDefault(0)
            // API 28+ 的 scrollDeltaX/Y 更直接；低版本用绝对位置差
            val deltaX = if (android.os.Build.VERSION.SDK_INT >= 28) {
                runCatching { event.scrollDeltaX }.getOrDefault(sx - lastScrollX)
            } else sx - lastScrollX
            val deltaY = if (android.os.Build.VERSION.SDK_INT >= 28) {
                runCatching { event.scrollDeltaY }.getOrDefault(sy - lastScrollY)
            } else sy - lastScrollY
            dx = deltaX
            dy = deltaY
            lastScrollX = sx
            lastScrollY = sy
        }

        val f = frame
        val at = SystemClock.elapsedRealtime() - startedAtMs
        val s = ++seq
        return RecordEvent(
            atMs = at,
            seq = s,
            kind = kind,
            node = node,
            x = x,
            y = y,
            dx = dx,
            dy = dy,
            text = text,
            activityName = if (kind == RecordEventKind.WINDOW_CHANGED) className else "",
            screenW = f?.width ?: 0,
            screenH = f?.height ?: 0,
            rotation = f?.rotation ?: 0,
        )
    }

    private fun readNode(source: AccessibilityNodeInfo?, pkg: String, fallbackClass: String): RecordNode {
        if (source == null) return RecordNode(packageName = pkg, className = fallbackClass)
        val r = android.graphics.Rect()
        runCatching { source.getBoundsInScreen(r) }
        return RecordNode(
            packageName = source.packageName?.toString().orEmpty().ifBlank { pkg },
            className = source.className?.toString().orEmpty().ifBlank { fallbackClass },
            viewId = runCatching { source.viewIdResourceName }.getOrNull().orEmpty(),
            text = runCatching { source.text?.toString() }.getOrNull().orEmpty(),
            contentDesc = runCatching { source.contentDescription?.toString() }.getOrNull().orEmpty(),
            bounds = RecordBounds(r.left, r.top, r.right, r.bottom),
            clickable = runCatching { source.isClickable }.getOrDefault(false),
            scrollable = runCatching { source.isScrollable }.getOrDefault(false),
            editable = runCatching { source.isEditable }.getOrDefault(false),
        )
    }
}

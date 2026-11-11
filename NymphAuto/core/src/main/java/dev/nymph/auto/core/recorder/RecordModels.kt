// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import kotlinx.serialization.Serializable

/**
 * 录制事件的归一化模型（core 纯 JVM，无 Android 依赖）。
 *
 * ## 数据来源契约（硬性约束：不与系统录屏抢权限）
 *
 * 本模型中的一切屏幕信息，**只能**来自 Android 无障碍服务的被动能力：
 *  - 节点信息 → `AccessibilityNodeInfo`（`canRetrieveWindowContent`）
 *  - 模板图 / OCR 输入 → `AccessibilityService.takeScreenshot()`（API 30+）
 *  - 交互事件 → `AccessibilityEvent` 的 `TYPE_VIEW_CLICKED` / `TYPE_VIEW_SCROLLED`
 *    等**被动语义事件**（系统广播，服务只观察、不消费、不拦截触摸）
 *
 * **全链路不含 MediaProjection、不含 `FLAG_WATCH_OUTSIDE_TOUCH` 覆盖层、
 * 不含 `flagRequestTouchExplorationMode`**。后者尤其重要：官方文档与实测均表明
 * 触摸浏览模式会把用户的「单击」重解释为「先聚焦再双击」，等于改变/打断
 * 其他 APP 的正常交互语义——这与「录制不打断其他 APP」的约束直接冲突，故禁用。
 *
 * 录制产物为明文 JSON（见 [RecordCodec]），不加密、不混淆，可逐行审计。
 */

/** 被动语义事件种类。刻意不含「原始触摸坐标流」——那需要拦截触摸，被禁止。 */
@Serializable
enum class RecordEventKind {
    /** TYPE_VIEW_CLICKED */
    CLICK,

    /** TYPE_VIEW_LONG_CLICKED */
    LONG_CLICK,

    /** TYPE_VIEW_SCROLLED（含 fromIndex/toIndex 推算的位移） */
    SCROLL,

    /** TYPE_VIEW_TEXT_CHANGED（输入法提交后的文本） */
    TEXT_INPUT,

    /** TYPE_WINDOW_STATE_CHANGED：页面/窗口切换，用于包守卫与等待推断 */
    WINDOW_CHANGED,

    /** 物理/全局按键（返回、Home 等） */
    KEY,

    /** TYPE_VIEW_FOCUSED：通常不作为独立动作，仅用于合并与锚点增强 */
    FOCUS,
}

/** 节点矩形（屏幕绝对像素，录制参考坐标系）。 */
@Serializable
data class RecordBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val valid: Boolean get() = width > 0 && height > 0
}

/** 事件发生时的目标节点快照。 */
@Serializable
data class RecordNode(
    val packageName: String = "",
    val className: String = "",
    /** `AccessibilityNodeInfo.viewIdResourceName`，如 `com.x:id/btn_ok` */
    val viewId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val bounds: RecordBounds = RecordBounds(),
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
)

/**
 * 一条录制事件。
 *
 * @param atMs 相对录制开始的单调毫秒（用 `SystemClock.elapsedRealtime()` 差值，
 *   不用 wall clock，避免用户改系统时间破坏时序）
 * @param screenW/screenH/rotation 录制时屏幕参考坐标系，写入脚本的
 *   `refWidth/refHeight/refRotation`，回放时按比例映射，解决换机错位
 * @param templateB64 **无障碍截图**裁剪出的小模板（base64 PNG），仅当有节点
 *   且裁剪成功时填充；来源为 `takeScreenshot()`，与 MediaProjection 无关
 * @param ocrText 离线 OCR 在该事件锚点附近识别到的文字（ONNX Runtime 通道），
 *   用于「画布文字/自绘界面」这类无障碍节点拿不到文本的锚点补全
 */
@Serializable
data class RecordEvent(
    val atMs: Long = 0,
    /** 采集侧递增序号：异步截图/OCR 增强结果按此回填，不受时间乱序影响 */
    val seq: Long = 0,
    val kind: RecordEventKind = RecordEventKind.CLICK,
    val node: RecordNode? = null,
    val x: Int = 0,
    val y: Int = 0,
    /** 滚动终点像素（采集侧能拿到时填，编译器优先采用） */
    val x2: Int = 0,
    val y2: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val text: String = "",
    val keyName: String = "",
    val activityName: String = "",
    val screenW: Int = 0,
    val screenH: Int = 0,
    val rotation: Int = 0,
    val templateB64: String = "",
    val ocrText: String = "",
    /**
     * 该事件代表连续点击的次数（编译器把同一锚点的快速连点聚合成一条，
     * 而不是丢弃）。1 = 普通单击。仅 CLICK/LONG_CLICK 有意义。
     */
    val repeat: Int = 1,
) {
    /** 本事件是否携带可用的屏幕坐标系。 */
    val hasScreen: Boolean get() = screenW > 0 && screenH > 0
}

/** 锚点种类，按「跨设备稳定性」从强到弱排序。 */
@Serializable
enum class AnchorKind {
    /** viewIdResourceName：最稳定，换机/换分辨率不变 */
    NODE_ID,

    /** 无障碍节点文本 / contentDescription */
    NODE_TEXT,

    /** 离线 OCR 识别到的画布文字 */
    OCR_TEXT,

    /** 无障碍截图裁剪的图像模板（NCC 匹配） */
    TEMPLATE,

    /** 纯百分比坐标：最脆弱，仅作最后降级 */
    COORD,
}

/**
 * 一个候选锚点。
 * @param stability 稳定性分值（0-100，越高越不易因换机/改版失效）
 */
@Serializable
data class Anchor(
    val kind: AnchorKind,
    val stability: Int,
    val summary: String,
)

/** 编译期选项。全部有默认值，调用方可按需收紧。 */
@Serializable
data class RecordOptions(
    /** 同类型、同锚点、间隔小于此值的事件视为同一次交互（无障碍事件常成对上报） */
    val dedupeWindowMs: Long = 320,
    /**
     * 「系统对同一次点击的重复上报」窗口：小于此值才当作噪声去重。
     * 旧版把整个 320ms 窗口内的重复点击都丢掉，用户有意的连点（如连点赞）
     * 会被吞成一次——这是录制丢步的真实来源。
     */
    val duplicateReportWindowMs: Long = 130,
    /**
     * 「用户有意连点」聚合窗口：同锚点、间隔在 duplicateReportWindowMs..此值
     * 之间的连续点击合并为一个动作 + times，保留点击次数。
     */
    val tapMergeWindowMs: Long = 600,
    /** 两次点击中心距离小于此像素视为同一目标 */
    val dedupeRadiusPx: Int = 28,
    /** 连续同向滚动在此窗口内合并为一个 SWIPE */
    val mergeScrollWindowMs: Long = 900,
    /**
     * v2.5.0：无障碍 `TYPE_VIEW_SCROLLED` 的 scrollDelta 是「列表内容滚了多少」，
     * 不是手指位移；多数 ROM 直接返回 0，旧版据此生成起点=终点的**零长度 SWIPE**，
     * 回放时退化成点击/长按，误触打开内容（真机录屏中复现）。
     * 因此幅度一律改用受控的屏幕百分比，方向只取 delta 的符号。
     */
    val swipeSpanPct: Double = 34.0,
    /** 手势起终点离屏幕边缘的最小百分比，避免贴边触发系统返回/多任务手势。 */
    val swipeSafeMarginPct: Double = 14.0,
    /** 快速翻页的单次时长（毫秒）；旧版固定 400ms 会把 12s 操作拖成 19s。 */
    val swipeDurationMs: Long = 220L,
    /** 连续翻页之间的间隔（毫秒），避免 6 次手势挤在同一帧。 */
    val swipeGapMs: Long = 140L,
    /** 翻页动作前的等待上限（毫秒）：连续翻页不该继承 30s 的停顿上限。 */
    val swipeWaitCapMs: Long = 500L,
    /** 一组连续同向滚动最多折叠成几次翻页，超出则新开一组，避免次数无限累积。 */
    val maxSwipeTimes: Int = 6,
    /** 相邻事件间隔超过此值，给下一个动作设置 waitMs */
    val minWaitMs: Long = 300,
    val maxWaitMs: Long = 30_000,
    /** 目标应用包名变化时插入 OPEN_APP 守卫 */
    val insertAppGuard: Boolean = true,
    /** 允许弱锚点降级到坐标（false = 宁可不录也不产生脆弱坐标动作） */
    val allowCoordFallback: Boolean = true,
    /** 模板图裁剪的最小边长（像素），过小不作锚点 */
    val templateMinPx: Int = 8,
    /** 录制器自身包名：其控制面板上的点击绝不能录进脚本 */
    val selfPackage: String = "dev.nymph.auto",
    /** 单次录制最大事件数（防内存无界增长） */
    val maxEvents: Int = 4000,
)

/** 编译统计，用于录制结束后的浮窗摘要与日志（明文可审计）。 */
@Serializable
data class RecordReport(
    val rawEvents: Int = 0,
    val keptEvents: Int = 0,
    val duplicatesMerged: Int = 0,
    val scrollsMerged: Int = 0,
    /** 连续点击被聚合为「多击」的次数（保留点击次数，不丢步）。 */
    val tapsMerged: Int = 0,
    val selfEventsDropped: Int = 0,
    /** 系统 UI 覆盖层（statusbar/navbar/recents）交互事件被剔除的条数。 */
    val systemUiEventsDropped: Int = 0,
    /** 无任何有效锚点、且坐标退化为 (0,0) 的点击被跳过的条数。 */
    val invalidCoordDropped: Int = 0,
    /** 窗口层级变化事件被丢弃的条数（不生成重复的 OPEN_APP）。 */
    val windowEventsDropped: Int = 0,
    val appGuardsInserted: Int = 0,
    val waitsInserted: Int = 0,
    val actions: Int = 0,
    val anchorCounts: Map<AnchorKind, Int> = emptyMap(),
    val warnings: List<String> = emptyList(),
) {
    /** 一行人类可读摘要。 */
    fun oneLine(): String = buildString {
        append("原始 $rawEvents 事件 → $actions 个动作")
        if (duplicatesMerged > 0) append("，合并重复 $duplicatesMerged")
        if (tapsMerged > 0) append("，连点聚合 $tapsMerged")
        if (scrollsMerged > 0) append("，合并滚动 $scrollsMerged")
        if (appGuardsInserted > 0) append("，包守卫 $appGuardsInserted")
        if (waitsInserted > 0) append("，等待 $waitsInserted")
        if (selfEventsDropped > 0) append("，剔除自身 $selfEventsDropped")
        if (systemUiEventsDropped > 0) append("，剔除系统UI $systemUiEventsDropped")
        if (invalidCoordDropped > 0) append("，跳过无效坐标 $invalidCoordDropped")
        if (windowEventsDropped > 0) append("，忽略窗口事件 $windowEventsDropped")
    }
}

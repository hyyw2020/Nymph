// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.Region
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 系统级悬浮动作编辑器的**纯 JVM 布局 / 状态 / 表单逻辑**（v2.8.0～v2.8.1）。
 *
 * ## 为什么把它放进 core
 *
 * v2.6～v2.7.1 的「编辑动作」是 Compose `AlertDialog`，画在 `MainActivity`
 * 的视图树里——它是 **Activity 生命周期绑定的前台界面**，切到其他 APP 就随
 * `onStop` 一起不可见，也没法在其他 APP 之上呼出（用户差评点）。本版把编辑器
 * 搬到 `TYPE_APPLICATION_OVERLAY` 系统悬浮窗 + 前台服务（见 app 层
 * `FloatingEditorService`），而窗口尺寸、折叠态、贴边 clamp、拖动、表单解析
 * 这些**确定性逻辑**必须与 Android 解耦，才能在没有真机的环境里用 JVM 单测
 * 兜底，不靠运行时猜。
 *
 * ## 设计取舍
 *
 * - **紧凑**：折叠态只有一个 40dp 小球；v2.9.8 展开态固定 168dp 宽、高度上限
 *   屏高 40%，且默认只显示核心控件（高级字段折叠），远小于 v2.7.1 那个占约
 *   94% 屏宽 / 73% 屏高的全屏 `AlertDialog`。
 * - **保真**：表单只碰 [ActionEditGuard] 允许的简易字段，构造 `Patch` 交给
 *   `ActionEditGuard.apply` 基于 `copy` 落盘，绝不重建 Action、绝不丢
 *   `children` / `listeners` / `imageData` / `condition`。
 * - **Locale 安全**：所有数值格式化强制 `Locale.US`（v2.7.1 的教训：de/fr 区
 *   输出 `34,5678` 会被回填解析成 null，坐标被清零）。
 */
object FloatingEditorLayout {

    /** 折叠态小球边长（dp）。只画图标，不抢焦点、几乎不遮挡底层 APP。 */
    const val COLLAPSED_DP = 40

    /**
     * 展开态面板宽度（dp）。
     *
     * v2.9.7 只做 240 → 220 的微调（约 -8%），真机仍是一块近半屏宽的大卡片；
     * 用户第二次反馈「悬浮窗还是这么大」。v2.9.8 改为**结构性瘦身**：
     * 220 → 168（1080px 屏上 660px → 504px，占屏宽 61% → 47%），同时把
     * 高级字段默认折叠（见 `FloatingEditorPanel` 的「高级」区），控件与功能
     * 一个不删，只是不再一打开就铺满半个屏幕。
     */
    const val EXPANDED_W_DP = 168

    /** 展开态最大高度占屏比；v2.9.7：0.62 → 0.54，v2.9.8：0.54 → 0.40。 */
    const val MAX_H_FRACTION = 0.40f

    /**
     * v2.9.8：拖动**启动**阈值（dp），刻意远小于系统 `scaledTouchSlop`。
     *
     * v2.9.7 把点击与拖动统一到 `ViewConfiguration.scaledTouchSlop`（常见
     * 8dp ≈ 24px @3x），副作用是：40dp 小球上手指轻移 10~20px 完全不启动
     * 拖动，抬手却被判成点击 → 球直接展开成大面板。真机体感就是「悬浮球
     * 拖不动、一碰就变大」。现在拖动用 4dp（约 12px @3x）启动，点击仍用
     * 系统 slop 兜底；一旦进入拖动，[isTap] 因 `dragging=true` 恒为 false。
     */
    const val DRAG_START_DP = 4

    /** 拖动启动阈值（px）；必须严格小于系统 slop，否则中间位移会误触发展开。 */
    fun dragStartPx(density: Float): Float = dp(DRAG_START_DP, density).toFloat()

    /** 两个阈值的构造性约束，供单测锁定：启动阈值必须小于点击阈值。 */
    fun dragThresholdsValid(dragStartPx: Float, slopPx: Float): Boolean =
        dragStartPx > 0f && slopPx > dragStartPx

    /** 贴边最小留白（px），避免窗口完全贴死导致拖不回来。 */
    const val EDGE_PX = 2

    /** 面板两种形态。 */
    enum class State { COLLAPSED, EXPANDED }

    fun toggle(s: State): State = if (s == State.COLLAPSED) State.EXPANDED else State.COLLAPSED

    /** dp → px（与 Android `density` 同一口径，+0.5 四舍五入）。 */
    fun dp(v: Int, density: Float): Int = (v * density + 0.5f).toInt()

    /** 展开态允许的最大高度（px）。 */
    fun maxHeightPx(screenHpx: Int): Int = (screenHpx * MAX_H_FRACTION).toInt()

    /**
     * 把窗口左上角夹在屏内。窗口尺寸变化时（折叠↔展开）也用它重新收敛，
     * 避免展开后右下角越界导致部分控件点不到。
     */
    fun clampPosition(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int): Pair<Int, Int> {
        val maxX = (sw - w - EDGE_PX).coerceAtLeast(EDGE_PX)
        val maxY = (sh - h - EDGE_PX).coerceAtLeast(EDGE_PX)
        return x.coerceIn(EDGE_PX, maxX) to y.coerceIn(EDGE_PX, maxY)
    }

    /** 拖动累加后立即 clamp（与 `PickerGeometry.drag` 同一思路，但作用于窗口左上角）。 */
    fun drag(x: Int, y: Int, dx: Int, dy: Int, w: Int, h: Int, sw: Int, sh: Int): Pair<Int, Int> =
        clampPosition(x + dx, y + dy, w, h, sw, sh)

    /** 步骤游标：在 `[0, size-1]` 内移动；`size<=0` 恒为 0。 */
    fun step(index: Int, delta: Int, size: Int): Int {
        if (size <= 0) return 0
        return (index + delta).coerceIn(0, size - 1)
    }

    /** 新增动作后的游标：插到末尾并选中它。 */
    fun afterAppend(size: Int): Int = size // 新列表 size = 旧 size + 1，末位索引 = 旧 size

    // ------------------------------------------------------------ 表单

    /**
     * 紧凑表单的**原始字符串**（由 Android `EditText` 收集）。
     * 全用字符串是为了让解析/容错成为纯函数，可单测；不在这里做数值转换。
     */
    data class Form(
        val kind: Int = ActionEditGuard.KIND_TAP,
        val x: String = "50",
        val y: String = "50",
        val x2: String = "50",
        val y2: String = "50",
        val holdMs: String = "100",
        val durationMs: String = "400",
        val times: String = "1",
        val waitMs: String = "0",
        val desc: String = "",
        /** 框选出的 ROI；非空时保存阶段交给 `RoiApplier` 应用到脚本内目标。 */
        val region: Region? = null,
    )

    /** 百分比格式化（强制 Locale.US，4 位小数）。 */
    fun fmt(v: Double): String = String.format(Locale.US, "%.4f", v)

    /** 空串 / 非法输入一律收敛为 fallback，绝不静默丢弃导致保存按钮失灵。 */
    fun num(s: String, fallback: Double): Double = s.trim().toDoubleOrNull() ?: fallback

    fun long(s: String, fallback: Long): Long = s.trim().toLongOrNull() ?: fallback

    fun int(s: String, fallback: Int): Int = s.trim().toIntOrNull() ?: fallback

    /** 从既有 Action 构造表单初值（保真：非简易类型回退为 TAP，但 apply 会原样返回）。 */
    fun formOf(a: Action): Form {
        val p = ActionEditGuard.patchOf(a)
        return Form(
            kind = p.kind,
            x = fmt(p.x),
            y = fmt(p.y),
            x2 = fmt(p.x2),
            y2 = fmt(p.y2),
            holdMs = p.holdMs.toString(),
            durationMs = p.durationMs.toString(),
            times = p.times.toString(),
            waitMs = p.waitMs.toString(),
            desc = p.desc,
            region = if (RoiApplier.hasExplicitRegion(a.region)) a.region else null,
        )
    }

    /** 表单 → `ActionEditGuard.Patch`。所有数值在这里统一容错与 Locale 无关解析。 */
    fun toPatch(f: Form): ActionEditGuard.Patch = ActionEditGuard.Patch(
        kind = f.kind,
        x = num(f.x, 50.0),
        y = num(f.y, 50.0),
        x2 = num(f.x2, 50.0),
        y2 = num(f.y2, 50.0),
        holdMs = long(f.holdMs, 100L),
        durationMs = long(f.durationMs, 400L),
        times = int(f.times, 1),
        waitMs = long(f.waitMs, 0L),
        desc = f.desc,
    )

    // ------------------------------------------------------------ v2.8.1 抗抽搐

    /**
     * 拖动位移提交阈值（px）。
     *
     * v2.8.0 每个 `ACTION_MOVE` 都无条件 `wm.updateViewLayout()`：一个 60~120Hz
     * 的触摸序列会触发同等频率的全窗口 relayout，而窗口又是 `WRAP_CONTENT`，
     * 于是 measure/layout 反复跑 —— 录屏里面板边缘持续「抽搐」。本版只在位置
     * 真的变化至少 1px 时才提交，并配合 app 层的 Choreographer 按帧合并。
     */
    const val DRAG_COMMIT_PX = 1

    /**
     * 用**屏幕绝对坐标**（`MotionEvent.rawX/rawY`）计算窗口新位置。
     *
     * v2.8.0 用「相对被拖动 View 的局部坐标增量」`e.x - lastX` 累加：一旦
     * `updateViewLayout` 让窗口重新布局（尤其 `WRAP_CONTENT` 高度变化、沉浸式
     * 游戏状态栏切换），被拖动 View 在窗口内的位置会漂，局部增量就不再等于屏幕
     * 位移，表现为跟手抖动 + 越拖越偏。改为「按下时的窗口坐标 + 手指绝对位移」，
     * 不累加、不依赖任何 View 的局部坐标系，从根上消除漂移。
     */
    fun dragAbsolute(
        startWinX: Int, startWinY: Int,
        downRawX: Float, downRawY: Float,
        rawX: Float, rawY: Float,
        w: Int, h: Int, sw: Int, sh: Int,
    ): Pair<Int, Int> = clampPosition(
        startWinX + (rawX - downRawX).roundToInt(),
        startWinY + (rawY - downRawY).roundToInt(),
        w, h, sw, sh,
    )

    /** 相对上次已提交位置，是否需要真正提交一次窗口更新。 */
    fun shouldCommit(x: Int, y: Int, committedX: Int, committedY: Int): Boolean =
        abs(x - committedX) >= DRAG_COMMIT_PX || abs(y - committedY) >= DRAG_COMMIT_PX

    /** 与 Android `MeasureSpec` 的 mode 对应的纯 JVM 枚举，便于单测。 */
    enum class MeasureMode { UNSPECIFIED, EXACTLY, AT_MOST }

    /**
     * 修正 `MaxHeightScrollView.onMeasure` 的高度规格。
     *
     * v2.8.0 直接 `MeasureSpec.getSize(heightSpec).coerceAtMost(maxH)` 再
     * `makeMeasureSpec(..., AT_MOST)`：当窗口是 `WRAP_CONTENT`、ScrollView 收到
     * **UNSPECIFIED** 时 `getSize()` 返回 **0**，于是构造出 `AT_MOST(0)`，把内容
     * 高度压成 0，与父 LinearLayout 反复博弈 —— 这是窗口高度忽大忽小、内容
     * 「忽多忽少」的直接原因（录屏里有的帧只到「屏幕拾取起点」，有的多出
     * 「框选 ROI」一行）。
     *
     * 正确做法：UNSPECIFIED 改为 `AT_MOST(maxH)`；其余两种取 `min(size, maxH)`，
     * 且 EXACTLY 保持 EXACTLY 语义。
     */
    fun measureHeight(mode: MeasureMode, size: Int, maxH: Int): Pair<MeasureMode, Int> = when (mode) {
        MeasureMode.UNSPECIFIED -> MeasureMode.AT_MOST to maxH
        MeasureMode.EXACTLY -> MeasureMode.EXACTLY to size.coerceAtMost(maxH)
        MeasureMode.AT_MOST -> MeasureMode.AT_MOST to size.coerceAtMost(maxH)
    }


    // ------------------------------------------------------------ v2.9.7 点击/拖动判定单一真源
    // v2.9.8：MOVE 传 [dragStartPx]（4dp），UP 传系统 slop，两个阈值刻意分离。

    /**
     * 本次 MOVE 是否应认定为拖动。
     *
     * v2.9.6 的 MOVE 用 `4f * density` 启动拖动、UP 却用 `8f * density` 判定
     * 「点击」，中间 4~8dp 既不进入拖动又被判成点击 → 小球一点就展开。
     * v2.9.7 把两者统一到系统 slop 消掉了这段误判窗口，但阈值偏大（约 24px
     * @3x），40dp 小球上轻拖仍完全不启动拖动。v2.9.8 把 [thresholdPx] 交给
     * 调用方：MOVE 用更小的 `dragStartPx`，UP 用系统 slop 做点击上界。
     */
    fun isDragMove(dx: Float, dy: Float, thresholdPx: Float): Boolean =
        abs(dx) + abs(dy) > thresholdPx

    /** 只有从未进入拖动、且总位移未过点击上界，才是点击。 */
    fun isTap(dragging: Boolean, dx: Float, dy: Float, slopPx: Float): Boolean =
        !dragging && !isDragMove(dx, dy, slopPx)

    // ------------------------------------------------------------ v2.9.8 同步拖动提交

    /**
     * 一次 MOVE 的**无状态**提交决策：算出新位置，只有相对「已提交位置」变化
     * 至少 1px 才返回，否则返回 null。
     *
     * ## 为什么必须有这个函数
     *
     * v2.9.7 及以前，`FloatingEditorService.onDragAbsolute` 把窗口位移提交
     * **唯一地**挂在 `Choreographer.postFrameCallback` 上，并用 `frameScheduled`
     * 布尔量自锁：该标志只在 VSYNC 回调**开头**复位。一旦某帧回调未触发
     * （后台省电 / overlay 窗口静止时部分 ROM 会停 VSYNC 派发），或回调内
     * `shouldCommit` / `isPositionOnlyUpdate` 判定丢弃后再没有新的 MOVE 续投，
     * `frameScheduled` 就永久为 true，此后每一次 MOVE 都在函数入口直接
     * `return`，窗口再也不更新 —— 真机表现正是「收起后的悬浮球完全拖不动」。
     * 反证：主悬浮球 `RunnerService.attachBallTouch` 一直是同步
     * `wm.updateViewLayout`，从未被投诉拖不动。
     *
     * 本函数把「算位置 + 1px 节流」收敛成纯函数，**没有任何可变状态**，
     * 因此不存在「卡住」这种失效模式；Service 只需同步提交返回值即可。
     * 绝对坐标（[dragAbsolute]）与 1px 节流（[shouldCommit]）都保留，
     * v2.8.1 抗抽搐的核心成果不回退 —— 抽搐的真因是「局部坐标增量累加」，
     * 不是 `updateViewLayout` 的调用频率本身。
     */
    fun commitDrag(
        startWinX: Int, startWinY: Int,
        downRawX: Float, downRawY: Float,
        rawX: Float, rawY: Float,
        w: Int, h: Int, sw: Int, sh: Int,
        committedX: Int, committedY: Int,
    ): Pair<Int, Int>? {
        val (nx, ny) = dragAbsolute(
            startWinX, startWinY, downRawX, downRawY, rawX, rawY, w, h, sw, sh,
        )
        return if (shouldCommit(nx, ny, committedX, committedY)) nx to ny else null
    }

}

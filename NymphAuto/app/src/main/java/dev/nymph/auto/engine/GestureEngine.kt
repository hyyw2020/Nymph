package dev.nymph.auto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import dev.nymph.auto.core.vision.EdgeSafety
import dev.nymph.auto.core.log.RunLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 手势引擎：规划 GestureDescription 并串行派发。
 *
 * - Mutex 保证手势不重叠（并发点击会互相取消）
 * - CompletableDeferred + withTimeout 把异步回调转同步挂起，超时不卡死
 * - dispatch 前重新校验服务实例有效性（R4 修复）
 * - 形态随机（裁定阶段 3.2）：DOWN-UP 间 1~2 个亚像素 MOVE、滑动轻微弯曲。
 *   注意：pressure/size 变化需要 Shizuku injectInputEvent 通道（未接入），
 *   无障碍 dispatchGesture 只能做路径与时间形态——能力矩阵 DEGRADED 档
 *   不承诺完整形态随机，这里做当前通道的上限。
 */
class GestureEngine {

    class PlannedGesture internal constructor(
        val description: GestureDescription,
        val timeoutMs: Long,
    )

    private val mutex = Mutex()

    /**
     * 形态随机总开关（调试置 false 可复现精确回放）。
     *
     * v3.0.0：默认每次会话用安全随机种子——固定种子意味着"抖动序列每次完全一样"，
     * 可复现即可预测，对反检测零价值（红方评审结论）。需要可复现时由调用方显式
     * 设 [seed] 并置 formRandomEnabled=true，而不是全局写死一个常量。
     */
    var formRandomEnabled: Boolean = true

    private var rng: java.util.Random = java.util.Random(java.security.SecureRandom().nextLong())

    /** 显式设定种子以获得可复现序列（调试/回归用）；不调用则每次会话安全随机。 */
    fun setSeed(value: Long) {
        rng = java.util.Random(value)
    }

    /**
     * 屏幕合法坐标上界（RunnerService 装配时注入）。
     * 真机 v2.2.0 崩溃根因：当目标坐标为 0 时，拟人点击的亚像素抖动
     * `0 + (rng.nextFloat()-0.5f)`、滑动贝塞尔控制点都可能算出负值，
     * Android 无障碍手势要求 Path bounds 非负，直接抛
     * `Path bounds must not be negative` 打断整个脚本。统一钳制到屏内。
     */
    private var maxX = Int.MAX_VALUE
    private var maxY = Int.MAX_VALUE

    fun setScreenBounds(w: Int, h: Int) {
        maxX = (w - 1).coerceAtLeast(0)
        maxY = (h - 1).coerceAtLeast(0)
        screenW = w.coerceAtLeast(1)
        screenH = h.coerceAtLeast(1)
    }

    /**
     * v2.9.29：系统手势热区。由 RunnerService 从无障碍服务读取后注入。
     *
     * 默认全 0 表示「不知道」——此时不做任何内移，保持历史行为；只有拿到真实
     * insets 才收敛，避免在无手势导航的设备上把合法边缘点击改坏。
     */
    fun setEdgeInsets(insets: EdgeSafety.Insets) {
        edgeInsets = insets
    }

    /** 累计被边缘修正的点击数，供执行总结呈现（不含滑动）。 */
    var edgeAdjusted: Int = 0
        private set

    private var edgeInsets = EdgeSafety.Insets()
    private var loggedEdge = false
    private var screenW = 0
    private var screenH = 0

    /**
     * 点类手势的落点收敛。
     *
     * 只对**单点**手势内移：落点在返回/Home 热区内时，系统会静默消费掉这次触摸，
     * `dispatchGesture` 却返回成功——表现为「日志显示点了、界面没反应」。
     *
     * **滑动不做内移**：从左边缘起手往右滑本身就是系统返回手势或抽屉手势，
     * 把起点搬离边缘会直接改变手势语义，比失败更糟。
     */
    private fun safePoint(x: Int, y: Int): Pair<Int, Int> {
        if (screenW <= 0 || screenH <= 0) return x to y
        val a = EdgeSafety.adjust(x, y, screenW, screenH, edgeInsets)
        if (!a.moved) return x to y
        edgeAdjusted++
        if (!loggedEdge) {
            loggedEdge = true
            RunLog.i(
                "Gesture",
                "落点位于${a.reason}，已内移至 (${a.x},${a.y})（原 $x,$y）；" +
                    "该点若不内移会被系统手势消费，事件不会到达目标应用",
            )
        }
        return a.x to a.y
    }

    private fun px(v: Float): Float = v.coerceIn(0f, maxX.toFloat())
    private fun py(v: Float): Float = v.coerceIn(0f, maxY.toFloat())

    /** @return true=系统确认完成；false=被取消/超时/服务失效 */
    suspend fun dispatch(planned: PlannedGesture): Boolean {
        val service = AutoClickService.live() ?: return false
        return mutex.withLock {
            val done = CompletableDeferred<Boolean>()
            val submitted = service.dispatchGesture(
                planned.description,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        done.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        done.complete(false)
                    }
                },
                null,
            )
            if (!submitted) return@withLock false
            try {
                withTimeout(planned.timeoutMs) { done.await() }
            } catch (e: TimeoutCancellationException) {
                false
            }
        }
    }

    fun planTap(x: Int, y: Int, holdMs: Long): PlannedGesture {
        // v3.0.0：点击按压收敛到 40..120ms——过短被系统丢弃，过长失去点击语义。
        val hold = holdMs.coerceIn(MIN_TAP_MS, MAX_TAP_MS)
        val (sx, sy) = safePoint(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(if (formRandomEnabled) strokeHuman(sx, sy, hold) else strokeAt(sx, sy, 0L, hold))
        return PlannedGesture(builder.build(), hold + TIMEOUT_MARGIN_MS)
    }

    fun planLongPress(x: Int, y: Int, holdMs: Long): PlannedGesture {
        // 长按不走 planTap 的 120ms 点击上限，独立用手势总时长上限。
        val hold = holdMs.coerceIn(LONG_PRESS_MS, MAX_GESTURE_MS)
        val (sx, sy) = safePoint(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(strokeAt(sx, sy, 0L, hold))
        return PlannedGesture(builder.build(), hold + TIMEOUT_MARGIN_MS)
    }

    /** 多击合并提交；超出系统限制（笔数/总时长）返回 null，由调用方逐次派发。 */
    fun planMultiTap(x: Int, y: Int, holdMs: Long, gapMs: Long, times: Int): PlannedGesture? {
        if (times <= 0) return null
        val hold = holdMs.coerceIn(MIN_TAP_MS, MAX_GESTURE_MS)
        if (times > MAX_STROKES) return null
        if (times * (hold + gapMs) > MAX_GESTURE_MS) return null
        val (sx, sy) = safePoint(x, y)
        val builder = GestureDescription.Builder()
        var t = 0L
        repeat(times) {
            builder.addStroke(strokeAt(sx, sy, t, hold))
            t += hold + gapMs
        }
        return PlannedGesture(builder.build(), t + TIMEOUT_MARGIN_MS)
    }

    fun planSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): PlannedGesture {
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS)
        val path = if (formRandomEnabled) {
            // 轻微弯曲贝塞尔：垂直方向偏移约为弧长的 5%（符号随机），
            // 比直线更接近真人滑动；formRandomEnabled=false 时退化为直线
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1.0)
            val bend = (rng.nextDouble() - 0.5) * len * 0.1
            val cx = (x1 + x2) / 2.0 + (-dy / len) * bend
            val cy = (y1 + y2) / 2.0 + (dx / len) * bend
            Path().apply {
                moveTo(px(x1.toFloat()), py(y1.toFloat()))
                quadTo(px(cx.toFloat()), py(cy.toFloat()), px(x2.toFloat()), py(y2.toFloat()))
            }
        } else {
            Path().apply {
                moveTo(px(x1.toFloat()), py(y1.toFloat()))
                lineTo(px(x2.toFloat()), py(y2.toFloat()))
            }
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        return PlannedGesture(builder.build(), duration + TIMEOUT_MARGIN_MS)
    }

    fun planCurve(x1: Int, y1: Int, cx: Int, cy: Int, x2: Int, y2: Int, durationMs: Long): PlannedGesture {
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS)
        val path = Path().apply {
            moveTo(px(x1.toFloat()), py(y1.toFloat()))
            quadTo(px(cx.toFloat()), py(cy.toFloat()), px(x2.toFloat()), py(y2.toFloat()))
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        return PlannedGesture(builder.build(), duration + TIMEOUT_MARGIN_MS)
    }

    /** 轨迹回放（录制的手势）。点 <2 时退化为点击。 */
    fun planReplay(points: List<Pair<Int, Int>>, durationMs: Long): PlannedGesture {
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS)
        if (points.size < 2) {
            val p = points.firstOrNull() ?: (0 to 0)
            return planTap(p.first, p.second, duration)
        }
        val path = Path().apply {
            moveTo(px(points[0].first.toFloat()), py(points[0].second.toFloat()))
            for (i in 1 until points.size) lineTo(px(points[i].first.toFloat()), py(points[i].second.toFloat()))
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        return PlannedGesture(builder.build(), duration + TIMEOUT_MARGIN_MS)
    }

    /** 双指捏合：以 (centerX,centerY)-(endX,endY) 为第一指，镜像生成第二指。 */
    fun planPinch(centerX: Int, centerY: Int, endX: Int, endY: Int, durationMs: Long, zoomIn: Boolean): PlannedGesture {
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS)
        val mirrorX = 2 * centerX - endX
        val mirrorY = 2 * centerY - endY
        val f1 = Path()
        val f2 = Path()
        if (zoomIn) {
            f1.moveTo(px(endX.toFloat()), py(endY.toFloat())); f1.lineTo(px(centerX.toFloat()), py(centerY.toFloat()))
            f2.moveTo(px(mirrorX.toFloat()), py(mirrorY.toFloat())); f2.lineTo(px(centerX.toFloat()), py(centerY.toFloat()))
        } else {
            f1.moveTo(px(centerX.toFloat()), py(centerY.toFloat())); f1.lineTo(px(endX.toFloat()), py(endY.toFloat()))
            f2.moveTo(px(centerX.toFloat()), py(centerY.toFloat())); f2.lineTo(px(mirrorX.toFloat()), py(mirrorY.toFloat()))
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(f1, 0L, duration))
        builder.addStroke(GestureDescription.StrokeDescription(f2, 0L, duration))
        return PlannedGesture(builder.build(), duration + TIMEOUT_MARGIN_MS)
    }

    /** 多指手势：≤10 指，每指一条轨迹，startDelayMs 控制先后。 */
    fun planMultiFinger(
        fingers: List<Pair<Long, List<Pair<Int, Int>>>>,
        durationMs: Long,
    ): PlannedGesture? {
        if (fingers.isEmpty() || fingers.size > MAX_STROKES) return null
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS)
        val builder = GestureDescription.Builder()
        var totalEnd = 0L
        for ((delayStart, pts) in fingers) {
            if (pts.size < 2) return null
            val path = Path().apply {
                moveTo(px(pts[0].first.toFloat()), py(pts[0].second.toFloat()))
                for (i in 1 until pts.size) lineTo(px(pts[i].first.toFloat()), py(pts[i].second.toFloat()))
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, delayStart, duration))
            totalEnd = maxOf(totalEnd, delayStart + duration)
        }
        return PlannedGesture(builder.build(), totalEnd + TIMEOUT_MARGIN_MS)
    }

    private fun strokeAt(x: Int, y: Int, startTime: Long, duration: Long): GestureDescription.StrokeDescription {
        val path = Path().apply {
            moveTo(px(x.toFloat()), py(y.toFloat()))
            lineTo(px(x.toFloat()), py(y.toFloat()))
        }
        return GestureDescription.StrokeDescription(path, startTime, duration)
    }

    /**
     * 拟人点击（v3.0.0）：DOWN 后 1~2 个受控微 MOVE 再回到落点附近 UP。
     *
     * v2.9.x 的 ±0.5px 亚像素 MOVE 在物理像素上不改变落点，是无意义动画；
     * 本版改为 ±2px 的真实像素抖动 + 起点/终点回中，形成可观测的微轨迹，
     * 幅度受控（不偏离目标按钮可点击区）。抖动来自会话安全随机种子，
     * 每次点击不同——可复现即可预测，对反检测无价值（红方评审结论）。
     */
    private fun strokeHuman(x: Int, y: Int, hold: Long): GestureDescription.StrokeDescription {
        val path = Path().apply {
            moveTo(px(x.toFloat()), py(y.toFloat()))
            val moves = 1 + rng.nextInt(2)
            repeat(moves) {
                val jx = x + (rng.nextFloat() - 0.5f) * 2f * HUMAN_JITTER_PX
                val jy = y + (rng.nextFloat() - 0.5f) * 2f * HUMAN_JITTER_PX
                lineTo(px(jx), py(jy))
            }
            // 回到落点附近，保证抬起点仍在目标可点击区内。
            lineTo(px(x.toFloat()), py(y.toFloat()))
        }
        return GestureDescription.StrokeDescription(path, 0L, hold)
    }

    companion object {
        const val MAX_STROKES = 10
        /** v3.0.0：1ms 点按在部分 ROM 被判无效手势，下限收敛到 40ms（可配区间 40..120）。 */
        const val MIN_TAP_MS = 40L
        /** v3.0.0：点击按压上限，超过退化为长按语义。 */
        const val MAX_TAP_MS = 120L
        const val LONG_PRESS_MS = 700L
        const val MIN_SWIPE_MS = 10L
        const val MAX_GESTURE_MS = 60_000L
        const val TIMEOUT_MARGIN_MS = 5_000L

        /** 拟人微轨迹的最大单轴抖动像素（受控，避免点偏目标）。 */
        private const val HUMAN_JITTER_PX = 2f
    }
}

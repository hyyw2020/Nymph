package dev.nymph.auto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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

    /** 形态随机总开关（调试置 false 可复现精确回放；固定种子保证同序列）。 */
    var formRandomEnabled: Boolean = true

    private val rng = java.util.Random(FORM_SEED)

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
        val hold = holdMs.coerceIn(MIN_TAP_MS, MAX_GESTURE_MS)
        val builder = GestureDescription.Builder()
        builder.addStroke(if (formRandomEnabled) strokeHuman(x, y, hold) else strokeAt(x, y, 0L, hold))
        return PlannedGesture(builder.build(), hold + TIMEOUT_MARGIN_MS)
    }

    fun planLongPress(x: Int, y: Int, holdMs: Long): PlannedGesture =
        planTap(x, y, holdMs.coerceAtLeast(LONG_PRESS_MS))

    /** 多击合并提交；超出系统限制（笔数/总时长）返回 null，由调用方逐次派发。 */
    fun planMultiTap(x: Int, y: Int, holdMs: Long, gapMs: Long, times: Int): PlannedGesture? {
        if (times <= 0) return null
        val hold = holdMs.coerceIn(MIN_TAP_MS, MAX_GESTURE_MS)
        if (times > MAX_STROKES) return null
        if (times * (hold + gapMs) > MAX_GESTURE_MS) return null
        val builder = GestureDescription.Builder()
        var t = 0L
        repeat(times) {
            builder.addStroke(strokeAt(x, y, t, hold))
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
     * 拟人点击：DOWN 后 1~2 个亚像素 MOVE（±0.5px）再 UP。
     * 路径点按弧长在时长内均匀分布，天然形成 DOWN→MOVE→UP 的节奏。
     */
    private fun strokeHuman(x: Int, y: Int, hold: Long): GestureDescription.StrokeDescription {
        val path = Path().apply {
            moveTo(px(x.toFloat()), py(y.toFloat()))
            repeat(1 + rng.nextInt(2)) { // 1~2 个 MOVE
                lineTo(px(x + (rng.nextFloat() - 0.5f)), py(y + (rng.nextFloat() - 0.5f)))
            }
        }
        return GestureDescription.StrokeDescription(path, 0L, hold)
    }

    companion object {
        const val MAX_STROKES = 10
        const val MIN_TAP_MS = 1L
        const val LONG_PRESS_MS = 700L
        const val MIN_SWIPE_MS = 10L
        const val MAX_GESTURE_MS = 60_000L
        const val TIMEOUT_MARGIN_MS = 5_000L

        /** 形态随机固定种子：同序列可复现，调试可关。 */
        private const val FORM_SEED = 0x4E794D4CL
    }
}

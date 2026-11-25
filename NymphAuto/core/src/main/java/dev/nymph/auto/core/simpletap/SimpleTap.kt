// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.simpletap

import kotlinx.serialization.Serializable

/**
 * 轻量定点点击计划（v3.0.0，core 纯 JVM，无 Android 依赖）。
 *
 * ## 为什么存在
 *
 * 现有 [dev.nymph.auto.core.model.Script] 携带 40+ 语义槽位（锚点 / 条件 /
 * 监听 / 场景规则 / OCR 断言 …），绝大多数是为「截图猜坐标」取证链服务的。
 * 定点回放——「在这个坐标、每隔 N 毫秒、点 M 次」——不需要任何一项。本模型
 * 只保留五个字段，全部带默认值，作为零配置快路径的数据契约：
 *
 * - [SimpleTapPlan] 描述一次定点回放计划；
 * - [SimpleTapSchedule] 把计划展开为逐次点击的绝对时间轴（递增 tMs），
 *   注入器按时间轴派发，不经过 ScriptRunner 的判据 / 截图 / NCC 主链。
 *
 * ## 坐标契约
 *
 * [x] / [y] 一律为屏幕百分比（0–100，[dev.nymph.auto.core.model.CoordUnit.PCT]），
 * 与项目既有拾取口径一致。注入器按当前屏幕的宽高把百分比换算为像素，
 * 横竖屏切换后由注入器重新读取屏幕尺寸再换算——坐标空间始终单一。
 *
 * ## 零回归
 *
 * 本类是纯新增，不进入 Script 序列化 schema，不动任何既有动作 / 规则字段。
 * 老脚本、录制、导入、现有回放路径逐字不变。
 */
@Serializable
data class SimpleTapPlan(
    /** 横坐标，屏幕百分比 0–100。 */
    val x: Double,
    /** 纵坐标，屏幕百分比 0–100。 */
    val y: Double,
    /** 两次点击的间隔（毫秒）。 */
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /** 点击次数；0 = 无限次（由注入器会话控制停止）。 */
    val repeat: Int = 1,
    /** 单次按压时长（毫秒），落在 [MIN_HOLD_MS]..[MAX_HOLD_MS]。 */
    val holdMs: Long = DEFAULT_HOLD_MS,
) {
    init {
        require(x in 0.0..100.0) { "x 须在 0..100，实际 $x" }
        require(y in 0.0..100.0) { "y 须在 0..100，实际 $y" }
        require(intervalMs >= MIN_INTERVAL_MS) { "intervalMs 须 >= $MIN_INTERVAL_MS，实际 $intervalMs" }
        require(repeat >= 0) { "repeat 须 >= 0，实际 $repeat" }
        require(holdMs in MIN_HOLD_MS..MAX_HOLD_MS) {
            "holdMs 须在 $MIN_HOLD_MS..$MAX_HOLD_MS，实际 $holdMs（过短会被系统丢弃，过长失去点击语义）"
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 500L
        const val MIN_INTERVAL_MS = 40L
        const val DEFAULT_HOLD_MS = 80L
        /** 低于 40ms 的按压在部分 ROM 上被判为无效手势（见 GestureEngine 同类约束）。 */
        const val MIN_HOLD_MS = 40L
        const val MAX_HOLD_MS = 120L
    }
}

/**
 * 定点回放的逐次点击时间轴（纯函数，可复现，无时钟依赖）。
 *
 * 时间轴只给出「第 i 次点击相对于会话起点的偏移」（递增、非负），
 * 绝对时钟由注入器会话启动时取一次 [SystemClock] 等价基准后相加，
 * 这样 core 保持零 Android 依赖，且时间轴可被单测逐项核对。
 */
object SimpleTapSchedule {

    /** 一次点击：序号（0 基）与相对会话起点的偏移毫秒。 */
    data class Tick(val index: Int, val offsetMs: Long)

    /**
     * 展开计划为时间轴。
     *
     * - [plan.repeat] == 0（无限）或 [limit] 更小时，以 [limit] 为展开上限，
     *   避免生成无界列表——注入器对无限计划按批取用。
     * - 偏移严格递增：`offset_i = i * intervalMs`，无抖动（拟人由注入器在
     *   派发时叠加，保持计划本身可复现）。
     */
    fun expand(plan: SimpleTapPlan, limit: Int = DEFAULT_LIMIT): List<Tick> {
        val count = if (plan.repeat == 0) limit else minOf(plan.repeat, limit)
        require(count >= 0) { "limit 须 >= 0，实际 $limit" }
        return (0 until count).map { i -> Tick(i, i * plan.intervalMs) }
    }

    /** 百分比坐标 → 屏幕像素（单一换算点，注入器与单测共用）。 */
    fun resolvePixels(x: Double, y: Double, screenW: Int, screenH: Int): Pair<Int, Int> {
        require(screenW > 0 && screenH > 0) { "屏幕尺寸须为正，实际 ${screenW}x${screenH}" }
        val px = (x / 100.0 * screenW).toInt().coerceIn(0, screenW - 1)
        val py = (y / 100.0 * screenH).toInt().coerceIn(0, screenH - 1)
        return px to py
    }

    const val DEFAULT_LIMIT = 10_000
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.gesture

import kotlin.math.abs

/**
 * **悬浮窗口位移事件链路自诊断**（v2.9.17 / D3，core 纯 JVM，可单测）。
 *
 * 目的：把「窗口位移能力是否生效」从依赖真机主观判断，转成一次手势即可取证的
 * 结构化结论。触摸绑定、屏幕绝对坐标计算与同步提交已在前序版本完成并通过
 * 构建层核对，剩余不确定性位于系统触摸派发与悬浮窗策略；本对象旁路记录一次
 * 手势的事件阶段，定位断点所在层级，使取证不再依赖推测。
 *
 * 设计边界：只读观察，**不参与任何位移提交逻辑**，不改变 `updateViewLayout` 的
 * 调用时机与参数；所有记录方法均为旁路调用，不返回决策值。
 */
class DragProbe {

    /** 按失败代价排序的四态结论。 */
    enum class Kind {
        /** 未采集到按下事件。 */
        NO_DOWN,

        /** 按下已接收，移动事件未持续派发。 */
        MOVE_INTERCEPTED,

        /** 移动已派发，但窗口位置未产生变化。 */
        COMMIT_BLOCKED,

        /** 事件链路完整且窗口位移生效。 */
        PASS,
    }

    data class Report(
        val kind: Kind,
        val headline: String,
        val detail: String,
        val action: String?,
        val downCount: Int,
        val moveCount: Int,
        val commitCount: Int,
        val displacementPx: Int,
        val cancelled: Boolean,
    ) {
        val ok: Boolean get() = kind == Kind.PASS

        /** 原始观测量，供运行日志与诊断明细复现，界面不直接呈现。 */
        val metrics: String
            get() = "DOWN=$downCount MOVE=$moveCount COMMIT=$commitCount " +
                "DISPLACEMENT=${displacementPx}px CANCEL=$cancelled"
    }

    private var downCount = 0
    private var moveCount = 0
    private var commitCount = 0
    private var displacementPx = 0
    private var cancelled = false
    private var started = false

    /** 一次新的按下视为新一轮手势；调用方需在结束前读取上一轮结论。 */
    fun onDown() {
        reset()
        started = true
        downCount++
    }

    fun onMove() {
        if (started) moveCount++
    }

    /**
     * 记录一次窗口位置提交。
     *
     * @param beforeX/beforeY 提交前窗口左上角坐标。
     * @param afterX/afterY 提交后窗口左上角坐标。
     */
    fun onCommit(beforeX: Int, beforeY: Int, afterX: Int, afterY: Int) {
        if (!started) return
        commitCount++
        displacementPx += abs(afterX - beforeX) + abs(afterY - beforeY)
    }

    /** 记录手势结束；系统取消（ACTION_CANCEL）是定位派发层断点的重要信号。 */
    fun onEnd(wasCancelled: Boolean = false) {
        if (started && wasCancelled) cancelled = true
    }

    fun report(): Report {
        val m = "原始观测量：DOWN=$downCount MOVE=$moveCount " +
            "COMMIT=$commitCount 累计位移=${displacementPx}px 取消=$cancelled"
        return when {
            downCount == 0 -> Report(
                Kind.NO_DOWN,
                "未采集到按下事件",
                "本轮自检尚无 DOWN 记录；窗口可能未接收触摸，或自检开启后尚未执行拖动手势。$m",
                "确认悬浮窗权限与窗口 flags 后，对悬浮球或标题条执行一次拖动",
                downCount, moveCount, commitCount, displacementPx, cancelled,
            )
            moveCount == 0 || (cancelled && displacementPx == 0) -> Report(
                Kind.MOVE_INTERCEPTED,
                "按下已接收，移动事件未持续派发",
                "DOWN 已记录，MOVE 计数为 $moveCount，取消标记=$cancelled。断点位于父视图" +
                    "拦截或系统触摸派发策略，不在本工程拖动提交链路内。$m",
                "记录父视图层级、requestDisallowInterceptTouchEvent 调用结果与 ROM 触摸派发策略",
                downCount, moveCount, commitCount, displacementPx, cancelled,
            )
            commitCount == 0 -> Report(
                Kind.COMMIT_BLOCKED,
                "移动已派发，尚未进入窗口位置提交",
                "MOVE=$moveCount 但 COMMIT=0；位移判定阈值可能未达到，或提交分支未执行。$m",
                "记录拖动阈值判定输入与提交分支进入情况",
                downCount, moveCount, commitCount, displacementPx, cancelled,
            )
            displacementPx == 0 -> Report(
                Kind.COMMIT_BLOCKED,
                "提交已执行，窗口位置未变化",
                "COMMIT=$commitCount 但累计位移为 0。断点位于 WindowManager 提交应用阶段或" +
                    "系统悬浮窗位置策略。$m",
                "记录 updateViewLayout 返回值、窗口 params 前后值与 ROM 悬浮窗限制策略",
                downCount, moveCount, commitCount, displacementPx, cancelled,
            )
            else -> Report(
                Kind.PASS,
                "事件链路完整，窗口位移生效",
                "一次手势内按下、移动、提交与位置变化四阶段均成立。$m",
                null,
                downCount, moveCount, commitCount, displacementPx, cancelled,
            )
        }
    }

    fun reset() {
        downCount = 0
        moveCount = 0
        commitCount = 0
        displacementPx = 0
        cancelled = false
        started = false
    }
}

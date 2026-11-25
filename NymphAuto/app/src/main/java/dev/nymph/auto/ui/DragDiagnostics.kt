// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import dev.nymph.auto.core.gesture.DragProbe
import dev.nymph.auto.core.log.RunLog

/**
 * **悬浮窗口位移自诊断的进程内聚合点**（v2.9.17 / D3）。
 *
 * 两处悬浮窗口各自持有一个 [DragProbe]，触摸链路以旁路方式记录事件阶段，
 * 设置页读取最近一次手势的结构化结论。所有调用均位于主线程；聚合点不改变
 * 任何窗口位置提交逻辑，仅提供取证与呈现所需的数据。
 */
object DragDiagnostics {

    enum class Target(val label: String) {
        /** 悬浮编辑器面板（折叠小球与顶部拖动手柄）。 */
        EDITOR_PANEL("悬浮编辑器"),

        /** 主界面运行球。 */
        RUNNER_BALL("主界面运行球"),
    }

    data class Snapshot(val target: String, val report: DragProbe.Report)

    private val probes: Map<Target, DragProbe> =
        Target.entries.associateWith { DragProbe() }

    fun onDown(target: Target) = probes.getValue(target).onDown()

    fun onMove(target: Target) = probes.getValue(target).onMove()

    fun onCommit(target: Target, beforeX: Int, beforeY: Int, afterX: Int, afterY: Int) =
        probes.getValue(target).onCommit(beforeX, beforeY, afterX, afterY)

    fun onEnd(target: Target, wasCancelled: Boolean = false) =
        probes.getValue(target).onEnd(wasCancelled)

    fun report(target: Target): DragProbe.Report = probes.getValue(target).report()

    /** 两路最近一次手势的结论，供设置页呈现。 */
    fun snapshot(): List<Snapshot> =
        Target.entries.map { Snapshot(it.label, probes.getValue(it).report()) }

    /**
     * 把两路结论与原始观测量写入运行日志，供真机一次手势后从日志页导出。
     * 设置页进入时记录一次快照，不参与任何执行语义。
     */
    fun writeLog() {
        snapshot().forEach { s ->
            RunLog.i("DragProbe", "${s.target}：${s.report.headline} · ${s.report.metrics}")
        }
    }
}

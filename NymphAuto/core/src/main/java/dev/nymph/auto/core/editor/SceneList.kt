// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import java.util.Locale

/**
 * 场景列表与用户可见术语的单一真源（v2.9.21，纯 JVM）。
 *
 * 术语契约：一次「拾取精确坐标 → 自动采集辅助素材 → 自动收敛有效区域 →
 * 颜色校验 → 执行」构成一个完整「场景」。用户可见层统一以「场景」表述，
 * 不出现「动作 / 动作名称 / 动作描述 / 添加动作 / 动作 chips」等叫法，
 * 也不按索引把完整场景编号为「场景 N / M」。
 *
 * 列表行只承载坐标摘要（拾取点在哪），点行进详情编辑；场景名是详情态内
 * 一个可获焦、可唤起输入法的可编辑字段，不作为列表行的只读文本。
 * 落盘字段（Action / schemaVersion）不变，术语归一仅作用于呈现层。
 */
object SceneList {

    const val UNIT = "场景"
    const val TITLE_CURRENT = "当前场景"
    const val TITLE_LIST = "场景列表"
    const val LABEL_NEW = "＋ 新场景"
    const val LABEL_PICK = "拾取坐标"
    const val LABEL_NAME = "场景名"
    const val LABEL_NAME_HINT = "场景名（点击可编辑）"
    const val LABEL_ASSIST = "辅助门控"
    const val LABEL_EVALUATING = "判定中"
    const val LABEL_MANUAL_ROI = "手动框选覆盖"
    const val LABEL_CLEAR_MANUAL_ROI = "清除手动，回退自动区域"
    const val LABEL_RESET_AUTO_ROI = "重置为自动区域"
    const val LABEL_SAVE = "保存"
    const val LABEL_CANCEL = "取消"

    /** 一行场景摘要：序号仅用于在列表中定位，不构成执行顺序编号。 */
    data class Row(
        val position: Int,
        val coordinateLabel: String,
        val selected: Boolean,
    )

    /**
     * 坐标摘要（Locale.US，一位小数百分比）。序号以「·」分隔，仅作定位标识，
     * 不呈现「场景 N / M」式分数，也不呈现「第 N 步」。
     */
    fun coordinateLabel(xPct: Double, yPct: Double): String = String.format(
        Locale.US, "%.1f%% , %.1f%%", xPct, yPct,
    )

    /** 由坐标摘要序列构造列表行；[selectedAt] 为当前进入详情的场景位置。 */
    fun rows(coordinates: List<String>, selectedAt: Int): List<Row> =
        coordinates.mapIndexed { i, label -> Row(i, label, i == selectedAt) }

    /** 当前场景标题：有名称用名称，未命名回落到「当前场景」，不呈现编号。 */
    fun currentTitle(name: String?): String =
        if (name.isNullOrBlank()) TITLE_CURRENT else name.trim()

    /** 列表游标在合法区间内收敛（供点行进入详情使用，非执行顺序）。 */
    fun selectAt(target: Int, size: Int): Int =
        if (size <= 0) 0 else target.coerceIn(0, size - 1)

    /** 场景名在详情态是否允许编辑：恒为 true（禁止只读化 / 懒实例化规避输入法）。 */
    fun nameEditable(): Boolean = true
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script

/**
 * 把「框选出的 ROI」真正作用到脚本（v2.7.1，纯 JVM 可测）。
 *
 * ## 修的 v2.7.0 真 bug：框了等于白框
 *
 * v2.7.0 的框选按钮在**简易动作编辑对话框**里，只把结果写进当前动作的
 * `region` 字段。但简易编辑器只编辑 TAP / LONG_PRESS / MULTI_TAP / SWIPE
 * 四种**坐标动作**，而 `ScriptRunner` 里 `region` 只被找图 / 找色 / 找文字 /
 * AI / 节点 / 条件读取——坐标动作不读取该字段。用户框完区域、确认、保存，
 * 运行时毫无变化，界面还显示「已框选」，是一次静默失效。
 *
 * 本类提供唯一真源 [apply]：把 ROI 写入脚本内**所有真正读取 region 的位置**
 * （视觉目标动作的 region；动作/脚本条件的 region 及条件树 children；
 * 嵌套 children；listener 动作），返回命中数量，UI 据此明确告知
 * 「应用到了 N 处」，N=0 时按实际情况提示脚本内没有找图/找色目标，不再误报成功。
 *
 * ROI 同时写入数值四元组与 `Region.expr`（表达式），运行时
 * `RoiExpr.resolveRegion` 优先按表达式解析，老脚本无 expr 时行为不变。
 */
/**
 * 多框应用的受理结果（v3.0.0）。
 *
 * [accepted]=false 时不写入任何目标、[script] 为原对象，[reason] 给出可直接呈现
 * 给用户的拒绝原因——这是 fail-loud 原则在数据层的落点：框数与目标数不匹配时
 * 绝不静默轮转造成落点错配。
 */
data class MultiApplyResult(
    val script: Script,
    val hits: Int,
    val accepted: Boolean,
    val reason: String = "",
)

object RoiApplier {

    /** 运行时按 action.region 找目标的动作类型（与 ScriptRunner 调用点一致）。 */
    private val ROI_ACTIONS: Set<ActionType> = setOf(
        ActionType.CLICK_IMAGE,
        ActionType.CLICK_COLOR,
        ActionType.CLICK_TEXT,
        ActionType.CLICK_AI,
        ActionType.CLICK_NODE,
    )

    /** 运行时按 condition.region 找目标的条件类型。 */
    private val ROI_CONDITIONS: Set<ConditionKind> = setOf(
        ConditionKind.APPEAR_IMAGE,
        ConditionKind.APPEAR_TEXT,
        ConditionKind.APPEAR_COLOR,
        ConditionKind.APPEAR_NODE,
        ConditionKind.APPEAR_AI,
        ConditionKind.ABORT,
    )

    fun isRoiTarget(type: ActionType): Boolean = type in ROI_ACTIONS

    /** 该区域是否为「非全屏」的显式区域（判断打开编辑器时算不算已框选）。 */
    fun hasExplicitRegion(region: Region): Boolean =
        region.expr.isNotBlank() ||
            region.leftPct > 0.0 || region.topPct > 0.0 ||
            region.rightPct < 100.0 || region.bottomPct < 100.0

    /**
     * 把 [region] 应用到 [script] 内所有读取 region 的位置，返回 (新脚本, 命中数)。
     * 无命中时返回**原对象**与 0（便于 UI 判断，也避免无意义重建）。
     *
     * 全程 `copy` 保真：children / listeners / condition / imageData 等字段不丢。
     */
    fun apply(script: Script, region: Region): Pair<Script, Int> {
        var hits = 0

        fun mapCondition(c: Condition): Condition {
            val target = c.kind in ROI_CONDITIONS
            if (target) hits++
            val newRegion = if (target) region else c.region
            val kids = if (c.children.isEmpty()) c.children else c.children.map(::mapCondition)
            return if (!target && kids === c.children) c
            else c.copy(region = newRegion, children = kids)
        }

        fun mapAction(a: Action): Action {
            val target = isRoiTarget(a.type)
            if (target) hits++
            val newRegion = if (target) region else a.region
            val children = if (a.children.isEmpty()) a.children else a.children.map(::mapAction)
            val condition = a.condition?.let(::mapCondition)
            val postCondition = a.postCondition?.let(::mapCondition)
            val listeners = mapListeners(a.listeners, ::mapAction)
            return if (!target && children === a.children && condition === a.condition &&
                postCondition === a.postCondition && listeners === a.listeners
            ) a
            else a.copy(
                region = newRegion, children = children,
                condition = condition, postCondition = postCondition, listeners = listeners,
            )
        }

        val actions = script.actions.map(::mapAction)
        val scriptListeners = mapListeners(script.listeners, ::mapAction)
        val abort = script.abortCondition?.let(::mapCondition)
        if (hits == 0) return script to 0
        return script.copy(
            actions = actions, listeners = scriptListeners, abortCondition = abort,
        ) to hits
    }

    /**
     * 多特征框应用（v3.0.0，取消隐式轮转；ADR-010 唯一真源的扩展）。
     *
     * 把 [boxes] 按**遍历序**写到脚本内所有真正读取 region 的位置，遍历顺序与
     * [apply] 逐字一致（动作 → children → condition → postCondition → listeners →
     * 脚本 listeners → abortCondition），因此 ADR-010 的目标全集一个不漏。
     *
     * ## v3.0.0 取消短边轮转（fail-loud）
     *
     * v2.9.49 在框数 ≠ 目标数时用 `boxes[cursor % boxes.size]` 循环复用，落点
     * 对应关系完全不可预测。本版改为**显式受理**：
     * - 空 [boxes] → 拒绝（NO_BOX），返回原对象与 0；
     * - 框数 == 目标数 → 按遍历序一一映射，一一对应、完全可预测；
     * - 单框兼容：1 框可覆盖任意数量目标（等价旧单 ROI），仍为显式受理；
     * - 框数 > 1 且 != 目标数 → **拒绝**（COUNT_MISMATCH），不写入、返回原对象，
     *   由 [MultiApplyResult.reason] 告知用户实际数量，禁止隐式循环复用。
     *
     * - 全程 `copy` 保真，不丢 imageData / condition / listeners / children。
     *
     * @return [MultiApplyResult]：受理状态、命中数、可直接呈现的拒绝原因。
     */
    fun applyMultiChecked(script: Script, boxes: List<FeatureBox>): MultiApplyResult {
        if (boxes.isEmpty()) {
            return MultiApplyResult(script, 0, false, "未提供特征框")
        }
        val targets = countTargets(script)
        if (targets == 0) {
            return MultiApplyResult(script, 0, false, "脚本内没有可应用特征框的找图目标")
        }
        // 显式受理：单框兼容覆盖全部；多框必须与目标数一一相等，否则拒绝。
        if (boxes.size > 1 && boxes.size != targets) {
            return MultiApplyResult(
                script, 0, false,
                "特征框数量(${boxes.size})与目标数量($targets)不一致，已拒绝应用以避免落点错配；" +
                    "请调整为每个目标一个框，或仅用一个框覆盖全部目标",
            )
        }
        var hits = 0
        var cursor = 0

        fun nextFor(writer: (Region, Int) -> Unit) {
            // 单框时 cursor 恒取 0；多框时已校验 boxes.size == targets，绝不越界。
            val box = if (boxes.size == 1) boxes[0] else boxes[cursor]
            cursor++
            hits++
            writer(box.region, box.similarity)
        }

        fun mapListenersMulti(l: Listeners, map: (Action) -> Action): Listeners {
            fun List<Action>.m(): List<Action> = if (isEmpty()) this else this.map(map)
            return Listeners(
                beforeAction = l.beforeAction.m(),
                afterAction = l.afterAction.m(),
                onActionFail = l.onActionFail.m(),
                onActionOk = l.onActionOk.m(),
                beforeList = l.beforeList.m(),
                afterList = l.afterList.m(),
                beforeScript = l.beforeScript.m(),
                afterScript = l.afterScript.m(),
                beforeConditionCheck = l.beforeConditionCheck.m(),
                onConditionFail = l.onConditionFail.m(),
            )
        }

        fun mapCondition(c: Condition): Condition {
            val target = c.kind in ROI_CONDITIONS
            var newRegion = c.region
            var newSim = c.imageSimilarity
            if (target) nextFor { r, s -> newRegion = r; newSim = s }
            val kids = if (c.children.isEmpty()) c.children else c.children.map(::mapCondition)
            return if (!target && kids === c.children) c
            else c.copy(region = newRegion, imageSimilarity = newSim, children = kids)
        }

        fun mapAction(a: Action): Action {
            val target = isRoiTarget(a.type)
            var newRegion = a.region
            var newSim = a.imageSimilarity
            if (target) nextFor { r, s -> newRegion = r; newSim = s }
            val children = if (a.children.isEmpty()) a.children else a.children.map(::mapAction)
            val condition = a.condition?.let(::mapCondition)
            val postCondition = a.postCondition?.let(::mapCondition)
            val listeners = mapListenersMulti(a.listeners, ::mapAction)
            return if (!target && children === a.children && condition === a.condition &&
                postCondition === a.postCondition && listeners === a.listeners
            ) a
            else a.copy(
                region = newRegion, imageSimilarity = newSim, children = children,
                condition = condition, postCondition = postCondition, listeners = listeners,
            )
        }

        val actions = script.actions.map(::mapAction)
        val scriptListeners = mapListenersMulti(script.listeners, ::mapAction)
        val abort = script.abortCondition?.let(::mapCondition)
        if (hits == 0) {
            return MultiApplyResult(script, 0, false, "没有目标被应用")
        }
        return MultiApplyResult(
            script.copy(
                actions = actions, listeners = scriptListeners, abortCondition = abort,
            ),
            hits, true,
        )
    }

    /**
     * 兼容视图：保持 v2.9.49 的 [Pair] 签名，内部委托 [applyMultiChecked]。
     * 拒绝时返回原对象与 0（与历史上的空框/无目标行为一致），不抛异常、零回归。
     * 需要拒绝原因的调用点请改用 [applyMultiChecked]。
     */
    fun applyMulti(script: Script, boxes: List<FeatureBox>): Pair<Script, Int> {
        val r = applyMultiChecked(script, boxes)
        return r.script to r.hits
    }

    private fun mapListeners(l: Listeners, map: (Action) -> Action): Listeners {
        fun List<Action>.m(): List<Action> = if (isEmpty()) this else this.map(map)
        return Listeners(
            beforeAction = l.beforeAction.m(),
            afterAction = l.afterAction.m(),
            onActionFail = l.onActionFail.m(),
            onActionOk = l.onActionOk.m(),
            beforeList = l.beforeList.m(),
            afterList = l.afterList.m(),
            beforeScript = l.beforeScript.m(),
            afterScript = l.afterScript.m(),
            beforeConditionCheck = l.beforeConditionCheck.m(),
            onConditionFail = l.onConditionFail.m(),
        )
    }

    /** 统计脚本内可被 ROI 作用的位置数（UI 预显示，不改脚本）。 */
    fun countTargets(script: Script): Int {
        fun countCondition(c: Condition): Int =
            (if (c.kind in ROI_CONDITIONS) 1 else 0) + c.children.sumOf(::countCondition)
        fun countAction(a: Action): Int =
            (if (isRoiTarget(a.type)) 1 else 0) +
                (a.condition?.let(::countCondition) ?: 0) +
                (a.postCondition?.let(::countCondition) ?: 0) +
                a.children.sumOf(::countAction) +
                a.listeners.all().sumOf(::countAction)
        return script.actions.sumOf(::countAction) +
            script.listeners.all().sumOf(::countAction) +
            (script.abortCondition?.let(::countCondition) ?: 0)
    }
}

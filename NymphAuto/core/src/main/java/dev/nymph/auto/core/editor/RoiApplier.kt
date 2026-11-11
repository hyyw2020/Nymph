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
 * AI / 节点 / 条件读取——坐标动作根本不读它。用户框完区域、确认、保存，
 * 运行时毫无变化，界面还显示「已框选」，是一次静默失效。
 *
 * 本类提供唯一真源 [apply]：把 ROI 写入脚本内**所有真正读取 region 的位置**
 * （视觉目标动作的 region；动作/脚本条件的 region 及条件树 children；
 * 嵌套 children；listener 动作），返回命中数量，UI 据此明确告知
 * 「应用到了 N 处」，N=0 时如实提示脚本内没有找图/找色目标，不再假装成功。
 *
 * ROI 同时写入数值四元组与 `Region.expr`（表达式），运行时
 * `RoiExpr.resolveRegion` 优先按表达式解析，老脚本无 expr 时行为不变。
 */
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

package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.CoordUnit

/**
 * 编辑器动作保真守卫（v2.7.0，纯 JVM 可测）。
 *
 * ## 修复的严重数据丢失 bug（v2.6.0 已知不足）
 *
 * `EditorScreen.kindOf()` 对非 4 种简易类型返回 0，用户打开一个 WAIT /
 * RUN_ACTIONS / CLICK_IMAGE 动作时会被当成「点击」，确认时用
 * `Action(...)` **重新构造**一个新对象——`children`、`listeners`、
 * `condition`、`postCondition`、`imageData`、`jsCode`、`region` 等全部字段
 * 被静默清空。录制/导入的复杂脚本一编辑就残废，且界面毫无提示。
 *
 * ## 本守卫的契约
 *
 * 1. [kindOf] 对不受简易编辑器支持的类型返回 `-1`，UI 据此**切换为只读/源码
 *    视图并禁用「确定」**，绝不把未知类型伪装成点击。
 * 2. [apply] 一律基于 `original.copy(...)`，只改简易字段，其余字段原样保留。
 *    即便一个 TAP 动作带着 children / listeners / 条件，编辑坐标也不会丢。
 * 3. [supportsSimpleEdit] 与 [kindOf] 单一真源，避免 UI 与保存各判一套。
 */
object ActionEditGuard {

    const val KIND_UNSUPPORTED = -1
    const val KIND_TAP = 0
    const val KIND_LONG_PRESS = 1
    const val KIND_MULTI_TAP = 2
    const val KIND_SWIPE = 3

    /** 简易编辑器能无损编辑的类型集合。 */
    val SIMPLE_TYPES: Set<ActionType> = setOf(
        ActionType.TAP,
        ActionType.LONG_PRESS,
        ActionType.MULTI_TAP,
        ActionType.SWIPE,
    )

    /** 与 [apply] 同一真源：false 时 UI 必须走源码视图或只读。 */
    fun supportsSimpleEdit(a: Action): Boolean = a.type in SIMPLE_TYPES

    /** 未知类型返回 [KIND_UNSUPPORTED]，不再悄悄映射为点击。 */
    fun kindOf(a: Action): Int = when (a.type) {
        ActionType.TAP -> KIND_TAP
        ActionType.LONG_PRESS -> KIND_LONG_PRESS
        ActionType.MULTI_TAP -> KIND_MULTI_TAP
        ActionType.SWIPE -> KIND_SWIPE
        else -> KIND_UNSUPPORTED
    }

    fun typeOf(kind: Int): ActionType? = when (kind) {
        KIND_TAP -> ActionType.TAP
        KIND_LONG_PRESS -> ActionType.LONG_PRESS
        KIND_MULTI_TAP -> ActionType.MULTI_TAP
        KIND_SWIPE -> ActionType.SWIPE
        else -> null
    }

    /** 简易编辑器可修改的字段集合（其余全部从 original 继承）。 */
    data class Patch(
        val kind: Int = KIND_TAP,
        val x: Double,
        val y: Double,
        val x2: Double = 0.0,
        val y2: Double = 0.0,
        val holdMs: Long = 100L,
        val durationMs: Long = 400L,
        val times: Int = 1,
        val waitMs: Long = 0L,
        val desc: String = "",
    )

    /**
     * 应用编辑。基于 `copy` 保真；类型不支持或 kind 非法时**原样返回**，
     * 绝不构造新 Action、绝不丢字段。
     */
    fun apply(original: Action, patch: Patch): Action {
        val type = typeOf(patch.kind) ?: return original
        if (!supportsSimpleEdit(original)) return original
        val px = patch.x.coerceIn(0.0, 100.0)
        val py = patch.y.coerceIn(0.0, 100.0)
        val hold = patch.holdMs.coerceIn(0L, 600_000L)
        val dur = patch.durationMs.coerceIn(20L, 600_000L)
        val times = patch.times.coerceAtLeast(1)
        val wait = patch.waitMs.coerceAtLeast(0L)
        return when (type) {
            ActionType.LONG_PRESS -> original.copy(
                type = type, desc = patch.desc, unit = CoordUnit.PCT,
                x = px, y = py, holdMs = hold, times = times, waitMs = wait,
            )
            ActionType.MULTI_TAP -> original.copy(
                type = type, desc = patch.desc, unit = CoordUnit.PCT,
                x = px, y = py, holdMs = hold.coerceAtMost(60L),
                times = times.coerceAtLeast(2), gapMs = 80L, waitMs = wait,
            )
            ActionType.SWIPE -> original.copy(
                type = type, desc = patch.desc, unit = CoordUnit.PCT,
                x = px, y = py,
                x2 = patch.x2.coerceIn(0.0, 100.0),
                y2 = patch.y2.coerceIn(0.0, 100.0),
                durationMs = dur, times = times, waitMs = wait,
            )
            else -> original.copy(
                type = type, desc = patch.desc, unit = CoordUnit.PCT,
                x = px, y = py, holdMs = hold, times = times, waitMs = wait,
            )
        }
    }

    /** 从既有动作构造 Patch，保证打开编辑框时数值与原文一致。 */
    fun patchOf(a: Action): Patch = Patch(
        kind = kindOf(a).let { if (it == KIND_UNSUPPORTED) KIND_TAP else it },
        x = a.x,
        y = a.y,
        x2 = a.x2,
        y2 = a.y2,
        holdMs = if (a.holdMs > 0) a.holdMs else 100L,
        durationMs = if (a.durationMs > 0) a.durationMs else 400L,
        times = maxOf(1, a.times),
        waitMs = a.waitMs,
        desc = a.desc,
    )
}

package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.SceneRule

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
 *
 * ## v3.0.3 S5 完整档（统一工作台内视觉/门控字段可见可改）
 *
 * 之前的「保真」只保证编辑坐标时这些字段不被清空，但界面上看不见、改不了，
 * 用户被迫去源码 JSON 改合取门控/相似度/区域——与「统一工作台」目标相悖，
 * 且是最小档冒充完整档。本版把 [imageData] / [imageSimilarity] / [region] /
 * [condition] / [sceneRule] 纳入 [Patch]，并支持在对话框内直接编辑。
 *
 * **零回归语义**：Patch 中这些新增字段全部 nullable，`null` = 保持原值（不改），
 * 非 null = 显式写入。老调用点不传新字段 → 全部 null → 行为逐字不变。
 */
object ActionEditGuard {

    const val KIND_UNSUPPORTED = -1
    const val KIND_TAP = 0
    const val KIND_LONG_PRESS = 1
    const val KIND_MULTI_TAP = 2
    const val KIND_SWIPE = 3
    /** 视觉类动作：不改 type，只在工作台内编辑视觉/门控字段。 */
    const val KIND_VISION = 4

    /** PX/DP 坐标的合法性上限（像素级）。仅用于兜住误输入，正常屏幕远小于此。 */
    private const val MAX_PIXEL_COORD = 100_000.0

    /** 可无损编辑坐标/手势的类型集合。 */
    val SIMPLE_TYPES: Set<ActionType> = setOf(
        ActionType.TAP,
        ActionType.LONG_PRESS,
        ActionType.MULTI_TAP,
        ActionType.SWIPE,
    )

    /** v3.0.3 S5：视觉类动作纳入统一工作台，字段可见可改，但 type 不被重映射。 */
    val VISION_TYPES: Set<ActionType> = setOf(
        ActionType.CLICK_IMAGE,
        ActionType.CLICK_TEXT,
        ActionType.CLICK_COLOR,
        ActionType.CLICK_NODE,
        ActionType.CLICK_AI,
    )

    /**
     * 与 [apply] 同一真源：false 时 UI 必须走源码视图或只读。
     *
     * 保持悬浮面板入口 [FloatingEditorLayout] 的既有契约：只认四种简易类型。
     * 视觉类型的可编辑性在**统一工作台（EditorScreen）**侧通过 [isVisionType] 单独开启，
     * 不借本函数外溢破坏悬浮面板的只读保护（S5「三入口收敛」的过渡兼容）。
     */
    fun supportsSimpleEdit(a: Action): Boolean = a.type in SIMPLE_TYPES

    /** 视觉类动作是否由统一工作台以完整档编辑（imageData/condition/sceneRule 可见可改）。 */
    fun isVisionType(a: Action): Boolean = a.type in VISION_TYPES

    /**
     * ADR-075 门禁①（运行时可测单一真源）：任意已存在 Action 都能在统一工作台编辑，
     * 恒 true——不再有「只读场景 → 去源码 JSON」分支。
     *
     * 与 [supportsSimpleEdit]（悬浮面板只读契约，只认四种简易类型）刻意分开：
     * 悬浮面板维持只读保护，统一工作台无条件开放。UI 的列表标签、标题、确定按钮
     * enabled 一律以本函数为锚，测试据此断言「打开任意 CLICK_IMAGE 无只读、确定可用」。
     * 注意：本函数证的是**编辑逻辑可用**，界面确实不弹只读由静态源码门禁 + 真机未验证栏覆盖。
     */
    fun isWorkbenchEditable(a: Action): Boolean = true

    /** 未知类型返回 [KIND_UNSUPPORTED]，不再悄悄映射为点击。视觉类型仍归为不支持（悬浮面板零回归）。 */
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
        // KIND_VISION 不映射 type：视觉动作保持原 type，只改视觉/门控字段。
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
        // ---- v3.0.3 S5 完整档：视觉/门控字段。null = 不改（保真）；非 null = 显式写入 ----
        val imageData: String? = null,
        val imageSimilarity: Int? = null,
        val region: Region? = null,
        val condition: Condition? = null,
        val sceneRule: SceneRule? = null,
    )

    /**
     * 应用编辑。基于 `copy` 保真；类型不支持或 kind 非法时**原样返回**，
     * 绝不构造新 Action、绝不丢字段。
     */
    fun apply(original: Action, patch: Patch): Action {
        val type = typeOf(patch.kind)
        val visionKind = patch.kind == KIND_VISION
        if (type == null && !visionKind) return original
        // 视觉类型由统一工作台显式传 KIND_VISION 放行（工作台已判 isVisionType）；
        // 悬浮面板仍走 supportsSimpleEdit 只读保护，不传 KIND_VISION，行为逐字不变。
        if (!visionKind && !supportsSimpleEdit(original)) return original

        // v2.9.40（P0-01）：原实现四个分支写死 unit=PCT 且把 x/y 钳到 0..100。
        // 打开一个 PX/DP 动作（如 x=540,y=1200）仅改 holdMs 后保存，坐标被截成
        // 100/100 且单位强改成 PCT —— 落点静默报废且无任何提示。改为保留原单位，
        // 上限随单位走：PCT 仍 0..100（零回归），PX/DP 用像素级上限。
        val unit = original.unit
        val maxCoord = when (unit) {
            CoordUnit.PCT -> 100.0
            CoordUnit.PX, CoordUnit.DP -> MAX_PIXEL_COORD
        }
        val px = patch.x.coerceIn(0.0, maxCoord)
        val py = patch.y.coerceIn(0.0, maxCoord)
        val hold = patch.holdMs.coerceIn(0L, 600_000L)
        val dur = patch.durationMs.coerceIn(20L, 600_000L)
        val times = patch.times.coerceAtLeast(1)
        val wait = patch.waitMs.coerceAtLeast(0L)

        // 视觉类型：不重映射 type（保持 CLICK_IMAGE 等原语义），只改通用字段。
        // 坐标字段写入对视觉动作无害（视觉动作按 region/imageData 取落点），但不主动写。
        val edited: Action = if (visionKind) {
            original.copy(desc = patch.desc, waitMs = wait)
        } else when (type) {
            ActionType.LONG_PRESS -> original.copy(
                type = type, desc = patch.desc, unit = unit,
                x = px, y = py, holdMs = hold, times = times, waitMs = wait,
            )
            ActionType.MULTI_TAP -> original.copy(
                type = type, desc = patch.desc, unit = unit,
                x = px, y = py, holdMs = hold.coerceAtMost(60L),
                times = times.coerceAtLeast(2), gapMs = 80L, waitMs = wait,
            )
            ActionType.SWIPE -> original.copy(
                type = type, desc = patch.desc, unit = unit,
                x = px, y = py,
                x2 = patch.x2.coerceIn(0.0, maxCoord),
                y2 = patch.y2.coerceIn(0.0, maxCoord),
                durationMs = dur, times = times, waitMs = wait,
            )
            // 走到此分支时 visionKind=false 且 type!=null（非法的 null 已在顶部 return），
            // 唯一剩余类型是 TAP。用 Elvis 兜底满足编译器可空性推断。
            else -> original.copy(
                type = type ?: ActionType.TAP, desc = patch.desc, unit = unit,
                x = px, y = py, holdMs = hold, times = times, waitMs = wait,
            )
        }

        // v3.0.3 S5：叠加视觉/门控字段，全部以 null 哨兵保真。
        return edited.copy(
            imageData = patch.imageData ?: edited.imageData,
            imageSimilarity = (patch.imageSimilarity ?: edited.imageSimilarity).coerceIn(1, 100),
            region = patch.region ?: edited.region,
            condition = patch.condition ?: edited.condition,
            sceneRule = patch.sceneRule ?: edited.sceneRule,
        )
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
        imageData = a.imageData,
        imageSimilarity = a.imageSimilarity,
        region = a.region,
        condition = a.condition,
        sceneRule = a.sceneRule,
    )
}

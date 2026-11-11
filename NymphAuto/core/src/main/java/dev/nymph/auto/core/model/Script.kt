package dev.nymph.auto.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * NymphAuto 脚本领域模型（core 纯 JVM，无 Android 依赖）。
 *
 * 设计原则：
 * 1. 宽容：所有字段有默认值，缺省可解析（对标自动精灵导入器的"跳过并提示"行为）。
 * 2. 双坐标单位：Pct(0-100 百分比) / Dp(逻辑像素) / Px(绝对像素)。
 * 3. 参考分辨率：refWidth/refHeight 记录制作脚本时的屏幕，执行期按当前屏幕映射
 *    （修复"导入脚本换机错位"问题）。
 */
@Serializable
data class Script(
    val name: String = "未命名",
    val schemaVersion: Int = SCHEMA_VERSION,
    val minVerCode: Int = 0,
    val refWidth: Int = 0,
    val refHeight: Int = 0,
    val refRotation: Int = 0,
    val repeatCount: Int = 1,
    /**
     * 录制起始界面的前台包名（v2.5.0）。被动无障碍录制不会记录「当时在哪个
     * 页面」，回放时若停在不同界面，所有坐标动作都会作用在错误界面上——
     * 这正是「录完的动作回放对不上」的首要原因。编译器写入，回放前校验。
     * 老脚本 / 手写脚本无此字段时为空，行为与 v2.4.0 完全一致。
     */
    val startPackage: String = "",
    val description: String = "",
    val variables: List<VariableDef> = emptyList(),
    val actions: List<Action> = emptyList(),
    val listeners: Listeners = Listeners(),   // 脚本级监听（自动精灵 globalCallbacks/scriptCallbacks）

    /**
     * 模板资源池（v2.7.0 脚本瘦身）。key = `ref:<16hex>`，value = 原始 base64 模板。
     * 保存时 [dev.nymph.auto.core.importer.ScriptCompressor] 把重复的 `imageData`
     * 抽成一条引用，读取时自动还原为完整 base64——运行引擎无需感知。
     * 老脚本无此字段（空 Map），行为与 v2.6.0 完全一致。
     */
    val assets: Map<String, String> = emptyMap(),

    /** 修订号（v2.7.0）：每次经 [dev.nymph.auto.core.store.ScriptRepository] 保存自增。 */
    val revision: Int = 1,

    /**
     * 脚本级中止条件（对标自动精灵 break 中止条件）：每个动作执行前检查，
     * 载荷命中（出现指定图片/文字/颜色/节点或 JS 表达式为真）即中止整个脚本。
     * null = 不设中止条件。导入的 .zjs 无此字段时为 null。
     */
    val abortCondition: Condition? = null,
) {
    /**
     * 含嵌套 children 与监听钩子的总动作数（UI/日志展示口径）。
     *
     * 行分隔 .zjs 脚本常见「3 个顶层 RUN_ACTIONS 组、57 个总动作」的结构，
     * 顶层 actions.size 会让用户误以为只导入了 3 步——展示一律用本口径。
     */
    fun totalActionCount(): Int = countActions(actions)

    private fun countActions(list: List<Action>): Int = list.sumOf { a ->
        1 + countActions(a.children) + countActions(a.listeners.all())
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
enum class ActionType {
    @SerialName("tap") TAP,
    @SerialName("longPress") LONG_PRESS,
    @SerialName("swipe") SWIPE,
    @SerialName("curve") CURVE,
    @SerialName("multiTap") MULTI_TAP,
    @SerialName("multiFinger") MULTI_FINGER,
    @SerialName("replay") REPLAY,
    @SerialName("pinchOut") PINCH_OUT,
    @SerialName("pinchIn") PINCH_IN,
    @SerialName("clickImage") CLICK_IMAGE,
    @SerialName("clickText") CLICK_TEXT,
    @SerialName("clickColor") CLICK_COLOR,
    @SerialName("clickNode") CLICK_NODE,
    @SerialName("clickAi") CLICK_AI,
    @SerialName("key") KEY,
    @SerialName("input") INPUT,
    @SerialName("openApp") OPEN_APP,
    @SerialName("openLink") OPEN_LINK,
    @SerialName("closeApp") CLOSE_APP,
    @SerialName("runActions") RUN_ACTIONS,
    @SerialName("runScript") RUN_SCRIPT,
    @SerialName("runJs") RUN_JS,
    @SerialName("wait") WAIT,
    @SerialName("goto") GOTO,
    @SerialName("stop") STOP,
    @SerialName("setVar") SET_VAR,
    @SerialName("captureToVar") CAPTURE_TO_VAR,
    @SerialName("toast") TOAST,
    @SerialName("varDialog") VAR_DIALOG,
}

@Serializable
enum class CoordUnit { PCT, DP, PX }

@Serializable
data class PathPoint(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val tMs: Long = 0,
)

/** 一个动作。可选字段按 ActionType 取用，其余忽略——宽容模型。 */
@Serializable
data class Action(
    val type: ActionType = ActionType.TAP,
    val desc: String = "",
    val disabled: Boolean = false,
    val label: String = "",           // goto 跳转标识

    // ---- 坐标与手势参数 ----
    val unit: CoordUnit = CoordUnit.PCT,
    val x: Double = 50.0,
    val y: Double = 50.0,
    val x2: Double = 0.0,
    val y2: Double = 0.0,
    val cx: Double = 0.0,
    val cy: Double = 0.0,
    val durationMs: Long = 40,
    val holdMs: Long = 100,
    val gapMs: Long = 80,
    val times: Int = 1,               // 重复次数，0 = 无限
    val timesExp: String = "",        // times 的 __vars 表达式（varType=expression 时保留；空=用 times）
    val fastRepeatMode: Boolean = false, // 快速重复：跳过 repeat 间的 gapMs 延迟（对标自动精灵 fastRepeatMode）
    val waitMs: Long = 500,           // 动作前等待
    val randomWaitMs: Long = 0,       // 等待随机上限（waitMs..waitMs+random）
    val jitterPx: Int = 0,            // 坐标随机抖动
    val path: List<PathPoint> = emptyList(),
    val fingers: List<Finger> = emptyList(),

    // ---- 视觉参数 ----
    val imageData: String = "",       // base64 模板图
    val text: String = "",            // 目标文字（支持 /正则/）
    val color: Long = 0L,
    val colorSimilarity: Int = 90,
    val coordX: Double = -1.0,        // CLICK_COLOR 指定点（百分比；-1=全区域扫描）
    val coordY: Double = -1.0,
    val imageSimilarity: Int = 80,
    val region: Region = Region(),
    val filters: List<ImageFilter> = emptyList(),
    val maxResults: Int = 1,
    val offsetX: Int = 0,
    val offsetY: Int = 0,

    // ---- AI 找目标参数（CLICK_AI / APPEAR_AI；对标自动精灵"AI 识别"）----
    // 文字描述（如"点击右上角的设置按钮"）：本地目标检测模型把描述映射到
    // 可识别类别（COCO 80 类中文别名，见 core.vision.CocoLabels），
    // 命中类别框的中心点即目标坐标。模型缺失/无匹配类别时如实失败归因。
    val aiLabel: String = "",
    val aiThreshold: Int = 50,        // 置信度阈值（百分制）

    // ---- 节点参数 ----
    val nodeId: String = "",
    val nodeClass: String = "",
    val nodeText: String = "",
    val nodeDepthMin: Int = 0,
    val nodeDepthMax: Int = 0,
    val nodeDirectClick: Boolean = false,   // 强制点击（即使被遮挡）

    // ---- 控制/脚本参数 ----
    val keyName: String = "",
    val packageName: String = "",
    val linkUrl: String = "",        // OPEN_LINK：要打开的链接（http/https/market/tel 等）
    val restart: Boolean = false,
    val scriptFile: String = "",
    val returnToCaller: Boolean = true,
    val runArgs: String = "",
    val jsCode: String = "",
    val jsFunc: String = "",
    val order: ListOrder = ListOrder.SEQUENTIAL,
    val limitCount: Int = 0,          // 列表内仅运行前 N 个
    val children: List<Action> = emptyList(),
    val listeners: Listeners = Listeners(),

    // ---- 变量参数 ----
    val varName: String = "",
    val varValue: String = "",
    val varType: VarType = VarType.STRING,
    // 一次性变量（对标自动精灵"执行后删变量"）：动作结束后即删除该变量的
    // 值（声明保留）。用于"赋值只供本次动作的条件/timesExp/JS 消费"的场景。
    val deleteVarAfter: Boolean = false,

    // ---- 提示参数 ----
    val toastStyle: ToastStyle = ToastStyle.TOAST,
    val toastDurationMs: Long = 3000,

    // ---- 运行条件 ----
    val condition: Condition? = null,
    /**
     * v2.9.6：等待目标画面出现再执行。为 true 且动作未显式配置 [condition] 时，
     * 运行引擎按 [dev.nymph.auto.core.video.WaitGate] 生成一条「图片出现」轮询条件，
     * 直到目标出现或脚本中止条件命中——用于阶段时长随机的游戏循环。
     * 老脚本无此字段时为 false，行为与 v2.9.5 完全一致。
     */
    val waitAppear: Boolean = false,
    val conditionFailWaitMs: Long = 100,
    val repeatCheckUntilOk: Boolean = false,
    val repeatCheckIntervalMs: Long = 1000,
    val repeatCheckMax: Int = 0,
    // ---- 后置断言（裁定阶段 3.3）----
    // 拾取时记录的"期望发生什么"：执行体成功后校验，失败归因为"断言失败"
    // （目标 App 界面逻辑变了，需重拾取），区别于"坐标未命中"。
    // 导入的 .zjs 无此字段（为 null）；机制先行，UI 接入后可由用户补录。
    val postCondition: Condition? = null,
)

@Serializable
data class Finger(
    val points: List<PathPoint> = emptyList(),
    val startDelayMs: Long = 0,
)

@Serializable
enum class ListOrder { SEQUENTIAL, PARALLEL, RANDOM }

@Serializable
data class Region(
    val leftPct: Double = 0.0,
    val topPct: Double = 0.0,
    val rightPct: Double = 100.0,
    val bottomPct: Double = 100.0,
    /**
     * v2.7.0：ROI 表达式（见 [dev.nymph.auto.core.vision.RoiExpr]）。
     * 非空时运行时优先按表达式解析，支持 `topHalf` / `10,20,90,80` /
     * `0,0,w*0.3,h` / `cx-r,cy-r,cx+r,cy+r` 等；解析失败自动退回上面四个
     * 数值，绝不因表达式写错让整轮回放崩掉。老脚本无此字段时为空串，
     * 行为与 v2.6.0 完全一致。
     */
    val expr: String = "",
)

@Serializable
enum class VarType { NUMBER, STRING, BOOL, COLOR, IMAGE, REGION, NODE, TEXT, COORD, FILE, DICT, ARRAY_NUM, ARRAY_STR, ARRAY_ACTION }

@Serializable
data class VariableDef(
    val name: String,
    val display: String = "",
    val type: VarType = VarType.STRING,
    val value: String = "",
    val expression: String = "",
    val requireInput: Boolean = false,
    val description: String = "",
)

/** 监听动作：10 个时机钩子。 */
@Serializable
data class Listeners(
    val beforeAction: List<Action> = emptyList(),
    val afterAction: List<Action> = emptyList(),
    val onActionFail: List<Action> = emptyList(),
    val onActionOk: List<Action> = emptyList(),
    val beforeList: List<Action> = emptyList(),
    val afterList: List<Action> = emptyList(),
    val beforeScript: List<Action> = emptyList(),
    val afterScript: List<Action> = emptyList(),
    val beforeConditionCheck: List<Action> = emptyList(),
    val onConditionFail: List<Action> = emptyList(),
) {
    /** 全部 10 个时机的监听动作合并列表（统计口径用）。 */
    fun all(): List<Action> =
        beforeAction + afterAction + onActionFail + onActionOk +
            beforeList + afterList + beforeScript + afterScript +
            beforeConditionCheck + onConditionFail
}

@Serializable
enum class ToastStyle { TOAST, NOTIFICATION, DIALOG }

/** 运行条件（树形：多个条件可组合）。 */
@Serializable
data class Condition(
    val kind: ConditionKind = ConditionKind.APPEAR_IMAGE,
    val invert: Boolean = false,
    // 图片
    val imageData: String = "",
    val imageSimilarity: Int = 80,
    val region: Region = Region(),
    val filters: List<ImageFilter> = emptyList(),
    // 文字
    val text: String = "",
    val textRegex: Boolean = false,
    // 颜色
    val color: Long = 0L,
    val colorSimilarity: Int = 90,
    val coordX: Double = -1.0,
    val coordY: Double = -1.0,
    // 节点
    val nodeId: String = "",
    val nodeClass: String = "",
    val nodeText: String = "",
    val nodeDepthMin: Int = 0,
    val nodeDepthMax: Int = 0,
    // JS 表达式
    val jsExpr: String = "",
    // 随机概率 0-100
    val randomPercent: Int = 100,
    // 时间间隔：满足后 intervalMs 内不再满足
    val intervalMs: Long = 0,
    // 动作运行次数 < limit
    val runCountLimit: Int = 0,
    val countOnlySuccess: Boolean = false,
    // 组合条件
    val combine: Combine = Combine.ALL,
    val children: List<Condition> = emptyList(),
    // 时间门：系统时间 >= afterTimeMs（epoch 毫秒）才满足（对标自动精灵 timeAfter 条件）
    val afterTimeMs: Long = 0,
    // AI 找目标：文字描述（"点击右上角的设置按钮"）映射到检测类别后出现即满足
    val aiLabel: String = "",
    val aiThreshold: Int = 50,
    // 重复检查
    val repeatCheckUntilOk: Boolean = false,
    val repeatCheckIntervalMs: Long = 1000,
    val repeatCheckMax: Int = 0,
)

@Serializable
enum class ConditionKind {
    APPEAR_IMAGE, APPEAR_TEXT, APPEAR_COLOR, APPEAR_NODE,
    JS_EXPR, RANDOM, INTERVAL, RUN_COUNT, COMBINE, AFTER_TIME,
    /** AI 找目标条件：文字描述对应类别的物体出现在屏幕上即满足。 */
    APPEAR_AI,
    /** 中止条件（对标自动精灵 break）：载荷命中即中止整个脚本。 */
    ABORT,
}

@Serializable
enum class Combine { ALL, ANY, N }

/** 图像滤镜链（按序叠加，对标自动精灵 8 种滤镜）。 */
@Serializable
data class ImageFilter(
    val kind: FilterKind = FilterKind.GRAY,
    val r: Int = 0, val g: Int = 0, val b: Int = 0,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val threshold: Int = 127,
    val fromColor: Long = 0L,
    val toColor: Long = 0L,
)

@Serializable
enum class FilterKind { RGB, BRIGHTNESS, CONTRAST, GRAY, BLACK_WHITE, INVERT, COLOR_REPLACE, CONTOUR }

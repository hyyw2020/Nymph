// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.model

import kotlinx.serialization.Serializable

/**
 * **场景规则落盘格式 —— 三处「一旦落盘就不可逆」语义的单一真源**（v2.9.15，core 纯 JVM）。
 *
 * ## 为什么这个文件必须现在定死
 *
 * 判断分界线只有一条：**是否落盘锁定**。以下三处的形状一旦写进存量脚本，
 * 以后再改就要么重录、要么维护两套语义 forever，所以必须在发版前一次定死，
 * 哪怕运行时能力分两步启用也先把字段与含义钉牢（缺省值走老逻辑，成本≈0）：
 *
 *  1. **模板结构（A1）**：`Condition.imageData` 里存的是「稳定局部锚点小图」还是
 *     「整块 ROI」。v2.9.14 存整块 ROI —— 点击会改变 ROI 内容 → 判消失 → 稍恢复
 *     判再现 → 边沿再点火，这是**自激振荡**的根因。本版起 `imageData` = 稳定锚点，
 *     [searchRoi] 退回为「搜索范围」，[maskRects] 记录被剔除的高方差（易变）区域。
 *  2. **坐标语义（A2）**：点击落点是「记录的绝对坐标」还是「匹配位置 + 相对偏移」。
 *     [clickOffsetXPct]/[clickOffsetYPct] 为 null = 老绝对坐标（零回归）；非 null =
 *     相对匹配坐标。缺省 null，语义一次定死，绝不维护两套 forever。
 *  3. **触发语义（A3）**：[planSceneFiring] 的输入输出形状，以及本类承载的
 *     triggerMode / 迟滞 / cooldown / retry / 仲裁字段。带状态纯函数的形状现在定死，
 *     未来只加带默认值字段，不改已有含义。
 *
 * ## 兼容性（零回归硬约束）
 *
 * 全部字段带默认值；`Action.sceneRule: SceneRule? = null` 缺省即「老格式 / 顺序链」，
 * 压缩容器 `encodeDefaults=false` 连 null 都不写，v2.9.14 及更早脚本落盘逐字不变。
 * 老 v2.9.14 过渡格式（整块 ROI 模板 + 绝对坐标 + 布尔边沿 + fail-open 降级）
 * 仍能被 `sceneDriven` 分流并按旧语义兼容执行，但**不再新产出**。
 *
 * ## 能力边界（未验证项）
 *
 * 本类只定义**格式与形状**，不含识别引擎、不含运行时自适应阈值（无真机数据前
 * 引入不可解释行为）、不含视觉 DSL / OCR 主链路。稳定锚点如何选、掩码如何生成由
 * `core.vision.StableAnchor` 的纯函数负责；触发如何调度由 `core.editor.SceneTrigger`
 * 的纯函数负责。
 */

/** 触发边沿/电平语义。默认 [ON_APPEAR] = v2.9.14 的「从无到有」布尔边沿，逐字零回归。 */
@Serializable
enum class TriggerMode {
    /** 场景从「不在场」变为「在场」时触发一次（默认，等价旧布尔边沿）。 */
    ON_APPEAR,

    /** 场景在场期间每轮都可触发（受 cooldownMs / 同轮 K 上限约束）。 */
    WHILE_PRESENT,

    /** 场景从「在场」变为「不在场」时触发一次。 */
    ON_DISAPPEAR,
}

/**
 * 动态区域掩码的一个矩形（屏幕百分比，0~100）。
 * 用于匹配副本里剔除「高方差 / 点击后易变」的区域（倒计时数字、动效、波纹…），
 * 避免这些像素把 NCC 拉低造成「自己制造消失」。
 */
@Serializable
data class MaskRect(
    val leftPct: Double = 0.0,
    val topPct: Double = 0.0,
    val rightPct: Double = 0.0,
    val bottomPct: Double = 0.0,
)

/**
 * 一条场景规则（挂到 `Action.sceneRule`）。字段按 A1/A2/A3 三组落盘锁定项组织。
 * 所有字段带默认值 → 老脚本 / 手写脚本 / 导入脚本零回归。
 */
@Serializable
data class SceneRule(
    // ------------------------------------------------ A1 模板结构
    /** ROI 退回「搜索范围」：NCC 只在这个区域内找锚点，不再当作模板内容。 */
    val searchRoi: Region = Region(),
    /** 拾取瞬间「稳定局部锚点」在屏幕上的位置（百分比），供二次校验与相对偏移换算。 */
    val anchorRectPct: Region = Region(),
    /** 动态区域掩码：匹配副本里被剔除的高方差块（屏幕百分比）。 */
    val maskRects: List<MaskRect> = emptyList(),
    /** 锚点模板的像素宽高（templateSrcSize 的一部分，用于跨分辨率尺度换算）。 */
    val anchorTemplateW: Int = 0,
    val anchorTemplateH: Int = 0,
    /** 拾取时屏幕像素尺寸（templateSrcSize），横竖屏 / 异形屏换算基准。 */
    val templateSrcW: Int = 0,
    val templateSrcH: Int = 0,
    /**
     * 拾取时屏幕方向（Surface.ROTATION_*，0/1/2/3）。v2.9.32 方向无关执行。
     * 缺省 0 = 老脚本未记录，运行时退化为「按 templateSrc 宽高比推断」。
     * 运行期据此把当前帧旋回拾取几何做匹配，把落点旋回当前屏幕注入。
     */
    val pickRotation: Int = 0,

    // ------------------------------------------------ A2 坐标语义
    /**
     * 拾取点相对「锚点匹配中心」的偏移（屏幕百分比）。
     * - null（默认）= 走老绝对坐标 `Action.x/y`，零回归；
     * - 非 null = 运行时用 `findTemplate()` 返回的匹配中心 + 本偏移算真实落点。
     * 定义成「相对中心」而非「相对左上角」：匹配中心比左上角更抗多尺度缩放误差。
     */
    val clickOffsetXPct: Double? = null,
    val clickOffsetYPct: Double? = null,
    /** 二次校验阈值：匹配中心偏离「记录锚点中心」超过该百分比 → 判「场景疑似错位」不点。 */
    val locMaxDeviationPct: Double = DEFAULT_LOC_MAX_DEVIATION_PCT,

    // ------------------------------------------------ A3 触发语义
    val triggerMode: TriggerMode = TriggerMode.ON_APPEAR,
    /** 迟滞：连续 N 帧命中才判「在场」，滤掉单帧闪烁。默认 1 = 不迟滞（旧行为）。 */
    val presentStreak: Int = 1,
    /** 迟滞：连续 M 帧未命中才判「离场」。默认 1 = 不迟滞（旧行为）。 */
    val absentStreak: Int = 1,
    /** 触发后冷却（毫秒），抑制「自己制造消失」后的连点。0 = 不冷却。 */
    val cooldownMs: Long = 0L,
    /**
     * EDGE 触发后场景未消失多久重试（毫秒），修复「触发后场景持续在场导致无新上升沿」P0 缺陷。
     * 0 = 关闭（旧行为）；>0 时若命中后场景一直在场且超过本值，允许再次触发。
     */
    val retryTimeoutMs: Long = 0L,
    /** 冲突仲裁优先级（越大越先）。默认 0 保兼容。 */
    val priority: Int = 0,
    /** 同组同轮只取优先级最高的一条；null = 不分组（保兼容）。 */
    val mutexGroup: String? = null,
    /** 该规则的相似度阈值（拾取时自校准后落盘，默认 82）。 */
    val similarity: Int = 82,

    // ------------------------------------------------ A5 可观测性（拾取即自测结果，落盘供诊断复现）
    /** 拾取即自测：全屏扫描最高命中分（0~1）。-1 = 未测（老格式）。 */
    val selfTestBest: Double = -1.0,
    /** 拾取即自测：ROI 之外最高误匹配分（次高干扰分，0~1）。-1 = 未测。 */
    val selfTestRunnerUp: Double = -1.0,
    /** 拾取即自测：命中位置与拾取点的偏差（屏幕百分比）。-1 = 未测。 */
    val selfTestLocDeviationPct: Double = -1.0,

    // ------------------------------------------------ A6 装配-运行同源证据（v2.9.31）
    /**
     * 拾取点邻域代表色（ARGB）。**0 = 未采集**（有效 ARGB 高位恒为 0xFF）。
     * v2.9.30 及更早：该证据只落在 `Action.color`，运行期场景判定链不消费，
     * 造成「界面显示 PASS、运行期要求另一套阈值」的装配-运行偏斜。本版把证据
     * 显式提升到场景规则内，运行期与模板共用同一份判定输入。
     */
    val colorArgb: Int = 0,
    /** 颜色容差（亮度加权 RGB 距离 0~255 口径）。0 = 未启用。 */
    val colorTolerance: Int = 0,
    /** 依据邻域离散度收敛的有效点击区域（屏幕百分比）。null = 无收敛区域。 */
    val convergedRoi: Region? = null,
    /** 装配期多帧 NCC 最低值（0~1），阈值校准的跨帧上界约束。-1 = 未测（老脚本）。 */
    val crossFrameFloor: Double = -1.0,
    /** 分层判据要求的连续「疑似在场」帧数 N。默认 3，落盘可调。 */
    val evidenceStreak: Int = 3,

    // ------------------------------------------------ A7 无障碍节点 fallback（v2.9.44）
    /**
     * 锚点位置的无障碍节点语义（拾取瞬间捕获，text 或 contentDescription）。
     * **空 = 未采集 / 该锚点无语义节点（纯图像、游戏、桌面色块）→ 不启用节点 fallback。**
     * 用途：系统录屏占用无障碍截图通道时，图像 NCC 拿不到帧，改以「该语义节点是否
     * 出现在节点树」作为等价的「目标画面出现」判据——节点树 `rootInActiveWindow`
     * 不受录屏影响、全程零截图。老脚本缺此字段默认空，零回归。
     */
    val nodeText: String = "",
    /** 锚点节点的 viewIdResourceName。空 = 未采集。 */
    val nodeViewId: String = "",
    /** 锚点节点的 className。空 = 未采集。 */
    val nodeClass: String = "",
    /**
     * 是否启用节点 fallback。仅当装配期确认锚点命中了「有判别度的语义节点」才置 true。
     * 默认 false = 老脚本 / 无节点界面，运行期完全走原 NCC 主链，零回归。
     */
    val nodeFallbackEnabled: Boolean = false,
) {
    /** 是否启用了「相对匹配坐标」新语义。 */
    val usesRelativeClick: Boolean get() = clickOffsetXPct != null && clickOffsetYPct != null

    companion object {
        const val DEFAULT_LOC_MAX_DEVIATION_PCT = 15.0
        /** 拾取即自测 / 阈值自校准的相似度基线（百分制）。 */
        const val SIMILARITY_BASELINE = 82
        /** 阈值自校准：在最佳干扰分之上留出的安全余量（0~1 分数制）。 */
        const val CALIBRATION_MARGIN = 0.08
    }
}

/**
 * 本轮一条规则的**原始观测**（未经迟滞/触发判定）。由 app 层一轮一帧批量匹配产生。
 * 纯数据，供状态机纯函数消费，可单测。
 */
data class SceneObservation(
    /** 本帧模板是否在搜索 ROI 内命中（分数 >= 阈值）。 */
    val present: Boolean,
    /** 本帧最佳 NCC 分数（0~1，未命中也保留用于可观测日志）。-1 = 无有效观测。 */
    val score: Double = -1.0,
    /** 命中中心（原图像素）。未命中为 -1。 */
    val cxPx: Int = -1,
    val cyPx: Int = -1,
    /** 匹配副本宽高的屏幕尺寸（把像素换算成百分比用）。 */
    val frameW: Int = 0,
    val frameH: Int = 0,
    /**
     * 本帧是否来自一次有效的画面/节点观测（v2.9.46）。
     *
     * false = 截图失败 / 几何错配 / 通道不可用。此时 [present=false] 只表示「没看到」，
     * 不代表「场景真的离场」。`planSceneFiringStateful` 对无效帧冻结状态机：不推进
     * hit/missStreak、不改变在场状态、不产生任何触发边沿——因此 ON_DISAPPEAR 不会
     * 在连续截图失败时误判离场。默认 true 保证存量位置参数构造点与 v2.9.14 兼容
     * 入口 [SceneTrigger.planSceneFiring] 逐字零回归。
     */
    val valid: Boolean = true,
)

/**
 * [SceneTrigger.planSceneFiringStateful] 的**状态**（不落盘，每次运行内累积）。
 * 形状现在定死：present 集合 / 命中连续帧 / 未命中连续帧 / 上次触发时刻 / 是否曾触发。
 */
data class SceneFiringState(
    /** 经迟滞后判定为「在场」的规则下标集合。 */
    val present: Set<Int> = emptySet(),
    val hitStreak: Map<Int, Int> = emptyMap(),
    val missStreak: Map<Int, Int> = emptyMap(),
    /** 上次触发时刻（elapsedRealtime 毫秒基准，纯函数由入参 nowMs 提供）。 */
    val lastFireAtMs: Map<Int, Long> = emptyMap(),
    /** 已经触发过至少一次（ON_APPEAR 边沿与 retry 复用）。 */
    val everFired: Set<Int> = emptySet(),
)

/** 一次场景调度决策。 */
data class SceneFiringDecision(
    /** 本轮最终要触发的规则下标（已过迟滞/cooldown/retry/仲裁/K 上限）。 */
    val fire: List<Int>,
    /** 供下一轮传入的新状态。 */
    val next: SceneFiringState,
    /** 可观测：每条规则本轮「触发/不触发」的可读原因（日志/调试覆盖层用）。 */
    val reasons: Map<Int, String> = emptyMap(),
)

/** 相对坐标解析结果（A2）。 */
sealed interface SceneClickResolution {
    /** 落点（屏幕百分比）。 */
    data class Hit(val xPct: Double, val yPct: Double, val via: String) : SceneClickResolution

    /** 采用老绝对坐标（clickOffset 缺省）。 */
    data class Absolute(val xPct: Double, val yPct: Double) : SceneClickResolution

    /** 匹配位置偏离预期，判「场景疑似错位」，本次不点。 */
    data class SuspiciousMismatch(val deviationPct: Double, val maxPct: Double) : SceneClickResolution

    /**
     * v2.9.31：相对坐标语义下本轮无有效匹配中心、且没有可沿用的上次有效落点。
     * 旧实现此时回退到 `Action.x/y` 默认 50/50，等于把点击派到屏幕正中央 ——
     * 真机日志已确证该退化（重试分支 `loc=-` → `TAP @ 50.0%,50.0%`）。
     * 新语义：不派发，显式报「本轮无有效落点」，由调用方记 skip。
     */
    data class NoValidLocation(val detail: String) : SceneClickResolution
}

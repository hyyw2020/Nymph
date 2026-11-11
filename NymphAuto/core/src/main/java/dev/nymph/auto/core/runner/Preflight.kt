package dev.nymph.auto.core.runner

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.model.TargetPackages

/**
 * 脚本依赖清单（裁定阶段 2）——落库一份，列表页直显 + 预检逐项比对。
 *
 * 例："依赖：无障碍 · 截图 · 1080×2400 · 竖屏 · 支付宝 v10.3"
 */
data class ScriptDeps(
    /** 脚本打开/关闭的目标包名（OPEN_APP/CLOSE_APP 动作收集，去重排序）。 */
    val packages: List<String> = emptyList(),
    /** 是否依赖截图通道（含图片模板/图片条件/文字条件任一步即依赖）。 */
    val needsScreenshot: Boolean = false,
    val refWidth: Int = 0,
    val refHeight: Int = 0,
    val refRotation: Int = 0,
    /** 脚本含图片步骤的总数（UI 展示"其中 N 步依赖截图"）。 */
    val imageStepCount: Int = 0,
    /** 是否含 AI 找目标步骤（CLICK_AI / APPEAR_AI）。 */
    val needsAi: Boolean = false,
    /** AI 步骤数。 */
    val aiStepCount: Int = 0,
) {
    /** 列表页一行依赖描述。 */
    fun oneLine(): String = buildList {
        add("无障碍")
        if (needsScreenshot) add("截图")
        if (needsAi) add("AI找目标")
        if (refWidth > 0 && refHeight > 0) add("${refWidth}×$refHeight")
        if (refRotation != 0) add(if (refRotation == 1 || refRotation == 3) "横屏" else "竖屏")
        packages.forEach { add(it) }
    }.joinToString(" · ")
}

/** 从脚本推导依赖清单（递归 children 与监听动作）。 */
fun Script.dependencies(): ScriptDeps {
    val packages = linkedSetOf<String>()
    var imageSteps = 0
    var aiSteps = 0
    fun walk(a: Action) {
        when (a.type) {
            ActionType.OPEN_APP, ActionType.CLOSE_APP ->
                // 系统组件包（桌面/系统 UI）无需启动、也不该参与安装预检：
                // v1.2.1 曾因把它们当目标应用而在预检里误拦整个录制脚本。
                if (TargetPackages.isLaunchableTarget(a.packageName)) packages += a.packageName
            else -> Unit
        }
        if (a.type == ActionType.CLICK_IMAGE ||
            a.type == ActionType.CLICK_TEXT ||
            a.condition?.kind == ConditionKind.APPEAR_IMAGE ||
            a.condition?.kind == ConditionKind.APPEAR_TEXT ||
            a.postCondition?.kind == ConditionKind.APPEAR_IMAGE ||
            a.postCondition?.kind == ConditionKind.APPEAR_TEXT
        ) {
            imageSteps++
        }
        // AI 找目标步骤：依赖本地检测模型（含截图抓帧，一并计入截图依赖）
        if (a.type == ActionType.CLICK_AI ||
            a.condition?.kind == ConditionKind.APPEAR_AI ||
            a.postCondition?.kind == ConditionKind.APPEAR_AI
        ) {
            aiSteps++
            imageSteps++
        }
        a.children.forEach { walk(it) }
        a.listeners.all().forEach { walk(it) }
    }
    actions.forEach { walk(it) }
    return ScriptDeps(
        packages = packages.toList(),
        needsScreenshot = imageSteps > 0,
        refWidth = refWidth,
        refHeight = refHeight,
        refRotation = refRotation,
        imageStepCount = imageSteps,
        needsAi = aiSteps > 0,
        aiStepCount = aiSteps,
    )
}

/** 运行环境快照（app 层探测后注入）。 */
data class EnvironmentSnapshot(
    val a11yOnline: Boolean = false,
    val screenshotSupported: Boolean = false,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val screenRotation: Int = 0,
    /** 已安装包名集合（预检只查集合包含，不查版本号——版本号查询成本高且易误报）。 */
    val installedPackages: Set<String> = emptySet(),
    /** AAPM 状态（由 CapabilityMatrix 先行判定后传入，避免重复探测）。 */
    val aapmEnabled: Boolean = false,
    /** AI 通道可用性（目标检测模型 + TFLite 运行时就绪）。 */
    val aiSupported: Boolean = false,
)

/** 预检报告：OK 或带具体差异的拦截原因列表。 */
data class PreflightReport(
    val ok: Boolean,
    val problems: List<String> = emptyList(),
) {
    /** 拦截时的一句话说明（含全部差异）。 */
    fun explain(): String = if (ok) "预检通过" else "预检未通过：" + problems.joinToString("；")
}

/**
 * 预检（裁定阶段 3.1）——把"回放失败才发现"提前到"跑之前就知道"。
 *
 * 逐项比对：无障碍 / AAPM / 截图能力 / 屏幕方向与宽高比 / 目标包已安装。
 * 任一项不匹配即拦截并说明差在哪，绝不静默放行。
 */
object Preflight {

    fun check(script: Script, env: EnvironmentSnapshot): PreflightReport {
        val problems = mutableListOf<String>()
        val deps = script.dependencies()
        if (!env.a11yOnline) problems += "无障碍服务未开启"
        if (env.aapmEnabled) problems += "AAPM 已开启（自动化工具无豁免）"
        if (deps.needsScreenshot && !env.screenshotSupported) {
            problems += "脚本有 $deps.imageStepCount 步依赖截图，但当前系统不支持（需 Android 11+）"
        }
        if (deps.refWidth > 0 && deps.refHeight > 0 && env.screenWidth > 0 && env.screenHeight > 0) {
            val refPortrait = deps.refHeight > deps.refWidth
            val curPortrait = env.screenHeight > env.screenWidth
            if (refPortrait != curPortrait) {
                problems += "屏幕方向与脚本相反（脚本 ${deps.refWidth}×${deps.refHeight}，" +
                    "当前 ${env.screenWidth}×${env.screenHeight}）"
            } else {
                val refRatio = deps.refWidth.toDouble() / deps.refHeight
                val curRatio = env.screenWidth.toDouble() / env.screenHeight
                if (kotlin.math.abs(refRatio - curRatio) / refRatio > 0.25) {
                    problems += "屏幕宽高比差异较大（脚本 ${deps.refWidth}×${deps.refHeight}，" +
                        "当前 ${env.screenWidth}×${env.screenHeight}），图片匹配大概率失败"
                }
            }
        }
        val missing = deps.packages.filter { it !in env.installedPackages }
        if (missing.isNotEmpty()) problems += "目标应用未安装：${missing.joinToString("、")}"
        // AI 找目标步骤需要本地检测模型：不可用就明说，别让脚本跑到那步才失败
        if (deps.needsAi && !env.aiSupported) {
            problems += "脚本有 $deps.aiStepCount 步 AI 找目标，但本地检测模型不可用" +
                "（可改用图片/文字/节点类步骤）"
        }
        return PreflightReport(problems.isEmpty(), problems)
    }
}

/**
 * 失败四类归因（裁定阶段 4）——失败提示按类给出对应动作。
 *
 * 坐标/断言失败 → 定点重拾取；等待超时 → 改等待条件；通道掉线 → 重连；
 * 环境不匹配 → 预检已拦截，给出具体差异。
 */
enum class FailReason(val label: String, val action: String) {
    COORD_MISS("坐标未命中", "重试该步；持续失败请定点重拾取（对照旧快照重新选点）"),
    ASSERT_FAIL("断言失败", "坐标命中但结果不符：目标 App 界面逻辑已变，需重拾取该步"),
    WAIT_TIMEOUT("等待超时", "修改该步的等待条件或超时时长"),
    CHANNEL_LOST("通道掉线", "无障碍/注入通道掉线，请重连后从失败步续跑"),
    ENV_MISMATCH("环境不匹配", "按预检给出的差异项修正环境（屏幕/方向/目标应用）"),
    ABORT_HIT("中止条件命中", "属预期内中止：确认中止条件是否符合预期，或调整后重跑"),
    AI_NO_MATCH("AI 未识别到目标", "确认描述能映射到离线模型类别（COCO 80 类），或改回图片/文字拾取"),
    AI_UNAVAILABLE("AI 通道不可用", "目标检测模型或运行环境缺失：改用图片/文字/节点类动作"),
    ;

    companion object {
        /** 归因描述 + 建议动作（UI 展示用）。 */
        fun adviceOf(r: FailReason): String = "${r.label}：${r.action}"
    }
}

/**
 * 漂移预算（裁定阶段 3.4 的"漂移预算要有上界"）——闭环修正不能无限容忍。
 *
 * 单次修正超过 [maxSinglePx] 或累计漂移超过 [maxCumulativePx] → 判定"布局已变"，
 * 直接失败引导重拾取，而不是悄悄挪 20px 继续点（静默大幅修正比报错更危险）。
 */
class DriftTracker(
    private val maxSinglePx: Double = 8.0,
    private val maxCumulativePx: Double = 40.0,
) {
    /** 累计漂移量（各锚点绝对值之和，回放期间只增不减）。 */
    var cumulative: Double = 0.0
        private set

    /** 记录一次锚点修正；返回 null=在预算内，非 null=超限原因。 */
    fun record(dx: Double, dy: Double): String? {
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        cumulative += d
        return when {
            d > maxSinglePx -> "单次修正 ${"%.1f".format(d)}px 超过预算 ${maxSinglePx}px，判定布局已变"
            cumulative > maxCumulativePx -> "累计漂移 ${"%.1f".format(cumulative)}px 超过预算 ${maxCumulativePx}px，判定布局已变"
            else -> null
        }
    }

    fun reset() {
        cumulative = 0.0
    }
}

/**
 * 时间抖动采样器（裁定阶段 3.2 的"毫秒级扰动，对数正态或韦伯分布"）。
 *
 * 固定节奏回放用：间隔 ±10–30ms、DOWN-UP ±5–15ms 量级。
 * 对数正态（Box-Muller 变换）比均匀分布更接近人类操作间隔的右偏形态。
 * 可复现：同种子同序列，调试可一键关闭（sigma=0 时退化为常量）。
 */
class JitterSampler(seed: Long = 0x5EEDL) {
    private var hasSpare = false
    private var spare = 0.0
    private val rng = java.util.Random(seed)

    /** 以 baseMs 为中位数（对数正态中位数）、sigma 为对数标准差采样一次。 */
    fun intervalMs(baseMs: Long, sigma: Double = DEFAULT_SIGMA): Long {
        if (sigma <= 0.0) return baseMs.coerceAtLeast(1L)
        val normal = nextGaussian()
        // ln(base) + sigma*N 的对数正态；中位数 = base，约 68% 落在 base*e^±sigma
        val sampled = kotlin.math.ln(baseMs.coerceAtLeast(1).toDouble()) + sigma * normal
        val value = kotlin.math.exp(sampled)
        // v2.9.4：极端 sigma / base 会让 exp 溢出成 Infinity，Double.toLong()
        // 饱和成 Long.MAX_VALUE，coerceAtLeast(1) 挡不住 —— 脚本将卡在
        // delay(Long.MAX_VALUE) 永不结束。上限至少 1 小时且不低于基准 32 倍，
        // 正常分布（sigma=0.06）完全不受影响，异常时回退到有限可结束的值。
        val cap = maxOf(3_600_000.0, baseMs.coerceAtLeast(1L).toDouble() * 32.0)
            .coerceAtMost(Long.MAX_VALUE.toDouble() / 2.0)
        if (!value.isFinite() || value >= cap) return cap.toLong()
        return value.toLong().coerceAtLeast(1L)
    }

    /**
     * ±ratio 范围内的均匀抖动（用于 DOWN-UP 间的小扰动）。
     *
     * 兼容性：这里**刻意不用** `java.util.Random.nextLong(origin, bound)`——该重载
     * 是 Java 17 / Android API 33 才加入的，而本工程 minSdk 26 且未启用 core library
     * desugaring，源码用 Java 17 编译能通过，但在 API 26–32 设备上回放会抛
     * `NoSuchMethodError`（v1.2.2 的 dex 反汇编已确认该调用真实存在）。
     * 改用全 API 可用的 [java.util.Random.nextDouble] 生成 [-span, span] 均匀整数，
     * 分布与取值范围不变，同种子仍可复现。
     */
    fun uniformDeltaMs(baseMs: Long, ratio: Double = DEFAULT_RATIO): Long {
        if (ratio <= 0.0) return baseMs.coerceAtLeast(1L)
        // v2.9.4：极端 baseMs / ratio 会让 span 溢出成负数或 Long.MAX_VALUE，
        // 进而 baseMs + delta 变负，产生「负延时」。正常取值路径保持原样。
        val rawSpan = baseMs.toDouble() * ratio
        val span = if (!rawSpan.isFinite() || rawSpan >= Long.MAX_VALUE / 4.0) {
            Long.MAX_VALUE / 4
        } else {
            rawSpan.toLong().coerceAtLeast(1L)
        }
        val range = 2.0 * span + 1.0
        val delta = (rng.nextDouble() * range).toLong() - span
        val sum = baseMs.toDouble() + delta.toDouble()
        return if (!sum.isFinite() || sum < 0.0 || sum > Long.MAX_VALUE.toDouble()) {
            baseMs.coerceAtLeast(1L)
        } else {
            sum.toLong()
        }
    }

    private fun nextGaussian(): Double {
        if (hasSpare) {
            hasSpare = false
            return spare
        }
        val u = 1.0 - rng.nextDouble()
        val v = rng.nextDouble()
        val mag = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u))
        spare = mag * kotlin.math.sin(2.0 * kotlin.math.PI * v)
        hasSpare = true
        return mag * kotlin.math.cos(2.0 * kotlin.math.PI * v)
    }

    companion object {
        /** 间隔对数正态 sigma：e^0.06 ≈ ±6%，落在 ±10–30ms 量级（裁定区间）。 */
        const val DEFAULT_SIGMA = 0.06
        /** DOWN-UP 均匀抖动比例 ±10%（裁定区间 5–15ms 对 100ms 基准即 ±5–15%）。 */
        const val DEFAULT_RATIO = 0.10
    }
}

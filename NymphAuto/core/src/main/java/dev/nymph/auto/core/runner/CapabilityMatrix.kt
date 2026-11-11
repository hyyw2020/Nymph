package dev.nymph.auto.core.runner

/**
 * 能力矩阵档位（裁定阶段 0）——决定"这条脚本能跑成什么样"。
 *
 * 四档（与裁定一一对应）：
 *  - FULL      无障碍 + 截图可用，非 AAPM → 精确拾取 + 形态随机 + 检查点闭环
 *  - DEGRADED  无障碍在线但截图不可用（API<30 / 截图持续失败）→ 只保证点中，
 *              图片类条件退化为节点通道，闭环不可用
 *  - RESTRICTED 命中 secure 窗口或 accessibilityDataSensitive 页面 →
 *              坐标可跑，语义吸附/命中预览/闭环/断言全部不可用
 *  - UNAVAILABLE AAPM 开启（或无无障碍）→ 自动化永久无豁免，诚实告知
 */
enum class CapabilityTier {
    FULL,
    DEGRADED,
    RESTRICTED,
    UNAVAILABLE,
}

/** 能力探测快照（app 层探测后注入，core 保持纯逻辑可测）。 */
data class CapabilitySnapshot(
    val a11yOnline: Boolean = false,
    val aapmEnabled: Boolean = false,
    val screenshotSupported: Boolean = false,      // API 30+
    val secureWindowDetected: Boolean = false,     // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
    val sensitivePageDetected: Boolean = false,    // accessibilityDataSensitive 节点存在
    val shizukuOnline: Boolean = false,            // 预留：接入 Shizuku 后置 true
)

/**
 * 能力矩阵：快照 → 档位 + 人话限制说明。
 *
 * 判定优先级（从严）：AAPM > 无无障碍 > secure/sensitive > 截图不可用 > 全功能。
 * 注意 RESTRICTED 与 DEGRADED 是正交的两个维度，但同时出现时 RESTRICTED 优先
 * （语义通道失效比缺截图更本质）。
 */
object CapabilityMatrix {

    fun tierOf(s: CapabilitySnapshot): CapabilityTier = when {
        s.aapmEnabled -> CapabilityTier.UNAVAILABLE
        !s.a11yOnline -> CapabilityTier.UNAVAILABLE
        s.secureWindowDetected || s.sensitivePageDetected -> CapabilityTier.RESTRICTED
        !s.screenshotSupported -> CapabilityTier.DEGRADED
        else -> CapabilityTier.FULL
    }

    /** 档位的人话说明（拾取/回放界面顶部明示用）。 */
    fun describe(tier: CapabilityTier): String = when (tier) {
        CapabilityTier.FULL -> "全功能：精确匹配 + 形态随机 + 检查点闭环可用"
        CapabilityTier.DEGRADED -> "降级：截图不可用，图片匹配退化，仅保证坐标点中"
        CapabilityTier.RESTRICTED -> "受限：命中安全/敏感页面，语义吸附与闭环不可用，只能按坐标执行"
        CapabilityTier.UNAVAILABLE -> "不可用：AAPM 已开启（或无无障碍服务），自动化工具无豁免"
    }

    /** 该档位下检查点闭环是否可用（裁定：受限档不承诺闭环）。 */
    fun closedLoopUsable(tier: CapabilityTier): Boolean = tier == CapabilityTier.FULL

    /** 该档位下语义通道（节点/命中预览/断言）是否可用。 */
    fun semanticUsable(tier: CapabilityTier): Boolean =
        tier == CapabilityTier.FULL || tier == CapabilityTier.DEGRADED
}

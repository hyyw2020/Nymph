// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

/**
 * 降级通道就绪判定（v2.9.30，core 纯 JVM 单一真源）。
 *
 * ## 为什么要在「还没断」的时候先判
 *
 * 感知方式切换（`SWITCHING`）只在主通道被动丢失时发生。上一版把「降级通道是否
 * 可用」的检查放在真正丢失之后——于是用户的体验是：脚本跑到一半突然停住，
 * 界面提示「正在切换」，几十秒后变成「切换失败，已停止」。而失败原因（系统设置里
 * 无障碍服务根本没启用、或本应用内未允许）在断线前就是已知的，只是没人去看。
 *
 * 本契约把这件事提前：**在持有连接期间就持续给出就绪结论**，状态卡常驻显示
 * 「降级通道未就绪」及具体原因。用户因此能在断线之前把开关打开，而不是在
 * 已经停下来之后才被要求去设置里翻。
 *
 * ## 为什么不能由 App 自己打开
 *
 * Android 不允许应用直接启用无障碍服务：`ENABLED_ACCESSIBILITY_SERVICES` 属
 * 安全设置，写入需要 `WRITE_SECURE_SETTINGS`（普通应用不可得，且系统会拒绝）。
 * Android 13 起对侧载应用另有「受限设置」（restricted settings）闸门，即使用户
 * 在设置页点开关，也可能先被要求解除限制。因此本应用只做**探测 + 指路**，
 * 不做任何写入尝试——那既失败又会污染日志，让用户以为是权限问题。
 *
 * 纯函数：状态由调用方采集后传入，本对象不读 Settings、不持上下文。
 */
object DegradeChannel {

    /** 降级通道就绪度。 */
    enum class Readiness {
        /** 已就绪：主通道丢失时可切。 */
        READY,

        /** 系统设置中未启用本应用的无障碍服务。 */
        SERVICE_DISABLED,

        /** 本应用内明确不允许使用无障碍通道（用户此前拒绝）。 */
        NOT_ALLOWED,

        /** 系统层面不可用（无无障碍能力 / 被高级保护模式拦截）。 */
        UNAVAILABLE,
    }

    /**
     * 判定。
     *
     * @param a11yServiceEnabled 系统设置中该服务是否处于启用状态。
     * @param appAllowed 本应用内是否允许使用无障碍通道（用户授权位）。
     * @param systemCapable 系统是否具备无障碍能力（极旧版本 / AAPM 拦截时为 false）。
     */
    fun evaluate(
        a11yServiceEnabled: Boolean,
        appAllowed: Boolean,
        systemCapable: Boolean = true,
    ): Readiness = when {
        !systemCapable -> Readiness.UNAVAILABLE
        !appAllowed -> Readiness.NOT_ALLOWED
        !a11yServiceEnabled -> Readiness.SERVICE_DISABLED
        else -> Readiness.READY
    }

    /** 是否已就绪（快捷判定）。 */
    fun isReady(r: Readiness): Boolean = r == Readiness.READY

    /**
     * 状态卡常驻文案。就绪时也要给一句短的，让用户知道「断了也能自己接上」，
     * 而不是只有出事才看到这一行。
     */
    fun banner(r: Readiness): String = when (r) {
        Readiness.READY -> "降级通道就绪"
        Readiness.SERVICE_DISABLED -> "降级通道未就绪：系统设置中未启用无障碍服务"
        Readiness.NOT_ALLOWED -> "降级通道未就绪：本应用内未允许使用无障碍通道"
        Readiness.UNAVAILABLE -> "降级通道不可用：系统不提供无障碍能力"
    }

    /** 出问题时的可操作指引（失败精确到「去设置开哪个开关」）。 */
    fun guidance(r: Readiness): String = when (r) {
        Readiness.READY -> ""
        Readiness.SERVICE_DISABLED ->
            "打开 设置 → 无障碍 → 已下载的服务 → NymphAuto，开启后返回即可，无需重装"
        Readiness.NOT_ALLOWED ->
            "在主界面「权限」页允许无障碍通道；若系统提示「受限设置」，先在应用信息页解除限制"
        Readiness.UNAVAILABLE ->
            "该系统版本不提供无障碍注入；请改用 Shizuku 通道（需安装 Shizuku 并授权）"
    }

    /**
     * 切换失败时的收尾文案。切换失败必须明确告诉用户「现在没有任何通道在工作」，
     * 不能停留在「正在切换」——那会让用户一直等一个不会到来的恢复。
     */
    fun failNotice(r: Readiness): String = when (r) {
        Readiness.READY -> "切换未完成：新通道启动超时，已停止"
        else -> "切换失败：${banner(r)}，已停止"
    }
}

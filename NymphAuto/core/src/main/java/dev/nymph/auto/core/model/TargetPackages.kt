// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.model

/**
 * 「可作为目标应用」的包名判定（纯 JVM，录制器 / 依赖清单 / 预检 / 执行器共用）。
 *
 * ## 为什么需要它
 *
 * 无障碍窗口事件（`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`）在系统桌面、
 * 通知栏、最近任务、锁屏、分屏等界面同样会上报，其包名是系统级组件，例如
 * `com.android.systemui`、各类 Launcher、`android` 框架包。这类包：
 *
 * 1. 没有标准 `CATEGORY_LAUNCHER` 启动入口，`getLaunchIntentForPackage` 返回 null；
 * 2. 无需也无法被「打开」——回放时回到桌面用 HOME 全局动作即可，`OPEN_APP` 是无意义动作；
 * 3. 若被当成普通目标应用，会因「目标应用未安装」在预检里把整个脚本拦死
 *    （v1.2.1 用户实测：桌面试录 65 事件 → 预检拦截 systemui + launcher）。
 *
 * 因此这类包名：录制时不生成 `OPEN_APP` 守卫，依赖清单不收集，预检不做安装校验，
 * 执行时若残留也优雅跳过。真实第三方应用、以及有启动入口的系统应用
 * （如 `com.android.settings`）完全不受影响，包守卫与预检能力完整保留。
 *
 * 判定只用字符串规则、不依赖 `PackageManager`，因此 core 层可纯 JVM 单测。
 */
object TargetPackages {

    /** 明确无启动入口的系统框架 / 系统 UI 包（精确匹配或子包）。 */
    private val SYSTEM_COMPONENTS = listOf(
        "com.android.systemui",  // 状态栏 / 通知栏 / 最近任务 / 导航栏 / 锁屏
        "com.android.keyguard",  // 部分 ROM 的独立锁屏包
        "com.android.server",    // system_server
        "com.android.internal",  // 内部资源包
        "com.android.wallpaper", // 动态壁纸
    )

    /**
     * 该包名是否可作为「需要启动、需要校验已安装」的目标应用。
     *
     * 排除：空白、`android` 框架包、`SYSTEM_COMPONENTS` 及其子包、桌面 / Launcher / Home 类包。
     * 其余（含 `com.android.settings`、`com.android.chrome` 等可启动系统应用）均视为目标应用。
     */
    fun isLaunchableTarget(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == "android") return false
        if (SYSTEM_COMPONENTS.any { pkg == it || pkg.startsWith("$it.") }) return false
        // 桌面类应用响应 CATEGORY_HOME，不是标准启动入口，且无需「打开桌面」。
        // 覆盖 launcher / launcher2 / launcher3 / nexuslauncher / home / homescreen 等。
        val leaf = pkg.substringAfterLast('.')
        if (leaf.contains("launcher") || leaf == "home" || leaf == "homescreen") return false
        return true
    }

    /**
     * 是否是「系统 UI 覆盖层」包（状态栏 / 导航栏 / 最近任务 / 锁屏 / 系统对话框）。
     *
     * 与 [isLaunchableTarget] 的区别：后者还把桌面 Launcher 判为不可启动目标，
     * 但用户在桌面点应用图标是**有效录制内容**，不能一并丢弃。本方法只覆盖
     * 那些节点仅存在于系统窗口、回放时永远找不到的包（v2.2.0 真机实测：
     * `com.android.systemui:id/recent_apps` 被录成脚本第 1 步，回放必失败）。
     */
    fun isSystemUiOverlay(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == "android") return true
        return SYSTEM_COMPONENTS.any { pkg == it || pkg.startsWith("$it.") }
    }
}

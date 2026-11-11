// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统组件包名判定（纯 JVM）：v1.2.2 预检误拦截修复的判定基础。 */
class TargetPackagesTest {

    @Test
    fun `系统UI与框架包不作为目标应用`() {
        listOf(
            "android",
            "com.android.systemui",
            "com.android.systemui.keyguard",
            "com.android.keyguard",
            "com.android.server.telecom",
            "com.android.internal.display",
            "com.android.wallpaper",
        ).forEach { assertFalse("$it 不应作为目标应用", TargetPackages.isLaunchableTarget(it)) }
    }

    @Test
    fun `各类桌面Launcher不作为目标应用`() {
        listOf(
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.oppo.homescreen",
        ).forEach { assertFalse("$it 不应作为目标应用", TargetPackages.isLaunchableTarget(it)) }
    }

    @Test
    fun `真实第三方与可启动系统应用仍作为目标应用`() {
        listOf(
            "com.demo.app",
            "com.example.app",
            "com.tencent.mm",
            "com.taobao.taobao",
            "com.android.settings", // 有启动入口，用户在设置里录制仍要包守卫
            "com.android.chrome",
            "com.missing",          // 未安装但属普通应用，预检仍应拦截
        ).forEach { assertTrue("$it 应作为目标应用", TargetPackages.isLaunchableTarget(it)) }
    }

    @Test
    fun `空白包名不作为目标应用`() {
        assertFalse(TargetPackages.isLaunchableTarget(""))
        assertFalse(TargetPackages.isLaunchableTarget("   "))
    }

    @Test
    fun `系统UI覆盖层包判定`() {
        listOf(
            "android",
            "com.android.systemui",
            "com.android.systemui.keyguard",
            "com.android.keyguard",
        ).forEach { assertTrue("$it 应判为系统 UI 覆盖层", TargetPackages.isSystemUiOverlay(it)) }
    }

    @Test
    fun `桌面与第三方应用不判为系统UI覆盖层`() {
        listOf(
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.demo.app",
            "com.tencent.mm",
            "com.android.settings",
            "",
        ).forEach { assertFalse("$it 不应判为系统 UI 覆盖层", TargetPackages.isSystemUiOverlay(it)) }
    }

}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.1.0 UI 大改——静态源码门禁。
 *
 * 与 WorkbenchRefactorGateTest 同风格的文本级门禁：只断言结构落地，
 * 不证明视觉行为。锁定四件事，防止后续重构悄悄退回旧版：
 *  1. 首次启动新手引导已接线且带一次性标记；
 *  2. 分组标题不再叠加水平内边距（旧版标题比卡片多缩进 16dp 的对齐 bug）；
 *  3. 设置页信息分层：权限置顶、纯参考内容折叠进「高级参考」；
 *  4. 脚本页空库三入口 Hero 与首屏权限状态条存在。
 */
class UiOverhaulGateTest {

    private fun appSrc(): File {
        val module = File(System.getProperty("user.dir"))
        val src = File(module, "src/main/java/dev/nymph/auto")
        assertTrue("工作目录异常：$module", src.isDirectory)
        return src
    }

    private fun read(rel: String): String = File(appSrc(), rel).readText()

    private fun codeOnly(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?m)^\s*//.*$"""), "")

    // ── 1. 新手引导：门控接线 + 一次性标记 ──
    @Test
    fun `首次启动引导已接线且带一次性标记`() {
        val app = codeOnly(read("ui/compose/App.kt"))
        assertTrue("主壳必须渲染 OnboardingScreen", app.contains("OnboardingScreen("))
        assertTrue("门控读一次性标记", app.contains("OnboardingPrefs.isDone(context)"))
        assertTrue("退出标记完成", app.contains("finishOnboarding"))

        val ob = read("ui/compose/Onboarding.kt")
        assertTrue("引导用横向分页", ob.contains("HorizontalPager"))
        assertTrue("四页常量", ob.contains("pageCount = { 4 }"))
        assertTrue("一次性标记对象", ob.contains("object OnboardingPrefs"))
        assertTrue("标记键", ob.contains("KEY_ONBOARDING_DONE"))
        assertTrue("末页主动作", ob.contains("开始使用"))
    }

    // ── 2. 分组标题对齐：不再叠加水平内边距 ──
    @Test
    fun `分组标题不再叠加水平内边距`() {
        val comp = codeOnly(read("ui/compose/Components.kt"))
        // 旧版 `padding(start = Ds.LG, end = Ds.LG, top = Ds.XL, bottom = Ds.SM)`
        // 与父容器 16dp 叠加，导致标题比卡片多缩进 16dp。
        assertFalse("SectionTitle 不得再叠加左右内边距",
            comp.contains("start = Ds.LG, end = Ds.LG"))
        assertTrue("SectionTitle 保留上下节奏", comp.contains("padding(top = Ds.XL, bottom = Ds.SM)"))
    }

    // ── 3. 设置页信息分层：权限置顶 + 高级参考折叠 ──
    @Test
    fun `设置页权限置顶且纯参考内容折叠`() {
        val screens = codeOnly(read("ui/compose/Screens.kt"))
        val permIdx = screens.indexOf("SectionTitle(\"权限\")")
        val visIdx = screens.indexOf("SectionTitle(\"回放可视化\"")
        assertTrue("权限分组存在", permIdx >= 0)
        assertTrue("回放可视化分组存在", visIdx >= 0)
        assertTrue("权限必须置顶于回放可视化", permIdx < visIdx)
        assertTrue("高级参考折叠容器存在", screens.contains("CollapsibleSection("))
        assertTrue("高级参考分组标题", screens.contains("\"高级参考\""))
        assertTrue("展开/收起文案", screens.contains("收起"))
    }

    // ── 4. 脚本页：空库三入口 Hero + 首屏权限状态条 ──
    @Test
    fun `脚本页空库三入口与权限状态条存在`() {
        val screens = codeOnly(read("ui/compose/Screens.kt"))
        assertTrue("三入口 Hero 标题", screens.contains("建立你的第一条自动化"))
        assertTrue("入口卡组件", screens.contains("EntryCard("))
        assertTrue("录制入口", screens.contains("title = \"录制\""))
        assertTrue("新建入口", screens.contains("title = \"新建脚本\""))
        assertTrue("导入入口", screens.contains("title = \"导入脚本\""))
        assertTrue("首屏权限状态条", screens.contains("StatusChip("))
        assertTrue("无障碍状态提示", screens.contains("无障碍未开启"))
        assertTrue("悬浮窗状态提示", screens.contains("悬浮窗未授权"))
    }
}

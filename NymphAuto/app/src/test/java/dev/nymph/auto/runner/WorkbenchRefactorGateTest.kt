// SPDX-License-Identifier: MIT
package dev.nymph.auto.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-075 单一工作台重构——静态源码门禁。
 *
 * 这是**静态扫描**（不是运行时、不是界面）：只断言源码结构上删除/停用项已落地，
 * 不证明界面行为。界面确实不弹「只读」由真机验证栏覆盖（本环境无真机，标未验证）。
 *
 * 与 [RuntimeCanvasZeroReferenceTest]（运行链 canvas 零引用）、
 * [ConjunctionSameFrameGateTest]（同帧求值）同风格的文本级门禁。
 */
class WorkbenchRefactorGateTest {

    private fun appSrc(): File {
        val module = File(System.getProperty("user.dir"))
        val src = File(module, "src/main/java/dev/nymph/auto")
        assertTrue("工作目录异常：$module", src.isDirectory)
        return src
    }

    private fun read(rel: String): String = File(appSrc(), rel).readText()

    /** 去掉行注释与块注释后再扫描——注释里声明「不接 X / 已删 Y」是合规文档，不算实际代码。 */
    private fun codeOnly(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?m)^\s*//.*$"""), "")

    // ── 静态扫描：无只读分支 ──
    @Test
    fun `工作台源码无只读场景与只剩一个的只读标签`() {
        val text = codeOnly(read("ui/compose/EditorScreen.kt"))
        assertFalse("不得再有「只读场景」标题", text.contains("只读场景"))
        assertFalse("不得再有「（只读）」列表标签", text.contains("（只读）"))
        assertFalse("不得再有「此处只读」文案", text.contains("此处只读"))
        assertFalse("不得再有「点「源码」编辑」引导", text.contains("点「源码」编辑完整 JSON"))
        // 工作台确定按钮以单一真源为锚，任意 Action 可编辑。
        assertTrue("确定按钮必须锚 isWorkbenchEditable",
            text.contains("ActionEditGuard.isWorkbenchEditable"))
    }

    // ── 静态扫描：3 入口合并为 1 ──
    @Test
    fun `三入口合并为单一工作台入口`() {
        val text = codeOnly(read("ui/compose/EditorScreen.kt"))
        assertTrue("必须有单一工作台入口", text.contains("场景工作台"))
        // 旧三入口 Specific 文案应不存在（添加场景 / 添加图片特征入口 Surface）
        assertFalse("不得再有「添加场景（点击 / 长按 / 双击 / 滑动）」入口",
            text.contains("添加场景（点击 / 长按 / 双击 / 滑动）"))
        assertFalse("不得再有「添加图片特征（框选截取 · 相似度判定）」入口",
            text.contains("添加图片特征（框选截取 · 相似度判定）"))
    }

    // ── 静态扫描：D5 不再以 Dialog 弹出；D6/D7 调用停用 ──
    @Test
    fun `D5工作台非Dialog弹出_D6与D7调用已停用`() {
        val editor = codeOnly(read("ui/compose/EditorScreen.kt"))
        // D5：工作台函数体内不得再以 AlertDialog( 包裹（本文件工作台函数已改 Column/TopAppBar）
        val wb = codeOnly(read("ui/compose/SceneCanvasEditorDialog.kt"))
        assertFalse("工作台主体不得用 AlertDialog 弹出", wb.contains("AlertDialog("))
        assertTrue("工作台须有顶栏返回", wb.contains("TopAppBar"))
        // D6：EditorScreen 中 FeatureCaptureDialog( 调用停用（仅注释里出现，不含实际调用）
        val featureCall = Regex("""(?m)^\s*FeatureCaptureDialog\s*\(""").findAll(editor).count()
        assertEquals("FeatureCaptureDialog 实际调用必须为 0", 0, featureCall)
        // D7：App.kt 中 SimpleTapDialog( 调用停用
        val app = codeOnly(read("ui/compose/App.kt"))
        val simpleCall = Regex("""(?m)^\s*SimpleTapDialog\s*\(""").findAll(app).count()
        assertEquals("SimpleTapDialog 实际调用必须为 0", 0, simpleCall)
    }

    // ── 静态扫描：改动确认 1 建档只走无障碍链，MediaProjection/HudCapture 不接线 ──
    @Test
    fun `建档只走无障碍链_MediaProjection与HudCapture不进工作台改动集`() {
        val wb = codeOnly(read("ui/compose/SceneCanvasEditorDialog.kt"))
        assertTrue("抓帧必须走无障碍 screenshotBitmap",
            wb.contains("AutoClickService.live()") && wb.contains("screenshotBitmap()"))
        // PNG 无损 + 质量 100（原画质最高质量；不降采样、不重编码）
        assertTrue("底图必须 PNG 无损压缩",
            wb.contains("Bitmap.CompressFormat.PNG, 100"))
        assertFalse("工作台不得接 MediaProjection", wb.contains("MediaProjection"))
        assertFalse("工作台不得接 HudCaptureService", wb.contains("HudCaptureService"))
    }

    // ── 静态扫描：三态与 m 合取 UI 接线（编译层不动） ──
    @Test
    fun `工作台同屏三态与合取UI接线存在`() {
        val wb = codeOnly(read("ui/compose/SceneCanvasEditorDialog.kt"))
        assertTrue("标点态", wb.contains("标点"))
        assertTrue("框特征态", wb.contains("框特征"))
        assertTrue("整图门态", wb.contains("整图门"))
        // N4：ALL / N-of-M 控件接 canvas.combine / minCount（编译层 compileToActions 不动）
        assertTrue("合取combine接线", wb.contains("copy(combine = cb)"))
        assertTrue("N-of-M minCount接线", wb.contains("copy(minCount = n)"))
        // N5：整图门开关 + 阈值写回 Script
        assertTrue("整图门方法开关", wb.contains("GlobalVerifyMethod.NCC"))
        assertTrue("整图门阈值回写", wb.contains("workSimilarity"))
    }

    // ── 静态扫描：v3.1.1 双球分离 + A+B+C 交互重构 ──
    @Test
    fun `运行球与相机球分离且点击启停长按出面板`() {
        val ball = read("ui/FloatBall.kt")
        // 运行球单一语义：点击启停 + 长按出面板；不再有互斥模式枚举。
        assertTrue("点击启停回调", ball.contains("var onToggle"))
        assertTrue("长按展开面板回调", ball.contains("var onShowPanel"))
        assertFalse("不得再有互斥模式枚举", ball.contains("enum class Mode"))
        assertFalse("不得再有相机抓帧回调", ball.contains("onCapture"))

        // 相机球是独立控件，只绑定抓帧回调，无启停/面板语义。
        val cam = read("ui/CameraBall.kt")
        assertTrue("独立相机球类", cam.contains("class CameraBall"))
        assertTrue("相机球抓帧回调", cam.contains("var onCapture"))
        assertFalse("相机球不得有启停回调", cam.contains("onToggle") or cam.contains("onShowPanel"))

        val svc = read("service/RunnerService.kt")
        assertTrue("创建独立相机球 API", svc.contains("fun showCameraBall"))
        assertTrue("移除独立相机球 API", svc.contains("fun hideCameraBall"))
        assertFalse("不得再有旧互斥入口", svc.contains("enterEditCameraMode") || svc.contains("exitEditCameraMode"))
        // A 方案：点击运行球走 onToggle 回调（绑定 toggle），长按走 onShowPanel（绑定 showPanel）。
        assertTrue("球点击回调绑定启停", svc.contains("onToggle = { toggle() }"))
        assertTrue("球长按回调绑定面板", svc.contains("onShowPanel = { showPanel() }"))
        assertTrue("点击时先收起面板", svc.contains("hidePanel()\n                        v.onToggle?.invoke()"))
        // B+C 方案：紧凑条 + 超时自动收起 + 操作后收起。
        assertTrue("面板超时自动收起", svc.contains("postDelayed(panelAutoHide"))
        val panel = read("ui/BallPanel.kt")
        assertTrue("面板半透明", panel.contains("Color.argb"))
        assertTrue("面板操作后收起钩子", panel.contains("onAction"))
        assertTrue("面板单行状态文本", panel.contains("maxLines = 1"))
    }

    // ── 静态扫描：裁决 4 修复——相机球建档取景态「主动拉起」链路 ──
    @Test
    fun `相机球建档取景态主动拉起服务而非静默降级`() {
        val svc = read("service/RunnerService.kt")
        // 进程内中转回调：Compose 抓帧闭包无法随 Intent 跨 Binder 传递，故进程内暂存。
        assertTrue("进程内中转抓帧回调", svc.contains("pendingCameraCapture"))
        assertTrue("主动进入取景态入口", svc.contains("fun enterCameraCapture"))
        assertTrue("退出取景态入口", svc.contains("fun exitCameraCapture"))
        assertTrue("服务未启动时拉起", svc.contains("startForegroundService(intent)"))
        // 专用 action：只建球 + 置相机模式，不加载脚本、不进 ACTION_START 链。
        assertTrue("专用 action 常量", svc.contains("ACTION_ENTER_CAMERA"))
        assertTrue("专用 action 分支", svc.contains("ACTION_ENTER_CAMERA ->"))
        // v3.1.1：独立相机球按幂等判空创建（不复用运行球）。
        assertTrue("独立相机球幂等创建", svc.contains("if (cameraBall != null) return"))
        // 首启必须落前台：ACTION_ENTER_CAMERA 分支不得提前 return 跳过 refreshForeground。
        val branch = svc.substring(
            svc.indexOf("ACTION_ENTER_CAMERA ->"),
            svc.indexOf("// v2.9.30：通知栏三键之一")
        )
        assertFalse("取景态分支不得提前 return（否则 startForegroundService 5s 未挂前台崩溃）",
            branch.contains("return START_NOT_STICKY"))

        val wb = codeOnly(read("ui/compose/SceneCanvasEditorDialog.kt"))
        // 进入工作台调 enterCameraCapture、退出 onDispose 调 exitCameraCapture——两侧都接上。
        assertTrue("工作台进入调 enterCameraCapture",
            wb.contains("RunnerService.enterCameraCapture(context)"))
        assertTrue("工作台退出调 exitCameraCapture",
            wb.contains("RunnerService.exitCameraCapture()"))
        assertTrue("退出在 onDispose 复位", wb.contains("onDispose"))
        // 抓帧回调用 rememberUpdatedState 持有，避免球点击调用过期闭包。
        assertTrue("抓帧回调记住最新闭包", wb.contains("rememberUpdatedState"))
        // 不再依赖 live() 判空静默降级——该断链导致相机球从不出现。
        assertFalse("不得再退回 live() 判空静默降级的断链实现",
            wb.contains("RunnerService.live()?."))
    }

    // ── 静态扫描：界面重合修复——工作台独占整屏（与源码视图同构 early-return） ──
    @Test
    fun `工作台分支earlyReturn独占整屏不再与步骤视图叠绘`() {
        val editor = codeOnly(read("ui/compose/EditorScreen.kt"))
        val wbCall = "SceneCanvasEditorDialog("
        val idx = editor.indexOf(wbCall)
        assertTrue("工作台入口必须存在", idx >= 0)
        // 从工作台调用点往后一小段必须出现 return——早于步骤视图 Column(fillMaxSize)。
        val afterWb = editor.substring(idx, (idx + 2400).coerceAtMost(editor.length))
        assertTrue("工作台组合后必须 early-return 独占整屏", afterWb.contains("return"))
        // 步骤视图锚点用其 TopAppBar 标题（注释行会被 codeOnly 过滤，不能做锚点）。
        val stepViewIdx = editor.indexOf("\"编辑场景\", maxLines = 1")
        assertTrue("步骤视图锚点存在", stepViewIdx >= 0)
        assertTrue("工作台调用必须位于步骤视图之前", idx < stepViewIdx)
        // 只保留一处工作台调用——此前分支重复（前置独占 + 末尾叠绘）是界面重合根因。
        val callCount = Regex(Regex.escape(wbCall)).findAll(editor).count()
        assertEquals("工作台调用点必须恰好 1 处", 1, callCount)
    }
}

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

    // ── 静态扫描：悬浮球互斥标志 ──
    @Test
    fun `悬浮球双语义互斥标志存实例字段且退出清除`() {
        val ball = read("ui/FloatBall.kt")
        assertTrue("互斥模式枚举", ball.contains("enum class Mode"))
        assertTrue("实例字段mode", ball.contains("private var mode"))
        assertTrue("相机态点击回调", ball.contains("var onCapture"))
        assertTrue("复位API", ball.contains("fun setMode"))
        // 退出复位 RUN_STATUS 时清空 onCapture，杜绝泄漏到运行态
        assertTrue("退出清除回调", ball.contains("if (m == Mode.RUN_STATUS) onCapture = null"))

        val svc = read("service/RunnerService.kt")
        assertTrue("进入建档态API", svc.contains("fun enterEditCameraMode"))
        assertTrue("退出建档态API", svc.contains("fun exitEditCameraMode"))
        assertTrue("点击分流不误触启停", svc.contains("v.onCapture?.invoke()"))
    }

    // ── 静态扫描：裁决 4 收尾——置位 API 已真正接线（此前零调用），且 fail-soft ──
    @Test
    fun `相机球置位API已接线到工作台进入退出且服务未启动静默降级`() {
        val svc = read("service/RunnerService.kt")
        // 进程内单例访问点：工作台据此触达置位 API（只内存、不持久化）。
        assertTrue("服务实例访问点 live()", svc.contains("fun live(): RunnerService?"))
        assertTrue("onCreate 注册实例", svc.contains("liveInstance = this"))
        assertTrue("onDestroy 注销实例", svc.contains("if (liveInstance === this) liveInstance = null"))
        // enterEditCameraMode 全局常驻：球未显示时先创建（此前是 `?: return` 静默失败）。
        assertTrue("球未显示先创建（全局常驻）", svc.contains("if (ball == null) showBall()"))

        val wb = codeOnly(read("ui/compose/SceneCanvasEditorDialog.kt"))
        // 进入工作台调 enterEditCameraMode、退出 onDispose 调 exitEditCameraMode——两侧都接上。
        assertTrue("工作台进入调 enterEditCameraMode",
            wb.contains(".enterEditCameraMode"))
        assertTrue("工作台退出调 exitEditCameraMode",
            wb.contains(".exitEditCameraMode"))
        assertTrue("退出在 onDispose 复位", wb.contains("onDispose"))
        // 抓帧回调用 rememberUpdatedState 持有，避免球点击调用过期闭包。
        assertTrue("抓帧回调记住最新闭包", wb.contains("rememberUpdatedState"))
        // fail-soft：服务未启动时判空静默，绝不因此启停脚本或改服务生命周期。
        assertTrue("服务判空安全调用", wb.contains("RunnerService.live()?."))
        assertFalse("工作台不得主动 startService（不改服务生命周期）",
            wb.contains("startService") || wb.contains("startForegroundService"))
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 录制核心（纯 JVM）：事件归一化 → 多模态锚点 → 明文脚本。 */
class RecordCompilerTest {

    private val compiler = RecordCompiler()

    private fun click(
        at: Long, x: Int = 300, y: Int = 600,
        pkg: String = "com.demo.app",
        viewId: String = "com.demo.app:id/btn_ok",
        text: String = "确定",
        className: String = "android.widget.Button",
        template: String = "",
        ocr: String = "",
    ) = RecordEvent(
        atMs = at, kind = RecordEventKind.CLICK, x = x, y = y,
        screenW = 1080, screenH = 2400, rotation = 0,
        templateB64 = template, ocrText = ocr,
        node = RecordNode(
            packageName = pkg, className = className, viewId = viewId,
            text = text, contentDesc = "",
            bounds = RecordBounds(x - 60, y - 30, x + 60, y + 30),
            clickable = true,
        ),
    )

    @Test
    fun `空事件流生成空脚本`() {
        val r = compiler.compile("空", emptyList())
        assertEquals(0, r.script.actions.size)
        assertEquals(0, r.report.rawEvents)
    }

    @Test
    fun `节点ID优先于文字与坐标`() {
        val r = compiler.compile("点击", listOf(click(100)))
        // 首个动作是包守卫，之后才是点击
        val hit = r.script.actions.last()
        assertEquals(ActionType.CLICK_NODE, hit.type)
        assertEquals("com.demo.app:id/btn_ok", hit.nodeId)
        assertEquals(1, r.report.anchorCounts[AnchorKind.NODE_ID])
    }

    @Test
    fun `无节点ID时降级到节点文字`() {
        val e = click(100, viewId = "").copy(
            node = click(100, viewId = "").node!!.copy(viewId = ""),
        )
        val r = compiler.compile("文字", listOf(e))
        assertEquals(ActionType.CLICK_TEXT, r.script.actions.last().type)
        assertEquals("确定", r.script.actions.last().text)
    }

    @Test
    fun `画布文字场景用OCR锚点`() {
        val e = RecordEvent(
            atMs = 100, kind = RecordEventKind.CLICK, x = 200, y = 300,
            screenW = 1080, screenH = 2400, ocrText = "立即领取",
            node = RecordNode(packageName = "com.game", bounds = RecordBounds(150, 270, 250, 330)),
        )
        val r = compiler.compile("游戏", listOf(e))
        val hit = r.script.actions.last()
        assertEquals(ActionType.CLICK_TEXT, hit.type)
        assertEquals("立即领取", hit.text)
        assertEquals(1, r.report.anchorCounts[AnchorKind.OCR_TEXT])
    }

    @Test
    fun `图像模板锚点带限定搜索区域`() {
        val e = click(100, viewId = "", text = "", template = "aGVsbG8=").copy(
            node = click(100).node!!.copy(viewId = "", text = "", contentDesc = ""),
        )
        val hit = compiler.compile("图标", listOf(e)).script.actions.last()
        assertEquals(ActionType.CLICK_IMAGE, hit.type)
        assertEquals("aGVsbG8=", hit.imageData)
        assertTrue(hit.region.leftPct < hit.region.rightPct)
    }

    @Test
    fun `全无锚点时降级到百分比坐标`() {
        val e = RecordEvent(
            atMs = 10, kind = RecordEventKind.CLICK, x = 540, y = 1200,
            screenW = 1080, screenH = 2400,
        )
        val hit = compiler.compile("裸坐标", listOf(e)).script.actions.last()
        assertEquals(ActionType.TAP, hit.type)
        assertEquals(50.0, hit.x, 1e-9)
        assertEquals(50.0, hit.y, 1e-9)
        assertEquals(1, compiler.compile("裸坐标", listOf(e)).report.anchorCounts[AnchorKind.COORD])
    }

    @Test
    fun `禁止坐标降级时跳过无锚点交互`() {
        val strict = RecordCompiler(RecordOptions(allowCoordFallback = false))
        val e = RecordEvent(atMs = 10, kind = RecordEventKind.CLICK, x = 5, y = 5, screenW = 1080, screenH = 2400)
        val r = strict.compile("严格", listOf(e))
        assertTrue(r.script.actions.isEmpty())
        assertTrue(r.report.warnings.isNotEmpty())
    }

    @Test
    fun `同一次点击的重复事件被合并`() {
        val events = listOf(click(100), click(140), click(180))
        val r = compiler.compile("去重", events)
        assertEquals(2, r.report.duplicatesMerged)
        // 1 个包守卫 + 1 个点击
        assertEquals(2, r.script.actions.size)
    }

    @Test
    fun `FOCUS噪声与自身UI事件被剔除`() {
        val focus = click(90).copy(kind = RecordEventKind.FOCUS)
        val self = click(95, pkg = "dev.nymph.auto")
        val r = compiler.compile("过滤", listOf(focus, self, click(100)))
        assertEquals(1, r.report.selfEventsDropped)
        assertEquals(1, r.report.keptEvents)
    }

    @Test
    fun `连续同向滚动合并为单个SWIPE`() {
        fun scroll(at: Long, dy: Int) = RecordEvent(
            atMs = at, kind = RecordEventKind.SCROLL, x = 540, y = 1800,
            dx = 0, dy = dy, screenW = 1080, screenH = 2400,
            node = RecordNode(packageName = "com.demo.app", scrollable = true),
        )
        val r = compiler.compile("滚动", listOf(scroll(0, -120), scroll(200, -130), scroll(400, -110)))
        assertEquals(2, r.report.scrollsMerged)
        assertEquals(1, r.script.actions.count { it.type == ActionType.SWIPE })
    }

    @Test
    fun `包名切换插入OPEN_APP守卫`() {
        val r = compiler.compile("跨应用", listOf(click(0, pkg = "com.a"), click(1000, pkg = "com.b")))
        val guards = r.script.actions.filter { it.type == ActionType.OPEN_APP }
        assertEquals(2, guards.size)
        assertEquals("com.a", guards[0].packageName)
        assertEquals("com.b", guards[1].packageName)
        assertEquals(2, r.report.appGuardsInserted)
    }

    @Test
    fun `长间隔自动设置waitMs`() {
        val r = compiler.compile("等待", listOf(click(0), click(2000)))
        assertEquals(1, r.report.waitsInserted)
        val tap = r.script.actions.last()
        assertTrue("waitMs 应为约 2000，实际 ${tap.waitMs}", tap.waitMs in 1900..2000)
    }

    @Test
    fun `短间隔不插入等待`() {
        val r = compiler.compile("紧凑", listOf(click(0), click(500, x = 800, viewId = "com.demo.app:id/btn2")))
        // dedupeWindowMs=320，间隔 500 且坐标远 → 不去重，但 minWaitMs=300 会触发等待
        // 这里验证等待被合并进动作而非生成独立 WAIT 动作
        assertFalse(r.script.actions.any { it.type == ActionType.WAIT })
    }

    @Test
    fun `参考分辨率写入脚本`() {
        val s = compiler.compile("分辨率", listOf(click(0))).script
        assertEquals(1080, s.refWidth)
        assertEquals(2400, s.refHeight)
        assertEquals(0, s.refRotation)
    }

    @Test
    fun `长按使用LONG_PRESS与更长holdMs`() {
        val e = click(0).copy(kind = RecordEventKind.LONG_CLICK)
        val hit = compiler.compile("长按", listOf(e)).script.actions.last()
        assertEquals(ActionType.LONG_PRESS, hit.type)
        assertEquals(800L, hit.holdMs)
    }

    @Test
    fun `文本输入与按键动作`() {
        val input = RecordEvent(atMs = 0, kind = RecordEventKind.TEXT_INPUT, text = "hello", screenW = 1080, screenH = 2400)
        val key = RecordEvent(atMs = 500, kind = RecordEventKind.KEY, keyName = "BACK", screenW = 1080, screenH = 2400)
        val acts = compiler.compile("输入", listOf(input, key)).script.actions
        assertEquals(ActionType.INPUT, acts[0].type)
        assertEquals("hello", acts[0].text)
        assertEquals(ActionType.KEY, acts.last().type)
    }

    @Test
    fun `事件数超上限被截断并给出告警`() {
        val strict = RecordCompiler(RecordOptions(maxEvents = 2))
        val r = strict.compile("截断", listOf(click(0), click(500, x = 800, viewId = "id2"), click(1000, x = 900, viewId = "id3")))
        assertEquals(3, r.report.rawEvents)                 // 原始输入如实上报
        assertTrue(r.report.keptEvents <= 2)                // 截断后参与编译的不超过上限
        assertTrue(r.report.warnings.any { it.contains("上限") })
    }

    @Test
    fun `录制的脚本是明文JSON且可往返解码`() {
        val script = compiler.compile("明文", listOf(click(0), click(1200, x = 700, viewId = "com.demo.app:id/btn2"))).script
        val json = ScriptCodec.encode(script)
        assertTrue("应为明文 JSON", json.trimStart().startsWith("{"))
        assertTrue("描述应标注无混淆", json.contains("未加密") || script.description.contains("未加密"))
        val back = ScriptCodec.decode(json)
        assertNotNull(back)
        assertEquals(script.actions.size, back!!.actions.size)
        assertEquals(script.refWidth, back.refWidth)
    }

    @Test
    fun `脚本描述声明权限约束`() {
        val s = compiler.compile("约束", listOf(click(0))).script
        assertTrue(s.description.contains("MediaProjection"))
        assertTrue(s.description.contains("不消费触摸"))
    }

    @Test
    fun `RecordCodec明文往返且坏输入不抛异常`() {
        val events = listOf(click(0), click(500, x = 800, viewId = "id2"))
        val raw = RecordCodec.encode(events)
        assertTrue(raw.trimStart().startsWith("["))
        assertEquals(2, RecordCodec.decode(raw).size)
        assertTrue(RecordCodec.decode("这不是JSON").isEmpty())
        assertTrue(RecordCodec.decode("").isEmpty())
        assertEquals(3, RecordCodec.decode(RecordCodec.append(raw, click(900))).size)
    }

    @Test
    fun `锚点选择器按稳定性降序`() {
        val e = click(0, template = "aGVsbG8=", ocr = "确定")
        val kinds = AnchorSelector.candidates(e).map { it.kind }
        assertEquals(AnchorKind.NODE_ID, kinds.first())
        assertEquals(AnchorKind.COORD, kinds.last())
        assertTrue(kinds.indexOf(AnchorKind.NODE_TEXT) < kinds.indexOf(AnchorKind.OCR_TEXT))
        assertTrue(kinds.indexOf(AnchorKind.OCR_TEXT) < kinds.indexOf(AnchorKind.TEMPLATE))
    }

    @Test
    fun `报告一行摘要可读`() {
        val r = compiler.compile("摘要", listOf(click(0), click(140), click(2000, pkg = "com.other")))
        val line = r.report.oneLine()
        assertTrue(line.contains("原始"))
        // 140ms 的同锚点第二次点击是「有意连点」，不再算重复上报
        assertTrue(line.contains("连点聚合"))
        assertTrue(line.contains("包守卫"))
    }

    // ---- v2.0：连点不再被当噪声吞掉 ----

    @Test
    fun `用户连点聚合为多击并保留次数`() {
        val r = compiler.compile("连点", listOf(click(0), click(220), click(440)))
        val hits = r.script.actions.filter { it.type == ActionType.CLICK_NODE }
        assertEquals(1, hits.size)
        assertEquals(3, hits.first().times)
        assertEquals(2, r.report.tapsMerged)
        assertEquals(1, r.script.actions.count { it.type == ActionType.CLICK_NODE })
    }

    @Test
    fun `系统重复上报的同一击不叠加次数`() {
        val r = compiler.compile("重复", listOf(click(0), click(40), click(80)))
        val hits = r.script.actions.filter { it.type == ActionType.CLICK_NODE }
        assertEquals(1, hits.size)
        assertEquals(1, hits.first().times)
        assertEquals(2, r.report.duplicatesMerged)
        assertEquals(0, r.report.tapsMerged)
    }

    @Test
    fun `不同位置的快速点击不会被误合并`() {
        val r = compiler.compile(
            "两点",
            listOf(click(0, x = 300, y = 600), click(200, x = 800, y = 1500)),
        )
        assertEquals(2, r.script.actions.count { it.type == ActionType.CLICK_NODE })
        assertEquals(0, r.report.tapsMerged)
    }

    // ---- v1.2.2：桌面 / 系统 UI 窗口事件不得污染 OPEN_APP ----

    private fun window(at: Long, pkg: String) = RecordEvent(
        atMs = at,
        kind = RecordEventKind.WINDOW_CHANGED,
        screenW = 1080, screenH = 2400, rotation = 0,
        activityName = "$pkg.MainActivity",
        node = RecordNode(packageName = pkg, className = "$pkg.MainActivity"),
    )

    @Test
    fun `系统组件窗口事件不生成OPEN_APP`() {
        val r = compiler.compile("桌面试录", listOf(
            window(0, "com.android.systemui"),
            window(100, "com.huawei.android.launcher"),
            window(200, "com.miui.home"),
            click(300),
        ))
        val opens = r.script.actions.filter { it.type == ActionType.OPEN_APP }
        // 系统组件窗口事件被剔除，只剩真实应用的包守卫，预检不会被误拦
        assertEquals(listOf("com.demo.app"), opens.map { it.packageName })
    }

    @Test
    fun `真实应用窗口切换仍生成OPEN_APP`() {
        val r = compiler.compile("跨应用窗口", listOf(
            window(0, "com.a"),
            click(200, pkg = "com.a"),
            window(1000, "com.b"),
            click(1200, pkg = "com.b"),
        ))
        val pkgs = r.script.actions.filter { it.type == ActionType.OPEN_APP }.map { it.packageName }
        assertTrue("$pkgs 应含 com.a", pkgs.contains("com.a"))
        assertTrue("$pkgs 应含 com.b", pkgs.contains("com.b"))
    }

    @Test
    fun `连续同包窗口事件全部丢弃不生成OPEN_APP`() {
        val r = compiler.compile("窗口噪声", listOf(
            window(0, "com.demo.app"),
            window(50, "com.demo.app"),
            window(100, "com.demo.app"),
        ))
        val opens = r.script.actions.filter { it.type == ActionType.OPEN_APP }
        // v2.3.0：窗口层级变化事件不携带任何可回放语义，全部丢弃；
        // 包守卫只由真实交互事件触发，纯窗口噪声不应产出任何动作。
        assertEquals(0, opens.size)
        assertEquals(3, r.report.windowEventsDropped)
        assertEquals(0, r.script.actions.size)
    }

    // ---- v2.2.0 真机回归修复 ----

    @Test
    fun `系统UI覆盖层节点点击不进入脚本`() {
        val e = click(
            100,
            pkg = "com.android.systemui",
            viewId = "com.android.systemui:id/recent_apps",
            text = "最近任务",
        )
        val r = compiler.compile("系统UI", listOf(e))
        // recent_apps 只在系统窗口存在，回放时 rootInActiveWindow 永远找不到，
        // 曾成为脚本第 1 步导致必然失败。
        assertTrue(
            "系统 UI 点击不应产出任何点击动作",
            r.script.actions.none { it.type == ActionType.CLICK_NODE || it.type == ActionType.TAP },
        )
        assertEquals(1, r.report.systemUiEventsDropped)
    }

    @Test
    fun `桌面Launcher上的有效点击不被误过滤`() {
        val e = click(
            100,
            pkg = "com.huawei.android.launcher",
            viewId = "com.huawei.android.launcher:id/icon",
            text = "设置",
        )
        val r = compiler.compile("桌面", listOf(e))
        assertTrue(r.script.actions.any { it.type == ActionType.CLICK_NODE })
        assertEquals(0, r.report.systemUiEventsDropped)
    }

    @Test
    fun `无锚点且坐标退化为0的点击被丢弃`() {
        val e = RecordEvent(
            atMs = 10, kind = RecordEventKind.CLICK, x = 0, y = 0,
            screenW = 1080, screenH = 2400,
        )
        val r = compiler.compile("零坐标", listOf(e))
        assertTrue(
            "不得降级成点击 (0,0) 的动作",
            r.script.actions.none { it.type == ActionType.TAP || it.type == ActionType.LONG_PRESS },
        )
        assertEquals(1, r.report.invalidCoordDropped)
    }

    @Test
    fun `有节点锚点时即使坐标为0也保留`() {
        // 有 viewId 就是有效锚点，不应因坐标恰好是 0 被当作噪声丢弃。
        val e = click(
            10, x = 0, y = 0,
            viewId = "com.demo.app:id/btn_ok", text = "确定",
        )
        val r = compiler.compile("零坐标但有节点", listOf(e))
        assertTrue(r.script.actions.any { it.type == ActionType.CLICK_NODE })
        assertEquals(0, r.report.invalidCoordDropped)
    }

    @Test
    fun `报告一行摘要含系统UI与无效坐标统计`() {
        val events = listOf(
            click(0, pkg = "com.android.systemui", viewId = "com.android.systemui:id/home"),
            RecordEvent(
                atMs = 300, kind = RecordEventKind.CLICK, x = 0, y = 0,
                screenW = 1080, screenH = 2400,
            ),
        )
        val line = compiler.compile("摘要", events).report.oneLine()
        assertTrue(line.contains("剔除系统UI"))
        assertTrue(line.contains("跳过无效坐标"))
    }


    // ---- v2.3.0 真机回归修复 ----

    @Test
    fun `窗口事件不再生成冗余OPEN_APP`() {
        val window = { at: Long ->
            RecordEvent(
                atMs = at, kind = RecordEventKind.WINDOW_CHANGED,
                screenW = 1080, screenH = 2400,
                node = RecordNode(packageName = "com.demo.app"),
            )
        }
        // 真机：5 步脚本里 3 个 OPEN_APP 反复拉起同一应用
        val events = listOf(window(0), click(100), window(1000), click(1200), window(2000))
        val r = compiler.compile("窗口", events)
        val opens = r.script.actions.count { it.type == ActionType.OPEN_APP }
        assertEquals("只允许 1 个包守卫", 1, opens)
        assertTrue(r.report.windowEventsDropped >= 3)
        assertTrue(r.report.oneLine().contains("忽略窗口事件"))
    }

    @Test
    fun `窗口事件丢弃后交互动作与等待仍完整`() {
        val window = RecordEvent(
            atMs = 500, kind = RecordEventKind.WINDOW_CHANGED,
            screenW = 1080, screenH = 2400,
            node = RecordNode(packageName = "com.demo.app"),
        )
        val r = compiler.compile("窗口", listOf(click(0), window, click(1400)))
        assertEquals(2, r.script.actions.count { it.type == ActionType.CLICK_NODE })
        // 窗口事件被丢弃不会吞掉它前后两次真实点击之间的等待
        assertTrue(r.script.actions.any { it.waitMs > 0 })
    }

}

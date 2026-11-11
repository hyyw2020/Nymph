package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.FilterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 多方言导入器的黄金样本测试（JVM，无 Android 依赖）。 */
class ImporterTest {

    private val zdjlScript = """
        {"name":"测试脚本","minVerCode":26000010,"screenWidth":1768,"screenHeight":800,
         "actions":[
           {"type":"点击","delay":500,"posData":{"x":"90.8%","y":"15.7%"},"duration":50},
           {"type":"滑动","posData":{"x":"10%","y":"20%"},"endPosData":{"x":"90%","y":"80%"},"duration":300},
           {"type":"点击图片","imageData":{"data":"__B64__"},"minSimilarPercent":82,"limitArea":"87.8% 11% 93.8% 22%"},
           {"type":"完全未知的动作","posData":{"x":"1%","y":"2%"}}
         ]}
    """.trimIndent()

    @Test
    fun `zdjl 方言导入基本动作并跳过未知类型`() {
        val r = Importer.importOrNull(zdjlScript)!!
        assertEquals("测试脚本", r.script.name)
        assertEquals(1768, r.script.refWidth)
        assertEquals(3, r.script.actions.size)
        assertTrue("note 应提示跳过", r.note.contains("跳过"))
        assertEquals(90.8, r.script.actions[0].x, 0.001)
        assertEquals(500L, r.script.actions[0].waitMs)
        assertEquals(90.0, r.script.actions[1].x2, 0.001)
        assertEquals("__B64__", r.script.actions[2].imageData)
        assertEquals(82, r.script.actions[2].imageSimilarity)
    }

    @Test
    fun `版本协商 - 脚本版本过高应拒绝`() {
        // 构造一个声明高于兼容基线（26000010）的 zdjl 脚本
        val future = zdjlScript.replace("26000010", "26999999")
        val r = Importer.import(future, curVerCode = 100)
        assertNull("来自更高版本的脚本应被拒绝", r.first)
        assertNotNull(r.second)
        assertTrue(r.second is Importer.Fail.VersionTooOld)
    }

    @Test
    fun `行格式截断修复`() {
        val line = """{"type":"点击","posData":{"x":"50%","y":"50%"},"condition":{"image":{"imageData":{"data":"AAAA"""
        val r = Importer.importOrNull(line)!!
        assertEquals(1, r.script.actions.size)
        assertTrue(r.note.contains("截断"))
    }

    @Test
    fun `打开链接映射 OPEN_LINK 并读取链接字段`() {
        val raw = """
            {"name":"链接脚本","minVerCode":26000010,
             "actions":[
               {"type":"打开链接","link":"https://example.com/a"},
               {"type":"打开链接","url":"market://details?id=x"},
               {"type":"打开应用","packageName":"com.x.y"}
             ]}
        """.trimIndent()
        val r = Importer.importOrNull(raw)!!
        assertEquals(3, r.script.actions.size)
        assertEquals(ActionType.OPEN_LINK, r.script.actions[0].type)
        assertEquals("https://example.com/a", r.script.actions[0].linkUrl)
        assertEquals(ActionType.OPEN_LINK, r.script.actions[1].type)
        assertEquals("market://details?id=x", r.script.actions[1].linkUrl)
        // 打开应用不受影响
        assertEquals(ActionType.OPEN_APP, r.script.actions[2].type)
        assertEquals("com.x.y", r.script.actions[2].packageName)
    }

    @Test
    fun `执行后删变量字段导入`() {
        val raw = """
            {"name":"一次性变量","minVerCode":26000010,
             "actions":[
               {"type":"设置变量","varName":"tmp","varValue":"1","deleteVarAfter":true},
               {"type":"设置变量","varName":"keep","varValue":"2"}
             ]}
        """.trimIndent()
        val r = Importer.importOrNull(raw)!!
        assertTrue(r.script.actions[0].deleteVarAfter)
        assertFalse(r.script.actions[1].deleteVarAfter)
    }

    @Test
    fun `中止条件 - 嵌套载荷规范化为 children`() {
        val raw = """
            {"name":"break 嵌套","minVerCode":26000010,
             "actions":[
               {"type":"点击","posData":{"x":"50%","y":"50%"},
                "condition":{"type":"break","condition":{"type":"image","imageData":{"data":"__B64__"}}}}
             ]}
        """.trimIndent()
        val r = Importer.importOrNull(raw)!!
        val cond = r.script.actions[0].condition!!
        assertEquals(ConditionKind.ABORT, cond.kind)
        assertEquals(1, cond.children.size)
        assertEquals(ConditionKind.APPEAR_IMAGE, cond.children[0].kind)
        assertEquals("__B64__", cond.children[0].imageData)
        assertFalse(cond.invert)
    }

    @Test
    fun `中止条件 - 顶层字段载荷按类型推断`() {
        val raw = """
            {"name":"break 顶层","minVerCode":26000010,
             "actions":[
               {"type":"点击图片","imageData":{"data":"__B64__"},
                "condition":{"type":"break","text":"任务完成","invert":true}}
             ]}
        """.trimIndent()
        val r = Importer.importOrNull(raw)!!
        val cond = r.script.actions[0].condition!!
        assertEquals(ConditionKind.ABORT, cond.kind)
        assertTrue("invert 应保留（取反命中）", cond.invert)
        assertEquals(ConditionKind.APPEAR_TEXT, cond.children[0].kind)
        assertEquals("任务完成", cond.children[0].text)
    }

    @Test
    fun `中止条件 - 脚本级 abortCondition 导入`() {
        val raw = """
            {"name":"break 脚本级","minVerCode":26000010,
             "abortCondition":{"type":"break","jsExpr":"vars.stop == 1"},
             "actions":[{"type":"点击","posData":{"x":"50%","y":"50%"}}]}
        """.trimIndent()
        val r = Importer.importOrNull(raw)!!
        val abort = r.script.abortCondition!!
        assertEquals(ConditionKind.ABORT, abort.kind)
        assertEquals(ConditionKind.JS_EXPR, abort.children[0].kind)
        assertEquals("vars.stop == 1", abort.children[0].jsExpr)
    }

    @Test
    fun `AI 找目标动作与条件导入`() {
        val raw = """
            {"name":"AI 脚本","minVerCode":26000010,
             "actions":[
               {"type":"AI找目标","aiLabel":"手机","limitArea":"0% 0% 50% 50%"},
               {"type":"点击图片","imageData":{"data":"__B64__"},
                "condition":{"type":"AI识别","aiDesc":"电视"}}
             ]}
        """.trimIndent()
        val r = Importer.importOrNull(raw)!!
        val ai = r.script.actions[0]
        assertEquals(ActionType.CLICK_AI, ai.type)
        assertEquals("手机", ai.aiLabel)
        assertEquals(0.0, ai.region.leftPct, 0.001)
        assertEquals(50.0, ai.region.rightPct, 0.001)
        val cond = r.script.actions[1].condition!!
        assertEquals(ConditionKind.APPEAR_AI, cond.kind)
        assertEquals("电视", cond.aiLabel)
    }

    @Test
    fun `空输入返回 Empty 失败`() {
        val r = Importer.import("   ")
        assertNull(r.first)
        assertTrue(r.second is Importer.Fail.Empty)
    }

    /** 自动精灵 v2.21 行分隔 .zjs 方言（真实样本"热爱生活.zjs"结构提炼：每个逻辑 JSON 对象独占一行）。 */
    private val zjsScript = listOf(
        """{"repeatCount":1,"minVerCode":2021000,"minVerName":"2.21.0","lastSaveScreenWidth":1080,"lastSaveScreenHeight":2400,"description":"热爱生活"}""",
        """{"type":"运行多个动作","delay":"1","delayUnit":0,"repeatCount":1,"scriptSet":[{"type":"location","x":"83.6%","y":"92.3%"},{"type":"点击","delay":"1","delayUnit":0,"duration":"50","posData":{"x":"50%","y":"50%"},"scriptCallbacks":{"afterConditionFail":{"type":"控制执行","delay":"2","delayUnit":1,"controlRunType":"waitDelay"}}},{"type":"点击图片","delay":"500","duration":"5","condition":{"type":"image","image":{"type":"image","imageData":{"data":"__B64__"},"limitArea":"0% 0% 100% 50%","minSimilarPercent":82}}},{"type":"控制执行","delay":"2","delayUnit":1,"controlRunType":"waitDelay"},{"type":"控制执行","controlRunType":"stop"},{"type":"点击","posData":{"x":"10%","y":"20%"},"condition":{"type":"conditionSet","matchMode":"or","conditions":[{"type":"image","imageData":{"data":"__B64__"}},{"type":"textFount","text":{"type":"text","text":"拾取","limitArea":"84.5% 0% 100% 23%"}}]}},{"type":"点击图片","condition":{"type":"timeAfter","timeValue":"2026-03-31 04:17:02"}},{"type":"点击图片","condition":{"type":"image","image":{"type":"image","imageData":{"data":"__B64__"},"imageFilter":{"type":"bw","greyAlgorithm":"weighted_average","threshold":100}}}}]}""",
    ).joinToString("\n")

    @Test
    fun `zjs 行分隔方言完整导入`() {
        val r = Importer.importOrNull(zjsScript)!!
        // 元数据行：description 作脚本名，参考分辨率来自 lastSaveScreen*
        assertEquals("热爱生活", r.script.name)
        assertEquals(1080, r.script.refWidth)
        assertEquals(2400, r.script.refHeight)
        assertTrue("note 应标注行分隔格式", r.note.contains("行分隔"))

        // 顶层 1 个"运行多个动作"包裹层（保留自身 condition/listeners），内含 8 个子动作
        assertEquals(1, r.script.actions.size)
        val wrapper = r.script.actions[0]
        assertEquals(ActionType.RUN_ACTIONS, wrapper.type)
        assertEquals(8, wrapper.children.size)
        assertTrue("全部类型受支持，不应有跳过", !r.note.contains("跳过"))

        // location：顶层 x/y 百分比
        val loc = wrapper.children[0]
        assertEquals(ActionType.TAP, loc.type)
        assertEquals(83.6, loc.x, 0.001)
        assertEquals(92.3, loc.y, 0.001)

        // 点击：posData + scriptCallbacks.afterConditionFail → onConditionFail 监听
        val tap = wrapper.children[1]
        assertEquals(50.0, tap.x, 0.001)
        assertEquals(1, tap.listeners.onConditionFail.size)
        assertEquals(ActionType.WAIT, tap.listeners.onConditionFail[0].type)
        assertEquals(2000L, tap.listeners.onConditionFail[0].waitMs) // delayUnit 1 = 秒

        // 点击图片：条件图片（image.imageData 双层路径 + minSimilarPercent + limitArea）
        val img = wrapper.children[2]
        assertEquals(ActionType.CLICK_IMAGE, img.type)
        assertEquals("__B64__", img.imageData)
        assertEquals(82, img.imageSimilarity)
        assertNotNull("条件应被保留", img.condition)
        assertEquals(ConditionKind.APPEAR_IMAGE, img.condition!!.kind)

        // 控制执行 waitDelay → WAIT（2 秒）
        val wait = wrapper.children[3]
        assertEquals(ActionType.WAIT, wait.type)
        assertEquals(2000L, wait.waitMs)

        // 控制执行 stop → STOP
        assertEquals(ActionType.STOP, wrapper.children[4].type)

        // 组合条件 conditionSet(matchMode=or) → COMBINE/ANY，含 image + textFount 两个子条件
        val combo = wrapper.children[5]
        val cond = combo.condition!!
        assertEquals(ConditionKind.COMBINE, cond.kind)
        assertEquals(Combine.ANY, cond.combine)
        assertEquals(2, cond.children.size)
        assertEquals(ConditionKind.APPEAR_IMAGE, cond.children[0].kind)
        assertEquals(ConditionKind.APPEAR_TEXT, cond.children[1].kind)
        assertEquals("拾取", cond.children[1].text)

        // timeAfter 时间门条件：绝对时间字符串 → epoch 毫秒
        val timeGated = wrapper.children[6]
        val timeCond = timeGated.condition!!
        assertEquals(ConditionKind.AFTER_TIME, timeCond.kind)
        val expectedMs = LocalDateTime.parse("2026-03-31 04:17:02", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedMs, timeCond.afterTimeMs)

        // v2.21 单数 imageFilter（bw）→ 黑白滤镜
        val filtered = wrapper.children[7]
        val filters = filtered.condition!!.filters
        assertEquals(1, filters.size)
        assertEquals(FilterKind.BLACK_WHITE, filters[0].kind)
        assertEquals(100, filters[0].threshold)
    }

    @Test
    fun `zjs 版本协商 - 未来版本应拒绝`() {
        val future = zjsScript.replace("2021000", "26999999")
        val r = Importer.import(future, curVerCode = 100)
        assertNull(r.first)
        assertTrue(r.second is Importer.Fail.VersionTooOld)
    }
}

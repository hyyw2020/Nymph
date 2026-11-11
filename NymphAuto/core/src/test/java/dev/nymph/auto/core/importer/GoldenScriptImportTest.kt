package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.ListOrder
import dev.nymph.auto.core.runner.dependencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden round-trip：用户真实脚本《热爱生活》（57 步）当固定测试向量。
 *
 * 文件SHA256 = 20e901fab27f0a0d85a1111c56adae40cb9e7091125d21c8cf4469a18151fda9，
 * 一个字节都不改；断言全部来自该文件的事前事实清单（排查报告基线）。
 *
 * 最关键的一条：顶层 repeatCount 必须保持 **0**（自动精灵"无限循环"哨兵）——
 * 曾经的 coerceAtLeast(1) 会把它改成 1，脚本跑一轮就停（H1/D1 根因）。
 */
class GoldenScriptImportTest {

    private val raw: String by lazy {
        javaClass.getResourceAsStream("/golden_reashenghuo.zjs")
            ?.bufferedReader()?.readText()
            ?: error("golden 脚本资源缺失")
    }

    private val result: Importer.Result by lazy {
        val (res, fail) = Importer.import(raw, curVerCode = 26_000_000)
        res ?: error("导入失败：${fail?.reason ?: "未知原因"}")
    }

    private val script get() = result.script

    @Test
    fun `顶层 repeatCount 保持 0（无限循环哨兵，D1 根因修复）`() {
        assertEquals("顶层 repeatCount 必须原样保留 0，否则脚本跑一轮就停", 0, script.repeatCount)
    }

    @Test
    fun `57 步全量导入无跳过`() {
        assertEquals(57, script.totalActionCount())
        assertFalse("不应有任何步骤被跳过：${result.note}", result.note.contains("跳过"))
    }

    @Test
    fun `顶层 3 个动作且第三个是停止`() {
        assertEquals(3, script.actions.size)
        assertEquals(ActionType.RUN_ACTIONS, script.actions[0].type)
        assertEquals(ActionType.RUN_ACTIONS, script.actions[1].type)
        assertEquals(ActionType.STOP, script.actions[2].type) // 控制执行 controlRunType=stop
    }

    @Test
    fun `multiThread 英文枚举识别为并行（中英文双轨）`() {
        assertEquals(ListOrder.PARALLEL, script.actions[0].order)
        assertEquals(25, script.actions[0].children.size)
        assertEquals(ListOrder.PARALLEL, script.actions[1].order)
        // 战斗模式内嵌组 runMode=random → RANDOM
        val inner = script.actions[1].children.firstOrNull { it.order == ListOrder.RANDOM }
        assertTrue("内嵌 random 组未识别为乱序", inner != null)
        assertEquals(3, inner!!.children.size)
    }

    @Test
    fun `参考分辨率 800x1768 竖屏`() {
        assertEquals(800, script.refWidth)
        assertEquals(1768, script.refHeight)
    }

    @Test
    fun `组合条件 matchMode 英文枚举全部识别`() {
        val combines = mutableListOf<Combine>()
        fun walk(a: dev.nymph.auto.core.model.Action) {
            a.condition?.let { c ->
                if (c.kind == ConditionKind.COMBINE) {
                    combines += c.combine
                    c.children.forEach { walk(dev.nymph.auto.core.model.Action(condition = it)) }
                }
            }
            a.children.forEach { walk(it) }
        }
        script.actions.forEach { walk(it) }
        // 事实清单：or×1 / and×3 / count×1
        assertEquals(1, combines.count { it == Combine.ANY })
        assertEquals(3, combines.count { it == Combine.ALL })
        assertEquals(1, combines.count { it == Combine.N })
    }

    @Test
    fun `count 组合的 matchCount 读取为 1`() {
        var found: Int? = null
        fun walk(a: dev.nymph.auto.core.model.Action) {
            a.condition?.let { c ->
                if (c.kind == ConditionKind.COMBINE && c.combine == Combine.N) found = c.runCountLimit
                c.children.forEach { walk(dev.nymph.auto.core.model.Action(condition = it)) }
            }
            a.children.forEach { walk(it) }
        }
        script.actions.forEach { walk(it) }
        assertEquals(1, found)
    }

    @Test
    fun `textFount 条件保留为文字条件（OCR 化在执行层）`() {
        var textConds = 0
        fun walkCond(c: dev.nymph.auto.core.model.Condition) {
            if (c.kind == ConditionKind.APPEAR_TEXT) textConds++
            c.children.forEach { walkCond(it) }
        }
        fun walk(a: dev.nymph.auto.core.model.Action) {
            a.condition?.let { walkCond(it) }
            a.children.forEach { walk(it) }
        }
        script.actions.forEach { walk(it) }
        assertEquals(15, textConds)
    }

    @Test
    fun `afterConditionFail 回调节拍映射为 onConditionFail（17 个等待）`() {
        var waits = 0
        fun walk(a: dev.nymph.auto.core.model.Action) {
            waits += a.listeners.onConditionFail.count { it.type == ActionType.WAIT }
            a.children.forEach { walk(it) }
        }
        script.actions.forEach { walk(it) }
        assertEquals("失败后等待重试的节拍必须保住，否则条件不满足时空转烧屏", 17, waits)
    }

    @Test
    fun `脚本依赖清单标记需要截图`() {
        val deps = script.dependencies()
        assertTrue(deps.needsScreenshot)
        assertTrue(deps.oneLine().contains("800×1768"))
    }

    @Test
    fun `脚本级 globalCallbacks 为空不炸（beforeScript afterScript 为空列表）`() {
        assertTrue(script.listeners.beforeScript.isEmpty())
        assertTrue(script.listeners.afterScript.isEmpty())
    }
}

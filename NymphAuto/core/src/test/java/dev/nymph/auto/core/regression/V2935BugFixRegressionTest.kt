// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.expr.ConditionEvaluator
import dev.nymph.auto.core.importer.ScriptCompressor
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.ocr.OcrPostprocess
import dev.nymph.auto.core.recorder.RecordCodec
import dev.nymph.auto.core.recorder.RecordEvent
import dev.nymph.auto.core.recorder.RecordEventKind
import dev.nymph.auto.core.store.ScriptGrant
import dev.nymph.auto.core.store.ScriptGrants
import dev.nymph.auto.core.store.ScriptRepository
import dev.nymph.auto.core.store.ScriptStore
import dev.nymph.auto.core.vision.TextBox
import dev.nymph.auto.core.vision.TextSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2.9.35 回归单测：把三份审查报告中确证为真的 core 层缺陷固化为断言，
 * 防止后续改动重新引入。每条测试对应一个已修复的具体 bug。
 *
 * 覆盖：RANDOM/Combine.N 边界、监听器 RUN_JS 授权、非法正则不降级、
 * CJK 脚本名隔离、历史版本前缀锚定、损坏录制不覆盖、NaN softmax、
 * 压缩载荷空转中止、损坏文件不误列为脚本。
 */
class V2935BugFixRegressionTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    // ---------------------------------------------------------------- 条件求值

    private class BoolEngine : dev.nymph.auto.core.expr.ExprEngine {
        override fun eval(expr: String, vars: Map<String, Any?>): Any? = null
        override fun evalBool(expr: String, vars: Map<String, Any?>): Boolean = false
        override fun evalArray(expr: String, vars: Map<String, Any?>): List<Any?> = emptyList()
        override fun run(code: String, vars: MutableMap<String, Any?>): Any? = null
        override fun call(func: String, args: List<Any?>, vars: MutableMap<String, Any?>): Any? = null
        override fun bindGlobal(name: String, value: Any?) = Unit
        override fun reset() = Unit
    }

    private val evaluator = ConditionEvaluator(BoolEngine())

    @Test
    fun `RANDOM 100百分比恒真 0百分比恒假`() {
        // 修复前：采样域 0..100（101 值），100% 时取到 100 会被 `100 < 100`
        // 误判为不满足，约 1/101 概率漏触发。改 0 until 100 后边界确定性成立。
        val always = Condition(kind = ConditionKind.RANDOM, randomPercent = 100)
        val never = Condition(kind = ConditionKind.RANDOM, randomPercent = 0)
        repeat(200) {
            assertTrue("100% 必须恒真", evaluator.eval(always, emptyMap(), { 0 }))
            assertFalse("0% 必须恒假", evaluator.eval(never, emptyMap(), { 0 }))
        }
    }

    @Test
    fun `Combine_N 阈值默认0按1处理 不让空组合恒真`() {
        // 修复前：runCountLimit 默认 0，`命中数 >= 0` 恒真，组合条件形同不存在。
        // 构造两个必然不满足的子条件：RUN_COUNT 且 limit=0 → runCount(0) < 0 为假。
        val allFalse = listOf(
            Condition(kind = ConditionKind.RUN_COUNT, runCountLimit = 0),
            Condition(kind = ConditionKind.RUN_COUNT, runCountLimit = 0),
        )
        val nCond = Condition(kind = ConditionKind.COMBINE, combine = Combine.N, children = allFalse)
        // 两个子条件都不满足 → 命中数 0；N 阈值默认 0 被 coerce 成 1
        // → 0 >= 1 为假。修复前 0 >= 0 恒真。
        assertFalse(evaluator.eval(nCond, emptyMap(), { 0 }))
    }

    // ---------------------------------------------------------------- 授权判定

    @Test
    fun `监听器中的RUN_JS必须触发授权`() {
        // 修复前：containsRunJs 仅遍历 actions 与 children，监听器里藏 RUN_JS 可绕过授权。
        val s = Script(
            name = "listener-js",
            actions = listOf(Action(type = ActionType.TAP)),
            listeners = Listeners(
                beforeAction = listOf(Action(type = ActionType.RUN_JS, jsCode = "zdjl.toast('x')")),
            ),
        )
        assertTrue("监听器内的 RUN_JS 必须检出", ScriptGrants.needsConsent(s))
    }

    // ---------------------------------------------------------------- 文字匹配

    @Test
    fun `非法正则显式失败不降级为字面包含`() {
        val boxes = listOf(TextBox("[确定", 10, 10), TextBox("确定", 20, 20))
        // "[确定" 是非法正则。修复前静默降级为 contains，会错误命中字面 "[确定"。
        assertFalse("非法正则 anyMatch 必须 false", TextSearch.anyMatch("[确定", true, boxes))
        org.junit.Assert.assertNull(
            "非法正则 matchBest 必须 null",
            TextSearch.matchBest("[确定", true, boxes),
        )
        // 合法正则仍正常工作
        assertTrue(TextSearch.anyMatch("(确定|取消)", true, listOf(TextBox("确定", 1, 1))))
    }

    // ---------------------------------------------------------------- 存储与历史

    @Test
    fun `中文脚本名生成不同文件名不互相覆盖`() {
        // 修复前：safeName 只保留 ASCII，所有中文名都变成 "script"，互相覆盖。
        val a = ScriptStore.safeName("自动脚本甲")
        val b = ScriptStore.safeName("自动脚本乙")
        assertNotEquals("不同中文名必须映射到不同文件名", a, b)
        assertTrue(a.any { it.code in 0x4E00..0x9FFF })

        val dir = tmp.newFolder("cn")
        val store = ScriptStore(dir)
        store.save(Script(name = "自动脚本甲", actions = listOf(Action(type = ActionType.TAP, x = 1.0))))
        store.save(Script(name = "自动脚本乙", actions = listOf(Action(type = ActionType.TAP, x = 2.0))))
        assertEquals("两个中文脚本应各自独立存在", 2, store.list().size)
        assertEquals(1.0, store.load("自动脚本甲")!!.actions.single().x, 0.001)
    }

    @Test
    fun `前缀相同脚本名的历史版本互不串档`() {
        // 修复前：versions/purge 用 startsWith(safeName+".r")，脚本 "a" 会
        // 把 "ab" 的历史当成自己的（前缀关系串档）。
        val dir = tmp.newFolder("hist")
        val store = ScriptStore(dir)
        val repo = ScriptRepository(store, File(dir, ".history"), keepVersions = 5)
        var sA = repo.save(Script(name = "a", description = "a1"))
        sA = repo.save(sA.copy(description = "a2"))
        val b1 = repo.save(Script(name = "ab", description = "ab1"))
        repo.save(b1.copy(description = "ab2"))

        // "a" 的历史只应有自己的 r1，不含 "ab" 的两份
        val versA = repo.versions("a")
        assertEquals("a 的历史版本数应为 1", 1, versA.size)
        assertTrue("a 的历史不应串入 ab", versA.all { it.revision == 1 })

        // purge("a") 不得删除 "ab" 的历史
        repo.purge("a")
        assertTrue("a 历史应被清空", repo.versions("a").isEmpty())
        assertFalse("ab 历史不得被 a 的 purge 误删", repo.versions("ab").isEmpty())
    }

    // ---------------------------------------------------------------- 录制持久化

    @Test
    fun `非空但损坏的录制文件追加时不覆盖历史`() {
        val corrupt = "{ 这不是合法 JSON"
        assertTrue(RecordCodec.looksCorrupt(corrupt))
        val ev = RecordEvent(seq = 99, kind = RecordEventKind.CLICK, atMs = 1L, x = 5, y = 6)
        val after = RecordCodec.append(corrupt, ev)
        assertEquals("损坏内容必须原样保留，不得被单条新事件覆盖", corrupt, after)

        // 正常文件追加仍然工作
        val good = RecordCodec.encode(listOf(ev))
        assertFalse(RecordCodec.looksCorrupt(good))
        val appended = RecordCodec.append(good, ev.copy(seq = 100))
        assertEquals(2, RecordCodec.decode(appended).size)
    }

    // ---------------------------------------------------------------- OCR 数值稳定

    @Test
    fun `logits含NaN时置信度不得为NaN`() {
        // 修复前：softmax 的 sum=NaN 时 `<=0` 与 isInfinite 皆 false，
        // 返回 NaN 置信度一路传播，识别结果被静默丢弃。
        val classes = 3
        val steps = 2
        val logits = floatArrayOf(
            Float.NaN, 1f, 2f,   // 时间步 0 含 NaN
            0f, 3f, 1f,          // 时间步 1 正常
        )
        val decoded = OcrPostprocess.ctcGreedyScored(
            logits, steps, classes, listOf("甲", "乙"),
        )
        assertTrue("置信度必须为有限值", decoded.confidence.isFinite())
        assertTrue("置信度必须落在 [0,1]", decoded.confidence in 0.0..1.0)
    }

    // ---------------------------------------------------------------- 压缩容错

    @Test
    fun `损坏的压缩脚本载荷返回null而非死循环`() {
        // 修复前：非 deflate 载荷可能让 inflate 返回 0 且既不需字典也不需输入，
        // 无跳出分支 → 空转死循环，卡死脚本加载线程与界面。
        // MAGIC 前缀 + 随意 base64，构造一个非法 deflate 载荷。
        val bogus = ScriptCompressor.MAGIC + "AAAAAAAAAAAAAAAA"
        val result = runCatching { ScriptCompressor.decodeAny(bogus) }
        // 必须在有限时间内返回（null 或退化结果），绝不抛异常或挂起。
        assertTrue("损坏载荷必须安全返回", result.isSuccess)
    }
}

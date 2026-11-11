package dev.nymph.auto.runner

import dev.nymph.auto.core.expr.ExprEngine
import dev.nymph.auto.core.model.VarType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * QuickJsEngine 的 JVM 单测（假绑定，不需要 Android 设备 / native so）。
 *
 * 覆盖引擎与绑定之间的契约：vars 整体推入 + 写回、错误哨兵、
 * undefined→null、call 的 arguments 注入、reset、installShim 的
 * prelude 语义，以及降级引擎 InMemoryEngine 的基本行为。
 */
class QuickJsEngineTest {

    /**
     * 内存假绑定：复刻 native 侧语义——
     *  - pushVars/setVarsObject：整体替换 JS 侧变量表；
     *  - syncVars：把 JS 侧表合并回 Kotlin 表（逐键覆盖）；
     *  - eval：执行 [onEval]（可模拟 JS 对 vars 的写操作）并返回其结果；
     *    返回含 __nymph_error__ 的 map 模拟 JS 异常。
     */
    private class FakeBinding : QuickJsBinding {
        val jsVars = mutableMapOf<String, Any?>()
        val globals = mutableMapOf<String, Any?>()
        var lastCode: String? = null
        var evalCount = 0
        var resetCount = 0
        var disposed = false

        /** eval 时对 JS 侧变量表的模拟写操作；返回值即 eval 结果。 */
        var onEval: (String) -> Any? = { null }

        override fun pushVar(name: String, value: Any?) {
            jsVars[name] = value
        }

        override fun pushVars(vars: Map<String, Any?>) {
            jsVars.clear()
            jsVars.putAll(vars)
        }

        override fun pushGlobal(name: String, value: Any?) {
            globals[name] = value
        }

        override fun syncVars(vars: MutableMap<String, Any?>) {
            for ((k, v) in jsVars) vars[k] = v
        }

        override fun eval(code: String): Any? {
            lastCode = code
            evalCount++
            return onEval(code)
        }

        override fun reset() {
            resetCount++
            jsVars.clear()
            globals.clear()
        }

        override fun dispose() {
            disposed = true
        }
    }

    private fun engine(): Pair<QuickJsEngine, FakeBinding> {
        val b = FakeBinding()
        return QuickJsEngine(b) to b
    }

    // ------------------------------------------------------------ eval / vars

    @Test
    fun `eval 返回绑定结果并回写 JS 侧变量`() {
        val (e, b) = engine()
        val vars = mutableMapOf<String, Any?>("a" to 1)
        b.onEval = { code ->
            assertTrue("表达式应被括号包裹", code.startsWith("(") && code.endsWith(")"))
            b.jsVars["b"] = 2 // 模拟 JS 里 vars.b = 2
            3
        }
        assertEquals(3, e.eval("vars.a + 2", vars))
        // 回写：JS 侧新写的键进入 Kotlin 变量表
        assertEquals(2, vars["b"])
        assertEquals(1, vars["a"])
    }

    @Test
    fun `eval 传入不可变表时不回写也不崩`() {
        val (e, b) = engine()
        val vars = mapOf<String, Any?>("a" to 1)
        b.onEval = { b.jsVars["b"] = 2; 3 }
        assertEquals(3, e.eval("vars.a + 2", vars))
        assertEquals(1, vars.size) // 不可变拷贝不受影响
    }

    @Test
    fun `pushVars 是整体替换语义`() {
        val (e, b) = engine()
        val vars = mutableMapOf<String, Any?>("a" to 1)
        e.eval("1", vars)
        assertEquals(mapOf("a" to 1), b.jsVars)
        vars.clear()
        vars["c"] = 9
        e.eval("1", vars)
        assertEquals(mapOf("c" to 9), b.jsVars) // 旧键 a 已被替换掉
    }

    @Test
    fun `run 回写变量且不包括号`() {
        val (e, b) = engine()
        val vars = mutableMapOf<String, Any?>()
        b.onEval = { code ->
            assertFalse(code.startsWith("("))
            b.jsVars["done"] = true
            "ok"
        }
        assertEquals("ok", e.run("vars.done = true", vars))
        assertEquals(true, vars["done"])
    }

    @Test
    fun `call 注入 arguments 并回写`() {
        val (e, b) = engine()
        val vars = mutableMapOf<String, Any?>()
        b.onEval = { code ->
            assertEquals("(fn)(...arguments)", code)
            assertEquals(listOf(1, "x"), b.globals["arguments"])
            b.jsVars["called"] = 1
            42
        }
        assertEquals(42, e.call("fn", listOf(1, "x"), vars))
        assertEquals(1, vars["called"])
    }

    // ------------------------------------------------------------ 值语义

    @Test
    fun `evalBool 按布尔 数值 字符串 假值规则`() {
        val (e, b) = engine()
        val vars = mutableMapOf<String, Any?>()
        b.onEval = { true }; assertTrue(e.evalBool("1", vars))
        b.onEval = { false }; assertFalse(e.evalBool("0", vars))
        b.onEval = { 1 }; assertTrue(e.evalBool("1", vars))
        b.onEval = { 0 }; assertFalse(e.evalBool("0", vars))
        b.onEval = { 2.5 }; assertTrue(e.evalBool("2.5", vars))
        b.onEval = { "yes" }; assertTrue(e.evalBool("yes", vars))
        b.onEval = { "" }; assertFalse(e.evalBool("", vars))
        b.onEval = { "false" }; assertFalse(e.evalBool("false", vars))
        b.onEval = { null }; assertFalse(e.evalBool("undefined", vars))
    }

    @Test
    fun `evalArray 解包列表 非列表给空表`() {
        val (e, b) = engine()
        val vars = mutableMapOf<String, Any?>()
        b.onEval = { listOf(1, 2, 3) }
        assertEquals(listOf(1, 2, 3), e.evalArray("[1,2,3]", vars))
        b.onEval = { "nope" }
        assertEquals(emptyList<Any?>(), e.evalArray("'nope'", vars))
    }

    @Test
    fun `undefined 返回 null`() {
        val (e, b) = engine()
        b.onEval = { null }
        assertNull(e.eval("undefined", mutableMapOf()))
    }

    @Test
    fun `错误哨兵转为 ExprException`() {
        val (e, b) = engine()
        // 哨兵解包发生在绑定层（native JSON → ExprException），
        // 假绑定按绑定层契约直接抛
        b.onEval = { throw ExprEngine.ExprException("boom: x is not defined") }
        try {
            e.eval("x + 1", mutableMapOf())
            fail("应抛 ExprException")
        } catch (ex: ExprEngine.ExprException) {
            assertTrue(ex.message!!.contains("boom"))
        }
    }

    // ------------------------------------------------------------ 全局与重置

    @Test
    fun `bindGlobal 推送并 reset 重建`() {
        val (e, b) = engine()
        e.bindGlobal("g", 7)
        assertEquals(7, b.globals["g"])
        e.reset()
        assertEquals(1, b.resetCount)
        assertTrue(b.globals.isEmpty())
        assertTrue(b.jsVars.isEmpty())
    }

    @Test
    fun `installShim 不覆盖 QuickJS 原生 prelude`() {
        val (e, b) = engine()
        val api = object : ZdjlApi {
            override val vars = mutableMapOf<String, Any?>()
            override suspend fun check(condName: String) = false
            override fun log(msg: String) = Unit
            override suspend fun toast(msg: String) = Unit
            override suspend fun sleep(ms: Long) = Unit
            override suspend fun findImage(b64: String) = emptyList<Pair<Int, Int>>()
            override suspend fun findText(text: String) = emptyList<Pair<Int, Int>>()
            override fun deviceInfo() = emptyMap<String, Any>()
        }
        QuickJsEngine.installShim(e, api)
        // zdjl/vars 由 native prelude 提供，不得被 Kotlin 对象序列化覆盖
        assertFalse(b.globals.containsKey("zdjl"))
        assertFalse(b.globals.containsKey("vars"))
    }

    // ------------------------------------------------------------ 降级引擎

    @Test
    fun `InMemoryEngine 降级行为`() {
        val e = InMemoryEngine()
        assertTrue(e.evalBool("a > 1", mapOf("a" to 2)))
        assertFalse(e.evalBool("a > 1", mapOf("a" to 1)))
        // SimpleExpr 对裸变量名返回其数值（历史口径）
        assertEquals(1.0, e.eval("a", mapOf("a" to 1)) as Double, 1e-9)
        assertEquals(emptyList<Any?>(), e.evalArray("[1]", mapOf()))
        assertNull(e.run("vars.x = 1", mutableMapOf()))
        assertNull(e.call("fn", listOf(1), mutableMapOf()))
        e.bindGlobal("g", 1)
        e.reset()
    }

    // ------------------------------------------------------------ 变量表联动

    @Test
    fun `VariableStore live 表经引擎回写持久保留`() {
        val (e, b) = engine()
        val store = VariableStore(e as ExprEngine)
        store.declare("n", VarType.NUMBER, "", "5")
        assertEquals(5.0, store.getNumber("n"), 1e-9)
        // 表达式求值期间 JS 侧写 vars.k = 9 → 回写进 live 表
        b.onEval = { b.jsVars["k"] = 9; 5.0 }
        store.declare("m", VarType.NUMBER, "n + 0", "")
        store.getNumber("m")
        assertEquals(9.0, (store.live()["k"] as Number).toDouble(), 1e-9)
    }
}

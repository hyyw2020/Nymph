package dev.nymph.auto.core.expr

import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件求值器的 JVM 单测。重点是 RUN_COUNT 的计数键语义——
 * 历史 bug：所有动作共用一个空键计数器，任一动作运行都会让其他动作的
 * "运行次数 < N"条件一起涨（次数条件全部失真）。
 */
class ConditionEvaluatorTest {

    private class FakeEngine : ExprEngine {
        var lastVars: Map<String, Any?> = emptyMap()
        override fun eval(expr: String, vars: Map<String, Any?>): Any? {
            lastVars = vars
            return when (expr) {
                "vars.n > 1" -> ((vars["n"] as? Number)?.toDouble() ?: 0.0) > 1.0
                else -> null
            }
        }
        // 与真实引擎同口径：数值结果按 !=0 判真
        override fun evalBool(expr: String, vars: Map<String, Any?>): Boolean =
            when (val v = eval(expr, vars)) {
                is Boolean -> v
                is Number -> v.toDouble() != 0.0
                else -> false
            }
        override fun evalArray(expr: String, vars: Map<String, Any?>): List<Any?> = emptyList()
        override fun run(code: String, vars: MutableMap<String, Any?>): Any? = null
        override fun call(func: String, args: List<Any?>, vars: MutableMap<String, Any?>): Any? = null
        override fun bindGlobal(name: String, value: Any?) = Unit
        override fun reset() = Unit
    }

    private val counts = mutableMapOf<String, Int>()

    private val evaluator = ConditionEvaluator(FakeEngine())

    private fun runCount(key: String): Int = counts[key] ?: 0

    @Test
    fun `RUN_COUNT 按动作键独立计数`() {
        val cond = Condition(kind = ConditionKind.RUN_COUNT, runCountLimit = 3)
        // 动作 A 跑 2 次：A 条件满足（2<3）
        counts["A"] = 2
        assertTrue(evaluator.eval(cond, emptyMap(), ::runCount, "A"))
        // 同一条件下按 B 求值：B 没跑过，同样满足——但两者互不影响
        assertTrue(evaluator.eval(cond, emptyMap(), ::runCount, "B"))
        // A 跑满 3 次后不满足；B 仍满足（修复前 B 也会跟着不满足）
        counts["A"] = 3
        assertFalse(evaluator.eval(cond, emptyMap(), ::runCount, "A"))
        assertTrue(evaluator.eval(cond, emptyMap(), ::runCount, "B"))
    }

    @Test
    fun `JS_EXPR 条件走引擎并读变量`() {
        val cond = Condition(kind = ConditionKind.JS_EXPR, jsExpr = "vars.n > 1")
        assertFalse(evaluator.eval(cond, mapOf("n" to 1), ::runCount))
        assertTrue(evaluator.eval(cond, mapOf("n" to 2), ::runCount))
    }

    @Test
    fun `COMBINE 递归保持同一计数键`() {
        val child = Condition(kind = ConditionKind.RUN_COUNT, runCountLimit = 1)
        val all = Condition(kind = ConditionKind.COMBINE, children = listOf(child, child))
        counts["A"] = 1
        assertFalse("A 已满 1 次 → ALL 组合不满足", evaluator.eval(all, emptyMap(), ::runCount, "A"))
        assertTrue("B 未跑过 → ALL 组合满足", evaluator.eval(all, emptyMap(), ::runCount, "B"))
    }
}

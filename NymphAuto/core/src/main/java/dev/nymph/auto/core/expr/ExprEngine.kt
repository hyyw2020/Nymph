package dev.nymph.auto.core.expr

import dev.nymph.auto.core.model.Condition

/**
 * 表达式引擎契约（core 只定义接口；QuickJS 实现在 app 层）。
 *
 * 为什么抽象：ScriptRunner 的条件判断、变量求值全部走表达式，
 * 单元测试可用内存实现替换，无需启动 Android。
 */
interface ExprEngine {
    /** 求值标量表达式（数值/字符/布尔/颜色字面量等）。失败抛 [ExprException]。 */
    fun eval(expr: String, vars: Map<String, Any?>): Any?

    /** 求值布尔条件表达式。 */
    fun evalBool(expr: String, vars: Map<String, Any?>): Boolean

    /** 求值数组表达式。 */
    fun evalArray(expr: String, vars: Map<String, Any?>): List<Any?>

    /** 执行一段 JS 代码（"运行 JS 代码"动作）。 */
    fun run(code: String, vars: MutableMap<String, Any?>): Any?

    /** 调用 JS 函数（"JS 函数"动作），参数以 arguments 数组暴露。 */
    fun call(func: String, args: List<Any?>, vars: MutableMap<String, Any?>): Any?

    /** 注册原生 API（zdjl.* 命名空间由实现方绑定）。 */
    fun bindGlobal(name: String, value: Any?)

    /** 重置引擎与全部变量。 */
    fun reset()

    class ExprException(message: String) : Exception(message)
}

/**
 * 条件判断的统一入口：把 [Condition] 树编译/执行为布尔值。
 */
class ConditionEvaluator(private val engine: ExprEngine) {

    /**
     * @param runCount 运行次数查询（ScriptRunner 传入按动作描述索引的计数器）
     * @param runCountKey RUN_COUNT 条件归属的动作键（动作描述 desc）。
     *        历史 bug：曾经所有动作共用空键 ""，任一动作运行都会让其他动作的
     *        "运行次数 < N"条件一起涨——次数条件全部失真。
     */
    fun eval(
        cond: Condition,
        vars: Map<String, Any?>,
        runCount: (String) -> Int,
        runCountKey: String = "",
    ): Boolean {
        return when (cond.kind) {
            dev.nymph.auto.core.model.ConditionKind.JS_EXPR ->
                runCatching { engine.evalBool(cond.jsExpr, vars) }.getOrDefault(false)
            dev.nymph.auto.core.model.ConditionKind.RANDOM ->
                (0..100).random() < cond.randomPercent.coerceIn(0, 100)
            dev.nymph.auto.core.model.ConditionKind.RUN_COUNT ->
                runCount(runCountKey) < cond.runCountLimit
            dev.nymph.auto.core.model.ConditionKind.COMBINE -> {
                val results = cond.children.map { eval(it, vars, runCount, runCountKey) }
                when (cond.combine) {
                    dev.nymph.auto.core.model.Combine.ALL -> results.all { it }
                    dev.nymph.auto.core.model.Combine.ANY -> results.any { it }
                    dev.nymph.auto.core.model.Combine.N -> results.count { it } >= cond.runCountLimit
                }
            }
            // APPEAR_* / INTERVAL 由 app 层视觉与状态机求值后经 vars 注入，
            // 这里兜底为 false（执行器应先完成视觉判断再进入表达式求值）
            else -> false
        }
    }
}

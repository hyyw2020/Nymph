package dev.nymph.auto.runner

import dev.nymph.auto.core.model.VarType

/**
 * 变量表：12 种类型的运行时存储 + 表达式求值入口。
 * 值与表达式在取用时惰性计算；数组/键值对象透传 JS 侧结构。
 */
class VariableStore(
    private val engine: dev.nymph.auto.core.expr.ExprEngine,
) {
    private val values = mutableMapOf<String, Any?>()
    private val expressions = mutableMapOf<String, String>()
    private val types = mutableMapOf<String, VarType>()

    fun declare(name: String, type: VarType, expression: String, initial: String) {
        types[name] = type
        expressions[name] = expression
        values[name] = coerce(initial, type)
    }

    fun set(name: String, value: Any?) {
        values[name] = value
    }

    /** 删除变量的值（声明保留；"执行后删变量"的一次性变量用）。 */
    fun clearValue(name: String) {
        values.remove(name)
    }

    @Suppress("UNCHECKED_CAST")
    fun get(name: String): Any? {
        expressions[name]?.takeIf { it.isNotEmpty() }?.let { expr ->
            val v = runCatching { engine.eval(expr, live()) }.getOrNull()
            if (v != null) return v
        }
        return values[name]
    }

    fun getString(name: String): String = get(name)?.toString().orEmpty()

    fun getNumber(name: String): Double =
        when (val v = get(name)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

    fun getBool(name: String): Boolean =
        when (val v = get(name)) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.equals("true", true) || v == "1"
            else -> false
        }

    fun getList(name: String): List<Any?> =
        when (val v = get(name)) {
            is List<*> -> v as List<Any?>
            else -> emptyList()
        }

    fun snapshot(): Map<String, Any?> = values.toMap()

    /**
     * 变量表的 live 引用（非拷贝）：供表达式引擎推入/回写变量。
     * QuickJsEngine 每次 eval 前整体推入、eval 后把 JS 侧写操作合并回本表，
     * 故 JS 里 `vars.x = 1` 会持久保留到后续动作与条件。
     */
    fun live(): MutableMap<String, Any?> = values

    fun clear() {
        values.clear()
        expressions.clear()
        types.clear()
    }

    private fun coerce(raw: String, type: VarType): Any? = when (type) {
        VarType.NUMBER -> raw.toDoubleOrNull() ?: 0.0
        VarType.BOOL -> raw.equals("true", true) || raw == "1"
        VarType.COLOR -> raw.removePrefix("#").lowercase().toLongOrNull(16) ?: 0L
        else -> raw
    }
}

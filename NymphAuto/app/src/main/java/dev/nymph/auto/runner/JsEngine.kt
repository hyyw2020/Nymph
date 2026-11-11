package dev.nymph.auto.runner

import dev.nymph.auto.core.expr.ExprEngine

/**
 * QuickJS 引擎封装。
 *
 * 选型理由（见方案文档第六章）：QuickJS arm64 so ~1MB、完整 ES2020（正则/JSON）、
 * 可与自动精灵共用同一引擎，使 JS 部分行为一致。
 *
 * 依赖隔离策略：本类通过 [QuickJsBinding] 接口调用具体绑定库，
 * 工程未接入绑定库时编译通过、运行时报明确错误；接入后零改动启用。
 * 另提供 [InMemoryEngine] 作为单元测试与降级实现。
 */
class QuickJsEngine(
    private val binding: QuickJsBinding,
) : ExprEngine {

    private val globals = mutableMapOf<String, Any?>()

    override fun eval(expr: String, vars: Map<String, Any?>): Any? {
        binding.pushVars(vars)
        val r = binding.eval("($expr)")
        // JS 侧对 vars 的写操作回写（传入可变表时生效；条件求值传入的
        // 即 VariableStore.live()，故 JS 条件里 vars.x = 1 会持久保留）。
        // 不可变拷贝（如 mapOf 单例）回写会抛 UnsupportedOperationException，
        // 属正常情况（调用方没打算接收回写）——静默跳过，不炸条件求值。
        (vars as? MutableMap<String, Any?>)?.let { m ->
            runCatching { binding.syncVars(m) }
        }
        return r
    }

    override fun evalBool(expr: String, vars: Map<String, Any?>): Boolean {
        val v = eval(expr, vars)
        return when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty() && !v.equals("false", true)
            else -> false
        }
    }

    override fun evalArray(expr: String, vars: Map<String, Any?>): List<Any?> {
        val v = eval(expr, vars)
        @Suppress("UNCHECKED_CAST")
        return (v as? List<Any?>) ?: emptyList()
    }

    override fun run(code: String, vars: MutableMap<String, Any?>): Any? {
        binding.pushVars(vars)
        val r = binding.eval(code)
        binding.syncVars(vars)
        return r
    }

    override fun call(func: String, args: List<Any?>, vars: MutableMap<String, Any?>): Any? {
        binding.pushVars(vars)
        binding.pushGlobal("arguments", args)
        val r = binding.eval("($func)(...arguments)")
        binding.syncVars(vars)
        return r
    }

    override fun bindGlobal(name: String, value: Any?) {
        globals[name] = value
        binding.pushGlobal(name, value)
    }

    override fun reset() {
        globals.clear()
        binding.reset()
    }

    companion object {
        /**
         * 注册自动精灵兼容命名空间（zdjl.*）。
         *
         * 注意：QuickJsEngine 的 zdjl/vars 由 native prelude 提供
         * （cpp/quickjs_jni.c 的 PRELUDE，经 __nymphCallNative 上调
         * Kotlin 侧 [QuickJsAndroidBinding.onCall]），此处再绑定会把
         * Kotlin 对象序列化成无意义字符串覆盖 prelude，故对 QuickJsEngine
         * 跳过；本方法仅用于没有 native prelude 的引擎实现。
         */
        fun installShim(engine: ExprEngine, api: ZdjlApi) {
            if (engine is QuickJsEngine) return
            engine.bindGlobal("zdjl", api)
            engine.bindGlobal("vars", api.vars)
        }
    }
}

/**
 * 脚本可见的原生 API（对应自动精灵 JS 约定）。
 *
 * 视觉/等待类方法为 suspend：由 QuickJS 原生回调桥接到独立执行流
 * （见 [QuickJsAndroidBinding] 的 runBlockingSync），避免阻塞脚本协程
 * 所在的主线程；纯数据类（log/deviceInfo）同步执行。
 */
interface ZdjlApi {
    val vars: MutableMap<String, Any?>

    /** 按动作描述（desc）查找其运行条件并求值；未找到返回 false。 */
    suspend fun check(condName: String): Boolean
    fun log(msg: String)
    suspend fun toast(msg: String)
    suspend fun sleep(ms: Long)

    /** 找图：命中返回 [[x, y]]，未命中返回空列表（对齐自动精灵返回 null 的假值语义）。 */
    suspend fun findImage(b64: String): List<Pair<Int, Int>>

    /** 找文字（节点文本优先）：命中返回 [[x, y]]，未命中空列表。 */
    suspend fun findText(text: String): List<Pair<Int, Int>>
    fun deviceInfo(): Map<String, Any>
}

/**
 * QuickJS 绑定抽象：把具体库（quickjs-android / 自编译 so）隔在实现里。
 */
interface QuickJsBinding {
    fun pushVar(name: String, value: Any?)
    fun pushVars(vars: Map<String, Any?>)
    fun pushGlobal(name: String, value: Any?)

    /**
     * 把 JS 侧对 vars 的写操作合并回 Kotlin 变量表（键值对逐个覆盖）。
     * 无原生 vars 回读能力的绑定可用默认空实现。
     */
    fun syncVars(vars: MutableMap<String, Any?>) {}

    fun eval(code: String): Any?
    fun reset()
    fun dispose()
}

/**
 * 内存表达式引擎：支持数值/字符串/布尔字面量与简单二元运算，
 * 用于 JVM 单元测试与 QuickJS 不可用时的降级（不支持完整 JS）。
 */
class InMemoryEngine : ExprEngine {
    override fun eval(expr: String, vars: Map<String, Any?>): Any? = SimpleExpr.eval(expr, vars)

    /**
     * 注意：SimpleExpr 返回的是数值（1.0/0.0）而非 Boolean，
     * 曾经直接 `as? Boolean` 让降级引擎下所有数值条件恒为 false
     * （JS 条件在无 QuickJS 设备上全部不满足）。口径与 QuickJsEngine 一致。
     */
    override fun evalBool(expr: String, vars: Map<String, Any?>): Boolean =
        when (val v = SimpleExpr.eval(expr, vars)) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty() && !v.equals("false", true)
            else -> false
        }

    override fun evalArray(expr: String, vars: Map<String, Any?>): List<Any?> = emptyList()
    override fun run(code: String, vars: MutableMap<String, Any?>): Any? = null
    override fun call(func: String, args: List<Any?>, vars: MutableMap<String, Any?>): Any? = null
    override fun bindGlobal(name: String, value: Any?) = Unit
    override fun reset() = Unit
}

/** 极简表达式求值（+ - * / % 比较 逻辑），仅测试/降级用。 */
internal object SimpleExpr {
    fun eval(expr: String, vars: Map<String, Any?>): Any? {
        val e = expr.trim().replace("true", "1").replace("false", "0")
        return runCatching {
            val parts = e.split(Regex("(?=[<>=!&|])|(?<=[<>=!&|])"))
            var acc = parts.first().trim().toDoubleOrNull()
                ?: vars[parts.first().trim()]?.toString()?.toDoubleOrNull() ?: 0.0
            var i = 1
            while (i < parts.size - 1) {
                val op = parts[i].trim()
                val rhs = parts[i + 1].trim().toDoubleOrNull() ?: 0.0
                acc = when (op) {
                    "+" -> acc + rhs; "-" -> acc - rhs
                    "*" -> acc * rhs; "/" -> if (rhs != 0.0) acc / rhs else 0.0
                    ">" -> if (acc > rhs) 1.0 else 0.0
                    "<" -> if (acc < rhs) 1.0 else 0.0
                    else -> acc
                }
                i += 2
            }
            acc
        }.getOrNull()
    }
}

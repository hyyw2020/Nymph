package dev.nymph.auto.runner

import dev.nymph.auto.core.expr.ExprEngine
import dev.nymph.auto.core.log.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch

/**
 * QuickJS 的 Android 绑定：JNI 值编解码 + zdjl.* 命名空间上调分发。
 *
 * 单线程约定：同一实例的方法必须从同一线程调用（执行器串行调度满足）。
 * 找图/找文字/check 等视觉调用是 suspend 的，原生上调用 [runBlockingSync]
 * 桥接——JS 代码只会在脚本协程内执行，不会阻塞主线程之外的其他流程。
 *
 * 线程模型（关键，勿改）：
 *  - JS eval 发生在脚本协程线程（RunnerService 用 Main.immediate 调度）；
 *  - 原生回调 [onCall] 因此也在该线程上同步进入；
 *  - suspend 类 API 经独立的 Dispatchers.Default 执行流跑（见
 *    [runBlockingSync]），当前线程用闭锁等待——截图回调由框架线程内联
 *    投递（AutoClickService 用 `Executor { it.run() }`），不依赖主线程，
 *    故不会自等死锁；
 *  - 代价：JS 里调用 zdjl.findImage 期间主线程短期阻塞（与自动精灵的
 *    同步 findImage 语义一致，属脚本作者的选择）。
 */
class QuickJsAndroidBinding(
    private val api: ZdjlApi,
) : QuickJsBinding {

    private var ptr: Long = 0L

    init {
        check(QuickJsNative.available) { "libquickjs-jni.so 不可用" }
        ptr = QuickJsNative.create(this)
        check(ptr != 0L) { "QuickJS 运行时创建失败" }
    }

    // ---- QuickJsBinding ----

    override fun pushVar(name: String, value: Any?) {
        if (ptr == 0L) return
        QuickJsNative.setVar(ptr, name, encode(value).toString())
    }

    override fun pushVars(vars: Map<String, Any?>) {
        if (ptr == 0L) return
        QuickJsNative.setVarsObject(ptr, encodeMap(vars).toString())
    }

    override fun pushGlobal(name: String, value: Any?) {
        if (ptr == 0L) return
        QuickJsNative.setGlobal(ptr, name, encode(value).toString())
    }

    override fun eval(code: String): Any? {
        if (ptr == 0L) throw ExprEngine.ExprException("QuickJS 已释放")
        val out = QuickJsNative.eval(ptr, code)
        if (out.isEmpty()) return null // undefined
        val el = runCatching { json.parseToJsonElement(out) }.getOrElse {
            throw ExprEngine.ExprException("QuickJS 返回非 JSON：$out")
        }
        if (el is JsonObject && el.containsKey(ERROR_KEY)) {
            throw ExprEngine.ExprException(el[ERROR_KEY]?.toString()?.trim('"') ?: "JS 异常")
        }
        return decode(el)
    }

    /** 把 JS 侧对 vars 的写操作合并回 Kotlin 变量表（键值对逐个覆盖）。 */
    override fun syncVars(vars: MutableMap<String, Any?>) {
        if (ptr == 0L) return
        val out = QuickJsNative.syncVars(ptr)
        val obj = runCatching { json.parseToJsonElement(out) as? JsonObject }.getOrNull() ?: return
        for ((k, v) in obj) {
            if (!vars.containsKey(k) || vars[k] != decode(v)) vars[k] = decode(v)
        }
    }

    override fun reset() {
        if (ptr != 0L) QuickJsNative.reset(ptr)
    }

    override fun dispose() {
        if (ptr != 0L) {
            QuickJsNative.destroy(ptr)
            ptr = 0L
        }
    }

    /** 停止按钮：指令级中断正在执行的 JS。 */
    fun requestInterrupt() {
        if (ptr != 0L) QuickJsNative.interrupt(ptr)
    }

    // ---- native → Kotlin 上调（__nymphCallNative）----

    fun onCall(method: String, argsJson: String): String {
        val args: List<Any?> = runCatching {
            (json.parseToJsonElement(argsJson) as? JsonArray)?.map { decode(it) } ?: emptyList()
        }.getOrDefault(emptyList())
        val result: Any? = when (method) {
            "log" -> { api.log(args.getOrNull(0)?.toString() ?: ""); null }
            "toast" -> runBlockingSync { api.toast(args.getOrNull(0)?.toString() ?: "") }
            "sleep" -> runBlockingSync { api.sleep((args.getOrNull(0) as? Number)?.toLong() ?: 0L) }
            "findImage" -> runBlockingSync { api.findImage(args.getOrNull(0)?.toString() ?: "") }
            "findText" -> runBlockingSync { api.findText(args.getOrNull(0)?.toString() ?: "") }
            "check" -> runBlockingSync { api.check(args.getOrNull(0)?.toString() ?: "") }
            "deviceInfo" -> api.deviceInfo()
            else -> null
        }
        return encode(result).toString()
    }

    // ---- JSON 编解码 ----

    private fun encodeMap(map: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in map) put(k, encode(v))
    }

    private fun encode(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        // NaN/Infinity 不是合法 JSON（JS 0/0、Infinity 会出现）——置 null，
        // 否则 JsonPrimitive 抛异常会把整条脚本带崩
        is Float -> if (v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)
        is Double -> if (v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)
        is Number -> JsonPrimitive(v.toDouble())
        is CharSequence -> JsonPrimitive(v.toString())
        is Array<*> -> buildJsonArray { v.forEach { add(encode(it)) } }
        is List<*> -> buildJsonArray { v.forEach { add(encode(it)) } }
        is Map<*, *> -> buildJsonObject {
            for ((k, vv) in v) put(k.toString(), encode(vv))
        }
        is Pair<*, *> -> buildJsonArray { add(encode(v.first)); add(encode(v.second)) }
        else -> JsonPrimitive(v.toString())
    }

    private fun decode(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            el.isString -> el.content
            else -> el.content.toBooleanStrictOrNull()
                ?: el.content.toDoubleOrNull()
                ?: el.content
        }
        is JsonArray -> el.map { decode(it) }
        is JsonObject -> el.entries.associate { (k, v) -> k to decode(v) }
    }

    companion object {
        private const val ERROR_KEY = "__nymph_error__"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * suspend λ → 阻塞结果（仅用于脚本协程内的原生上调）。
         *
         * 为什么不能用 startCoroutine 直接读结果：截图/找图会真正挂起
         * （等框架回调），startCoroutine 返回时 result 还是 null，
         * 后续解包必然 NPE。改用闭锁 + 独立执行流才是正确的阻塞桥。
         */
        private fun <T> runBlockingSync(block: suspend () -> T): T? {
            val latch = CountDownLatch(1)
            var result: Result<T>? = null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope.launch {
                result = runCatching { block() }
                latch.countDown()
            }
            runCatching { latch.await() }
            scope.cancel()
            return result?.getOrNull() ?: run {
                result?.exceptionOrNull()?.let { e ->
                    RunLog.e("QuickJs", "原生上调失败：${e.message}")
                }
                null
            }
        }
    }
}

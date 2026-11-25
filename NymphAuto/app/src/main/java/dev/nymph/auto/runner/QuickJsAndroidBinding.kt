package dev.nymph.auto.runner

import dev.nymph.auto.core.expr.ExprEngine
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.runner.NativeHandle
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
import java.util.concurrent.TimeUnit

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

    // v2.9.45：原生 runtime 指针改为一次性句柄守卫。停止按钮（主线程）与脚本协程
    // 收尾可能并发进入销毁路径；裸 Long 的 check-then-act 会 double-free / UAF。
    // disposeOnce 用原子 getAndSet 保证 destroy 恰好执行一次，其余路径读到 0 直接跳过。
    private val handle: NativeHandle

    init {
        check(QuickJsNative.available) { "libquickjs-jni.so 不可用" }
        val created = QuickJsNative.create(this)
        check(created != 0L) { "QuickJS 运行时创建失败" }
        handle = NativeHandle(created)
    }

    /**
     * v2.9.46：停止标志。停止按钮置位后，[runBlockingSync] 的等待循环每 50ms 检查一次，
     * 立即取消桥接协程并返回，不再让脚本线程在闭锁上干等固定 30 秒。
     */
    @Volatile
    private var stopRequested = false

    /** 停止入口：置位停止标志 + 指令级中断 JS。任意线程可调。 */
    fun requestStop() {
        stopRequested = true
        handle.useIfAlive { p -> QuickJsNative.interrupt(p) }
    }

    // ---- QuickJsBinding ----

    override fun pushVar(name: String, value: Any?) {
        // v2.9.46：持读锁调用，杜绝 get() 后到 native 调用前被并发 dispose 的 UAF 窗口。
        handle.useIfAlive { p -> QuickJsNative.setVar(p, name, encode(value).toString()) }
    }

    override fun pushVars(vars: Map<String, Any?>) {
        handle.useIfAlive { p -> QuickJsNative.setVarsObject(p, encodeMap(vars).toString()) }
    }

    override fun pushGlobal(name: String, value: Any?) {
        handle.useIfAlive { p -> QuickJsNative.setGlobal(p, name, encode(value).toString()) }
    }

    override fun eval(code: String): Any? {
        // v2.9.46：整个 eval 在持有读锁期间执行——JS 运行期间 dispose 的写锁无法进入，
        // 从根本上杜绝「eval 进行中 runtime 被释放」的 UAF。用非空包装区分「已释放」(null)
        // 与「正常返回 undefined」(空字符串)。
        val wrapped = handle.useIfAlive { p -> EvalOutput(QuickJsNative.eval(p, code)) }
            ?: throw ExprEngine.ExprException("QuickJS 已释放")
        val out = wrapped.text
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
        handle.useIfAlive { p ->
            val out = QuickJsNative.syncVars(p)
            val obj = runCatching { json.parseToJsonElement(out) as? JsonObject }.getOrNull()
                ?: return@useIfAlive
            for ((k, v) in obj) {
                if (!vars.containsKey(k) || vars[k] != decode(v)) vars[k] = decode(v)
            }
        }
    }

    override fun reset() {
        handle.useIfAlive { p -> QuickJsNative.reset(p) }
    }

    override fun dispose() {
        handle.disposeOnce { p -> QuickJsNative.destroy(p) }
    }

    /** 停止按钮：指令级中断正在执行的 JS（持读锁，与 dispose 写锁互斥）。 */
    fun requestInterrupt() {
        handle.useIfAlive { p -> QuickJsNative.interrupt(p) }
    }

    // ---- native → Kotlin 上调（__nymphCallNative）----

    fun onCall(method: String, argsJson: String): String {
        val args: List<Any?> = runCatching {
            (json.parseToJsonElement(argsJson) as? JsonArray)?.map { decode(it) } ?: emptyList()
        }.getOrDefault(emptyList())
        // v2.9.46：按 API 拆分超时，不再把 30s 当所有调用的语义。
        // sleep 的等待上限 = 请求时长 + 余量（脚本写 sleep(60000) 就真正等 60s），
        // 且等待可被停止标志打断；find/check 用识别窗口上限；toast 用短上限。
        val result: Any? = when (method) {
            "log" -> { api.log(args.getOrNull(0)?.toString() ?: ""); null }
            "toast" -> runBlockingSync(TOAST_TIMEOUT_MS) { api.toast(args.getOrNull(0)?.toString() ?: "") }
            "sleep" -> {
                val ms = (args.getOrNull(0) as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
                if (ms <= 0L) null
                else runBlockingSync(ms + STOP_POLL_MS) { api.sleep(ms) }
            }
            "findImage" -> runBlockingSync(VISION_TIMEOUT_MS) { api.findImage(args.getOrNull(0)?.toString() ?: "") }
            "findText" -> runBlockingSync(VISION_TIMEOUT_MS) { api.findText(args.getOrNull(0)?.toString() ?: "") }
            "check" -> runBlockingSync(VISION_TIMEOUT_MS) { api.check(args.getOrNull(0)?.toString() ?: "") }
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

    /**
     * suspend λ → 阻塞结果（仅用于脚本协程内的原生上调）。
     *
     * 为什么不能用 startCoroutine 直接读结果：截图/找图会真正挂起（等框架回调），
     * startCoroutine 返回时 result 还是 null，后续解包必然 NPE。改用闭锁 + 独立执行流。
     *
     * v2.9.46 两处改进：
     *  1. [timeoutMs] 由调用方按 API 语义传入，sleep(60000) 不再被 30s 截断；
     *  2. 等待改为 [STOP_POLL_MS] 短周期轮询，一旦 [stopRequested]（停止按钮置位）
     *     立即取消桥接协程并返回，脚本线程无需干等满超时。桥接 scope 在退出时统一 cancel，
     *     杜绝协程泄漏到下一轮。
     */
    private fun <T> runBlockingSync(timeoutMs: Long, block: suspend () -> T): T? {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            result = runCatching { block() }
            latch.countDown()
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var completed = false
        try {
            while (!completed && !stopRequested && System.nanoTime() < deadline) {
                completed = latch.await(STOP_POLL_MS, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // 无论正常完成 / 超时 / 被停止，都取消桥接 scope，杜绝协程泄漏。
            scope.cancel()
        }
        if (!completed) {
            val why = if (stopRequested) "已收到停止请求" else "超时（${timeoutMs}ms）"
            RunLog.w("QuickJs", "原生上调中断：$why，按失败返回 null")
        }
        return result?.getOrNull() ?: run {
            result?.exceptionOrNull()?.let { e -> RunLog.e("QuickJs", "原生上调失败：${e.message}") }
            null
        }
    }

    /** eval 结果的非空包装，让 useIfAlive 的 null 只表示「句柄已释放」。 */
    private data class EvalOutput(val text: String)

    companion object {
        private const val ERROR_KEY = "__nymph_error__"
        private val json = Json { ignoreUnknownKeys = true }

        /** 停止检查间隔：停止后最多等这么久即从闭锁等待中返回。 */
        private const val STOP_POLL_MS = 50L
        /** 找图 / 找文字 / check 的识别窗口上限。 */
        private const val VISION_TIMEOUT_MS = 30_000L
        /** toast 的上调上限（纯 UI 分发，正常毫秒级完成）。 */
        private const val TOAST_TIMEOUT_MS = 3_000L
    }
}

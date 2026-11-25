package dev.nymph.auto.core.store

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Script
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 脚本运行授权（对标自动精灵三档权限：询问 / 允许一次 / 允许以后不再提示 /
 * 不允许一次 / 不允许以后不再提示）。
 *
 * 授权范围：**只对含"运行 JS 代码"动作的脚本生效**——任意 JS 代码执行是
 * 唯一真正敏感的能力（可调 zdjl.* 原生 API、可写变量、可死循环）。其余
 * 动作（点击/滑动/找图）本身就是用户录制的回放，不额外设卡。
 *
 * 持久化：ASK / ALLOW_ALWAYS / DENY_ALWAYS 落盘 grants.json；
 * ALLOW_ONCE / DENY_ONCE 只活在进程内（本次运行有效，杀进程即失效）。
 */
enum class ScriptGrant {
    /** 每次询问（默认）。 */
    ASK,

    /** 允许一次（进程内）。 */
    ALLOW_ONCE,

    /** 允许，以后不再提示（持久化）。 */
    ALLOW_ALWAYS,

    /** 不允许，此次运行不再提示（进程内）。 */
    DENY_ONCE,

    /** 不允许，以后不再提示（持久化）。 */
    DENY_ALWAYS,
}

/**
 * 授权状态存储。文件损坏时退化为全 ASK（安全侧默认值，不会误放行）。
 */
class ScriptGrants(private val dir: File) {

    /** 进程内一次性授权（ALLOW_ONCE/DENY_ONCE），不落盘。 */
    private val once = mutableMapOf<String, ScriptGrant>()

    @Serializable
    private data class Persisted(val grants: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file get() = File(dir, "grants.json")

    /** 持久化状态（无记录 → ASK）。 */
    fun stored(name: String): ScriptGrant = read()[name].toGrant()

    /** 生效状态：进程内一次性授权优先于持久化状态。 */
    // v2.9.35：once 是普通 HashMap，与 grantOnce/clearOnce/persist 跨线程读写，
    // 统一 @Synchronized 保护，避免丢失更新与可见性问题。
    @Synchronized
    fun effective(name: String): ScriptGrant = once[name] ?: stored(name)

    /** 记录进程内一次性授权。 */
    @Synchronized
    fun grantOnce(name: String, grant: ScriptGrant) {
        once[name] = grant
    }

    /** 清除进程内一次性授权（运行结束后调用，允许下次重新询问）。 */
    @Synchronized
    fun clearOnce(name: String) {
        once.remove(name)
    }

    /** 持久化授权（仅 ALLOW_ALWAYS / DENY_ALWAYS / ASK 有意义）。 */
    // v2.9.35：read→改→write 三段式此前无锁，两个脚本几乎同时授权时后写覆盖前写；
    // 配合 AtomicTextFile 的唯一临时名，整体加锁保证 read-modify-write 原子。
    @Synchronized
    fun persist(name: String, grant: ScriptGrant) {
        val cur = read().toMutableMap()
        if (grant == ScriptGrant.ASK) cur.remove(name) else cur[name] = grant.name
        AtomicTextFile.write(file, json.encodeToString(Persisted(cur.toMap())))
    }

    private fun read(): Map<String, String> = runCatching {
        if (!file.exists()) return emptyMap()
        json.decodeFromString<Persisted>(file.readText()).grants
    }.getOrDefault(emptyMap())

    private fun String?.toGrant(): ScriptGrant =
        runCatching { ScriptGrant.valueOf(this ?: "") }.getOrDefault(ScriptGrant.ASK)

    companion object {
        /**
         * 是否需要在运行前征求授权：脚本（含子动作）中存在 RUN_JS。
         * 纯函数——UI 询问与服务端校验用同一判定，避免两边口径漂移。
         */
        fun needsConsent(script: Script): Boolean =
            containsRunJs(script.actions) || containsRunJs(script.listeners.all())

        private fun containsRunJs(actions: List<Action>): Boolean = actions.any { a ->
            a.type == ActionType.RUN_JS
                || containsRunJs(a.children)
                // v2.9.35：监听器（10 个时机钩子）里的动作同样可执行 RUN_JS，
                // 漏遍历会让"回调中挂 JS"的脚本绕过运行前授权直接执行任意代码。
                || containsRunJs(a.listeners.all())
        }
    }
}

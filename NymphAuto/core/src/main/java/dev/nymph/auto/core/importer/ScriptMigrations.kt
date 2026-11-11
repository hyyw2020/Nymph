// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Script
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **`.zjs` schema 版本迁移链**（v2.9.0，纯 JVM）。
 *
 * ## 回应的缺陷
 *
 * 「不加密、明文可审计」的代价是格式演进必须靠版本号兜：一旦将来给 `Script`
 * 加字段/改语义，旧 APK 用 `ignoreUnknownKeys=true` 会把不认识的未来版本
 * **静默当成当前版本加载**，字段缺失按默认值补，脚本能打开但行为全错——
 * 比直接报错更危险。`Script.schemaVersion` 字段早在 v2.7.0 就存在，但一直
 * 没有任何读取/迁移逻辑，等于白设。
 *
 * 本对象补上三件事：
 *  1. [classify] 从原始 JSON 显式分出「无版本字段的历史脚本 / 当前版本 /
 *     未来版本 / 损坏」四类，不再靠默认值猜；
 *  2. [apply] 定义逐级迁移链（当前 v1 为基线，链为空但结构就位），
 *     未来加 v2 只需注册一个 `(Script)->Script`；
 *  3. [isSupported] 供 `ScriptStore` 在列举/加载时挡掉未来版本，避免静默错乱。
 *
 * 接线点：`ScriptCodec.decode`（迁移）、`ScriptStore.list/load`（版本闸门）。
 *
 * ## 兼容性
 *
 * v1 与「无 schemaVersion 字段」的历史脚本行为与 v2.8.1 **完全一致**：
 * [apply] 对 ≤ v1 的脚本原样返回，不改动任何字段。
 */
object ScriptMigrations {

    /** 当前 schema 版本，与 `Script.SCHEMA_VERSION` 同源，避免两处硬编码漂移。 */
    const val CURRENT: Int = Script.SCHEMA_VERSION

    /**
     * 逐级迁移表：key = 源版本 N，value = 把 vN 升到 v(N+1) 的函数。
     * 目前 v1 是初始版本，链为空；未来加字段时在此注册即可，调用方无需改动。
     */
    private val chain: Map<Int, (Script) -> Script> = emptyMap()

    enum class Kind { LEGACY_NO_FIELD, CURRENT, FUTURE, MALFORMED }

    data class VersionInfo(val kind: Kind, val declared: Int?)

    /**
     * 从原始 JSON 读出声明的 schemaVersion 并分类。
     * 读取失败（非 JSON，例如压缩容器）返回 MALFORMED，由调用方按原路径处理。
     */
    fun classify(raw: String): VersionInfo = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val node = obj["schemaVersion"]
        if (node == null) return VersionInfo(Kind.LEGACY_NO_FIELD, null)
        val v = node.jsonPrimitive.int
        val kind = when {
            v > CURRENT -> Kind.FUTURE
            v <= 0 -> Kind.MALFORMED
            else -> Kind.CURRENT // 1..CURRENT 都视为当前链可处理
        }
        VersionInfo(kind, v)
    }.getOrDefault(VersionInfo(Kind.MALFORMED, null))

    /** 该脚本能否被当前版本安全加载（历史脚本与 v1 可以，未来版本不行）。 */
    fun isSupported(script: Script): Boolean = script.schemaVersion in 1..CURRENT

    /** 迁移链需要走几步（供单测与日志，不抛异常）。 */
    fun steps(from: Int, to: Int = CURRENT): Int = (to - from).coerceAtLeast(0)

    data class Result(val script: Script, val from: Int, val to: Int, val appliedSteps: Int)

    /**
     * 把脚本逐级迁移到 [CURRENT]。当前链为空，对 v1/历史脚本恒等返回；
     * 未来版本不应调用本方法（应被 [isSupported] 挡在门外）。
     */
    fun apply(script: Script): Result {
        var cur = script
        var v = script.schemaVersion.coerceAtLeast(1)
        var applied = 0
        while (v < CURRENT) {
            val step = chain[v] ?: break
            cur = step(cur).copy(schemaVersion = v + 1)
            v++
            applied++
        }
        return Result(cur, script.schemaVersion.coerceAtLeast(1), v, applied)
    }
}

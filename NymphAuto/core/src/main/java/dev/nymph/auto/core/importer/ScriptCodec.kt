package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Script
import kotlinx.serialization.json.Json

/**
 * 原生 JSON 编解码。宽容配置：忽略未知键 + 不编码默认值以外的差异。
 *
 * v2.7.0：读取统一走 [ScriptCompressor.decodeAny]，同时接受压缩容器
 * （`NAZ1:` 前缀）与旧版明文 JSON，并自动展开模板引用。写入仍输出
 * 人类可读的明文 JSON（导入器/调试用）；需要瘦身的落盘路径请用
 * [ScriptCompressor.encode]（[dev.nymph.auto.core.store.ScriptStore] 已接入）。
 */
object ScriptCodec {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(script: Script): String = json.encodeToString(Script.serializer(), script)

    /**
     * v2.9.0：解码后统一过 [ScriptMigrations] 迁移链与版本闸门。
     *  - 未来版本（schemaVersion > CURRENT）直接判为不可加载，避免
     *    `ignoreUnknownKeys` 把不认识的格式静默当 v1 打开、字段按默认值补齐
     *    却行为全错；
     *  - v1 / 无版本字段的历史脚本 / 压缩容器行为与 v2.8.1 完全一致。
     */
    fun decode(raw: String): Script? {
        // 明文 JSON 才做「未来版本」预检；压缩容器（NAZ1: 前缀）不是 JSON，
        // classify 返回 MALFORMED，交给 decodeAny 原路径处理（本机产物即当前版本）。
        if (!raw.trimStart().startsWith(ScriptCompressor.MAGIC) &&
            ScriptMigrations.classify(raw).kind == ScriptMigrations.Kind.FUTURE
        ) {
            return null
        }
        val parsed = ScriptCompressor.decodeAny(raw) ?: return null
        if (!ScriptMigrations.isSupported(parsed)) return null
        return ScriptMigrations.apply(parsed).script
    }
}

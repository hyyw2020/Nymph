package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Script
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 脚本瘦身编解码（v2.7.0，纯 JVM 可测）。
 *
 * ## 回应的反馈
 *
 * 「脚本太大了不易保存和维护」——v2.6.0 的 [ScriptStore] 写的是
 * `prettyPrint=true` + `encodeDefaults=true` 的明文 JSON，且每个 CLICK_IMAGE
 * / APPEAR_IMAGE 都内联一整份 base64 模板。录制脚本里重复截同一按钮会
 * 把同一张 PNG 存 N 遍，文件动辄几 MB，diff、备份、导入都难受。
 *
 * 本类做三件事，全部**向后兼容**：
 *  1. **模板去重**：重复 base64 抽到 [Script.assets]，动作里只留 `ref:xxxx`。
 *  2. **紧凑 JSON**：省略默认值、不缩进。
 *  3. **DEFLATE + Base64**：加 `NAZ1:` 魔数头，读取时按魔数自动识别。
 *
 * 读取侧 [decodeAny] 同时接受压缩容器与旧版明文 JSON，且一律 `expand()`
 * 把引用还原成完整 base64——**运行引擎 ScriptRunner / VisionPipeline 完全
 * 无感知**，不需要改动任何找图代码。老脚本（无 assets 字段）解析为空 Map，
 * 行为与 v2.6.0 一致。
 *
 * 安全：解压设 64MB 上限，防畸形/恶意文件把内存打爆。
 */
object ScriptCompressor {

    const val MAGIC = "NAZ1:"
    private const val REF_PREFIX = "ref:"
    private const val MIN_DEDUP_LEN = 256
    private const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

    /** 写入侧紧凑格式；读取仍用 [ScriptCodec.json]（宽容配置）。 */
    private val compactJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    data class Stats(
        val originalBytes: Int,
        val storedBytes: Int,
        val uniqueTemplates: Int,
    ) {
        val savedPct: Double
            get() = if (originalBytes <= 0) 0.0
            else 100.0 * (originalBytes - storedBytes) / originalBytes
    }

    /** 保存入口：去重 → 紧凑 JSON → deflate → base64 → 加魔数头。 */
    fun encode(script: Script): String {
        val deduped = dedup(script)
        val json = compactJson.encodeToString(Script.serializer(), deduped)
        val raw = json.toByteArray(Charsets.UTF_8)
        val def = Deflater(Deflater.BEST_COMPRESSION)
        val bos = ByteArrayOutputStream(raw.size / 2)
        try {
            def.setInput(raw)
            def.finish()
            val buf = ByteArray(8192)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
        } finally {
            def.end()
        }
        return MAGIC + Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    /**
     * 通用读取入口：压缩容器或旧版明文 JSON 都接受，返回已展开模板的完整脚本。
     * 解析失败返回 null（[ScriptStore] 会跳过坏文件，不影响脚本库其它条目）。
     */
    fun decodeAny(raw: String): Script? = runCatching {
        val s = raw.trim()
        val parsed: Script = if (s.startsWith(MAGIC)) {
            val json = inflate(s.removePrefix(MAGIC))
                ?: return@runCatching null
            compactJson.decodeFromString(Script.serializer(), json)
        } else {
            ScriptCodec.json.decodeFromString(Script.serializer(), raw)
        }
        expand(parsed)
    }.getOrNull()

    /** 体积统计（供发布说明/日志给出可核对的数字，不靠手工估算）。 */
    fun stats(script: Script): Stats {
        val plain = ScriptCodec.json.encodeToString(Script.serializer(), script)
            .toByteArray(Charsets.UTF_8).size
        val stored = encode(script).toByteArray(Charsets.UTF_8).size
        return Stats(plain, stored, dedup(script).assets.size)
    }

    /** 模板去重：返回带 assets 的等价脚本。 */
    fun dedup(script: Script): Script {
        val assets = LinkedHashMap<String, String>()
        fun ref(b64: String): String {
            if (b64.length < MIN_DEDUP_LEN || b64.startsWith(REF_PREFIX)) return b64
            val key = REF_PREFIX + sha16(b64)
            assets.putIfAbsent(key, b64)
            return key
        }
        val out = script.copy(
            actions = walkActions(script.actions, ::ref),
            listeners = walkListeners(script.listeners, ::ref),
            abortCondition = script.abortCondition?.let { walkCondition(it, ::ref) },
        )
        return out.copy(assets = assets)
    }

    /** 还原：把 `ref:xxxx` 换回完整 base64；找不到的引用原样保留（宽容）。 */
    fun expand(script: Script): Script {
        if (script.assets.isEmpty()) return script
        fun deref(v: String): String =
            if (v.startsWith(REF_PREFIX)) script.assets[v] ?: v else v
        return script.copy(
            actions = walkActions(script.actions, ::deref),
            listeners = walkListeners(script.listeners, ::deref),
            abortCondition = script.abortCondition?.let { walkCondition(it, ::deref) },
            assets = emptyMap(),
        )
    }

    // ------------------------------------------------------------ 递归遍历

    private fun walkActions(list: List<Action>, f: (String) -> String): List<Action> =
        if (list.isEmpty()) list else list.map { a ->
            a.copy(
                imageData = if (a.imageData.isEmpty()) a.imageData else f(a.imageData),
                children = walkActions(a.children, f),
                listeners = walkListeners(a.listeners, f),
                condition = a.condition?.let { walkCondition(it, f) },
                postCondition = a.postCondition?.let { walkCondition(it, f) },
            )
        }

    private fun walkListeners(ls: Listeners, f: (String) -> String): Listeners {
        if (ls.all().isEmpty()) return ls
        return Listeners(
            beforeAction = walkActions(ls.beforeAction, f),
            afterAction = walkActions(ls.afterAction, f),
            onActionFail = walkActions(ls.onActionFail, f),
            onActionOk = walkActions(ls.onActionOk, f),
            beforeList = walkActions(ls.beforeList, f),
            afterList = walkActions(ls.afterList, f),
            beforeScript = walkActions(ls.beforeScript, f),
            afterScript = walkActions(ls.afterScript, f),
            beforeConditionCheck = walkActions(ls.beforeConditionCheck, f),
            onConditionFail = walkActions(ls.onConditionFail, f),
        )
    }

    private fun walkCondition(c: Condition, f: (String) -> String): Condition = c.copy(
        imageData = if (c.imageData.isEmpty()) c.imageData else f(c.imageData),
        children = if (c.children.isEmpty()) c.children else c.children.map { walkCondition(it, f) },
    )

    // ------------------------------------------------------------ 低层工具

    private fun inflate(b64: String): String? {
        val bytes = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()
            ?: return null
        val inf = Inflater()
        val bos = ByteArrayOutputStream(bytes.size * 3)
        try {
            inf.setInput(bytes)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsDictionary() || inf.needsInput()) break
                } else {
                    bos.write(buf, 0, n)
                }
                if (bos.size() > MAX_INFLATED_BYTES) return null
            }
        } finally {
            inf.end()
        }
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    private fun sha16(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

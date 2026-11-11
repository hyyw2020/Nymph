package dev.nymph.auto.core.store

import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.importer.ScriptCompressor
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.runner.ScriptDeps
import dev.nymph.auto.core.runner.dependencies
import java.io.File

/**
 * 脚本库：导入的脚本持久化到本地目录（每个脚本一个 JSON 文件）。
 *
 * 设计约束：
 *  - 纯 JVM（File 抽象），core 单测用临时目录即可验证；
 *  - 编解码走 [ScriptCodec]（与 RunnerService 传输同一格式）；
 *  - 宽容：坏文件跳过不计入列表，绝不因单文件损坏影响整体；
 *  - 文件名净化：脚本名转安全文件名，重名覆盖（视为再次保存）。
 */
class ScriptStore(private val dir: File) {

    /** 列表项（轻量：不含动作体，供 UI 展示；含依赖清单一行摘要）。 */
    data class Item(
        val name: String,
        val steps: Int,
        val savedAt: Long,
        val file: File,
        val deps: ScriptDeps = ScriptDeps(),
    )

    private val lock = Any()
    private var lastSaveAt = 0L

    init {
        runCatching { dir.mkdirs() }
        // v2.9.0 启动恢复：清掉上次写入在 rename 之前被中断遗留的半成品，
        // 它们绝不能被当脚本加载（本来 list() 也只认 .json，这里显式清理）。
        runCatching { AtomicTextFile.cleanupStale(dir) }
    }

    /** 全部脚本（按保存时间倒序；坏文件跳过）。 */
    fun list(): List<Item> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(SUFFIX) }
        .mapNotNull { f ->
            val s = runCatching { ScriptCodec.decode(f.readText()) }.getOrNull() ?: return@mapNotNull null
            Item(
                name = s.name.ifBlank { f.nameWithoutExtension },
                steps = s.totalActionCount(),
                savedAt = f.lastModified(),
                file = f,
                deps = s.dependencies(),
            )
        }
        .sortedByDescending { it.savedAt }

    /**
     * 保存（写入/覆盖），返回文件。
     * v2.7.0：落盘改用 [ScriptCompressor] 压缩容器（模板去重 + deflate），
     * 文件显著变小；读取侧 [ScriptCodec.decode] 自动识别新旧两种格式，
     * 老明文 JSON 无需迁移即可继续打开。
     * v2.9.0：写入改走 [AtomicTextFile]（临时文件→fsync→rename），
     * 编辑会话中被杀不会留下「写了一半」的文件导致脚本凭空消失。
     */
    fun save(script: Script): File = synchronized(lock) {
        val f = File(dir, safeName(script.name) + SUFFIX)
        // v2.9.4：原子写返回 Boolean。此前忽略返回值，磁盘满 / 目录不可写 /
        // 目标被占用时仍返回 File，上层 `runCatching { store.save() }.onSuccess`
        // 会误报「已保存」，脚本实际丢失。失败必须显式抛错，交给既有错误路径提示。
        if (!AtomicTextFile.write(f, ScriptCompressor.encode(script))) {
            throw java.io.IOException("脚本写入失败：${f.name}")
        }
        // 同一毫秒内的连续保存会得到相同 mtime 导致排序不稳；这里单调递增兜底
        val bumped = maxOf(System.currentTimeMillis(), lastSaveAt + 1)
        runCatching { f.setLastModified(bumped) }
        lastSaveAt = bumped
        f
    }

    /** 按列表项名称删除（匹配 name 字段或文件名）。 */
    fun delete(name: String): Boolean {
        val target = list().firstOrNull { it.name == name }?.file
            ?: File(dir, safeName(name) + SUFFIX).takeIf { it.exists() }
            ?: return false
        return target.delete()
    }

    /** 按名称读取完整脚本。 */
    fun load(name: String): Script? {
        val f = list().firstOrNull { it.name == name }?.file
            ?: File(dir, safeName(name) + SUFFIX).takeIf { it.exists() }
            ?: return null
        return runCatching { ScriptCodec.decode(f.readText()) }.getOrNull()
    }

    companion object {
        const val SUFFIX = ".json"

        /** 脚本名 → 安全文件名（去路径分隔符与控制字符，限长 80）。 */
        fun safeName(raw: String): String {
            val s = raw.trim().ifEmpty { "script" }
            val cleaned = buildString {
                s.forEach { c ->
                    append(
                        when {
                            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._-一-龥（）()【】" -> c
                            else -> '_'
                        },
                    )
                }
            }.trim('_').take(80)
            return cleaned.ifEmpty { "script" }
        }
    }
}

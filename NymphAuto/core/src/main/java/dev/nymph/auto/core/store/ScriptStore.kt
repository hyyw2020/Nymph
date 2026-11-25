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
        // v2.9.39 启动迁移：消除「不同文件解码出相同 name」的脏数据。否则 UI 以
        // name 作 LazyColumn key 会崩、按 name 删除/编辑会串台。幂等，无重复零改动。
        runCatching { migrateDuplicateNames() }
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
     * v2.9.39：幂等迁移，消除「不同文件解码出相同 name」的脏数据。
     *
     * 成因：v2.9.35 之前 [safeName] 把所有中文名折叠成单个 `script.json`；升级到
     * v2.9.35 后中文名按原样落盘。若老版本遗留的 `script.json`（内部 name 仍为
     * 「未命名脚本」）与新版本 `未命名脚本.json` 并存，[list] 会解出两个同名项：
     * Compose 列表以 name 作 key 直接崩溃，按 name 删除/编辑也会互相串台。
     *
     * 处理：保留每个 name 最新的一项原名不变（[list] 已按 savedAt 倒序），其余同名
     * 旧项改名为 `<原名> (2)`、`<原名> (3)`…直到不与任何现有 name 冲突，并连同
     * 文件名一起写回。改名项的动作体、revision 全部保留，只改展示名/文件名。
     * 幂等：全部 name 唯一时零写入。坏文件由 [list] 自动跳过，不参与迁移。
     *
     * @return 实际改名的文件数。
     */
    fun migrateDuplicateNames(): Int = synchronized(lock) {
        val items = list()
        if (items.isEmpty()) return@synchronized 0
        val used = HashSet<String>()
        var renamed = 0
        // list() 已按 savedAt 倒序：每个 name 的首次出现（最新）保留原名。
        for (item in items) {
            if (used.add(item.name)) continue
            val script = runCatching { ScriptCodec.decode(item.file.readText()) }.getOrNull()
                ?: continue
            var suffix = 2
            var candidate: String
            do {
                candidate = "${item.name} ($suffix)"
                suffix++
            } while (used.contains(candidate) || File(dir, safeName(candidate) + SUFFIX).exists())
            used.add(candidate)
            val target = File(dir, safeName(candidate) + SUFFIX)
            val ok = runCatching {
                AtomicTextFile.write(target, ScriptCodec.encode(script.copy(name = candidate)))
            }.getOrDefault(false)
            if (ok) {
                // 沿用旧文件的时间戳，避免迁移把改名项顶到列表最前、打乱保存顺序。
                runCatching { target.setLastModified(item.savedAt) }
                if (target.absolutePath != item.file.absolutePath) {
                    // v2.9.41（④）：此前 runCatching{delete()} 吞掉失败且无告警——旧文件
                    // 仍以 .json 结尾残留时，下次启动 list() 会再次解出重复 name，再生成
                    // (3)/(4)…，与「幂等」声明不符。改为：删除失败先告警，再兜底把旧文件
                    // 改名为 .json.orphan-<nano>（脱离 .json 识别、list() 不再加载、清理器
                    // 也不会误删），彻底封死重复迁移死角；两步都失败则 ERROR 告警，数据未丢。
                    val deleted = runCatching { item.file.delete() }.getOrDefault(false)
                    if (!deleted && item.file.exists()) {
                        dev.nymph.auto.core.log.RunLog.w(
                            "ScriptStore",
                            "重名迁移：已写入 ${target.name}，但旧文件 ${item.file.name} 删除失败，尝试改名隔离",
                        )
                        val orphan = File(item.file.path + ".orphan-" + System.nanoTime().toString(36))
                        val quarantined = runCatching { item.file.renameTo(orphan) }.getOrDefault(false)
                        if (!quarantined) {
                            dev.nymph.auto.core.log.RunLog.e(
                                "ScriptStore",
                                "重名迁移：旧文件 ${item.file.name} 删除与改名隔离均失败，仍以原名残留",
                            )
                        }
                    }
                }
                renamed++
            }
        }
        renamed
    }

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
                            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
                                || c in "._-（）()【】"
                                // v2.9.35：原写法 `c in "._-一-龥..."` 里 "一-龥" 只是
                                // 三个孤立字符，并非 CJK 区间——中文名全被替换成 '_'，
                                // trim 后回退 "script"，多个中文脚本互相覆盖、数据丢失。
                                || c in '\u4E00'..'\u9FFF' -> c
                            else -> '_'
                        },
                    )
                }
            }.trim('_').take(80)
            return cleaned.ifEmpty { "script" }
        }
    }
}

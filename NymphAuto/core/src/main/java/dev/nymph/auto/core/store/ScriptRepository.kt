package dev.nymph.auto.core.store

import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.importer.ScriptCompressor
import dev.nymph.auto.core.model.Script
import java.io.File

/**
 * 脚本仓库（v2.7.0，纯 JVM 可测）：版本/修订/回滚。
 *
 * ## 回应的不足
 *
 * 「无脚本引用/版本/ScriptRepository」——v2.6.0 只有 ScriptStore 的单文件覆盖写，
 * 误删一个动作、或自动覆盖一次录制结果就再也回不去。本类在保存时：
 *  1. 递增 [Script.revision]；
 *  2. 把**上一版**快照写进 `.history/<name>.r<N>.naz`（同样走压缩容器）；
 *  3. 只保留最近 [keepVersions] 份，自动清理更旧的。
 *
 * 历史目录是脚本目录下的子目录，`ScriptStore.list()` 用 `isFile` 过滤且
 * 不递归，故历史快照不会被误列成脚本。全部基于已有 File 抽象，不引入
 * 数据库、不新增权限、不联网。
 */
class ScriptRepository(
    private val store: ScriptStore,
    historyDir: File,
    private val keepVersions: Int = 8,
) {
    private val history = historyDir

    data class Version(
        val revision: Int,
        val savedAt: Long,
        val steps: Int,
        val file: File,
    )

    init {
        runCatching { history.mkdirs() }
    }

    /** 保存并返回带新修订号的脚本；旧版本自动进历史。 */
    fun save(script: Script): Script {
        val previous = store.load(script.name)
        if (previous != null) snapshot(previous)
        val next = script.copy(revision = (previous?.revision ?: 0) + 1)
        store.save(next)
        return next
    }

    /** 某脚本的历史版本（新→旧），不含当前版本。 */
    fun versions(name: String): List<Version> {
        val prefix = prefixOf(name)
        return (history.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(SUFFIX) }
            .mapNotNull { f ->
                val rev = REV_REGEX.find(f.name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val s = ScriptCodec.decode(f.readText()) ?: return@mapNotNull null
                Version(rev, f.lastModified(), s.totalActionCount(), f)
            }
            .sortedByDescending { it.revision }
    }

    /** 读取某个历史版本的完整脚本（不写回；由 UI 决定是否再次 save）。 */
    fun restore(name: String, revision: Int): Script? {
        val f = File(history, "${prefixOf(name)}$revision$SUFFIX")
        if (!f.isFile) return null
        return ScriptCodec.decode(f.readText())
    }

    /** 删除脚本时一并清理其历史，避免残留文件无限增长。 */
    fun purge(name: String) {
        val prefix = prefixOf(name)
        (history.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(prefix) }
            .forEach { runCatching { it.delete() } }
    }

    private fun snapshot(s: Script) {
        val rev = s.revision
        if (rev <= 0) return
        val f = File(history, "${prefixOf(s.name)}$rev$SUFFIX")
        // v2.9.0：历史快照同样原子写，回滚点不会是半截文件。
        AtomicTextFile.write(f, ScriptCompressor.encode(s))
        trim(s.name)
    }

    private fun trim(name: String) {
        val all = versions(name)
        if (all.size > keepVersions) {
            all.drop(keepVersions).forEach { runCatching { it.file.delete() } }
        }
    }

    private fun prefixOf(name: String): String = ScriptStore.safeName(name) + ".r"

    companion object {
        private const val SUFFIX = ".naz"
        private val REV_REGEX = Regex("""\.r(\d+)\.naz$""")
    }
}

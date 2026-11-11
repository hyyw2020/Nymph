// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.store

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * **原子文本落盘**（v2.9.0，纯 JVM）。
 *
 * ## 回应的缺陷
 *
 * `ScriptStore.save()` 原来是 `f.writeText(...)`——直接截断目标文件再写。
 * 编辑会话中若进程被杀 / 断电 / 磁盘满，目标文件会停在「写了一半」的状态：
 * 下次 `list()` 解析失败就静默跳过，用户看到的是**脚本凭空消失**。
 * 这是「一致性靠约定」最实的一个洞，也是刷新率/悬浮窗之外的真实数据风险。
 *
 * 标准做法：**临时文件 → fsync → rename**。rename 在同一文件系统内是原子操作，
 * 读者要么看到完整的旧版本，要么看到完整的新版本，永远看不到半截。
 *
 * ## 约束
 *
 * - 纯 JVM（只用 JDK NIO），core 单测用临时目录即可验证，不需要 Android / Robolectric。
 * - 不联网、不新增权限、不改文件格式：落盘内容仍是原来的明文/压缩容器文本。
 * - 失败**绝不破坏已有文件**：临时文件写完才动目标，任何异常都只清理临时文件。
 */
object AtomicTextFile {

    const val TMP_SUFFIX = ".tmp"

    /**
     * 原子写入 [target]，返回是否成功。
     *
     * 步骤：写同目录 `<name>.tmp` → `FileChannel.force(true)` 刷盘 →
     * `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 原子替换。
     * 某些文件系统不支持 ATOMIC_MOVE 时退化为 REPLACE_EXISTING（仍是单次 rename）。
     */
    fun write(target: File, text: String): Boolean {
        val parent = target.parentFile
        if (parent != null) runCatching { parent.mkdirs() }
        val tmp = File(target.path + TMP_SUFFIX)
        return try {
            // 先写临时文件并强制刷盘，确保 rename 之前数据已落介质。
            FileOutputStream(tmp, false).use { fos ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                fos.write(bytes)
                fos.flush()
                // 刷盘是持久化加强，个别文件系统/沙箱不支持时降级为 best-effort，
                // 不能因为 sync 失败就放弃整次保存（原子性由 rename 保证）。
                runCatching { fos.fd.sync() }
            }
            // 再原子替换目标；ATOMIC_MOVE 不被支持时降级但仍保持「一次 move」。
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            // 目录项落盘（Linux 可 fsync 目录）；不支持时忽略，不影响文件本身。
            if (parent != null) runCatching {
                FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
            true
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * 启动恢复检查：列出目录里遗留的 `*.tmp`。
     * 这些是上次写入在 rename 之前被中断留下的半成品，**不应被当作脚本加载**。
     */
    fun staleTempFiles(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(TMP_SUFFIX) }
            .toList()

    /** 清理遗留临时文件，返回清理数量。只删 `.tmp`，绝不碰正式文件。 */
    fun cleanupStale(dir: File): Int {
        var n = 0
        staleTempFiles(dir).forEach { if (runCatching { it.delete() }.getOrDefault(false)) n++ }
        return n
    }
}

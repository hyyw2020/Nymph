// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2.9.0 原子落盘的纯 JVM 回归：验证「写一半被杀不留半截文件」这一核心保证。
 */
class AtomicTextFileTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    @Test
    fun `首次写入生成目标文件且无残留 tmp`() {
        val f = File(tmp.root, "a.json")
        assertTrue(AtomicTextFile.write(f, "hello"))
        assertEquals("hello", f.readText())
        assertTrue(AtomicTextFile.staleTempFiles(tmp.root).isEmpty())
    }

    @Test
    fun `覆盖写是完整替换 不会出现半截内容`() {
        val f = File(tmp.root, "a.json")
        f.writeText("OLD")
        assertTrue(AtomicTextFile.write(f, "NEW-COMPLETE-CONTENT"))
        assertEquals("NEW-COMPLETE-CONTENT", f.readText())
        assertFalse(f.readText().contains("OLD"))
        assertTrue(AtomicTextFile.staleTempFiles(tmp.root).isEmpty())
    }

    @Test
    fun `多轮连续覆盖 每轮都是完整值`() {
        val f = File(tmp.root, "a.json")
        repeat(20) { i ->
            assertTrue(AtomicTextFile.write(f, "v$i-${"x".repeat(i * 100)}"))
            assertEquals("v$i-${"x".repeat(i * 100)}", f.readText())
        }
        assertTrue(AtomicTextFile.staleTempFiles(tmp.root).isEmpty())
    }

    @Test
    fun `模拟上次中断遗留的 tmp 被识别并清理 不误删正式文件`() {
        val good = File(tmp.root, "script.json").apply { writeText("VALID") }
        val stale = File(tmp.root, "script.json.tmp").apply { writeText("HALF-WRITTEN") }
        assertEquals(1, AtomicTextFile.staleTempFiles(tmp.root).size)
        assertEquals(1, AtomicTextFile.cleanupStale(tmp.root))
        assertFalse(stale.exists())
        assertTrue(good.exists())
        assertEquals("VALID", good.readText())
        assertTrue(AtomicTextFile.staleTempFiles(tmp.root).isEmpty())
    }

    @Test
    fun `中文与压缩容器文本 UTF_8 往返无损`() {
        val f = File(tmp.root, "a.json")
        val payload = "NAZ1:脚本「测试」— 🐛 数据 eJxLz8lPzkzN" // 含非 ASCII
        assertTrue(AtomicTextFile.write(f, payload))
        assertEquals(payload, f.readText(Charsets.UTF_8))
    }

    @Test
    fun `ScriptStore 保存后不留 tmp 且可被 list 正常读回`() {
        val dir = File(tmp.root, "scripts")
        val store = ScriptStore(dir)
        store.save(dev.nymph.auto.core.model.Script(name = "原子写验证", actions = emptyList()))
        assertTrue(AtomicTextFile.staleTempFiles(dir).isEmpty())
        assertEquals(1, store.list().size)
        assertEquals("原子写验证", store.list().first().name)
        // 二次保存（覆盖）不留半截
        store.save(dev.nymph.auto.core.model.Script(name = "原子写验证", actions = emptyList()))
        assertTrue(AtomicTextFile.staleTempFiles(dir).isEmpty())
        assertEquals(1, store.list().size)
    }

    @Test
    fun `目录不存在时自动创建`() {
        val nested = File(tmp.root, "a/b/c/a.json")
        assertTrue(AtomicTextFile.write(nested, "ok"))
        assertEquals("ok", nested.readText())
    }
}

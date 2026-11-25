package dev.nymph.auto.core.store

import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2.9.39：重名脏数据迁移回归。
 *
 * 复现真机崩溃：老版本把所有中文脚本折叠进 script.json（内部 name 仍是
 * 「未命名脚本」），升级到 v2.9.35 后又新建了「未命名脚本.json」。两个文件
 * 解码出同一个 name，Compose 以 name 作 LazyColumn key 直接抛
 * `Key "未命名脚本" was already used` 崩溃。迁移保证 name 恢复唯一主键。
 */
class DuplicateNameMigrationTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun script(name: String, x: Double) = Script(
        name = name,
        actions = listOf(Action(type = ActionType.TAP, x = x, y = 20.0)),
    )

    /** 手工落一个文件并设定 mtime，模拟老版本/新版本两个不同来源的同名脚本。 */
    private fun put(dir: File, file: String, name: String, x: Double, mtime: Long) {
        File(dir, file).writeText(ScriptCodec.encode(script(name, x)))
        File(dir, file).setLastModified(mtime)
    }

    @Test
    fun `老script与新同名文件迁移后 name 恢复唯一 动作体不丢`() {
        val dir = tmp.newFolder()
        // 老遗留：script.json，内部 name=未命名脚本，动作 x=11，较早
        put(dir, "script.json", "未命名脚本", 11.0, 1_000_000L)
        // 新版：未命名脚本.json，内部 name=未命名脚本，动作 x=22，较新
        put(dir, "未命名脚本.json", "未命名脚本", 22.0, 2_000_000L)

        val store = ScriptStore(dir) // init 触发迁移
        val list = store.list()
        assertEquals("迁移后仍是两个脚本", 2, list.size)
        val names = list.map { it.name }
        assertEquals("name 必须唯一", names.size, names.toSet().size)

        // 最新的一项保留原名；较旧的被改名，且动作体完整保留
        val kept = list.first { it.name == "未命名脚本" }
        assertEquals(22.0, store.load(kept.name)!!.actions[0].x, 0.001)
        val renamed = list.first { it.name != "未命名脚本" }
        assertEquals("未命名脚本 (2)", renamed.name)
        assertEquals(11.0, store.load(renamed.name)!!.actions[0].x, 0.001)
    }

    @Test
    fun `迁移幂等 再次启动零改动`() {
        val dir = tmp.newFolder()
        put(dir, "script.json", "未命名脚本", 11.0, 1_000_000L)
        put(dir, "未命名脚本.json", "未命名脚本", 22.0, 2_000_000L)

        val first = ScriptStore(dir) // init 已触发首次迁移
        val namesAfterFirst = first.list().map { it.name }.sorted()
        assertEquals(2, namesAfterFirst.size)
        assertTrue("首次迁移应产生改名项", namesAfterFirst.contains("未命名脚本 (2)"))
        assertEquals("同一实例再调幂等", 0, first.migrateDuplicateNames())

        val second = ScriptStore(dir) // 二次启动，init 再迁仍是 0
        assertEquals(0, second.migrateDuplicateNames())
        assertEquals(namesAfterFirst, second.list().map { it.name }.sorted())
        assertEquals(2, second.list().size)
    }

    @Test
    fun `三个同名脚本迁移后全部唯一`() {
        val dir = tmp.newFolder()
        put(dir, "script.json", "未命名脚本", 11.0, 1_000_000L)
        put(dir, "未命名脚本.json", "未命名脚本", 22.0, 2_000_000L)
        put(dir, "未命名脚本_3.json", "未命名脚本", 33.0, 3_000_000L)

        val store = ScriptStore(dir)
        val list = store.list()
        assertEquals(3, list.size)
        assertEquals(list.size, list.map { it.name }.toSet().size)
        // 各动作体分别在各自文件里，按唯一名可精确取回
        assertEquals(33.0, store.load("未命名脚本")!!.actions[0].x, 0.001)
        assertTrue(list.any { store.load(it.name)!!.actions[0].x == 11.0 })
        assertTrue(list.any { store.load(it.name)!!.actions[0].x == 22.0 })
    }

    @Test
    fun `无重名时零改名 坏文件不参与迁移`() {
        val dir = tmp.newFolder()
        val store = ScriptStore(dir)
        store.save(script("脚本A", 1.0))
        store.save(script("脚本B", 2.0))
        File(dir, "broken.json").writeText("{ 坏文件")
        val before = store.list().map { it.name }.sorted()

        assertEquals(0, store.migrateDuplicateNames())
        assertEquals(before, store.list().map { it.name }.sorted())
    }

    @Test
    fun `迁移后可分别删除同名脚本 不串台`() {
        val dir = tmp.newFolder()
        put(dir, "script.json", "未命名脚本", 11.0, 1_000_000L)
        put(dir, "未命名脚本.json", "未命名脚本", 22.0, 2_000_000L)

        val store = ScriptStore(dir)
        val renamed = store.list().first { it.name != "未命名脚本" }.name
        assertNotEquals("未命名脚本", renamed)

        // 删除改名项，原名项仍在且内容正确
        assertTrue(store.delete(renamed))
        assertNull(store.load(renamed))
        assertNotNull(store.load("未命名脚本"))
        assertEquals(22.0, store.load("未命名脚本")!!.actions[0].x, 0.001)
    }
}

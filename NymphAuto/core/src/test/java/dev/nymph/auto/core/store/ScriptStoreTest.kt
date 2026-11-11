package dev.nymph.auto.core.store

import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.video.WaitGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ScriptStore 持久化行为（临时目录，纯 JVM）。 */
class ScriptStoreTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun script(name: String, steps: Int = 2) = Script(
        name = name,
        actions = List(steps) { Action(type = ActionType.TAP, x = 10.0 + it, y = 20.0) },
    )

    @Test
    fun `保存-列表-读取-删除 往返`() {
        val store = ScriptStore(tmp.newFolder())
        store.save(script("测试脚本A", 3))
        store.save(script("脚本B", 1))

        val list = store.list()
        assertEquals(2, list.size)
        // 保存时间倒序：B 后保存排最前
        assertEquals("脚本B", list[0].name)
        assertEquals(1, list[0].steps)
        assertEquals(3, list[1].steps)

        val loaded = store.load("测试脚本A")
        assertNotNull(loaded)
        assertEquals(3, loaded!!.actions.size)
        assertEquals(10.0, loaded.actions[0].x, 0.001)

        assertTrue(store.delete("测试脚本A"))
        assertNull(store.load("测试脚本A"))
        assertEquals(1, store.list().size)
        assertFalse(store.delete("不存在的脚本"))
    }

    @Test
    fun `同名覆盖与坏文件跳过`() {
        val dir = tmp.newFolder()
        val store = ScriptStore(dir)
        store.save(script("同名", 2))
        store.save(script("同名", 5))
        assertEquals(1, store.list().size)
        assertEquals(5, store.load("同名")!!.actions.size)

        // 手工放入坏文件：列表跳过它，但不应崩溃
        File(dir, "broken.json").writeText("{ 不是合法 json")
        assertEquals(1, store.list().size)
    }

    @Test
    fun `文件名净化与空名兜底`() {
        assertEquals("a_b", ScriptStore.safeName("a/b"))
        assertEquals("script", ScriptStore.safeName(""))
        assertEquals("script", ScriptStore.safeName("///"))
        val weird = ScriptStore.safeName("我的/脚本:v1")
        assertTrue(weird.none { it == '/' || it == ':' })
        val store = ScriptStore(tmp.newFolder())
        val f = store.save(script(weird))
        assertTrue(f.exists())
        assertEquals(ScriptCodec.decode(f.readText())!!.name, weird)
    }

    @Test
    fun `目录不存在时自动创建`() {
        val dir = File(tmp.newFolder(), "nested/scripts")
        val store = ScriptStore(dir)
        store.save(script("深目录", 1))
        assertTrue(dir.isDirectory)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `步数统计含嵌套子动作与监听（行分隔脚本 3 组 57 步口径）`() {
        val store = ScriptStore(tmp.newFolder())
        val nested = Script(
            name = "嵌套脚本",
            actions = listOf(
                Action(
                    type = ActionType.RUN_ACTIONS,
                    children = List(2) { Action(type = ActionType.TAP) },
                    listeners = dev.nymph.auto.core.model.Listeners(
                        beforeAction = listOf(Action(type = ActionType.TOAST, text = "hi")),
                    ),
                ),
            ),
        )
        // 1 顶层组 + 2 子动作 + 1 监听动作 = 4（而非顶层 actions.size = 1）
        assertEquals(1, nested.actions.size)
        assertEquals(4, nested.totalActionCount())
        store.save(nested)
        assertEquals(4, store.list()[0].steps)
    }

    @Test
    fun `原子写失败必须抛错 不能静默报保存成功`() {
        // 把脚本目录指向一个已存在的普通文件：mkdirs / FileOutputStream 均失败，
        // AtomicTextFile.write 返回 false。v2.9.3 忽略该返回值仍返回 File，
        // 上层 `runCatching { save() }.onSuccess` 会误报「已保存」，脚本实际丢失。
        val blocker = tmp.newFile("not-a-dir")
        val store = ScriptStore(blocker)
        val err = runCatching { store.save(script("写失败", 1)) }.exceptionOrNull()
        assertTrue("必须显式抛 IOException，实际：$err", err is java.io.IOException)
        assertTrue("失败不得破坏既有文件", blocker.isFile)
    }

    @Test
    fun `门控脚本保存后 等待条件与模板经压缩还原仍完整`() {
        // v2.9.6：视频导入生成的「等画面出现」动作同时把模板放在
        // action.imageData 与 condition.imageData。ScriptCompressor 会去重成
        // assets 引用，读取时必须两处都还原，否则条件会拿 "ref:xxxx" 去找图。
        val tpl = "iVBORw0KGgo" + "C".repeat(300)
        val gated = Script(
            name = "门控循环",
            repeatCount = 0,
            actions = listOf(
                Action(
                    type = ActionType.CLICK_IMAGE,
                    imageData = tpl,
                    waitAppear = true,
                    condition = WaitGate.image(tpl, 80),
                ),
            ),
        )
        val store = ScriptStore(tmp.newFolder())
        store.save(gated)
        val loaded = requireNotNull(store.load("门控循环"))
        val a = loaded.actions.single()
        assertTrue(a.waitAppear)
        val cond = requireNotNull(a.condition)
        assertTrue(cond.repeatCheckUntilOk)
        assertEquals(WaitGate.INFINITE, cond.repeatCheckMax)
        assertEquals(tpl, a.imageData)
        assertEquals(tpl, cond.imageData)
        assertEquals(0, loaded.repeatCount)
    }
}

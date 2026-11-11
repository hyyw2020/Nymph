package dev.nymph.auto.core.store

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptRepositoryTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun repo(): Pair<ScriptStore, ScriptRepository> {
        val dir = tmp.newFolder("scripts")
        val store = ScriptStore(dir)
        val repo = ScriptRepository(store, java.io.File(dir, ".history"), keepVersions = 3)
        return store to repo
    }

    @Test
    fun `保存递增修订号并写入历史`() {
        val (_, repo) = repo()
        val v1 = repo.save(Script(name = "A", actions = listOf(Action(type = ActionType.TAP))))
        assertEquals(1, v1.revision)
        val v2 = repo.save(v1.copy(actions = v1.actions + Action(type = ActionType.WAIT)))
        assertEquals(2, v2.revision)
        assertEquals(1, repo.versions("A").size) // 只保存了两次，历史里应有 r1 一份
        assertTrue(repo.versions("A").any { it.revision == 1 })
    }

    @Test
    fun `历史版本可完整恢复`() {
        val (store, repo) = repo()
        val v1 = repo.save(Script(name = "B", description = "第一版"))
        repo.save(v1.copy(description = "第二版"))
        val restored = repo.restore("B", 1)
        assertNotNull(restored)
        assertEquals("第一版", restored!!.description)
        assertEquals("第二版", store.load("B")!!.description)
        assertEquals(2, store.load("B")!!.revision)
    }

    @Test
    fun `历史快照不会被误列为脚本`() {
        val (store, repo) = repo()
        val v1 = repo.save(Script(name = "C"))
        repo.save(v1.copy(description = "v2"))
        assertEquals(1, store.list().size)
        assertEquals("C", store.list().single().name)
    }

    @Test
    fun `只保留最近N份历史`() {
        val (_, repo) = repo()
        var s = Script(name = "D")
        repeat(7) { i -> s = repo.save(s.copy(description = "v$i")) }
        assertTrue(repo.versions("D").size <= 3)
    }

    @Test
    fun `purge 清理历史`() {
        val (_, repo) = repo()
        val v1 = repo.save(Script(name = "E"))
        repo.save(v1)
        assertTrue(repo.versions("E").isNotEmpty())
        repo.purge("E")
        assertTrue(repo.versions("E").isEmpty())
    }
}

package dev.nymph.auto.core.store

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三档权限（ScriptGrants）的 JVM 单测：授权判定、持久化、进程内一次性
 * 授权、损坏文件退化，以及 needsConsent 的纯函数口径。
 */
class ScriptGrantsTest {

    private fun tmpDir(): File = File.createTempFile("grants", "").let {
        it.delete(); it.mkdirs(); it
    }

    private fun script(name: String, withJs: Boolean): Script {
        val actions = mutableListOf<Action>()
        if (withJs) {
            actions += Action(type = ActionType.RUN_JS, jsCode = "vars.a = 1")
        }
        return Script(name = name, actions = actions)
    }

    @Test
    fun `needsConsent 只对含 JS 代码的脚本生效且递归子动作`() {
        assertFalse(ScriptGrants.needsConsent(script("s", withJs = false)))
        assertTrue(ScriptGrants.needsConsent(script("s", withJs = true)))
        val nested = Script(
            name = "nested",
            actions = listOf(
                Action(
                    type = ActionType.RUN_ACTIONS,
                    children = listOf(Action(type = ActionType.TAP)),
                ),
                Action(
                    type = ActionType.RUN_ACTIONS,
                    children = listOf(
                        Action(
                            type = ActionType.RUN_ACTIONS,
                            children = listOf(Action(type = ActionType.RUN_JS, jsCode = "1")),
                        ),
                    ),
                ),
            ),
        )
        assertTrue("深层子动作里的 RUN_JS 也应检出", ScriptGrants.needsConsent(nested))
    }

    @Test
    fun `默认 ASK 持久化后生效 进程内一次性优先`() {
        val dir = tmpDir()
        val g = ScriptGrants(dir)
        assertEquals(ScriptGrant.ASK, g.effective("s"))

        g.persist("s", ScriptGrant.ALLOW_ALWAYS)
        assertEquals(ScriptGrant.ALLOW_ALWAYS, g.effective("s"))
        // 重新加载（模拟杀进程后）
        assertEquals(ScriptGrant.ALLOW_ALWAYS, ScriptGrants(dir).effective("s"))

        g.grantOnce("s", ScriptGrant.DENY_ONCE)
        assertEquals("一次性授权优先于持久化", ScriptGrant.DENY_ONCE, g.effective("s"))
        g.clearOnce("s")
        assertEquals(ScriptGrant.ALLOW_ALWAYS, g.effective("s"))

        g.persist("s", ScriptGrant.DENY_ALWAYS)
        assertEquals(ScriptGrant.DENY_ALWAYS, ScriptGrants(dir).effective("s"))
        // 改回 ASK = 删除记录
        g.persist("s", ScriptGrant.ASK)
        assertEquals(ScriptGrant.ASK, ScriptGrants(dir).effective("s"))
    }

    @Test
    fun `损坏的授权文件退化为 ASK 不误放行`() {
        val dir = tmpDir()
        File(dir, "grants.json").writeText("{ this is not json")
        assertEquals(ScriptGrant.ASK, ScriptGrants(dir).effective("s"))
    }

    @Test
    fun `未知授权值退化为 ASK`() {
        val dir = tmpDir()
        File(dir, "grants.json").writeText("""{"grants":{"s":"SOMETHING_NEW"}}""")
        assertEquals(ScriptGrant.ASK, ScriptGrants(dir).effective("s"))
    }
}

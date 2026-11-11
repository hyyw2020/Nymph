// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0 schema 版本闸门：验证「未来版本不会被静默当 v1 打开」。
 */
class ScriptMigrationsTest {

    @Test
    fun `CURRENT 与 Script_SCHEMA_VERSION 同源`() {
        assertEquals(Script.SCHEMA_VERSION, ScriptMigrations.CURRENT)
        assertEquals(1, ScriptMigrations.CURRENT)
    }

    @Test
    fun `无 schemaVersion 字段的历史脚本归为 LEGACY 且能正常解码`() {
        val raw = """{"name":"老脚本","actions":[]}"""
        assertEquals(ScriptMigrations.Kind.LEGACY_NO_FIELD, ScriptMigrations.classify(raw).kind)
        val s = ScriptCodec.decode(raw)
        assertNotNull(s)
        assertEquals("老脚本", s!!.name)
        assertEquals(1, s.schemaVersion) // 模型默认值
    }

    @Test
    fun `当前版本脚本解码行为不变`() {
        val raw = """{"name":"v1","schemaVersion":1,"actions":[]}"""
        assertEquals(ScriptMigrations.Kind.CURRENT, ScriptMigrations.classify(raw).kind)
        assertEquals("v1", ScriptCodec.decode(raw)!!.name)
    }

    @Test
    fun `未来版本被明确挡下 不静默加载`() {
        val raw = """{"name":"v99","schemaVersion":99,"actions":[],"unknownFutureField":{"x":1}}"""
        assertEquals(ScriptMigrations.Kind.FUTURE, ScriptMigrations.classify(raw).kind)
        assertNull("未来版本必须拒绝，绝不按默认值补字段后当 v1 打开", ScriptCodec.decode(raw))
    }

    @Test
    fun `非法负版本与损坏 JSON 不会被当成合法脚本`() {
        assertEquals(ScriptMigrations.Kind.MALFORMED, ScriptMigrations.classify("""{"schemaVersion":0}""").kind)
        assertEquals(ScriptMigrations.Kind.MALFORMED, ScriptMigrations.classify("not json at all").kind)
        // 压缩容器不是 JSON，classify 返回 MALFORMED，但 decodeAny 原路径能处理
        val compressed = ScriptCompressor.encode(Script(name = "压缩容器"))
        assertEquals(ScriptMigrations.Kind.MALFORMED, ScriptMigrations.classify(compressed).kind)
        assertEquals("压缩容器", ScriptCodec.decode(compressed)!!.name)
    }

    @Test
    fun `isSupported 只放行 1 到 CURRENT`() {
        assertTrue(ScriptMigrations.isSupported(Script(schemaVersion = 1)))
        assertFalse(ScriptMigrations.isSupported(Script(schemaVersion = 2)))
        assertFalse(ScriptMigrations.isSupported(Script(schemaVersion = 0)))
        assertFalse(ScriptMigrations.isSupported(Script(schemaVersion = -1)))
    }

    @Test
    fun `apply 对 v1 恒等 迁移链幂等且不丢字段`() {
        val s = Script(name = "保真", schemaVersion = 1, actions = emptyList(), description = "desc")
        val r = ScriptMigrations.apply(s)
        assertEquals(0, r.appliedSteps)
        assertEquals(1, r.from)
        assertEquals(1, r.to)
        assertEquals("保真", r.script.name)
        assertEquals("desc", r.script.description)
        // 幂等
        val r2 = ScriptMigrations.apply(r.script)
        assertEquals(r.script, r2.script)
    }

    @Test
    fun `steps 只返回非负步数`() {
        assertEquals(0, ScriptMigrations.steps(1, 1))
        assertEquals(3, ScriptMigrations.steps(1, 4))
        assertEquals(0, ScriptMigrations.steps(5, 1))
    }
}

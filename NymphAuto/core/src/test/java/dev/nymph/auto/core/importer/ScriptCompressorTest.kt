package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCompressorTest {

    private val tpl = "iVBORw0KGgoAAAANSUhEUg" + "A".repeat(400)
    private val tpl2 = "iVBORw0KGgoAAAANSUhEUg" + "B".repeat(400)

    private fun rich(): Script = Script(
        name = "回归脚本",
        startPackage = "com.example.app",
        actions = listOf(
            Action(
                type = ActionType.CLICK_IMAGE, imageData = tpl,
                children = listOf(Action(type = ActionType.CLICK_IMAGE, imageData = tpl)),
                listeners = Listeners(onActionFail = listOf(Action(type = ActionType.CLICK_IMAGE, imageData = tpl2))),
                condition = Condition(imageData = tpl),
                postCondition = Condition(imageData = tpl2),
            ),
            Action(type = ActionType.CLICK_IMAGE, imageData = tpl),
        ),
        abortCondition = Condition(imageData = tpl2),
    )

    @Test
    fun `压缩编解码往返 完整还原所有字段`() {
        val s = rich()
        val encoded = ScriptCompressor.encode(s)
        assertTrue(encoded.startsWith(ScriptCompressor.MAGIC))
        val back = ScriptCompressor.decodeAny(encoded)
        assertNotNull(back)
        assertEquals(s.name, back!!.name)
        assertEquals(s.startPackage, back.startPackage)
        assertEquals(s.actions.size, back.actions.size)
        assertEquals(tpl, back.actions[0].imageData)
        assertEquals(tpl, back.actions[0].children[0].imageData)
        assertEquals(tpl2, back.actions[0].listeners.onActionFail[0].imageData)
        assertEquals(tpl, back.actions[0].condition!!.imageData)
        assertEquals(tpl2, back.actions[0].postCondition!!.imageData)
        assertEquals(tpl2, back.abortCondition!!.imageData)
        // 读取后引用已展开，运行引擎无需感知
        assertTrue(back.assets.isEmpty())
    }

    @Test
    fun `重复模板去重为一份资源`() {
        val deduped = ScriptCompressor.dedup(rich())
        assertEquals(2, deduped.assets.size)
        assertTrue(deduped.actions[0].imageData.startsWith("ref:"))
        assertEquals(deduped.actions[0].imageData, deduped.actions[1].imageData)
    }

    @Test
    fun `压缩后体积显著小于明文`() {
        val st = ScriptCompressor.stats(rich())
        assertTrue("stored=${st.storedBytes} plain=${st.originalBytes}", st.storedBytes < st.originalBytes)
        assertTrue(st.savedPct > 50.0)
    }

    @Test
    fun `兼容v2_6明文JSON`() {
        val plain = ScriptCodec.encode(rich())
        val back = ScriptCompressor.decodeAny(plain)
        assertNotNull(back)
        assertEquals(tpl, back!!.actions[0].imageData)
        assertEquals(tpl2, back.abortCondition!!.imageData)
    }

    @Test
    fun `ScriptCodec_decode 自动识别压缩容器`() {
        val back = ScriptCodec.decode(ScriptCompressor.encode(rich()))
        assertNotNull(back)
        assertEquals(tpl, back!!.actions[0].imageData)
    }

    @Test
    fun `损坏输入返回null 不抛异常`() {
        assertNull(ScriptCompressor.decodeAny("NAZ1:not-a-base64!!!"))
        assertNull(ScriptCompressor.decodeAny("{not json"))
        assertNull(ScriptCompressor.decodeAny("NAZ1:"))
    }

    @Test
    fun `短模板不抽资源`() {
        val s = Script(actions = listOf(Action(type = ActionType.CLICK_IMAGE, imageData = "tiny")))
        assertEquals(0, ScriptCompressor.dedup(s).assets.size)
        assertEquals("tiny", ScriptCompressor.decodeAny(ScriptCompressor.encode(s))!!.actions[0].imageData)
    }
}

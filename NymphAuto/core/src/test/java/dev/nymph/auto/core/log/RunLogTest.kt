package dev.nymph.auto.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RunLog 环形缓冲与监听器行为（纯 JVM）。 */
class RunLogTest {

    @Test
    fun `写入与快照保序`() {
        RunLog.clear()
        RunLog.i("T", "第一条")
        RunLog.w("T", "第二条")
        RunLog.e("T", "第三条")
        val s = RunLog.snapshot()
        assertEquals(3, s.size)
        assertEquals(LogLevel.INFO, s[0].level)
        assertEquals(LogLevel.WARN, s[1].level)
        assertEquals(LogLevel.ERROR, s[2].level)
        assertTrue(s[2].format().contains("第三条"))
    }

    @Test
    fun `环形缓冲上限 500 条`() {
        RunLog.clear()
        repeat(600) { RunLog.d("T", "m$it") }
        val s = RunLog.snapshot()
        assertEquals(500, s.size)
        assertEquals("m100", s.first().msg) // 最早 100 条被挤出
        assertEquals("m599", s.last().msg)
    }

    @Test
    fun `监听器收到新条目且 clear 生效`() {
        RunLog.clear()
        val got = mutableListOf<String>()
        val l: (LogEntry) -> Unit = { got += it.msg }
        RunLog.addListener(l)
        RunLog.i("T", "hello")
        assertEquals(listOf("hello"), got)
        RunLog.removeListener(l)
        RunLog.i("T", "after-remove")
        assertEquals(listOf("hello"), got) // 解绑后不再收到
        RunLog.clear()
        assertTrue(RunLog.snapshot().isEmpty())
    }

    @Test
    fun `导出文本多行可读`() {
        RunLog.clear()
        RunLog.i("Import", "导入成功")
        val text = RunLog.exportText()
        assertTrue(text.contains("I/Import: 导入成功"))
    }
}

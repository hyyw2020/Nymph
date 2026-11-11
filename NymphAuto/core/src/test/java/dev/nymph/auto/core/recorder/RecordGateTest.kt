package dev.nymph.auto.core.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordGateTest {

    private val self = "dev.nymph.auto"

    @Test
    fun `点击器内的自扰事件全部丢弃`() {
        val g = RecordGate(self)
        assertFalse(g.armed)
        repeat(5) {
            assertEquals(RecordGate.Decision.DROP_SELF, g.accept(self))
        }
        assertFalse(g.armed)
        assertEquals(5L, g.droppedSelfCount())
    }

    @Test
    fun `待命期间无包名事件不记录`() {
        val g = RecordGate(self)
        assertEquals(RecordGate.Decision.DROP_WAITING, g.accept(""))
        assertFalse(g.armed)
    }

    @Test
    fun `切到目标APP的第一个事件解除待命且本身被记录`() {
        val g = RecordGate(self)
        assertEquals(RecordGate.Decision.ARMED, g.accept("com.target.app"))
        assertTrue(g.armed)
        assertEquals(RecordGate.Decision.ACCEPT, g.accept("com.target.app"))
    }

    @Test
    fun `解除待命后本应用事件仍被丢弃`() {
        val g = RecordGate(self)
        assertEquals(RecordGate.Decision.ARMED, g.accept("com.target.app"))
        // 中途回点击器点"撤销"：不能进脚本
        assertEquals(RecordGate.Decision.DROP_SELF, g.accept(self))
        assertEquals(1L, g.droppedSelfCount())
        assertEquals(RecordGate.Decision.ACCEPT, g.accept("com.target.app"))
    }

    @Test
    fun `未传自包名时保持v2_6行为立即记录`() {
        val g = RecordGate()
        assertTrue(g.armed)
        assertEquals(RecordGate.Decision.ACCEPT, g.accept(""))
        assertEquals(RecordGate.Decision.ACCEPT, g.accept(self))
        assertEquals(0L, g.droppedSelfCount())
    }
}

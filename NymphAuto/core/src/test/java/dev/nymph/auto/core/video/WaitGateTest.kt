package dev.nymph.auto.core.video

import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.ConditionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.6：等待画面出现门控的纯 JVM 契约。 */
class WaitGateTest {

    private val tpl = "iVBORw0KGgo" + "B".repeat(200)

    @Test
    fun `图片门控是无限等待的出现条件`() {
        val c = WaitGate.image(tpl, 83)
        assertEquals(ConditionKind.APPEAR_IMAGE, c.kind)
        assertEquals(tpl, c.imageData)
        assertEquals(83, c.imageSimilarity)
        assertTrue(c.repeatCheckUntilOk)
        assertEquals(WaitGate.INFINITE, c.repeatCheckMax)
        assertEquals(WaitGate.DEFAULT_POLL_MS, c.repeatCheckIntervalMs)
    }

    @Test
    fun `相似度越界被钳制 不让条件永远不命中或全命中`() {
        assertEquals(1, WaitGate.image(tpl, -5).imageSimilarity)
        assertEquals(100, WaitGate.image(tpl, 999).imageSimilarity)
    }

    @Test
    fun `门控只支持有可视目标的找图类动作 坐标点击不可等`() {
        assertTrue(WaitGate.supports(ActionType.CLICK_IMAGE))
        assertTrue(WaitGate.supports(ActionType.CLICK_TEXT))
        assertTrue(WaitGate.supports(ActionType.CLICK_COLOR))
        assertFalse(WaitGate.supports(ActionType.TAP))
        assertFalse(WaitGate.supports(ActionType.SWIPE))
        assertFalse(WaitGate.supports(ActionType.WAIT))
    }

    @Test
    fun `无限等待不被帧同步档压快 避免长动画期间 33ms 全屏找图`() {
        assertEquals(300L, WaitGate.pollIntervalMs(300L, 33L, infinite = true, accelerated = true))
        assertEquals(1000L, WaitGate.pollIntervalMs(1000L, 33L, infinite = true, accelerated = true))
    }

    @Test
    fun `有限等待保留既有加速语义`() {
        assertEquals(33L, WaitGate.pollIntervalMs(300L, 33L, infinite = false, accelerated = true))
        assertEquals(300L, WaitGate.pollIntervalMs(300L, 1000L, infinite = false, accelerated = true))
    }

    @Test
    fun `LEGACY 档沿用脚本间隔`() {
        assertEquals(300L, WaitGate.pollIntervalMs(300L, 33L, infinite = false, accelerated = false))
    }

    @Test
    fun `轮询间隔有 50ms 下限 防零间隔空转`() {
        assertEquals(50L, WaitGate.pollIntervalMs(0L, 1L, infinite = true, accelerated = true))
        assertEquals(50L, WaitGate.pollIntervalMs(-100L, 1L, infinite = false, accelerated = false))
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.17 / D3：位移事件链路四态诊断的纯 JVM 契约。 */
class DragProbeTest {

    @Test
    fun `未采集到手势时结论为 NO_DOWN`() {
        val r = DragProbe().report()
        assertEquals(DragProbe.Kind.NO_DOWN, r.kind)
        assertFalse(r.ok)
        assertEquals(0, r.downCount)
        assertTrue(r.action!!.isNotBlank())
    }

    @Test
    fun `仅收到按下时定位到派发层`() {
        val p = DragProbe()
        p.onDown()
        p.onEnd()
        assertEquals(DragProbe.Kind.MOVE_INTERCEPTED, p.report().kind)
    }

    @Test
    fun `移动已到达但未进入提交时为 COMMIT_BLOCKED`() {
        val p = DragProbe()
        p.onDown()
        p.onMove()
        p.onMove()
        val r = p.report()
        assertEquals(DragProbe.Kind.COMMIT_BLOCKED, r.kind)
        assertEquals(2, r.moveCount)
        assertTrue("尚未进入" in r.headline)
    }

    @Test
    fun `提交已执行但位置未变化时为 COMMIT_BLOCKED`() {
        val p = DragProbe()
        p.onDown()
        p.onMove()
        p.onCommit(100, 200, 100, 200)
        val r = p.report()
        assertEquals(DragProbe.Kind.COMMIT_BLOCKED, r.kind)
        assertEquals(0, r.displacementPx)
        assertTrue("窗口位置未变化" in r.headline)
    }

    @Test
    fun `四阶段齐备时结论为 PASS 且无建议动作`() {
        val p = DragProbe()
        p.onDown()
        p.onMove()
        p.onCommit(100, 200, 118, 215)
        p.onMove()
        p.onCommit(118, 215, 130, 230)
        p.onEnd()
        val r = p.report()
        assertEquals(DragProbe.Kind.PASS, r.kind)
        assertTrue(r.ok)
        assertNull(r.action)
        assertEquals(2, r.commitCount)
        assertEquals(18 + 15 + 12 + 15, r.displacementPx)
    }

    @Test
    fun `系统取消且无位移时定位到派发层`() {
        val p = DragProbe()
        p.onDown()
        p.onMove()
        p.onEnd(wasCancelled = true)
        val r = p.report()
        assertEquals(DragProbe.Kind.MOVE_INTERCEPTED, r.kind)
        assertTrue(r.cancelled)
        assertTrue("派发策略" in r.action!!)
    }

    @Test
    fun `新按下重置上一轮观测量`() {
        val p = DragProbe()
        p.onDown(); p.onMove(); p.onCommit(0, 0, 20, 20)
        assertEquals(DragProbe.Kind.PASS, p.report().kind)
        p.onDown()
        val r = p.report()
        assertEquals(DragProbe.Kind.MOVE_INTERCEPTED, r.kind)
        assertEquals(1, r.downCount)
        assertEquals(0, r.moveCount)
        assertEquals(0, r.commitCount)
    }

    @Test
    fun `判定优先级按失败代价排序`() {
        val p = DragProbe()
        p.onCommit(0, 0, 50, 50) // 无 DOWN 时提交记录不生效
        assertEquals(DragProbe.Kind.NO_DOWN, p.report().kind)
        p.onDown()
        assertEquals(DragProbe.Kind.MOVE_INTERCEPTED, p.report().kind)
        p.onMove()
        assertEquals(DragProbe.Kind.COMMIT_BLOCKED, p.report().kind)
    }

    @Test
    fun `原始观测量完整写入结构化串`() {
        val p = DragProbe()
        p.onDown(); p.onMove(); p.onCommit(0, 0, 7, 9)
        val r = p.report()
        assertEquals("DOWN=1 MOVE=1 COMMIT=1 DISPLACEMENT=16px CANCEL=false", r.metrics)
        assertTrue("原始观测量" in r.detail)
    }

    @Test
    fun `结论明细包含层级定位而非主观判断`() {
        val p = DragProbe()
        p.onDown(); p.onMove(); p.onCommit(0, 0, 1, 1)
        val r = p.report()
        assertTrue(r.detail.contains("原始观测量"))
        assertFalse("结论不应包含建议之外的操作指引", r.headline.contains("重试"))
    }
}

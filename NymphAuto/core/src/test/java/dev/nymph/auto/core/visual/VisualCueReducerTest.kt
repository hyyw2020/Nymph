// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可视化状态机（纯 JVM）：生命周期、上限、进度、裁剪、合并包围盒、空状态。 */
class VisualCueReducerTest {

    private fun cue(
        id: Long,
        kind: VisualKind = VisualKind.TAP,
        at: Long = 1000L,
        dur: Long = 320L,
        pts: FloatArray = floatArrayOf(100f, 200f),
    ) = VisualCue(id, kind, at, dur, pts, step = 3, totalSteps = 12)

    @Test
    fun `到期后被移除且空帧返回null包围盒`() {
        val r = VisualCueReducer()
        r.add(cue(1, at = 1000L, dur = 320L))
        val mid = r.frame(1160L)
        assertEquals(1, mid.elements.size)
        assertNotNull(mid.boundsPx)

        val done = r.frame(1000L + 320L) // 恰好到期（>=）即移除
        assertTrue(done.isEmpty)
        assertNull(done.boundsPx)
        assertEquals(0, r.size())
    }

    @Test
    fun `进度边界钳制在0到1`() {
        val r = VisualCueReducer()
        r.add(cue(1, at = 1000L, dur = 400L))
        assertEquals(0f, r.frame(500L).elements[0].progress, 1e-6f) // 时间倒退钳制到 0
        assertEquals(0.5f, r.frame(1200L).elements[0].progress, 1e-6f)
        // 到期前的最后一帧：进度 <1（>=1 的那一刻 cue 已被移除）
        val near = r.frame(1399L).elements[0].progress
        assertTrue("进度应落在 0..1", near in 0.99f..1f)
        assertTrue(r.frame(1400L).isEmpty) // 到期即删，绝不留一个 progress=1 的残影
    }

    @Test
    fun `同屏数量上限丢弃最老的`() {
        val cfg = VisualConfig(maxCues = 3)
        val r = VisualCueReducer(cfg)
        repeat(5) { i -> r.add(cue(i.toLong(), at = 1000L + i, dur = 5000L)) }
        assertEquals(3, r.size())
        val f = r.frame(1010L)
        // 保留的是 id 2,3,4
        assertEquals(listOf(2L, 3L, 4L), f.elements.map { it.cue.id })
    }

    @Test
    fun `多指裁剪到前四指`() {
        val cfg = VisualConfig(maxFingers = 4)
        val r = VisualCueReducer(cfg)
        // 6 指 = 12 个 float
        val pts = FloatArray(12) { (it * 10).toFloat() }
        r.add(cue(1, kind = VisualKind.MULTI_TOUCH, pts = pts))
        val e = r.frame(1000L).elements[0]
        assertEquals(8, e.points.size) // 4 指 × 2
        assertEquals(0f, e.points[0], 0f)
        assertEquals(70f, e.points[7], 0f)
    }

    @Test
    fun `轨迹裁剪到最近N点上限`() {
        val cfg = VisualConfig(maxTrailPoints = 24)
        val r = VisualCueReducer(cfg)
        val pts = FloatArray(60) { it.toFloat() } // 30 点
        r.add(cue(1, kind = VisualKind.SWIPE, pts = pts))
        assertEquals(48, r.frame(1000L).elements[0].points.size) // 24 点 × 2
    }

    @Test
    fun `非法坐标被清洗且不足一点则丢弃`() {
        val r = VisualCueReducer()
        r.add(cue(1, pts = floatArrayOf(Float.NaN, 10f, 20f, 30f)))
        r.add(cue(2, pts = floatArrayOf(Float.POSITIVE_INFINITY, Float.NaN)))
        assertEquals(1, r.size())
        val e = r.frame(1000L).elements[0]
        assertEquals(2, e.points.size)
        assertEquals(listOf(20f, 30f), e.points.toList())
    }

    @Test
    fun `奇数点位被截断为成对`() {
        val r = VisualCueReducer()
        r.add(cue(1, pts = floatArrayOf(1f, 2f, 3f, 4f, 5f)))
        assertEquals(4, r.frame(1000L).elements[0].points.size)
    }

    @Test
    fun `多元素合并包围盒取全量极值`() {
        val r = VisualCueReducer()
        r.add(cue(1, pts = floatArrayOf(10f, 20f)))
        r.add(cue(2, pts = floatArrayOf(300f, 400f, 50f, 60f)))
        val b = r.frame(1000L).boundsPx!!
        assertEquals(10f, b[0], 0f)  // left
        assertEquals(20f, b[1], 0f)  // top
        assertEquals(300f, b[2], 0f) // right
        assertEquals(400f, b[3], 0f) // bottom
    }

    @Test
    fun `空状态不产生任何抑制且isEmpty为真`() {
        val r = VisualCueReducer()
        val f = r.frame(1000L)
        assertTrue(f.isEmpty)
        assertNull(f.boundsPx)
        assertFalse(r.config().enabled)
    }

    @Test
    fun `默认配置总开关与预告关闭`() {
        val c = VisualConfig()
        assertFalse(c.enabled)
        assertFalse(c.previewEnabled)
        assertEquals(350L, c.previewMs)
    }

    @Test
    fun `配置表accepts与specFor覆盖全部手势且新增不改渲染`() {
        val c = VisualConfig(tapEnabled = false, trailEnabled = false)
        assertFalse(c.accepts(VisualKind.TAP))
        assertFalse(c.accepts(VisualKind.SWIPE))
        assertTrue(c.accepts(VisualKind.PREVIEW))
        VisualKind.entries.forEach { k ->
            assertTrue("${k.name} 必须有规格", VisualConfig.specFor(k).atoms.isNotEmpty())
        }
        assertEquals(2, VisualConfig.specFor(VisualKind.TAP).ringCount)
        assertEquals(60L, VisualConfig.specFor(VisualKind.TAP).ringStaggerMs)
    }

    @Test
    fun `零时长或负时长立即视为结束`() {
        val r = VisualCueReducer()
        r.add(cue(1, dur = 0L))
        r.add(cue(2, dur = -5L))
        assertTrue(r.frame(1000L).isEmpty)
    }

    @Test
    fun `clear清空所有活跃元素`() {
        val r = VisualCueReducer()
        repeat(4) { i -> r.add(cue(i.toLong(), dur = 5000L)) }
        assertEquals(4, r.size())
        r.clear()
        assertEquals(0, r.size())
        assertTrue(r.frame(1000L).isEmpty)
    }
}

package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPredictorTest {

    @Test
    fun `线性运动外推到下一帧`() {
        val p = MotionPredictor()
        p.observe(100.0, 100.0, 0L)
        p.observe(110.0, 100.0, 100L)
        val r = p.predict(200L)
        assertNotNull(r)
        assertEquals(120.0, r!!.x, 2.0)
        assertEquals(100.0, r.y, 2.0)
        assertTrue(r.vx > 0.0)
    }

    @Test
    fun `样本不足或乱序不外推`() {
        val p = MotionPredictor()
        assertNull(p.predict(100L))
        p.observe(10.0, 10.0, 100L)
        assertNull(p.predict(200L))
        // 时间戳不递增必须被丢弃，否则速度会算成负数
        p.observe(999.0, 999.0, 50L)
        assertEquals(1, p.sampleCount)
    }

    @Test
    fun `陈旧样本置信度归零退回全屏搜索`() {
        val p = MotionPredictor(maxExtrapolateMs = 100L, maxSamples = 4)
        p.observe(0.0, 0.0, 0L)
        p.observe(10.0, 0.0, 100L)
        assertNull(p.predict(10_000L))
    }

    @Test
    fun `速度上限裁剪防止误匹配瞬移带飞预测`() {
        val p = MotionPredictor(maxSpeedPxPerMs = 1.0, maxExtrapolateMs = 1000L)
        p.observe(0.0, 0.0, 0L)
        p.observe(10_000.0, 0.0, 100L)
        val r = p.predict(200L)
        assertNotNull(r)
        assertEquals(1.0, r!!.vx, 1e-6)
        // 位置本身就在 10000（最后一次观测），裁剪的是"外推增量"而不是绝对坐标
        assertTrue("lead=${r.x - 10_000.0}", r.x - 10_000.0 <= 101.0)
    }

    @Test
    fun `窄ROI包含预测点且随置信度自适应`() {
        val p = MotionPredictor(maxExtrapolateMs = 1000L)
        repeat(5) { i -> p.observe(200.0 + i * 20.0, 400.0, i * 33L) }
        val roi = p.predictRoi(5 * 33L + 33L, 1080, 2400)
        assertNotNull(roi)
        assertTrue(roi!!.right > roi.left)
        assertTrue(roi.bottom > roi.top)
        // 预测点在框内
        val pred = p.predict(5 * 33L + 33L)!!
        val cxPct = 100.0 * pred.x / 1080
        assertTrue(cxPct >= roi.left && cxPct <= roi.right)
        // 半径不超过上限
        assertTrue(roi.right - roi.left <= 90.0)
    }

    @Test
    fun `reset清空历史`() {
        val p = MotionPredictor()
        p.observe(1.0, 1.0, 0L)
        p.observe(2.0, 2.0, 33L)
        p.reset()
        assertEquals(0, p.sampleCount)
        assertNull(p.predict(66L))
    }
}

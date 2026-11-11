package dev.nymph.auto.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapCandidateDetectorTest {

    private val W = 64
    private val H = 64

    private fun flat(v: Int, tMs: Long) =
        TapCandidateDetector.FrameSample(tMs, W, H, IntArray(W * H) { v })

    private fun framesWithSpot(tMsOfChange: Long, spotValue: Int = 250): List<TapCandidateDetector.FrameSample> {
        val a = flat(100, 0L)
        val b = flat(100, tMsOfChange)
        // 在 (30..34, 30..34) 造一小块局部高亮，模拟触点涟漪
        for (y in 30..34) for (x in 30..34) b.gray[y * W + x] = spotValue
        val c = flat(100, tMsOfChange + 100L)
        return listOf(a, b, c)
    }

    @Test
    fun `局部突发被检测为候选触点且坐标接近`() {
        val r = TapCandidateDetector.detect(framesWithSpot(100L))
        assertEquals(1, r.size)
        val c = r.single()
        assertEquals(100L, c.tMs)
        assertTrue("x=${c.x}", kotlin.math.abs(c.x - 32) <= 4)
        assertTrue("y=${c.y}", kotlin.math.abs(c.y - 32) <= 4)
        assertTrue(c.score > 0.5)
        assertTrue(c.radiusPx > 0)
    }

    @Test
    fun `全屏变化被判定为翻页滚动并跳过`() {
        val a = flat(100, 0L)
        val b = flat(250, 100L)
        val c = flat(100, 200L)
        assertTrue(TapCandidateDetector.detect(listOf(a, b, c)).isEmpty())
    }

    @Test
    fun `静止序列无候选`() {
        assertTrue(TapCandidateDetector.detect(listOf(flat(100, 0L), flat(100, 100L), flat(100, 200L))).isEmpty())
    }

    @Test
    fun `帧数不足或尺寸不一致返回空`() {
        assertTrue(TapCandidateDetector.detect(listOf(flat(100, 0L))).isEmpty())
        val odd = TapCandidateDetector.FrameSample(100L, 32, 32, IntArray(32 * 32) { 100 })
        assertTrue(TapCandidateDetector.detect(listOf(flat(100, 0L), odd)).isEmpty())
    }

    @Test
    fun `连续帧同一位置会被时空去重`() {
        // 连续 3 帧都在同一位置出现局部变化，不应刷出 3 个候选
        val frames = ArrayList<TapCandidateDetector.FrameSample>()
        frames += flat(100, 0L)
        for (i in 1..4) {
            val f = flat(100, i * 50L)
            for (y in 30..34) for (x in 30..34) f.gray[y * W + x] = 250
            frames += f
        }
        val r = TapCandidateDetector.detect(frames)
        assertTrue("candidates=${r.size}", r.size <= 2)
        assertTrue(r.all { kotlin.math.abs(it.x - 32) <= 6 })
    }

    @Test
    fun `结果按时间排序且不超过上限`() {
        val r = TapCandidateDetector.detect(
            framesWithSpot(100L),
            TapCandidateDetector.Options(maxCandidates = 1),
        )
        assertTrue(r.size <= 1)
        assertEquals(r.sortedBy { it.tMs }, r)
    }

    // ---- v2.9.6：默认勾选必须「覆盖阶段但不全选」----

    private fun cand(tMs: Long, score: Double) =
        TapCandidateDetector.TapCandidate(tMs, 0, 10, 10, 4, score)

    @Test
    fun `空候选与少于桶数时行为保守`() {
        assertTrue(TapCandidateDetector.pickDefaults(emptyList()).isEmpty())
        val few = listOf(cand(0L, 0.5), cand(100L, 0.4))
        assertEquals(setOf(0, 1), TapCandidateDetector.pickDefaults(few, buckets = 4))
        assertTrue(TapCandidateDetector.pickDefaults(few, buckets = 0).isEmpty())
    }

    @Test
    fun `默认勾选按时间分桶覆盖各阶段且总数可控`() {
        // 模拟一段 50 秒游戏视频：前段战斗、中段转场噪声、后段结算
        val cs = listOf(
            cand(0L, 0.30), cand(200L, 0.90), cand(8_000L, 0.40),
            cand(30_000L, 0.95), cand(31_000L, 0.80), cand(35_000L, 0.99),
            cand(45_000L, 0.60), cand(45_100L, 0.85), cand(48_000L, 0.50),
        )
        // 6 桶是默认值：中段 35s 的动画噪声(0.99)不能挤掉 45s 的结算按钮。
        val picked = TapCandidateDetector.pickDefaults(cs, buckets = 6)
        assertTrue("总数应可控：${picked.size}", picked.size <= 6)
        val times = picked.map { cs[it].tMs }.sorted()
        assertTrue("应覆盖早期阶段：${times}", times.any { it < 8_000L })
        assertTrue("应覆盖中段：${times}", times.any { it in 8_000L..40_000L })
        assertTrue("应覆盖结算阶段：${times}", times.any { it > 40_000L })
    }

    @Test
    fun `同一时间桶内只取得分最高者 抑制转场动画噪声`() {
        val cs = listOf(
            cand(0L, 0.10), cand(100L, 0.95), cand(200L, 0.30),
            cand(45_000L, 0.20), cand(45_100L, 0.88),
        )
        val picked = TapCandidateDetector.pickDefaults(cs, buckets = 2)
        assertEquals(2, picked.size)
        assertTrue(1 in picked)   // 前段最高分
        assertTrue(4 in picked)   // 结算段最高分
        assertTrue(3 !in picked)  // 同桶低分被抑制
    }
}

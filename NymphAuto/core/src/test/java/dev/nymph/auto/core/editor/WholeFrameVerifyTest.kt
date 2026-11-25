package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.GlobalVerifyMethod
import dev.nymph.auto.core.vision.ImageFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.3 S4：整图缩放 NCC 门纯 JVM 单测。
 * 覆盖三件被门禁点名的事：几何门串联（不过直接不跑 NCC）、同图/等比缩放通过、
 * 跨宽高比 fail-closed。阈值标保守初值，真机精度未验证。
 */
class WholeFrameVerifyTest {

    private fun gradient(w: Int, h: Int): ImageFinder.Image = ImageFinder.Image(
        w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 255 / w.coerceAtLeast(1)).coerceIn(0, 255)
            val g = (y * 255 / h.coerceAtLeast(1)).coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or 0x40
        },
    )

    @Test
    fun `未启用时跳过整图门_直接通过零回归`() {
        val base = gradient(64, 64)
        val cur = gradient(64, 64)
        val v = SceneTrigger.verifyWholeFrame(base, cur, GlobalVerifyMethod.OFF, 70)
        assertFalse(v.enabled)
        assertTrue(v.passed)
        assertTrue(v.geometryOk)
        assertEquals(-1.0, v.score, 0.0)
    }

    @Test
    fun `同宽高比同图整图NCC高分通过`() {
        val base = gradient(64, 96)
        val cur = gradient(64, 96) // 完全同一帧
        val v = SceneTrigger.verifyWholeFrame(base, cur, GlobalVerifyMethod.NCC, 70)
        assertTrue(v.enabled)
        assertTrue(v.geometryOk)
        assertTrue("同图 NCC 应接近 1 并通过", v.passed)
        assertTrue("score=${v.score} 应 >= 0.9", v.score >= 0.9)
    }

    @Test
    fun `跨宽高比几何门fail-closed且不跑NCC`() {
        val base = gradient(60, 90)      // 2:3
        val cur = gradient(90, 60)       // 3:2，宽高比互为倒数
        val v = SceneTrigger.verifyWholeFrame(base, cur, GlobalVerifyMethod.NCC, 70)
        assertTrue(v.enabled)
        assertFalse("跨宽高比必须几何门失败", v.geometryOk)
        assertFalse("几何门失败必须 fail-closed 不派发", v.passed)
        assertEquals("几何门失败不跑 NCC，分数保持 -1", -1.0, v.score, 0.0)
    }

    @Test
    fun `小方向偏差在2_percent容差内通过几何门`() {
        // 相对偏差 ~0.8%，在 2% 容差内 → 几何门通过
        val base = gradient(50, 100)     // 0.5
        val cur = gradient(50, 99)       // 0.50505, 偏差 ~1.0%
        assertTrue(SceneTrigger.geometryWithinTol(50, 100, 50, 99, 0.02))
        assertFalse(SceneTrigger.geometryWithinTol(50, 100, 100, 50, 0.02))
    }

    @Test
    fun `阈值收紧只fail-closed不提高命中_保守初值语义`() {
        // 用两张同宽高比、不同纹理的梯度图，让 NCC 落在一个可评的中间分数。
        val base = gradient(64, 64)
        val cur = ImageFinder.Image(64, 64, IntArray(64 * 64) { i ->
            val y = i / 64
            val v = (y * 200 / 64).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        })
        val loose = SceneTrigger.verifyWholeFrame(base, cur, GlobalVerifyMethod.NCC, 1)
        val tight = SceneTrigger.verifyWholeFrame(base, cur, GlobalVerifyMethod.NCC, 100)
        assertEquals("同一对图 NCC 分数与阈值无关", loose.score, tight.score, 1e-9)
        assertTrue("${loose.score}: 极低阈值应放行", loose.passed)
        assertFalse("100% 阈值对非同图必 fail-closed（宁可不点）", tight.passed)
    }

    @Test
    fun `无基线时即使方法为NCC也跳过`() {
        val cur = gradient(64, 64)
        val v = SceneTrigger.verifyWholeFrame(null, cur, GlobalVerifyMethod.NCC, 70)
        assertFalse(v.enabled)
        assertTrue(v.passed)
    }
}

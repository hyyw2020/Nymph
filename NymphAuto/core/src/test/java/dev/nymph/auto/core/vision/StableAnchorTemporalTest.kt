// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.31 时域掩码回归防线：只把「帧间确实变化」的 cell 纳入掩码，
 * 与锚点重叠的 cell 一律排除，尺寸不一致 / 无变化时返回空列表，
 * 由装配期显式回退单帧启发式而不是静默伪装成时域结果。
 */
class StableAnchorTemporalTest {

    private fun solid(w: Int, h: Int, argb: Int) =
        ImageFinder.Image(w, h, IntArray(w * h) { argb })

    private val fullRoi = StableAnchor.PxRect(0, 0, 64, 64)
    private val anchor = StableAnchor.PxRect(32, 32, 48, 48)

    @Test
    fun `相同两帧灰度差为0`() {
        val a = solid(16, 16, 0xFF808080.toInt())
        val b = solid(16, 16, 0xFF808080.toInt())
        assertEquals(0.0, StableAnchor.meanAbsDiff(a, b, 0, 0, 16, 16), 1e-9)
    }

    @Test
    fun `灰到白的灰度差约127`() {
        val a = solid(16, 16, 0xFF808080.toInt())
        val b = solid(16, 16, 0xFFFFFFFF.toInt())
        assertEquals(127.0, StableAnchor.meanAbsDiff(a, b, 0, 0, 16, 16), 1e-6)
    }

    @Test
    fun `尺寸不一致的灰度差按0处理`() {
        val a = solid(16, 16, 0xFF808080.toInt())
        val b = solid(16, 8, 0xFFFFFFFF.toInt())
        assertEquals(0.0, StableAnchor.meanAbsDiff(a, b, 0, 0, 16, 16), 0.0)
    }

    @Test
    fun `两帧完全相同时不产生掩码`() {
        val a = solid(64, 64, 0xFF808080.toInt())
        val b = solid(64, 64, 0xFF808080.toInt())
        assertTrue(StableAnchor.planMaskTemporal(a, b, fullRoi, anchor).isEmpty())
    }

    @Test
    fun `尺寸不一致时返回空列表供装配期回退`() {
        val a = solid(64, 64, 0xFF808080.toInt())
        val b = solid(64, 32, 0xFF808080.toInt())
        assertTrue(StableAnchor.planMaskTemporal(a, b, fullRoi, anchor).isEmpty())
    }

    @Test
    fun `只有帧间变化的cell入选且锚点重叠cell被排除`() {
        val base = solid(64, 64, 0xFF808080.toInt())
        val later = ImageFinder.Image(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })
        // 左上角 16×16 变白；同时让锚点所在 32..48 也变白，检验排除逻辑
        for (y in 0 until 16) {
            for (x in 0 until 16) later.argb[y * 64 + x] = 0xFFFFFFFF.toInt()
        }
        for (y in 32 until 48) {
            for (x in 32 until 48) later.argb[y * 64 + x] = 0xFFFFFFFF.toInt()
        }
        val masks = StableAnchor.planMaskTemporal(base, later, fullRoi, anchor, cellPx = 16)
        assertEquals("只有左上角变化的 cell 应入选", 1, masks.size)
        assertEquals(StableAnchor.PxRect(0, 0, 16, 16), masks[0])
    }

    @Test
    fun `阈值提高后无cell入选`() {
        val base = solid(64, 64, 0xFF808080.toInt())
        val later = ImageFinder.Image(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })
        for (y in 0 until 16) {
            for (x in 0 until 16) later.argb[y * 64 + x] = 0xFFFFFFFF.toInt()
        }
        assertTrue(StableAnchor.planMaskTemporal(base, later, fullRoi, anchor, 16, 200.0).isEmpty())
    }

    @Test
    fun `单帧空间方差启发式仍保留作回退`() {
        // 计划掩码旧接口不被删除：纹理块仍能被挑出，保证无第二帧时零回归
        val img = ImageFinder.Image(64, 64, IntArray(64 * 64) { i ->
            if ((i / 64) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        })
        val masks = StableAnchor.planMask(img, fullRoi, anchor)
        assertTrue("旧接口必须仍可用", masks.isNotEmpty())
    }
}

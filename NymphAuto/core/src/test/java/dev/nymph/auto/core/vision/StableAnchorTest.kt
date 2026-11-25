// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import dev.nymph.auto.core.model.MaskRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.15 / A1 根因修复回归防线：稳定局部锚点选取 + 动态区域掩码（纯 JVM）。
 * 锁死「整块 ROI 当模板」被替换为「ROI 内稳定小块当模板、ROI 退回搜索范围」这条
 * 语义不回退，以及掩码绝不覆盖锚点自身、非法输入不崩。
 */
class StableAnchorTest {

    /** 造一张图：背景纯色，指定矩形内放高频棋盘（高梯度 / 高方差）。 */
    private fun image(w: Int, h: Int, hi: StableAnchor.PxRect? = null): ImageFinder.Image {
        val argb = IntArray(w * h) { 0xFF202020.toInt() }
        if (hi != null) {
            for (y in hi.t until hi.b) {
                for (x in hi.l until hi.r) {
                    if (x in 0 until w && y in 0 until h) {
                        argb[y * w + x] = if (((x / 4) + (y / 4)) % 2 == 0)
                            0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    }
                }
            }
        }
        return ImageFinder.Image(w, h, argb)
    }

    // ------------------------------------------------------------ 方差 / 梯度

    @Test
    fun `纯色区域方差与梯度为零 棋盘区域显著为正`() {
        val img = image(120, 120, StableAnchor.PxRect(20, 20, 100, 100))
        val flat = StableAnchor.variance(img, 0, 0, 20, 20)
        val textured = StableAnchor.variance(img, 20, 20, 100, 100)
        assertTrue("纯色方差应≈0: $flat", flat < 0.001)
        assertTrue("棋盘方差应>0: $textured", textured > 0.01)
        assertTrue("棋盘梯度应>0", StableAnchor.edgeEnergy(img, 20, 20, 100, 100) > 0.05)
        assertTrue("纯色梯度应≈0", StableAnchor.edgeEnergy(img, 0, 0, 20, 20) < 0.001)
    }

    @Test
    fun `非法矩形与空图像安全返回 0`() {
        val img = image(50, 50)
        assertEquals(0.0, StableAnchor.variance(img, 40, 40, 10, 10), 0.0)
        assertEquals(0.0, StableAnchor.edgeEnergy(img, -5, -5, 5, 5), 0.0)
        val empty = ImageFinder.Image(0, 0, IntArray(0))
        assertEquals(0.0, StableAnchor.variance(empty, 0, 0, 10, 10), 0.0)
        val mismatch = ImageFinder.Image(10, 10, IntArray(3))
        assertEquals(0.0, StableAnchor.variance(mismatch, 0, 0, 5, 5), 0.0)
    }

    // ------------------------------------------------------------ 锚点边长

    @Test
    fun `锚点边长随屏幕短边自适应 且横竖屏一致 被 clamp 到 24-48`() {
        assertEquals(
            StableAnchor.anchorSizePx(800, 1768),
            StableAnchor.anchorSizePx(1768, 800),
        )
        assertTrue(StableAnchor.anchorSizePx(800, 1768) in StableAnchor.ANCHOR_MIN_PX..StableAnchor.ANCHOR_MAX_PX)
        assertTrue(StableAnchor.anchorSizePx(4000, 4000) <= StableAnchor.ANCHOR_MAX_PX)
        assertTrue(StableAnchor.anchorSizePx(1, 1) >= StableAnchor.ANCHOR_MIN_PX)
    }

    // ------------------------------------------------------------ 锚点选取

    @Test
    fun `有纹理 ROI 选到稳定锚点 且贴近拾取点`() {
        // 纹理区在右侧 (60..180)，拾取点 (120,60)
        val img = image(200, 120, StableAnchor.PxRect(60, 20, 180, 100))
        val roi = StableAnchor.PxRect(0, 0, 200, 120)
        val pick = StableAnchor.pickAnchor(img, roi, 120, 60, anchorPx = 24)
        assertTrue("应判稳定", pick.stable)
        assertTrue("锚点应落在纹理区:", pick.rect.cx >= 60)
        assertTrue("锚点辨识度为正", pick.edgeEnergy > StableAnchor.MIN_EDGE_ENERGY)
    }

    @Test
    fun `整块纯色 ROI 选不到稳定锚点 显式降级`() {
        val img = image(120, 120) // 全纯色
        val pick = StableAnchor.pickAnchor(
            img, StableAnchor.PxRect(0, 0, 120, 120), 60, 60, anchorPx = 24,
        )
        assertFalse("纯色 ROI 不应误报找到稳定锚点", pick.stable)
    }

    @Test
    fun `ROI 太小或非法返回 invalid 不崩`() {
        val img = image(60, 60, StableAnchor.PxRect(0, 0, 60, 60))
        val tiny = StableAnchor.pickAnchor(img, StableAnchor.PxRect(10, 10, 14, 14), 12, 12)
        assertFalse(tiny.stable)
        val bad = StableAnchor.pickAnchor(img, StableAnchor.PxRect(50, 50, 10, 10), 0, 0)
        assertFalse(bad.stable)
    }

    // ------------------------------------------------------------ 掩码

    @Test
    fun `掩码剔除高方差 cell 且绝不覆盖锚点`() {
        val anchor = StableAnchor.PxRect(20, 20, 44, 44)          // 左侧纯色，锚点
        val hi = StableAnchor.PxRect(60, 20, 120, 100)             // 右侧棋盘，高方差
        val img = image(140, 120, hi)
        val roi = StableAnchor.PxRect(0, 0, 140, 120)
        val masks = StableAnchor.planMask(img, roi, anchor, cellPx = 24)
        assertTrue("应产生掩码", masks.isNotEmpty())
        val overlapsAnchor = { c: StableAnchor.PxRect ->
            c.l < anchor.r && c.r > anchor.l && c.t < anchor.b && c.b > anchor.t
        }
        assertFalse("掩码不得覆盖锚点", masks.any(overlapsAnchor))
        assertTrue("掩码应落在高方差区", masks.any { it.l >= 60 })
    }

    @Test
    fun `纯色 ROI 不产生掩码 非法 ROI 安全`() {
        val img = image(100, 100)
        val anchor = StableAnchor.PxRect(10, 10, 30, 30)
        assertEquals(0, StableAnchor.planMask(img, StableAnchor.PxRect(0, 0, 100, 100), anchor).size)
        assertEquals(0, StableAnchor.planMask(img, StableAnchor.PxRect(80, 80, 20, 20), anchor).size)
    }

    // ------------------------------------------------------------ 坐标换算

    @Test
    fun `像素矩形转屏幕百分比 且 clamp 到 0-100`() {
        val m: MaskRect = StableAnchor.toMaskPct(StableAnchor.PxRect(100, 200, 300, 400), 1000, 2000)
        assertEquals(10.0, m.leftPct, 0.0)
        assertEquals(10.0, m.topPct, 0.0)
        assertEquals(30.0, m.rightPct, 0.0)
        assertEquals(20.0, m.bottomPct, 0.0)
        val clamped = StableAnchor.toMaskPct(StableAnchor.PxRect(0, 0, 5000, 5000), 1000, 2000)
        assertTrue(clamped.rightPct <= 100.0 && clamped.bottomPct <= 100.0)
        val r = StableAnchor.toRegionPct(StableAnchor.PxRect(100, 200, 300, 400), 1000, 2000)
        assertEquals(10.0, r.leftPct, 0.0)
        assertEquals(30.0, r.rightPct, 0.0)
    }

    @Test
    fun `零尺寸屏幕换算不崩`() {
        val m = StableAnchor.toMaskPct(StableAnchor.PxRect(0, 0, 10, 10), 0, 0)
        assertTrue(m.rightPct in 0.0..100.0)
    }
}

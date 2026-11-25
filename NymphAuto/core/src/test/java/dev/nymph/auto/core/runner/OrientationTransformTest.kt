// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import dev.nymph.auto.core.model.MaskRect
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.vision.ImageFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OrientationTransform] 的纯 JVM 穷举测试：覆盖 0/90/180/270 四种角度与互逆性。 */
class OrientationTransformTest {

    private fun dbl(a: Double, b: Double, eps: Double = 1e-9) =
        assertEquals("值不一致", a, b, eps)

    // ---------------------------------------------------------------- norm / turns

    @Test
    fun norm_wrapsNegativeAndOverflow() {
        assertEquals(0, OrientationTransform.norm(0))
        assertEquals(1, OrientationTransform.norm(1))
        assertEquals(3, OrientationTransform.norm(3))
        assertEquals(0, OrientationTransform.norm(4))
        assertEquals(3, OrientationTransform.norm(-1))
        assertEquals(1, OrientationTransform.norm(-7))
    }

    @Test
    fun turns_computesClockwiseDelta() {
        // 拾取竖屏(0) → 运行横屏(1)：当前帧相对参考顺时针转 1 格
        assertEquals(1, OrientationTransform.turns(0, 1))
        assertEquals(3, OrientationTransform.turns(0, 3))
        assertEquals(2, OrientationTransform.turns(0, 2))
        assertEquals(0, OrientationTransform.turns(1, 1))
        assertEquals(2, OrientationTransform.turns(3, 1)) // (1-3+4)%4
    }

    @Test
    fun fromDimensions_detectsSwap() {
        assertEquals(0, OrientationTransform.fromDimensions(800, 1768, 800, 1768))
        assertEquals(0, OrientationTransform.fromDimensions(800, 1768, 900, 1900)) // 都竖
        assertEquals(1, OrientationTransform.fromDimensions(800, 1768, 1768, 800)) // 竖→横
        assertEquals(1, OrientationTransform.fromDimensions(1768, 800, 800, 1768)) // 横→竖
    }

    @Test
    fun isQuarterTurn_and_rotatedSize() {
        assertTrue(OrientationTransform.isQuarterTurn(1))
        assertTrue(OrientationTransform.isQuarterTurn(3))
        assertFalse(OrientationTransform.isQuarterTurn(0))
        assertFalse(OrientationTransform.isQuarterTurn(2))
        assertEquals(Pair(1768, 800), OrientationTransform.rotatedSize(800, 1768, 1))
        assertEquals(Pair(1768, 800), OrientationTransform.rotatedSize(800, 1768, 3))
        assertEquals(Pair(800, 1768), OrientationTransform.rotatedSize(800, 1768, 2))
        assertEquals(Pair(800, 1768), OrientationTransform.rotatedSize(800, 1768, 0))
    }

    // ---------------------------------------------------------------- geometry

    @Test
    fun geometryCompatible_sameAndRotatedPass_mismatchBlocked() {
        assertTrue(OrientationTransform.geometryCompatible(800, 1768, 800, 1768))
        assertTrue(OrientationTransform.geometryCompatible(800, 1768, 1768, 800)) // 90°
        assertTrue(OrientationTransform.geometryCompatible(1080, 2400, 2400, 1080))
        assertFalse(OrientationTransform.geometryCompatible(800, 1768, 1000, 1000)) // 比例异常
        assertTrue(OrientationTransform.geometryCompatible(0, 0, 800, 1768)) // 老脚本零回归
    }

    // ---------------------------------------------------------------- rotateImage

    @Test
    fun rotateImage_identity_whenZero() {
        val img = img(2, 3, intArrayOf(1, 2, 3, 4, 5, 6))
        val out = OrientationTransform.rotateImage(img, 0)
        assertTrue(out.argb.contentEquals(img.argb))
        assertEquals(2, out.width)
        assertEquals(3, out.height)
    }

    @Test
    fun rotateImage_90cw_mapsCorners() {
        // 2×3 矩阵：
        // 1 2
        // 3 4
        // 5 6
        // 顺时针 90° → 3×2：
        // 5 3 1
        // 6 4 2
        val img = img(2, 3, intArrayOf(1, 2, 3, 4, 5, 6))
        val out = OrientationTransform.rotateImage(img, 1)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        val expected = intArrayOf(5, 3, 1, 6, 4, 2)
        assertTrue("旋转结果错误: ${out.argb.toList()}", out.argb.contentEquals(expected))
    }

    @Test
    fun rotateImage_fourTurns_returnToOriginal() {
        val src = img(3, 4, IntArray(12) { it + 1 })
        var cur = src
        repeat(4) { cur = OrientationTransform.rotateImage(cur, 1) }
        assertEquals(3, cur.width)
        assertEquals(4, cur.height)
        assertTrue(cur.argb.contentEquals(src.argb))
    }

    @Test
    fun rotateImage_180_then90cw_equals270cw() {
        val src = img(3, 4, IntArray(12) { it * 7 + 3 })
        val via180Then90 = OrientationTransform.rotateImage(OrientationTransform.rotateImage(src, 2), 1)
        val direct270 = OrientationTransform.rotateImage(src, 3)
        assertTrue(via180Then90.argb.contentEquals(direct270.argb))
    }

    // ---------------------------------------------------------------- pointToCurrent

    @Test
    fun pointToCurrent_zeroTurns_identity() {
        val (x, y) = OrientationTransform.pointToCurrent(41.4, 84.4, 0)
        dbl(41.4, x)
        dbl(84.4, y)
    }

    @Test
    fun pointToCurrent_centerInvariant_allAngles() {
        for (t in 0..3) {
            val (x, y) = OrientationTransform.pointToCurrent(50.0, 50.0, t)
            dbl(50.0, x, 1e-9)
            dbl(50.0, y, 1e-9)
        }
    }

    @Test
    fun pointToCurrent_fourTurns_returnToOriginal() {
        var x = 30.0
        var y = 70.0
        repeat(4) {
            val r = OrientationTransform.pointToCurrent(x, y, 1)
            x = r.first
            y = r.second
        }
        dbl(30.0, x, 1e-9)
        dbl(70.0, y, 1e-9)
    }

    @Test
    fun pointToCurrent_corner90cw() {
        // 左上角 (0,0) 顺时针 90° → 右上角 (100,0)
        val (x, y) = OrientationTransform.pointToCurrent(0.0, 0.0, 1)
        dbl(100.0, x, 1e-9)
        dbl(0.0, y, 1e-9)
    }

    @Test
    fun pointToCurrent_180_mirrors() {
        val (x, y) = OrientationTransform.pointToCurrent(20.0, 30.0, 2)
        dbl(80.0, x, 1e-9)
        dbl(70.0, y, 1e-9)
    }

    @Test
    fun pointToCurrent_staysInBounds() {
        for (t in 0..3) {
            for (px in listOf(0.0, 12.5, 50.0, 87.3, 100.0)) {
                for (py in listOf(0.0, 33.0, 50.0, 99.9, 100.0)) {
                    val (x, y) = OrientationTransform.pointToCurrent(px, py, t)
                    assertTrue("x 越界: $x", x in 0.0..100.0)
                    assertTrue("y 越界: $y", y in 0.0..100.0)
                }
            }
        }
    }

    /**
     * 关键互逆性：把帧顺时针旋 q 格做归一化，匹配中心在归一化帧上的百分比，
     * 再用 pointToCurrent 顺时针旋回 q 格，应还原到原帧上同一物理点的百分比。
     * 这是「落点准确」的根基——用真实像素点验证，不靠记忆角度约定。
     */
    @Test
    fun rotateImage_thenPointBack_recoversOriginalLocation() {
        val w = 8
        val h = 12
        val src = img(w, h, IntArray(w * h) { it + 1 })
        for (q in 0..3) {
            val rotated = OrientationTransform.rotateImage(src, q)
            // 找一个唯一的物理点（原帧坐标）
            val origX = 2
            val origY = 5
            val marker = origY * w + origX + 1
            // 在旋转后的帧里找到该标记的位置
            var foundX = -1
            var foundY = -1
            for (y in 0 until rotated.height) {
                for (x in 0 until rotated.width) {
                    if (rotated.argb[y * rotated.width + x] == marker) {
                        foundX = x
                        foundY = y
                    }
                }
            }
            assertTrue("q=$q 标记丢失", foundX >= 0)
            val normXPct = 100.0 * foundX / (rotated.width - 1)
            val normYPct = 100.0 * foundY / (rotated.height - 1)
            // 归一化帧是原帧顺时针 q 格；把百分比顺时针 -q 格旋回原帧
            val back = OrientationTransform.pointToCurrent(normXPct, normYPct, -q)
            val expectedXPct = 100.0 * origX / (w - 1)
            val expectedYPct = 100.0 * origY / (h - 1)
            assertEquals("q=$q x 未还原", expectedXPct, back.first, 1e-6)
            assertEquals("q=$q y 未还原", expectedYPct, back.second, 1e-6)
        }
    }

    // ---------------------------------------------------------------- region / mask

    @Test
    fun regionToCurrent_zeroTurns_identity() {
        val r = Region(10.0, 20.0, 60.0, 80.0)
        val out = OrientationTransform.regionToCurrent(r, 0)
        assertEquals(r, out)
    }

    @Test
    fun regionToCurrent_180_swapsCorners() {
        val out = OrientationTransform.regionToCurrent(Region(10.0, 20.0, 60.0, 80.0), 2)
        dbl(40.0, out.leftPct, 1e-9)
        dbl(20.0, out.topPct, 1e-9)
        dbl(90.0, out.rightPct, 1e-9)
        dbl(80.0, out.bottomPct, 1e-9)
    }

    @Test
    fun maskToCurrent_fourTurns_identity() {
        val m = MaskRect(5.0, 15.0, 45.0, 55.0)
        var cur = m
        repeat(4) { cur = OrientationTransform.maskToCurrent(cur, 1) }
        dbl(5.0, cur.leftPct, 1e-9)
        dbl(15.0, cur.topPct, 1e-9)
        dbl(45.0, cur.rightPct, 1e-9)
        dbl(55.0, cur.bottomPct, 1e-9)
    }

    @Test
    fun driftPct_isManhattan() {
        dbl(20.0, OrientationTransform.driftPct(10.0, 10.0, 20.0, 20.0), 1e-9)
        dbl(0.0, OrientationTransform.driftPct(50.0, 50.0, 50.0, 50.0), 1e-9)
    }

    private fun img(w: Int, h: Int, argb: IntArray) = ImageFinder.Image(w, h, argb)
}

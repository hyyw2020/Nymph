// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.editor.FeatureBoxAnalysis
import dev.nymph.auto.core.vision.PickerGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.49 多框分析纯函数回归：重叠冗余检测 + 纹理强度参考。 */
class V2949FeatureBoxAnalysisTest {

    @Test
    fun `完全不重叠的两框重叠度为0`() {
        val a = PickerGeometry.Rect(0, 0, 100, 100)
        val b = PickerGeometry.Rect(200, 200, 300, 300)
        assertEquals(0.0, FeatureBoxAnalysis.overlapRatio(a, b), 1e-9)
    }

    @Test
    fun `小框完全落在大框内重叠度为1`() {
        val big = PickerGeometry.Rect(0, 0, 1000, 1000)
        val small = PickerGeometry.Rect(100, 100, 200, 200)
        assertEquals(1.0, FeatureBoxAnalysis.overlapRatio(big, small), 1e-9)
        assertEquals(1.0, FeatureBoxAnalysis.overlapRatio(small, big), 1e-9)
    }

    @Test
    fun `半重叠恰好等于阈值`() {
        val a = PickerGeometry.Rect(0, 0, 100, 100)
        val b = PickerGeometry.Rect(50, 0, 150, 100)
        assertEquals(0.5, FeatureBoxAnalysis.overlapRatio(a, b), 1e-9)
    }

    @Test
    fun `纯色区域纹理强度为0`() {
        val argb = IntArray(100 * 100) { 0xFF808080.toInt() }
        assertEquals(0, FeatureBoxAnalysis.textureStrength(argb, 100, 100, PickerGeometry.Rect(0, 0, 100, 100)))
    }

    @Test
    fun `高对比棋盘的纹理强度显著大于低对比`() {
        val size = 100
        val low = IntArray(size * size) { 0xFF808080.toInt() }
        val high = IntArray(size * size) { i ->
            val x = i % size; val y = i / size
            if (((x / 2) + (y / 2)) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val rect = PickerGeometry.Rect(0, 0, size, size)
        val sl = FeatureBoxAnalysis.textureStrength(low, size, size, rect)
        val sh = FeatureBoxAnalysis.textureStrength(high, size, size, rect)
        assertTrue("低对比 $sl < 高对比 $sh", sl < sh)
        assertTrue("高对比强度接近上限", sh >= 90)
    }

    @Test
    fun `越界矩形被夹逼不崩溃且局部区域生效`() {
        val argb = IntArray(10 * 10) { 0xFF000000.toInt() }
        val r = FeatureBoxAnalysis.textureStrength(argb, 10, 10, PickerGeometry.Rect(-5, -5, 999, 999))
        assertTrue(r in 0..100)
    }

    @Test
    fun `空数组返回0`() {
        assertEquals(0, FeatureBoxAnalysis.textureStrength(IntArray(0), 0, 0, PickerGeometry.Rect(0, 0, 1, 1)))
    }
}

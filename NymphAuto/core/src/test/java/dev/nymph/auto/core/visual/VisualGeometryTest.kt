// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 轨迹采样（纯 JVM）：定点输出、端点精确、弧长均匀、退化安全。 */
class VisualGeometryTest {

    @Test
    fun `线性插值端点精确且点数固定`() {
        val p = VisualGeometry.line(0f, 0f, 100f, 200f, 12)
        assertEquals(24, p.size)
        assertEquals(0f, p[0], 1e-5f); assertEquals(0f, p[1], 1e-5f)
        assertEquals(100f, p[22], 1e-4f); assertEquals(200f, p[23], 1e-4f)
    }

    @Test
    fun `二次贝塞尔经过首尾且受控制点牵引`() {
        val p = VisualGeometry.quad(0f, 0f, 100f, 200f, 200f, 0f, 16)
        assertEquals(32, p.size)
        assertEquals(0f, p[0], 1e-5f); assertEquals(0f, p[1], 1e-5f)
        assertEquals(200f, p[30], 1e-4f); assertEquals(0f, p[31], 1e-4f)
        // t=0.5 处 y 应被控制点抬高（直线插值此处 y=0）
        val midY = p[16 + 1]
        assertTrue("中点应高于直线（受控制点牵引），实际 $midY", midY > 50f)
    }

    @Test
    fun `折线重采样定点输出且首尾保持`() {
        // 一条带折点的路径
        val src = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 20f, 10f)
        val p = VisualGeometry.resample(src, 24)
        assertEquals(48, p.size)
        assertEquals(0f, p[0], 1e-4f); assertEquals(0f, p[1], 1e-4f)
        assertEquals(20f, p[46], 1e-3f); assertEquals(10f, p[47], 1e-3f)
        // 相邻点间距应大致均匀（弧长重采样）
        var min = Float.MAX_VALUE; var max = 0f
        for (i in 1 until 24) {
            val dx = p[i * 2] - p[(i - 1) * 2]
            val dy = p[i * 2 + 1] - p[(i - 1) * 2 + 1]
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            min = minOf(min, d); max = maxOf(max, d)
        }
        assertTrue("间距应均匀 min=$min max=$max", max - min < min * 2f + 0.5f)
    }

    @Test
    fun `单点与空输入安全退化为两点`() {
        assertEquals(4, VisualGeometry.resample(floatArrayOf(5f, 6f)).size)
        assertEquals(4, VisualGeometry.resample(FloatArray(0)).size)
    }

    @Test
    fun `重合点不产生NaN`() {
        val p = VisualGeometry.resample(floatArrayOf(3f, 3f, 3f, 3f, 3f, 3f))
        p.forEach { assertTrue("不应出现 NaN", it.isFinite()) }
    }

    @Test
    fun `点数上限被钳制`() {
        assertTrue(VisualGeometry.line(0f, 0f, 1f, 1f, 999).size <= 64)
        assertTrue(VisualGeometry.quad(0f, 0f, 1f, 1f, 2f, 2f, 999).size <= 64)
    }
}

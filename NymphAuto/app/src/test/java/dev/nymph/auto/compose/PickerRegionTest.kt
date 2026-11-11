// SPDX-License-Identifier: MIT
package dev.nymph.auto.compose

import dev.nymph.auto.ui.PickerRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 框选区域结果（v2.7.1）。覆盖百分比换算、表达式透传、Locale 安全，
 * 是「框选 ROI / 模板范围」回填与运行时解析的回归防线。
 */
class PickerRegionTest {

    @Test
    fun `四角百分比全精度换算`() {
        val r = PickerRegion(108, 240, 972, 2160, 1080, 2400)
        assertEquals(10.0, r.leftPct, 1e-9)
        assertEquals(10.0, r.topPct, 1e-9)
        assertEquals(90.0, r.rightPct, 1e-9)
        assertEquals(90.0, r.bottomPct, 1e-9)
    }

    @Test
    fun `expr 非空时 toSpec 直接透传`() {
        val r = PickerRegion(0, 0, 100, 100, 1000, 1000, "topHalf")
        assertEquals("topHalf", r.toSpec())
    }

    @Test
    fun `expr 为空时 toSpec 生成四元组且 Locale 安全`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val r = PickerRegion(512, 1000, 800, 1500, 1080, 2400)
            val spec = r.toSpec()
            val parts = spec.split(",")
            assertEquals(4, parts.size)
            parts.forEach { assertTrue("每段须能解析为 Double: $it", it.toDoubleOrNull() != null) }
            assertFalse("表达式不能含多余逗号", spec.count { it == ',' } != 3)
            assertFalse("小数点不能是逗号", parts[0].contains(","))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `零尺寸屏幕不产生NaN`() {
        val r = PickerRegion(0, 0, 1, 1, 0, 0)
        assertEquals(0.0, r.leftPct, 1e-12)
        assertEquals(0.0, r.rightPct, 1e-12)
    }
}

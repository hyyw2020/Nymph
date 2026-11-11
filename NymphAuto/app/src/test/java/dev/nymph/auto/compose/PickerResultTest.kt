// SPDX-License-Identifier: MIT
package dev.nymph.auto.compose

import dev.nymph.auto.ui.PickerResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 坐标拾取百分比换算精度（v2.6.0）。「超高精度」的落点是：像素坐标原样保留，
 * 百分比用 Double 全精度换算、显示 4 位小数，绝不提前取整或降级成 Int。
 */
class PickerResultTest {

    @Test
    fun `典型非整除分辨率百分比为全精度`() {
        // 1080x2400 下的 512px：51 200 / 1080 = 47.407407…%
        val r = PickerResult(512, 1000, 1080, 2400)
        assertEquals(47.407407407407405, r.xPct, 1e-9)
        assertEquals(41.666666666666664, r.yPct, 1e-9)
    }

    @Test
    fun `屏幕中心精确为50`() {
        val r = PickerResult(540, 1200, 1080, 2400)
        assertEquals(50.0, r.xPct, 1e-12)
        assertEquals(50.0, r.yPct, 1e-12)
    }

    @Test
    fun `零尺寸不崩溃且不产生NaN`() {
        val r = PickerResult(10, 20, 0, 0)
        assertEquals(0.0, r.xPct, 1e-12)
        assertEquals(0.0, r.yPct, 1e-12)
    }

    @Test
    fun `四位小数格式化与像素整数一致`() {
        val r = PickerResult(512, 1000, 1080, 2400)
        assertEquals("47.4074", "%.4f".format(r.xPct))
        assertEquals("41.6667", "%.4f".format(r.yPct))
        assertEquals(512, r.xPx)
        assertEquals(1000, r.yPx)
    }
}

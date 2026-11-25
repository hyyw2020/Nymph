// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.capture.FeatureCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.1 / ADR-069：图片特征截取的纯 JVM 几何回归。
 * 覆盖竞品交互「框选→原画质裁剪→相似度/限制区域」中平台无关的部分。
 */
class V300FeatureCaptureTest {

    @Test
    fun cropRect_mapsPickerBoxToFrameByPercentage() {
        // Picker 记录屏 1000×2000，框 (200,400)-(400,800)；截图帧 500×1000（密度减半）
        val r = FeatureCapture.cropRect(
            leftPx = 200, topPx = 400, rightPx = 400, bottomPx = 800,
            pickerW = 1000, pickerH = 2000, frameW = 500, frameH = 1000,
        )
        assertNotNull(r)
        assertEquals(100, r!!.left)
        assertEquals(200, r.top)
        assertEquals(200, r.right)
        assertEquals(400, r.bottom)
        assertEquals(100, r.width)
        assertEquals(200, r.height)
    }

    @Test
    fun cropRect_clampsOutOfRangeBox() {
        val r = FeatureCapture.cropRect(
            leftPx = -50, topPx = -50, rightPx = 2000, bottomPx = 4000,
            pickerW = 1000, pickerH = 2000, frameW = 500, frameH = 1000,
        )
        assertNotNull(r)
        assertEquals(0, r!!.left)
        assertEquals(0, r.top)
        assertEquals(500, r.right)
        assertEquals(1000, r.bottom)
    }

    @Test
    fun cropRect_rejectsDegenerateInput() {
        assertFalse(FeatureCapture.isUsable(null))
        // 零尺寸框（右==左）→ coerce 后仍保证 right=left+1，但边长 1 < MIN → 不可用
        val r = FeatureCapture.cropRect(10, 10, 10, 10, 100, 100, 100, 100)
        assertNotNull(r)
        assertFalse(FeatureCapture.isUsable(r))
        // 屏宽为 0 → 直接 null
        assertFalse(FeatureCapture.isUsable(
            FeatureCapture.cropRect(0, 0, 50, 50, 0, 100, 100, 100)
        ))
    }

    @Test
    fun clampSimilarity_and_isUsable() {
        assertEquals(FeatureCapture.MIN_SIMILARITY, FeatureCapture.clampSimilarity(0))
        assertEquals(FeatureCapture.MAX_SIMILARITY, FeatureCapture.clampSimilarity(150))
        assertEquals(90, FeatureCapture.clampSimilarity(90))
        val good = FeatureCapture.CropRect(10, 10, 50, 50) // 40×40
        assertTrue(FeatureCapture.isUsable(good))
        val tiny = FeatureCapture.CropRect(10, 10, 13, 13) // 3×3
        assertFalse(FeatureCapture.isUsable(tiny))
    }

    @Test
    fun toRegion_matchesCropRectInPercent() {
        val rect = FeatureCapture.CropRect(50, 100, 150, 300) // 帧 1000×1000
        val region = FeatureCapture.toRegion(rect, 1000, 1000)
        assertNotNull(region)
        assertEquals(5.0, region!!.leftPct, 1e-9)
        assertEquals(10.0, region.topPct, 1e-9)
        assertEquals(15.0, region.rightPct, 1e-9)
        assertEquals(30.0, region.bottomPct, 1e-9)
    }
}

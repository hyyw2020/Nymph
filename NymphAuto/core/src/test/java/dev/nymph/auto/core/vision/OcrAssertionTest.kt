// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 安全根基的纯 JVM 契约测试：断言值与区域值在类型层面互不携带对方语义。
 * 用反射核验"字段集合"，确保后续维护不会把坐标偷渡回断言、把文字偷渡回区域。
 */
class OcrAssertionTest {

    private fun fieldNames(clazz: Class<*>): Set<String> = clazz.declaredFields.map { it.name }.toSet()

    @Test
    fun `文字断言不携带任何坐标字段`() {
        val fields = fieldNames(OcrTextAssertion::class.java)
        // 内部可能有 Companion / hasConfidence 合成字段，但不出现坐标语义名
        val coordinateNames = setOf("cx", "cy", "x", "y", "left", "top", "right", "bottom")
        assertTrue("断言类型混入坐标字段：$fields", fields.none { it.lowercase() in coordinateNames })
    }

    @Test
    fun `区域类型不携带任何文字字段`() {
        val fields = fieldNames(OcrRegion::class.java)
        val textNames = setOf("text", "label", "content", "value")
        assertTrue("区域类型混入文字字段：$fields", fields.none { it.lowercase() in textNames })
    }

    @Test
    fun `从文本框投影断言时坐标被显式丢弃`() {
        val a = OcrTextAssertion.from(TextBox("确认", 500, 700), "matcher_v5")
        assertEquals("确认", a.text)
        assertEquals("matcher_v5", a.matcherId)
        assertFalse(a.hasConfidence)
        assertEquals(OcrTextAssertion.UNAVAILABLE_CONFIDENCE, a.confidence, 0.0)
    }

    @Test
    fun `v2937_文本框置信度贯通到断言_不再被抹成哨兵`() {
        // v2.9.37 回归：v2.9.34 的 OcrTextAssertion.from 硬编码 -1.0，
        // 把 ORT CTC 解码算出的置信度抹掉；现必须透传 box.confidence。
        val box = TextBox("确认", 500, 700, 0.87)
        val a = OcrTextAssertion.from(box, "ort_v5")
        assertEquals("置信度必须从 TextBox 透传到断言", 0.87, a.confidence, 1e-9)
        assertTrue(a.hasConfidence)
        assertEquals("ort_v5", a.matcherId)
    }

    @Test
    fun `v2937_MLKit无置信度仍为零回归哨兵`() {
        // 不传 confidence 的构造点（ML Kit bundled 通道）必须仍为 -1.0，不伪造数值。
        val box = TextBox("确认", 10, 20)
        assertEquals(TextBox.UNAVAILABLE_CONFIDENCE, box.confidence, 0.0)
        val a = OcrTextAssertion.from(box, "mlkit_bundled")
        assertFalse(a.hasConfidence)
        assertEquals(OcrTextAssertion.UNAVAILABLE_CONFIDENCE, a.confidence, 0.0)
    }

    @Test
    fun `置信度可用范围与哨兵判定`() {
        assertTrue(OcrTextAssertion("x", 0.93, "m").hasConfidence)
        assertTrue(OcrTextAssertion("x", 0.0, "m").hasConfidence)
        assertTrue(OcrTextAssertion("x", 1.0, "m").hasConfidence)
        assertFalse(OcrTextAssertion("x", -1.0, "m").hasConfidence)
        assertFalse(OcrTextAssertion("x", 1.2, "m").hasConfidence)
    }

    @Test
    fun `区域中心点与来源语义`() {
        val r = OcrRegion.of(100, 200, 300, 260, OcrRegion.SOURCE_VISION)
        assertEquals(200, r.centerX)
        assertEquals(230, r.centerY)
        assertEquals(200, r.width)
        assertEquals(60, r.height)
        assertEquals("vision_region", r.source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法矩形被拒绝`() {
        OcrRegion.of(300, 200, 100, 260)
    }

    @Test
    fun `断言可用于存在性判定而不暴露位置`() {
        val assertion = OcrTextAssertion("确认", 0.88, "m")
        // 条件判定只需要文本与置信度；调用方无法从断言取到任何点击位置
        assertEquals("确认", assertion.text)
        assertTrue(assertion.confidence >= 0.8)
    }
}

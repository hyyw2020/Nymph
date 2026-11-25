// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PP-OCR ONNX 后处理的纯 JVM 单测（无需模型、无需真机）。 */
class OcrPostprocessTest {

    @Test
    fun `CTC解码处理blank与重复`() {
        // 约定：类别0=CTC blank，类别1→'A'，类别2→'B'（keys 不含 blank）
        val keys = listOf("A", "B")
        val classes = 3
        // 5 帧：A A blank B B  → "AB"
        val logits = FloatArray(5 * classes)
        fun set(t: Int, c: Int) { logits[t * classes + c] = 10f }
        set(0, 1); set(1, 1); set(2, 0); set(3, 2); set(4, 2)
        assertEquals("AB", OcrPostprocess.ctcGreedy(logits, 5, classes, keys))
    }

    @Test
    fun `CTC解码空白输入返回空串`() {
        assertEquals("", OcrPostprocess.ctcGreedy(FloatArray(0), 0, 3, listOf("#", "A")))
        assertEquals("", OcrPostprocess.ctcGreedy(FloatArray(9), 3, 3, emptyList()))
    }

    @Test
    fun `带置信度解码文本与旧签名一致`() {
        val keys = listOf("A", "B")
        val classes = 3
        val logits = FloatArray(5 * classes)
        fun set(t: Int, c: Int) { logits[t * classes + c] = 10f }
        set(0, 1); set(1, 1); set(2, 0); set(3, 2); set(4, 2)
        val scored = OcrPostprocess.ctcGreedyScored(logits, 5, classes, keys)
        assertEquals("AB", scored.text)
        assertEquals(OcrPostprocess.ctcGreedy(logits, 5, classes, keys), scored.text)
    }

    @Test
    fun `高置信 logit 的解置信度接近一`() {
        // 每帧采纳类别 logit=10，其余=0 → softmax≈1 → 平均≈1
        val keys = listOf("A")
        val classes = 2
        val logits = FloatArray(3 * classes)
        for (t in 0 until 3) logits[t * classes + 1] = 10f
        val conf = OcrPostprocess.ctcGreedyScored(logits, 3, classes, keys).confidence
        assertTrue("置信度应接近 1，实际 $conf", conf > 0.99 && conf <= 1.0)
    }

    @Test
    fun `低区分度 logit 的解置信度接近类别均分`() {
        // 3 类：blank(0) logit=0，A(1)/B(2) logit 相等且更高 → best=1，softmax(A)=0.5
        val keys = listOf("A", "B")
        val classes = 3
        val logits = FloatArray(3 * classes)
        for (t in 0 until 3) { logits[t * classes + 1] = 5f; logits[t * classes + 2] = 5f }
        val decoded = OcrPostprocess.ctcGreedyScored(logits, 3, classes, keys)
        assertEquals("A", decoded.text)
        assertTrue("置信度应接近 0.5，实际 ${decoded.confidence}", decoded.confidence in 0.49..0.51)
    }

    @Test
    fun `无采纳字符时置信度为零`() {
        val classes = 2
        val logits = FloatArray(3 * classes) // 全 0 → best 恒为 blank(0)
        val decoded = OcrPostprocess.ctcGreedyScored(logits, 3, classes, listOf("A"))
        assertEquals("", decoded.text)
        assertEquals(0.0, decoded.confidence, 0.0)
    }

    @Test
    fun `字典加载去掉回车与末尾空行`() {
        val keys = OcrPostprocess.loadKeys("中\r\n文\r\nA\n")
        assertEquals(listOf("中", "文", "A"), keys)
    }

    @Test
    fun `概率图提取横排文本框`() {
        val w = 40; val h = 20
        val p = FloatArray(w * h)
        // 在第 5..10 行、第 4..20 列画一个实心矩形
        for (y in 5..10) for (x in 4..20) p[y * w + x] = 1f
        val boxes = OcrPostprocess.boxesFromProbabilityMap(p, w, h)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertTrue("left 应≈4", b.left in 3..5)
        assertTrue("right 应≈20", b.right in 19..22)
        assertTrue("top 应≈5", b.top in 4..6)
        assertTrue("bottom 应≈10", b.bottom in 9..11)
    }

    @Test
    fun `噪点被最小面积过滤`() {
        val w = 20; val h = 20
        val p = FloatArray(w * h)
        p[5 * w + 5] = 1f // 单个像素
        assertTrue(OcrPostprocess.boxesFromProbabilityMap(p, w, h).isEmpty())
    }

    @Test
    fun `两个分离的文本行产生两个框`() {
        val w = 30; val h = 24
        val p = FloatArray(w * h)
        for (y in 2..5) for (x in 2..25) p[y * w + x] = 1f
        for (y in 14..17) for (x in 2..25) p[y * w + x] = 1f
        val boxes = OcrPostprocess.boxesFromProbabilityMap(p, w, h)
        assertEquals(2, boxes.size)
        assertTrue(boxes[0].cy < boxes[1].cy)
    }

    @Test
    fun `多维数组展平保留形状与顺序`() {
        val v = arrayOf(arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)))
        val (data, shape) = OcrPostprocess.flatten(v)
        assertEquals(3, shape.size)
        assertEquals(1, shape[0]); assertEquals(2, shape[1]); assertEquals(2, shape[2])
        assertEquals(listOf(1f, 2f, 3f, 4f), data.toList())
    }

    @Test
    fun `展平一维FloatArray`() {
        val (data, shape) = OcrPostprocess.flatten(floatArrayOf(7f, 8f))
        assertEquals(1, shape.size)
        assertEquals(2, shape[0])
        assertEquals(listOf(7f, 8f), data.toList())
    }

    @Test
    fun `坏输入不抛异常`() {
        assertTrue(OcrPostprocess.boxesFromProbabilityMap(FloatArray(2), 100, 100).isEmpty())
        assertTrue(OcrPostprocess.boxesFromProbabilityMap(FloatArray(0), 0, 0).isEmpty())
        val (data, _) = OcrPostprocess.flatten(null)
        assertTrue(data.isEmpty())
    }
}

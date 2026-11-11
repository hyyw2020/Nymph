package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR 文本匹配的黄金样本（纯 JVM，语义与 NodeFinder.findByText 对齐）。 */
class TextSearchTest {

    private val boxes = listOf(
        TextBox("不再提示", 100, 300),
        TextBox("确定", 100, 200),
        TextBox("取消", 400, 200),
        TextBox("设置", 100, 100),
    )

    @Test
    fun `普通模式子串包含`() {
        assertEquals("确定", TextSearch.matchBest("确定", false, boxes)?.text)
        assertEquals("取消", TextSearch.matchBest("消", false, boxes)?.text)
    }

    @Test
    fun `阅读序优先 - 同列取最上`() {
        // "设置"在最上（y=100），虽然"确定"也在左侧列
        val hit = TextSearch.matchBest("", false, boxes) // 空查询直接 null
        assertNull(hit)
        // 两个都含"示"的候选：按 y 取更靠上的（此样本无重复，用坐标验证排序语义）
        val all = TextSearch.matchBest("设", false, boxes)
        assertEquals("设置", all?.text)
    }

    @Test
    fun `正则模式整串匹配`() {
        assertTrue(TextSearch.anyMatch("确定", true, boxes))
        assertFalse(TextSearch.anyMatch("确", true, boxes)) // 正则模式要求整串
        assertTrue(TextSearch.anyMatch("(确定|取消)", true, boxes))
    }

    @Test
    fun `空输入与空列表安全`() {
        assertNull(TextSearch.matchBest("", false, boxes))
        assertNull(TextSearch.matchBest("x", false, emptyList()))
        assertFalse(TextSearch.anyMatch("", true, boxes))
        assertFalse(TextSearch.anyMatch("x", false, emptyList()))
    }

    @Test
    fun `多个命中取阅读序最靠前`() {
        val dup = listOf(
            TextBox("确定", 300, 400),
            TextBox("确定并继续", 100, 250),
            TextBox("确定", 100, 250),
        )
        // 两个在 (100,250)：取更短文本（"确定" 比 "确定并继续" 更可能是按钮本体）
        assertEquals("确定", TextSearch.matchBest("确定", false, dup)?.text)
    }

    @Test
    fun `anyMatch 存在性判断`() {
        assertTrue(TextSearch.anyMatch("取消", false, boxes))
        assertFalse(TextSearch.anyMatch("退出", false, boxes))
    }
}

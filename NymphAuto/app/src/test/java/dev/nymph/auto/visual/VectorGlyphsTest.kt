// SPDX-License-Identifier: MIT
package dev.nymph.auto.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 矢量字形（纯 JVM 逻辑）：覆盖全部字符、越界安全、空输入安全、排版宽度。 */
class VectorGlyphsTest {

    @Test
    fun `数字斜杠乘号全部有笔画且坐标有限`() {
        val required = "0123456789/×xX"
        for (ch in required) {
            assertTrue("字符 $ch 应存在", VectorGlyphs.has(ch))
            val strokes = VectorGlyphs.strokes(ch)
            if (ch == ' ') continue
            assertTrue("字符 $ch 应有笔画", strokes.isNotEmpty())
            for (s in strokes) {
                assertTrue("笔画至少两点", s.size >= 4 && s.size % 2 == 0)
                s.forEach { assertTrue("坐标应有限", it.isFinite()) }
            }
        }
    }

    @Test
    fun `未知字符返回空笔画不抛异常`() {
        for (ch in listOf('中', 'A', '?', '%', '步', '\n')) {
            assertFalse("$ch 不应有字形", VectorGlyphs.has(ch))
            assertEquals(0, VectorGlyphs.strokes(ch).size)
        }
    }

    @Test
    fun `空输入与未知文本宽度安全`() {
        assertEquals(0f, VectorGlyphs.textWidth(""), 0f)
        assertTrue(VectorGlyphs.textWidth("3/12") > 0f)
        assertTrue(VectorGlyphs.textWidth("中文") > 0f) // 未知字符按默认宽度稳定计
    }

    @Test
    fun `前进宽度均为正且有限`() {
        for (ch in "0123456789/× ") {
            val a = VectorGlyphs.advance(ch)
            assertTrue("$ch 宽度应为正", a > 0f && a.isFinite())
        }
    }

    @Test
    fun `数字8笔画数最多数字1最少`() {
        assertTrue(VectorGlyphs.strokes('8').size > VectorGlyphs.strokes('1').size)
    }
}

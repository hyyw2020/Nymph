// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自家浮窗区域抑制（纯 JVM）：只改指定矩形，不动外部像素。 */
class ImageMaskTest {

    /** 每个像素编码成 (x*16 + y)，便于精确断言区域内容。 */
    private fun gradient(w: Int, h: Int) = ImageFinder.Image(
        w, h, IntArray(w * h) { i -> (i % w) * 16 + (i / w) },
    )

    @Test
    fun `空列表与非法图像安全返回0`() {
        val img = gradient(20, 20)
        assertEquals(0, ImageMask.suppress(img, emptyList()))
        assertEquals(0, ImageMask.suppress(ImageFinder.Image(0, 0, IntArray(0)), listOf(
            ImageMask.RectPct(0.0, 0.0, 100.0, 100.0),
        )))
    }

    @Test
    fun `矩形内被背景行覆盖矩形外不变`() {
        val img = gradient(20, 20)
        val before = img.argb.copyOf()
        // x=5..9, y=4..7（百分比换算：20px 宽高）
        val n = ImageMask.suppress(img, listOf(ImageMask.RectPct(25.0, 20.0, 50.0, 40.0)))
        assertEquals(1, n)
        for (y in 4 until 8) {
            for (x in 5 until 10) {
                // 上方一行 y=3 的像素 (x*16+3) 被复制下来
                assertEquals("y=$y x=$x", (x * 16 + 3), img.argb[y * 20 + x])
            }
        }
        // 矩形外必须逐像素不变
        for (y in 0 until 20) for (x in 0 until 20) {
            if (x in 5 until 10 && y in 4 until 8) continue
            assertEquals("外部被改动 y=$y x=$x", before[y * 20 + x], img.argb[y * 20 + x])
        }
    }

    @Test
    fun `贴顶矩形用下方行作背景`() {
        val img = gradient(20, 20)
        val n = ImageMask.suppress(img, listOf(ImageMask.RectPct(0.0, 0.0, 25.0, 15.0)))
        assertEquals(1, n)
        // y=0..2, x=0..4，下方一行 y=3
        for (y in 0 until 3) for (x in 0 until 5) {
            assertEquals((x * 16 + 3), img.argb[y * 20 + x])
        }
    }

    @Test
    fun `覆盖全高且贴顶的矩形取不到背景被跳过`() {
        val img = gradient(20, 20)
        val before = img.argb.copyOf()
        val n = ImageMask.suppress(img, listOf(ImageMask.RectPct(0.0, 0.0, 25.0, 100.0)))
        assertEquals(0, n)
        assertTrue(before.contentEquals(img.argb))
    }

    @Test
    fun `越界矩形被裁剪不抛异常`() {
        val img = gradient(20, 20)
        val n = ImageMask.suppress(img, listOf(
            ImageMask.RectPct(-50.0, -50.0, 200.0, 30.0),
            ImageMask.RectPct(10.0, 10.0, 5.0, 5.0), // 反向，宽度被钳到 1
        ))
        assertTrue("应至少处理一个", n >= 1)
    }
}

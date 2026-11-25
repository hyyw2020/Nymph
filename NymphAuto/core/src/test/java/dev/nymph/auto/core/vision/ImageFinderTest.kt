package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** NCC 匹配器行为（纯 JVM，合成图）。 */
class ImageFinderTest {

    private fun solid(w: Int, h: Int, argb: Int) =
        ImageFinder.Image(w, h, IntArray(w * h) { argb })

    /** 确定性伪随机噪点图（固定 seed，跨平台稳定）。 */
    private fun noise(w: Int, h: Int, seed: Int): ImageFinder.Image {
        val rnd = Random(seed)
        return ImageFinder.Image(w, h, IntArray(w * h) {
            val v = rnd.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        })
    }

    /** 从屏幕截取子图作为模板。 */
    private fun crop(src: ImageFinder.Image, x: Int, y: Int, w: Int, h: Int): ImageFinder.Image {
        val out = IntArray(w * h)
        for (j in 0 until h) for (i in 0 until w) out[j * w + i] = src.argb[(y + j) * src.width + (x + i)]
        return ImageFinder.Image(w, h, out)
    }

    @Test
    fun `相同图命中且分数接近 1`() {
        val screen = noise(300, 300, seed = 42)
        val tpl = crop(screen, 100, 80, 40, 40)
        val hit = ImageFinder.find(screen, tpl, 0.0, 0.0, 100.0, 100.0, 80, 0.0, 1.0)
        assertNotNull(hit)
        // 中心应落在 (100+20, 80+20) 附近（降采样允许少量误差）
        assertTrue("cx=${hit!!.cx}", kotlin.math.abs(hit.cx - 120) <= 6)
        assertTrue("cy=${hit.cy}", kotlin.math.abs(hit.cy - 100) <= 6)
        assertTrue("score=${hit.score}", hit.score > 0.98)
    }

    @Test
    fun `不同图不命中`() {
        val screen = noise(300, 300, seed = 1)
        val other = noise(40, 40, seed = 2)
        assertNull(ImageFinder.find(screen, other, 0.0, 0.0, 100.0, 100.0, 80, 0.0, 1.0))
    }

    @Test
    fun `bestScore 相同图接近 1 不同图显著低`() {
        val screen = noise(300, 300, seed = 7)
        val same = crop(screen, 60, 60, 32, 32)
        val diff = noise(32, 32, seed = 8)
        val hi = ImageFinder.bestScore(screen, same, 0.0, 0.0, 100.0, 100.0, 0.0, 1.0)
        val lo = ImageFinder.bestScore(screen, diff, 0.0, 0.0, 100.0, 100.0, 0.0, 1.0)
        assertTrue("hi=$hi", hi > 0.98)
        assertTrue("lo=$lo", lo < 0.5)
    }

    @Test
    fun `纯色图方差为零不命中不崩溃`() {
        val screen = solid(100, 100, 0xFF808080.toInt())
        val tpl = solid(10, 10, 0xFF808080.toInt())
        assertNull(ImageFinder.find(screen, tpl, 0.0, 0.0, 100.0, 100.0, 80, 0.0, 1.0))
        val score = ImageFinder.bestScore(screen, tpl, 0.0, 0.0, 100.0, 100.0, 0.0, 1.0)
        assertTrue("score=$score", score <= 0.0)
    }

    @Test
    fun `区域外目标不命中`() {
        val screen = noise(300, 300, seed = 11)
        val tpl = crop(screen, 250, 250, 30, 30) // 右下角
        // 只搜左上角 1/4 区域 → 不应命中
        assertNull(ImageFinder.find(screen, tpl, 0.0, 0.0, 20.0, 20.0, 80, 0.0, 1.0))
        // 全屏 → 命中
        assertNotNull(ImageFinder.find(screen, tpl, 0.0, 0.0, 100.0, 100.0, 80, 0.0, 1.0))
    }

    @Test
    fun `小模板降采样上限收紧后仍可命中`() {
        // 大屏幕 + 小模板（模拟 2652x1200 屏上的 40px 按钮）
        val screen = noise(1200, 500, seed = 21)
        val tpl = crop(screen, 700, 300, 40, 40)
        val hit = ImageFinder.find(screen, tpl, 0.0, 0.0, 100.0, 100.0, 80, 0.0, 1.0)
        assertNotNull(hit)
        assertTrue(hit!!.score > 0.95)
    }

    @Test
    fun `dpi 比例尺度生效`() {
        val screen = noise(600, 600, seed = 31)
        val tpl = crop(screen, 200, 200, 50, 50)
        // 模板制作端 dpi 2.0、运行端 3.0 → 模板应放大 1.5 倍仍命中
        val hit = ImageFinder.find(screen, tpl, 0.0, 0.0, 100.0, 100.0, 70, 2.0, 3.0)
        assertNotNull(hit)
    }

    @Test
    fun `脚本参考分辨率比例作为尺度基准可命中`() {
        // 模拟：脚本在 400 宽设备录制模板，当前屏幕 600 宽（ratio=1.5）。
        // v2.3.0 修复前 refDpi 恒传 0.0，ratio 永远 1.0，这类模板全灭。
        val screen = noise(600, 600, seed = 77)
        val tpl = crop(screen, 200, 200, 40, 40) // 40px 模板
        // 放大 1.5 倍后的画面（近似：把 40px 模板贴成 60px）
        val big = noise(600, 600, seed = 78)
        for (j in 0 until 60) for (i in 0 until 60) {
            val sx = i * 40 / 60; val sy = j * 40 / 60
            big.argb[(200 + j) * 600 + (200 + i)] = tpl.argb[sy * 40 + sx]
        }
        val hit = ImageFinder.find(big, tpl, 0.0, 0.0, 100.0, 100.0, 70, 400.0, 600.0)
        assertNotNull("按 1.5 尺度应命中", hit)
    }

    // ===== v2.9.26：未命中的最佳窗口位置可观测（现场归因依据） =====

    @Test
    fun `未命中时最佳窗口位置落在搜索窗内`() {
        val screen = noise(300, 300, seed = 41)
        val tpl = noise(40, 40, seed = 42)
        val r = ImageFinder.findDetailed(screen, tpl, 25.0, 40.0, 75.0, 80.0, 82, 0.0, 1.0)
        assertNull(r.hit)
        assertTrue("score=${r.bestScore}", r.bestScore >= 0.0)
        // 搜索窗像素范围：x 75~225，y 120~240；最佳窗口中心必须落在其中
        assertTrue("bestCx=${r.bestCx}", r.bestCx in 75..225)
        assertTrue("bestCy=${r.bestCy}", r.bestCy in 120..240)
    }

    @Test
    fun `元素在场但外观失真时未命中且最佳窗口仍在原位`() {
        val screen = noise(400, 300, seed = 51)
        val tpl = crop(screen, 200, 120, 40, 40)
        // 复刻现场：元素出现了，但渲染内容与拾取快照不同（一半像素被换掉）
        val distorted = screen.copy(argb = screen.argb.copyOf())
        val rnd = Random(52)
        for (j in 0 until 40) for (i in 0 until 40) {
            val idx = (120 + j) * screen.width + (200 + i)
            val orig = screen.argb[idx]
            val nv = rnd.nextInt(256)
            val mixed = ((orig and 0xFF) + nv) / 2
            distorted.argb[idx] = (0xFF shl 24) or (mixed shl 16) or (mixed shl 8) or mixed
        }
        val r = ImageFinder.findDetailed(distorted, tpl, 0.0, 0.0, 100.0, 100.0, 82, 0.0, 1.0)
        // 半失真不足以过 0.82 阈值 → fail-closed 不命中；但最佳窗口必须仍指向元素原位
        assertNull("score=${r.bestScore}", r.hit)
        assertTrue("score=${r.bestScore}", r.bestScore < 0.82)
        assertTrue("bestCx=${r.bestCx}", kotlin.math.abs(r.bestCx - 220) <= 10)
        assertTrue("bestCy=${r.bestCy}", kotlin.math.abs(r.bestCy - 140) <= 10)
    }

    @Test
    fun `无可评窗口时最佳位置为 -1`() {
        val screen = solid(100, 100, 0xFF808080.toInt())
        val tpl = solid(10, 10, 0xFF808080.toInt())
        val r = ImageFinder.findDetailed(screen, tpl, 0.0, 0.0, 100.0, 100.0, 82, 0.0, 1.0)
        assertNull(r.hit)
        assertEquals(-1, r.bestCx)
        assertEquals(-1, r.bestCy)
    }
}

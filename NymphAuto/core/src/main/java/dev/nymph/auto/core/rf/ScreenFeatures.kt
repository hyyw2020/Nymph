package dev.nymph.auto.core.rf

import dev.nymph.auto.core.vision.ImageFinder

/**
 * 屏幕特征提取（HOG-lite）——随机森林的输入向量。
 *
 * 流程（教科书 HOG 的轻量版，与 sklearn 常见用法对齐）：
 *  1. 灰度化（Rec.601 亮度）
 *  2. 全图 Sobel 梯度（幅值 + 无向方向 0~180°）
 *  3. 按 cells×cells 网格池化，每 cell 出 bins 维方向直方图（幅值加权）
 *  4. L2 归一化整条向量
 *
 * 输出定长 cells*cells*bins（默认 8×8×8=512）——与 .dot 模型里的
 * X[i] 索引约定一一对应（i = cellY*cells*bins + cellX*bins + bin）。
 *
 * 纯 JVM 无依赖：可被单元测试全覆盖；真机上由 app 层把无障碍截图
 * 转成 [ImageFinder.Image] 后调用。
 */
object ScreenFeatures {

    const val DEFAULT_CELLS = 8
    const val DEFAULT_BINS = 8

    /** 提取特征向量（失败/空图返回零向量，长度仍为定长）。 */
    fun extract(
        image: ImageFinder.Image,
        cells: Int = DEFAULT_CELLS,
        bins: Int = DEFAULT_BINS,
    ): DoubleArray {
        val out = DoubleArray(cells * cells * bins)
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return out
        val px = image.argb
        // 灰度 + 梯度：Sobel 3x3
        val gray = DoubleArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        }
        val binWidth = 180.0 / bins
        val cw = w.toDouble() / cells
        val ch = h.toDouble() / cells
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = sobelAt(gray, w, h, x, y, dx = true)
                val gy = sobelAt(gray, w, h, x, y, dx = false)
                val mag = kotlin.math.sqrt(gx * gx + gy * gy)
                if (mag < 1e-6) continue
                // 无向方向：0~180°
                var ang = Math.toDegrees(kotlin.math.atan2(gy, gx))
                if (ang < 0) ang += 180.0
                if (ang >= 180.0) ang = 179.999
                val bin = (ang / binWidth).toInt().coerceIn(0, bins - 1)
                val cx = ((x / cw).toInt()).coerceIn(0, cells - 1)
                val cy = ((y / ch).toInt()).coerceIn(0, cells - 1)
                out[cy * cells * bins + cx * bins + bin] += mag
            }
        }
        // L2 归一化：消除整体亮度/对比度差异
        var sum = 0.0
        for (v in out) sum += v * v
        if (sum > 1e-12) {
            val inv = 1.0 / kotlin.math.sqrt(sum)
            for (i in out.indices) out[i] *= inv
        }
        return out
    }

    private fun sobelAt(gray: DoubleArray, w: Int, h: Int, x: Int, y: Int, dx: Boolean): Double {
        fun at(xx: Int, yy: Int) = gray[yy * w + xx]
        return if (dx) {
            -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1) +
                at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)
        } else {
            -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) +
                at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
        }
    }
}

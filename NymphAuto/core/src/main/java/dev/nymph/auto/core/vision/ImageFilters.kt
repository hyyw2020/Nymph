package dev.nymph.auto.core.vision

import dev.nymph.auto.core.model.FilterKind
import dev.nymph.auto.core.model.ImageFilter

/**
 * 图像滤镜（对标自动精灵 8 种滤镜）——作用于 NCC 匹配前的预处理。
 *
 * 关键约定（与 [ImageFinder] 的一致性）：
 *  - 灰度权重 0.299/0.587/0.114，与 ImageFinder.grayOf 完全一致，
 *    保证"灰度滤镜 + 模板匹配"与匹配器内部灰度化结果同尺度
 *  - screen 与 template **双方**都应用同一组滤镜（调用方保证），
 *    单方应用会导致模板与屏幕特征空间不一致
 *  - 透明通道原样保留（ARGB 高位不动），只改 RGB
 *
 * 平台无关：纯 IntArray 运算，JVM 单测直接覆盖。
 */
object ImageFilters {

    /** 按顺序应用滤镜组；空组原样返回（零拷贝）。 */
    fun apply(image: ImageFinder.Image, filters: List<ImageFilter>): ImageFinder.Image {
        if (filters.isEmpty()) return image
        val w = image.width
        val h = image.height
        var px = image.argb.copyOf()
        for (f in filters) px = applyOne(px, w, h, f)
        return ImageFinder.Image(w, h, px)
    }

    private fun applyOne(src: IntArray, w: Int, h: Int, f: ImageFilter): IntArray {
        val out = IntArray(src.size)
        when (f.kind) {
            FilterKind.RGB -> {
                val dr = f.r; val dg = f.g; val db = f.b
                for (i in src.indices) {
                    val c = src[i]
                    out[i] = (c and 0xFF000000.toInt()) or
                        (clamp255(((c shr 16) and 0xFF) + dr) shl 16) or
                        (clamp255(((c shr 8) and 0xFF) + dg) shl 8) or
                        clamp255((c and 0xFF) + db)
                }
            }
            FilterKind.BRIGHTNESS -> {
                val d = f.brightness
                for (i in src.indices) {
                    val c = src[i]
                    out[i] = (c and 0xFF000000.toInt()) or
                        (clamp255(((c shr 16) and 0xFF) + d) shl 16) or
                        (clamp255(((c shr 8) and 0xFF) + d) shl 8) or
                        clamp255((c and 0xFF) + d)
                }
            }
            FilterKind.CONTRAST -> {
                // 以 128 为轴缩放：factor=(100+contrast)/100，contrast=0 恒等，
                // 100 → 2 倍反差，-100 → 全灰（factor 0）
                val factor = (100.0 + f.contrast.coerceIn(-100, 100)) / 100.0
                for (i in src.indices) {
                    val c = src[i]
                    out[i] = (c and 0xFF000000.toInt()) or
                        (clamp255(((((c shr 16) and 0xFF) - 128) * factor + 128).toInt()) shl 16) or
                        (clamp255(((((c shr 8) and 0xFF) - 128) * factor + 128).toInt()) shl 8) or
                        clamp255((((c and 0xFF) - 128) * factor + 128).toInt())
                }
            }
            FilterKind.GRAY -> {
                for (i in src.indices) {
                    val c = src[i]
                    val g = grayOf(c)
                    out[i] = (c and 0xFF000000.toInt()) or (g shl 16) or (g shl 8) or g
                }
            }
            FilterKind.BLACK_WHITE -> {
                val th = f.threshold.coerceIn(0, 255)
                for (i in src.indices) {
                    val c = src[i]
                    val v = if (grayOf(c) >= th) 255 else 0
                    out[i] = (c and 0xFF000000.toInt()) or (v shl 16) or (v shl 8) or v
                }
            }
            FilterKind.INVERT -> {
                for (i in src.indices) {
                    val c = src[i]
                    out[i] = (c and 0xFF000000.toInt()) or
                        ((255 - ((c shr 16) and 0xFF)) shl 16) or
                        ((255 - ((c shr 8) and 0xFF)) shl 8) or
                        (255 - (c and 0xFF))
                }
            }
            FilterKind.COLOR_REPLACE -> {
                // fromColor 精确匹配的像素替换为 toColor（RGB 全等才算；
                // 抗锯齿边缘像素不替换，避免把轮廓糊掉）
                val from = f.fromColor.toInt()
                val to = f.toColor.toInt()
                val fr = (from shr 16) and 0xFF; val fg = (from shr 8) and 0xFF; val fb = from and 0xFF
                val tr = (to shr 16) and 0xFF; val tg = (to shr 8) and 0xFF; val tb = to and 0xFF
                for (i in src.indices) {
                    val c = src[i]
                    out[i] = if (((c shr 16) and 0xFF) == fr && ((c shr 8) and 0xFF) == fg && (c and 0xFF) == fb) {
                        (c and 0xFF000000.toInt()) or (tr shl 16) or (tg shl 8) or tb
                    } else c
                }
            }
            FilterKind.CONTOUR -> sobel(src, out, w, h)
        }
        return out
    }

    /**
     * Sobel 梯度幅值 → 轮廓图：边缘亮、平坦区暗，alpha 保留。
     * 边界像素（无完整 3x3 邻域）幅值记 0。
     */
    private fun sobel(src: IntArray, out: IntArray, w: Int, h: Int) {
        if (w < 3 || h < 3) {
            src.copyInto(out)
            return
        }
        val gray = DoubleArray(src.size)
        for (i in src.indices) gray[i] = grayOf(src[i]).toDouble()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    out[i] = (src[i] and 0xFF000000.toInt())
                    continue
                }
                val tl = gray[i - w - 1]; val t = gray[i - w]; val tr = gray[i - w + 1]
                val l = gray[i - 1]; val r = gray[i + 1]
                val bl = gray[i + w - 1]; val b = gray[i + w]; val br = gray[i + w + 1]
                val gx = (tl + 2 * l + bl) - (tr + 2 * r + br)
                val gy = (tl + 2 * t + tr) - (bl + 2 * b + br)
                val mag = kotlin.math.sqrt(gx * gx + gy * gy).toInt().coerceIn(0, 255)
                out[i] = (src[i] and 0xFF000000.toInt()) or (mag shl 16) or (mag shl 8) or mag
            }
        }
    }

    /** 灰度（与 ImageFinder.grayOf 同权重）。 */
    private fun grayOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }

    private fun clamp255(v: Int): Int = v.coerceIn(0, 255)
}

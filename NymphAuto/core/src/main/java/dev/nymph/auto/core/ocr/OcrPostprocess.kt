// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.ocr

/**
 * PP-OCR 系列 ONNX 模型的纯 JVM 后处理（det 概率图 → 文本框、CTC → 文字）。
 *
 * 与具体推理引擎解耦：app 层 ONNX Runtime 只负责把模型输出解码成
 * `FloatArray + 形状`，本对象负责其余全部逻辑，因此可在 JVM 单测里
 * 完整验证，无需真机、无需模型文件。
 *
 * 模型格式契约（用户把 PP-OCRv5 ONNX 放进 filesDir/ocr/ 即启用）：
 *  - det.onnx：输入 [1,3,H,W]，输出 [1,1,h,w] 的二值概率图
 *  - rec.onnx：输入 [1,3,48,W]，输出 [1,T,C] 的字符 logits
 *  - keys.txt：每行一个字符，首行通常为 "#" 或空白（blank）
 */
object OcrPostprocess {

    /** 轴对齐文本框（概率图坐标系）。 */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
    }

    /**
     * 加载字典：**不含 blank**。约定 logits 的类别 0 恒为 CTC blank，
     * 类别 c(≥1) 对应 `keys[c-1]`（PP-OCR 官方 ppocr_keys_v1.txt 即此约定）。
     * 行尾回车去掉；末尾空行丢弃。
     */
    fun loadKeys(raw: String): List<String> =
        raw.split('\n').map { it.trimEnd('\r') }.dropLastWhile { it.isEmpty() }

    /**
     * CTC greedy 解码：每帧取最大 logit 的类别，连续重复合并，丢弃 blank（类别 0）。
     *
     * @param logits 展平的 [T, C] 或 [1, T, C] 数据
     * @param steps 时间步 T
     * @param classes 类别数 C（= keys.size + 1）
     */
    fun ctcGreedy(logits: FloatArray, steps: Int, classes: Int, keys: List<String>): String {
        if (steps <= 0 || classes <= 0 || keys.isEmpty()) return ""
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until steps) {
            val off = t * classes
            if (off + classes > logits.size) break
            var best = 0
            for (c in 1 until classes) {
                if (logits[off + c] > logits[off + best]) best = c
            }
            if (best == 0) { prev = -1; continue }          // CTC blank
            if (best == prev) continue                        // 连续重复合并
            val key = keys.getOrNull(best - 1).orEmpty()       // 类别0=blank，故偏移1
            if (key.isNotEmpty() && key != " ") sb.append(key)
            prev = best
        }
        return sb.toString()
    }

    /**
     * 概率图 → 文本框（行列投影 + 连通段合并，足够覆盖屏幕文字的横排布局）。
     *
     * 相比完整 DB 后处理（需轮廓/多边形/透视），这里用更轻的投影法：
     *  - 逐行统计超过阈值的像素数，连续达标行合成一条「文本行带」
     *  - 在每个行带内逐列统计，连续达标列合成一个「文本框」
     * 对横排中文/英文按钮文字足够，且纯 JVM、O(h*w)、可单测。
     *
     * @param prob 展平的概率图（长度 = w*h）
     * @param minArea 过滤噪点的最小框面积
     */
    fun boxesFromProbabilityMap(
        prob: FloatArray,
        w: Int,
        h: Int,
        threshold: Float = 0.3f,
        minArea: Int = 24,
        minHeight: Int = 4,
    ): List<Box> {
        if (w <= 0 || h <= 0 || prob.size < w * h) return emptyList()
        val rowHit = IntArray(h)
        for (y in 0 until h) {
            var c = 0
            val base = y * w
            for (x in 0 until w) if (prob[base + x] >= threshold) c++
            rowHit[y] = c
        }
        // 行带：连续存在有效像素的行（允许 1 行断裂以容忍笔画间隙）
        val bands = mutableListOf<IntRange>()
        var start = -1
        var gap = 0
        for (y in 0 until h) {
            if (rowHit[y] > 0) {
                if (start < 0) start = y
                gap = 0
            } else if (start >= 0) {
                if (++gap > 1) { bands += start..(y - gap); start = -1; gap = 0 }
            }
        }
        if (start >= 0) bands += start..(h - 1)

        val out = mutableListOf<Box>()
        for (band in bands) {
            if (band.last - band.first + 1 < minHeight) continue
            val colHit = IntArray(w)
            for (x in 0 until w) {
                var c = 0
                for (y in band) if (prob[y * w + x] >= threshold) c++
                colHit[x] = c
            }
            var segStart = -1
            var colGap = 0
            for (x in 0 until w) {
                if (colHit[x] > 0) {
                    if (segStart < 0) segStart = x
                    colGap = 0
                } else if (segStart >= 0) {
                    // 字符间允许 6 像素空隙，超过则切分
                    if (++colGap > 6) {
                        addBox(out, segStart, band.first, x - colGap, band.last, minArea)
                        segStart = -1; colGap = 0
                    }
                }
            }
            if (segStart >= 0) addBox(out, segStart, band.first, w - 1, band.last, minArea)
        }
        return mergeOverlapping(out)
    }

    private fun addBox(out: MutableList<Box>, l: Int, t: Int, r: Int, b: Int, minArea: Int) {
        if (r <= l || b <= t) return
        if ((r - l) * (b - t) < minArea) return
        out += Box(l, t, r, b)
    }

    /** 合并同一行内水平重叠/紧邻的框（一个词被切成多段时）。 */
    private fun mergeOverlapping(boxes: List<Box>): List<Box> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedWith(compareBy({ it.top }, { it.left }))
        val out = mutableListOf<Box>()
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val b = sorted[i]
            val sameLine = kotlin.math.abs(b.cy - cur.cy) <= maxOf(cur.height, b.height) / 2
            val touching = b.left - cur.right <= 8
            cur = if (sameLine && touching) {
                Box(
                    minOf(cur.left, b.left), minOf(cur.top, b.top),
                    maxOf(cur.right, b.right), maxOf(cur.bottom, b.bottom),
                )
            } else {
                out += cur
                b
            }
        }
        out += cur
        return out
    }

    /** 通用多维数组展平（ORT 输出可能是 float[][] / float[][][][] 等）。 */
    fun flatten(value: Any?): Pair<FloatArray, IntArray> {
        val shape = IntArray(8)
        val dims = IntArray(1)
        fun probe(v: Any?, depth: Int) {
            if (v == null || depth >= shape.size) return
            when (v) {
                is FloatArray -> { shape[depth] = v.size; dims[0] = maxOf(dims[0], depth + 1) }
                is Array<*> -> {
                    shape[depth] = v.size
                    dims[0] = maxOf(dims[0], depth + 1)
                    if (v.isNotEmpty()) probe(v[0], depth + 1)
                }
                else -> Unit
            }
        }
        probe(value, 0)
        val out = FloatArray(totalElements(shape, dims[0]))
        val cursor = IntArray(1)
        fun fill(v: Any?) {
            when (v) {
                is FloatArray -> { v.copyInto(out, cursor[0]); cursor[0] += v.size }
                is Array<*> -> v.forEach { fill(it) }
                else -> Unit
            }
        }
        fill(value)
        return out to shape.copyOf(dims[0])
    }

    private fun totalElements(shape: IntArray, dims: Int): Int {
        if (dims <= 0) return 0
        var n = 1
        for (i in 0 until dims) n *= shape[i].coerceAtLeast(1)
        return n
    }
}

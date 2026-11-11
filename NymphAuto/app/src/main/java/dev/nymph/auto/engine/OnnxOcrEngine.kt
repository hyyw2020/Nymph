// SPDX-License-Identifier: MIT
package dev.nymph.auto.engine

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.ocr.OcrPostprocess
import dev.nymph.auto.core.vision.TextBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * 基于 **ONNX Runtime Android 1.31.1**（2026-10 release，MIT）的
 * **完全离线**中文 OCR 引擎。
 *
 * ## 为什么替换/补位 ML Kit
 *
 * ML Kit 中文识别的 `text-recognition-chinese:16.0.1` 依赖 Google Play 服务
 * **按需下载模型**（GMS 侧网络）。本 APK 声明 `tools:node="remove"` 移除了
 * INTERNET / ACCESS_NETWORK_STATE 权限，且大量国产 ROM 无 GMS——该通道
 * 在这些设备上**永远不可用**。本引擎把模型文件放在本机，推理不联网、
 * 不依赖 GMS，是「完全离线」承诺下唯一可靠的 OCR 通道。
 *
 * ## 模型可插拔契约（Apache-2.0 的 PaddleOCR PP-OCRv5 导出 ONNX）
 *
 * 用户把以下文件放到 `filesDir/ocr/` 即自动启用；**不随 APK 内置大模型**，
 * 理由：① 中文 rec 模型 7.5–81MB，会让 30MB 的 APK 翻倍；② 遵循上游
 * Apache-2.0 分发条款时，显式携带模型与 NOTICE 更清晰；③ 与既有 RF `.dot`
 * 机制口径一致（机制对等、模型自备）。
 *
 * ```
 * filesDir/ocr/det.onnx   文字检测，输入 [1,3,H,W] 输出 [1,1,h,w] 概率图
 * filesDir/ocr/rec.onnx   文字识别，输入 [1,3,48,W] 输出 [1,T,C] logits
 * filesDir/ocr/keys.txt   字符字典（每行一个字符，不含 blank）
 * ```
 * 也可用 `assets/ocr/` 随包（若自行打包）；filesDir 优先。
 *
 * 缺任一文件时 [available]=false，[OcrEngineFactory] 自动回退到 ML Kit
 * 或节点文本通道，录制/回放不中断。
 *
 * ## 权限契约
 *
 * 输入位图来自无障碍 `takeScreenshot()`（非 MediaProjection）；
 * ORT AAR 自身声明的 ACCESS_NETWORK_STATE 已被 Manifest `tools:node="remove"`
 * 移除（aapt2 实测无该权限）。推理为纯本地 CPU，零网络调用。
 */
class OnnxOcrEngine(private val context: Context) : OcrEngine {

    private val dir = File(context.filesDir, "ocr")
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var keys: List<String> = emptyList()
    private var detInputName = ""
    private var recInputName = ""
    private var ready = false

    init {
        runCatching { initSessions() }
            .onFailure { RunLog.w("OcrOnnx", "初始化失败，回退其他通道：${it.message}") }
    }

    override val available: Boolean get() = ready

    private fun initSessions() {
        val fDet = resolve("det.onnx")
        val fRec = resolve("rec.onnx")
        val fKeys = resolve("keys.txt")
        if (fDet == null || fRec == null || fKeys == null) {
            RunLog.i("OcrOnnx", "未检测到可插拔 OCR 模型（filesDir/ocr/ 或 assets/ocr/），离线 OCR 通道关闭")
            return
        }
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            // 纯 CPU：不启用 NNAPI（Android 15+ 已弃用），追求结果稳定一致
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val d = e.createSession(fDet.readBytes(), opts)
        val r = e.createSession(fRec.readBytes(), opts)
        detInputName = d.inputNames.firstOrNull().orEmpty()
        recInputName = r.inputNames.firstOrNull().orEmpty()
        if (detInputName.isEmpty() || recInputName.isEmpty()) {
            runCatching { d.close() }; runCatching { r.close() }; runCatching { opts.close() }
            return
        }
        keys = OcrPostprocess.loadKeys(fKeys.readText(Charsets.UTF_8))
        env = e
        detSession = d
        recSession = r
        ready = keys.isNotEmpty()
        RunLog.i("OcrOnnx", "离线 OCR 就绪（ONNX Runtime 1.31.1，字典 ${keys.size} 字，纯 CPU）")
    }

    /** filesDir 优先；没有则读 assets/ocr/（自行随包时）。 */
    private fun resolve(name: String): File? {
        val local = File(dir, name)
        if (local.isFile && local.length() > 0) return local
        return runCatching {
            context.assets.open("ocr/$name").use { input ->
                val out = File(context.cacheDir, "ocr_$name")
                input.copyTo(out.outputStream())
                out
            }
        }.getOrNull()
    }

    override suspend fun recognize(bmp: Bitmap): List<TextBox> = withContext(Dispatchers.Default) {
        if (!ready || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return@withContext emptyList()
        val e = env ?: return@withContext emptyList()
        val det = detSession ?: return@withContext emptyList()
        val rec = recSession ?: return@withContext emptyList()
        runCatching { runPipeline(e, det, rec, bmp) }
            .onFailure { RunLog.w("OcrOnnx", "识别失败：${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun runPipeline(e: OrtEnvironment, det: OrtSession, rec: OrtSession, bmp: Bitmap): List<TextBox> {
        // ---- 检测：等比缩放至 640 长边，CHW 归一化 (x-0.5)/0.5 ----
        val scale = 640.0 / maxOf(bmp.width, bmp.height)
        val dw = (bmp.width * scale).toInt().coerceAtLeast(32)
        val dh = (bmp.height * scale).toInt().coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(bmp, dw, dh, true)
        val detInput = bitmapToChw(scaled)
        if (scaled !== bmp) scaled.recycle()

        val detTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(detInput), longArrayOf(1, 3, dh.toLong(), dw.toLong()))
        val (prob, shape) = try {
            val r = det.run(mapOf(detInputName to detTensor))
            try {
                OcrPostprocess.flatten(r.get(0).value)
            } finally {
                r.close()
            }
        } finally {
            detTensor.close()
        }
        val pw = shape.getOrNull(shape.lastIndex) ?: 0
        val ph = if (shape.size >= 2) shape[shape.lastIndex - 1] else 0
        if (pw <= 0 || ph <= 0) return emptyList()
        val boxes = OcrPostprocess.boxesFromProbabilityMap(prob, pw, ph)

        // ---- 识别：逐框裁剪 → 48 高 → CTC 解码 ----
        val out = mutableListOf<TextBox>()
        for (b in boxes.take(40)) {
            // 概率图坐标映射回原图
            val l = (b.left * dw / pw).coerceIn(0, bmp.width - 1)
            val t = (b.top * dh / ph).coerceIn(0, bmp.height - 1)
            val r = (b.right * dw / pw).coerceIn(l + 1, bmp.width)
            val bt = (b.bottom * dh / ph).coerceIn(t + 1, bmp.height)
            val text = recognizeLine(e, rec, bmp, l, t, r, bt)
            if (text.isNotBlank()) {
                out += TextBox(text, (l + r) / 2, (t + bt) / 2)
            }
        }
        return out
    }

    private fun recognizeLine(
        e: OrtEnvironment, rec: OrtSession,
        bmp: Bitmap, l: Int, t: Int, r: Int, b: Int,
    ): String {
        val cw = (r - l).coerceAtLeast(1)
        val ch = (b - t).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(bmp, l, t, cw, ch)
        val rw = (48.0 * cw / ch).toInt().coerceIn(32, 640)
        val resized = Bitmap.createScaledBitmap(crop, rw, 48, true)
        if (resized !== crop) crop.recycle()
        val input = bitmapToChw(resized)
        resized.recycle()
        val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(input), longArrayOf(1, 3, 48, rw.toLong()))
        val (logits, shape) = try {
            val r = rec.run(mapOf(recInputName to tensor))
            try {
                OcrPostprocess.flatten(r.get(0).value)
            } finally {
                r.close()
            }
        } finally {
            tensor.close()
        }
        val classes = shape.lastOrNull() ?: 0
        val steps = if (shape.size >= 2) shape[shape.size - 2] else 0
        if (classes <= 0 || steps <= 0) return ""
        return OcrPostprocess.ctcGreedy(logits, steps, classes, keys)
    }

    /** ARGB_8888 → CHW float，(v/255 - 0.5)/0.5。 */
    private fun bitmapToChw(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val c = px[i]
            out[i] = (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            out[plane + i] = (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            out[2 * plane + i] = ((c and 0xFF) / 255f - 0.5f) / 0.5f
        }
        return out
    }

    override fun close() {
        ready = false
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        recSession = null
        // OrtEnvironment 是进程级单例，不在此关闭（其他会话可能仍在用）
    }
}

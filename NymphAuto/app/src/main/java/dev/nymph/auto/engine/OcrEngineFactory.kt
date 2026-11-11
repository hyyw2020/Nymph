// SPDX-License-Identifier: MIT
package dev.nymph.auto.engine

import android.content.Context
import dev.nymph.auto.core.log.RunLog

/**
 * OCR 通道选择（按「完全离线」优先级）。
 *
 * 1. **ONNX Runtime 1.31.1 + 可插拔 PP-OCRv5 ONNX 模型**：模型在本机、
 *    纯 CPU、不联网、不依赖 GMS —— 最符合本工程「无 INTERNET 权限」的承诺。
 * 2. **ML Kit 中文**：仅当设备有 GMS 且模型已由 Play 服务缓存时可用；
 *    作为兼容兜底（无 GMS / 无网络时其 available 会回落）。
 * 3. **null**：两者皆不可用，VisionPipeline 回退到无障碍节点文本通道，
 *    录制依旧能产出节点/模板/坐标锚点，功能不中断。
 *
 * 全部通道的输入位图都来自无障碍 `takeScreenshot()`，零 MediaProjection。
 */
object OcrEngineFactory {

    @Volatile
    private var cached: OcrEngine? = null

    /** 每次脚本运行/录制开一轮新引擎；调用方负责 close()。 */
    fun create(context: Context): OcrEngine {
        val onnx = runCatching { OnnxOcrEngine(context.applicationContext) }.getOrNull()
        if (onnx != null && onnx.available) {
            RunLog.i("Ocr", "采用离线 ONNX Runtime OCR（模型本机、无 GMS 依赖）")
            return onnx
        }
        runCatching { onnx?.close() }

        val mlkit = runCatching { MlKitOcrEngine() }.getOrNull()
        if (mlkit != null && mlkit.available) {
            RunLog.i("Ocr", "采用 ML Kit 中文兜底（需 GMS 已缓存模型）")
            return mlkit
        }
        runCatching { mlkit?.close() }
        RunLog.i("Ocr", "无可用 OCR 通道，回退无障碍节点文本")
        return UnavailableOcr
    }

    /** 空实现：明确表达「不可用」而非抛异常，调用方无需判空。 */
    private object UnavailableOcr : OcrEngine {
        override val available: Boolean = false
        override suspend fun recognize(bmp: android.graphics.Bitmap) = emptyList<dev.nymph.auto.core.vision.TextBox>()
        override fun close() = Unit
    }
}

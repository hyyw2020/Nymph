package dev.nymph.auto.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.vision.TextBox
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * OCR 引擎契约（app 层实现，core 只认 [TextBox]）。
 *
 * 存在意义：无障碍节点文本（[NodeFinder]）拿不到"画在画布上的文字"——
 * 游戏/自绘界面/图片里的验证码。此时唯一通道是截图 + OCR。
 *
 * 实现约束（与全架构一致）：
 *  - 只用无障碍截图（无 MediaProjection，不与系统录屏争权限）
 *  - 模型不可用时优雅降级：available=false，调用方回退节点文本
 *  - 识别失败不抛异常炸脚本，返回空列表由上层判"未找到"
 */
interface OcrEngine {
    val available: Boolean

    /** 识别位图中的文字行（含中心点）。失败/不可用返回空列表。 */
    suspend fun recognize(bmp: Bitmap): List<TextBox>

    /** 释放识别器（随本次脚本运行结束关闭，防止跨轮累积）。 */
    fun close()
}

/**
 * ML Kit 中文识别实现。
 *
 * 入口（ML Kit 官方约定）：TextRecognition.getClient(ChineseTextRecognizerOptions)
 * ——拉丁件提供入口类，中文件负责切换到中文模型；模型（~2MB）由 Google Play
 * 服务按需下载管理。无 GMS 或模型未就绪时 [available] 为 false，调用方自动
 * 回退节点文本通道，不报错不阻塞。首次识别可能触发模型下载（数秒），
 * 调用方应给足超时（VisionPipeline 给 20s）。
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
    }

    override val available: Boolean
        get() = runCatching { recognizer }.isSuccess

    override suspend fun recognize(bmp: Bitmap): List<TextBox> =
        suspendCancellableCoroutine { cont ->
            if (bmp.isRecycled) {
                cont.resume(emptyList()); return@suspendCancellableCoroutine
            }
            val image = runCatching { InputImage.fromBitmap(bmp, 0) }.getOrNull()
            if (image == null) {
                cont.resume(emptyList()); return@suspendCancellableCoroutine
            }
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val boxes = text.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->
                            val r = line.boundingBox ?: return@mapNotNull null
                            if (line.text.isBlank()) null
                            else TextBox(line.text, r.centerX(), r.centerY())
                        }
                    cont.resume(boxes)
                }
                .addOnFailureListener { e ->
                    // 模型未下载/无 GMS：记一次日志后静默降级（节点通道仍在）
                    RunLog.w("OCR", "识别失败（模型未就绪或无 GMS，已回退节点文本）：${e.message}")
                    cont.resume(emptyList())
                }
        }

    override fun close() {
        runCatching { recognizer.close() }
    }
}

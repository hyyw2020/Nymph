package dev.nymph.auto.engine

import android.content.Context
import android.graphics.Bitmap
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.vision.CocoLabels
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.Channels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/** AI 找目标的命中结果（类别框中心 + 置信度）。 */
data class AiHit(
    val cx: Int,
    val cy: Int,
    val label: String,      // COCO 英文标签
    val score: Float,       // 0~1
)

/**
 * 本地目标检测（对标自动精灵"AI 识别"：文字描述找目标）。
 *
 * 模型：efficientdet-lite0 int8（assets/models/efficientdet-lite0.tflite，
 * 4.6MB，COCO 80 类，明文未加密）。推理全程本地 CPU，不联网。
 *
 * 加载方式：资产文件整体读进 direct ByteBuffer 后走
 * [ObjectDetector.createFromBufferAndOptions]——不依赖 Task Library 的
 * 文件路径解析（各版本对"asset 路径还是文件路径"的处理不一致，
 * 显式读字节是确定性的）。
 *
 * 与对方的诚实差异（不夸大）：
 *  - 对方用的是自训练模型（960×960 输入，PaddleOCR 系导出），界面元素
 *    描述直接可转类别；我们用公开 COCO 预训练模型，"设置按钮"这类 UI
 *    描述映射不到类别时如实失败（见 [find] 返回 null 的归因日志），
 *    而不是乱点一个框。
 *  - 模型缺失（so/资源被裁）时 available=false，动作归因 AI_NO_MATCH。
 */
class AiDetector(private val context: Context) : Closeable {

    private var detector: ObjectDetector? = null
    private var modelBuffer: ByteBuffer? = null
    private var failed = false

    val available: Boolean
        get() = !failed

    /** 首次使用时懒加载（读模型 + 创建检测器均阻塞，放 Default 线程）。 */
    private suspend fun ensure(): ObjectDetector? {
        detector?.let { return it }
        if (failed) return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val buffer = loadModel()
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setNumThreads(THREADS)
                            .build(),
                    )
                    .setMaxResults(MAX_RESULTS)
                    // 下限放低，精确阈值由 find() 按调用方传入的 aiThreshold 过滤
                    .setScoreThreshold(FLOOR_THRESHOLD)
                    .build()
                ObjectDetector.createFromBufferAndOptions(buffer, options)
                    .also { detector = it }
            }.onFailure { e ->
                failed = true
                // v2.9.0：16 KB 页设备上，TFLite 预编译的 libtask_vision_jni.so
                // 若未做 ELF 段对齐，dlopen 会抛 UnsatisfiedLinkError。这里如实
                // 归因，避免把「页大小不兼容」误报成「模型损坏」。检测能力降级
                // 为 AI_NO_MATCH，其余 OCR / 找图 / 节点链路照常可用，不崩溃。
                val hint = if (e is UnsatisfiedLinkError || e is LinkageError)
                    "（疑似 native 库加载失败：16KB 页设备 / so 对齐不兼容；AI 找目标已降级）"
                else ""
                RunLog.e("AI", "目标检测模型加载失败（${MODEL_ASSET}）$hint：${e.message}")
            }.getOrNull()
        }
    }

    /** 资产模型 → direct ByteBuffer（TFLite 偏好直接内存，避免拷贝）。 */
    private fun loadModel(): ByteBuffer {
        modelBuffer?.let { return it }
        val bytes = context.applicationContext.assets.open(MODEL_ASSET).use { input ->
            val buf = ByteBuffer.allocateDirect(input.available())
            Channels.newChannel(input).use { ch -> ch.read(buf) }
            buf.flip()
            buf
        }
        modelBuffer = bytes
        return bytes
    }

    /**
     * 在 [bmp] 中查找描述对应的目标。
     *
     * @param description 文字描述（如"手机"）；经 [CocoLabels] 映射到 COCO
     *        类别，映射不到返回 null（如实归因，绝不退化为"随便点一个"）。
     * @param threshold   置信度阈值（百分制）。
     * @param regionFilter 命中框中心是否落在限定区域（百分比）。
     */
    suspend fun find(
        description: String,
        threshold: Int,
        bmp: Bitmap,
        regionFilter: ((Int, Int) -> Boolean)? = null,
    ): AiHit? {
        val label = CocoLabels.resolve(description)
        if (label == null) {
            RunLog.w("AI", "描述「$description」无法映射到离线模型类别（COCO 80 类）")
            return null
        }
        val det = ensure() ?: return null
        if (bmp.isRecycled) return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val results: List<Detection> = det.detect(TensorImage.fromBitmap(bmp))
                val min = threshold.coerceIn(1, 100) / 100f
                results.asSequence()
                    .flatMap { r -> r.categories.asSequence().map { c -> c to r } }
                    .filter { (c, _) -> c.label == label && c.score >= min }
                    .map { (c, r) ->
                        val b = r.boundingBox
                        AiHit(b.centerX().toInt(), b.centerY().toInt(), c.label, c.score)
                    }
                    .filter { hit -> regionFilter?.invoke(hit.cx, hit.cy) ?: true }
                    .maxByOrNull { it.score }
            }.onFailure { e ->
                RunLog.e("AI", "检测执行失败：${e.message}")
            }.getOrNull()
        }
    }

    override fun close() {
        runCatching { detector?.close() }
        detector = null
        // modelBuffer 由 GC 回收（direct buffer 无显式释放 API，交由 finalizer）
        modelBuffer = null
    }

    companion object {
        /** 明文模型文件（assets 下，不加密；与"不允许加密"硬约束一致）。 */
        const val MODEL_ASSET = "models/efficientdet-lite0.tflite"
        private const val MAX_RESULTS = 8
        private const val THREADS = 2
        private const val FLOOR_THRESHOLD = 0.05f
    }
}

package dev.nymph.auto.engine

import android.graphics.Bitmap
import android.os.Build
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.vision.ImageFinder
import dev.nymph.auto.core.vision.ImageMask
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/**
 * 屏幕图像源抽象 —— 「不与录屏抢权限」的核心。
 *
 * 实现顺序（优先级从高到低）：
 *  1. AccessibilityScreenshotSource：无障碍 takeScreenshot（API 30+）
 *     —— 不创建 MediaProjection 会话，与任何录屏 App 完全共存
 *  2. NodeOnlySource（由 NodeFinder 承担）：完全不依赖截图的识别通道
 *  3. RootScreenSource / AdbScreenSource（可选组件，用户手动启用）
 *
 * 全工程**没有任何 MediaProjection / ACTION_MEDIA_PROJECTION 引用**。
 */
interface ScreenSource {
    /** 当前是否可用。 */
    val available: Boolean

    /** 不可用原因（用于脚本降级提示）。 */
    val unavailableReason: String

    /** 抓取一帧屏幕；失败返回 null。实现方负责回收中间产物。 */
    suspend fun grab(): Bitmap?

    /** 最近一次成功抓取的时间戳（elapsedRealtime），用于 500ms 缓存复用。 */
    fun lastGrabAt(): Long
}

/**
 * 无障碍截图实现。仅 API 30+ 可用。
 * 注意：截图回调里 wrapHardwareBuffer → copy 成软件位图后立即回收硬件缓冲。
 *
 * 失败熔断：takeScreenshot 连续失败 MAX_FAILS 次后进入 COOLDOWN_MS 冷却期，
 * 冷却期内直接快速失败——否则每个图片条件都要等满 2×3s 超时，
 * 27 个条件的脚本会卡死数分钟（实测安卓 12 体感"假死"的次要原因）。
 */
class AccessibilityScreenshotSource(
    private val service: AutoClickService,
) : ScreenSource {

    override val available: Boolean
        get() = Build.VERSION.SDK_INT >= 30 && AutoClickService.instance != null

    override val unavailableReason: String
        get() = if (Build.VERSION.SDK_INT < 30) {
            "当前系统版本低于 Android 11，无障碍截图不可用（可用节点/文字类动作替代）"
        } else {
            "无障碍服务未连接"
        }

    private var lastAt = 0L
    private var failStreak = 0
    private var circuitOpenUntil = 0L
    private var circuitLogged = false

    /**
     * 最近一次抓屏失败是否由 secure 窗口（FLAG_SECURE，黑帧）引起。
     * 能力矩阵据此判 RESTRICTED 档——注意这是"最近一次"而非粘性标记：
     * 用户离开安全页面后应自动恢复，不需要重启。
     */
    var lastFailureWasSecure: Boolean = false
        private set

    override fun lastGrabAt(): Long = lastAt

    override suspend fun grab(): Bitmap? {
        if (failStreak >= MAX_FAILS) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < circuitOpenUntil) {
                if (!circuitLogged) {
                    circuitLogged = true
                    dev.nymph.auto.core.log.RunLog.w(
                        "Screen",
                        "无障碍截图连续失败 $failStreak 次，进入 ${COOLDOWN_MS / 1000}s 冷却" +
                            "（可能原因：系统正在录屏/其他应用占用截图、或当前窗口不允许截图）",
                    )
                }
                return null
            }
            // 冷却结束，半开试探
            failStreak = MAX_FAILS - 1
        }
        val bmp = service.screenshotBitmap()
        if (bmp == null) {
            failStreak++
            lastFailureWasSecure =
                service.lastScreenshotError == android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            if (lastFailureWasSecure) {
                dev.nymph.auto.core.log.RunLog.w(
                    "Screen",
                    "当前窗口不允许截图（FLAG_SECURE，如支付/隐私页面）——能力矩阵判为受限档：" +
                        "坐标可执行，语义吸附与闭环不可用",
                )
            }
            if (failStreak >= MAX_FAILS) {
                circuitOpenUntil = android.os.SystemClock.elapsedRealtime() + COOLDOWN_MS
            }
        } else {
            failStreak = 0
            circuitLogged = false
            lastFailureWasSecure = false
            lastAt = android.os.SystemClock.elapsedRealtime()
        }
        return bmp
    }

    companion object {
        private const val MAX_FAILS = 3
        private const val COOLDOWN_MS = 3000L
    }
}

/**
 * 视觉 facade：把截图源 + NCC 匹配 + 降级策略组合成脚本层统一入口。
 *
 * 三条识别通道（与"不与系统录屏抢权限"一致，全部走无障碍截图）：
 *  1. 节点通道（NodeFinder）：全版本可用，无需截图
 *  2. 截图通道（NCC 找图/找色）：API 30+ 无障碍截图
 *  3. OCR 通道（OcrEngine）：节点拿不到画布文字时的兜底
 *  4. AI 通道（AiDetector）：文字描述找目标（COCO 离线检测）
 */
class VisionPipeline(
    private val screenSource: ScreenSource,
    private val nodeFinder: NodeFinder,
    private val ocr: OcrEngine? = null,
    private val ai: AiDetector? = null,
    /** 自家浮窗矩形提供者（由 RunnerService 装配）。空 = 不抑制。 */
    private val selfOverlayRects: () -> List<ImageMask.RectPct> = { emptyList() },
) {
    /**
     * 帧缓存（短 TTL）。
     *
     * 一个动作常常连续多次识别（条件 → 点击 → 断言），每次 takeScreenshot
     * 要 100~300ms，还会与系统录屏争用合成器。400ms 内复用同一帧既省时间，
     * 又把截图次数降下来——这对「不和系统录屏抢资源」是正向的。
     *
     * 所有权变更（相对旧版）：返回的 Bitmap 由本类持有，**调用方不得 recycle**，
     * 过期时在这里回收；管线 close() 释放最后一帧。
     */
    private val frameLock = kotlinx.coroutines.sync.Mutex()
    private var cachedFrame: Bitmap? = null
    private var cachedAt = 0L

    private suspend fun frame(): Bitmap? = frameLock.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        val c = cachedFrame
        if (c != null && !c.isRecycled && now - cachedAt <= FRAME_TTL_MS) {
            return c
        }
        c?.let { runCatching { it.recycle() } }
        val b = screenSource.grab()
        cachedFrame = b
        cachedAt = now
        b
    }

    /** 主动失效（动作结束时想拿新帧，或界面已明显变化）。 */
    fun invalidateFrame() {
        val f = cachedFrame
        cachedFrame = null
        runCatching { f?.recycle() }
    }

    /** 释放缓存帧；一次脚本运行结束时调用。 */
    fun close() = invalidateFrame()

    /**
     * 取一帧用于匹配的 core 图像：先拷出像素（`toCoreImage` 是新数组，
     * 不会改动缓存 Bitmap 与失败现场存证），再抑制自家浮窗区域。
     * 录屏/存证看到的仍是真实画面，只有 NCC 匹配用的是干净副本。
     */
    private fun screenImage(bmp: Bitmap): ImageFinder.Image {
        val img = bmp.toCoreImage()
        val rects = runCatching { selfOverlayRects() }.getOrDefault(emptyList())
        if (rects.isNotEmpty()) ImageMask.suppress(img, rects)
        return img
    }

    /** 找图：命中返回中心坐标（原图像素）。filters 对 screen/template 双方应用（匹配前预处理）。 */
    suspend fun findImage(
        templateB64: String,
        region: dev.nymph.auto.core.model.Region,
        minSimilar: Int,
        refDpi: Double,
        curDensity: Double,
        filters: List<dev.nymph.auto.core.model.ImageFilter> = emptyList(),
    ): ImageFinder.Hit? {
        val bmp = frame() ?: return null
        return try {
            val screen = screenImage(bmp)
            val template = ImageOps.decodeBase64(templateB64) ?: return null
            // 滤镜双方应用：与 ImageFilters 约定一致（单方应用会破坏特征空间一致性）
            val fs = dev.nymph.auto.core.vision.ImageFilters.apply(screen, filters)
            val ft = dev.nymph.auto.core.vision.ImageFilters.apply(template, filters)
            ImageFinder.find(
                fs, ft,
                region.leftPct, region.topPct, region.rightPct, region.bottomPct,
                minSimilar, refDpi, curDensity,
            )
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    /**
     * 失败现场存证：把当前屏幕帧 dump 到 [dir]（PNG）。
     * 目录内只保留最近 [KEEP] 张，防止写满存储。返回文件绝对路径或 null。
     */
    suspend fun dumpFrame(dir: File, tag: String): String? {
        val bmp = runCatching { frame() }.getOrNull() ?: return null
        try {
            runCatching { dir.mkdirs() }
            val olds = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            if (olds.size >= KEEP) olds.take(olds.size - KEEP + 1).forEach { runCatching { it.delete() } }
            val safe = tag.replace(Regex("[^\\w一-龥（）()\\[\\]_-]"), "_").take(48)
            val f = File(dir, "miss_${safe}_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            }
            return f.absolutePath
        } catch (e: Exception) {
            return null
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    /** 存在性判断（条件用）。 */
    suspend fun imageExists(
        templateB64: String,
        region: dev.nymph.auto.core.model.Region,
        minSimilar: Int,
        filters: List<dev.nymph.auto.core.model.ImageFilter> = emptyList(),
        refDpi: Double = 0.0,
        curDensity: Double = 1.0,
    ): Boolean = findImage(templateB64, region, minSimilar, refDpi, curDensity, filters) != null

    /**
     * 匹配诊断：返回当前屏幕对该模板的全局最佳 NCC 分数（0~1，不套阈值）。
     * 抓屏失败返回 -1。用于未命中时的归因日志：
     *  <0.45 多半界面没有该元素/方向错配；0.45~阈值 是尺度或清晰度问题。
     */
    suspend fun diagnoseImage(
        templateB64: String,
        region: dev.nymph.auto.core.model.Region,
        filters: List<dev.nymph.auto.core.model.ImageFilter> = emptyList(),
        refDpi: Double = 0.0,
        curDensity: Double = 1.0,
    ): Double {
        val bmp = frame() ?: return -1.0
        return try {
            val screen = screenImage(bmp)
            val template = ImageOps.decodeBase64(templateB64) ?: return -1.0
            val fs = dev.nymph.auto.core.vision.ImageFilters.apply(screen, filters)
            val ft = dev.nymph.auto.core.vision.ImageFilters.apply(template, filters)
            ImageFinder.bestScore(
                fs, ft,
                region.leftPct, region.topPct, region.rightPct, region.bottomPct,
                refDpi, curDensity,
            )
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    /** 节点通道：不依赖截图，全版本可用。 */
    fun findNode(
        id: String, className: String, text: String,
        depthMin: Int, depthMax: Int,
    ): NodeHit? = nodeFinder.find(id, className, text, depthMin, depthMax)

    /**
     * 文字查找：先走节点文本（快、全版本、不花截图），未命中且 OCR 可用时
     * 回退截图 + ML Kit 中文识别（对标自动精灵"文字条件 OCR 化"——节点树
     * 拿不到游戏/自绘界面/图片里的文字）。
     *
     * @param ocrFallback false=只要节点结果（调用方明确不需要 OCR 时省一次截图）
     */
    suspend fun findTextNode(text: String, regex: Boolean, ocrFallback: Boolean = true): NodeHit? {
        nodeFinder.findByText(text, regex)?.let { return it }
        if (!ocrFallback) return null
        val engine = ocr?.takeIf { it.available } ?: return null
        val bmp = frame() ?: return null
        return try {
            // 首次识别可能触发 Play 服务模型下载：给足超时，超时按未命中降级
            val boxes = kotlinx.coroutines.withTimeoutOrNull(OCR_TIMEOUT_MS) {
                engine.recognize(bmp)
            } ?: run {
                RunLog.w("OCR", "识别超时（${OCR_TIMEOUT_MS / 1000}s，模型可能仍在下载）")
                emptyList()
            }
            if (boxes.isEmpty()) return null
            dev.nymph.auto.core.vision.TextSearch.matchBest(text, regex, boxes)
                ?.let { NodeHit(it.cx, it.cy, it.text, "", "ocr", -1) }
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    /**
     * AI 找目标（对标自动精灵"AI识别"）：文字描述 → COCO 类别 → 检测框中心。
     * 描述映射不到类别 / 模型不可用 / 未检出都返回 null，由调用方归因。
     */
    suspend fun findAiObject(
        description: String,
        threshold: Int,
        region: dev.nymph.auto.core.model.Region,
    ): AiHit? {
        val det = ai?.takeIf { it.available } ?: return null
        val bmp = frame() ?: return null
        return try {
            det.find(description, threshold, bmp) { cx, cy ->
                // 检测框中心（屏幕像素）换算成百分比后判区域命中
                val px = cx * 100.0 / bmp.width
                val py = cy * 100.0 / bmp.height
                px >= region.leftPct && px <= region.rightPct &&
                    py >= region.topPct && py <= region.bottomPct
            }
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    /**
     * 找色：coordX/coordY 均 >= 0（百分比）时只检查该指定点（对标自动精灵
     * colorData.x/y 的定点取色）；否则在区域内扫描第一个相似度达标点。
     * 返回相对坐标的绝对值（原图像素）。
     */
    suspend fun findColor(
        color: Long,
        similarity: Int,
        region: dev.nymph.auto.core.model.Region,
        coordX: Double = -1.0,
        coordY: Double = -1.0,
    ): Pair<Int, Int>? {
        val bmp = frame() ?: return null
        return try {
            val screen = screenImage(bmp)
            // v2.9.4：0 尺寸或像素数组不完整的异常帧会让下面 coerceIn(0, w-1)
            // 抛空区间异常、argb 索引越界；直接判为未命中（在 try 内，finally 仍执行）。
            if (screen.width <= 0 || screen.height <= 0 ||
                screen.argb.size.toLong() < screen.width.toLong() * screen.height
            ) return null
            val target = color.toInt()
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val tol = (100 - similarity.coerceIn(1, 100)) * 3 // 每通道容差
        fun matchAt(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return false
            val c = screen.argb[y * screen.width + x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return Math.abs(r - tr) <= tol && Math.abs(g - tg) <= tol && Math.abs(b - tb) <= tol
        }
        if (coordX >= 0.0 && coordY >= 0.0) {
            // 定点取色：colorData.x/y 是百分比坐标
            val px = (coordX / 100.0 * screen.width).toInt()
            val py = (coordY / 100.0 * screen.height).toInt()
            return if (matchAt(px, py)) px to py else null
        }
        val x0 = (region.leftPct / 100.0 * screen.width).toInt().coerceIn(0, screen.width - 1)
        val y0 = (region.topPct / 100.0 * screen.height).toInt().coerceIn(0, screen.height - 1)
        val x1 = (region.rightPct / 100.0 * screen.width).toInt().coerceIn(x0 + 1, screen.width)
        val y1 = (region.bottomPct / 100.0 * screen.height).toInt().coerceIn(y0 + 1, screen.height)
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val c = screen.argb[y * screen.width + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (Math.abs(r - tr) <= tol && Math.abs(g - tg) <= tol && Math.abs(b - tb) <= tol) {
                        return x to y
                    }
                }
            }
            null
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    companion object {
        /** 失败现场截图保留张数（按时间滚动）。 */
        private const val KEEP = 5

        /** OCR 兜底超时：首次识别可能触发 Play 服务模型下载，给 20s 宽限。 */
        private const val OCR_TIMEOUT_MS = 20_000L

        /** 帧复用窗口：同一动作内的连续识别共享一帧。 */
        private const val FRAME_TTL_MS = 400L
    }
}

/** Bitmap ↔ core.vision.ImageFinder.Image 互转（零拷贝灰度化在 core 内完成）。 */
fun Bitmap.toCoreImage(): ImageFinder.Image {
    val w = width
    val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    return ImageFinder.Image(w, h, px)
}

fun ScreenSource.recycle(img: ImageFinder.Image) { /* Bitmap 由实现方管理；core Image 仅数组 */ }

object ImageOps {
    fun decodeBase64(b64: String): ImageFinder.Image? {
        if (b64.isEmpty()) return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val core = bmp.toCoreImage()
            bmp.recycle()
            core
        } catch (e: Exception) {
            null
        }
    }
}

/** 节点命中结果。 */
data class NodeHit(
    val centerX: Int,
    val centerY: Int,
    val text: String,
    val viewId: String,
    val className: String,
    val depth: Int,
)

/**
 * 能力探测（裁定阶段 0）：把散落各处的探测收口成一个快照，
 * 供 CapabilityMatrix 定档，并在拾取/回放界面顶部明示档位与限制。
 *
 * 探测项与档位（四档）：
 *  - 全功能：无障碍 + 截图可用，非 AAPM → 精确匹配 + 形态随机 + 检查点闭环
 *  - 降级：  截图不可用 → 只保证点中，闭环不可用
 *  - 受限：  命中 secure/sensitive 页 → 坐标可跑，语义吸附与闭环不可用
 *  - 不可用：AAPM 开启或无无障碍 → 诚实告知，无豁免
 */
object CapabilityProbe {

    fun snapshot(
        context: android.content.Context,
        screenSource: ScreenSource? = null,
        nodeFinder: NodeFinder? = null,
    ): dev.nymph.auto.core.runner.CapabilitySnapshot {
        val a11y = AutoClickService.live() != null
        val aapm = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                context.getSystemService(
                    android.security.advancedprotection.AdvancedProtectionManager::class.java,
                )?.isAdvancedProtectionEnabled == true
            } else {
                false
            }
        }.getOrDefault(false)
        val secure = (screenSource as? AccessibilityScreenshotSource)?.lastFailureWasSecure == true
        val sensitive = runCatching {
            (nodeFinder?.countSensitiveNodes() ?: 0) > 0
        }.getOrDefault(false)
        return dev.nymph.auto.core.runner.CapabilitySnapshot(
            a11yOnline = a11y,
            aapmEnabled = aapm,
            screenshotSupported = android.os.Build.VERSION.SDK_INT >= 30,
            secureWindowDetected = secure,
            sensitivePageDetected = sensitive,
            shizukuOnline = false, // 预留：接入 Shizuku 后置 true
        )
    }
}

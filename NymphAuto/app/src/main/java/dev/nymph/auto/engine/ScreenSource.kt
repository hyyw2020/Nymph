package dev.nymph.auto.engine

import android.graphics.Bitmap
import android.os.Build
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.vision.ImageFinder
import dev.nymph.auto.core.vision.ImageMask
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

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
 * 27 个条件的脚本会卡死数分钟（实测安卓 12 表现为「假死」的次要原因）。
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
        // v2.9.36：单飞丢弃的帧（上一请求仍在途）是「本帧无新画面」，不是采集失败。
        // 若计入 failStreak，16ms 帧同步下 3 次 BUSY 就会触发 3s 熔断，反而把通道
        // 锁得更死。这里直接返回 null（场景判定 fail-closed），不触碰熔断计数。
        if (service.lastScreenshotError == AutoClickService.SHOT_ERROR_BUSY) {
            return null
        }
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

    /**
     * v2.9.46：模板解码缓存（运行期实例级）。锚点 PNG 在脚本拾取后逐字不变，
     * 但旧实现每帧对每条规则都 `decodeBase64` + `BitmapFactory.decodeByteArray` +
     * `getPixels` 一遍：1080p 屏 × 10 条规则 × 30fps = 每秒数百次位图解码与整帧分配。
     * 这里按 templateB64 缓存解码结果，一次运行内只解一次；null（解码失败）也缓存，
     * 避免坏模板每帧重试。缓存随本管线实例创建 / 随 [close] 释放，不跨运行累积。
     */
    private val templateCache = HashMap<String, ImageFinder.Image?>()

    /** 解码模板并缓存；空串返回 null（fail-closed，不缓存哨兵）。 */
    private fun decodeTemplate(b64: String): ImageFinder.Image? {
        if (b64.isEmpty()) return null
        if (templateCache.containsKey(b64)) return templateCache[b64]
        val decoded = ImageOps.decodeBase64(b64)
        templateCache[b64] = decoded
        return decoded
    }

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

    /**
     * v2.9.28：**强制取新帧**（场景驱动逐帧判定专用）。
     *
     * [frame] 的 400ms TTL 是为「一个动作内连续多次识别复用同一帧」设计的省截图
     * 策略，适用于顺序链；场景驱动是持续监视，若沿用会让连续多轮复用同一张位图，
     * 表面上缩短了轮间隔、实际仍在对旧画面重复计算。本方法绕过 TTL 每轮抓取一次，
     * 并原子替换缓存，使同一轮的失败现场存证也能拿到当轮画面。
     *
     * 抓取失败时缓存置空并返回 null：调用方据此把本轮全部规则观测置为未在场，
     * **不拿上一轮旧帧冒充新帧判定**（fail-closed）。连续失败仍由
     * [AccessibilityScreenshotSource] 的熔断冷却收敛，不会每帧重试到 ANR。
     */
    private suspend fun grabFreshFrame(): Bitmap? = frameLock.withLock {
        val b = screenSource.grab()
        val old = cachedFrame
        cachedFrame = b
        cachedAt = android.os.SystemClock.elapsedRealtime()
        if (old !== b) runCatching { old?.recycle() }
        b
    }

    /** 主动失效（动作结束时想拿新帧，或界面已明显变化）。 */
    // v2.9.35：此前无锁直接读写 cachedFrame（其它访问点都在 frameLock 内），
    // 与 frame()/grabFreshFrame() 并发时可能回收正在被读取的位图、或同一 Bitmap
    // 被回收两次 → getPixels on recycled bitmap 崩溃/黑图匹配失败。统一走 frameLock。
    suspend fun invalidateFrame() = frameLock.withLock {
        val f = cachedFrame
        cachedFrame = null
        runCatching { f?.recycle() }
    }

    /** 释放缓存帧与模板解码缓存；一次脚本运行结束时调用（协程上下文）。 */
    suspend fun close() {
        invalidateFrame()
        // v2.9.46：模板解码缓存随运行结束释放，避免跨运行持有像素数组。
        templateCache.clear()
    }

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
            val template = decodeTemplate(templateB64) ?: return null
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

    /**
     * **v2.9.15 / A1+A6：找模板，返回 `{score, loc}`**（一次抓帧）。
     *
     * 命中返回带 NCC 分数与命中中心（原图像素）的 [TemplateMatch]，未命中返回 null。
     * 相比旧的 [findImage]（只回 Hit），这里额外应用 `TemplateSpec.maskRects`
     * 动态区域掩码（剔除高方差 / 易变像素），并统一走 [findTemplates] 的一轮一帧通道。
     */
    suspend fun findTemplate(spec: TemplateSpec): TemplateMatch? =
        findTemplates(listOf(spec)).firstOrNull()?.takeIf { it.present }

    /**
     * **一轮一帧共享给 N 条规则**（v2.9.15 / A6）：一次 `frame()` + 一份基础像素副本，
     * N 个模板各 copy 一份数组做掩码/滤镜/NCC。绝不 N 次各自 `screenshotBitmap()`
     * —— 那对功耗和「不打断其他 APP」是实打实的风险。
     *
     * 帧缓存（[frame] 的 400ms TTL）之外，这里再显式保证「同一轮、同一帧、同一基础副本」，
     * `runSceneDriven` 每轮只调一次本方法。每个 spec 的掩码是**原地变换**，因此必须
     * 各自 `copyOf()`，绝不共享数组互相污染。
     *
     * v2.9.28：新增 `freshFrame` 参数。场景驱动逐帧监视传 `true`，每轮强制抓取新帧；
     * 顺序链的条件判定 / 找图 / 存证仍走默认 `false`，沿用 400ms 缓存复用，零回归。
     *
     * @return 与 [specs] 同序、同长度的命中结果（未命中/模板解码失败为 null）。
     */
    suspend fun findTemplates(
        specs: List<TemplateSpec>,
        freshFrame: Boolean = false,
        /** v2.9.32：当前帧方向（Surface.ROTATION_*）。场景驱动传实时 rotation，其余通道默认 0 零回归。 */
        currentRotation: Int = 0,
        /** v3.0.3 S4：整图缩放 NCC 基线（整帧原画质 PNG base64）。空 = 不启用整图门，零回归。 */
        wholeFrameBaselineB64: String = "",
        /** v3.0.3 S4：整图门方法与保守初值阈值（百分制，待真机回调）。 */
        wholeFrameMethod: dev.nymph.auto.core.model.GlobalVerifyMethod =
            dev.nymph.auto.core.model.GlobalVerifyMethod.OFF,
        wholeFrameSimilarity: Int = 70,
    ): List<TemplateMatch?> {
        if (specs.isEmpty()) return emptyList()
        val bmp = (if (freshFrame) grabFreshFrame() else frame())
            ?: return List<TemplateMatch?>(specs.size) { null }
        return try {
            // 一次像素副本（screenImage 内已抑制自家浮窗）；N 个模板共享这一帧基础数据
            val base = screenImage(bmp)
            // v3.0.3 S4：整图门与局部门共用本帧 base（frame() 仍恰好 1 次）。
            // 几何入口门 → 整图 NCC 串联；fail-closed 时整批 spec 不跑局部 NCC，直接不派发。
            if (wholeFrameMethod != dev.nymph.auto.core.model.GlobalVerifyMethod.OFF &&
                wholeFrameBaselineB64.isNotBlank()
            ) {
                val baseline = decodeTemplate(wholeFrameBaselineB64)
                val verdict = dev.nymph.auto.core.editor.SceneTrigger.verifyWholeFrame(
                    baseline = baseline, current = base,
                    method = wholeFrameMethod, similarityPct = wholeFrameSimilarity,
                )
                if (!verdict.passed) {
                    return List<TemplateMatch?>(specs.size) {
                        TemplateMatch(
                            present = false, score = verdict.score,
                            cxPx = -1, cyPx = -1, frameW = base.width, frameH = base.height,
                            geometryOk = !verdict.enabled || verdict.geometryOk,
                        )
                    }
                }
            }
            val orient = dev.nymph.auto.core.runner.OrientationTransform
            // v2.9.32 方向无关：按不同旋转格数把当前帧旋回拾取几何，缓存复用。
            // 正常脚本所有规则拾取方向一致 → 只转一次（turn=0 时零拷贝）。
            val normalizedCache = HashMap<Int, ImageFinder.Image>()
            fun normalizedFrame(turns: Int): ImageFinder.Image {
                val key = orient.norm(-turns)
                return normalizedCache.getOrPut(key) {
                    if (key == 0) base else orient.rotateImage(base, key)
                }
            }
            fun effectivePickRotation(spec: TemplateSpec): Int {
                if (spec.pickRotation != 0) return orient.norm(spec.pickRotation)
                // 老脚本未记录方向：横屏源按 1，竖屏源按 0。同向运行时 turns=0 零回归。
                return if (spec.templateSrcW > 0 && spec.templateSrcW > spec.templateSrcH) 1 else 0
            }
            specs.map { spec ->
                // 无画面模板的规则（fail-closed，A4）返回 null，不 copy 数组、不跑 NCC
                if (spec.templateB64.isBlank()) return@map null
                val turns = orient.turns(effectivePickRotation(spec), currentRotation)
                val pickFrame = normalizedFrame(turns)
                // v2.9.32 帧几何闭环：当前帧已旋回拾取几何，再做同向宽高比校验。
                // 宽高比异常（既非同向也非互换，如异形裁剪）仍 fail-closed，不跑 NCC。
                if (!dev.nymph.auto.core.editor.SceneEvidence.geometryMatches(
                        spec.templateSrcW, spec.templateSrcH, pickFrame.width, pickFrame.height,
                    )) {
                    return@map TemplateMatch(
                        present = false, score = -1.0,
                        cxPx = -1, cyPx = -1, frameW = pickFrame.width, frameH = pickFrame.height,
                        geometryOk = false, orientationTurns = turns,
                    )
                }
                // v2.9.46：只有需要动态掩码抑制的规则才复制整帧——findDetailed 与
                // ImageFilters.apply 都只读像素（后者有滤镜时内部另建数组），唯一原地写
                // 是下面的 ImageMask.suppress，而它仅在 maskRects 非空时可能触发。
                // 无掩码规则直接只读共享 pickFrame，省掉每规则一份整帧 IntArray 复制
                // （1080×2400 ≈ 10MB/份，10 条规则即每帧省约 100MB 分配）。
                val screen = if (spec.maskRects.isEmpty()) {
                    pickFrame
                } else {
                    ImageFinder.Image(pickFrame.width, pickFrame.height, pickFrame.argb.copyOf())
                }
                val template = decodeTemplate(spec.templateB64) ?: return@map null
                val ft = dev.nymph.auto.core.vision.ImageFilters.apply(template, spec.filters)
                val roi = dev.nymph.auto.core.vision.RoiExpr.resolveRegion(spec.region)
                // 先在未抑制的副本上跑一次，拿到全局最佳窗口位置。
                var scored = ImageFinder.findDetailed(
                    dev.nymph.auto.core.vision.ImageFilters.apply(screen, spec.filters), ft,
                    roi.leftPct, roi.topPct, roi.rightPct, roi.bottomPct,
                    spec.minSimilar, spec.refDpi, spec.curDensity,
                )
                // v2.9.31 两阶段掩码：只有最佳窗口确实落在掩码区才抑制后重算。
                // 掩码盖住 0/N 时保持未抑制结果，避免把有效特征抹掉 —— 真机
                // RCA 已确证单帧抑制把上一行像素复制下来、最佳窗口又不在掩码区。
                val covered = dev.nymph.auto.core.editor.SceneEvidence.maskCovers(
                    spec.maskRects, scored.bestCx, scored.bestCy, screen.width, screen.height,
                )
                if (covered > 0 && spec.maskRects.isNotEmpty()) {
                    ImageMask.suppress(
                        screen,
                        spec.maskRects.map {
                            ImageMask.RectPct(it.leftPct, it.topPct, it.rightPct, it.bottomPct)
                        },
                    )
                    scored = ImageFinder.findDetailed(
                        dev.nymph.auto.core.vision.ImageFilters.apply(screen, spec.filters), ft,
                        roi.leftPct, roi.topPct, roi.rightPct, roi.bottomPct,
                        spec.minSimilar, spec.refDpi, spec.curDensity,
                    )
                }
                // v2.9.31 逐帧取色：在原始帧副本上按拾取点邻域采样，与装配期
                // sampleNeighborhood 同一口径。v2.9.32：拾取点是拾取几何下的百分比，
                // 故在已旋回拾取几何的 pickFrame 上采样，与锚点/NCC 同帧同源。
                // 无证据时零开销（老脚本零回归）。
                val observedArgb = if (
                    spec.pickXPct >= 0.0 && spec.pickYPct >= 0.0 && spec.colorArgb != 0
                ) {
                    val cx = (spec.pickXPct / 100.0 * pickFrame.width).toInt()
                        .coerceIn(0, (pickFrame.width - 1).coerceAtLeast(0))
                    val cy = (spec.pickYPct / 100.0 * pickFrame.height).toInt()
                        .coerceIn(0, (pickFrame.height - 1).coerceAtLeast(0))
                    dev.nymph.auto.core.editor.SceneAssist.sampleNeighborhood(
                        { x, y ->
                            if (x in 0 until pickFrame.width && y in 0 until pickFrame.height) {
                                pickFrame.argb.getOrNull(y * pickFrame.width + x)
                            } else null
                        },
                        cx, cy, pickFrame.width, pickFrame.height,
                    ).argb
                } else 0
                val hit = scored.hit
                TemplateMatch(
                    present = hit != null,
                    score = if (hit != null) hit.score else scored.bestScore,
                    cxPx = hit?.cx ?: -1,
                    cyPx = hit?.cy ?: -1,
                    frameW = pickFrame.width,
                    frameH = pickFrame.height,
                    bestCxPx = scored.bestCx,
                    bestCyPx = scored.bestCy,
                    observedArgb = observedArgb,
                    geometryOk = true,
                    maskCovered = covered,
                    orientationTurns = turns,
                )
            }
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    /**
     * 存在性判断（条件用）。v2.9.15 起委托 [findTemplate]（旧签名/返回类型逐字不变，
     * 406 例条件求值零回归）；命中语义与旧 `findImage(...) != null` 完全一致。
     */
    suspend fun imageExists(
        templateB64: String,
        region: dev.nymph.auto.core.model.Region,
        minSimilar: Int,
        filters: List<dev.nymph.auto.core.model.ImageFilter> = emptyList(),
        refDpi: Double = 0.0,
        curDensity: Double = 1.0,
    ): Boolean = findTemplate(
        TemplateSpec(templateB64, region, minSimilar, filters, refDpi, curDensity),
    ) != null

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
            val template = decodeTemplate(templateB64) ?: return -1.0
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
        // v2.9.35：/正则/ 约定下首尾斜杠是定界符，不参与匹配。统一在此剥离，
        // 节点通道与 OCR 通道共用同一份规整结果，避免两处口径漂移。
        val (pattern, isRegex) = normalizeTextPattern(text, regex)
        nodeFinder.findByText(pattern, isRegex)?.let { return it }
        if (!ocrFallback) return null
        val engine = ocr?.takeIf { it.available } ?: return null
        val bmp = frame() ?: return null
        return try {
            // bundled ML Kit 模型随包、无需下载；ORT 模型由用户自备。超时按未命中降级。
            val boxes = kotlinx.coroutines.withTimeoutOrNull(OCR_TIMEOUT_MS) {
                engine.recognize(bmp)
            } ?: run {
                RunLog.w("OCR", "识别超时（${OCR_TIMEOUT_MS / 1000}s），按未命中降级")
                emptyList()
            }
            if (boxes.isEmpty()) return null
            dev.nymph.auto.core.vision.TextSearch.matchBest(pattern, isRegex, boxes)
                ?.let { NodeHit(it.cx, it.cy, it.text, "", "ocr", -1) }
        } finally {
            // 帧由 VisionPipeline 缓存持有（见 frame()），此处不回收
        }
    }

    /**
     * 文字存在性断言（v2.9.34 安全根基）：供 APPEAR_TEXT 等条件判定使用。
     *
     * 与 [findTextNode] 的区别是返回类型：本方法只产出无坐标的
     * [dev.nymph.auto.core.vision.OcrTextAssertion]，调用方在类型系统上无法从
     * 判定结果取到任何点击位置——文字是否出现与在哪里点击被彻底解耦。点击定位
     * 仍走 [findTextNode]，能力不退化。
     *
     * 观测只有一条脱敏记录 `ocr_triggered`：matcher / hit / confidence / backend，
     * **不记原文、不记原文哈希**（"确认"类短文本可被字典枚举，哈希不构成脱敏）。
     */
    suspend fun assertText(text: String, regex: Boolean): dev.nymph.auto.core.vision.OcrTextAssertion? {
        val (pattern, isRegex) = normalizeTextPattern(text, regex)
        nodeFinder.findByText(pattern, isRegex)?.let { n ->
            emitOcrTriggered("node", hit = true, confidence = dev.nymph.auto.core.vision.OcrTextAssertion.UNAVAILABLE_CONFIDENCE)
            return dev.nymph.auto.core.vision.OcrTextAssertion(n.text, dev.nymph.auto.core.vision.OcrTextAssertion.UNAVAILABLE_CONFIDENCE, "node")
        }
        val engine = ocr?.takeIf { it.available }
        if (engine == null) {
            emitOcrTriggered("unavailable", hit = false, confidence = dev.nymph.auto.core.vision.OcrTextAssertion.UNAVAILABLE_CONFIDENCE)
            return null
        }
        val bmp = frame() ?: return null
        val boxes = kotlinx.coroutines.withTimeoutOrNull(OCR_TIMEOUT_MS) { engine.recognize(bmp) } ?: emptyList()
        val best = dev.nymph.auto.core.vision.TextSearch.matchBest(pattern, isRegex, boxes)
        val matcher = if (engine is OnnxOcrEngine) "ort_v5" else "mlkit_bundled"
        // v2.9.37：ORT CTC 解码已产出真实置信度（透传在 TextBox.confidence）；
        // ML Kit bundled 无置信度 API，仍是 -1.0。绝不伪造数值。
        val confidence = best?.confidence
            ?: dev.nymph.auto.core.vision.OcrTextAssertion.UNAVAILABLE_CONFIDENCE
        emitOcrTriggered(matcher, hit = best != null, confidence = confidence)
        return best?.let { dev.nymph.auto.core.vision.OcrTextAssertion.from(it, matcher) }
    }

    /**
     * 规整文字匹配模式：自动精灵用 `/pattern/` 定界表示正则。regex=true 且文本
     * 被首尾斜杠包裹时剥离定界符；否则原样返回。与正则编译失败显式失败策略配合，
     * 修复"斜杠未剥离致节点/OCR 双通道恒不命中"。
     */
    private fun normalizeTextPattern(text: String, regex: Boolean): Pair<String, Boolean> {
        if (regex && text.length >= 2 && text.startsWith("/") && text.endsWith("/")) {
            return text.substring(1, text.length - 1) to true
        }
        return text to regex
    }

    /** 脱敏观测：只记后端 / 命中 / 置信度，禁止写入原文或原文哈希。 */
    private fun emitOcrTriggered(matcher: String, hit: Boolean, confidence: Double) {
        RunLog.i("ocr_triggered", "matcher=$matcher hit=$hit confidence=${String.format(Locale.US, "%.3f", confidence)}")
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
        // v2.9.35：similarity 可经导入脚本带入 1~14，此时 tol=258~297 超过单通道
        // 上限 255，abs(r-tr)<=tol 恒成立 → 找色退化为"区域内第一个像素"，命中完全
        // 错误的坐标却报成功。容差必须钳在单通道有效范围内。
        val tol = ((100 - similarity.coerceIn(1, 100)) * 3).coerceIn(0, 255) // 每通道容差
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

        /** OCR 兜底超时：覆盖首帧模型加载（bundled 随包或 ORT 自检），给 20s 宽限。 */
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
 *  - 不可用：AAPM 开启或无无障碍 → 明确告知，无豁免
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

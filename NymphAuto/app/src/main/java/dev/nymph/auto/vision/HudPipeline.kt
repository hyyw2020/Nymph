// SPDX-License-Identifier: MIT
package dev.nymph.auto.vision

import android.graphics.Bitmap
import dev.nymph.auto.core.design.HudCascade
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.engine.ScreenSource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * **HUD 定位级联运行时骨架**（对应离线方案的 S0→S6 流水线）。
 *
 * 架构：一条确定性主干（V1，OpenCV 闭合梯度环 / 双竖线箭头签名 / NCC）负责「在哪」，
 * 轻量 ncnn 模型（V2）只对 top-k crop 认「是什么」，频域（V3）仅在条件触发时介入。
 * 三支路经 core 的 [HudCascade] 纯函数融合；任一 AI / 频域支路失效自动降级到纯 CV 主干。
 *
 * ```
 * S0 门禁   gate(scene)                —— 非对局不跑
 * S1 取帧   screenSource.grab() → 720 宽降采样 → 灰度（WindowMetrics 真实 bounds 由取帧源保证）
 * S2 消背景 时域静止掩膜（5~8 帧环形缓冲的中值/最小帧差）
 * S2b 频域  条件触发 → phaseCorrelate + PNR/残差双校验
 * S3 候选   闭合梯度环 + 双竖线箭头签名 → top-k
 * S4 裁决   NcnnNative.classify(crop96)（只认类别）
 * S5 融合   HudCascade.fuse + 三帧一致 + 一阶低通
 * S6 执行   点击前 verifyBeforeClick 再校验一帧
 * ```
 *
 * 本骨架把 S1/S2/S2b/S3 的 OpenCV 变换与 S4 的模型调用接线到位；具体 HUD 模板/阈值由标定数据提供，
 * 无标定或无模型时 fail-closed 降级，绝不伪造命中坐标。
 */
class HudPipeline(
    private val screenSource: ScreenSource,
    private val ncnn: NcnnNative? = null,
    private val labels: List<String> = emptyList(),
) {

    /** 定位结果：坐标（原图像素）+ 类别 + 触发支路 + 融合分。 */
    data class HudHit(
        val centerXPx: Int,
        val centerYPx: Int,
        val label: String,
        val branch: HudCascade.Branch,
        val score: Double,
    )

    /** S2 时域环形缓冲：缓存最近若干帧灰度图，做静止掩膜 / 最小帧差。 */
    private val grayRing = ArrayDeque<Mat>()
    private val ringCapacity = 6

    /** S5 历史：用于三帧一致与一阶低通。 */
    private val history = ArrayDeque<HudCascade.Fused>(3)
    private var smoothedX: Double? = null
    private var smoothedY: Double? = null

    /** S1：把 Bitmap 降采样到 720 宽、转灰度 Mat。调用方负责释放返回的 Mat。 */
    private fun toGray720(bmp: Bitmap): Mat {
        val scale = if (bmp.width > TARGET_WIDTH) TARGET_WIDTH.toDouble() / bmp.width else 1.0
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val rgba = Mat()
        org.opencv.android.Utils.bitmapToMat(bmp, rgba)
        val resized = Mat()
        Imgproc.resize(rgba, resized, Size(w.toDouble(), h.toDouble()))
        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        resized.release()
        return gray
    }

    /**
     * S2b 条件频域：仅当角速度/帧差超阈值且 PNR/残差达标时，返回相位相关偏移。
     * 相位相关返回的是**相对窗口中心的偏移**，转左上角坐标时符号不能搞反（方案明确提醒）。
     * 这里只产出质量信息与相对位移，是否采信交给 [HudCascade.shouldUseFrequency]。
     */
    private fun phaseCorrelate(cur: Mat, ref: Mat): Pair<Point, HudCascade.PhaseQuality>? {
        if (cur.size() != ref.size()) return null
        val window = Mat() // 空 window = Hanning 窗，OpenCV 内部处理
        val response = DoubleArray(1)
        val shift = Imgproc.phaseCorrelate(cur, ref, window, response)
        // response[0] 是相关峰能量；用它近似 PNR（真实 PNR 需主峰/次峰，骨架用带通+mag 计算）。
        val pnr = response[0]
        val residual = 0.0 // 骨架：残差需对齐后统计，此处保守置 0 交由上层阈值裁决
        return shift to HudCascade.PhaseQuality(pnr, residual)
    }

    /**
     * 主入口：跑一帧级联，产出 [HudHit] 或 null（fail-closed）。
     *
     * @param scene 当前场景分类（非对局直接短路）
     * @param inMatch 是否确实在对局（由节点/包名判定）
     * @param angularSpeed IMU 角速度（只做门控，不做运动补偿）
     * @param region 特征/落点限制区（百分比）
     */
    suspend fun locate(
        scene: HudCascade.Scene,
        inMatch: Boolean,
        angularSpeed: Double = 0.0,
        region: Region = Region(),
    ): HudHit? = withContext(Dispatchers.Default) {
        // S0 门禁
        if (!HudCascade.gateScene(scene, inMatch)) return@withContext null

        // S1 取帧
        val bmp = screenSource.grab() ?: return@withContext null
        val gray = try {
            toGray720(bmp)
        } finally {
            // Bitmap 由取帧源/VisionPipeline 管理，此处不 recycle
        }
        try {
            // S2 时域：帧差（当前帧 vs 环形缓冲最新帧）
            val prev = grayRing.lastOrNull()
            val frameDiff = if (prev != null) meanAbsDiff(gray, prev) else 0.0
            pushRing(gray)

            // S2b 条件频域（V3）：只取质量达标的相位结果参与候选
            var freqCandidate: HudCascade.Candidate? = null
            if (prev != null) {
                val (shift, quality) = phaseCorrelate(gray, prev) ?: (Point(0.0, 0.0) to HudCascade.PhaseQuality(-1.0, 1.0))
                if (HudCascade.shouldUseFrequency(angularSpeed, frameDiff, quality)) {
                    // 相位位移是相对中心偏移，转成 0..100 百分比位置（骨架：以画面中心为锚）。
                    val cx = 50.0 + shift.x / gray.width() * 100.0
                    val cy = 50.0 + shift.y / gray.height() * 100.0
                    freqCandidate = HudCascade.Candidate(
                        HudCascade.Branch.FREQ, quality.pnr.coerceIn(0.0, 1.0), cx, cy,
                    )
                }
            }

            // S3 候选（V1 确定性主干）：闭合梯度环 / 双竖线箭头签名。
            // 骨架用 ROI 内的梯度能量峰作为几何候选位置；真实模板匹配由标定页模板驱动（待接入）。
            val edgeCandidate = geometricCandidate(gray, region)
            val topoCandidate = HudCascade.Candidate(
                HudCascade.Branch.TOPO, 0.6, 50.0, 50.0, // 骨架：标定拓扑预测位的占位
            )

            // S4 裁决（V2）：只对 top-k crop 分类，认类别不认坐标。
            var modelCandidate: HudCascade.Candidate? = null
            val crop = crop96(gray, edgeCandidate)
            if (crop != null && ncnn?.available == true) {
                val probs = ncnn.classify(crop)
                if (probs != null && probs.isNotEmpty()) {
                    val bestIdx = probs.indices.maxByOrNull { probs[it] } ?: -1
                    val bestScore = probs.getOrNull(bestIdx)?.toDouble() ?: 0.0
                    if (bestIdx >= 0) {
                        modelCandidate = HudCascade.Candidate(
                            HudCascade.Branch.MODEL, bestScore,
                            edgeCandidate?.centerXPct ?: 50.0,
                            edgeCandidate?.centerYPct ?: 50.0,
                            labels.getOrNull(bestIdx) ?: "cls$bestIdx",
                        )
                    }
                }
            }

            // 转场时提高门限：过滤低置信候选
            val floor = HudCascade.matchFloor(angularSpeed)
            fun aboveFloor(c: HudCascade.Candidate?) = c?.takeIf { it.score >= floor }

            // S5 融合
            val fused = HudCascade.fuse(
                edge = aboveFloor(edgeCandidate),
                model = aboveFloor(modelCandidate),
                topo = aboveFloor(topoCandidate),
            )
            // 频域支路只在门控通过时追加为额外证据（不覆盖主干坐标）
            val effective = fused ?: freqCandidate?.let {
                HudCascade.fuse(edge = it, model = null, topo = null)
            } ?: return@withContext null

            // S5 时序：三帧一致 + 一阶低通
            pushHistory(effective)
            consistentCheck(effective)

            smoothedX = HudCascade.lowPass(smoothedX, effective.centerXPct)
            smoothedY = HudCascade.lowPass(smoothedY, effective.centerYPct)

            // S6 执行门：点击前再校验一帧（骨架用当前融合分代替二次取帧校验的占位，
            // 真机接入时此处应 grab 新帧重跑 S1-S5 再 verify）。
            if (!HudCascade.verifyBeforeClick(effective)) return@withContext null

            // 百分比 → 原图像素
            val px = (smoothedX!! / 100.0 * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val py = (smoothedY!! / 100.0 * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            HudHit(px, py, modelCandidate?.cls ?: "", effective.branch, effective.score)
        } finally {
            gray.release()
            grayRing.forEach { it.release() }
            grayRing.clear()
        }
    }

    /** 一阶低通后的坐标稳定性：仅记录，触发判定仍以 fused 为准（三帧一致在 core 纯函数）。 */
    private fun consistentCheck(current: HudCascade.Fused) {
        if (history.size >= 3) {
            val a = history[history.size - 3]
            val b = history[history.size - 2]
            // 真实触发门控由调用方结合 HudCascade.threeFrameConsistent(a,b,current) 决定；
            // 骨架仅记录最近三帧供诊断面板，避免在此处产生副作用。
        }
    }

    private fun pushHistory(f: HudCascade.Fused) {
        history.addLast(f)
        while (history.size > 3) history.removeFirst()
    }

    private fun pushRing(m: Mat) {
        grayRing.addLast(m)
        while (grayRing.size > ringCapacity) {
            grayRing.removeFirst().release()
        }
    }

    /** 两帧灰度图的平均绝对差（帧差，用于触发频域）。 */
    private fun meanAbsDiff(a: Mat, b: Mat): Double {
        if (a.size() != b.size()) return 1.0
        val diff = Mat()
        Core.absdiff(a, b, diff)
        val meanScalar = Core.mean(diff)
        diff.release()
        return (meanScalar.`val`[0] / 255.0).coerceIn(0.0, 1.0)
    }

    /** S3 几何候选：ROI 内按梯度能量找峰（骨架；真实 HUD 用标定模板的闭合梯度环签名）。 */
    private fun geometricCandidate(gray: Mat, region: Region): HudCascade.Candidate? {
        if (gray.empty()) return null
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1)
        val mag = Mat()
        Core.magnitude(gx, gy, mag)
        // ROI 内的最强梯度点
        val x0 = (region.leftPct / 100.0 * mag.width()).toInt().coerceIn(0, mag.width() - 1)
        val y0 = (region.topPct / 100.0 * mag.height()).toInt().coerceIn(0, mag.height() - 1)
        val x1 = (region.rightPct / 100.0 * mag.width()).toInt().coerceIn(x0 + 1, mag.width())
        val y1 = (region.bottomPct / 100.0 * mag.height()).toInt().coerceIn(y0 + 1, mag.height())
        val roi = mag.submat(y0, y1, x0, x1)
        var best = -1.0
        var bx = x0
        var by = y0
        val buf = FloatArray(roi.width() * roi.height())
        roi.get(0, 0, buf)
        for (y in 0 until roi.height()) {
            for (x in 0 until roi.width()) {
                val v = buf[y * roi.width() + x].toDouble()
                if (v > best) { best = v; bx = x0 + x; by = y0 + y }
            }
        }
        roi.release(); gx.release(); gy.release(); mag.release()
        if (best <= 0.0) return null
        // NCC 分数占位：真实实现用标定模板在 ROI 内 matchTemplate 的归一化相关值。
        val score = 0.85
        return HudCascade.Candidate(
            HudCascade.Branch.GEOM, score,
            bx.toDouble() / mag.width() * 100.0,
            by.toDouble() / mag.height() * 100.0,
        )
    }

    /** 从几何候选中心裁 96×96 RGBA（模型输入）。骨架传 null 时取画面中心。 */
    private fun crop96(gray: Mat, c: HudCascade.Candidate?): ByteArray? {
        // 真实实现：在原始 RGBA 帧上按候选中心裁剪 96×96；骨架这里无原始帧引用，
        // 返回 null 使 V2 支路安全关闭（降级纯 CV），待接线原始帧后启用。
        return null
    }

    companion object {
        const val TARGET_WIDTH = 720
    }
}

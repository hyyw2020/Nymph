// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import dev.nymph.auto.core.video.TapCandidateDetector
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 从用户导入的 mp4 抽帧（v2.7.0）。
 *
 * ## 权限契约
 *
 * 只读**用户通过系统文件选择器选中的本地视频**，用 framework 自带的
 * [MediaMetadataRetriever] 解码帧。零 `MediaProjection`、零 `VirtualDisplay`、
 * 零录屏权限、零 `INTERNET`，也不与用户正在进行的系统录屏冲突。
 *
 * ## 能力边界（未验证项）
 *
 * `getFrameAtTime(OPTION_CLOSEST)` 取的是最近可解码帧，时间戳有帧级误差，
 * 关键帧稀疏的视频可能漏掉短促点击。因此本类只产出**候选触点**，必须经
 * 用户在时间轴上确认（见 VideoImportDialog），不宣称「自动总结点击特征」。
 * 抽帧统一降采样到 [targetW] 宽以控制内存；截模板时再按需取一次全分辨率帧。
 */
object VideoFrameExtractor {

    data class Result(
        val frames: List<TapCandidateDetector.FrameSample>,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        /**
         * v2.9.47：关键帧时间表（证据①）。老链路不消费，缺省空表；
         * 新链路传给 [TapCandidateDetector.detectWithEvidence] 做稀疏化块匹配。
         */
        val keyframeSchedule: dev.nymph.auto.core.video.TapEvidence.KeyframeSchedule =
            dev.nymph.auto.core.video.TapEvidence.KeyframeSchedule(),
    )

    /**
     * 按固定间隔抽降采样灰度帧，供 [TapCandidateDetector] 做帧差检测。
     * 返回 null = 视频无法读取/无有效帧。
     */
    fun sampleFrames(
        context: Context,
        uri: Uri,
        intervalMs: Long = 100L,
        maxFrames: Int = 300,
        targetW: Int = 180,
    ): Result? {
        val r = MediaMetadataRetriever()
        return try {
            runCatching { r.setDataSource(context.applicationContext, uri) }
                .getOrNull() ?: return null
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: return null
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (duration <= 0L) return null

            val frames = ArrayList<TapCandidateDetector.FrameSample>()
            var t = 0L
            var sampledW = 0
            var sampledH = 0
            // v2.9.35：坐标换算的基准必须是「实际解码帧」的宽高，而不是元数据里的
            // 编码宽高。旋转 90°/270° 的 mp4 其 METADATA_KEY_VIDEO_WIDTH/HEIGHT
            // 与实际帧宽高互换，用元数据会让 templateAt 的百分比坐标整体错位。
            // 取第一张有效帧的真实宽高作为 Result 基准，与 templateAt 同一坐标系。
            var realW = 0
            var realH = 0
            while (t <= duration && frames.size < maxFrames) {
                val bmp = runCatching {
                    r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()
                if (bmp != null && !bmp.isRecycled) {
                    // v2.9.4：异常解码帧可能宽/高为 0，旧代码会整数除零、
                    // createScaledBitmap(0 宽) 抛异常并连带泄漏这一帧。
                    if (bmp.width <= 0 || bmp.height <= 0) {
                        bmp.recycle()
                    } else {
                        if (realW == 0) { realW = bmp.width; realH = bmp.height }
                        val tw = targetW.coerceAtMost(bmp.width).coerceAtLeast(1)
                        val th = (bmp.height.toLong() * tw / bmp.width).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                        sampledW = tw
                        sampledH = th
                        // v2.9.48：复用这张已解码的彩色缩略帧压 JPEG（零二次解码），
                        // 供候选列表直观定位。必须在 grayOf/recycle 之前取，取完即释放。
                        runCatching { compressThumb(uri, frames.size, scaled) }
                        frames += TapCandidateDetector.FrameSample(t, tw, th, grayOf(scaled))
                        // createScaledBitmap 在目标尺寸等于源尺寸时直接返回 bmp 本身，
                        // 不加身份判断会对同一对象 recycle 两次。
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                    }
                }
                t += intervalMs.coerceAtLeast(16L)
            }
            if (frames.size < 2) return null
            Result(
                frames, duration,
                if (realW > 0) realW else if (vw > 0) vw else sampledW,
                if (realH > 0) realH else if (vh > 0) vh else sampledH,
            )
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * v2.9.47：基于关键帧时间表的**稀疏化采样**（B 通道证据①，零额外解码预算）。
     *
     * 与 [sampleFrames] 的区别只在「采样时刻如何选」：
     *  - 关键帧密集区（用户交互高发区）用 [denseIntervalMs] 密采；
     *  - 其余区间用 [sparseIntervalMs] 只取代表帧 → 解码量降一个数量级。
     * 解码/降采样/坐标基准逻辑与 [sampleFrames] 完全一致。
     *
     * 空时间表（读取失败 / 老链路）退化为固定 [intervalMs]，**逐字等价 sampleFrames**（零回归）。
     * 时间表随 [Result.keyframeSchedule] 回传，供 core 的 detectWithEvidence 消费。
     */
    fun sampleFramesAdaptive(
        context: Context,
        uri: Uri,
        schedule: dev.nymph.auto.core.video.TapEvidence.KeyframeSchedule =
            dev.nymph.auto.core.video.TapEvidence.KeyframeSchedule(),
        intervalMs: Long = 100L,
        denseIntervalMs: Long = 50L,
        sparseIntervalMs: Long = 200L,
        maxFrames: Int = 300,
        targetW: Int = 180,
    ): Result? {
        if (schedule.timesUs.isEmpty()) {
            // 零回归：无时间表直接走原固定间隔链路。
            return sampleFrames(context, uri, intervalMs, maxFrames, targetW)?.copy(keyframeSchedule = schedule)
        }
        val r = MediaMetadataRetriever()
        return try {
            runCatching { r.setDataSource(context.applicationContext, uri) }.getOrNull() ?: return null
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: return null
            if (duration <= 0L) return null

            // 构造稀疏化采样时刻表：关键帧 ±150ms 密采，其余稀疏。
            val times = ArrayList<Long>()
            var t = 0L
            while (t <= duration && times.size < maxFrames * 2) {
                times += t
                val dense = dev.nymph.auto.core.video.TapEvidence.isDenseRegion(t, schedule, 150L)
                t += if (dense) denseIntervalMs.coerceAtLeast(16L) else sparseIntervalMs.coerceAtLeast(16L)
            }

            val frames = ArrayList<TapCandidateDetector.FrameSample>()
            var sampledW = 0
            var sampledH = 0
            var realW = 0
            var realH = 0
            for (ts in times) {
                if (frames.size >= maxFrames) break
                val bmp = runCatching {
                    r.getFrameAtTime(ts * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()
                if (bmp != null && !bmp.isRecycled) {
                    if (bmp.width <= 0 || bmp.height <= 0) {
                        bmp.recycle()
                    } else {
                        if (realW == 0) { realW = bmp.width; realH = bmp.height }
                        val tw = targetW.coerceAtMost(bmp.width).coerceAtLeast(1)
                        val th = (bmp.height.toLong() * tw / bmp.width).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                        sampledW = tw
                        sampledH = th
                        // v2.9.48：稀疏采样同样复用彩色帧压缩略图（零二次解码）。
                        runCatching { compressThumb(uri, frames.size, scaled) }
                        frames += TapCandidateDetector.FrameSample(ts, tw, th, grayOf(scaled))
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                    }
                }
            }
            if (frames.size < 2) return null
            Result(
                frames, duration,
                if (realW > 0) realW else sampledW,
                if (realH > 0) realH else sampledH,
                schedule,
            )
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * 在指定时刻取一张全分辨率帧，按百分比坐标裁出模板并转 base64 PNG。
     * 模板用于生成 CLICK_IMAGE 特征动作；失败返回 null（构建器会降级为坐标点击）。
     */
    fun templateAt(
        context: Context,
        uri: Uri,
        tMs: Long,
        xPct: Double,
        yPct: Double,
        radiusPx: Int = 48,
    ): String? {
        val r = MediaMetadataRetriever()
        return try {
            runCatching { r.setDataSource(context.applicationContext, uri) }
                .getOrNull() ?: return null
            val bmp = runCatching {
                r.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull() ?: return null
            // v2.9.4：0 尺寸帧会让 coerceIn(0, w-1) 抛空区间异常。
            if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
                bmp.recycle()
                return null
            }
            val cx = (bmp.width * xPct / 100.0).toInt().coerceIn(0, bmp.width - 1)
            val cy = (bmp.height * yPct / 100.0).toInt().coerceIn(0, bmp.height - 1)
            val l = (cx - radiusPx).coerceAtLeast(0)
            val t = (cy - radiusPx).coerceAtLeast(0)
            val w = (cx + radiusPx).coerceAtMost(bmp.width) - l
            val h = (cy + radiusPx).coerceAtMost(bmp.height) - t
            if (w < 4 || h < 4) { bmp.recycle(); return null }
            val cut = Bitmap.createBitmap(bmp, l, t, w, h)
            val bos = ByteArrayOutputStream()
            cut.compress(Bitmap.CompressFormat.PNG, 90, bos)
            // createBitmap 在裁剪区覆盖整帧时可能返回 bmp 本身，按身份判断避免双回收。
            if (cut !== bmp) cut.recycle()
            bmp.recycle()
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * v2.9.48：复用已解码的彩色缩略帧压一张 JPEG 进瞬态缓存（零二次解码）。
     * [frameIndex] 即该帧在 frames 列表中的下标，与候选的 frameIndex 同源。
     */
    private fun compressThumb(uri: Uri, frameIndex: Int, scaled: Bitmap) {
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 78, bos)
        FramePreviewCache.putThumb(uri.toString(), frameIndex, bos.toByteArray())
    }

    /**
     * v2.9.48：按需放大候选所在帧——用户点开缩略图时调用。
     *
     * 优先命中 [FramePreviewCache] 的 LRU（只留 4 张）；未命中才 MediaMetadataRetriever
     * seek 单帧缩放到约 [targetW] 压 JPEG。放大图不常驻、Dialog dismiss 时随缓存清空。
     * 用 [frameIndex] 作缓存 key、用 [tMs] 作 seek 时刻（自适应采样间隔不固定，
     * 不能靠 tMs 推算 frameIndex，反过来用候选自带的 frameIndex 才不会错位）。
     *
     * 准星坐标由 UI 按 (x%, y%) 直接映射到本图——本图与缩略帧同源同比例，
     * 无 center-crop，故不存在缩放/裁剪偏移问题。
     */
    fun frameFullAt(
        context: Context,
        uri: Uri,
        tMs: Long,
        frameIndex: Int,
        targetW: Int = 720,
    ): ByteArray? {
        val key = uri.toString()
        FramePreviewCache.getFull(key, frameIndex)?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            runCatching { r.setDataSource(context.applicationContext, uri) }.getOrNull() ?: return null
            val bmp = runCatching {
                r.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull() ?: return null
            if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
                runCatching { bmp.recycle() }
                return null
            }
            val tw = targetW.coerceAtMost(bmp.width).coerceAtLeast(1)
            val th = max(1, (bmp.height.toLong() * tw / bmp.width).toInt())
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, bos)
            val jpeg = bos.toByteArray()
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            FramePreviewCache.putFull(key, frameIndex, jpeg)
            jpeg
        } finally {
            runCatching { r.release() }
        }
    }

    private fun grayOf(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val rr = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val bb = c and 0xFF
            out[i] = (rr * 0.299 + gg * 0.587 + bb * 0.114).toInt()
        }
        return out
    }
}

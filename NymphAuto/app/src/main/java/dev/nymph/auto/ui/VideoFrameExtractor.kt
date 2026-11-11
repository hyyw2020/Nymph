// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import dev.nymph.auto.core.video.TapCandidateDetector
import java.io.ByteArrayOutputStream

/**
 * 从用户导入的 mp4 抽帧（v2.7.0）。
 *
 * ## 权限契约
 *
 * 只读**用户通过系统文件选择器选中的本地视频**，用 framework 自带的
 * [MediaMetadataRetriever] 解码帧。零 `MediaProjection`、零 `VirtualDisplay`、
 * 零录屏权限、零 `INTERNET`，也不与用户正在进行的系统录屏冲突。
 *
 * ## 诚实边界
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
                        val tw = targetW.coerceAtMost(bmp.width).coerceAtLeast(1)
                        val th = (bmp.height.toLong() * tw / bmp.width).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                        sampledW = tw
                        sampledH = th
                        frames += TapCandidateDetector.FrameSample(t, tw, th, grayOf(scaled))
                        scaled.recycle()
                        bmp.recycle()
                    }
                }
                t += intervalMs.coerceAtLeast(16L)
            }
            if (frames.size < 2) return null
            Result(frames, duration, if (vw > 0) vw else sampledW, if (vh > 0) vh else sampledH)
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
            cut.recycle()
            bmp.recycle()
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
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

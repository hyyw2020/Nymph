// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.nymph.auto.core.video.TapEvidence

/**
 * 编解码器元数据稀疏化（v2.9.47，证据①，零解码预算）。
 *
 * 用 [MediaExtractor] 遍历视频 track 的 sample，**只读元数据**、不解码任何像素：
 *  `SAMPLE_FLAG_SYNC` 标出关键帧、`getSampleTime()` 给出其时间戳（微秒）。
 * 录屏编码器已经替我们标出「哪里发生了大变化」——关键帧密集区正是交互高发区，
 * 只在附近密集采样，其余区间取代表帧，解码量降一个数量级。
 *
 * ## 权限契约
 *
 * 只读用户通过系统文件选择器选中的本地 Uri（`setDataSource(context, uri, null)`），
 * 零 `INTERNET`、零 `MediaProjection`、零录屏权限，与现有抽帧链路一致。
 *
 * ## 失败退化（不变式：任一通道缺席不阻断）
 *
 * 读取失败 / 无关键帧 / 非视频文件一律返回**空时间表**，core 侧
 * [TapEvidence.isDenseRegion] 见空表退化为「处处密集」= 均匀采样（老行为零回归）。
 */
object KeyframeTimeline {

    /**
     * 读关键帧时间表。返回 null = 读取失败（调用方按空表处理）。
     * @param maxKeyframes 时间表上限，防止超长视频产出超大表。
     */
    fun read(
        context: Context,
        uri: Uri,
        maxKeyframes: Int = 4096,
    ): TapEvidence.KeyframeSchedule? {
        val extractor = MediaExtractor()
        return try {
            runCatching { extractor.setDataSource(context.applicationContext, uri, null) }
                .getOrNull() ?: return null
            var durationMs = 0L
            var selected = false
            for (i in 0 until extractor.trackCount) {
                val fmt: MediaFormat = extractor.getTrackFormat(i)
                val mime = runCatching { fmt.getString(MediaFormat.KEY_MIME) }.getOrNull() ?: ""
                if (!mime.startsWith("video/")) continue
                durationMs = runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L) / 1000L
                extractor.selectTrack(i)
                selected = true
                break
            }
            if (!selected) return null
            val timesUs = ArrayList<Long>()
            while (timesUs.size < maxKeyframes) {
                val t = extractor.sampleTime      // 微秒
                if (t < 0L) break                 // -1 = 无更多 sample
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) timesUs += t
                if (!extractor.advance()) break
            }
            TapEvidence.KeyframeSchedule(timesUs, durationMs)
        } finally {
            runCatching { extractor.release() }
        }
    }
}

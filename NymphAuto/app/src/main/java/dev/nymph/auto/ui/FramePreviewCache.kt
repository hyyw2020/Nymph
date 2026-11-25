// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import java.util.LinkedHashMap

/**
 * 候选缩略图 / 放大图的**瞬态**缓存（v2.9.48，零新依赖、不落盘）。
 *
 * ## 为什么要瞬态而非放进 VideoFrameExtractor.Result
 *
 * Result 只活在分析作用域，但把几十张 JPEG 塞进它的公开字段会诱导调用方
 * 误以为可持久化。缩略图是纯呈现数据，生命周期必须严格跟随对话框：
 * uri 切换 / Dialog dismiss 即清空，绝不进入脚本 JSON 或源码包。
 *
 * ## 两级容量
 *
 *  - 缩略图：抽帧时**复用已解码的彩色帧**压一张约 180px 宽的 JPEG（零二次解码），
 *    按 frameIndex 索引，最多 [MAX_THUMBS] 张（抽帧上限就是 300）。
 *  - 放大图：用户点开某候选时按需 MediaMetadataRetriever seek 单帧压 720px，
 *    LRU 只留 [MAX_FULLS] 张，未命中回退解码——不常驻 53 张全图（会爆内存）。
 *
 * 所有访问 @Synchronized：抽帧在 Dispatchers.Default、UI 在 Main，交叉访问要串行化。
 */
object FramePreviewCache {

    private const val MAX_THUMBS = 320
    private const val MAX_FULLS = 4

    private data class Key(val uri: String, val index: Int)

    private val thumbs = object : LinkedHashMap<Key, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ByteArray>?): Boolean = size > MAX_THUMBS
    }

    private val fulls = object : LinkedHashMap<Key, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ByteArray>?): Boolean = size > MAX_FULLS
    }

    @Synchronized
    fun putThumb(uri: String, frameIndex: Int, jpeg: ByteArray) {
        thumbs[Key(uri, frameIndex)] = jpeg
    }

    @Synchronized
    fun getThumb(uri: String, frameIndex: Int): ByteArray? = thumbs[Key(uri, frameIndex)]

    @Synchronized
    fun getFull(uri: String, frameIndex: Int): ByteArray? = fulls[Key(uri, frameIndex)]

    @Synchronized
    fun putFull(uri: String, frameIndex: Int, jpeg: ByteArray) {
        fulls[Key(uri, frameIndex)] = jpeg
    }

    /** 切换视频 / 关闭对话框时清空，防止跨视频串图。 */
    @Synchronized
    fun clear() {
        thumbs.clear()
        fulls.clear()
    }
}

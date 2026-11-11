// SPDX-License-Identifier: MIT
package dev.nymph.auto.recorder

import android.graphics.Bitmap
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.recorder.AnchorSelector
import dev.nymph.auto.core.recorder.RecordCompiler
import dev.nymph.auto.core.recorder.RecordEvent
import dev.nymph.auto.core.recorder.RecordEventKind
import dev.nymph.auto.core.recorder.RecordNode
import dev.nymph.auto.core.recorder.RecordReport
import dev.nymph.auto.core.model.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 录制会话：事件收集 + 后台异步增强 + 编译保存。
 *
 * ## 异步增强（不阻塞无障碍回调）
 *
 * 无障碍回调只投递事件即返回（见 [RecorderBus]）。本会话用单个后台协程
 * 串行消费「需要增强」的事件：
 *  - 截取无障碍 `takeScreenshot()` 结果，按节点 bounds 裁小图做图像模板锚点
 *  - 离线 OCR 在锚点附近找文字，补「画布文字」锚点
 * 增强结果按 `seq` 回填，即使截图较慢也不会错序。
 *
 * 增强是**尽力而为**：截图失败 / OCR 不可用时静默跳过，节点 ID 与文字
 * 锚点依旧可用，录制不中断。这样也保证「不和系统录屏抢权限」——
 * 截图通道始终是无障碍的。
 */
class RecordingSession(
    private val scope: CoroutineScope,
    private val enhancer: Enhancer?,
    private val onEventCount: (Int) -> Unit,
) {

    /** 增强能力注入（由服务装配；可为 null = 只录语义锚点）。 */
    interface Enhancer {
        /** 无障碍截图（非 MediaProjection）；失败返回 null。 */
        suspend fun screenshot(): Bitmap?

        /**
         * 离线 OCR：返回锚点 (x,y) 附近识别到的文字；不可用时返回 null。
         * 具体实现见 app 层 OcrEngine 通道。
         */
        suspend fun ocrNear(bmp: Bitmap, x: Int, y: Int): String?
    }

    private val lock = Any()
    private var events = mutableListOf<RecordEvent>()
    private val enhanceQueue = Channel<RecordEvent>(Channel.UNLIMITED)
    private var enhanceJob: Job? = null
    @Volatile
    private var active = false

    /** 后台增强队列排空信号：finish() 据此等待已入队事件处理完，避免末尾增强丢失。 */
    private var drained = CountDownLatch(1)

    fun start() {
        synchronized(lock) { events = mutableListOf(); active = true }
        drained = CountDownLatch(1)
        enhanceJob = scope.launch(Dispatchers.Default) {
            try {
                // 刻意不检查 active：finish() 会 close() 队列，迭代器会把已入队的
                // 事件全部消费完才自然结束。旧版用 active 提前 break + cancel，
                // 导致「停止并保存」时排队中的 OCR/模板增强 100% 丢失。
                for (e in enhanceQueue) {
                    runCatching { enhance(e) }
                }
            } finally {
                drained.countDown()
            }
        }
    }

    /** 由 RecorderBus 的 sink 调用：只入队、立即返回。 */
    fun accept(e: RecordEvent) {
        if (!active) return
        val count = synchronized(lock) {
            events.add(e)
            events.size
        }
        // v1.2.1：首条与每 10 条打一次进度日志。无悬浮窗权限时这是用户
        // 在「运行日志」里唯一能确认「录制确实在工作」的信号，故必须节流但不可省略。
        if (count == 1 || count % 10 == 0) {
            RunLog.i("Record", "已记录 $count 个事件（最新 ${e.kind.name}）")
        }
        runCatching { onEventCount(count) }
        // 只为「语义锚点不足」的点击做视觉增强，避免每个事件都截图
        if (needsEnhance(e) && enhancer != null) {
            enhanceQueue.trySend(e)
        }
    }

    private fun needsEnhance(e: RecordEvent): Boolean {
        if (e.kind != RecordEventKind.CLICK && e.kind != RecordEventKind.LONG_CLICK) return false
        val n = e.node
        val hasStrongAnchor = n != null &&
            (n.viewId.isNotBlank() || AnchorSelector.pickNodeLabel(n).isNotBlank())
        return !hasStrongAnchor && e.hasScreen
    }

    /** 后台执行：截图 → 裁剪模板 → OCR → 按 seq 回填。 */
    private suspend fun enhance(e: RecordEvent) {
        val eng = enhancer ?: return
        val bmp = eng.screenshot() ?: return
        try {
            val n = e.node ?: RecordNode()
            val b = n.bounds
            val template = if (b.valid) cropTemplate(bmp, b.left, b.top, b.right, b.bottom) else ""
            val ocr = if (e.hasScreen) runCatching { eng.ocrNear(bmp, e.x, e.y) }.getOrNull().orEmpty() else ""
            val enriched = e.copy(templateB64 = template, ocrText = ocr)
            synchronized(lock) {
                val idx = events.indexOfFirst { it.seq == e.seq }
                if (idx >= 0) events[idx] = enriched
            }
        } finally {
            runCatching { if (!bmp.isRecycled) bmp.recycle() }
        }
    }

    /** 按节点 bounds 向外扩 12px 裁剪，限制最长边 160px，编码为 base64 PNG。 */
    private fun cropTemplate(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int): String {
        val pad = 12
        val left = (l - pad).coerceAtLeast(0)
        val top = (t - pad).coerceAtLeast(0)
        val right = (r + pad).coerceAtMost(bmp.width)
        val bottom = (b + pad).coerceAtMost(bmp.height)
        if (right <= left || bottom <= top) return ""
        val w = right - left
        val h = bottom - top
        return try {
            val scale = minOf(1.0, 160.0 / maxOf(w, h))
            val cut = Bitmap.createBitmap(bmp, left, top, w, h)
            val out = if (scale < 1.0) {
                Bitmap.createScaledBitmap(cut, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
                    .also { if (it !== cut) cut.recycle() }
            } else cut
            val bytes = java.io.ByteArrayOutputStream().use { bos ->
                out.compress(Bitmap.CompressFormat.PNG, 90, bos)
                bos.toByteArray()
            }
            if (out !== bmp) runCatching { out.recycle() }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            RunLog.w("Record", "模板裁剪失败：${t.message}")
            ""
        }
    }

    /** 撤销最后一条事件。 */
    fun undo(): Boolean = synchronized(lock) {
        if (events.isEmpty()) false else { events.removeAt(events.lastIndex); true }
    }

    /**
     * 结束并编译，返回 (脚本, 报告)；不在此处落盘，交给调用方 ScriptStore.save。
     *
     * 关闭增强队列后**有限等待**后台协程排空再取快照。旧版立刻 cancel + 取快照，
     * 会让「停止并保存」时仍在排队、且最依赖 OCR/模板的点击丢失增强结果
     * （退化成坐标锚点，甚至因无锚点被跳过）。等待有上限，慢 OCR 不会卡住停止操作。
     */
    fun finish(name: String, compiler: RecordCompiler = RecordCompiler()): Pair<Script, RecordReport> {
        active = false
        enhanceQueue.close()
        val drainedInTime = runCatching {
            drained.await(FINISH_DRAIN_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!drainedInTime) enhanceJob?.cancel()
        val snapshot = synchronized(lock) { events.toList() }
        val result = compiler.compile(name, snapshot)
        RunLog.i("Record", "录制结束：${result.report.oneLine()}")
        return result.script to result.report
    }

    fun count(): Int = synchronized(lock) { events.size }

    /** 事件流快照，供明文审计留档（不含任何二进制/加密内容）。 */
    fun eventsForAudit(): List<RecordEvent> = synchronized(lock) { events.toList() }

    companion object {
        /** 结束录制时等待后台增强排空的上限（毫秒）；远低于 Service ANR 阈值。 */
        private const val FINISH_DRAIN_MS = 1200L
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import java.util.Locale

/**
 * **场景驱动监视循环的帧同步策略单一真源**（v2.9.28，core 纯 JVM，零 Android 依赖）。
 *
 * ## 问题基线
 *
 * 场景驱动是「场景出现即触发」的事件循环，不是顺序链的「等待固定目标」。
 * v2.9.15 起 `runSceneDriven` 每轮一次抓帧、N 条规则共享这一帧（一轮一帧），
 * 但轮与轮之间复用了 [WaitGate] 的慢场景常量：本轮无触发意图时固定休眠
 * `DEFAULT_POLL_MS`（300ms）。同时 `VisionPipeline` 的帧缓存 TTL 为 400ms，
 * 即便缩短休眠，连续多轮仍会复用同一张位图。两者叠加后，实际判定节拍被锁在
 * 数百毫秒量级：短暂出现的场景可能整个落在两次判定之间，事后也拿不到当轮画面。
 *
 * ## 本版收口
 *
 * - [frameIntervalMs] 把监视节拍绑定到识别调参的帧同步档位；旧版回滚档
 *   （`LEGACY_100` / `LEGACY_300`）只服务于顺序链的条件等待与找图重试，
 *   不再让场景驱动退化成低频轮询。自定义档位仍被尊重（显式慢速是用户选择）。
 * - [delayFor] 采用固定步长：本轮抓帧加匹配已耗满目标间隔时不追加休眠，
 *   由截图本身的耗时构成天然节拍；快于目标时才补足差值，避免无意义空转。
 * - [due] / [heartbeatLine] 为提频后的可观测性提供有界节流：判定仍逐帧执行，
 *   完整明细按「状态变化 + 周期心跳」写入日志，心跳行携带本周期实际判定帧数，
 *   使逐帧判定可被事后核对，同时不撑爆运行日志环形缓冲。
 *
 * ## 能力边界
 *
 * 纯函数只给出节拍数值，不读系统时钟、不调用截图。真实帧率仍受无障碍
 * `takeScreenshot()` 端到端延迟与设备刷新率约束；本策略保证「有帧即判定、
 * 不因固定休眠漏判」，不宣称达到固定的物理帧率。
 */
object SceneFramePolicy {

    /** 默认帧同步间隔，与 [RecognitionTuning] 的 `FRAME_33` 档一致。 */
    const val DEFAULT_FRAME_MS = 33L

    /** 低于 8ms 会退化成无意义空转（截图回调跟不上）。与识别调参下限对齐。 */
    const val MIN_FRAME_MS = 8L

    /** 上限沿用识别调参的合法区间，自定义慢速档位不被强制提速。 */
    const val MAX_FRAME_MS = 2000L

    /** 逐帧明细的周期心跳间隔：状态不变时仍保留可核对的帧数证据。 */
    const val HEARTBEAT_MS = 500L

    /** 等待浮层刷新间隔：避免每帧更新界面文本。 */
    const val STATUS_MS = 200L

    /**
     * 场景驱动监视的目标帧间隔（毫秒）。
     *
     * `LEGACY_100` / `LEGACY_300` 是顺序链「退回旧版调度」的回滚档位，
     * 与场景驱动事件循环无关；若沿用会把监视压回低频轮询，故统一取默认帧同步档。
     * 其余档位（含 `CUSTOM`）沿用调参值，并钳制到合法区间。
     */
    fun frameIntervalMs(pollMode: PollMode, tunedIntervalMs: Long): Long {
        val base = when (pollMode) {
            PollMode.LEGACY_100, PollMode.LEGACY_300 -> DEFAULT_FRAME_MS
            else -> tunedIntervalMs
        }
        return base.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
    }

    /**
     * 固定步长补足值：本轮已耗时 [spentMs] 时，还需休眠多少毫秒才进入下一轮。
     *
     * 抓帧与匹配耗时达到或超过目标间隔时返回 0 —— 此时不应再叠加休眠，
     * 否则每轮总时长变成「实际耗时 + 固定间隔」，判定反而更慢。
     */
    fun delayFor(spentMs: Long, targetMs: Long): Long {
        val target = targetMs.coerceIn(0L, MAX_FRAME_MS)
        val spent = spentMs.coerceAtLeast(0L)
        return (target - spent).coerceAtLeast(0L)
    }

    /**
     * 周期任务是否到期。[lastMs] 为 0 表示尚未发生过，首轮恒为 true。
     * 时钟回拨（nowMs 小于 lastMs）时按到期处理，避免节流永久卡死。
     */
    fun due(nowMs: Long, lastMs: Long, periodMs: Long): Boolean {
        val period = periodMs.coerceAtLeast(1L)
        if (lastMs <= 0L || nowMs < lastMs) return true
        return nowMs - lastMs >= period
    }

    /**
     * 心跳行：把「本周期实际判定帧数 / 每帧参与判定的规则数 / 平均帧间隔」
     * 写成可核对的一行日志，避免用日志条数充当逐帧证据。
     */
    fun heartbeatLine(frames: Long, rules: Int, periodMs: Long): String {
        val avg = if (frames > 0) periodMs.toDouble() / frames else 0.0
        return "逐帧判定心跳：本周期 " + frames + " 帧 · 每帧 " + rules + " 条规则全判 · 平均帧间隔 " +
            String.format(Locale.US, "%.1f", avg) + "ms"
    }
}

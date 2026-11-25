// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import dev.nymph.auto.core.editor.SceneTrigger
import dev.nymph.auto.core.model.SceneFiringState
import dev.nymph.auto.core.model.SceneObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.28：场景驱动监视必须是「有帧即判定、每帧对全部规则判定」，
 * 而不是被固定轮询节拍锁在数百毫秒量级。
 *
 * 现场链路：`runSceneDriven` 在无触发意图时固定休眠 `WaitGate.DEFAULT_POLL_MS`
 * （300ms），且 `VisionPipeline` 的帧缓存 TTL 为 400ms —— 缩短休眠也不会换帧。
 * 这里锁定 core 侧的节拍策略与逐帧全判契约，防止后续把间隔改回慢场景常量，
 * 或让某条规则在某些帧被跳过判定。
 */
class SceneFramePolicyTest {

    @Test
    fun `帧同步档位直接采用调参间隔`() {
        assertEquals(33L, SceneFramePolicy.frameIntervalMs(PollMode.FRAME_33, 33L))
        assertEquals(16L, SceneFramePolicy.frameIntervalMs(PollMode.FRAME_16, 16L))
        assertEquals(50L, SceneFramePolicy.frameIntervalMs(PollMode.FRAME_50, 50L))
        assertEquals(75L, SceneFramePolicy.frameIntervalMs(PollMode.CUSTOM, 75L))
    }

    @Test
    fun `旧版回滚档不再把场景监视压成低频轮询`() {
        // LEGACY 档是顺序链「条件等待 / 找图重试」的回滚项，与场景驱动事件循环无关。
        // 沿用会把监视压回 100/300ms，短暂出现的场景整段落在两次判定之间。
        assertEquals(33L, SceneFramePolicy.frameIntervalMs(PollMode.LEGACY_100, 100L))
        assertEquals(33L, SceneFramePolicy.frameIntervalMs(PollMode.LEGACY_300, 300L))
    }

    @Test
    fun `自定义慢速档位仍被尊重并钳制到合法区间`() {
        // 显式慢速是用户选择，不被强制提速；非法值仍须收敛。
        assertEquals(300L, SceneFramePolicy.frameIntervalMs(PollMode.CUSTOM, 300L))
        assertEquals(2000L, SceneFramePolicy.frameIntervalMs(PollMode.CUSTOM, 5000L))
        assertEquals(8L, SceneFramePolicy.frameIntervalMs(PollMode.CUSTOM, 1L))
        assertEquals(8L, SceneFramePolicy.frameIntervalMs(PollMode.CUSTOM, -20L))
    }

    @Test
    fun `抓帧已耗满目标间隔时不追加休眠`() {
        // 否则每轮总时长变成「实际耗时 + 固定间隔」，判定反而更慢。
        assertEquals(0L, SceneFramePolicy.delayFor(40L, 33L))
        assertEquals(0L, SceneFramePolicy.delayFor(33L, 33L))
        assertEquals(0L, SceneFramePolicy.delayFor(180L, 33L))
    }

    @Test
    fun `快于目标时补足差值且边界收敛`() {
        assertEquals(23L, SceneFramePolicy.delayFor(10L, 33L))
        assertEquals(33L, SceneFramePolicy.delayFor(0L, 33L))
        assertEquals(33L, SceneFramePolicy.delayFor(-5L, 33L))
        assertEquals(0L, SceneFramePolicy.delayFor(10L, 0L))
        assertEquals(0L, SceneFramePolicy.delayFor(10L, -1L))
    }

    @Test
    fun `周期到期判定首轮恒写且时钟回拨不卡死`() {
        assertTrue(SceneFramePolicy.due(1000L, 0L, 500L))
        assertFalse(SceneFramePolicy.due(1499L, 1000L, 500L))
        assertTrue(SceneFramePolicy.due(1500L, 1000L, 500L))
        // 时钟回拨：按到期处理，避免节流永久不再触发
        assertTrue(SceneFramePolicy.due(999L, 1000L, 500L))
        // 周期下限 1ms，0 不会退化为除零或恒 false
        assertTrue(SceneFramePolicy.due(1001L, 1000L, 0L))
    }

    @Test
    fun `心跳行携带帧数与规则数且数值格式与地区无关`() {
        val line = SceneFramePolicy.heartbeatLine(6L, 3, 500L)
        assertTrue(line.contains("6 帧"))
        assertTrue(line.contains("3 条规则全判"))
        // 500 / 6 = 83.3；必须是小数点而非逗号（Locale.US）
        assertTrue(line.contains("83.3ms"))
        assertFalse(line.contains("83,3"))
        // 0 帧不得产生除零或 NaN
        assertTrue(SceneFramePolicy.heartbeatLine(0L, 3, 500L).contains("0.0ms"))
    }

    @Test
    fun `每帧对全部规则给出判定理由而不跳过未命中项`() {
        // 判定链本身：4 条规则里 2 命中 2 未命中，状态机必须为每一条产出理由。
        // 这是「每帧都在判断所有场景」的契约，不是只看命中项。
        val observed = listOf(
            SceneObservation(present = true),
            SceneObservation(present = false),
            SceneObservation(present = true),
            SceneObservation(present = false),
        )
        val decision = SceneTrigger.planSceneFiringStateful(
            observed, emptyList(), SceneFiringState(), 1000L, 1,
        )
        assertEquals(4, decision.reasons.size)
        for (i in 0 until 4) {
            assertTrue("第 $i 条规则必须参与本帧判定", decision.reasons.containsKey(i))
            assertTrue(decision.reasons.getValue(i).contains("状态="))
        }
        assertTrue(decision.reasons.getValue(1).contains("场景未出现"))
        assertTrue(decision.reasons.getValue(3).contains("场景未出现"))
    }
}

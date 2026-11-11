// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordabilityGuardTest {

    private fun signal(
        armed: Boolean = true,
        interaction: Int = 0,
        window: Int = 0,
        total: Int = interaction + window,
        elapsed: Long = 20_000L,
    ) = RecordabilityGuard.Signal(armed, interaction, window, total, elapsed)

    @Test
    fun `未解除待命且未超时判为等待 不误报自绘界面`() {
        assertEquals(
            RecordabilityGuard.Verdict.WAITING,
            RecordabilityGuard.diagnose(signal(armed = false, total = 0)),
        )
        assertFalse(
            RecordabilityGuard.shouldWarnDuring(
                signal(armed = false, total = 0, elapsed = 3_000L),
            ),
        )
    }

    @Test
    fun `零事件超时后即便仍在待命也要提示检查无障碍服务`() {
        val s = signal(armed = false, total = 0, elapsed = 20_000L)
        assertTrue(RecordabilityGuard.shouldWarnDuring(s))
        assertEquals(RecordabilityGuard.Verdict.NO_EVENTS, RecordabilityGuard.warningVerdict(s))
    }

    @Test
    fun `warningVerdict把零事件统一归为无障碍问题 避免矛盾指引`() {
        assertEquals(
            RecordabilityGuard.Verdict.NO_EVENTS,
            RecordabilityGuard.warningVerdict(signal(armed = true, total = 0)),
        )
        assertEquals(
            RecordabilityGuard.Verdict.SELF_DRAWN,
            RecordabilityGuard.warningVerdict(signal(interaction = 0, window = 4, total = 4)),
        )
    }

    @Test
    fun `系统一个事件都没派发判为无障碍未开启`() {
        assertEquals(
            RecordabilityGuard.Verdict.NO_EVENTS,
            RecordabilityGuard.diagnose(signal(total = 0, elapsed = 20_000L)),
        )
        assertTrue(RecordabilityGuard.shouldWarnDuring(signal(total = 0, elapsed = 20_000L)))
    }

    @Test
    fun `有窗口事件但零交互判为自绘界面 这是本次真机差评的现场`() {
        // 游戏里切页/弹窗会发 WINDOW_STATE_CHANGED，但按钮不是 View，零点击
        val s = signal(interaction = 0, window = 6, total = 6)
        assertEquals(RecordabilityGuard.Verdict.SELF_DRAWN, RecordabilityGuard.diagnose(s))
        assertTrue(RecordabilityGuard.reason(RecordabilityGuard.Verdict.SELF_DRAWN).contains("能力边界"))
    }

    @Test
    fun `只要服务在收事件且零交互就判自绘 不依赖窗口事件条数`() {
        assertEquals(
            RecordabilityGuard.Verdict.SELF_DRAWN,
            RecordabilityGuard.diagnose(signal(interaction = 0, window = 1, total = 1)),
        )
        assertEquals(
            RecordabilityGuard.Verdict.SELF_DRAWN,
            RecordabilityGuard.diagnose(signal(interaction = 0, window = 0, total = 3)),
        )
    }

    @Test
    fun `交互极少而窗口事件压倒性多判为信号不足`() {
        val s = signal(interaction = 1, window = 9, total = 10)
        assertEquals(RecordabilityGuard.Verdict.LOW_SIGNAL, RecordabilityGuard.diagnose(s))
    }

    @Test
    fun `正常收到交互事件判为可录制 不打扰`() {
        assertEquals(
            RecordabilityGuard.Verdict.RECORDABLE,
            RecordabilityGuard.diagnose(signal(interaction = 8, window = 3, total = 11)),
        )
        assertFalse(
            RecordabilityGuard.shouldWarnDuring(
                signal(interaction = 8, window = 3, total = 11),
            ),
        )
    }

    @Test
    fun `提前告警有八秒时间门槛 刚进入目标APP不打扰`() {
        val s = signal(interaction = 0, window = 3, total = 3, elapsed = 3_000L)
        assertFalse(RecordabilityGuard.shouldWarnDuring(s))
        assertTrue(RecordabilityGuard.shouldWarnDuring(s.copy(elapsedMs = 9_000L)))
    }

    @Test
    fun `替代路径文案指向真实存在的视频导入入口并说明找图与循环`() {
        val alt = RecordabilityGuard.alternative(RecordabilityGuard.Verdict.SELF_DRAWN)
        assertTrue(alt.contains("视频导入"))
        assertTrue(alt.contains("无限循环"))
        assertTrue(alt.contains("轮询等待"))
    }

    @Test
    fun `收尾指引包含根因 替代路径与系统诊断`() {
        val guidance = RecordabilityGuard.finishGuidance(
            RecordabilityGuard.Verdict.SELF_DRAWN,
            "原始事件 6 / 转换 6 / 未映射丢弃 0",
        )
        assertTrue(guidance.contains("能力边界"))
        assertTrue(guidance.contains("视频导入"))
        assertTrue(guidance.contains("原始事件 6"))
    }

    @Test
    fun `收尾toast短版不为空且比完整指引短`() {
        val short = RecordabilityGuard.finishToast(RecordabilityGuard.Verdict.SELF_DRAWN)
        val full = RecordabilityGuard.finishGuidance(RecordabilityGuard.Verdict.SELF_DRAWN, "诊断")
        assertTrue(short.isNotBlank())
        assertTrue(short.length < full.length)
        assertTrue(short.contains("视频导入"))
    }

    @Test
    fun `等待态与可录制态不产生误导性的录制中提示`() {
        assertEquals("", RecordabilityGuard.duringHint(RecordabilityGuard.Verdict.WAITING))
        assertEquals("", RecordabilityGuard.duringHint(RecordabilityGuard.Verdict.RECORDABLE))
        assertEquals("", RecordabilityGuard.alternative(RecordabilityGuard.Verdict.RECORDABLE))
    }

    @Test
    fun `判定为纯函数 同一信号重复判定结果一致`() {
        val s = signal(interaction = 0, window = 4, total = 4)
        assertEquals(RecordabilityGuard.diagnose(s), RecordabilityGuard.diagnose(s.copy()))
    }
}

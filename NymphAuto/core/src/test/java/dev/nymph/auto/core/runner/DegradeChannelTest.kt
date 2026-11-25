// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Readiness = DegradeChannel.Readiness

/** 降级通道与终止报告契约（v2.9.30）。 */
class DegradeChannelTest {

    @Test
    fun `全开即就绪`() {
        assertEquals(Readiness.READY, DegradeChannel.evaluate(true, true, true))
        assertTrue(DegradeChannel.isReady(Readiness.READY))
    }

    @Test
    fun `系统不支持优先于其它原因`() {
        assertEquals(Readiness.UNAVAILABLE, DegradeChannel.evaluate(false, false, false))
    }

    @Test
    fun `应用内拒绝先于系统开关`() {
        assertEquals(Readiness.NOT_ALLOWED, DegradeChannel.evaluate(true, false, true))
    }

    @Test
    fun `系统未启用单独成档`() {
        assertEquals(Readiness.SERVICE_DISABLED, DegradeChannel.evaluate(false, true, true))
    }

    @Test
    fun `每一档都有非空提示与指引`() {
        Readiness.values().forEach { v ->
            assertTrue("banner $v", DegradeChannel.banner(v).isNotBlank())
            if (v != Readiness.READY) {
                assertTrue("guidance $v", DegradeChannel.guidance(v).isNotBlank())
            }
        }
    }

    @Test
    fun `未就绪提示必须含具体开关位置`() {
        assertTrue(DegradeChannel.guidance(Readiness.SERVICE_DISABLED).contains("设置"))
    }

    @Test
    fun `切换失败文案不得停留在正在切换`() {
        Readiness.values().forEach { v ->
            val t = DegradeChannel.failNotice(v)
            assertFalse("$v: $t", t.contains("正在切换"))
        }
    }

    @Test
    fun `终止报告 可恢复类带 RESUME`() {
        val kinds = listOf(
            HaltReport.Kind.USER_STOP,
            HaltReport.Kind.BACKGROUND_TIMEOUT,
            HaltReport.Kind.GATE_MISMATCH,
            HaltReport.Kind.LINK_LOST,
        )
        kinds.forEach { k ->
            val rep = HaltReport.of(k)
            assertTrue("$k 应可恢复", rep.resumable)
            assertTrue(rep.actions.contains(HaltReport.HaltAction.RESUME))
            assertTrue(rep.actions.contains(HaltReport.HaltAction.VIEW_REASON))
            assertTrue(rep.actions.contains(HaltReport.HaltAction.STOP))
        }
    }

    @Test
    fun `终止报告 不可恢复类不带 RESUME`() {
        listOf(
            HaltReport.Kind.USER_HANDOVER,
            HaltReport.Kind.SCRIPT_ERROR,
            HaltReport.Kind.WATCHDOG,
        ).forEach { k ->
            val rep = HaltReport.of(k)
            assertFalse("$k 不应可恢复", rep.resumable)
            assertFalse(rep.actions.contains(HaltReport.HaltAction.RESUME))
        }
    }

    @Test
    fun `报告短文本说清停在哪`() {
        val byRule = HaltReport.of(HaltReport.Kind.GATE_MISMATCH, resumeRule = 3)
        assertTrue(byRule.shortText.contains("规则 3"))
        val byStep = HaltReport.of(HaltReport.Kind.USER_STOP, resumeStep = 7)
        assertTrue(byStep.shortText.contains("第 7 步"))
        val plain = HaltReport.of(HaltReport.Kind.WATCHDOG)
        assertEquals(plain.headline, plain.shortText)
    }

    @Test
    fun `负步号归零 不产生非法位置`() {
        val rep = HaltReport.of(HaltReport.Kind.USER_STOP, resumeStep = -5, resumeRule = -1)
        assertEquals(0, rep.resumeStep)
        assertEquals(0, rep.resumeRule)
    }

    @Test
    fun `所有终止都有主文案`() {
        HaltReport.Kind.values().forEach { k ->
            assertTrue("$k", HaltReport.headlineOf(k).isNotBlank())
        }
    }

    @Test
    fun `归还报告不带恢复入口`() {
        val rep = HaltReport.handedOver()
        assertEquals(HaltReport.Kind.USER_HANDOVER, rep.kind)
        assertFalse(rep.resumable)
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用回切失败处置策略（v2.9.33 厂商应用中止循环修复）。 */
class RestorePolicyTest {

    private val policy = RestorePolicy

    @Test
    fun `拉起失败但已进入录制应用则放行`() {
        val r = policy.decide("com.hihonor.gameassistant", "com.hihonor.gameassistant", false)
        assertEquals(RestorePolicy.Outcome.PROCEED, r.outcome)
        assertTrue(r.message.contains("继续运行"))
    }

    @Test
    fun `大小写差异仍判为已进入`() {
        val r = policy.decide("com.Example.App", "COM.EXAMPLE.APP", false)
        assertEquals(RestorePolicy.Outcome.PROCEED, r.outcome)
    }

    @Test
    fun `不在目标应用则中止并一次性点名`() {
        val r = policy.decide("com.hihonor.gameassistant", "com.android.launcher", false)
        assertEquals(RestorePolicy.Outcome.ABORT, r.outcome)
        assertTrue(r.message.contains("com.hihonor.gameassistant"))
        assertTrue(r.message.contains("请手动打开"))
        assertTrue(r.message.contains("已中止"))
    }

    @Test
    fun `中止文案不含循环触发话术`() {
        val r = policy.decide("com.demo.app", "com.other.app", false)
        assertTrue("禁止旧的循环话术", !r.message.contains("请手动打开后重试"))
    }

    @Test
    fun `位置手势在放行与中止分支都追加界面一致性提示`() {
        val proceed = policy.decide("com.demo.app", "com.demo.app", true)
        assertEquals(RestorePolicy.Outcome.PROCEED, proceed.outcome)
        assertTrue(proceed.message.contains("坐标手势"))
        assertTrue(proceed.message.contains("滚动位置"))

        val abort = policy.decide("com.demo.app", "com.other.app", true)
        assertEquals(RestorePolicy.Outcome.ABORT, abort.outcome)
        assertTrue(abort.message.contains("坐标手势"))
    }

    @Test
    fun `取不到前台包名按不在目标应用处理`() {
        assertEquals(
            RestorePolicy.Outcome.ABORT,
            policy.decide("com.demo.app", null, false).outcome,
        )
        assertEquals(
            RestorePolicy.Outcome.ABORT,
            policy.decide("com.demo.app", "  ", false).outcome,
        )
    }

    @Test
    fun `录制包名为空时中止文案退化为通用描述`() {
        val r = policy.decide("  ", "com.other.app", false)
        assertEquals(RestorePolicy.Outcome.ABORT, r.outcome)
        assertTrue(r.message.contains("录制时的应用"))
    }

    @Test
    fun `非位置手势的中止文案给出自动继续承诺`() {
        val r = policy.decide("com.demo.app", "com.other.app", false)
        assertTrue(r.message.contains("自动检测并继续"))
    }
}

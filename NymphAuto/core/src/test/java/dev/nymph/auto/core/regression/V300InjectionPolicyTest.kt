// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.simpletap.ChannelDecision
import dev.nymph.auto.core.simpletap.InjectionChannel
import dev.nymph.auto.core.simpletap.InjectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.0 注入通道降级决策回归。
 *
 * 锁死：真值模式不可用必须拒绝而非静默回退截图猜（不回退红线）；
 * 标准模式降级有明确原因；高级模式原样保留。
 */
class V300InjectionPolicyTest {

    @Test
    fun `真值模式Shizuku就绪走SHIZUKU`() {
        val d = InjectionPolicy.decide(InjectionChannel.SHIZUKU, true)
        assertTrue(d.accepted)
        assertEquals(InjectionChannel.SHIZUKU, d.channel)
    }

    @Test
    fun `真值模式但Shizuku不可用明确拒绝不回退`() {
        val d: ChannelDecision = InjectionPolicy.decide(InjectionChannel.SHIZUKU, false)
        assertFalse(d.accepted)
        assertNull(d.channel)
        assertTrue("须说明已停止回放", d.reason.contains("停止回放"))
        assertTrue("须提示不退回截图猜", d.reason.contains("截图猜"))
    }

    @Test
    fun `标准模式降级到GESTURE且有原因`() {
        val d = InjectionPolicy.decide(InjectionChannel.GESTURE, false)
        assertTrue(d.accepted)
        assertEquals(InjectionChannel.GESTURE, d.channel)
        assertTrue(d.reason.contains("标准模式"))
    }

    @Test
    fun `标准模式Shizuku就绪附带升级提示但不擅自切换`() {
        val d = InjectionPolicy.decide(InjectionChannel.GESTURE, true)
        assertEquals(InjectionChannel.GESTURE, d.channel) // 不擅自切真值
        assertTrue(d.reason.contains("可切换真值"))
    }

    @Test
    fun `高级模式原样保留LEGACY`() {
        val d = InjectionPolicy.decide(InjectionChannel.LEGACY, true)
        assertEquals(InjectionChannel.LEGACY, d.channel)
        assertTrue(d.reason.contains("取证链"))
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScenePreset] 意图层单测（core 纯 JVM）。
 *
 * 覆盖：默认意图与 v2.9.15 装配常量逐字一致、全部意图组合往返一致、维度耦合
 * 规范化、[ScenePreset.toRule] 只覆盖意图字段、非预设组合降级为 null、摘要不
 * 暴露内核参数。断言不依赖任何 Android 类型。
 */
class ScenePresetTest {

    @Test
    fun `默认意图与 v2_9_15 装配常量逐字一致`() {
        val rule = ScenePreset.toRule(ScenePreset.DEFAULT)
        assertEquals(TriggerMode.ON_APPEAR, rule.triggerMode)
        assertEquals(2, rule.presentStreak)
        assertEquals(2, rule.absentStreak)
        assertEquals(400L, rule.cooldownMs)
        assertEquals(1500L, rule.retryTimeoutMs)
    }

    @Test
    fun `默认意图可无损折叠回自身`() {
        val rule = ScenePreset.toRule(ScenePreset.DEFAULT)
        assertEquals(ScenePreset.DEFAULT, ScenePreset.fromRule(rule))
        assertTrue(ScenePreset.isPreset(rule))
    }

    @Test
    fun `全部二十七种意图组合往返一致`() {
        var n = 0
        for (t in ScenePreset.Timing.entries) {
            for (d in ScenePreset.Debounce.entries) {
                for (r in ScenePreset.Repetition.entries) {
                    val intent = ScenePreset.Intent(timing = t, debounce = d, repetition = r)
                    assertTrue("组合 $intent 往返不一致", ScenePreset.roundTrip(intent))
                    n++
                }
            }
        }
        assertEquals(27, n)
    }

    @Test
    fun `规范化消除触发时机与重复策略的维度耦合`() {
        // CONTINUOUS 蕴含 WHILE_PRESENT
        val a = ScenePreset.Intent(
            timing = ScenePreset.Timing.ON_APPEAR,
            repetition = ScenePreset.Repetition.CONTINUOUS,
        ).normalized()
        assertEquals(ScenePreset.Timing.WHILE_PRESENT, a.timing)
        assertEquals(ScenePreset.Repetition.CONTINUOUS, a.repetition)
        // WHILE_PRESENT 且不重试只能表达 CONTINUOUS
        val b = ScenePreset.Intent(
            timing = ScenePreset.Timing.WHILE_PRESENT,
            repetition = ScenePreset.Repetition.ONCE,
        ).normalized()
        assertEquals(ScenePreset.Repetition.CONTINUOUS, b.repetition)
        assertEquals(ScenePreset.Timing.WHILE_PRESENT, b.timing)
        // 其余组合保持原样
        assertEquals(ScenePreset.DEFAULT, ScenePreset.DEFAULT.normalized())
    }

    @Test
    fun `toRule 只覆盖意图字段并保留 A1 A2 A5 原值`() {
        val base = SceneRule(
            anchorTemplateW = 32,
            anchorTemplateH = 40,
            templateSrcW = 1080,
            templateSrcH = 2400,
            clickOffsetXPct = 1.5,
            clickOffsetYPct = -2.5,
            similarity = 91,
            selfTestBest = 0.93,
            locMaxDeviationPct = 9.0,
            priority = 7,
            mutexGroup = "popup",
        )
        val rule = ScenePreset.toRule(
            ScenePreset.Intent(
                timing = ScenePreset.Timing.ON_DISAPPEAR,
                debounce = ScenePreset.Debounce.STRONG,
                repetition = ScenePreset.Repetition.ONCE,
            ),
            base,
        )
        assertEquals(TriggerMode.ON_DISAPPEAR, rule.triggerMode)
        assertEquals(3, rule.presentStreak)
        assertEquals(3, rule.absentStreak)
        assertEquals(400L, rule.cooldownMs)
        assertEquals(0L, rule.retryTimeoutMs)
        // 下列字段属于模板结构 / 坐标语义 / 可观测性，必须逐字保留
        assertEquals(32, rule.anchorTemplateW)
        assertEquals(40, rule.anchorTemplateH)
        assertEquals(1080, rule.templateSrcW)
        assertEquals(2400, rule.templateSrcH)
        assertEquals(1.5, rule.clickOffsetXPct!!, 0.0)
        assertEquals(-2.5, rule.clickOffsetYPct!!, 0.0)
        assertEquals(91, rule.similarity)
        assertEquals(0.93, rule.selfTestBest, 0.0)
        assertEquals(9.0, rule.locMaxDeviationPct, 0.0)
        assertEquals(7, rule.priority)
        assertEquals("popup", rule.mutexGroup)
        assertTrue(rule.usesRelativeClick)
    }

    @Test
    fun `非预设参数组合降级为 null`() {
        assertNull(ScenePreset.fromRule(SceneRule(presentStreak = 2, absentStreak = 3)))
        assertNull(ScenePreset.fromRule(SceneRule(presentStreak = 5, absentStreak = 5)))
        assertNull(
            ScenePreset.fromRule(
                ScenePreset.toRule(ScenePreset.DEFAULT).copy(cooldownMs = 999L),
            ),
        )
        assertNull(
            ScenePreset.fromRule(
                ScenePreset.toRule(ScenePreset.DEFAULT).copy(retryTimeoutMs = 777L),
            ),
        )
        assertFalse(ScenePreset.isPreset(SceneRule(presentStreak = 2, absentStreak = 3)))
    }

    @Test
    fun `档位映射取值正确`() {
        assertEquals(1, ScenePreset.streakOf(ScenePreset.Debounce.OFF))
        assertEquals(2, ScenePreset.streakOf(ScenePreset.Debounce.STANDARD))
        assertEquals(3, ScenePreset.streakOf(ScenePreset.Debounce.STRONG))
        assertEquals(400L to 0L, ScenePreset.repetitionMs(ScenePreset.Repetition.ONCE))
        assertEquals(
            400L to 1500L,
            ScenePreset.repetitionMs(ScenePreset.Repetition.RETRY_IF_PERSISTENT),
        )
        assertEquals(400L to 0L, ScenePreset.repetitionMs(ScenePreset.Repetition.CONTINUOUS))
    }

    @Test
    fun `摘要不暴露内核参数名与数值`() {
        for (t in ScenePreset.Timing.entries) {
            for (r in ScenePreset.Repetition.entries) {
                val s = ScenePreset.Intent(timing = t, repetition = r).summary
                assertFalse(s.contains("cooldown"))
                assertFalse(s.contains("streak"))
                assertFalse(s.contains("triggerMode"))
                assertFalse(s.contains("ms"))
                assertTrue(s.isNotBlank())
            }
        }
    }

    @Test
    fun `同轮上限由调用方传入并参与往返`() {
        val k = 3
        val rule = ScenePreset.toRule(ScenePreset.DEFAULT)
        assertEquals(k, ScenePreset.fromRule(rule, k)?.maxFiringPerRound)
        assertTrue(ScenePreset.roundTrip(ScenePreset.DEFAULT, k = k))
    }
}

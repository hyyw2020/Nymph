// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.17 / D1：意图后果展开的纯 JVM 契约。 */
class ScenePresetExplanationTest {

    private val kernelTerms = listOf(
        "triggerMode", "presentStreak", "absentStreak", "cooldownMs",
        "retryTimeoutMs", "sceneMaxFiringPerRound", "priority", "mutexGroup",
    )

    @Test
    fun `默认意图展开为四维且维度标签固定`() {
        val e = ScenePreset.explain(ScenePreset.DEFAULT)
        assertEquals(4, e.consequences.size)
        assertEquals(
            listOf("触发时机", "防抖强度", "重复与重试", "同轮上限"),
            e.consequences.map { it.dimension },
        )
    }

    @Test
    fun `主层摘要不呈现内核参数名`() {
        val e = ScenePreset.explain(ScenePreset.DEFAULT)
        val mainLayer = e.headline + e.briefLine
        for (t in kernelTerms) {
            assertFalse("主层出现内核参数名 $t", t in mainLayer)
        }
    }

    @Test
    fun `高级区明细逐维给出实际参数与权衡`() {
        val e = ScenePreset.explain(ScenePreset.DEFAULT)
        assertEquals(4, e.detailText.lines().size)
        for (c in e.consequences) {
            assertTrue("缺少实际参数：${c.dimension}", c.actual.isNotBlank())
            assertTrue("缺少运行时效果：${c.dimension}", c.effect.isNotBlank())
            assertTrue("缺少权衡：${c.dimension}", c.tradeoff.isNotBlank())
            assertTrue("明细未展开：${c.dimension}", c.actual in c.detailLine)
        }
    }

    @Test
    fun `强防抖档位后果与实际写入参数一致`() {
        val intent = ScenePreset.Intent(debounce = ScenePreset.Debounce.STRONG)
        val e = ScenePreset.explain(intent)
        val debounce = e.consequences[1]
        assertEquals("强防抖", debounce.choice)
        assertEquals("presentStreak = absentStreak = 3", debounce.actual)
        assertEquals(3, ScenePreset.streakOf(ScenePreset.Debounce.STRONG))
        assertEquals(3, ScenePreset.toRule(intent).presentStreak)
        assertTrue("3 帧" in debounce.effect)
    }

    @Test
    fun `持续在场档位展开的重试参数与落盘一致`() {
        val intent = ScenePreset.Intent(repetition = ScenePreset.Repetition.CONTINUOUS)
        val e = ScenePreset.explain(intent)
        val rep = e.consequences[2]
        assertEquals("retryTimeoutMs = 0", rep.actual.substringAfter(", "))
        assertEquals(0L, ScenePreset.toRule(intent).retryTimeoutMs)
    }

    @Test
    fun `档位目录九个条目且与展开结果逐字一致`() {
        val catalog = ScenePreset.optionCatalog()
        assertEquals(9, catalog.size)
        for (t in ScenePreset.Timing.entries) {
            val fromCatalog = catalog.single { it.dimension == "触发时机" && it.choice == choiceOf(t) }
            val fromExplain = ScenePreset.explain(ScenePreset.Intent(timing = t)).consequences[0]
            assertEquals(fromCatalog, fromExplain)
        }
        for (d in ScenePreset.Debounce.entries) {
            val fromExplain = ScenePreset.explain(ScenePreset.Intent(debounce = d)).consequences[1]
            assertTrue(catalog.contains(fromExplain))
        }
    }

    @Test
    fun `二十七种组合的展开均以规范化意图为基准`() {
        var n = 0
        for (t in ScenePreset.Timing.entries) {
            for (d in ScenePreset.Debounce.entries) {
                for (r in ScenePreset.Repetition.entries) {
                    val intent = ScenePreset.Intent(timing = t, debounce = d, repetition = r)
                    val e = ScenePreset.explain(intent)
                    val normalized = intent.normalized()
                    assertEquals(4, e.consequences.size)
                    assertEquals(
                        "组合 $intent 的防抖展开不一致",
                        "presentStreak = absentStreak = ${ScenePreset.streakOf(normalized.debounce)}",
                        e.consequences[1].actual,
                    )
                    assertTrue("组合 $intent 未通过往返", ScenePreset.roundTrip(intent))
                    n++
                }
            }
        }
        assertEquals(27, n)
    }

    @Test
    fun `同轮上限由调用方展开且下界为一`() {
        val e3 = ScenePreset.explain(ScenePreset.DEFAULT, 3)
        assertEquals("sceneMaxFiringPerRound = 3", e3.consequences[3].actual)
        assertEquals("同轮最多触发 3 条", e3.consequences[3].choice)
        assertTrue("3 条" in e3.headline)
        val e0 = ScenePreset.explain(ScenePreset.DEFAULT, 0)
        assertEquals("同轮最多触发 1 条", e0.consequences[3].choice)
    }

    private fun choiceOf(t: ScenePreset.Timing): String = when (t) {
        ScenePreset.Timing.ON_APPEAR -> "画面出现时"
        ScenePreset.Timing.WHILE_PRESENT -> "画面在场期间"
        ScenePreset.Timing.ON_DISAPPEAR -> "画面消失时"
    }
}

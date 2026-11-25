// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.editor.SceneTrigger
import dev.nymph.auto.core.model.SceneFiringState
import dev.nymph.auto.core.model.SceneObservation
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.46 回归：无效帧必须冻结场景状态机。
 *
 * 修复前：`ScriptRunner` 在截图失败时构造 `SceneObservation(present=false)`，
 * 状态机把它当真实「离场」推进 missStreak。ON_DISAPPEAR 只判 `wasPresent && !presentNow`，
 * 于是「曾在场 + 连续 N 帧截图失败」会误派发下降沿动作——把「没拍到」当成「场景消失」。
 *
 * 修复后：无有效观测（valid=false）的帧不推进计数、不改变在场状态、不产生任何边沿。
 */
class V2946FrozenFrameTest {

    private fun disappearRule() = SceneRule(
        triggerMode = TriggerMode.ON_DISAPPEAR,
        presentStreak = 1,
        absentStreak = 2,
    )

    /** 在场状态：下标 0 已在场，曾触发过。 */
    private fun presentState() = SceneFiringState(present = setOf(0))

    @Test
    fun `默认观测valid为true零回归`() {
        val o = SceneObservation(present = true)
        assertTrue(o.valid)
        val o2 = SceneObservation(present = false, score = -1.0, cxPx = -1, cyPx = -1, frameW = 0, frameH = 0)
        assertTrue(o2.valid)
    }

    @Test
    fun `在场后连续无效帧不触发ON_DISAPPEAR`() {
        val rule = disappearRule()
        var state = presentState()
        // 连续 5 帧截图失败：全部 valid=false、present=false
        repeat(5) { t ->
            val obs = listOf(SceneObservation(present = false, valid = false))
            val d = SceneTrigger.planSceneFiringStateful(obs, listOf(rule), state, (t + 1) * 100L)
            assertTrue("第 $t 帧不应触发下降沿", d.fire.isEmpty())
            assertTrue("在场状态应被保留", 0 in d.next.present)
            // missStreak 不推进
            assertEquals(0, d.next.missStreak[0] ?: -1)
            state = d.next
        }
    }

    @Test
    fun `无效帧夹在有效帧之间不推进离场计数`() {
        val rule = disappearRule()
        var state = presentState()
        // 有效在场帧
        run {
            val d = SceneTrigger.planSceneFiringStateful(
                listOf(SceneObservation(present = true)), listOf(rule), state, 100L,
            )
            assertTrue(d.fire.isEmpty())
            state = d.next
        }
        // 1 帧无效（本应是离场计数的第 1 帧）
        run {
            val d = SceneTrigger.planSceneFiringStateful(
                listOf(SceneObservation(present = false, valid = false)), listOf(rule), state, 200L,
            )
            assertTrue(d.fire.isEmpty())
            assertEquals(0, d.next.missStreak[0] ?: -1)
            state = d.next
        }
        // 1 帧有效离场：absentStreak=2，但无效帧未计数，故此时 missStreak=1，仍不触发
        run {
            val d = SceneTrigger.planSceneFiringStateful(
                listOf(SceneObservation(present = false)), listOf(rule), state, 300L,
            )
            assertTrue("只有 1 帧有效离场，不应达到 absentStreak=2", d.fire.isEmpty())
            assertEquals(1, d.next.missStreak[0])
            state = d.next
        }
        // 再 1 帧有效离场：missStreak=2 达阈值 → 触发
        run {
            val d = SceneTrigger.planSceneFiringStateful(
                listOf(SceneObservation(present = false)), listOf(rule), state, 400L,
            )
            assertEquals(listOf(0), d.fire)
        }
    }

    @Test
    fun `纯无效帧序列不产生ON_APPEAR上升沿`() {
        val rule = SceneRule(triggerMode = TriggerMode.ON_APPEAR, presentStreak = 1)
        var state = SceneFiringState()
        repeat(3) { t ->
            val d = SceneTrigger.planSceneFiringStateful(
                listOf(SceneObservation(present = true, valid = false)),
                listOf(rule), state, (t + 1) * 100L,
            )
            assertTrue("无效帧即使 present=true 也不触发上升沿", d.fire.isEmpty())
            assertEquals(0, d.next.hitStreak[0] ?: -1)
            assertTrue(0 !in d.next.present)
            state = d.next
        }
    }

    @Test
    fun `兼容入口planSceneFiring逐字零回归`() {
        // v2.9.14 兼容入口用 SceneObservation(present=it)，valid 默认 true。
        // 上升沿、消失沿语义必须与修复前一致。
        val fire1 = SceneTrigger.planSceneFiring(listOf(true), emptySet())
        assertEquals(listOf(0), fire1.first)
        assertTrue(0 in fire1.second)
        val fire2 = SceneTrigger.planSceneFiring(listOf(false), setOf(0))
        // 默认规则 ON_APPEAR，不在场，不触发
        assertTrue(fire2.first.isEmpty())
        assertFalse(0 in fire2.second)
    }
}

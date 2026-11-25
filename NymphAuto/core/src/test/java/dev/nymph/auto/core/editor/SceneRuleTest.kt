// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneClickResolution
import dev.nymph.auto.core.model.SceneFiringState
import dev.nymph.auto.core.model.SceneObservation
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.15 三处「落盘锁定」语义回归防线：
 *  - A3 触发状态机（ON_APPEAR / WHILE_PRESENT / ON_DISAPPEAR、迟滞、cooldown、
 *    retryTimeout 修 P0、priority+mutexGroup 仲裁、同轮 K 上限）；
 *  - A2 相对匹配坐标 + 「场景疑似错位」二次校验；
 *  - A5 阈值自校准；
 *  - A1 新格式装配（attachSceneRule）与旧格式（v2.9.14 attachScene）零回归；
 *  - 旧布尔边沿 planSceneFiring 委托等价。
 *
 * 全部纯 JVM，不依赖 Android / 真机。
 */
class SceneRuleTest {

    private fun obs(
        present: Boolean,
        score: Double = -1.0,
        cx: Int = -1,
        cy: Int = -1,
        w: Int = 0,
        h: Int = 0,
    ) = SceneObservation(present, score, cx, cy, w, h)

    private fun fire(
        observed: List<SceneObservation>,
        rules: List<SceneRule>,
        prev: SceneFiringState = SceneFiringState(),
        nowMs: Long = 0L,
        k: Int = 1,
    ) = SceneTrigger.planSceneFiringStateful(observed, rules, prev, nowMs, k)

    // ============================================================ A3 触发状态机：mode

    @Test
    fun `ON_APPEAR 上升沿触发一次 常驻不重复 消失再出现再触发`() {
        val rules = listOf(SceneRule(triggerMode = TriggerMode.ON_APPEAR))
        val d1 = fire(listOf(obs(true)), rules)
        assertEquals(listOf(0), d1.fire)
        assertTrue(0 in d1.next.everFired)
        assertEquals(0L, d1.next.lastFireAtMs[0])

        val d2 = fire(listOf(obs(true)), rules, d1.next, 100L)
        assertTrue("场景常驻不应重复触发", d2.fire.isEmpty())

        val d3 = fire(listOf(obs(false)), rules, d2.next, 200L)
        assertTrue(d3.fire.isEmpty())
        assertFalse(0 in d3.next.present)

        val d4 = fire(listOf(obs(true)), rules, d3.next, 300L)
        assertEquals(listOf(0), d4.fire)
    }

    @Test
    fun `WHILE_PRESENT 在场每轮触发 离场不触发`() {
        val rules = listOf(SceneRule(triggerMode = TriggerMode.WHILE_PRESENT))
        val d1 = fire(listOf(obs(true)), rules, nowMs = 0L)
        assertEquals(listOf(0), d1.fire)
        val d2 = fire(listOf(obs(true)), rules, d1.next, 100L)
        assertEquals(listOf(0), d2.fire)
        val d3 = fire(listOf(obs(false)), rules, d2.next, 200L)
        assertTrue(d3.fire.isEmpty())
    }

    @Test
    fun `ON_DISAPPEAR 下降沿触发`() {
        val rules = listOf(SceneRule(triggerMode = TriggerMode.ON_DISAPPEAR))
        val inScene = SceneFiringState(present = setOf(0))
        val d = fire(listOf(obs(false)), rules, inScene, 100L)
        assertEquals(listOf(0), d.fire)
    }

    // ============================================================ A3 迟滞

    @Test
    fun `迟滞 presentStreak 连续 N 帧命中才在场`() {
        val rules = listOf(SceneRule(presentStreak = 3))
        val d1 = fire(listOf(obs(true)), rules, nowMs = 0L)
        assertTrue("第 1 帧未达 3 帧，不触发", d1.fire.isEmpty())
        val d2 = fire(listOf(obs(true)), rules, d1.next, 100L)
        assertTrue("第 2 帧仍未达", d2.fire.isEmpty())
        assertEquals(2, d2.next.hitStreak[0])
        val d3 = fire(listOf(obs(true)), rules, d2.next, 200L)
        assertEquals("第 3 帧达阈值，上升沿触发", listOf(0), d3.fire)
        assertTrue(0 in d3.next.present)
    }

    @Test
    fun `迟滞 absentStreak 单帧丢失不误判离场`() {
        val rules = listOf(SceneRule(absentStreak = 3))
        val inScene = SceneFiringState(present = setOf(0), everFired = setOf(0), lastFireAtMs = mapOf(0 to 0L))
        // 单帧未命中：仍在场（迟滞未满足）
        val d1 = fire(listOf(obs(false)), rules, inScene, 100L)
        assertTrue("单帧丢失不判离场", 0 in d1.next.present)
        val d2 = fire(listOf(obs(false)), rules, d1.next, 200L)
        assertTrue(0 in d2.next.present)
        val d3 = fire(listOf(obs(false)), rules, d2.next, 300L)
        assertFalse("连续 3 帧未命中才离场", 0 in d3.next.present)
    }

    // ============================================================ A3 cooldown / retry P0

    @Test
    fun `cooldown 抑制冷却期内连点 冷却过后恢复`() {
        val rules = listOf(SceneRule(triggerMode = TriggerMode.WHILE_PRESENT, cooldownMs = 1000L))
        val d1 = fire(listOf(obs(true)), rules, nowMs = 0L)
        assertEquals(listOf(0), d1.fire)
        val d2 = fire(listOf(obs(true)), rules, d1.next, 300L)
        assertTrue("冷却期内抑制", d2.fire.isEmpty())
        val d3 = fire(listOf(obs(true)), rules, d2.next, 1200L)
        assertEquals("超过冷却期恢复触发", listOf(0), d3.fire)
    }

    @Test
    fun `retryTimeout 修触发后场景持续在场导致无法再次触发`() {
        // ON_APPEAR + retryTimeout=1000：EDGE 触发后场景一直在场，
        // 超过重试时长应再次触发（否则不再产生新上升沿 → 持续停滞）。
        val rules = listOf(SceneRule(triggerMode = TriggerMode.ON_APPEAR, retryTimeoutMs = 1000L))
        val d1 = fire(listOf(obs(true)), rules, nowMs = 0L)
        assertEquals(listOf(0), d1.fire)
        val d2 = fire(listOf(obs(true)), rules, d1.next, 500L)
        assertTrue("未到重试时长不触发", d2.fire.isEmpty())
        val d3 = fire(listOf(obs(true)), rules, d2.next, 1500L)
        assertEquals("超过 retryTimeout 应重试", listOf(0), d3.fire)
    }

    @Test
    fun `retryTimeout 为 0 时保持旧行为不重试`() {
        val rules = listOf(SceneRule(triggerMode = TriggerMode.ON_APPEAR, retryTimeoutMs = 0L))
        val d1 = fire(listOf(obs(true)), rules, nowMs = 0L)
        val d2 = fire(listOf(obs(true)), rules, d1.next, 99999L)
        assertTrue(d2.fire.isEmpty())
    }

    // ============================================================ A3 仲裁 / K 上限

    @Test
    fun `同轮最多触发 K 条 K=1 最安全`() {
        val rules = List(3) { SceneRule() }
        val observed = listOf(obs(true), obs(true), obs(true))
        assertEquals(listOf(0), fire(observed, rules, k = 1).fire)
        assertEquals(listOf(0, 1), fire(observed, rules, k = 2).fire)
        assertEquals(listOf(0, 1, 2), fire(observed, rules, k = 3).fire)
    }

    @Test
    fun `priority 仲裁 高优先级先触发`() {
        val rules = listOf(SceneRule(priority = 1), SceneRule(priority = 9), SceneRule(priority = 5))
        val observed = listOf(obs(true), obs(true), obs(true))
        val d = fire(observed, rules, k = 1)
        assertEquals(listOf(1), d.fire)
    }

    @Test
    fun `mutexGroup 同组只取最高优先级一条`() {
        val rules = listOf(
            SceneRule(priority = 8, mutexGroup = "popup"),
            SceneRule(priority = 3, mutexGroup = "popup"),
            SceneRule(priority = 1, mutexGroup = null),
        )
        val observed = listOf(obs(true), obs(true), obs(true))
        val d = fire(observed, rules, k = Int.MAX_VALUE)
        // 组内仅 index0 胜出；index2 不同组，另取一条；index1 被同组压制
        assertEquals(listOf(0, 2), d.fire)
        assertTrue("同组被压制应有可观测原因", d.reasons[1]?.contains("互斥组") == true)
    }

    @Test
    fun `默认全默认规则下状态机与旧布尔边沿逐字等价`() {
        val sequences = listOf(
            listOf(false, false, false),
            listOf(false, false, true, false),
            listOf(true, false, true, true),
        )
        for (seq in sequences) {
            val old = SceneTrigger.planSceneFiring(seq, emptySet())
            val st = SceneTrigger.planSceneFiringStateful(
                seq.map { obs(it) }, emptyList(), SceneFiringState(), 0L, Int.MAX_VALUE,
            )
            assertEquals("fire 等价 @$seq", old.first, st.fire)
            assertEquals("present 等价 @$seq", old.second, st.next.present)
        }
    }

    @Test
    fun `reasons 覆盖每条规则且含分数与决策`() {
        val rules = listOf(SceneRule(), SceneRule())
        val d = fire(listOf(obs(true, score = 0.91), obs(false, score = 0.40)), rules, k = Int.MAX_VALUE)
        assertEquals(2, d.reasons.size)
        assertTrue(d.reasons[0]!!.contains("0.91"))
        assertTrue(d.reasons[1]!!.contains("0.40"))
    }

    @Test
    fun `全不出现不推进任何规则 且状态不残留 present`() {
        val d = fire(listOf(obs(false), obs(false)), listOf(SceneRule(), SceneRule()))
        assertTrue(d.fire.isEmpty())
        assertTrue(d.next.present.isEmpty())
        assertTrue(d.next.everFired.isEmpty())
    }

    // ============================================================ A5 阈值自校准

    @Test
    fun `阈值自校准 干扰分低时用基线 82 高时抬到干扰分加余量`() {
        assertEquals(82, SceneTrigger.calibrateSimilarity(-1.0))          // 未测
        assertEquals(82, SceneTrigger.calibrateSimilarity(0.10))          // 18 < 82
        assertEquals(82, SceneTrigger.calibrateSimilarity(0.70))          // 78 < 82
        assertEquals(87, SceneTrigger.calibrateSimilarity(0.79))          // 79+8=87 > 82（浮点稳定）
        assertEquals(95, SceneTrigger.calibrateSimilarity(0.87))          // 87+8=95
        assertEquals(100, SceneTrigger.calibrateSimilarity(0.99))         // clamp 上界
    }

    // ============================================================ A2 相对坐标

    @Test
    fun `相对偏移 拾取点减锚点中心`() {
        val anchor = Region(40.0, 40.0, 60.0, 60.0) // center (50,50)
        val (ox, oy) = SceneTrigger.computeClickOffset(55.0, 47.0, anchor)
        assertEquals(5.0, ox, 0.0)
        assertEquals(-3.0, oy, 0.0)
    }

    @Test
    fun `clickOffset 缺省 null 走老绝对坐标 零回归`() {
        val rule = SceneRule() // 无相对偏移
        val r = SceneTrigger.resolveSceneClick(
            obs(true, score = 0.9, cx = 500, cy = 600, w = 1000, h = 2000),
            rule, 42.0, 68.0,
        )
        assertTrue(r is SceneClickResolution.Absolute)
        assertEquals(42.0, (r as SceneClickResolution.Absolute).xPct, 0.0)
        assertEquals(68.0, r.yPct, 0.0)
    }

    @Test
    fun `相对匹配坐标 命中中心加偏移算真实落点`() {
        val rule = SceneRule(
            clickOffsetXPct = 5.0,
            clickOffsetYPct = -3.0,
            anchorRectPct = Region(40.0, 40.0, 60.0, 60.0), // center (50,50)
            locMaxDeviationPct = 15.0,
        )
        // 匹配中心 (500,1000) on 1000×2000 → (50%,50%)，与锚点中心一致 → dev=0
        val r = SceneTrigger.resolveSceneClick(
            obs(true, score = 0.93, cx = 500, cy = 1000, w = 1000, h = 2000),
            rule, 42.0, 68.0,
        )
        assertTrue(r is SceneClickResolution.Hit)
        val hit = r as SceneClickResolution.Hit
        assertEquals(55.0, hit.xPct, 0.0)
        assertEquals(47.0, hit.yPct, 0.0)
    }

    @Test
    fun `二次校验 匹配位置偏离预期超阈值判场景疑似错位不点`() {
        val rule = SceneRule(
            clickOffsetXPct = 5.0,
            clickOffsetYPct = 0.0,
            anchorRectPct = Region(40.0, 40.0, 60.0, 60.0), // center (50,50)
            locMaxDeviationPct = 15.0,
        )
        // 匹配中心 75% / 50% → 偏离 25% > 15%
        val r = SceneTrigger.resolveSceneClick(
            obs(true, score = 0.85, cx = 750, cy = 1000, w = 1000, h = 2000),
            rule, 42.0, 68.0,
        )
        assertTrue("应判疑似错位", r is SceneClickResolution.SuspiciousMismatch)
        assertEquals(25.0, (r as SceneClickResolution.SuspiciousMismatch).deviationPct, 0.0)
    }

    @Test
    fun `ON_DISAPPEAR 无有效匹配中心时回退绝对坐标`() {
        val rule = SceneRule(clickOffsetXPct = 5.0, clickOffsetYPct = 5.0)
        val r = SceneTrigger.resolveSceneClick(obs(false), rule, 10.0, 20.0)
        assertTrue(r is SceneClickResolution.Absolute)
    }

    // ============================================================ A1 新格式装配

    @Test
    fun `attachSceneRule 写落盘 SceneRule 且 copy 保真不丢字段`() {
        val child = Action(type = ActionType.WAIT, desc = "子动作")
        val listener = Action(type = ActionType.TOAST, desc = "监听")
        val original = Action(
            type = ActionType.TAP, x = 42.0, y = 68.0, desc = "领奖",
            children = listOf(child), listeners = Listeners(beforeAction = listOf(listener)),
        )
        val searchRoi = Region(30.0, 30.0, 70.0, 70.0)
        val anchorRect = Region(46.0, 46.0, 54.0, 54.0)
        val rule = SceneRule(
            searchRoi = searchRoi,
            anchorRectPct = anchorRect,
            clickOffsetXPct = 2.0,
            clickOffsetYPct = 4.0,
            similarity = 88,
            triggerMode = TriggerMode.ON_APPEAR,
        )
        val (armed, hasGate) = SceneTrigger.attachSceneRule(original, rule, "ANCHOR_B64")
        assertTrue(hasGate)
        assertEquals("region 应为搜索范围", searchRoi, armed.region)
        assertEquals(rule, SceneTrigger.sceneRuleOf(armed))
        assertEquals(42.0, armed.x, 0.0)
        assertEquals("领奖", armed.desc)
        assertSame("children 保真", original.children, armed.children)
        assertSame("listeners 保真", original.listeners, armed.listeners)
        assertEquals(ConditionKind.APPEAR_IMAGE, armed.condition?.kind)
        assertEquals("ANCHOR_B64", armed.condition?.imageData)
        assertEquals(88, armed.condition?.imageSimilarity)
        assertEquals(searchRoi, armed.condition?.region)
        assertTrue(SceneTrigger.isSceneTrigger(armed))
    }

    @Test
    fun `attachSceneRule 无模板 fail-closed 仍落盘规则但不挂门控`() {
        val rule = SceneRule(searchRoi = Region(30.0, 30.0, 70.0, 70.0))
        val (armed, hasGate) = SceneTrigger.attachSceneRule(
            Action(type = ActionType.TAP), rule, "",
        )
        assertFalse("无模板不启用门控", hasGate)
        assertEquals("规则仍落盘供可观测", rule, SceneTrigger.sceneRuleOf(armed))
        assertNull("不挂图像门控", armed.condition)
        assertFalse(SceneTrigger.isSceneTrigger(armed))
        assertNull(SceneTrigger.sceneGateOf(armed))
    }

    @Test
    fun `旧格式 attachScene 逐字零回归`() {
        val original = Action(type = ActionType.TAP, x = 10.0, y = 20.0)
        val roi = SceneTrigger.autoRoi(10.0, 20.0, 800, 1768)
        val (armed, hasGate) = SceneTrigger.attachScene(original, roi, "WHOLE_ROI_B64")
        assertTrue(hasGate)
        assertNull("旧格式不带 SceneRule", SceneTrigger.sceneRuleOf(armed))
        assertEquals(roi, armed.region)
        assertEquals(ConditionKind.APPEAR_IMAGE, armed.condition?.kind)
        assertFalse(armed.condition?.repeatCheckUntilOk ?: true)
        assertFalse("旧格式不启用相对坐标", armed.sceneRule?.usesRelativeClick ?: false)
    }

    @Test
    fun `sceneLabel 场景话术不含步`() {
        assertEquals("场景 2 / 5", SceneTrigger.sceneLabel(1, 5))
        assertFalse(SceneTrigger.sceneLabel(0, 1).contains("步"))
    }
}

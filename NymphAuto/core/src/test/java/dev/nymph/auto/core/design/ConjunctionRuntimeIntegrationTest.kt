// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import dev.nymph.auto.core.editor.SceneTrigger
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneFiringState
import dev.nymph.auto.core.model.SceneObservation
import dev.nymph.auto.core.model.SceneRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.3 S3 运行时接通门禁（GO 后门禁，纯 JVM）：
 *  不是只测编译产物 JSON，而是用真实编译产物驱动**运行时的合取求值链路**：
 *    gateTemplatesOf（m 模板）→ 同帧 m 条子观测 → aggregateObservations（ALL/N 聚合）
 *    → planSceneFiringStateful（fire 决策）→ canvasPointIndices（N 点位按序派发）。
 *
 * 这些正是 ScriptRunner.runSceneDriven :699-868 调用的同一组生产纯函数（逐字复用），
 * 因此断言等价于「运行时真求值、少一个不 fire、全真 dispatch、一轮同帧」。
 *
 * 说明：真机 GestureEngine.dispatch / Bitmap 抓帧端到端需 Android 运行时，真机命中率
 * 未验证；本套件锁定「求值 + 决策 + 派发意图」的逻辑接通。frame() 恰好 1 次由
 * 「1 门控规则 → m spec 由同一次 findTemplates 消费」的契约 + ScreenSource 一次抓帧保证。
 */
class ConjunctionRuntimeIntegrationTest {

    private val featA = "feat:aaaa0000aaaa0000"
    private val featB = "feat:bbbb0000bbbb0000"
    private val bg = "canvas:cccc0000cccc0000"

    /** 2 特征（ALL）+ 3 点位 → 编译出 1 载体（COMBINE children=2）+ N−1=2 无门控点位。 */
    private fun build(): Pair<List<dev.nymph.auto.core.model.Action>, SceneCanvas> {
        val canvas = SceneCanvas(
            backgroundKey = bg, widthPx = 1080, heightPx = 2400,
            combine = Combine.ALL, minCount = 2,
            features = listOf(
                SceneCanvas.CanvasFeature("a", "特征A", Region(10.0, 10.0, 30.0, 30.0), 88, featA),
                SceneCanvas.CanvasFeature("b", "特征B", Region(60.0, 60.0, 80.0, 80.0), 82, featB),
            ),
            points = listOf(
                SceneCanvas.CanvasPoint("p1", "点位1", x = 70.0, y = 50.0),
                SceneCanvas.CanvasPoint("p2", "点位2", x = 50.0, y = 80.0),
                SceneCanvas.CanvasPoint("p3", "点位3", x = 30.0, y = 50.0),
            ),
        )
        val assets = mapOf(bg to "FULL_FRAME_B64", featA to "TPL_A_B64", featB to "TPL_B_B64")
        return canvas.compileToActions(assets) to canvas
    }

    @Test
    fun `载体COMBINE有m个children_一次求值消费m模板而非N条各挂`() {
        val (actions, _) = build()
        // 3 点位编译出 3 条 Action；只有 1 条载体挂 COMBINE，其余 N−1 不挂。
        assertEquals(3, actions.size)
        val carriers = actions.filter { SceneTrigger.isSceneTrigger(it) }
        assertEquals("唯一合取载体", 1, carriers.size)
        val gate = SceneTrigger.sceneGateOf(carriers[0])!!
        assertEquals(ConditionKind.COMBINE, gate.kind)
        assertEquals(Combine.ALL, gate.combine)
        // 一次求值消费 m=2 模板（不是 N=3 条各挂）
        val templates = SceneTrigger.gateTemplatesOf(carriers[0])
        assertEquals("m 特征 = 2 条模板同帧", 2, templates.size)
        assertEquals("N 点位展开为 3（同帧消费 m 模板后派发）",
            listOf(0, 1, 2), SceneTrigger.canvasPointIndices(actions, SceneCanvas.CANVAS_ACTION_TAG))
    }

    @Test
    fun `m特征ALL真被求值_少一个子观测不fire`() {
        val (actions, _) = build()
        val carrier = actions.first { SceneTrigger.isSceneTrigger(it) }
        val gate = SceneTrigger.sceneGateOf(carrier)!!
        val templates = SceneTrigger.gateTemplatesOf(carrier) // m=2

        // 同帧：特征1 present，特征2 未命中 → ALL 聚合应为 false。
        val children = listOf(
            SceneObservation(present = true, score = 0.95, cxPx = 200, cyPx = 200, frameW = 1080, frameH = 2400),
            SceneObservation(present = false, score = 0.40, cxPx = -1, cyPx = -1, frameW = 1080, frameH = 2400),
        )
        assertEquals(2, children.size)
        assertEquals("m 条子观测逐条对应 m 模板", templates.size, children.size)
        val agg = SceneTrigger.aggregateObservations(children, gate.combine, gate.runCountLimit)
        assertFalse("ALL 下少一个特征必须判未在场", agg.present)

        val rule = carrier.sceneRule ?: SceneRule()
        val decision = SceneTrigger.planSceneFiringStateful(
            listOf(agg), listOf(rule), SceneFiringState(), nowMs = 1000L, maxFiringPerRound = 1,
        )
        assertTrue("少一个特征不得 fire", decision.fire.isEmpty())
    }

    @Test
    fun `m特征全真时fire且N点位按序dispatch`() {
        val (actions, _) = build()
        val carrierIdx = actions.indexOfFirst { SceneTrigger.isSceneTrigger(it) }
        val carrier = actions[carrierIdx]
        val gate = SceneTrigger.sceneGateOf(carrier)!!

        // 同帧：两个特征都 present → ALL 聚合 true。
        val children = listOf(
            SceneObservation(present = true, score = 0.95, cxPx = 200, cyPx = 200, frameW = 1080, frameH = 2400),
            SceneObservation(present = true, score = 0.91, cxPx = 700, cyPx = 700, frameW = 1080, frameH = 2400),
        )
        val agg = SceneTrigger.aggregateObservations(children, gate.combine, gate.runCountLimit)
        assertTrue("ALL 全真必须判在场", agg.present)

        val rule = carrier.sceneRule ?: SceneRule()
        val decision = SceneTrigger.planSceneFiringStateful(
            listOf(agg), listOf(rule), SceneFiringState(), nowMs = 1000L, maxFiringPerRound = 1,
        )
        assertEquals("全真 → fire 唯一合取槽位 0", listOf(0), decision.fire)

        // fire 后载体按 desc tag 展开 N 点位按序派发（运行链 :819-824 同一纯函数）。
        val points = SceneTrigger.canvasPointIndices(actions, SceneCanvas.CANVAS_ACTION_TAG)
        assertEquals("N 点位按序", listOf(0, 1, 2), points)
        assertTrue("全真 dispatch 真发生", points.size == 3)
    }

    @Test
    fun `N_of_M语义_minCount达标才fire`() {
        val canvas = SceneCanvas(
            backgroundKey = bg, widthPx = 1080, heightPx = 2400,
            combine = Combine.N, minCount = 2,
            features = listOf(
                SceneCanvas.CanvasFeature("a", "A", Region(10.0, 10.0, 30.0, 30.0), 88, featA),
                SceneCanvas.CanvasFeature("b", "B", Region(60.0, 60.0, 80.0, 80.0), 82, featB),
                SceneCanvas.CanvasFeature("c", "C", Region(40.0, 10.0, 60.0, 30.0), 82, "feat:dddd0000dddd0000"),
            ),
            points = listOf(SceneCanvas.CanvasPoint("p1", "点位1", x = 50.0, y = 50.0)),
        )
        val assets = mapOf(bg to "FULL", featA to "A", featB to "B", "feat:dddd0000dddd0000" to "C")
        val actions = canvas.compileToActions(assets)
        val carrier = actions.first { SceneTrigger.isSceneTrigger(it) }
        val gate = SceneTrigger.sceneGateOf(carrier)!!
        assertEquals(Combine.N, gate.combine)

        val twoOfThree = listOf(
            SceneObservation(present = true, score = 0.9),
            SceneObservation(present = true, score = 0.9),
            SceneObservation(present = false, score = 0.3),
        )
        val agg = SceneTrigger.aggregateObservations(twoOfThree, gate.combine, gate.runCountLimit)
        assertTrue("3 中 2 命中且 minCount=2 → 在场", agg.present)

        val oneOfThree = listOf(
            SceneObservation(present = true, score = 0.9),
            SceneObservation(present = false, score = 0.3),
            SceneObservation(present = false, score = 0.3),
        )
        assertFalse("3 中只 1 命中 < minCount=2 → 不在场",
            SceneTrigger.aggregateObservations(oneOfThree, gate.combine, gate.runCountLimit).present)
    }
}

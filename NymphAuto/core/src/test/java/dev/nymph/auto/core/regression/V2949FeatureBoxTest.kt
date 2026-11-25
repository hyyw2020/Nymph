// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.editor.FeatureBox
import dev.nymph.auto.core.editor.RoiApplier
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.49 多特征框回归（ADR-010 红线）。
 *
 * 锁死：N 个框按遍历序一一映射到所有真正读取 region 的目标，每框相似度独立；
 * v3.0.0 起框数与目标数不等且多框时明确拒绝（不再轮转）；单框兼容旧 apply；
 * 空框无副作用；其它字段保真。
 */
class V2949FeatureBoxTest {

    private fun roi(i: Int) = Region(i * 10.0, i * 10.0, i * 10.0 + 40.0, i * 10.0 + 50.0, "$i,$i,${i + 40},${i + 50}")

    private fun visualScript(): Script = Script(
        actions = listOf(
            Action(type = ActionType.CLICK_IMAGE, imageData = "img1", desc = "d1"),
            Action(type = ActionType.CLICK_COLOR, color = 0xFF0000, desc = "d2"),
            Action(type = ActionType.TAP, x = 50.0, y = 50.0, desc = "noRoi"),
            Action(
                type = ActionType.RUN_ACTIONS,
                children = listOf(Action(type = ActionType.CLICK_TEXT, text = "ok", desc = "d3")),
                listeners = Listeners(beforeAction = listOf(Action(type = ActionType.CLICK_NODE, desc = "d4"))),
            ),
        ),
        abortCondition = Condition(kind = ConditionKind.APPEAR_COLOR, color = 1),
    )

    @Test
    fun `框数与目标数相等时按序一一映射且相似度独立`() {
        val s = visualScript() // 5 个 ROI 目标：img1/color/text/node/abort
        // v3.0.0：取消轮转，5 目标必须给 5 框才受理。
        val boxes = (1..5).map { FeatureBox(roi(it), 80 + it) }
        val r = RoiApplier.applyMultiChecked(s, boxes)
        assertTrue(r.accepted)
        assertEquals(5, r.hits)
        val next = r.script
        // 遍历序：actions[0], actions[1], children[0], listener, abort
        assertEquals(roi(1), next.actions[0].region); assertEquals(81, next.actions[0].imageSimilarity)
        assertEquals(roi(2), next.actions[1].region); assertEquals(82, next.actions[1].imageSimilarity)
        assertEquals(roi(3), next.actions[3].children[0].region); assertEquals(83, next.actions[3].children[0].imageSimilarity)
        assertEquals(roi(4), next.actions[3].listeners.beforeAction[0].region)
        assertEquals(84, next.actions[3].listeners.beforeAction[0].imageSimilarity)
        assertEquals(roi(5), next.abortCondition!!.region)
        assertEquals(85, next.abortCondition!!.imageSimilarity)
        // 非 ROI 目标不动 + 其它字段保真
        assertEquals(Region(), next.actions[2].region)
        assertEquals("img1", next.actions[0].imageData)
        assertEquals("d1", next.actions[0].desc)
        assertEquals("ok", next.actions[3].children[0].text)
    }

    @Test
    fun `v300框数与目标数不等且多框时明确拒绝不写入`() {
        val s = visualScript() // 5 个目标
        val boxes = listOf(FeatureBox(roi(1), 91), FeatureBox(roi(2), 72), FeatureBox(roi(3), 85))
        val r = RoiApplier.applyMultiChecked(s, boxes)
        // fail-loud：拒绝，返回原对象，原因含实际数量。
        org.junit.Assert.assertFalse(r.accepted)
        assertEquals(0, r.hits)
        assertSame(s, r.script)
        assertTrue("原因应含框数 3", r.reason.contains("3"))
        assertTrue("原因应含目标数 5", r.reason.contains("5"))
    }

    @Test
    fun `单框行为兼容旧apply 所有目标同一region与相似度`() {
        val s = visualScript()
        val box = FeatureBox(roi(7), 66)
        val (next, hits) = RoiApplier.applyMulti(s, listOf(box))
        assertEquals(5, hits)
        assertTrue(next.actions.subList(0, 2).all { it.region == roi(7) && it.imageSimilarity == 66 })
        assertEquals(66, next.abortCondition!!.imageSimilarity)
    }

    @Test
    fun `v300框数多于目标数明确拒绝而非静默复用首框`() {
        val s = Script(actions = listOf(Action(type = ActionType.CLICK_IMAGE)))
        val boxes = (0 until 10).map { FeatureBox(roi(it), 50 + it) }
        val r = RoiApplier.applyMultiChecked(s, boxes)
        org.junit.Assert.assertFalse(r.accepted)
        assertEquals(0, r.hits)
        assertSame(s, r.script)
    }

    @Test
    fun `空框返回原对象命中0`() {
        val s = visualScript()
        val (next, hits) = RoiApplier.applyMulti(s, emptyList())
        assertEquals(0, hits)
        assertSame(s, next)
    }

    @Test
    fun `无ROI目标返回原对象且命中0`() {
        val s = Script(actions = listOf(Action(type = ActionType.TAP), Action(type = ActionType.WAIT)))
        val (next, hits) = RoiApplier.applyMulti(s, listOf(FeatureBox(roi(1))))
        assertEquals(0, hits)
        assertSame(s, next)
    }

    @Test
    fun `条件树内多条件按遍历序各拿一框`() {
        val s = Script(
            actions = listOf(
                Action(
                    type = ActionType.TAP,
                    condition = Condition(
                        kind = ConditionKind.COMBINE,
                        children = listOf(
                            Condition(kind = ConditionKind.APPEAR_IMAGE, imageData = "c1"),
                            Condition(kind = ConditionKind.JS_EXPR, jsExpr = "1==1"),
                            Condition(kind = ConditionKind.APPEAR_TEXT, text = "x"),
                        ),
                    ),
                ),
            ),
        )
        val (next, hits) = RoiApplier.applyMulti(
            s, listOf(FeatureBox(roi(1), 80), FeatureBox(roi(2), 90)),
        )
        assertEquals(2, hits) // JS 条件不读 region
        val kids = next.actions[0].condition!!.children
        assertEquals(roi(1), kids[0].region); assertEquals(80, kids[0].imageSimilarity)
        assertEquals(Region(), kids[1].region) // JS 不动
        assertEquals(roi(2), kids[2].region); assertEquals(90, kids[2].imageSimilarity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `相似度越界构造失败`() {
        FeatureBox(roi(1), 101)
    }

    @Test
    fun `默认相似度85与clamp`() {
        assertEquals(85, FeatureBox.DEFAULT_SIMILARITY)
        assertEquals(FeatureBox(roi(1)).similarity, 85)
        assertEquals(0, FeatureBox.clampSimilarity(-5))
        assertEquals(100, FeatureBox.clampSimilarity(200))
    }
}

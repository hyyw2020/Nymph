// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

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
 * 框选 ROI 应用（v2.7.1）。这是「框了等于白框」bug 的回归防线：
 * ROI 必须真正写进脚本内所有读取 region 的位置，且绝不丢其它字段。
 */
class RoiApplierTest {

    private val roi = Region(10.0, 20.0, 80.0, 90.0, "10,20,80,90")

    @Test
    fun `找图找色找文字 AI 节点动作被应用 坐标动作不动`() {
        val s = Script(
            actions = listOf(
                Action(type = ActionType.CLICK_IMAGE, imageData = "base64xxx", desc = "找图"),
                Action(type = ActionType.CLICK_COLOR, color = 0xFF0000, desc = "找色"),
                Action(type = ActionType.CLICK_TEXT, text = "确定"),
                Action(type = ActionType.CLICK_AI, aiLabel = "按钮"),
                Action(type = ActionType.CLICK_NODE, nodeId = "btn1"),
                Action(type = ActionType.TAP, x = 50.0, y = 50.0),
                Action(type = ActionType.SWIPE),
            ),
        )
        val (next, hits) = RoiApplier.apply(s, roi)
        assertEquals(5, hits)
        assertEquals(5, RoiApplier.countTargets(s))
        // 目标动作 region 被替换，非 region 字段保真
        assertEquals(roi, next.actions[0].region)
        assertEquals("base64xxx", next.actions[0].imageData)
        assertEquals("找图", next.actions[0].desc)
        assertEquals(0xFF0000, next.actions[1].color)
        assertEquals("btn1", next.actions[4].nodeId)
        // 坐标动作 region 保持默认全屏，不被污染
        assertEquals(Region(), next.actions[5].region)
        assertEquals(Region(), next.actions[6].region)
    }

    @Test
    fun `无目标时返回原对象且命中0 不假装成功`() {
        val s = Script(actions = listOf(Action(type = ActionType.TAP), Action(type = ActionType.WAIT)))
        val (next, hits) = RoiApplier.apply(s, roi)
        assertEquals(0, hits)
        assertSame(s, next)
    }

    @Test
    fun `递归 children 与 listeners 内的找图动作`() {
        val s = Script(
            actions = listOf(
                Action(
                    type = ActionType.RUN_ACTIONS,
                    children = listOf(
                        Action(type = ActionType.CLICK_IMAGE, imageData = "child"),
                        Action(
                            type = ActionType.RUN_ACTIONS,
                            children = listOf(Action(type = ActionType.CLICK_COLOR)),
                        ),
                    ),
                    listeners = Listeners(
                        beforeAction = listOf(Action(type = ActionType.CLICK_TEXT)),
                    ),
                ),
            ),
            listeners = Listeners(
                afterScript = listOf(Action(type = ActionType.CLICK_AI)),
            ),
        )
        val (next, hits) = RoiApplier.apply(s, roi)
        assertEquals(4, hits)
        val group = next.actions[0]
        assertEquals(roi, group.children[0].region)
        assertEquals("child", group.children[0].imageData)
        assertEquals(roi, group.children[1].children[0].region)
        assertEquals(roi, group.listeners.beforeAction[0].region)
        assertEquals(roi, next.listeners.afterScript[0].region)
    }

    @Test
    fun `条件树的视觉条件与中止条件被应用 JS等非视觉条件不动`() {
        val s = Script(
            actions = listOf(
                Action(
                    type = ActionType.TAP,
                    condition = Condition(
                        kind = ConditionKind.COMBINE,
                        children = listOf(
                            Condition(kind = ConditionKind.APPEAR_IMAGE, imageData = "cond"),
                            Condition(kind = ConditionKind.JS_EXPR, jsExpr = "1==1"),
                        ),
                    ),
                ),
            ),
            abortCondition = Condition(kind = ConditionKind.APPEAR_COLOR, color = 123),
        )
        val (next, hits) = RoiApplier.apply(s, roi)
        assertEquals(2, hits)
        val cond = next.actions[0].condition!!
        assertEquals(roi, cond.children[0].region)
        assertEquals("cond", cond.children[0].imageData)
        assertEquals(Region(), cond.children[1].region) // JS 条件不读 region
        assertEquals("1==1", cond.children[1].jsExpr)
        assertEquals(roi, next.abortCondition!!.region)
    }

    @Test
    fun `hasExplicitRegion 区分默认全屏与显式区域`() {
        assertEquals(false, RoiApplier.hasExplicitRegion(Region()))
        assertEquals(true, RoiApplier.hasExplicitRegion(Region(10.0, 0.0, 100.0, 100.0)))
        assertEquals(true, RoiApplier.hasExplicitRegion(Region(expr = "topHalf")))
    }

    @Test
    fun `countTargets 与 apply 命中数一致`() {
        val s = Script(
            actions = listOf(
                Action(type = ActionType.CLICK_IMAGE),
                Action(type = ActionType.RUN_ACTIONS, children = listOf(Action(type = ActionType.CLICK_COLOR))),
            ),
        )
        val (_, hits) = RoiApplier.apply(s, roi)
        assertTrue(hits > 0)
        // countTargets 只数动作级目标，apply 还会数条件；这里无条件，两者应相等
        assertEquals(RoiApplier.countTargets(s).toLong(), hits.toLong())
    }
}

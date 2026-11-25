// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.25：门控结论必须与「运行时会不会触发」一致。
 *
 * 现场日志（v2.9.24）呈现出一条自相矛盾的链路：稳定锚点装配得到 0×0px、掩码 0 块、
 * 结论 NO_FRAME，紧接着点击前门控却输出「结论=PASS 允许点击=true」；保存后运行时因
 * 「没有任何可用画面模板」立即 fail-closed 停止，用户连点四次运行，四次都是「未跑完」。
 *
 * 本文件把这条链路的两个判断点钉死为纯 JVM 断言：
 *  1. 锚点未装配时，无论颜色多匹配，[TapGate] 都不得返回 PASS；
 *  2. 场景驱动脚本的「可触发规则数」只认真正挂了画面模板的规则。
 */
class AnchorReadyGateTest {

    private val roi = Region(40.0, 40.0, 60.0, 60.0)
    private val sample = SceneAssist.ColorSample(0xFF3366CC.toInt(), 24, 0, 0, 1)
    private val observed = 0xFF3366CC.toInt()

    @Test
    fun `锚点未装配时颜色完全一致也不判命中`() {
        val v = TapGate.evaluate(
            TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample, observed,
            sample.tolerance, anchorReady = false,
        )
        assertEquals(TapGate.Reason.ASSEMBLY_FAILED, v.reason)
        assertFalse("锚点不可用却允许点击，就是界面与运行时互相打脸", v.allowTap)
    }

    @Test
    fun `锚点已装配且颜色一致仍判命中`() {
        val v = TapGate.evaluate(
            TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample, observed,
            sample.tolerance, anchorReady = true,
        )
        assertEquals(TapGate.Reason.PASS, v.reason)
        assertTrue(v.allowTap)
    }

    @Test
    fun `锚点未装配的结论文案说明运行不会触发`() {
        val text = TapGate.messageFor(TapGate.Reason.ASSEMBLY_FAILED)
        assertTrue("文案需要直接说明后果，而不是只报阶段名：$text", text.contains("不会触发"))
    }

    @Test
    fun `可触发规则数只统计挂了画面模板的动作`() {
        val withGate = actionWith(template = "iVBORw0KGgo=")
        val withoutGate = actionWith(template = "")
        assertEquals(1, SceneTrigger.triggerableCount(listOf(withGate, withoutGate)))
        assertEquals(0, SceneTrigger.triggerableCount(listOf(withoutGate, withoutGate)))
        assertEquals(2, SceneTrigger.triggerableCount(listOf(withGate, withGate)))
    }

    @Test
    fun `空动作列表的可触发数为零`() {
        assertEquals(0, SceneTrigger.triggerableCount(emptyList()))
    }

    @Test
    fun `场景驱动且零模板时脚本必然不产生动作`() {
        val script = Script(
            name = "现场脚本",
            actions = listOf(actionWith(template = ""), actionWith(template = "")),
            sceneDriven = true,
        )
        assertEquals(0, SceneTrigger.triggerableCount(script.actions))
    }

    private fun actionWith(template: String): Action = Action(
        type = ActionType.TAP,
        x = 50.0,
        y = 50.0,
        condition = if (template.isBlank()) {
            null
        } else {
            Condition(
                kind = ConditionKind.APPEAR_IMAGE,
                imageData = template,
                imageSimilarity = 89,
                region = Region(40.0, 40.0, 60.0, 60.0),
            )
        },
    )
}

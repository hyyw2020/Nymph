// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文字识别依赖判定（v2.9.33 OCR 通道可见告警的判定基础）。 */
class ScriptTextDependencyTest {

    @Test
    fun `空脚本不依赖文字`() {
        assertFalse(Script().dependsOnText())
    }

    @Test
    fun `点击文字动作依赖文字`() {
        val s = Script(actions = listOf(Action(type = ActionType.CLICK_TEXT, text = "确认")))
        assertTrue(s.dependsOnText())
    }

    @Test
    fun `纯坐标动作不依赖文字`() {
        val s = Script(actions = listOf(Action(type = ActionType.TAP)))
        assertFalse(s.dependsOnText())
    }

    @Test
    fun `文字出现条件依赖文字`() {
        val s = Script(actions = listOf(
            Action(type = ActionType.TAP, condition = Condition(kind = ConditionKind.APPEAR_TEXT)),
        ))
        assertTrue(s.dependsOnText())
    }

    @Test
    fun `嵌套条件树中的文字条件被识别`() {
        val s = Script(actions = listOf(
            Action(
                type = ActionType.TAP,
                condition = Condition(
                    kind = ConditionKind.COMBINE,
                    children = listOf(Condition(kind = ConditionKind.APPEAR_TEXT)),
                ),
            ),
        ))
        assertTrue(s.dependsOnText())
    }

    @Test
    fun `嵌套子动作中的文字动作被识别`() {
        val s = Script(actions = listOf(
            Action(
                type = ActionType.REPLAY,
                children = listOf(Action(type = ActionType.CLICK_TEXT, text = "继续")),
            ),
        ))
        assertTrue(s.dependsOnText())
    }

    @Test
    fun `图片条件不算文字依赖`() {
        val s = Script(actions = listOf(
            Action(type = ActionType.CLICK_IMAGE, condition = Condition(kind = ConditionKind.APPEAR_IMAGE)),
        ))
        assertFalse(s.dependsOnText())
    }
}

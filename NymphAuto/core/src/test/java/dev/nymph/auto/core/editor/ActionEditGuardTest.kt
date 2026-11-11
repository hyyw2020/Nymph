package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEditGuardTest {

    /** 回归 v2.6.0 已知数据丢失：编辑坐标不得清空 children/listeners/条件/模板。 */
    private fun richTap(): Action = Action(
        type = ActionType.TAP,
        x = 12.5, y = 30.0, unit = CoordUnit.PCT,
        imageData = "BASE64_TEMPLATE_DATA_LONG_ENOUGH_FOR_TEST_1234567890",
        region = Region(1.0, 2.0, 3.0, 4.0),
        children = listOf(Action(type = ActionType.WAIT, waitMs = 500)),
        listeners = Listeners(onActionOk = listOf(Action(type = ActionType.TOAST, text = "ok"))),
        condition = Condition(jsExpr = "1==1"),
        postCondition = Condition(jsExpr = "2==2"),
        jsCode = "var a=1;",
        packageName = "com.example",
    )

    @Test
    fun `编辑简易类型时保留全部非编辑字段`() {
        val a = richTap()
        val edited = ActionEditGuard.apply(
            a,
            ActionEditGuard.Patch(kind = ActionEditGuard.KIND_TAP, x = 55.0, y = 66.0, desc = "新描述"),
        )
        assertEquals(55.0, edited.x, 0.0)
        assertEquals(66.0, edited.y, 0.0)
        assertEquals("新描述", edited.desc)
        // 以下字段 v2.6.0 会被静默清空
        assertEquals(a.children, edited.children)
        assertEquals(a.listeners, edited.listeners)
        assertEquals(a.condition, edited.condition)
        assertEquals(a.postCondition, edited.postCondition)
        assertEquals(a.imageData, edited.imageData)
        assertEquals(a.region, edited.region)
        assertEquals(a.jsCode, edited.jsCode)
        assertEquals(a.packageName, edited.packageName)
    }

    @Test
    fun `非简易类型返回原对象 不被静默改成点击`() {
        val wait = Action(type = ActionType.WAIT, waitMs = 3000, children = listOf(Action(type = ActionType.TAP)))
        assertEquals(ActionEditGuard.KIND_UNSUPPORTED, ActionEditGuard.kindOf(wait))
        assertFalse(ActionEditGuard.supportsSimpleEdit(wait))
        val out = ActionEditGuard.apply(
            wait, ActionEditGuard.Patch(kind = ActionEditGuard.KIND_TAP, x = 50.0, y = 50.0),
        )
        assertSame(wait, out)
        assertEquals(ActionType.WAIT, out.type)
        assertEquals(3000L, out.waitMs)
        assertEquals(1, out.children.size)
    }

    @Test
    fun `kindOf 与 typeOf 覆盖四种简易类型`() {
        assertEquals(0, ActionEditGuard.kindOf(Action(type = ActionType.TAP)))
        assertEquals(1, ActionEditGuard.kindOf(Action(type = ActionType.LONG_PRESS)))
        assertEquals(2, ActionEditGuard.kindOf(Action(type = ActionType.MULTI_TAP)))
        assertEquals(3, ActionEditGuard.kindOf(Action(type = ActionType.SWIPE)))
        assertEquals(ActionType.SWIPE, ActionEditGuard.typeOf(3))
        assertEquals(null, ActionEditGuard.typeOf(-1))
    }

    @Test
    fun `切换类型也基于copy保真`() {
        val a = richTap()
        val out = ActionEditGuard.apply(
            a, ActionEditGuard.Patch(
                kind = ActionEditGuard.KIND_SWIPE,
                x = 10.0, y = 20.0, x2 = 80.0, y2 = 90.0, durationMs = 500,
            ),
        )
        assertEquals(ActionType.SWIPE, out.type)
        assertEquals(80.0, out.x2, 0.0)
        assertEquals(a.children, out.children)
        assertEquals(a.listeners, out.listeners)
        assertNotEquals(a.type, out.type)
    }

    @Test
    fun `patchOf 往返不丢字段`() {
        val a = richTap()
        val roundtrip = ActionEditGuard.apply(a, ActionEditGuard.patchOf(a))
        assertEquals(a.type, roundtrip.type)
        assertEquals(a.x, roundtrip.x, 0.0)
        assertEquals(a.y, roundtrip.y, 0.0)
        assertEquals(a.children, roundtrip.children)
        assertEquals(a.listeners, roundtrip.listeners)
        assertEquals(a.condition, roundtrip.condition)
        assertTrue(roundtrip.imageData == a.imageData)
    }

    @Test
    fun `非法kind原样返回`() {
        val a = richTap()
        assertSame(a, ActionEditGuard.apply(a, ActionEditGuard.Patch(kind = 99, x = 1.0, y = 1.0)))
    }
}

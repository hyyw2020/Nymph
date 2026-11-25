package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule
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

    // ---------------------------------------------------------------- v3.0.3 S5 完整档

    /** S3 编译产物形状：TAP 载体 + COMBINE(ALL) 门控 + sceneRule，特征在 children。 */
    private fun compiledCarrier(): Action = Action(
        type = ActionType.TAP, desc = "画布·点1", x = 40.0, y = 60.0,
        condition = Condition(
            kind = ConditionKind.COMBINE, combine = Combine.ALL,
            children = listOf(
                Condition(kind = ConditionKind.APPEAR_IMAGE, imageData = "TPL_A", imageSimilarity = 82,
                    region = Region(10.0, 10.0, 30.0, 30.0)),
                Condition(kind = ConditionKind.APPEAR_IMAGE, imageData = "TPL_B", imageSimilarity = 85,
                    region = Region(50.0, 50.0, 70.0, 70.0)),
            ),
        ),
        sceneRule = SceneRule(similarity = 82, cooldownMs = 500, priority = 3),
    )

    @Test
    fun `S5未传视觉字段时逐字保真_null哨兵零回归`() {
        val a = compiledCarrier()
        val out = ActionEditGuard.apply(
            a, ActionEditGuard.Patch(kind = ActionEditGuard.KIND_TAP, x = 41.0, y = 61.0),
        )
        assertEquals(a.condition, out.condition)
        assertEquals(a.sceneRule, out.sceneRule)
        assertEquals(a.imageData, out.imageData)
        assertEquals(a.imageSimilarity, out.imageSimilarity)
        assertEquals(a.region, out.region)
        assertEquals(41.0, out.x, 0.0)
    }

    @Test
    fun `S5显式改imageData与相似度与region真正写入`() {
        val a = compiledCarrier()
        val newRegion = Region(1.0, 2.0, 3.0, 4.0)
        val out = ActionEditGuard.apply(
            a, ActionEditGuard.Patch(
                kind = ActionEditGuard.KIND_TAP, x = a.x, y = a.y,
                imageData = "NEW_TPL", imageSimilarity = 91, region = newRegion,
            ),
        )
        assertEquals("NEW_TPL", out.imageData)
        assertEquals(91, out.imageSimilarity)
        assertEquals(newRegion, out.region)
        // 未传 condition/sceneRule 仍保真
        assertEquals(a.condition, out.condition)
        assertEquals(a.sceneRule, out.sceneRule)
    }

    @Test
    fun `S5合取门控整棵condition可替换_children逐条生效`() {
        val a = compiledCarrier()
        val editedGate = Condition(
            kind = ConditionKind.COMBINE, combine = Combine.N, runCountLimit = 2,
            children = listOf(
                Condition(kind = ConditionKind.APPEAR_IMAGE, imageData = "TPL_C", imageSimilarity = 93,
                    region = Region(2.0, 3.0, 4.0, 5.0)),
            ),
        )
        val out = ActionEditGuard.apply(
            a, ActionEditGuard.Patch(
                kind = ActionEditGuard.KIND_TAP, x = a.x, y = a.y, condition = editedGate,
            ),
        )
        // 整棵替换：combine / N 阈值 / children 条数与逐条字段全部按调用方构造落盘，
        // 守卫不递归清洗 Condition 树（UI 滑块已把相似度约束在 0..100）。
        assertEquals(Combine.N, out.condition?.combine)
        assertEquals(2, out.condition?.runCountLimit)
        assertEquals(1, out.condition?.children?.size)
        assertEquals("TPL_C", out.condition?.children?.first()?.imageData)
        assertEquals(93, out.condition?.children?.first()?.imageSimilarity)
        assertEquals(Region(2.0, 3.0, 4.0, 5.0), out.condition?.children?.first()?.region)
    }

    @Test
    fun `S5场景规则可替换_视觉动作工作台可编辑且type不被重映射`() {
        val clickImg = Action(type = ActionType.CLICK_IMAGE, imageData = "IMG", imageSimilarity = 80)
        // 悬浮面板契约：视觉类型不算「简易」、kindOf=-1（只读保护不破坏）。
        assertFalse(ActionEditGuard.supportsSimpleEdit(clickImg))
        assertEquals(ActionEditGuard.KIND_UNSUPPORTED, ActionEditGuard.kindOf(clickImg))
        // 统一工作台契约：isVisionType=true，显式传 KIND_VISION 即可编辑且 type 不改。
        assertTrue(ActionEditGuard.isVisionType(clickImg))

        val newRule = SceneRule(similarity = 88, cooldownMs = 1200, priority = 7)
        val out = ActionEditGuard.apply(
            clickImg, ActionEditGuard.Patch(
                kind = ActionEditGuard.KIND_VISION, x = 0.0, y = 0.0,
                desc = "改过的图片动作", imageSimilarity = 77, sceneRule = newRule,
            ),
        )
        assertEquals("CLICK_IMAGE", out.type.name)
        assertEquals("改过的图片动作", out.desc)
        assertEquals(77, out.imageSimilarity)
        assertEquals(newRule, out.sceneRule)
        assertEquals("IMG", out.imageData) // 未传 imageData 保真
    }

    @Test
    fun `S5的patchOf往返保留视觉门控字段`() {
        val a = compiledCarrier()
        val roundtrip = ActionEditGuard.apply(a, ActionEditGuard.patchOf(a))
        assertEquals(a.condition, roundtrip.condition)
        assertEquals(a.sceneRule, roundtrip.sceneRule)
        assertEquals(ActionEditGuard.KIND_TAP, ActionEditGuard.kindOf(roundtrip))
    }

    // ── ADR-075 门禁①（运行时可测）：任意 Action 无只读、确定可用、门控字段可见可改 ──
    @Test
    fun `门禁一_任意CLICK_IMAGE工作台可编辑且确定可用`() {
        val clickImg = Action(type = ActionType.CLICK_IMAGE, imageData = "IMG", imageSimilarity = 80)
        // 工作台侧：无条件可编辑（无只读门槛），这是确定按钮 enabled 的真源。
        assertTrue("任意 CLICK_IMAGE 可编辑", ActionEditGuard.isWorkbenchEditable(clickImg))
        // 悬浮面板侧：只读契约保留，不因工作台开放而外溢。
        assertFalse("悬浮面板只读契约保留", ActionEditGuard.supportsSimpleEdit(clickImg))
    }

    @Test
    fun `门禁一_任意类型均isWorkbenchEditable_true含非简易非视觉`() {
        // 覆盖 30 类动作：RUN_JS / WAIT / SET_VAR 等复杂类型也不再有「只读」门槛。
        listOf(
            ActionType.TAP, ActionType.CLICK_IMAGE, ActionType.RUN_JS,
            ActionType.WAIT, ActionType.SET_VAR, ActionType.MULTI_FINGER,
        ).forEach { t ->
            assertTrue("$t 工作台可编辑", ActionEditGuard.isWorkbenchEditable(Action(type = t)))
        }
    }

    @Test
    fun `门禁一_CLICK_IMAGE的condition与sceneRule可见可改且type不被重映射`() {
        val a = Action(
            type = ActionType.CLICK_IMAGE, imageData = "IMG", imageSimilarity = 70,
            condition = Condition(kind = ConditionKind.APPEAR_IMAGE, imageData = "OLD", imageSimilarity = 60),
            sceneRule = SceneRule(similarity = 55),
        )
        val newCond = Condition(kind = ConditionKind.APPEAR_IMAGE, imageData = "NEW", imageSimilarity = 90)
        val newRule = SceneRule(similarity = 85, cooldownMs = 300)
        val out = ActionEditGuard.apply(
            a, ActionEditGuard.Patch(
                kind = ActionEditGuard.KIND_VISION, x = 0.0, y = 0.0,
                condition = newCond, sceneRule = newRule,
            ),
        )
        assertEquals("CLICK_IMAGE", out.type.name)      // type 不被重映射
        assertEquals(newCond, out.condition)             // condition 可见可改
        assertEquals(newRule, out.sceneRule)             // sceneRule 可见可改
        assertEquals("IMG", out.imageData)               // 未传字段保真
    }
}

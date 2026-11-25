// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.vision.RoiExpr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景触发（Scene → Trigger）v2.9.14 回归防线：自动 ROI 几何、场景规则装配保真、
 * 边沿触发调度。全部纯 JVM，锁定「消除步骤概念」这条语义转变不回退。
 */
class SceneTriggerTest {

    // ------------------------------------------------------------ autoRoi 几何

    @Test
    fun `中心拾取框出物理正方形 ROI 且 expr 可被 RoiExpr 解析`() {
        // 横屏 1768×800：短边 800，半边 96px → x 轴 ±5.43%，y 轴 ±12%
        val r = SceneTrigger.autoRoi(50.0, 50.0, 1768, 800)
        assertTrue("左应在 [0,100]", r.leftPct >= 0.0 && r.leftPct <= 100.0)
        assertTrue("右应大于左", r.rightPct > r.leftPct)
        assertTrue("下应大于上", r.bottomPct > r.topPct)
        // 物理像素近似正方形（容差 1px）
        val wPx = (r.rightPct - r.leftPct) / 100.0 * 1768
        val hPx = (r.bottomPct - r.topPct) / 100.0 * 800
        assertTrue("横屏 ROI 物理上应近似正方形: w=$wPx h=$hPx", kotlin.math.abs(wPx - hPx) < 1.0)
        val resolved = RoiExpr.resolve(r.expr)
        assertNotNull("expr 四元组必须能被运行时解析", resolved)
    }

    @Test
    fun `竖屏与横屏对称 物理尺寸一致`() {
        val portrait = SceneTrigger.autoRoi(50.0, 50.0, 800, 1768)
        val landscape = SceneTrigger.autoRoi(50.0, 50.0, 1768, 800)
        val pw = (portrait.rightPct - portrait.leftPct) / 100.0 * 800
        val ph = (portrait.bottomPct - portrait.topPct) / 100.0 * 1768
        val lw = (landscape.rightPct - landscape.leftPct) / 100.0 * 1768
        val lh = (landscape.bottomPct - landscape.topPct) / 100.0 * 800
        assertTrue("竖屏 ROI 近似正方形: $pw x $ph", kotlin.math.abs(pw - ph) < 1.0)
        assertTrue("横竖屏 ROI 物理边长应一致: $pw vs $lw", kotlin.math.abs(pw - lw) < 1.0)
    }

    @Test
    fun `贴边拾取 ROI 不越界且保持最小跨度`() {
        for ((x, y) in listOf(0.0 to 0.0, 100.0 to 0.0, 0.0 to 100.0, 100.0 to 100.0, 2.0 to 50.0)) {
            val r = SceneTrigger.autoRoi(x, y, 1080, 2400)
            assertTrue("left>=0 @($x,$y): ${r.leftPct}", r.leftPct >= 0.0)
            assertTrue("top>=0 @($x,$y): ${r.topPct}", r.topPct >= 0.0)
            assertTrue("right<=100 @($x,$y): ${r.rightPct}", r.rightPct <= 100.0)
            assertTrue("bottom<=100 @($x,$y): ${r.bottomPct}", r.bottomPct <= 100.0)
            assertTrue("right>left @($x,$y)", r.rightPct > r.leftPct)
            assertTrue("bottom>top @($x,$y)", r.bottomPct > r.topPct)
            assertNotNull(RoiExpr.resolve(r.expr))
        }
    }

    @Test
    fun `非法输入与极端屏幕不崩 半径被 clamp`() {
        val nan = SceneTrigger.autoRoi(Double.NaN, Double.NaN, 0, 0, Double.POSITIVE_INFINITY)
        assertTrue(nan.leftPct >= 0.0 && nan.rightPct <= 100.0)
        assertTrue(nan.rightPct > nan.leftPct && nan.bottomPct > nan.topPct)
        val tiny = SceneTrigger.autoRoi(50.0, 50.0, 1, 1, 0.49)
        assertTrue("极小屏也要合法 ROI", tiny.rightPct > tiny.leftPct && tiny.bottomPct > tiny.topPct)
    }

    // ------------------------------------------------------------ cropPx

    @Test
    fun `cropPx 按位图尺寸换算且裁剪矩形合法`() {
        val r = Region(25.0, 25.0, 75.0, 75.0)
        val px = SceneTrigger.cropPx(r, 1000, 2000)
        assertEquals(4, px.size)
        assertEquals(250, px[0]); assertEquals(500, px[1])
        assertEquals(750, px[2]); assertEquals(1500, px[3])
        assertTrue(px[2] > px[0] && px[3] > px[1])
        assertTrue("零尺寸位图返回空", SceneTrigger.cropPx(r, 0, 0).isEmpty())
    }

    // ------------------------------------------------------------ attachScene

    @Test
    fun `有模板挂 APPEAR_IMAGE 门控且 copy 保真不丢字段`() {
        val child = Action(type = ActionType.WAIT, desc = "子动作")
        val listener = Action(type = ActionType.TOAST, desc = "监听")
        val original = Action(
            type = ActionType.TAP, x = 42.0, y = 68.0, desc = "领奖",
            children = listOf(child), listeners = Listeners(beforeAction = listOf(listener)),
            postCondition = Condition(kind = ConditionKind.APPEAR_TEXT, text = "已领取"),
        )
        val roi = SceneTrigger.autoRoi(42.0, 68.0, 1080, 2400)
        val (armed, hasGate) = SceneTrigger.attachScene(original, roi, "BASE64TEMPLATE")
        assertTrue("有模板应启用门控", hasGate)
        assertEquals("ROI 应写入 region", roi, armed.region)
        assertFalse("waitAppear 应关闭（等待交给外层循环）", armed.waitAppear)
        assertEquals("坐标不变", 42.0, armed.x, 0.0)
        assertEquals("y 不变", 68.0, armed.y, 0.0)
        assertEquals("desc 不变", "领奖", armed.desc)
        assertSame("children 保真", original.children, armed.children)
        assertSame("listeners 保真", original.listeners, armed.listeners)
        assertSame("postCondition 保真", original.postCondition, armed.postCondition)
        assertEquals(ConditionKind.APPEAR_IMAGE, armed.condition?.kind)
        assertEquals("BASE64TEMPLATE", armed.condition?.imageData)
        assertFalse("门控不应再触发无限轮询", armed.condition?.repeatCheckUntilOk ?: true)
        assertTrue(SceneTrigger.isSceneTrigger(armed))
        assertNotNull(SceneTrigger.sceneGateOf(armed))
    }

    @Test
    fun `模板为空显式降级 只框 ROI 不挂图像门控`() {
        val original = Action(type = ActionType.TAP, x = 10.0, y = 20.0)
        val roi = SceneTrigger.autoRoi(10.0, 20.0, 800, 1768)
        val (armed, hasGate) = SceneTrigger.attachScene(original, roi, "")
        assertFalse("无模板必须标记未启用门控", hasGate)
        assertEquals(roi, armed.region)
        assertNull("不应挂图像门控条件", armed.condition)
        assertFalse(SceneTrigger.isSceneTrigger(armed))
        assertNull(SceneTrigger.sceneGateOf(armed))
    }

    @Test
    fun `老脚本裸动作不被误判为场景规则`() {
        val bare = Action(type = ActionType.TAP, x = 50.0, y = 50.0)
        assertFalse(SceneTrigger.isSceneTrigger(bare))
        assertNull(SceneTrigger.sceneGateOf(bare))
        val waitAppearOnly = bare.copy(waitAppear = true, condition = Condition(kind = ConditionKind.JS_EXPR))
        assertFalse(SceneTrigger.isSceneTrigger(waitAppearOnly))
    }

    // ------------------------------------------------------------ planSceneFiring（消除步骤概念的核心）

    @Test
    fun `全不出现时不触发任何规则`() {
        val (fire, present) = SceneTrigger.planSceneFiring(listOf(false, false, false), emptySet())
        assertTrue("没有场景出现不应推进任何步", fire.isEmpty())
        assertTrue(present.isEmpty())
    }

    @Test
    fun `场景出现即触发 且按规则下标而非步骤顺序`() {
        // 第 3 条规则的场景出现 → 触发 index 2，不从 0 开始顺序推进
        val (fire, present) = SceneTrigger.planSceneFiring(listOf(false, false, true, false), emptySet())
        assertEquals(listOf(2), fire)
        assertEquals(setOf(2), present)
    }

    @Test
    fun `多个场景同时出现 一并触发`() {
        val (fire, _) = SceneTrigger.planSceneFiring(listOf(true, false, true, true), emptySet())
        assertEquals(listOf(0, 2, 3), fire)
    }

    @Test
    fun `边沿触发 场景常驻不重复触发`() {
        val prev = setOf(1)
        val (fire, present) = SceneTrigger.planSceneFiring(listOf(true, true, false), prev)
        assertTrue("已常驻的 index1 不再触发", !fire.contains(1))
        assertEquals(listOf(0), fire)   // index0 是从无到有的新场景
        assertEquals(setOf(0, 1), present)
    }

    @Test
    fun `场景消失再出现会再次触发`() {
        val (f1, p1) = SceneTrigger.planSceneFiring(listOf(true), emptySet())
        assertEquals(listOf(0), f1)
        val (f2, p2) = SceneTrigger.planSceneFiring(listOf(false), p1)  // 场景消失
        assertTrue(f2.isEmpty()); assertTrue(p2.isEmpty())
        val (f3, _) = SceneTrigger.planSceneFiring(listOf(true), p2)    // 再次出现
        assertEquals(listOf(0), f3)
    }

    @Test
    fun `空规则集合安全`() {
        val (fire, present) = SceneTrigger.planSceneFiring(emptyList(), setOf(0, 1))
        assertTrue(fire.isEmpty()); assertTrue(present.isEmpty())
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `sceneLabel 用场景话术替换步骤话术`() {
        assertEquals("场景 2 / 5", SceneTrigger.sceneLabel(1, 5))
        assertEquals("场景 1 / 3 · 领奖", SceneTrigger.sceneLabel(0, 3, "领奖"))
        assertFalse(SceneTrigger.sceneLabel(0, 1).contains("步"))
    }
}

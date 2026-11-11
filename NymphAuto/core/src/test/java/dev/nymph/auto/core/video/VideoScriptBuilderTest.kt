package dev.nymph.auto.core.video

import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.ConditionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScriptBuilderTest {

    private val tpl = "iVBORw0KGgo" + "A".repeat(300)

    @Test
    fun `有模板生成找图点击 按时间排序并保留间隔`() {
        val s = VideoScriptBuilder.build(
            "V",
            listOf(
                VideoScriptBuilder.Tap(1200, 30.0, 40.0, tpl),
                VideoScriptBuilder.Tap(300, 10.0, 20.0, tpl),
            ),
            1080, 2400,
            opts = VideoScriptBuilder.Options(waitForAppearance = false),
        )
        assertEquals(2, s.actions.size)
        assertEquals(ActionType.CLICK_IMAGE, s.actions[0].type)
        assertEquals(ActionType.CLICK_IMAGE, s.actions[1].type)
        assertTrue(s.actions[0].desc.contains("300ms")) // 已按时间排序
        assertEquals(0L, s.actions[0].waitMs)
        assertEquals(900L, s.actions[1].waitMs)
        assertEquals(tpl, s.actions[0].imageData)
        assertEquals(1080, s.refWidth)
        assertEquals(2400, s.refHeight)
    }

    @Test
    fun `模板缺失如实降级为坐标点击 不假装找图`() {
        val s = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 12.5, 77.0, "")), 720, 1280,
        )
        assertEquals(ActionType.TAP, s.actions.single().type)
        assertEquals(12.5, s.actions.single().x, 0.0)
        assertEquals(77.0, s.actions.single().y, 0.0)
        assertTrue(s.actions.single().desc.contains("坐标兜底"))
    }

    @Test
    fun `间隔被钳制到上限`() {
        val s = VideoScriptBuilder.build(
            "V",
            listOf(
                VideoScriptBuilder.Tap(0, 10.0, 10.0, tpl),
                VideoScriptBuilder.Tap(60_000, 20.0, 20.0, tpl),
            ),
            100, 100,
            opts = VideoScriptBuilder.Options(waitForAppearance = false),
        )
        assertEquals(3000L, s.actions[1].waitMs)
    }

    @Test
    fun `空候选生成空脚本`() {
        val s = VideoScriptBuilder.build("空", emptyList(), 0, 0)
        assertTrue(s.actions.isEmpty())
        assertEquals("空", s.name)
    }

    // ---- v2.9.2：视频导入产物支持循环（真机差评「重复操作跑不了」）----

    @Test
    fun `不传循环参数时默认执行一次 老调用零回归`() {
        val s = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
        )
        assertEquals(1, s.repeatCount)
    }

    @Test
    fun `传入 0 生成无限循环脚本`() {
        val s = VideoScriptBuilder.build(
            "循环", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
            repeatCount = 0,
        )
        assertEquals(0, s.repeatCount)
        assertTrue(s.description.contains("无限循环"))
    }

    @Test
    fun `指定轮数与越界值都经 LoopPolicy 归一化`() {
        val s5 = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
            repeatCount = 5,
        )
        assertEquals(5, s5.repeatCount)
        assertTrue(s5.description.contains("5"))

        val sBad = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
            repeatCount = -3,
        )
        assertEquals(1, sBad.repeatCount)

        val sHuge = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
            repeatCount = 999999,
        )
        assertEquals(9999, sHuge.repeatCount)
    }

    @Test
    fun `循环字段不影响动作生成与找图锚定`() {
        val base = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
        )
        val looped = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
            repeatCount = 0,
        )
        assertEquals(base.actions.map { it.type }, looped.actions.map { it.type })
        assertEquals(base.actions.map { it.imageData }, looped.actions.map { it.imageData })
        assertEquals(0, looped.repeatCount)
    }

    @Test
    fun `自定义 minGap 大于 maxGap 不再抛空区间异常`() {
        // v2.9.4：旧实现直接 coerceIn(min, max)，min > max 时抛
        // IllegalArgumentException，整条视频导入链路崩溃。
        val s = VideoScriptBuilder.build(
            "V",
            listOf(
                VideoScriptBuilder.Tap(0, 10.0, 10.0, tpl),
                VideoScriptBuilder.Tap(5000, 20.0, 20.0, tpl),
                VideoScriptBuilder.Tap(5200, 30.0, 30.0, tpl),
            ),
            1080, 2400,
            opts = VideoScriptBuilder.Options(
                minGapMs = 3000L, maxGapMs = 100L, waitForAppearance = false,
            ),
        )
        assertEquals(3, s.actions.size)
        // 归一化后区间为 [100, 3000]，所有间隔都落在其中且非负
        s.actions.drop(1).forEach { a ->
            assertTrue("waitMs=${a.waitMs}", a.waitMs in 100L..3000L)
        }
    }

    // ---- v2.9.6：门控模式（真机差评「不支持复杂操作」）----

    @Test
    fun `默认开启门控 找图动作等到画面出现且不再依赖固定延时`() {
        val s = VideoScriptBuilder.build(
            "V",
            listOf(
                VideoScriptBuilder.Tap(0, 10.0, 20.0, tpl),
                // 结算「确认」按钮在约 12 秒开箱动画之后才出现
                VideoScriptBuilder.Tap(45_000, 50.0, 80.0, tpl),
            ),
            720, 328,
        )
        assertEquals(2, s.actions.size)
        s.actions.forEach { a ->
            assertEquals(ActionType.CLICK_IMAGE, a.type)
            assertTrue(a.waitAppear)
            val cond = requireNotNull(a.condition)
            assertEquals(ConditionKind.APPEAR_IMAGE, cond.kind)
            assertTrue(cond.repeatCheckUntilOk)
            assertEquals(WaitGate.INFINITE, cond.repeatCheckMax)
            assertEquals(0L, a.waitMs) // 门控即同步点，不猜动画时长
        }
        assertTrue(s.description.contains("门控模式"))
    }

    @Test
    fun `门控条件的模板与动作模板完全一致 避免等错画面`() {
        val s = VideoScriptBuilder.build(
            "V", listOf(VideoScriptBuilder.Tap(100, 10.0, 20.0, tpl)), 720, 1280,
        )
        val a = s.actions.single()
        val cond = requireNotNull(a.condition)
        assertEquals(a.imageData, cond.imageData)
        assertEquals(a.imageSimilarity, cond.imageSimilarity)
    }

    @Test
    fun `模板缺失动作在门控模式下如实降级为坐标加固定延时`() {
        val s = VideoScriptBuilder.build(
            "V",
            listOf(
                VideoScriptBuilder.Tap(0, 10.0, 10.0, tpl),
                VideoScriptBuilder.Tap(2000, 50.0, 50.0, ""),
            ),
            720, 1280,
        )
        assertEquals(0L, s.actions[0].waitMs) // 有模板 → 门控
        val tap = s.actions[1]
        assertEquals(ActionType.TAP, tap.type)
        assertFalse(tap.waitAppear)
        assertNull(tap.condition)
        assertEquals(2000L, tap.waitMs)       // 无模板可等 → 保留延时
        assertTrue(s.description.contains("1 个模板缺失"))
    }

    @Test
    fun `关闭门控完全回到 v2_9_5 固定延时行为`() {
        val s = VideoScriptBuilder.build(
            "V",
            listOf(
                VideoScriptBuilder.Tap(0, 10.0, 10.0, tpl),
                VideoScriptBuilder.Tap(2500, 50.0, 50.0, tpl),
            ),
            720, 1280,
            opts = VideoScriptBuilder.Options(waitForAppearance = false),
        )
        s.actions.forEach { a ->
            assertFalse(a.waitAppear)
            assertNull(a.condition)
        }
        assertEquals(0L, s.actions[0].waitMs)
        assertEquals(2500L, s.actions[1].waitMs)
        assertTrue(s.description.contains("固定延时模式"))
    }
}

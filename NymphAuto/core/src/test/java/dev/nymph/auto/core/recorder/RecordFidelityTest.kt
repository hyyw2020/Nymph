// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0 录制保真度回归测试。
 *
 * 真机录屏复现的问题：用户在应用抽屉里 12 秒连续翻页（39 次 TYPE_VIEW_SCROLLED，
 * 其中多数 scrollDelta=0），旧版编译成 15 个「起点=终点」的零长度 SWIPE，
 * 回放退化成点击/长按，误触打开内容页；且缺 400ms 长滑动 + 30s 等待上限，
 * 12s 操作被拖成 18.9s。这里逐项锁死修复行为。
 */
class RecordFidelityTest {

    private val compiler = RecordCompiler()

    private fun scroll(
        at: Long, dy: Int = 0, dx: Int = 0,
        pkg: String = "com.miui.home",
    ) = RecordEvent(
        atMs = at, kind = RecordEventKind.SCROLL,
        x = 540, y = 1400, dx = dx, dy = dy,
        screenW = 1080, screenH = 2400,
        node = RecordNode(packageName = pkg, scrollable = true),
    )

    private fun swipes(events: List<RecordEvent>) =
        compiler.compile("翻页", events).script.actions.filter { it.type == ActionType.SWIPE }

    // ---- 1) 零长度 SWIPE 必须彻底消失 ----

    @Test
    fun `滚动增量为零时生成有长度的标准翻页而非零长度点击`() {
        val sw = swipes(listOf(scroll(0))).single()
        val travel = kotlin.math.abs(sw.x2 - sw.x) + kotlin.math.abs(sw.y2 - sw.y)
        assertTrue("翻页行程必须大于 0，实际 $travel", travel >= 12.0)
        assertEquals(34.0, travel, 1e-6) // 默认受控幅度
    }

    @Test
    fun `滚动增量异常巨大时幅度仍受控不会被拉到屏幕边缘`() {
        val sw = swipes(listOf(scroll(0, dy = 9000))).single()
        val travel = kotlin.math.abs(sw.y2 - sw.y)
        assertTrue("幅度不得随内容滚动量放大，实际 $travel", travel <= 60.0)
        assertTrue(sw.y >= 8.0 && sw.y2 >= 8.0)
        assertTrue(sw.y <= 92.0 && sw.y2 <= 92.0)
    }

    @Test
    fun `起终点都落在安全边距内不贴边`() {
        val opts = RecordOptions(swipeSpanPct = 34.0, swipeSafeMarginPct = 14.0)
        val sw = RecordCompiler(opts).compile("翻页", listOf(scroll(0, dy = 120)))
            .script.actions.single { it.type == ActionType.SWIPE }
        listOf(sw.x, sw.y, sw.x2, sw.y2).forEach {
            assertTrue("坐标 $it 应落在 [14,86]", it in 14.0..86.0 || it == 50.0)
        }
    }

    // ---- 2) 方向只取符号，主轴自动判定 ----

    @Test
    fun `内容上滚对应手指向上滑且主轴为纵向`() {
        val sw = swipes(listOf(scroll(0, dy = 100))).single()
        assertTrue("dy>0 时终点应在起点上方，实际 ${sw.y}→${sw.y2}", sw.y2 < sw.y)
        assertEquals(sw.x, sw.x2, 1e-6)
    }

    @Test
    fun `内容下滚对应手指向下滑`() {
        val sw = swipes(listOf(scroll(0, dy = -100))).single()
        assertTrue("dy<0 时终点应在起点下方，实际 ${sw.y}→${sw.y2}", sw.y2 > sw.y)
    }

    @Test
    fun `横向滚动走横向主轴`() {
        val sw = swipes(listOf(scroll(0, dx = 100))).single()
        assertTrue(sw.x2 != sw.x)
        assertEquals(sw.y, sw.y2, 1e-6)
    }

    // ---- 3) 连续翻页合并为「次数」而非超长单次滑动 ----

    @Test
    fun `连续同向滚动折叠成一次带次数的快速翻页`() {
        val events = (0 until 6).map { scroll(it * 120L, dy = 0) }
        val result = compiler.compile("连翻", events)
        val list = result.script.actions.filter { it.type == ActionType.SWIPE }
        assertEquals("6 次滚动应折叠为 1 个动作", 1, list.size)
        assertEquals(6, list.single().times)
        assertEquals(220L, list.single().durationMs)
        assertTrue(list.single().gapMs > 0)
        assertEquals(5, result.report.scrollsMerged)
    }

    @Test
    fun `连续翻页次数有上限超出则新开一组`() {
        val events = (0 until 15).map { scroll(it * 100L, dy = 0) }
        val opts = RecordOptions(maxSwipeTimes = 6)
        val list = RecordCompiler(opts).compile("连翻", events)
            .script.actions.filter { it.type == ActionType.SWIPE }
        assertTrue("应拆成多组，实际 ${list.size}", list.size >= 3)
        assertTrue(list.all { it.times <= 6 })
        assertEquals(15, list.sumOf { it.times })
    }

    @Test
    fun `方向反转不会被误合并成同向`() {
        val events = listOf(scroll(0, dy = 100), scroll(100, dy = 100), scroll(200, dy = -100))
        val list = swipes(events)
        assertEquals(2, list.size)
    }

    @Test
    fun `横竖混合滚动不会串成一组`() {
        val events = listOf(scroll(0, dy = 100), scroll(100, dy = 100), scroll(200, dx = 100))
        assertEquals(2, swipes(events).size)
    }

    // ---- 4) 节奏：翻页动作等待被收紧 ----

    @Test
    fun `翻页动作前的长等待被收紧到上限`() {
        val events = listOf(scroll(0), scroll(20_000))
        val list = swipes(events)
        assertEquals(2, list.size)
        assertTrue("翻页等待不得超过 500ms，实际 ${list.last().waitMs}", list.last().waitMs <= 500L)
    }

    // ---- 5) 起始界面与诚实告警 ----

    @Test
    fun `录制起始包名写入脚本供回放前校验`() {
        val script = compiler.compile("抽屉翻页", listOf(scroll(0), scroll(100))).script
        assertEquals("com.miui.home", script.startPackage)
    }

    @Test
    fun `方向不可知时如实给出告警`() {
        val r = compiler.compile("翻页", listOf(scroll(0)))
        assertTrue(r.report.warnings.any { it.contains("未上报方向") })
        assertTrue(r.script.description.contains("未上报方向"))
    }

    @Test
    fun `含滚动的脚本描述提示手动回到起始界面`() {
        val s = compiler.compile("翻页", listOf(scroll(0))).script
        assertTrue(s.description.contains("手动回到录制起始界面"))
    }

    @Test
    fun `老脚本无起始包名时行为不变`() {
        val s = Script(name = "手写", actions = emptyList())
        assertEquals("", s.startPackage)
        // 明文 JSON 往返兼容：老 JSON 缺 startPackage 仍可解析
        val decoded = ScriptCodec.decode(ScriptCodec.encode(s))
        assertEquals("", decoded!!.startPackage)
    }

    @Test
    fun `场景守卫决策覆盖全部四种情况`() {
        val launchable = { p: String -> p == "com.demo.app" }
        assertEquals(SceneGuard.Decision.SKIP, SceneGuard.decide("", "com.demo.app", launchable).decision)
        assertEquals(SceneGuard.Decision.SKIP, SceneGuard.decide("com.demo.app", null, launchable).decision)
        assertEquals(SceneGuard.Decision.PROCEED, SceneGuard.decide("com.demo.app", "com.demo.app", launchable).decision)
        assertEquals(SceneGuard.Decision.RESTORE_APP, SceneGuard.decide("com.demo.app", "com.other", launchable).decision)
        assertEquals(SceneGuard.Decision.REQUIRE_MANUAL, SceneGuard.decide("com.miui.home", "com.other", launchable).decision)
    }

    @Test
    fun `自家界面不参与界面校验`() {
        val launchable = { _: String -> true }
        assertEquals(
            SceneGuard.Decision.SKIP,
            SceneGuard.decide("com.demo.app", "dev.nymph.auto", launchable).decision,
        )
    }

    @Test
    fun `无滚动的脚本不产生回到界面提示`() {
        val click = RecordEvent(
            atMs = 0, kind = RecordEventKind.CLICK, x = 300, y = 600,
            screenW = 1080, screenH = 2400,
            node = RecordNode(packageName = "com.demo.app", viewId = "com.demo.app:id/ok", text = "确定"),
        )
        val r = compiler.compile("单点", listOf(click))
        assertFalse(r.script.description.contains("手动回到录制起始界面"))
    }
}

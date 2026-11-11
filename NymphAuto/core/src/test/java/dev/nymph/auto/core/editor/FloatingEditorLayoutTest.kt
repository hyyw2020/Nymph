// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 系统级悬浮动作编辑器的纯 JVM 逻辑回归（v2.8.0）。
 *
 * 无真机环境下，窗口几何 / 折叠 / 表单容错 / 保真保存全靠这一层兜底。
 */
class FloatingEditorLayoutTest {

    // ------------------------------------------------------------ 尺寸与折叠

    @Test
    fun `折叠态远小于展开态 且展开宽高都受限 不会铺满全屏`() {
        val d = 3.0f
        val sw = 1080
        val sh = 2400
        val collapsed = FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, d)
        val expandedW = FloatingEditorLayout.dp(FloatingEditorLayout.EXPANDED_W_DP, d)
        val maxH = FloatingEditorLayout.maxHeightPx(sh)
        assertEquals(120, collapsed)
        // v2.9.8：220dp → 168dp，168*3=504（占 1080px 屏宽 46.7%）
        assertEquals(504, expandedW)
        // 对比差评里那个占 94% 屏宽 / 73% 屏高的全屏 AlertDialog：
        // 展开态宽度必须 < 屏宽 50%，高度上限必须 < 屏高 42%，折叠态 < 屏宽 15%
        assertTrue("展开宽度应 < 屏宽 50%（实测 ${expandedW * 100 / sw}%）", expandedW * 100 / sw < 50)
        assertTrue("折叠态应 < 屏宽 15%", collapsed * 100 / sw < 15)
        // v2.9.7：0.62 → 0.54；v2.9.8：0.54 → 0.40
        assertEquals((sh * 0.40f).toInt(), maxH)
        assertTrue("高度上限必须 < 屏高 42%", maxH * 100 / sh < 42)
        assertTrue("折叠态必须比展开态窄得多", collapsed * 4 < expandedW)
        // v2.9.8 结构性瘦身：展开态最大面积相对 v2.9.7 (660*1296) 明显下降
        val area297 = 660L * (sh * 0.54f).toInt()
        val area298 = expandedW.toLong() * maxH
        assertTrue("展开态最大面积应比 v2.9.7 至少缩小 35%（297=$area297 298=$area298）",
            area298 * 100 / area297 <= 65)
    }

    @Test
    fun `折叠与展开状态互切`() {
        var s = FloatingEditorLayout.State.COLLAPSED
        s = FloatingEditorLayout.toggle(s)
        assertEquals(FloatingEditorLayout.State.EXPANDED, s)
        s = FloatingEditorLayout.toggle(s)
        assertEquals(FloatingEditorLayout.State.COLLAPSED, s)
    }

    @Test
    fun `窗口位置被夹在屏内 负值与越界都收敛`() {
        val (x1, y1) = FloatingEditorLayout.clampPosition(-50, -80, 780, 1200, 1080, 2400)
        assertEquals(FloatingEditorLayout.EDGE_PX, x1)
        assertEquals(FloatingEditorLayout.EDGE_PX, y1)
        val (x2, y2) = FloatingEditorLayout.clampPosition(5000, 5000, 780, 1200, 1080, 2400)
        assertEquals(1080 - 780 - FloatingEditorLayout.EDGE_PX, x2)
        assertEquals(2400 - 1200 - FloatingEditorLayout.EDGE_PX, y2)
    }

    @Test
    fun `窗口比屏幕还大时也不产生越界坐标`() {
        val (x, y) = FloatingEditorLayout.clampPosition(300, 300, 2000, 3000, 1080, 2400)
        assertEquals(FloatingEditorLayout.EDGE_PX, x)
        assertEquals(FloatingEditorLayout.EDGE_PX, y)
    }

    @Test
    fun `拖动累加后立刻 clamp 贴边不再漂移`() {
        val (x, y) = FloatingEditorLayout.drag(100, 100, -999, -999, 780, 1200, 1080, 2400)
        assertEquals(FloatingEditorLayout.EDGE_PX, x)
        assertEquals(FloatingEditorLayout.EDGE_PX, y)
        val (x2, y2) = FloatingEditorLayout.drag(100, 100, 10, 20, 780, 1200, 1080, 2400)
        assertEquals(110, x2)
        assertEquals(120, y2)
    }

    @Test
    fun `步骤游标在范围内移动 空脚本恒为 0`() {
        assertEquals(0, FloatingEditorLayout.step(0, -1, 3))
        assertEquals(2, FloatingEditorLayout.step(2, 1, 3))
        assertEquals(1, FloatingEditorLayout.step(0, 1, 3))
        assertEquals(0, FloatingEditorLayout.step(0, 1, 0))
        assertEquals(3, FloatingEditorLayout.afterAppend(3))
    }

    // ------------------------------------------------------------ 表单容错

    @Test
    fun `空串与非法输入收敛为默认值 绝不抛异常`() {
        val p = FloatingEditorLayout.toPatch(
            FloatingEditorLayout.Form(x = "", y = "abc", times = "", waitMs = "  ", holdMs = "-1"),
        )
        assertEquals(50.0, p.x, 0.0)
        assertEquals(50.0, p.y, 0.0)
        assertEquals(1, p.times)
        assertEquals(0L, p.waitMs)
        // 负值交给 ActionEditGuard.apply 统一 clamp，此处只验证解析不丢
        assertEquals(-1L, p.holdMs)
    }

    @Test
    fun `德语区 Locale 下格式化仍是点号小数 可被 toPatch 解析回原值`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val s = FloatingEditorLayout.fmt(34.5678)
            assertFalse("不能输出逗号小数点", s.contains(','))
            val back = FloatingEditorLayout.num(s, 0.0)
            assertEquals(34.5678, back, 0.0001)
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `formOf 与 toPatch 往返保真 且非简易类型不伪装成点击`() {
        val a = Action(
            type = ActionType.SWIPE, x = 12.5, y = 34.25, x2 = 80.0, y2 = 90.0,
            durationMs = 650, times = 2, waitMs = 300, desc = "滑动翻页",
            children = listOf(Action(type = ActionType.WAIT)),
        )
        val f = FloatingEditorLayout.formOf(a)
        assertEquals(ActionEditGuard.KIND_SWIPE, f.kind)
        assertEquals("12.5000", f.x)
        assertEquals("34.2500", f.y)
        val p = FloatingEditorLayout.toPatch(f)
        assertEquals(12.5, p.x, 0.0)
        assertEquals(80.0, p.x2, 0.0)
        assertEquals(650L, p.durationMs)
        // 关键：apply 基于 copy，children 不丢
        val next = ActionEditGuard.apply(a, p)
        assertEquals(1, next.children.size)
        assertEquals(ActionType.SWIPE, next.type)

        val complex = Action(type = ActionType.CLICK_IMAGE, imageData = "base64", desc = "找图")
        assertEquals(ActionEditGuard.KIND_TAP, FloatingEditorLayout.formOf(complex).kind)
        assertEquals("patchOf 对未知类型回退 TAP，但 apply 必须原样返回",
            complex, ActionEditGuard.apply(complex, FloatingEditorLayout.toPatch(FloatingEditorLayout.formOf(complex))))
    }

    @Test
    fun `已有显式区域带入表单 全屏默认区域不带入`() {
        val explicit = Action(type = ActionType.TAP, region = Region(10.0, 20.0, 80.0, 90.0, "10,20,80,90"))
        assertNotNull(FloatingEditorLayout.formOf(explicit).region)
        val full = Action(type = ActionType.TAP, region = Region())
        assertNull(FloatingEditorLayout.formOf(full).region)
    }

    @Test
    fun `只读复杂动作经 apply 不丢 condition 与 listeners`() {
        val a = Action(
            type = ActionType.CLICK_NODE, nodeId = "btn",
            condition = Condition(kind = ConditionKind.APPEAR_TEXT, text = "确定"),
            listeners = Listeners(afterAction = listOf(Action(type = ActionType.WAIT))),
        )
        val next = ActionEditGuard.apply(a, FloatingEditorLayout.toPatch(FloatingEditorLayout.formOf(a)))
        assertEquals(a, next)
        assertNotNull(next.condition)
        assertEquals(1, next.listeners.afterAction.size)
    }

    @Test
    fun `框选 ROI 通过 RoiApplier 命中找图动作而非当前坐标动作`() {
        val script = dev.nymph.auto.core.model.Script(
            actions = listOf(
                Action(type = ActionType.TAP, x = 50.0, y = 50.0),
                Action(type = ActionType.CLICK_IMAGE, imageData = "x"),
            ),
        )
        val roi = Region(10.0, 20.0, 80.0, 90.0, "10,20,80,90")
        val (next, hits) = RoiApplier.apply(script, roi)
        assertEquals(1, hits)
        assertEquals(roi, next.actions[1].region)
        assertEquals(Region(), next.actions[0].region)
    }

    // ------------------------------------------------------------ v2.8.1 抗抽搐回归

    @Test
    fun `绝对坐标拖动不累加不漂移 同一手指位置重复计算结果恒定`() {
        // 按下时窗口在 (100,100)，手指从 (500,500) 移到 (530,545)
        val a = FloatingEditorLayout.dragAbsolute(100, 100, 500f, 500f, 530f, 545f, 720, 1200, 1080, 2400)
        assertEquals(130, a.first)
        assertEquals(145, a.second)
        // 同一个 MOVE 重复投递（v2.8.0 会累加一次增量 → 越拖越偏）必须完全不变
        val b = FloatingEditorLayout.dragAbsolute(100, 100, 500f, 500f, 530f, 545f, 720, 1200, 1080, 2400)
        assertEquals(a, b)
        // 与「先移到中间再移到终点」的结果一致，证明与路径无关
        val mid = FloatingEditorLayout.dragAbsolute(100, 100, 500f, 500f, 515f, 520f, 720, 1200, 1080, 2400)
        val viaMid = FloatingEditorLayout.dragAbsolute(mid.first, mid.second, 515f, 520f, 530f, 545f, 720, 1200, 1080, 2400)
        assertEquals(a, viaMid)
    }

    @Test
    fun `绝对坐标拖动越界仍被夹在屏内`() {
        val (x, y) = FloatingEditorLayout.dragAbsolute(100, 100, 0f, 0f, 9000f, 9000f, 720, 1200, 1080, 2400)
        assertEquals(1080 - 720 - FloatingEditorLayout.EDGE_PX, x)
        assertEquals(2400 - 1200 - FloatingEditorLayout.EDGE_PX, y)
    }

    @Test
    fun `位移未达阈值不触发窗口更新 避免高频无效 relayout`() {
        assertFalse(FloatingEditorLayout.shouldCommit(100, 100, 100, 100))
        assertTrue(FloatingEditorLayout.shouldCommit(101, 100, 100, 100))
        assertTrue(FloatingEditorLayout.shouldCommit(100, 99, 100, 100))
        assertEquals(FloatingEditorLayout.DRAG_COMMIT_PX, 1)
    }

    @Test
    fun `UNSPECIFIED 高度不再被压成 0 而是限到最大高度`() {
        val m = FloatingEditorLayout.measureHeight(FloatingEditorLayout.MeasureMode.UNSPECIFIED, 0, 1488)
        assertEquals(FloatingEditorLayout.MeasureMode.AT_MOST, m.first)
        assertEquals(1488, m.second)
        assertTrue("高度绝不能是 0", m.second > 0)
    }

    @Test
    fun `三种测量模式都不超过最大高度且 EXACTLY 保持语义`() {
        val ex = FloatingEditorLayout.measureHeight(FloatingEditorLayout.MeasureMode.EXACTLY, 2000, 1488)
        assertEquals(FloatingEditorLayout.MeasureMode.EXACTLY, ex.first)
        assertEquals(1488, ex.second)
        val at1 = FloatingEditorLayout.measureHeight(FloatingEditorLayout.MeasureMode.AT_MOST, 900, 1488)
        assertEquals(FloatingEditorLayout.MeasureMode.AT_MOST, at1.first)
        assertEquals(900, at1.second)
        val at2 = FloatingEditorLayout.measureHeight(FloatingEditorLayout.MeasureMode.AT_MOST, 3000, 1488)
        assertEquals(1488, at2.second)
    }

    // ------------------------------------------------------------ v2.9.7 收起球拖动

    @Test
    fun `点击与拖动判定严格互补 中间没有误判窗口`() {
        val slop = 24f
        assertFalse(FloatingEditorLayout.isDragMove(10f, 5f, slop))
        assertTrue(FloatingEditorLayout.isTap(false, 10f, 5f, slop))
        // 旧实现 4~8dp 窗口会误触发展开：越过阈值必须是拖动
        assertTrue(FloatingEditorLayout.isDragMove(20f, 10f, slop))
        assertFalse(FloatingEditorLayout.isTap(false, 20f, 10f, slop))
        assertFalse(FloatingEditorLayout.isTap(true, 0f, 0f, slop))
        // 恰好等于阈值属点击：两者严格互补
        assertFalse(FloatingEditorLayout.isDragMove(24f, 0f, slop))
        assertTrue(FloatingEditorLayout.isTap(false, 24f, 0f, slop))
        for (d in 0..60 step 3) {
            val drag = FloatingEditorLayout.isDragMove(d.toFloat(), 0f, slop)
            val tap = FloatingEditorLayout.isTap(false, d.toFloat(), 0f, slop)
            assertTrue("位移 $d 必须恰好落入点击或拖动之一", drag != tap)
        }
    }

    @Test
    fun `收起态小球可在整屏范围拖动 不被展开态高度压缩`() {
        val d = 3f
        val sw = 1080
        val sh = 2400
        val ball = FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, d)
        // 收起态 clamp 只让 40dp 球尺寸参与，y 上限接近屏底，
        // 而不是被展开态 54% 屏高压在上半屏。
        val (_, y) = FloatingEditorLayout.dragAbsolute(
            100, 500, 100f, 500f, 100f, 1300f, ball, ball, sw, sh)
        assertEquals(1300, y)
        val (_, y2) = FloatingEditorLayout.dragAbsolute(
            100, 500, 100f, 500f, 100f, 9999f, ball, ball, sw, sh)
        assertEquals(sh - ball - FloatingEditorLayout.EDGE_PX, y2)
        val (_, y3) = FloatingEditorLayout.dragAbsolute(
            100, 500, 100f, 500f, 100f, -9999f, ball, ball, sw, sh)
        assertEquals(FloatingEditorLayout.EDGE_PX, y3)
    }

    @Test
    fun `收紧后的展开尺寸仍满足不铺满全屏约束`() {
        val d = 3f
        val sw = 1080
        val sh = 2400
        val w = FloatingEditorLayout.dp(FloatingEditorLayout.EXPANDED_W_DP, d)
        val h = FloatingEditorLayout.maxHeightPx(sh)
        // v2.9.8：阈值由 62/54 收紧到 48/41，防止后续又「放宽回去」
        assertTrue("宽占比 ${w * 100 / sw}%", w * 100 / sw <= 48)
        assertTrue("高占比 ${h * 100 / sh}%", h * 100 / sh <= 41)
        assertTrue("折叠球仍远小于展开态", FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, d) * 4 < w)
    }

    // ------------------------------------------------------------ v2.9.8 同步拖动 / 阈值分离

    @Test
    fun `拖动启动阈值远小于系统 slop 轻拖也能启动且绝不会被判成点击展开`() {
        val density = 3f
        val start = FloatingEditorLayout.dragStartPx(density)   // 4dp = 12px
        val slop = 8f * density                                 // 常见 scaledTouchSlop
        assertEquals(12f, start)
        assertTrue(FloatingEditorLayout.dragThresholdsValid(start, slop))
        assertFalse(FloatingEditorLayout.dragThresholdsValid(slop, start))
        // 真机关键窗口：轻移 15px（v2.9.7 用 slop 判定 → 不启动拖动 → 抬手展开）
        assertTrue("15px 必须已启动拖动", FloatingEditorLayout.isDragMove(15f, 0f, start))
        // 进入拖动后，即使位移仍小于系统 slop，也绝不能算点击
        assertFalse("dragging 后一律不是点击", FloatingEditorLayout.isTap(true, 15f, 0f, slop))
        // 完全没动才是点击展开
        assertTrue(FloatingEditorLayout.isTap(false, 0f, 0f, slop))
        assertFalse(FloatingEditorLayout.isTap(false, 30f, 0f, slop))
    }

    @Test
    fun `同步提交无自锁 连续 MOVE 每次都返回新位置 重复投递不会卡住`() {
        val w = FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, 3f)
        var committedX = 100
        var committedY = 500
        var commits = 0
        // 模拟 20 个连续 MOVE：每次都基于「当前已提交位置」决策，没有
        // frameScheduled 之类的锁，任何一帧被丢弃都不会让后续永久失效。
        for (i in 1..20) {
            val rawY = 500f + i * 20f
            val next = FloatingEditorLayout.commitDrag(
                100, 500, 100f, 500f, 100f, rawY,
                w, w, 1080, 2400, committedX, committedY,
            )
            if (next != null) {
                commits++
                committedX = next.first
                committedY = next.second
                assertEquals(500 + i * 20, committedY)
            }
        }
        assertEquals("每个 MOVE 位移 20px，应帧帧提交", 20, commits)
        // 同一位置重复投递：<1px 变化被节流，但仍只是「本次不提交」，
        // 不会把任何状态锁死；下一次真实位移照常提交。
        val idle = FloatingEditorLayout.commitDrag(
            100, 500, 100f, 500f, 100f, 900f,
            w, w, 1080, 2400, committedX, committedY,
        )
        assertNull(idle)
        val resumed = FloatingEditorLayout.commitDrag(
            100, 500, 100f, 500f, 100f, 905f,
            w, w, 1080, 2400, committedX, committedY,
        )
        assertEquals(905, requireNotNull(resumed).second)
    }

    @Test
    fun `同步提交贴边仍被 clamp 且不会产生越界坐标`() {
        val w = FloatingEditorLayout.dp(FloatingEditorLayout.COLLAPSED_DP, 3f)
        val next = FloatingEditorLayout.commitDrag(
            100, 500, 100f, 500f, 100f, 99999f,
            w, w, 1080, 2400, 100, 500,
        )
        assertEquals(2400 - w - FloatingEditorLayout.EDGE_PX, requireNotNull(next).second)
    }
}

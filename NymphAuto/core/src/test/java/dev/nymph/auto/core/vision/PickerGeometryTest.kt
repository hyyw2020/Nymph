// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 坐标拾取几何核心（v2.7.1）。覆盖准星真值累加、边界夹逼、
 * 框选矩形归一化（负向拖动 / 零面积 / 贴边 / 越界）、百分比与
 * Locale 安全格式化。这些是 v2.7.0 准星跳变 / ROI 失效 bug 的回归防线。
 */
class PickerGeometryTest {

    @Test
    fun `绝对定位 拖动不累加 手柄与准星恒定重合`() {
        // v2.9.5 真机「抽搐」回归：旧实现用「相对窗口的增量」，而手柄窗口
        // 自身在跟随手指移动，窗口一动增量就被抵消，手柄一顿一顿地滞后。
        // 改成「手指屏幕绝对坐标 − 抓取偏移」后，与窗口位置完全无关。
        val downRaw = 300f
        val target = 280f
        val off = PickerGeometry.grabOffset(downRaw, target)
        assertEquals(20f, off, 0f)
        assertEquals(480f, PickerGeometry.placeFromGrab(500f, off, 1080), 0f)
        assertEquals(500f, PickerGeometry.placeFromGrab(520f, off, 1080), 0f)
        // 连续采样不丢增量：50 次 × 7px = 350px，一像素不差
        var raw = 300f
        var t = target
        repeat(50) {
            raw += 7f
            t = PickerGeometry.placeFromGrab(raw, off, 1080)
        }
        assertEquals(630f, t, 0f)
    }

    @Test
    fun `绝对定位 越界被夹在屏幕内`() {
        val off = PickerGeometry.grabOffset(500f, 500f)
        assertEquals(0f, PickerGeometry.placeFromGrab(-100f, off, 1080), 0f)
        assertEquals(1079f, PickerGeometry.placeFromGrab(99999f, off, 1080), 0f)
    }

    @Test
    fun `提交阈值 低于一像素不提交 达到阈值即提交`() {
        assertFalse(PickerGeometry.shouldCommit(0.4f, 0.4f))
        assertFalse(PickerGeometry.shouldCommit(0.9f, 0.9f, threshold = 1f))
        assertTrue(PickerGeometry.shouldCommit(1f, 0f))
        assertTrue(PickerGeometry.shouldCommit(0f, 1.2f))
        assertTrue(PickerGeometry.shouldCommit(1.0f, 1.0f, threshold = 1f))
    }

    @Test
    fun `拖动累加为独立真值且与窗口尺寸无关`() {
        // 从 (540,1200) 出发，多次增量累加，绝不因工具条展开/收起改变
        var p = 540f to 1200f
        p = PickerGeometry.drag(p.first, p.second, 10, -5, 1080, 2400)
        assertEquals(550f, p.first, 0f)
        assertEquals(1195f, p.second, 0f)
        p = PickerGeometry.drag(p.first, p.second, -3, 7, 1080, 2400)
        assertEquals(547f, p.first, 0f)
        assertEquals(1202f, p.second, 0f)
    }

    @Test
    fun `拖到屏幕外被夹在 0 到 extent-1`() {
        val a = PickerGeometry.drag(2f, 3f, -999, -999, 1080, 2400)
        assertEquals(0f, a.first, 0f)
        assertEquals(0f, a.second, 0f)
        val b = PickerGeometry.drag(1078f, 2398f, 999, 999, 1080, 2400)
        assertEquals(1079f, b.first, 0f)
        assertEquals(2399f, b.second, 0f)
    }

    @Test
    fun `零尺寸屏幕不崩溃不越界`() {
        assertEquals(0f, PickerGeometry.clamp(50f, 0), 0f)
        assertEquals(0f, PickerGeometry.clamp(-5f, 1), 0f)
        assertEquals(0.0, PickerGeometry.pct(10, 0), 0.0)
    }

    @Test
    fun `px 与 pct 往返全精度`() {
        assertEquals(540f, PickerGeometry.px(50.0, 1080), 0.001f)
        assertEquals(50.0, PickerGeometry.pct(540, 1080), 1e-9)
        assertEquals(47.407407407407405, PickerGeometry.pct(512, 1080), 1e-9)
    }

    @Test
    fun `框选归一化 两角任意顺序收敛为 left小于right`() {
        val a = PickerGeometry.normalizeRegion(900f, 1800f, 200f, 400f, 1080, 2400)
        assertEquals(200, a.l); assertEquals(400, a.t)
        assertEquals(900, a.r); assertEquals(1800, a.b)
        val b = PickerGeometry.normalizeRegion(200f, 400f, 900f, 1800f, 1080, 2400)
        assertEquals(a.l, b.l); assertEquals(a.r, b.r)
        assertEquals(a.t, b.t); assertEquals(a.b, b.b)
    }

    @Test
    fun `零面积框选保证最小 1px`() {
        val r = PickerGeometry.normalizeRegion(500f, 500f, 500f, 500f, 1080, 2400)
        assertTrue(r.r > r.l)
        assertTrue(r.b > r.t)
    }

    @Test
    fun `贴右下角零面积不越界且仍合法`() {
        val r = PickerGeometry.normalizeRegion(1079f, 2399f, 1079f, 2399f, 1080, 2400)
        assertTrue(r.l < r.r)
        assertTrue(r.t < r.b)
        assertTrue(r.r <= 1079)
        assertTrue(r.b <= 2399)
    }

    @Test
    fun `负向与越界坐标被夹进屏幕`() {
        val r = PickerGeometry.normalizeRegion(-500f, -800f, 5000f, 9000f, 1080, 2400)
        assertEquals(0, r.l); assertEquals(0, r.t)
        assertEquals(1079, r.r); assertEquals(2399, r.b)
    }

    @Test
    fun `regionExpr 为四段百分比且顺序 l t r b`() {
        val rect = PickerGeometry.Rect(108, 240, 972, 2160)
        val expr = PickerGeometry.regionExpr(rect, 1080, 2400)
        val parts = expr.split(",")
        assertEquals(4, parts.size)
        assertEquals("10.0000", parts[0])
        assertEquals("10.0000", parts[1])
        assertEquals("90.0000", parts[2])
        assertEquals("90.0000", parts[3])
    }

    @Test
    fun `fmt4 在德语区仍是小数点 绝不用逗号`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val s = PickerGeometry.fmt4(47.4074)
            assertEquals("47.4074", s)
            assertFalse("格式化结果不能含逗号（会被 toDoubleOrNull 拒绝 / ROI 切碎）", s.contains(","))
            assertTrue(s.toDoubleOrNull() != null)
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `regionExpr 在德语区仍可被 RoiExpr 解析`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val rect = PickerGeometry.Rect(100, 200, 500, 800)
            val expr = PickerGeometry.regionExpr(rect, 1000, 1000)
            val resolved = RoiExpr.resolve(expr)
            assertTrue("Locale 安全表达式必须能被解析: $expr", resolved != null)
            assertEquals(resolved!!.left, PickerGeometry.pct(100, 1000), 1e-3)
        } finally {
            Locale.setDefault(prev)
        }
    }
}

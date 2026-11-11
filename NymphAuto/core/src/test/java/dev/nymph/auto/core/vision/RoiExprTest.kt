package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiExprTest {

    private fun assertRoi(r: ImageFinder.RegionPct?, l: Double, t: Double, ri: Double, b: Double) {
        assertNotNull(r)
        assertEquals(l, r!!.left, 1e-6)
        assertEquals(t, r.top, 1e-6)
        assertEquals(ri, r.right, 1e-6)
        assertEquals(b, r.bottom, 1e-6)
    }

    @Test
    fun `别名解析`() {
        assertRoi(RoiExpr.resolve("full"), 0.0, 0.0, 100.0, 100.0)
        assertRoi(RoiExpr.resolve("top"), 0.0, 0.0, 100.0, 33.0)
        assertRoi(RoiExpr.resolve("center"), 25.0, 25.0, 75.0, 75.0)
        assertRoi(RoiExpr.resolve("BOTTOM_RIGHT"), 50.0, 50.0, 100.0, 100.0)
        assertTrue(RoiExpr.aliases().contains("tophalf"))
    }

    @Test
    fun `四元组与具名前缀`() {
        assertRoi(RoiExpr.resolve("10,20,90,80"), 10.0, 20.0, 90.0, 80.0)
        assertRoi(RoiExpr.resolve("l=10,t=20,r=90,b=80"), 10.0, 20.0, 90.0, 80.0)
        assertRoi(RoiExpr.resolve("10 20 90 80"), 10.0, 20.0, 90.0, 80.0)
    }

    @Test
    fun `表达式与变量`() {
        assertRoi(RoiExpr.resolve("0,0,w*0.3,h"), 0.0, 0.0, 30.0, 100.0)
        assertRoi(RoiExpr.resolve("cx-r,cy-r,cx+r,cy+r"), 40.0, 40.0, 60.0, 60.0)
        assertRoi(
            RoiExpr.resolve("0,0,min(w*0.5,40),max(20,h*0.1)"),
            0.0, 0.0, 40.0, 20.0,
        )
        assertRoi(RoiExpr.resolve("0,0,lim,h", mapOf("lim" to 62.0)), 0.0, 0.0, 62.0, 100.0)
    }

    @Test
    fun `倒置自动纠正与钳制`() {
        assertRoi(RoiExpr.resolve("90,80,10,20"), 10.0, 20.0, 90.0, 80.0)
        assertRoi(RoiExpr.resolve("-20,-10,120,130"), 0.0, 0.0, 100.0, 100.0)
    }

    @Test
    fun `空串为全屏 非法输入返回null`() {
        assertRoi(RoiExpr.resolve(""), 0.0, 0.0, 100.0, 100.0)
        assertNull(RoiExpr.resolve("1,2,3"))
        assertNull(RoiExpr.resolve("a,b,c,d", emptyMap()))
        assertNull(RoiExpr.resolve("0,0,unknown(1,2),100"))
        assertNull(RoiExpr.resolve("0,0,1/0,100"))
    }

    @Test
    fun `运行时接线 resolveRegion 空表达式原样返回`() {
        val r = dev.nymph.auto.core.model.Region(10.0, 20.0, 90.0, 80.0)
        assertSame(r, RoiExpr.resolveRegion(r))
        val exprless = dev.nymph.auto.core.model.Region(1.0, 2.0, 3.0, 4.0, "   ")
        assertSame(exprless, RoiExpr.resolveRegion(exprless))
    }

    @Test
    fun `运行时接线 resolveRegion 表达式优先于数值`() {
        val r = dev.nymph.auto.core.model.Region(0.0, 0.0, 100.0, 100.0, "topHalf")
        val out = RoiExpr.resolveRegion(r)
        assertEquals(0.0, out.leftPct, 1e-6)
        assertEquals(0.0, out.topPct, 1e-6)
        assertEquals(100.0, out.rightPct, 1e-6)
        assertEquals(50.0, out.bottomPct, 1e-6)
        assertEquals("topHalf", out.expr)
    }

    @Test
    fun `运行时接线 resolveRegion 非法表达式静默退回数值 不崩溃`() {
        val r = dev.nymph.auto.core.model.Region(12.0, 24.0, 88.0, 76.0, "0,0,1/0,100")
        val out = RoiExpr.resolveRegion(r)
        assertEquals(12.0, out.leftPct, 1e-6)
        assertEquals(24.0, out.topPct, 1e-6)
        assertEquals(88.0, out.rightPct, 1e-6)
        assertEquals(76.0, out.bottomPct, 1e-6)
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import org.junit.Assert.*
import org.junit.Test

/** HudCascade 三支路级联判定单测：锁死 S0/S2b/S5/S6 语义与全链路降级。 */
class HudCascadeTest {

    private fun cand(branch: HudCascade.Branch, score: Double, x: Double, y: Double, cls: String = "") =
        HudCascade.Candidate(branch, score, x, y, cls)

    // ---- S0 门禁 ----
    @Test fun `S0 非对局场景直接短路`() {
        assertFalse(HudCascade.gateScene(HudCascade.Scene.OTHER, inMatch = true))
        assertFalse(HudCascade.gateScene(HudCascade.Scene.IN_MATCH, inMatch = false))
        assertTrue(HudCascade.gateScene(HudCascade.Scene.IN_MATCH, inMatch = true))
    }

    // ---- S2b 条件频域 ----
    @Test fun `频域只在 IMU 或帧差超阈值时介入`() {
        val good = HudCascade.PhaseQuality(pnr = 3.0, residual = 0.1)
        assertFalse("静止时不该触发频域", HudCascade.shouldUseFrequency(0.1, 0.05, good))
        assertTrue("角速度超阈值触发", HudCascade.shouldUseFrequency(0.5, 0.05, good))
        assertTrue("帧差超阈值触发", HudCascade.shouldUseFrequency(0.1, 0.3, good))
    }

    @Test fun `频域 PNR 与残差必须双达标`() {
        val lowPnr = HudCascade.PhaseQuality(pnr = 1.5, residual = 0.1) // 六边形地砖多峰
        val highResidual = HudCascade.PhaseQuality(pnr = 4.0, residual = 0.5) // 遮挡破坏相位
        assertFalse("PNR 不足丢弃", HudCascade.shouldUseFrequency(0.5, 0.3, lowPnr))
        assertFalse("残差过大丢弃", HudCascade.shouldUseFrequency(0.5, 0.3, highResidual))
        assertFalse("双不达标仍丢弃", lowPnr.reliable || highResidual.reliable)
    }

    @Test fun `转场提高匹配门限`() {
        assertEquals(0.0, HudCascade.matchFloor(0.1), 1e-9)
        assertEquals(HudCascade.TURN_MATCH_FLOOR, HudCascade.matchFloor(0.9), 1e-9)
    }

    // ---- S5 融合 ----
    @Test fun `三支路融合权重 0-4-0-4-0-2`() {
        val edge = cand(HudCascade.Branch.GEOM, 0.9, 50.0, 30.0)
        val model = cand(HudCascade.Branch.MODEL, 0.8, 50.0, 30.0, "up_arrow")
        val topo = cand(HudCascade.Branch.TOPO, 0.7, 50.0, 30.0)
        val f = HudCascade.fuse(edge, model, topo)!!
        // 0.4*0.9 + 0.4*0.8 + 0.2*0.7 = 0.36+0.32+0.14 = 0.82
        assertEquals(0.82, f.score, 1e-6)
        assertEquals(3, f.usedBranches.size)
        assertEquals("up_arrow", model.cls)
    }

    @Test fun `模型挂了降级纯 CV 主干`() {
        val edge = cand(HudCascade.Branch.GEOM, 0.9, 40.0, 25.0)
        val topo = cand(HudCascade.Branch.TOPO, 0.6, 41.0, 26.0)
        val f = HudCascade.fuse(edge, null, topo)!!
        // 无效支路不参与：权重重归一化 → (0.4*0.9 + 0.2*0.6)/(0.4+0.2) = 0.48/0.6 = 0.8
        assertEquals(0.8, f.score, 1e-6)
        assertEquals(2, f.usedBranches.size)
        assertFalse("模型不可用不得出现在 usedBranches", f.usedBranches.contains(HudCascade.Branch.MODEL))
        assertEquals(HudCascade.Branch.GEOM, f.branch)
    }

    @Test fun `全支路无效返回 null（fail-closed）`() {
        val bad = cand(HudCascade.Branch.GEOM, -1.0, 50.0, 50.0)
        assertNull(HudCascade.fuse(bad, null, null))
        assertNull(HudCascade.fuse(null, null, null))
    }

    @Test fun `AI 只认类别不找位置——坐标取分数最高支路`() {
        val edge = cand(HudCascade.Branch.GEOM, 0.95, 30.0, 20.0)
        val model = cand(HudCascade.Branch.MODEL, 0.7, 88.0, 88.0, "grenade")
        val f = HudCascade.fuse(edge, model, null)!!
        // 模型分低，坐标必须由确定性主干定，不能被 AI 的假坐标带偏
        assertEquals(30.0, f.centerXPct, 1e-6)
        assertEquals(20.0, f.centerYPct, 1e-6)
    }

    // ---- S5 时序 ----
    @Test fun `三帧一致要求分数与漂移双达标`() {
        val a = HudCascade.Fused(0.9, 50.0, 30.0, HudCascade.Branch.GEOM, listOf(HudCascade.Branch.GEOM))
        val b = HudCascade.Fused(0.88, 51.0, 30.5, HudCascade.Branch.GEOM, listOf(HudCascade.Branch.GEOM))
        val c = HudCascade.Fused(0.91, 49.5, 31.0, HudCascade.Branch.GEOM, listOf(HudCascade.Branch.GEOM))
        assertTrue(HudCascade.threeFrameConsistent(a, b, c))
        val drift = HudCascade.Fused(0.95, 70.0, 30.0, HudCascade.Branch.GEOM, listOf(HudCascade.Branch.GEOM))
        assertFalse("漂移超容差不触发", HudCascade.threeFrameConsistent(a, b, drift))
        assertFalse("缺帧不触发", HudCascade.threeFrameConsistent(a, b, null))
    }

    @Test fun `一阶低通`() {
        assertEquals(0.8, HudCascade.lowPass(null, 0.8), 1e-9)
        // α=0.35: 0.35*0.8 + 0.65*0.4 = 0.28+0.26 = 0.54
        assertEquals(0.54, HudCascade.lowPass(0.4, 0.8), 1e-9)
    }

    // ---- S6 执行门 ----
    @Test fun `点击前再校验一帧`() {
        val ok = HudCascade.Fused(0.9, 50.0, 30.0, HudCascade.Branch.GEOM, emptyList())
        val low = HudCascade.Fused(0.5, 50.0, 30.0, HudCascade.Branch.GEOM, emptyList())
        assertTrue(HudCascade.verifyBeforeClick(ok))
        assertFalse(HudCascade.verifyBeforeClick(low))
        assertFalse(HudCascade.verifyBeforeClick(null))
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.22：拾取纠偏反馈契约回归。
 *
 * 锁定三件事：未命中原因必须可自助修复；置信度分级单调且不阻断保存；
 * 诊断摘要可用于复现且不泄露内部术语。
 */
class PickFeedbackTest {

    private val allReasons = TapGate.Reason.values().toList()

    @Test
    fun `每个判定结论都有非空结论文案`() {
        allReasons.forEach { r ->
            val g = PickFeedback.guidanceFor(r)
            assertTrue("结论文案为空：$r", g.headline.isNotBlank())
        }
    }

    @Test
    fun `仅命中与判定中不呈现修复动作`() {
        assertEquals(PickFeedback.Action.NONE, PickFeedback.guidanceFor(TapGate.Reason.PASS).primary)
        assertEquals(PickFeedback.Action.NONE, PickFeedback.guidanceFor(TapGate.Reason.PENDING).primary)
        assertFalse(PickFeedback.guidanceFor(TapGate.Reason.PASS).fixable)
        assertFalse(PickFeedback.guidanceFor(TapGate.Reason.PENDING).fixable)
    }

    @Test
    fun `每个未命中原因都具备唯一主修复动作`() {
        val misses = allReasons - setOf(TapGate.Reason.PASS, TapGate.Reason.PENDING)
        assertTrue("未命中原因不应为空", misses.isNotEmpty())
        misses.forEach { r ->
            val g = PickFeedback.guidanceFor(r)
            assertTrue("$r 缺少主修复动作", g.fixable)
            assertTrue("$r 修复动作标签为空", !g.primaryLabel.isNullOrBlank())
        }
    }

    @Test
    fun `越界引导扩大区域而非重新拾取`() {
        val g = PickFeedback.guidanceFor(TapGate.Reason.OUT_OF_BOUNDS)
        assertEquals(PickFeedback.Action.EDIT_ROI, g.primary)
        assertTrue(g.primaryLabel!!.contains("区域"))
    }

    @Test
    fun `颜色不符引导重新采样而非调整容差`() {
        val g = PickFeedback.guidanceFor(TapGate.Reason.COLOR_MISMATCH)
        assertEquals(PickFeedback.Action.RESAMPLE, g.primary)
        // 用户无需理解容差：标签与结论都不得出现该内部参数名
        assertFalse(g.headline.contains("容差"))
        assertFalse(g.primaryLabel!!.contains("容差"))
    }

    @Test
    fun `空区域区分手动覆盖与自动派生`() {
        val auto = PickFeedback.guidanceFor(TapGate.Reason.EMPTY_ROI, hasManualRoi = false)
        val manual = PickFeedback.guidanceFor(TapGate.Reason.EMPTY_ROI, hasManualRoi = true)
        assertEquals(PickFeedback.Action.REPICK, auto.primary)
        assertEquals(PickFeedback.Action.CLEAR_MANUAL, manual.primary)
    }

    @Test
    fun `取色失败引导重试`() {
        assertEquals(
            PickFeedback.Action.RETRY,
            PickFeedback.guidanceFor(TapGate.Reason.ASYNC_FAILURE).primary,
        )
    }

    @Test
    fun `无一修复动作指向NONE以外的空标签`() {
        allReasons.forEach { r ->
            val g = PickFeedback.guidanceFor(r)
            if (g.primary != PickFeedback.Action.NONE) {
                assertTrue("$r 有动作但无标签", !g.primaryLabel.isNullOrBlank())
            }
        }
    }

    private fun sample(spread: Int, count: Int = 120) =
        SceneAssist.ColorSample(0xFF3A7BC8.toInt(), 24, spread, 0x1234, count)

    @Test
    fun `有效采样为零时置信度为低`() {
        assertEquals(PickFeedback.Confidence.LOW, PickFeedback.confidenceOf(null))
        assertEquals(PickFeedback.Confidence.LOW, PickFeedback.confidenceOf(sample(0, 0)))
    }

    @Test
    fun `置信度随离散度单调下降`() {
        assertEquals(PickFeedback.Confidence.HIGH, PickFeedback.confidenceOf(sample(0)))
        assertEquals(PickFeedback.Confidence.HIGH, PickFeedback.confidenceOf(sample(24)))
        assertEquals(PickFeedback.Confidence.MEDIUM, PickFeedback.confidenceOf(sample(25)))
        assertEquals(PickFeedback.Confidence.MEDIUM, PickFeedback.confidenceOf(sample(72)))
        assertEquals(PickFeedback.Confidence.LOW, PickFeedback.confidenceOf(sample(73)))
        assertEquals(PickFeedback.Confidence.LOW, PickFeedback.confidenceOf(sample(255)))
    }

    @Test
    fun `低置信度提示明确建议手动框选`() {
        val low = PickFeedback.confidenceHint(PickFeedback.Confidence.LOW)
        assertTrue(low.contains("手动框选"))
        assertTrue(PickFeedback.confidenceHint(PickFeedback.Confidence.HIGH).contains("自动生成"))
        assertTrue(PickFeedback.confidenceLabel(PickFeedback.Confidence.HIGH).isNotBlank())
    }

    @Test
    fun `区域模式文案区分自动与手动`() {
        assertEquals("自动区域", PickFeedback.roiModeLabel(SceneAssist.RoiMode.AUTO))
        assertEquals("手动区域", PickFeedback.roiModeLabel(SceneAssist.RoiMode.MANUAL))
        assertTrue(PickFeedback.manualOverlayNote(true).contains("清除"))
        assertEquals("", PickFeedback.manualOverlayNote(false))
    }

    @Test
    fun `计数器累加且在快照中保持固定键序`() {
        val c = PickFeedback.Counters()
        assertEquals(1, c.inc(PickFeedback.Keys.PICK))
        assertEquals(2, c.inc(PickFeedback.Keys.PICK))
        c.inc(PickFeedback.Keys.STALE_DROP, 3)
        assertEquals(2, c.get(PickFeedback.Keys.PICK))
        val snap = c.snapshot()
        assertEquals(PickFeedback.Keys.PICK, snap.first().first)
        assertTrue(snap.all { it.second >= 0 })
        assertEquals(0, c.get(PickFeedback.Keys.RESAMPLE))
        c.reset()
        assertEquals(0, c.get(PickFeedback.Keys.PICK))
    }

    private fun diag() = PickFeedback.Diag(
        versionName = "2.9.22",
        versionCode = 40,
        gesture = "点击",
        xPct = 62.44,
        yPct = 81.03,
        roiMode = "自动区域",
        roiText = "60.1,78.9 ~ 64.8,83.2 %",
        colorText = "#3A7BC8 · 容差 24",
        confidence = "高",
        reason = "已命中：区域与颜色校验通过",
        focusState = "可聚焦",
        counters = listOf(PickFeedback.Keys.PICK to 3, PickFeedback.Keys.GATE_PASS to 2),
    )

    @Test
    fun `诊断摘要包含复现所需的全部中性字段`() {
        val t = PickFeedback.diagnose(diag())
        listOf("2.9.22", "40", "点击", "62.44", "81.03", "自动区域", "#3A7BC8", "高", "焦点", "计数")
            .forEach { assertTrue("摘要缺少字段：$it", t.contains(it)) }
    }

    @Test
    fun `诊断摘要不泄露内部术语`() {
        val t = PickFeedback.diagnose(diag())
        assertFalse("摘要含内部术语", PickFeedback.leaksInternalTerm(t))
        PickFeedback.INTERNAL_TERMS.forEach {
            assertFalse("摘要命中内部术语 $it", t.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `内部术语检测能识别内核概念`() {
        assertTrue(PickFeedback.leaksInternalTerm("anchorB64=abcd"))
        assertTrue(PickFeedback.leaksInternalTerm("SceneRule(searchRoi=...)"))
        assertFalse(PickFeedback.leaksInternalTerm("区域 60.1,78.9 ~ 64.8,83.2 %"))
    }

    @Test
    fun `诊断摘要含扩展事实时按行追加`() {
        val t = PickFeedback.diagnose(diag().copy(extras = listOf("输入法" to "已连接")))
        assertTrue(t.contains("输入法 已连接"))
        assertFalse(PickFeedback.leaksInternalTerm(t))
    }
}

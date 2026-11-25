// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配失败阶段化（v2.9.23）回归。
 *
 * 背景：装配失败此前统一呈现为「取色失败」，把「截图未取到」与「采样为空」
 * 混为一谈，导致定位方向被引偏。本测试锁定三件事：
 *  1. 每个失败阶段映射到独立结论，不再共用一个原因；
 *  2. 每个结论都有唯一主修复动作，且文案不含「取色」这类误导性归因；
 *  3. 诊断摘要携带采集状态、失败阶段与采样点数，且不泄露内部术语。
 */
class StageFailureTest {

    private val roi = Region(0.0, 0.0, 100.0, 100.0, "")
    private val sample = SceneAssist.ColorSample(0xFF3366CC.toInt(), 12, 0, 1, 40)

    @Test
    fun `六个失败阶段各自映射到独立结论`() {
        assertEquals(
            TapGate.Reason.CAPTURE_UNAVAILABLE, TapGate.reasonFor(TapGate.Failure.CAPTURE_NULL),
        )
        assertEquals(
            TapGate.Reason.CAPTURE_UNAVAILABLE, TapGate.reasonFor(TapGate.Failure.CAPTURE_SECURE),
        )
        assertEquals(
            TapGate.Reason.CAPTURE_UNAVAILABLE, TapGate.reasonFor(TapGate.Failure.CAPTURE_TIMEOUT),
        )
        assertEquals(
            TapGate.Reason.ASSEMBLY_FAILED, TapGate.reasonFor(TapGate.Failure.ASSEMBLY),
        )
        assertEquals(
            TapGate.Reason.SAMPLE_EMPTY, TapGate.reasonFor(TapGate.Failure.PIXEL_READ),
        )
        assertEquals(
            TapGate.Reason.SAMPLE_EMPTY, TapGate.reasonFor(TapGate.Failure.SAMPLE_EMPTY),
        )
    }

    @Test
    fun `安全窗口失败不再呈现为取色失败`() {
        val v = TapGate.evaluate(
            TapGate.Phase.FAILED, roi, 50.0, 50.0, null, null,
            failure = TapGate.Failure.CAPTURE_SECURE,
        )
        assertEquals(TapGate.Reason.CAPTURE_UNAVAILABLE, v.reason)
        assertFalse("安全窗口不得放行", v.allowTap)
        assertFalse("文案不得把截图失败归因为取色：${v.message}", v.message.contains("取色"))
        assertTrue("文案应说明截图未取到", v.message.contains("截图"))
    }

    @Test
    fun `装配失败与采样为空给出不同结论`() {
        val assembly = TapGate.evaluate(
            TapGate.Phase.FAILED, roi, 50.0, 50.0, null, null,
            failure = TapGate.Failure.ASSEMBLY,
        )
        val sampling = TapGate.evaluate(
            TapGate.Phase.FAILED, roi, 50.0, 50.0, null, null,
            failure = TapGate.Failure.SAMPLE_EMPTY,
        )
        assertEquals(TapGate.Reason.ASSEMBLY_FAILED, assembly.reason)
        assertEquals(TapGate.Reason.SAMPLE_EMPTY, sampling.reason)
        assertNotEquals("两类失败不得共用同一文案", assembly.message, sampling.message)
    }

    @Test
    fun `样本缺失默认归为采样为空而非通用异步失败`() {
        val v = TapGate.evaluate(TapGate.Phase.SETTLED, roi, 50.0, 50.0, null, 0xFF3366CC.toInt())
        assertEquals(TapGate.Reason.SAMPLE_EMPTY, v.reason)
        assertFalse(v.allowTap)
    }

    @Test
    fun `未携带阶段时保持兜底原因以兼容旧调用`() {
        val v = TapGate.evaluate(TapGate.Phase.FAILED, roi, 50.0, 50.0, null, null)
        assertEquals(TapGate.Reason.ASYNC_FAILURE, v.reason)
        assertFalse(v.allowTap)
    }

    @Test
    fun `每个新结论都有唯一主修复动作`() {
        val capture = PickFeedback.guidanceFor(TapGate.Reason.CAPTURE_UNAVAILABLE)
        val assembly = PickFeedback.guidanceFor(TapGate.Reason.ASSEMBLY_FAILED)
        val sampling = PickFeedback.guidanceFor(TapGate.Reason.SAMPLE_EMPTY)
        assertTrue(capture.fixable)
        assertTrue(assembly.fixable)
        assertTrue(sampling.fixable)
        assertEquals(PickFeedback.Action.RETRY, capture.primary)
        assertEquals(PickFeedback.Action.REPICK, assembly.primary)
        assertEquals(PickFeedback.Action.RESAMPLE, sampling.primary)
        assertNotEquals(capture.headline, assembly.headline)
        assertNotEquals(assembly.headline, sampling.headline)
    }

    @Test
    fun `新结论文案不含误导性归因`() {
        for (reason in TapGate.Reason.values()) {
            val g = PickFeedback.guidanceFor(reason)
            assertFalse(
                "结论 $reason 的文案不得把非取色失败归因为取色：${g.headline}",
                g.headline.contains("取色失败"),
            )
        }
    }

    @Test
    fun `诊断摘要携带采集阶段与采样点数且不泄露内部术语`() {
        val text = PickFeedback.diagnose(
            PickFeedback.Diag(
                versionName = "2.9.23",
                versionCode = 41,
                gesture = "点击",
                xPct = 97.03,
                yPct = 35.68,
                roiMode = "自动区域",
                roiText = "85.0,30.2 ~ 100.0,41.2 %%",
                colorText = "未采集",
                confidence = "低",
                reason = "截图未取到，未能校验颜色",
                focusState = "不可聚焦",
                counters = PickFeedback.Counters().snapshot(),
                extras = listOf(
                    "采集" to "错误码 3（安全窗口拒绝截图）· 820ms",
                    "阶段" to "安全窗口拒绝截图",
                    "采样" to "0 点",
                ),
            ),
        )
        assertTrue("摘要应含采集行", text.contains("采集 错误码 3"))
        assertTrue("摘要应含阶段行", text.contains("阶段 "))
        assertTrue("摘要应含采样行", text.contains("采样 0 点"))
        assertFalse("摘要不得泄露内部术语", PickFeedback.leaksInternalTerm(text))
        assertFalse("摘要不得含脚本名占位", text.contains("脚本名:"))
    }

    @Test
    fun `采样圆整窗越界时回退中心像素`() {
        val w = 64
        val h = 64
        val px = IntArray(w * h) { 0xFF102030.toInt() }
        val pixelAt: (Int, Int) -> Int? = { x, y ->
            if (x in 0 until w && y in 0 until h) px[y * w + x] else null
        }
        // 拾取点恰好落在帧右下边界之外：圆窗内所有坐标都越界，仍需回退到夹取后的
        // 边界像素，而不是返回空样本。
        val s = SceneAssist.sampleNeighborhood(pixelAt, w, h, w, h, radiusPx = 2)
        assertTrue("应回退到中心像素", s.valid)
        assertEquals(1, s.sampleCount)
        assertEquals(0xFF102030.toInt(), s.argb)
    }

    @Test
    fun `中心像素同样越界才判为采样失败`() {
        val s = SceneAssist.sampleNeighborhood({ _, _ -> null }, 0, 0, 10, 10)
        assertFalse(s.valid)
        assertEquals(0, s.sampleCount)
    }
}

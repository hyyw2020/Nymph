// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.recorder.AnchorSelector
import dev.nymph.auto.core.runner.DriftTracker
import dev.nymph.auto.core.runner.RunStats
import dev.nymph.auto.core.runner.WaitStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 文案口径的 Locale 无关性（v2.9.24）。
 *
 * 背景：`"%.1f".format(v)` 走设备默认 Locale，在 de/fr/ru 等地区输出 `3,2`。
 * 这类文本一路流到坐标读数、结论明细与运行日志，用户与客服看到的数字变成
 * 「3,2s / 97,03%」，与代码里 `Locale.US` 的解析口径不一致；v2.7.0 已因同类
 * 缺陷修过拾取面板（`PickerGeometry.fmt4`），但只修了那一条链路。
 *
 * 本测试把默认 Locale 切成 de-DE 后断言：所有对外文案的数字一律用小数点，
 * 不出现 ASCII 逗号。任何回退到设备默认 Locale 的写法都会在此失败。
 */
class LocaleSafetyTest {

    private lateinit var saved: Locale

    @Before
    fun switchToCommaLocale() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(saved)
    }

    @Test
    fun `自测结论明细在逗号小数点地区仍用小数点`() {
        val a = SceneVerdict.assess(
            SceneSelfTest.Result(0.93, 0.31, 1.45, 540, 1200),
            89,
        )
        assertTrue("明细应含两位小数：${a.detail}", a.detail.contains("0.93"))
        assertFalse("明细不得出现逗号小数点：${a.detail}", a.detail.contains("0,93"))
        assertTrue(a.detail.contains("1.5"))
        assertFalse(a.detail.contains("1,5"))
    }

    @Test
    fun `等待浮层耗时在逗号小数点地区仍用小数点`() {
        val text = WaitStatus("确认", 3200, 15000).render()
        assertTrue("应输出 3.2s：$text", text.contains("3.2s"))
        assertFalse("不得输出 3,2s：$text", text.contains("3,2s"))
    }

    @Test
    fun `执行总结耗时在逗号小数点地区仍用小数点`() {
        val stats = RunStats()
        stats.executed = 3
        val text = stats.summary(2.45)
        assertTrue("应输出 2.5s：$text", text.contains("2.5s"))
        assertFalse("不得输出 2,5s：$text", text.contains("2,5s"))
    }

    @Test
    fun `漂移预算告警在逗号小数点地区仍用小数点`() {
        val msg = DriftTracker().record(9.0, 0.0)
        assertTrue("应触发单次超限：$msg", msg != null)
        assertTrue("应输出 9.0px：$msg", msg!!.contains("9.0px"))
        assertFalse("不得输出 9,0px：$msg", msg.contains("9,0px"))
    }

    @Test
    fun `诊断摘要坐标在逗号小数点地区仍用小数点`() {
        val text = PickFeedback.diagnose(
            PickFeedback.Diag(
                versionName = "2.9.24",
                versionCode = 42,
                gesture = "点击",
                xPct = 97.03,
                yPct = 35.68,
                roiMode = "自动区域",
                roiText = "85.0,30.2 ~ 100.0,41.2 %",
                colorText = "未采集",
                confidence = "低",
                reason = "截图未取到，未能校验颜色",
                focusState = "不可聚焦",
                counters = PickFeedback.Counters().snapshot(),
            ),
        )
        assertTrue("应输出 97.03%：$text", text.contains("97.03%"))
        assertFalse("不得输出 97,03%：$text", text.contains("97,03%"))
    }

    @Test
    fun `锚点描述在逗号小数点地区仍用小数点`() {
        val e = dev.nymph.auto.core.recorder.RecordEvent(
            kind = dev.nymph.auto.core.recorder.RecordEventKind.CLICK,
            x = 540,
            y = 1200,
            screenW = 1080,
            screenH = 2400,
        )
        val coords = AnchorSelector.candidates(e)
            .filter { it.kind == dev.nymph.auto.core.recorder.AnchorKind.COORD }
        assertTrue("坐标锚点恒可用", coords.isNotEmpty())
        val text = coords.first().summary
        assertTrue("应输出 50.0%：$text", text.contains("50.0%"))
        assertFalse("不得输出 50,0%：$text", text.contains("50,0%"))
    }

    @Test
    fun `观测色缺失时门控不得判命中`() {
        val roi = Region(0.0, 0.0, 100.0, 100.0, "")
        val sample = SceneAssist.ColorSample(0xFF3366CC.toInt(), 12, 0, 1, 40)
        // v2.9.24：有样本但缺实测观测色 = 无法校验颜色，必须失败即停。
        // 旧实现用 sample.argb 当观测值与自身比较，距离恒为 0，必然误判命中。
        val v = TapGate.evaluate(TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample, null)
        assertEquals(TapGate.Reason.SAMPLE_EMPTY, v.reason)
        assertFalse(v.allowTap)
    }

    @Test
    fun `观测色与样本一致才允许点击`() {
        val roi = Region(0.0, 0.0, 100.0, 100.0, "")
        val sample = SceneAssist.ColorSample(0xFF3366CC.toInt(), 12, 0, 1, 40)
        val hit = TapGate.evaluate(TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample, sample.argb)
        assertTrue(hit.allowTap)
        val miss = TapGate.evaluate(
            TapGate.Phase.SETTLED, roi, 50.0, 50.0, sample, 0xFFFFFFFF.toInt(),
        )
        assertEquals(TapGate.Reason.COLOR_MISMATCH, miss.reason)
        assertFalse(miss.allowTap)
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.32 场景判据参数精调的纯 JVM 契约测试。
 *
 * 锁定四件事：空配置恒等（老脚本零回归）、填值覆盖且装配元数据逐字保留、
 * 越界钳制与非法沿用、from/applyTo 往返一致；以及分层约束——意图层字段
 * 与判据层字段互不重叠。
 */
class SceneTuningTest {

    /** 一条带齐 v2.9.31 同源证据与 v2.9.32 方向元数据的装配结果。 */
    private fun assembled() = SceneRule(
        searchRoi = Region(10.0, 20.0, 30.0, 40.0),
        anchorRectPct = Region(11.0, 21.0, 15.0, 25.0),
        anchorTemplateW = 48,
        anchorTemplateH = 48,
        templateSrcW = 800,
        templateSrcH = 1768,
        pickRotation = 1,
        clickOffsetXPct = 2.5,
        clickOffsetYPct = -1.5,
        similarity = 87,                 // 自校准值，刻意不同于基线 82
        colorArgb = 0xFF123456.toInt(),
        colorTolerance = 24,
        convergedRoi = Region(29.0, 79.0, 53.0, 89.0),
        crossFrameFloor = 0.79,
        evidenceStreak = 3,
        triggerMode = TriggerMode.WHILE_PRESENT,
        presentStreak = 2,
        absentStreak = 2,
        cooldownMs = 400L,
        retryTimeoutMs = 1500L,
        locMaxDeviationPct = 15.0,
        selfTestBest = 0.93,
        selfTestRunnerUp = 0.71,
    )

    @Test
    fun `空配置是恒等变换 老脚本零回归`() {
        val base = assembled()
        assertEquals(base, SceneTuning.applyTo(base, SceneTuning.Config()))
        assertFalse(SceneTuning.hasOverride(SceneTuning.Config()))
    }

    @Test
    fun `全默认规则应用空配置逐字不变`() {
        val base = SceneRule()
        assertEquals(base, SceneTuning.applyTo(base, SceneTuning.Config()))
    }

    @Test
    fun `逐字段显式覆盖只改该字段`() {
        val base = assembled()
        val cases = listOf(
            SceneTuning.SIMILARITY to "90",
            SceneTuning.COLOR_TOLERANCE to "45",
            SceneTuning.EVIDENCE_STREAK to "6",
            SceneTuning.COOLDOWN_MS to "1750",
            SceneTuning.RETRY_TIMEOUT_MS to "2200",
            SceneTuning.LOC_DEVIATION_PCT to "9",
        )
        for ((f, raw) in cases) {
            val out = SceneTuning.applyTo(base, SceneTuning.Config().withRaw(f, raw))
            val expected = f.write(base, SceneTuning.parse(f, raw)!!)
            assertEquals("${f.key} 覆盖结果不等于逐字段写入", expected, out)
            if (f.key != "similarity") {
                assertEquals("${f.key} 不应改动自校准相似度", base.similarity, out.similarity)
            }
        }
    }

    @Test
    fun `装配元数据与同源证据全部逐字保留`() {
        val base = assembled()
        val config = SceneTuning.Config(
            similarity = "91", colorTolerance = "50", evidenceStreak = "5",
            cooldownMs = "800", retryTimeoutMs = "2600", locMaxDeviationPct = "8",
        )
        val out = SceneTuning.applyTo(base, config)
        assertEquals(base.pickRotation, out.pickRotation)
        assertEquals(base.templateSrcW, out.templateSrcW)
        assertEquals(base.templateSrcH, out.templateSrcH)
        assertEquals(base.anchorRectPct, out.anchorRectPct)
        assertEquals(base.searchRoi, out.searchRoi)
        assertEquals(base.maskRects, out.maskRects)
        assertEquals(base.colorArgb, out.colorArgb)
        assertEquals(base.convergedRoi, out.convergedRoi)
        assertEquals(base.crossFrameFloor, out.crossFrameFloor, 1e-9)
        assertEquals(base.clickOffsetXPct, out.clickOffsetXPct)
        assertEquals(base.clickOffsetYPct, out.clickOffsetYPct)
        assertEquals(base.selfTestBest, out.selfTestBest, 1e-9)
        assertEquals(base.selfTestRunnerUp, out.selfTestRunnerUp, 1e-9)
        // 意图层字段不得被判据层覆盖
        assertEquals(base.triggerMode, out.triggerMode)
        assertEquals(base.presentStreak, out.presentStreak)
        assertEquals(base.absentStreak, out.absentStreak)
    }

    @Test
    fun `越界值钳制到字段边界`() {
        val base = assembled()
        val high = SceneTuning.applyTo(base, SceneTuning.Config().withRaw(SceneTuning.SIMILARITY, "300"))
        assertEquals(99, high.similarity)
        val low = SceneTuning.applyTo(base, SceneTuning.Config().withRaw(SceneTuning.SIMILARITY, "1"))
        assertEquals(55, low.similarity)
        val negCooldown = SceneTuning.applyTo(base, SceneTuning.Config().withRaw(SceneTuning.COOLDOWN_MS, "-500"))
        assertEquals(0L, negCooldown.cooldownMs)
        val hugeRetry = SceneTuning.applyTo(
            base, SceneTuning.Config().withRaw(SceneTuning.RETRY_TIMEOUT_MS, "999999"),
        )
        assertEquals(60_000L, hugeRetry.retryTimeoutMs)
    }

    @Test
    fun `空白与非数值一律沿用装配值`() {
        val base = assembled()
        for (raw in listOf("", "   ", "abc", "NaN", "1e999")) {
            val out = SceneTuning.applyTo(base, SceneTuning.Config().withRaw(SceneTuning.SIMILARITY, raw))
            assertEquals("raw=[$raw] 不应覆盖自校准阈值", base.similarity, out.similarity)
        }
    }

    @Test
    fun `parse 非有限值返回 null`() {
        assertNull(SceneTuning.parse(SceneTuning.SIMILARITY, Double.NaN.toString()))
        assertNull(SceneTuning.coerce(SceneTuning.SIMILARITY, Double.POSITIVE_INFINITY))
        assertEquals(82.0, SceneTuning.coerce(SceneTuning.SIMILARITY, 82.0)!!, 1e-9)
    }

    @Test
    fun `from 与 applyTo 往返一致`() {
        val base = assembled()
        val config = SceneTuning.from(base)
        assertTrue(SceneTuning.hasOverride(config))
        val out = SceneTuning.applyTo(base, config)
        // 全部字段读回再写回，结果与原规则逐字相同
        assertEquals(base, out)
    }

    @Test
    fun `from 的空规则也给出可解析配置`() {
        val config = SceneTuning.from(SceneRule())
        assertEquals("82", config.similarity)
        assertEquals("3", config.evidenceStreak)
        assertEquals("15", config.locMaxDeviationPct)
    }

    @Test
    fun `边界外的装配值回显为空且不被保存改写`() {
        // colorTolerance = 0 表示「未启用颜色判据」，位于字段下界 1 之外。
        // 若回显时被钳制成 1，用户不改动直接保存会把「未启用」改写为「启用」。
        val base = assembled().copy(colorTolerance = 0)
        val config = SceneTuning.from(base)
        assertEquals("", config.colorTolerance)
        assertEquals(base, SceneTuning.applyTo(base, config))
    }

    @Test
    fun `步进边界与留空语义`() {
        val f = SceneTuning.SIMILARITY
        // 留空加号：以装配值为基准上一档
        assertEquals(89.0, SceneTuning.bump(f, "", 1, assembled = 87.0), 1e-9)
        // 留空减号：落到下限
        assertEquals(55.0, SceneTuning.bump(f, "", -1, assembled = 87.0), 1e-9)
        // 上界不溢出
        assertEquals(99.0, SceneTuning.bump(f, "99", 1), 1e-9)
        // 下界不回绕
        assertEquals(55.0, SceneTuning.bump(f, "55", -1), 1e-9)
        // 非法输入按装配值步进
        assertEquals(89.0, SceneTuning.bump(f, "abc", 1, assembled = 87.0), 1e-9)
    }

    @Test
    fun `text 整数不带小数点且 Locale 安全`() {
        assertEquals("87", SceneTuning.text(SceneTuning.SIMILARITY, 87.0))
        assertEquals("15", SceneTuning.text(SceneTuning.LOC_DEVIATION_PCT, 15.0))
        assertEquals("2.5", SceneTuning.text(SceneTuning.LOC_DEVIATION_PCT, 2.5))
    }

    @Test
    fun `字段键唯一且与 Config 映射闭合`() {
        val fields = SceneTuning.fields()
        assertEquals(6, fields.size)
        assertEquals(fields.size, fields.map { it.key }.toSet().size)
        for (f in fields) {
            assertEquals(f, SceneTuning.field(f.key))
            // 每个字段写入后能被同名配置读回
            val c = SceneTuning.Config().withRaw(f, "42")
            assertEquals("42", c.raw(f))
        }
    }

    @Test
    fun `summary 呈现当前生效值且不含内核参数名`() {
        val s = SceneTuning.summary(assembled())
        assertTrue(s.contains("相似度阈值 87%"))
        assertTrue(s.contains("连续稳定帧数 3帧"))
        assertFalse(s.contains("similarity"))
        assertFalse(s.contains("cooldownMs"))
        assertFalse(s.contains("evidenceStreak"))
    }

    @Test
    fun `hint 明确留空沿用语义`() {
        val h = SceneTuning.hintOf(SceneTuning.SIMILARITY, 87.0)
        assertTrue(h.contains("留空沿用"))
        assertTrue(h.contains("87"))
    }
}

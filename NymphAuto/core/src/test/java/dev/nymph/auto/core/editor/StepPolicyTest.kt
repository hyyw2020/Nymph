package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「数值字段不用键盘」的契约。
 *
 * 这些用例对应真实的失败形态：用户在悬浮窗里点加号，值却是 0；连点减号把次数
 * 减成负数导致保存被拒；输入框里残留一个空格让 `toDoubleOrNull()` 失败后步进
 * 从 0 起跳。任何一条回归都会表现为「按钮点了没反应」。
 */
class StepPolicyTest {

    @Test
    fun `空值点加号从默认值起跳而不是零`() {
        // 竞品的「选填」语义：留空代表默认行为，加一档就该是默认值 + 一档。
        assertEquals(150.0, StepPolicy.bump(StepPolicy.HOLD, "", 1), 1e-9)
        assertEquals(2.0, StepPolicy.bump(StepPolicy.TIMES, "", 1), 1e-9)
        assertEquals(100.0, StepPolicy.bump(StepPolicy.WAIT, "", 1), 1e-9)
        assertEquals(450.0, StepPolicy.bump(StepPolicy.DURATION, "", 1), 1e-9)
    }

    @Test
    fun `空值点减号不产生负数`() {
        // 空值先回落默认值，再夹到下限；绝不能出现 "-100" 这种把保存卡死的值。
        assertEquals(StepPolicy.HOLD.min, StepPolicy.bump(StepPolicy.HOLD, "", -1), 1e-9)
        assertEquals(1.0, StepPolicy.bump(StepPolicy.TIMES, "", -1), 1e-9)
        assertEquals(0.0, StepPolicy.bump(StepPolicy.WAIT, "", -1), 1e-9)
        assertEquals(StepPolicy.DURATION.min, StepPolicy.bump(StepPolicy.DURATION, "", -1), 1e-9)
    }

    @Test
    fun `步进结果始终夹在字段边界内`() {
        assertEquals(1.0, StepPolicy.bump(StepPolicy.TIMES, "1", -1), 1e-9)
        assertEquals(999.0, StepPolicy.bump(StepPolicy.TIMES, "999", 1), 1e-9)
        assertEquals(10_000.0, StepPolicy.bump(StepPolicy.HOLD, "10000", 1), 1e-9)
        assertEquals(600_000.0, StepPolicy.bump(StepPolicy.WAIT, "600000", 1), 1e-9)
        assertEquals(0.0, StepPolicy.bump(StepPolicy.WAIT, "0", -1), 1e-9)
    }

    @Test
    fun `非法输入按默认值处理而不是崩溃或归零`() {
        assertEquals(150.0, StepPolicy.bump(StepPolicy.HOLD, "abc", 1), 1e-9)
        assertEquals(150.0, StepPolicy.bump(StepPolicy.HOLD, "   ", 1), 1e-9)
        assertEquals(150.0, StepPolicy.bump(StepPolicy.HOLD, "1e999", 1), 1e-9)
    }

    @Test
    fun `写回文本去掉多余小数位且不带千分位`() {
        assertEquals("100", StepPolicy.text(StepPolicy.HOLD, 100.0))
        assertEquals("1.5", StepPolicy.text(StepPolicy.HOLD, 1.5))
        assertEquals("1000", StepPolicy.text(StepPolicy.WAIT, 1000.0))
        // 千分位会让回读 toDoubleOrNull() 失败，这里显式防止。
        assertTrue(!StepPolicy.text(StepPolicy.WAIT, 1000.0).contains(","))
    }

    @Test
    fun `选填字段的占位与就地说明都要写明留空含义`() {
        StepPolicy.fields().filter { it.optional }.forEach { f ->
            assertEquals("占位文案漂移：${f.key}", "选填", StepPolicy.placeholder(f))
            val hint = StepPolicy.hintOf(f)
            assertTrue("缺少选填语义：${f.key} → $hint", hint.startsWith("选填"))
            assertTrue("没讲清留空会怎样：${f.key} → $hint", hint.contains("留空"))
        }
        // 必填字段不能伪装成选填。
        assertTrue(!StepPolicy.placeholder(StepPolicy.DURATION).contains("选填"))
        assertTrue(StepPolicy.hintOf(StepPolicy.DURATION).isNotEmpty())
    }

    @Test
    fun `字段 key 与表单字段一一对应`() {
        // 跨层对齐：Form 里叫 holdMs/times/waitMs/durationMs，步进器改名后必须同步。
        val keys = StepPolicy.fields().map { it.key }.toSet()
        assertEquals(setOf("holdMs", "times", "waitMs", "durationMs"), keys)
        assertEquals(4, StepPolicy.fields().size)
        assertEquals(StepPolicy.HOLD, StepPolicy.field("holdMs"))
        assertNull(StepPolicy.field("notAField"))
    }

    @Test
    fun `摘要只输出有值的字段`() {
        assertEquals("100ms", StepPolicy.summaryOf(StepPolicy.HOLD, "100"))
        assertEquals("3次", StepPolicy.summaryOf(StepPolicy.TIMES, "3"))
        assertEquals("", StepPolicy.summaryOf(StepPolicy.TIMES, ""))
        assertEquals("", StepPolicy.summaryOf(StepPolicy.TIMES, "abc"))
    }

    @Test
    fun `默认值与表单初值一致`() {
        // Form 默认 holdMs=100 / times=1 / waitMs=0 / durationMs=400，两侧必须同源。
        assertEquals(100.0, StepPolicy.HOLD.def, 1e-9)
        assertEquals(1.0, StepPolicy.TIMES.def, 1e-9)
        assertEquals(0.0, StepPolicy.WAIT.def, 1e-9)
        assertEquals(400.0, StepPolicy.DURATION.def, 1e-9)
    }
}

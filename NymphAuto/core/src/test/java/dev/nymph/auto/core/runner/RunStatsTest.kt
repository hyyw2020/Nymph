// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.1 真机回归：调试包日志里脚本明明回放了 3 组 / 16 组 SWIPE 翻页，
 * 两次总结却都是「命中 0、跳过 0、失败 0」——旧统计的 executed 从未自增，
 * 直接派发手势不计入任何口径，用户误以为脚本没跑。
 *
 * 这里锁死：executed 与三类结果互相独立、纯手势脚本不再显示成「什么都没做」、
 * 且不把坐标手势伪装成「命中」。
 */
class RunStatsTest {

    @Test
    fun `初始与 reset 后全部归零`() {
        val s = RunStats()
        assertEquals(0, s.executed)
        s.executed = 9; s.hit = 3; s.miss = 2; s.skip = 1; s.fail = 4
        s.reset()
        assertEquals(0, s.executed)
        assertEquals(0, s.hit)
        assertEquals(0, s.miss)
        assertEquals(0, s.skip)
        assertEquals(0, s.fail)
    }

    @Test
    fun `纯坐标手势脚本显示已执行步数而不是全 0`() {
        val s = RunStats()
        // 模拟真机：主循环处理 16 个 SWIPE 顶层动作组，无查找类命中/失败
        repeat(16) { s.executed++ }
        val line = s.summary(27.7, 1, 1)
        assertTrue(line.contains("已执行 16 步"))
        assertTrue(line.contains("命中 0、跳过 0、失败 0"))
        // 必须如实标注「无目标校验」，不把没找图显示成没执行
        assertTrue(line.contains("无目标校验"))
        assertTrue(line.contains("27.7s"))
    }

    @Test
    fun `坐标手势不被伪装成命中`() {
        val s = RunStats()
        repeat(38) { s.executed++ }
        assertEquals(0, s.hit)
        assertTrue(s.summary(9.3).contains("命中 0"))
    }

    @Test
    fun `查找类命中与失败口径保持不变`() {
        val s = RunStats()
        s.executed = 10; s.hit = 4; s.miss = 2; s.fail = 1; s.skip = 3
        assertEquals("命中 4、跳过 3、失败 3", s.resultText())
        val line = s.summary(5.0)
        assertTrue(line.contains("已执行 10 步"))
        assertTrue(line.contains("命中 4、跳过 3、失败 3"))
        // 有查找类结果时不追加「纯手势」说明
        assertFalse(line.contains("无目标校验"))
    }

    @Test
    fun `多轮与无限循环的轮次文案`() {
        val s = RunStats().apply { executed = 6 }
        assertTrue(s.summary(1.0, 2, 3).contains("共 3 轮"))
        assertTrue(s.summary(1.0, 7, 0).contains("无限循环，已跑 7 轮"))
        assertFalse(s.summary(1.0, 1, 1).contains("轮）"))
    }

    @Test
    fun `零步脚本不误报为纯手势`() {
        val s = RunStats()
        assertFalse(s.summary(0.0).contains("无目标校验"))
        assertTrue(s.summary(0.0).contains("已执行 0 步"))
    }
}

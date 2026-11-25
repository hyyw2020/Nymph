// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Level = AnnouncePolicy.Level

/**
 * 预告分级契约（v2.9.30）。
 *
 * 核心锁点：**已确认的重复循环必须零阻塞**。这条如果破了，自动点击器的吞吐
 * 会被撤销窗口吃掉一个数量级，而这是纯技术评审从没算过的账。
 */
class AnnouncePolicyTest {

    @Test
    fun `已确认循环零阻塞`() {
        val d = AnnouncePolicy.decide(
            AnnouncePolicy.Fact(confirmed = true, sceneFirstSeen = false),
            "点击『开始』",
        )
        assertEquals(Level.FOLDED, d.level)
        assertEquals(0L, d.undoWindowMs)
        assertTrue(d.nextLabel.isNotBlank())
        assertEquals("", d.announce)
    }

    @Test
    fun `首次运行的新场景给最长窗口`() {
        val d = AnnouncePolicy.decide(
            AnnouncePolicy.Fact(firstRun = true, sceneFirstSeen = true),
            "点击『确定』",
        )
        assertEquals(Level.FULL, d.level)
        assertEquals(AnnouncePolicy.UNDO_FIRST_MS, d.undoWindowMs)
        assertTrue(d.announce.contains("首次运行"))
    }

    @Test
    fun `新场景但不是首跑 用常规窗口`() {
        val d = AnnouncePolicy.decide(
            AnnouncePolicy.Fact(firstRun = false, sceneFirstSeen = true),
            "点击『确定』",
        )
        assertEquals(Level.FULL, d.level)
        assertEquals(AnnouncePolicy.UNDO_MS, d.undoWindowMs)
    }

    @Test
    fun `未确认过的动作至少预告一次`() {
        val d = AnnouncePolicy.decide(AnnouncePolicy.Fact(confirmed = false), "点击『购买』")
        assertEquals(Level.FULL, d.level)
        assertEquals(AnnouncePolicy.UNDO_MS, d.undoWindowMs)
    }

    @Test
    fun `用户标记的高风险每次都打断 且不受合并限制`() {
        val d = AnnouncePolicy.decide(
            AnnouncePolicy.Fact(
                highRisk = true,
                confirmed = true,
                sinceLastFullMs = 10L,
            ),
            "点击『删除』",
        )
        assertEquals(Level.FULL, d.level)
        assertEquals(AnnouncePolicy.UNDO_RISK_MS, d.undoWindowMs)
    }

    @Test
    fun `两次预告过密时后一次降级为常驻`() {
        val d = AnnouncePolicy.decide(
            AnnouncePolicy.Fact(confirmed = false, sinceLastFullMs = 200L),
            "点击『继续』",
        )
        assertEquals(Level.FOLDED, d.level)
        assertEquals(0L, d.undoWindowMs)
        assertTrue(d.reason.contains("合并"))
    }

    @Test
    fun `合并窗口不压制新场景`() {
        val d = AnnouncePolicy.decide(
            AnnouncePolicy.Fact(sceneFirstSeen = true, sinceLastFullMs = 100L),
            "点击『继续』",
        )
        assertEquals(Level.FULL, d.level)
    }

    @Test
    fun `无落点动作不预告`() {
        val d = AnnouncePolicy.decide(AnnouncePolicy.Fact(undoable = false), "等待 3 秒")
        assertEquals(Level.NONE, d.level)
        assertEquals(0L, d.undoWindowMs)
        assertEquals("", d.nextLabel)
        assertEquals("", d.announce)
    }

    @Test
    fun `空描述不编造文案`() {
        val d = AnnouncePolicy.decide(AnnouncePolicy.Fact(), "")
        assertEquals(Level.FULL, d.level)
        assertEquals("下一步", d.nextLabel)
    }

    @Test
    fun `窗口时长有上界 不会无限放大`() {
        val values = listOf(
            AnnouncePolicy.UNDO_MS,
            AnnouncePolicy.UNDO_FIRST_MS,
            AnnouncePolicy.UNDO_RISK_MS,
        )
        assertTrue(values.all { it in 500L..3_000L })
    }

    @Test
    fun `blocks 与窗口一致`() {
        assertTrue(AnnouncePolicy.blocks(Level.FULL))
        assertTrue(!AnnouncePolicy.blocks(Level.FOLDED))
        assertTrue(!AnnouncePolicy.blocks(Level.NONE))
    }

    @Test
    fun `撤销文案带动作描述`() {
        assertTrue(AnnouncePolicy.revokedNotice("点击『确定』").contains("点击『确定』"))
        assertEquals("已撤销，本步未执行", AnnouncePolicy.revokedNotice("  "))
    }
}

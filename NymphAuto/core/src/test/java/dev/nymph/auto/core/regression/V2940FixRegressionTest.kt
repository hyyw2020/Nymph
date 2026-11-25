// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.editor.ActionEditGuard
import dev.nymph.auto.core.importer.LineImporter
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.store.AtomicTextFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2.9.40 三份审查文档逐条源码甄别后确证真 bug 的纯 JVM 回归。
 *
 * 只收录「源码实读确证属实 + 数据正确性/数据丢失级 + 可纯 JVM 单测锁定」的条目。
 * 其余 P0/P1 属实但需真机/涉大重构/语义变更/文档定性错误的条目不在本类范围。
 */
class V2940FixRegressionTest {

    // ---------------- P0-01：ActionEditGuard 编辑后单位与坐标保真 ----------------

    @Test
    fun `PX动作编辑保存后单位和坐标都不变`() {
        val origin = Action(type = ActionType.TAP, unit = CoordUnit.PX, x = 540.0, y = 1200.0)
        val patch = ActionEditGuard.patchOf(origin).copy(holdMs = 800L)
        val out = ActionEditGuard.apply(origin, patch)
        assertEquals(CoordUnit.PX, out.unit)
        assertEquals(540.0, out.x, 0.0)
        assertEquals(1200.0, out.y, 0.0)
        assertEquals(800L, out.holdMs)
    }

    @Test
    fun `DP动作编辑保存后不被截断到100`() {
        val origin = Action(type = ActionType.LONG_PRESS, unit = CoordUnit.DP, x = 360.0, y = 800.0)
        val out = ActionEditGuard.apply(origin, ActionEditGuard.patchOf(origin))
        assertEquals(CoordUnit.DP, out.unit)
        assertEquals(360.0, out.x, 0.0)
        assertEquals(800.0, out.y, 0.0)
    }

    @Test
    fun `PCT动作仍按百分比钳制并保留单位`() {
        val origin = Action(type = ActionType.TAP, unit = CoordUnit.PCT, x = 150.0, y = -5.0)
        val out = ActionEditGuard.apply(origin, ActionEditGuard.patchOf(origin))
        assertEquals(CoordUnit.PCT, out.unit)
        assertEquals(100.0, out.x, 0.0)
        assertEquals(0.0, out.y, 0.0)
    }

    @Test
    fun `PX滑动动作编辑保存后终点坐标不被截断`() {
        val origin = Action(
            type = ActionType.SWIPE, unit = CoordUnit.PX,
            x = 200.0, y = 400.0, x2 = 900.0, y2 = 1800.0,
        )
        val out = ActionEditGuard.apply(origin, ActionEditGuard.patchOf(origin))
        assertEquals(CoordUnit.PX, out.unit)
        assertEquals(900.0, out.x2, 0.0)
        assertEquals(1800.0, out.y2, 0.0)
    }

    // ---------------- P0-09：LineImporter 解析失败不补 0 ----------------

    /** 构造一行分隔方言的点击动作（x/y 为原始字符串，便于注入非法值）。 */
    private fun line(x: String, y: String): String =
        """{"type":"点击","posData":{"x":"$x","y":"$y"}}"""

    @Test
    fun `x解析失败的坐标被丢弃而非贴边点击`() {
        // 一行合法 + 一行 x 非法：非法行必须被丢弃，结果只剩 1 个动作。
        val raw = listOf(
            line("45", "60"),
            line("abc", "45"),
        ).joinToString("\n")
        val result = LineImporter.import(raw, curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
        assertEquals(45.0, result.script.actions[0].x, 0.0)
        assertTrue(result.note.contains("跳过"))
    }

    @Test
    fun `负数与NaN坐标被丢弃`() {
        val raw = listOf(
            line("10", "20"),
            line("-10", "20"),
            line("NaN", "30"),
        ).joinToString("\n")
        val result = LineImporter.import(raw, curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
    }

    @Test
    fun `合法的零坐标仍保留`() {
        // x=0,y=45 是合法点（贴左边框但用户确实这么配），不能因修 bug 被误丢。
        val raw = line("0", "45")
        val result = LineImporter.import(raw, curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
        assertEquals(0.0, result.script.actions[0].x, 0.0)
        assertEquals(45.0, result.script.actions[0].y, 0.0)
    }

    // ---------------- P0-15：AtomicTextFile 只清自家临时文件 ----------------

    @JvmField @Rule val tmp = TemporaryFolder()

    @Test
    fun `用户自有的tmp中间名文件不被误删`() {
        File(tmp.root, "备份.tmp.bak").writeText("USER-DATA-1")
        File(tmp.root, "test.tmp.txt").writeText("USER-DATA-2")
        assertEquals(0, AtomicTextFile.staleTempFiles(tmp.root).size)
        assertEquals(0, AtomicTextFile.cleanupStale(tmp.root))
        assertTrue(File(tmp.root, "备份.tmp.bak").readText() == "USER-DATA-1")
        assertTrue(File(tmp.root, "test.tmp.txt").readText() == "USER-DATA-2")
    }

    @Test
    fun `自家新格式与老格式临时文件仍被清理`() {
        val fresh = File(tmp.root, "a.json.tmp-1a2b3c-9xz")
        val legacy = File(tmp.root, "b.json.tmp")
        val good = File(tmp.root, "c.json").apply { writeText("KEEP") }
        fresh.writeText("HALF")
        legacy.writeText("HALF")
        val stale = AtomicTextFile.staleTempFiles(tmp.root)
        assertEquals(2, stale.size)
        assertEquals(2, AtomicTextFile.cleanupStale(tmp.root))
        assertFalse(fresh.exists())
        assertFalse(legacy.exists())
        assertTrue(good.exists())
        assertEquals("KEEP", good.readText())
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.importer.LineImporter
import dev.nymph.auto.core.store.AtomicTextFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2.9.41 审查回执确证真 bug 的纯 JVM 回归。
 *
 * 收录范围（源码实读确证属实 + 数据正确性/数据丢失级 + 可纯 JVM 单测锁定）：
 *  - ① LineImporter 滑动终点解析失败/缺失仍补 0 → 生成「滑到 (0,0)」；
 *  - ③ AtomicTextFile 老格式 `.+\.tmp$` 过宽 → 误删用户精确以 .tmp 结尾的文件。
 *
 * ④（迁移 delete 失败告警+隔离）与 ⑤a（崩溃页 /proc 回退）分别由 core 行为与
 * 静态接线门禁锁定；⑤b（后台启动限制）需真机，记录 ADR 不改。
 */
class V2941FixRegressionTest {

    // ---------------- ①：LineImporter 滑动终点 ----------------

    private fun swipe(end: String): String =
        """{"type":"滑动","posData":{"x":"10%","y":"20%"},$end}"""

    @Test
    fun `滑动终点坐标非法时整条丢弃而非滑到左上角`() {
        // 终点 x 非法：起点合法但终点解析失败 → 整条丢弃（skipped），不得生成 SWIPE。
        val raw = listOf(
            swipe(""""endPosData":{"x":"90%","y":"80%"},"duration":300"""),  // 合法对照
            swipe(""""endPosData":{"x":"abc","y":"80%"},"duration":300"""),
        ).joinToString("\n")
        val result = LineImporter.import(raw, curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
        assertTrue(result.note.contains("跳过"))
        // 保留的那条终点必须是合法值，不能是 (0,0)
        assertEquals(90.0, result.script.actions[0].x2, 0.0)
        assertEquals(80.0, result.script.actions[0].y2, 0.0)
    }

    @Test
    fun `滑动终点整体缺失时整条丢弃`() {
        // endPosData / posData2 都缺失：旧实现 x2=y2=0 无条件生成滑到 (0,0)。
        val raw = listOf(
            swipe(""""endPosData":{"x":"90%","y":"80%"},"duration":300"""),  // 合法对照
            swipe(""""duration":300"""),  // 终点缺失
        ).joinToString("\n")
        val result = LineImporter.import(raw, curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
        assertTrue(result.note.contains("跳过"))
        assertEquals(90.0, result.script.actions[0].x2, 0.0)
    }

    @Test
    fun `滑动终点合法零坐标仍保留`() {
        // 用户确配滑到 (0,0)：合法的 0 不能被误丢。
        val raw = swipe(""""endPosData":{"x":"0","y":"0"},"duration":300""")
        val result = LineImporter.import(raw, curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
        assertEquals(0.0, result.script.actions[0].x2, 0.0)
        assertEquals(0.0, result.script.actions[0].y2, 0.0)
    }

    // ---------------- ③：AtomicTextFile 临时文件精确匹配 ----------------

    @JvmField @Rule val tmp = TemporaryFolder()

    @Test
    fun `用户自有精确tmp结尾文件不被误删`() {
        // v2.9.40 的老格式分支 `.+\.tmp$` 会命中这两个；收紧到 .json.tmp 后不碰。
        val a = File(tmp.root, "备份.tmp").apply { writeText("USER-A") }
        val b = File(tmp.root, "draft.tmp").apply { writeText("USER-B") }
        assertEquals(0, AtomicTextFile.staleTempFiles(tmp.root).size)
        assertEquals(0, AtomicTextFile.cleanupStale(tmp.root))
        assertEquals("USER-A", a.readText())
        assertEquals("USER-B", b.readText())
    }

    @Test
    fun `自家json_tmp新旧格式仍被识别清理`() {
        val fresh = File(tmp.root, "a.json.tmp-1a2b3c-9xz").apply { writeText("HALF") }
        val legacy = File(tmp.root, "b.json.tmp").apply { writeText("HALF") }
        val userTmp = File(tmp.root, "备份.tmp").apply { writeText("KEEP") }
        val good = File(tmp.root, "c.json").apply { writeText("KEEP") }
        val stale = AtomicTextFile.staleTempFiles(tmp.root)
        assertEquals(2, stale.size)
        assertEquals(2, AtomicTextFile.cleanupStale(tmp.root))
        assertFalse(fresh.exists())
        assertFalse(legacy.exists())
        assertTrue(userTmp.exists())
        assertTrue(good.exists())
    }

    @Test
    fun `带tmp中间名的用户文件仍不被误删`() {
        val a = File(tmp.root, "备份.tmp.bak").apply { writeText("USER") }
        val b = File(tmp.root, "x.tmp-note.txt").apply { writeText("USER") }
        assertEquals(0, AtomicTextFile.staleTempFiles(tmp.root).size)
        assertTrue(a.exists() && b.exists())
    }
}

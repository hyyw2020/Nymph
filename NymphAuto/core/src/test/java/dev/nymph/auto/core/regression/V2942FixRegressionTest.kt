// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.importer.LineImporter
import dev.nymph.auto.core.store.AtomicTextFile
import dev.nymph.auto.core.store.ScriptRepository
import dev.nymph.auto.core.store.ScriptStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2.9.42 审查回执确证真 bug 的纯 JVM 回归。
 *
 * 收录范围（源码实读确证属实 + 可纯 JVM 单测锁定）：
 *  - ③ 事实前提错误：历史快照写 .naz，STALE_TMP 旧正则结构上匹配不到 .naz.tmp；
 *    且 .history 目录从未接 cleanupStale。补 .naz 分支 + history 清理接线；
 *  - ③ 起点/终点零坐标不对称：起点 `x<=0&&y<=0` 丢弃，与「终点合法零保留」矛盾；
 *    统一为 parsePercent 成功即保留；
 *  - ④ 补测：迁移 delete 失败改名隔离的 .orphan-* 文件须脱离 list 识别与清理器匹配
 *    （上版实现正确但零测试覆盖）；
 *  - ⑤a 补测：isCrashProcessName 纯函数在 app 模块单独测（见 app/src/test）。
 *
 * ④ orphan 无自动回收路径属设计副作用（保数据优先），审查稿本版未列必修，记为已知边界。
 */
class V2942FixRegressionTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    // ---------------- ③-a：.naz 快照临时文件清理 ----------------

    @Test
    fun `naz快照临时文件新旧格式都被识别清理`() {
        // ScriptRepository.SUFFIX=".naz"，崩溃残留是 <name>.r<N>.naz.tmp-<nano>-<tid> 与老格式 .naz.tmp
        val fresh = File(tmp.root, "A.r1.naz.tmp-1a2b3c-9xz").apply { writeText("HALF") }
        val legacy = File(tmp.root, "B.r2.naz.tmp").apply { writeText("HALF") }
        val jsonFresh = File(tmp.root, "C.json.tmp-1a2b3c-9xz").apply { writeText("HALF") }
        assertEquals(3, AtomicTextFile.staleTempFiles(tmp.root).size)
        assertEquals(3, AtomicTextFile.cleanupStale(tmp.root))
        assertFalse(fresh.exists())
        assertFalse(legacy.exists())
        assertFalse(jsonFresh.exists())
    }

    @Test
    fun `用户自有naz与tmp文件不被误删`() {
        // 正式 .naz 快照本身、用户自有 .tmp 结尾文件都不能被清理器碰到。
        val naz = File(tmp.root, "my.r1.naz").apply { writeText("DATA") }
        val userTmp = File(tmp.root, "备份.tmp").apply { writeText("KEEP") }
        val userNazTmpMid = File(tmp.root, "note.naz.tmp.bak").apply { writeText("KEEP") }
        assertEquals(0, AtomicTextFile.staleTempFiles(tmp.root).size)
        assertEquals(0, AtomicTextFile.cleanupStale(tmp.root))
        assertEquals("DATA", naz.readText())
        assertEquals("KEEP", userTmp.readText())
        assertEquals("KEEP", userNazTmpMid.readText())
    }

    @Test
    fun `history目录的naz临时残留被构造时清理`() {
        // ScriptRepository.init 接 cleanupStale(history)：构造后 .history 里的 .naz.tmp-* 应被清掉。
        val scripts = tmp.newFolder("scripts")
        val history = File(scripts, ".history").apply { mkdirs() }
        val stale = File(history, "A.r1.naz.tmp-1a2b3c-9xz").apply { writeText("HALF") }
        val good = File(history, "A.r1.naz").apply { writeText("SNAP") }
        ScriptRepository(ScriptStore(scripts), history, keepVersions = 3)
        assertFalse("history 里的 .naz.tmp 残留应被清理", stale.exists())
        assertTrue("正式历史快照不能被清理", good.exists())
        assertEquals("SNAP", good.readText())
    }

    // ---------------- ③-b：起点/终点零坐标统一 ----------------

    /** 滑动：起点 (sx,sy) → 终点 (ex,ey)。 */
    private fun swipe(sx: String, sy: String, ex: String, ey: String): String =
        """{"type":"滑动","posData":{"x":"$sx","y":"$sy"},"endPosData":{"x":"$ex","y":"$ey"}}"""

    @Test
    fun `从左上角零坐标出发的合法滑动不再被丢弃`() {
        // 起点 (0,0) 是合法角滑起点；上版起点双零被整条丢弃，终点 (0,0) 却保留，口径矛盾。
        val raw = swipe("0", "0", "90", "80")
        val result = LineImporter.import(raw, curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
        assertEquals(0.0, result.script.actions[0].x, 0.0)
        assertEquals(0.0, result.script.actions[0].y, 0.0)
        assertEquals(90.0, result.script.actions[0].x2, 0.0)
        assertEquals(80.0, result.script.actions[0].y2, 0.0)
    }

    @Test
    fun `起终双零仍按解析成功保留且非法坐标依旧丢弃`() {
        // 起终同为 (0,0) 也属解析成功（不引入新丢弃）；但 abc / 负数 / 缺失仍须丢弃整条。
        val ok = swipe("0", "0", "0", "0")
        val bad = swipe("abc", "0", "90", "80")
        val result = LineImporter.import(listOf(ok, bad).joinToString("\n"), curVerCode = 1000)
        assertEquals(1, result!!.script.actions.size)
        assertEquals(0.0, result.script.actions[0].x2, 0.0)
        assertEquals(0.0, result.script.actions[0].y2, 0.0)
        assertTrue(result.note.contains("跳过"))
    }

    // ---------------- ④：orphan 隔离文件脱离识别（补测） ----------------

    @Test
    fun `迁移隔离的orphan文件不被当脚本加载也不被清理`() {
        // 迁移 delete 失败时旧文件改名为 <name>.json.orphan-<nano>，须脱离 list 识别，
        // 且不能进入 staleTempFiles（否则清掉会丢数据；本版确认无回收路径，数据保留）。
        val dir = tmp.newFolder("scripts")
        val orphan = File(dir, "dup.json.orphan-1a2b3c").apply { writeText("SAVED") }
        val store = ScriptStore(dir)
        assertTrue("orphan 文件不能被 list 当脚本", store.list().isEmpty())
        assertTrue("orphan 文件不能被清理器删除", orphan.exists())
        assertEquals(0, AtomicTextFile.staleTempFiles(dir).size)
        assertEquals("SAVED", orphan.readText())
    }
}

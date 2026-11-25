// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 场景术语归一与列表坐标摘要的纯 JVM 回归防线。 */
class SceneListTest {

    @Test
    fun `坐标摘要为百分比且不出现步或动作话术`() {
        val label = SceneList.coordinateLabel(50.0, 31.4)
        assertEquals("50.0% , 31.4%", label)
        assertFalse(label.contains("步"))
        assertFalse(label.contains("动作"))
        assertFalse(label.contains("/"))
    }

    @Test
    fun `当前场景标题不呈现编号`() {
        assertEquals("当前场景", SceneList.currentTitle(null))
        assertEquals("当前场景", SceneList.currentTitle("  "))
        assertEquals("领奖", SceneList.currentTitle(" 领奖 "))
        assertFalse(SceneList.currentTitle("领奖").contains("/"))
    }

    @Test
    fun `列表行以坐标摘要为单位且仅当前项选中`() {
        val rows = SceneList.rows(listOf("10.0% , 20.0%", "30.0% , 40.0%", "50.0% , 60.0%"), 1)
        assertEquals(3, rows.size)
        assertEquals(1, rows.count { it.selected })
        assertEquals("30.0% , 40.0%", rows[1].coordinateLabel)
        assertEquals(1, rows[1].position)
    }

    @Test
    fun `空脚本列表不产生行`() {
        assertTrue(SceneList.rows(emptyList(), 0).isEmpty())
    }

    @Test
    fun `选择位置收敛到合法区间`() {
        assertEquals(0, SceneList.selectAt(-3, 3))
        assertEquals(2, SceneList.selectAt(9, 3))
        assertEquals(0, SceneList.selectAt(5, 0))
        assertEquals(1, SceneList.selectAt(1, 3))
    }

    @Test
    fun `场景名恒可编辑 独立回归项防止只读化`() {
        assertTrue(SceneList.nameEditable())
    }

    @Test
    fun `用户可见术语统一为场景且不含动作类叫法`() {
        val visible = listOf(
            SceneList.UNIT, SceneList.TITLE_CURRENT, SceneList.TITLE_LIST,
            SceneList.LABEL_NEW, SceneList.LABEL_PICK, SceneList.LABEL_NAME,
            SceneList.LABEL_NAME_HINT, SceneList.LABEL_ASSIST,
            SceneList.LABEL_MANUAL_ROI, SceneList.LABEL_CLEAR_MANUAL_ROI,
            SceneList.LABEL_RESET_AUTO_ROI,
        )
        for (t in visible) {
            assertFalse("$t 不应含动作话术", t.contains("动作"))
            assertFalse("$t 不应含步话术", t.contains("步"))
        }
        assertEquals("场景", SceneList.UNIT)
        assertEquals("拾取坐标", SceneList.LABEL_PICK)
    }
}

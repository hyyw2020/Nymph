// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.17 / D2：两种执行范式同构呈现的纯 JVM 契约。 */
class ExecutionParadigmTest {

    @Test
    fun `两种范式的四字段标签完全一致`() {
        val scene = ExecutionParadigm.describe(ExecutionParadigm.Kind.SCENE_DRIVEN)
        val seq = ExecutionParadigm.describe(ExecutionParadigm.Kind.SEQUENTIAL)
        assertEquals(4, scene.fields.size)
        assertEquals(4, seq.fields.size)
        assertEquals(scene.fields.map { it.label }, seq.fields.map { it.label })
        assertEquals(listOf("触发依据", "执行次序", "画面条件", "失败处理"),
            scene.fields.map { it.label })
    }

    @Test
    fun `顺序侧不再是单句静态说明`() {
        val seq = ExecutionParadigm.describe(ExecutionParadigm.Kind.SEQUENTIAL)
        assertEquals(4, seq.detailText.lines().size)
        assertTrue(seq.fields.all { it.value.isNotBlank() })
        assertTrue("动作序号" in seq.fields[0].value)
        assertTrue("下标顺序" in seq.fields[1].value)
    }

    @Test
    fun `场景侧呈现同轮仲裁与 fail-closed 语义`() {
        val scene = ExecutionParadigm.describe(ExecutionParadigm.Kind.SCENE_DRIVEN, 2, 3)
        assertTrue("2 条" in scene.fields[1].value)
        assertTrue("3 条规则" in scene.fields[2].value)
        assertTrue("fail-closed" in scene.fields[3].value)
    }

    @Test
    fun `两范式取值逐项不同`() {
        val scene = ExecutionParadigm.describe(ExecutionParadigm.Kind.SCENE_DRIVEN)
        val seq = ExecutionParadigm.describe(ExecutionParadigm.Kind.SEQUENTIAL)
        scene.fields.indices.forEach { i ->
            assertNotEquals("第 $i 维取值相同", scene.fields[i].value, seq.fields[i].value)
        }
    }

    @Test
    fun `对照表四行且两列均有内容`() {
        val rows = ExecutionParadigm.comparison()
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.label.isNotBlank() && it.sceneDriven.isNotBlank() && it.sequential.isNotBlank() })
        assertEquals(
            ExecutionParadigm.describe(ExecutionParadigm.Kind.SCENE_DRIVEN).fields.map { it.label },
            rows.map { it.label },
        )
    }

    @Test
    fun `落盘布尔值映射与切换退路`() {
        assertEquals(ExecutionParadigm.Kind.SCENE_DRIVEN, ExecutionParadigm.of(true))
        assertEquals(ExecutionParadigm.Kind.SEQUENTIAL, ExecutionParadigm.of(false))
        assertTrue("关闭场景驱动" in
            ExecutionParadigm.describe(ExecutionParadigm.Kind.SCENE_DRIVEN).switchHint)
        assertTrue("开启场景驱动" in
            ExecutionParadigm.describe(ExecutionParadigm.Kind.SEQUENTIAL).switchHint)
    }

    @Test
    fun `同轮上限与规则条数下界保护`() {
        val c = ExecutionParadigm.describe(ExecutionParadigm.Kind.SCENE_DRIVEN, 0, -5)
        assertTrue("1 条" in c.fields[1].value)
        assertTrue("0 条规则" in c.fields[2].value)
    }
}

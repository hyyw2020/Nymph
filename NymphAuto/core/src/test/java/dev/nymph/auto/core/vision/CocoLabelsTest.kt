package dev.nymph.auto.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** COCO 标签中文映射的黄金样本（纯 JVM）。 */
class CocoLabelsTest {

    @Test
    fun `英文标签精确命中`() {
        assertEquals("person", CocoLabels.resolve("person"))
        assertEquals("cell phone", CocoLabels.resolve("Cell Phone"))
        assertEquals("traffic light", CocoLabels.resolve(" traffic light "))
    }

    @Test
    fun `中文别名精确命中`() {
        assertEquals("cell phone", CocoLabels.resolve("手机"))
        assertEquals("tv", CocoLabels.resolve("电视"))
        assertEquals("dining table", CocoLabels.resolve("餐桌"))
        assertEquals("chair", CocoLabels.resolve("椅子"))
    }

    @Test
    fun `互相包含时最长别名优先`() {
        // "汽车"与"车"都含于"我的汽车不见了"，最长匹配应命中 car
        assertEquals("car", CocoLabels.resolve("我的汽车不见了"))
        // "遥控器"含于描述，优先于更短的"器"类噪声
        assertEquals("remote", CocoLabels.resolve("找一下遥控器"))
    }

    @Test
    fun `无法映射的描述返回 null 而非乱猜`() {
        assertNull("UI 描述不在 COCO 80 类内，必须如实为空", CocoLabels.resolve("设置按钮"))
        assertNull(CocoLabels.resolve("右上角的确认"))
        assertNull(CocoLabels.resolve(""))
        assertNull(CocoLabels.resolve("   "))
    }

    @Test
    fun `标签表完整性 - COCO 80 类且无重复`() {
        assertEquals(80, CocoLabels.labels.size)
        assertEquals(80, CocoLabels.labels.toSet().size)
        assertTrue(CocoLabels.labels.containsAll(
            listOf("person", "cell phone", "traffic light", "toothbrush"),
        ))
    }

    @Test
    fun `别名表全部指向合法标签`() {
        // 防止别名拼错指向不存在的标签（运行时静默失配的典型根因）
        val valid = CocoLabels.labels.toSet()
        val zhAliasesField = CocoLabels::class.java.getDeclaredField("zhAliases")
        zhAliasesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val aliases = zhAliasesField.get(CocoLabels) as Map<String, String>
        assertTrue("别名表非空", aliases.size > 50)
        aliases.forEach { (alias, label) ->
            assertTrue("别名「$alias」指向不存在的标签「$label」", label in valid)
            assertTrue("别名「$alias」过短（误匹配风险）", alias.length >= 1)
        }
    }

    @Test
    fun `歧义别名取最具体的那个`() {
        // "笔记本"→ laptop 而非 book（"书本"更短且同样命中，最长匹配取 laptop）
        assertEquals("laptop", CocoLabels.resolve("笔记本"))
        assertEquals("book", CocoLabels.resolve("书本"))
        assertFalse(CocoLabels.resolve("笔记本") == "book")
    }
}

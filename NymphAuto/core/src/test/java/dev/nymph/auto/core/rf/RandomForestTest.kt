package dev.nymph.auto.core.rf

import dev.nymph.auto.core.vision.ImageFinder
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** .dot 解析 + 随机森林 + 屏幕特征（纯 JVM）。 */
class RandomForestTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    /** sklearn export_graphviz 标准风格三分类树。 */
    private val dot = """
        digraph Tree {
        node [shape=box, style="filled, rounded", color="black", fontname="helvetica"] ;
        edge [fontname="helvetica"] ;
        0 [label="X[0] <= 0.5\ngini = 0.667\nsamples = 9\nvalue = [3, 3, 3]"] ;
        1 [label="gini = 0.0\nsamples = 3\nvalue = [3, 0, 0]"] ;
        0 -> 1 [labeldistance=2.5, labelangle=45, headlabel="True"] ;
        2 [label="X[1] <= 0.5\ngini = 0.5\nsamples = 6\nvalue = [0, 3, 3]"] ;
        0 -> 2 [labeldistance=2.5, labelangle=-45, headlabel="False"] ;
        3 [label="gini = 0.0\nsamples = 3\nvalue = [0, 3, 0]"] ;
        2 -> 3 [labeldistance=2.5, labelangle=45, headlabel="True"] ;
        4 [label="gini = 0.0\nsamples = 3\nvalue = [0, 0, 3]"] ;
        2 -> 4 [labeldistance=2.5, labelangle=-45, headlabel="False"] ;
        }
    """.trimIndent()

    @Test
    fun `解析 sklearn 风格 dot 并按特征路由`() {
        val tree = DotTreeParser.parse(dot)
        assertEquals(3, tree.depth())
        // X[0] <= 0.5 → 类别 0
        assertEquals(0 to 1.0, tree.predict(doubleArrayOf(0.1, 0.9, 0.0)))
        // X[0] > 0.5 且 X[1] <= 0.5 → 类别 1
        assertEquals(1 to 1.0, tree.predict(doubleArrayOf(0.9, 0.1, 0.0)))
        // X[0] > 0.5 且 X[1] > 0.5 → 类别 2
        assertEquals(2 to 1.0, tree.predict(doubleArrayOf(0.9, 0.9, 0.0)))
    }

    @Test
    fun `叶节点置信度按 value 分布`() {
        val impure = dot.replace("value = [3, 0, 0]", "value = [2, 1, 0]")
            .replace("value = [0, 3, 3]", "value = [0, 2, 1]")
            .replace("value = [0, 3, 0]", "value = [0, 2, 0]")
            .replace("value = [0, 0, 3]", "value = [0, 0, 2]")
        val tree = DotTreeParser.parse(impure)
        val (cls, conf) = tree.predict(doubleArrayOf(0.1, 0.0))
        assertEquals(0, cls)
        assertEquals(2.0 / 3.0, conf, 1e-9)
    }

    @Test
    fun `随机森林多数投票`() {
        val tA = DotTreeParser.parse(dot)                                   // (0.9,0.9) → 2
        val tB = DotTreeParser.parse(dot)                                   // (0.9,0.9) → 2
        val tC = DotTreeParser.parse(dot.replace("X[0] <= 0.5", "X[0] <= 99.5")) // (0.9,0.9) → 0
        val forest = RandomForest(listOf(tA, tB, tC))
        assertEquals(3, forest.treeCount)
        val p = forest.predict(doubleArrayOf(0.9, 0.9))
        assertEquals(2, p.classIndex)
        assertEquals(2.0 / 3.0, p.confidence, 1e-9)
    }

    @Test
    fun `平票取叶置信度和更高者`() {
        // treeA 投 0（杂叶 conf=2/3），treeB 投 1（纯叶 conf=1.0）—— 1:1 平票取 conf 和更高者 = 1
        val treeA = DotTreeParser.parse(dot.replace("value = [3, 0, 0]", "value = [2, 1, 0]"))
        val treeB = DotTreeParser.parse(dot.replace("X[0] <= 0.5", "X[0] <= 0.1"))
        val forest = RandomForest(listOf(treeA, treeB))
        val p = forest.predict(doubleArrayOf(0.2, 0.0)) // treeA: X0≤0.5→节点1[2,1,0]→0(2/3); treeB: X0>0.1→X1≤0.5→节点3[0,3,0]→1(1.0)
        assertEquals(1, p.classIndex)
        assertEquals(0.5, p.confidence, 1e-9)
    }

    @Test
    fun `从目录加载 dot 并跳过坏文件`() {
        tmp.newFile("a.dot").writeText(dot)
        tmp.newFile("b.dot").writeText(dot)
        tmp.newFile("broken.dot").writeText("这不是 dot")
        tmp.newFile("readme.txt").writeText("忽略")
        val forest = RandomForest.loadFromDir(tmp.root)!!
        assertEquals(2, forest.treeCount)
    }

    @Test
    fun `空目录返回 null`() {
        assertNull(RandomForest.loadFromDir(tmp.root))
    }

    @Test
    fun `畸形 dot 抛出可读异常`() {
        // 节点在独立行（NODE_RE 以 ^ 锚定行首）；叶节点既无分裂也无 value 分布
        try {
            DotTreeParser.parse("digraph G {\n1 [label=\"gini = 0.0\\nsamples = 3\"] ;\n}")
            fail("应抛出解析异常")
        } catch (e: DotTreeParser.DotParseException) {
            assertTrue(e.message!!.contains("value"))
        }
        try {
            DotTreeParser.parse(" totally invalid ")
            fail("应抛出解析异常")
        } catch (e: DotTreeParser.DotParseException) {
            assertTrue(e.message!!.contains("节点"))
        }
    }

    @Test
    fun `环路 dot 被拒绝`() {
        val cyclic = """
            digraph Tree {
            0 [label="X[0] <= 0.5\nvalue = [1, 1]"] ;
            1 [label="value = [1, 0]"] ;
            0 -> 1 [headlabel="True"] ;
            1 -> 0 [headlabel="False"] ;
            }
        """.trimIndent()
        try {
            DotTreeParser.parse(cyclic)
            fail("应拒绝环路")
        } catch (e: DotTreeParser.DotParseException) {
            assertTrue(e.message!!.contains("环路"))
        }
    }

    @Test
    fun `屏幕特征定长且归一化`() {
        val w = 64; val h = 64
        // 左黑右白：强竖直边缘 → 竖直方向梯度集中
        val px = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val img = ImageFinder.Image(w, h, px)
        val f = ScreenFeatures.extract(img, cells = 4, bins = 4)
        assertEquals(4 * 4 * 4, f.size)
        var norm = 0.0
        for (v in f) norm += v * v
        assertEquals(1.0, kotlin.math.sqrt(norm), 1e-9)
        // 边缘附近的 cell 应有非零能量
        assertTrue(f.any { it > 0.0 })
    }

    @Test
    fun `纯色图特征为零向量`() {
        val px = IntArray(32 * 32) { 0xFF808080.toInt() }
        val img = ImageFinder.Image(32, 32, px)
        val f = ScreenFeatures.extract(img, cells = 4, bins = 4)
        assertArrayEquals(DoubleArray(f.size), f, 1e-12)
    }

    @Test
    fun `空图返回零向量不抛异常`() {
        val f = ScreenFeatures.extract(ImageFinder.Image(0, 0, IntArray(0)))
        assertEquals(ScreenFeatures.DEFAULT_CELLS * ScreenFeatures.DEFAULT_CELLS * ScreenFeatures.DEFAULT_BINS, f.size)
        assertArrayEquals(DoubleArray(f.size), f, 1e-12)
    }
}

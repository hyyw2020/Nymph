package dev.nymph.auto.core.rf

/**
 * scikit-learn export_graphviz 风格 .dot 决策树解析器。
 *
 * 自动精灵的 RF 模型就是一堆 .dot 文件（assets/model/RF 目录下），
 * 本解析器让用户把自己的 .dot 模型放进 filesDir 即可复刻同一机制——
 * 对方的专有模型不内置（也拿不到），但格式兼容、机制对等。
 *
 * 支持的格式（sklearn 0.20+ 标准导出）：
 * ```
 * digraph Tree {
 * node [shape=box] ;
 * 0 [label="X[3] <= 0.5\ngini = 0.5\nsamples = 8\nvalue = [4, 4]"] ;
 * 1 [label="gini = 0.0\nsamples = 4\nvalue = [4, 0]"] ;
 * 0 -> 1 [labeldistance=2.5, labelangle=45, headlabel="True"] ;
 * }
 * ```
 * 容错：属性顺序无关、允许缺少部分统计字段、headlabel 缺失时按
 * 左支=True 的默认序（sklearn 的 0->1 通常是 True 支）。
 */
object DotTreeParser {

    class DotParseException(message: String) : Exception(message)

    // 节点声明：id [label="..."]（label 内允许 \" 转义）
    private val NODE_RE = Regex("""^\s*(\d+)\s*\[label="((?:[^"\\]|\\.)*)""", RegexOption.MULTILINE)
    // 边：src -> dst [... headlabel="True"/"False"]
    private val EDGE_RE = Regex("""(\d+)\s*->\s*(\d+)[^\n]*?headlabel="(True|False)"""")
    // 分裂条件：X[12] <= 0.5（也兼容 > 形式：X[12] > 0.5 视为左支取反）
    private val SPLIT_RE = Regex("""X\[(\d+)\]\s*(<=|<|>|>=)\s*(-?[\d.]+(?:[eE][-+]?\d+)?)""")
    // 叶节点类别分布：value = [3, 6]（支持科学计数与负数）
    private val VALUE_RE = Regex("""value\s*=\s*\[([^\]]*)]""")

    fun parse(dot: String): DecisionTree {
        val labels = HashMap<Int, String>()
        for (m in NODE_RE.findAll(dot)) {
            labels[m.groupValues[1].toInt()] = m.groupValues[2]
        }
        if (labels.isEmpty()) throw DotParseException("未找到任何节点声明（不是有效的 .dot 决策树）")
        val edges = HashMap<Pair<Int, Int>, Boolean>() // (src,dst) -> isLeft(True)
        for (m in EDGE_RE.findAll(dot)) {
            val src = m.groupValues[1].toInt()
            val dst = m.groupValues[2].toInt()
            edges[src to dst] = m.groupValues[3] == "True"
        }
        // 入度为 0 的节点是根（sklearn 固定为 0，但不假设）
        val dstSet = edges.keys.map { it.second }.toSet()
        val roots = (labels.keys - dstSet).sorted()
        if (roots.isEmpty()) throw DotParseException("未找到根节点（边构成环路？）")
        val root = roots.first()
        val built = buildNode(root, labels, edges, HashSet(), 0)
        return DecisionTree(built)
    }

    private fun buildNode(
        id: Int,
        labels: Map<Int, String>,
        edges: Map<Pair<Int, Int>, Boolean>,
        seen: MutableSet<Int>,
        depth: Int,
    ): DecisionTree.Node {
        if (depth > MAX_DEPTH) throw DotParseException("树深超过 $MAX_DEPTH（疑似环路或病态模型）")
        if (!seen.add(id)) throw DotParseException("节点 $id 重复访问（环路）")
        val label = labels[id] ?: throw DotParseException("边引用了未声明的节点 $id")
        val split = SPLIT_RE.find(label)
        val valueMatch = VALUE_RE.find(label)
        if (split == null) {
            // 叶节点
            val values = valueMatch?.groupValues?.get(1)?.split(",")
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                ?: throw DotParseException("叶节点 $id 既无分裂条件也无 value 分布")
            if (values.isEmpty()) throw DotParseException("叶节点 $id 的 value 分布为空")
            return if (values.size == 1) {
                // 回归树（mse）：单值，四舍五入为类别
                DecisionTree.Node.Leaf(Math.round(values[0]).toInt(), 1.0)
            } else {
                var best = 0
                for (i in values.indices) if (values[i] > values[best]) best = i
                val sum = values.sum()
                val conf = if (sum > 0) values[best] / sum else 1.0
                DecisionTree.Node.Leaf(best, conf)
            }
        }
        // 分裂节点
        val feature = split.groupValues[1].toInt()
        val op = split.groupValues[2]
        val threshold = split.groupValues[3].toDouble()
        // op 归一化为 "<= t 走左支"："> t 走左支" 等价于 "<= t 走右支"，
        // 直接把阈值语义翻过来（X > 5 的 True 支 = X <= 5 的 False 支）。
        val children = edges.filterKeys { it.first == id }
        val trueDst = children.entries.firstOrNull { it.value }?.key?.second
        val falseDst = children.entries.firstOrNull { !it.value }?.key?.second
        // sklearn 的边总是 True/False 成对；缺失一半时用剩余边按序补
        val (leftId, rightId) = when {
            trueDst != null && falseDst != null -> trueDst to falseDst
            children.size == 2 -> {
                // 无 headlabel：按 sklearn 导出顺序，第一条为 True 支
                val sorted = children.keys.sortedBy { it.second }
                sorted[0].second to sorted[1].second
            }
            else -> throw DotParseException("分裂节点 $id 的子边不是 2 条（实际 ${children.size}）")
        }
        val left = buildNode(leftId, labels, edges, seen, depth + 1)
        val right = buildNode(rightId, labels, edges, seen, depth + 1)
        return if (op == "<=" || op == "<") {
            DecisionTree.Node.Split(feature, threshold, left, right)
        } else {
            // X > t 的左支 = 原 False 支
            DecisionTree.Node.Split(feature, threshold, right, left)
        }
    }

    private const val MAX_DEPTH = 64
}

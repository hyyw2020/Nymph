package dev.nymph.auto.core.rf

/**
 * 决策树（随机森林的单棵树）。
 *
 * 节点语义（对标 scikit-learn export_graphviz 的 .dot 格式，即自动精灵
 * RF 模型同一格式）：
 *  - 分裂节点：X[i] <= t 走左支（True），否则右支（False）
 *  - 叶节点：value = [各类别计数/权重]，预测类别 = 权重最大项；
 *    回归树（单值）预测 = 四舍五入值，置信度 1.0
 */
class DecisionTree(private val root: Node) {

    sealed class Node {
        data class Split(
            val feature: Int,
            val threshold: Double,
            val left: Node,
            val right: Node,
        ) : Node()

        data class Leaf(
            val classIndex: Int,
            val confidence: Double,
        ) : Node()
    }

    /** @return 预测类别 + 叶节点置信度（0~1）。 */
    fun predict(features: DoubleArray): Pair<Int, Double> {
        var node = root
        while (true) {
            node = when (node) {
                is Node.Leaf -> return node.classIndex to node.confidence
                is Node.Split -> {
                    val v = features.getOrElse(node.feature) { 0.0 }
                    if (v <= node.threshold) node.left else node.right
                }
            }
        }
    }

    /** 树的深度（诊断用：过深可能过拟合，导入日志里报出来）。 */
    fun depth(): Int = depthOf(root)

    private fun depthOf(n: Node): Int = when (n) {
        is Node.Leaf -> 1
        is Node.Split -> 1 + maxOf(depthOf(n.left), depthOf(n.right))
    }
}

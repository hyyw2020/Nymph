package dev.nymph.auto.core.rf

import java.io.File

/** 随机森林预测结果：多数票类别 + 票数占比置信度。 */
data class RfPrediction(
    val classIndex: Int,
    val confidence: Double,
)

/**
 * 随机森林（多棵 [DecisionTree] 多数投票）。
 *
 * 对标自动精灵 RF 机制：assets/model/RF 目录下一组 .dot 树，对屏幕特征向量
 * 投票得到类别。对方专有模型不内置（拿不到也不该塞进我们的包），
 * 但加载格式与投票语义对等——用户把自己的 .dot 放进
 * filesDir/rf-models/ 即可启用（见 [loadFromDir]）。
 */
class RandomForest(private val trees: List<DecisionTree>) {

    init {
        require(trees.isNotEmpty()) { "随机森林至少需要一棵树" }
    }

    val treeCount: Int get() = trees.size

    /** 多数投票；平票时取叶置信度之和更高的一方（更确定的那棵树多说一句话）。 */
    fun predict(features: DoubleArray): RfPrediction {
        val votes = HashMap<Int, Int>()
        val confSum = HashMap<Int, Double>()
        for (t in trees) {
            val (cls, conf) = t.predict(features)
            votes[cls] = (votes[cls] ?: 0) + 1
            confSum[cls] = (confSum[cls] ?: 0.0) + conf
        }
        val best = votes.entries.maxWithOrNull(
            compareBy({ it.value }, { confSum[it.key] ?: 0.0 }, { -it.key }),
        )
        val cls = best?.key ?: 0
        return RfPrediction(cls, (best?.value ?: 0).toDouble() / trees.size)
    }

    companion object {
        /** 从目录加载全部 .dot（子目录不递归；空目录/无 .dot 返回 null）。 */
        fun loadFromDir(dir: File): RandomForest? {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".dot") } ?: return null
            if (files.isEmpty()) return null
            val trees = files.sortedBy { it.name }.mapNotNull { f ->
                runCatching { DotTreeParser.parse(f.readText()) }
                    .onFailure { e -> dev.nymph.auto.core.log.RunLog.w("RF", "跳过无法解析的模型 ${f.name}：${e.message}") }
                    .getOrNull()
            }
            return if (trees.isEmpty()) null else RandomForest(trees)
        }
    }
}

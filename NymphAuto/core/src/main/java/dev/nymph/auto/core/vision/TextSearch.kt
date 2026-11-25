package dev.nymph.auto.core.vision

/**
 * 文本框（OCR 识别结果的归一化表示）。
 * 与 app 层 NodeHit 解耦：core 只认"文字 + 中心点"，节点/OCR 两条通道
 * 都能转换进来，匹配逻辑因此可纯 JVM 单测。
 *
 * v2.9.37：[confidence] 承载后端置信度（ORT CTC 解码的 softmax 均值）。默认
 * [UNAVAILABLE_CONFIDENCE] 零回归——ML Kit bundled 通道无置信度 API，构造时
 * 不传即为 -1.0，绝不伪造数值。此前 v2.9.34 虽在 OcrPostprocess 算了置信度，
 * 但 TextBox 无字段承载，导致 ctcGreedyScored.confidence 产出即丢弃。
 */
data class TextBox(
    val text: String,
    val cx: Int,
    val cy: Int,
    val confidence: Double = UNAVAILABLE_CONFIDENCE,
) {
    companion object {
        /** 后端不提供置信度时的占位值（不伪造数值）。 */
        const val UNAVAILABLE_CONFIDENCE: Double = -1.0
    }
}

/**
 * 文字查找（OCR 兜底通道用）。
 *
 * 语义与 [dev.nymph.auto.engine.NodeFinder.findByText] 对齐：
 *  - 普通模式：子串包含（contains）
 *  - /正则/ 模式：整串匹配（matches，与 ScriptRunner CLICK_TEXT 的约定一致）
 *
 * 多个命中时的挑选顺序：阅读序（上→下、左→右）优先，同位置取更短文本
 * （"确定" 比 "确定并不再提示" 更可能是按钮本体）。
 */
object TextSearch {

    fun matchBest(query: String, regex: Boolean, boxes: List<TextBox>): TextBox? {
        if (query.isEmpty() || boxes.isEmpty()) return null
        // v2.9.35：regex=true 时编译失败必须显式失败，绝不降级为字面 contains——
        // 静默切换语义会让 "[确定" 这类非法正则错误命中、调用方无从区分。
        if (regex) {
            val re = runCatching { Regex(query) }.getOrNull() ?: return null
            return boxes
                .filter { re.matches(it.text) }
                .minWithOrNull(compareBy({ it.cy }, { it.cx }, { it.text.length }))
        }
        return boxes
            .filter { it.text.contains(query) }
            .minWithOrNull(compareBy({ it.cy }, { it.cx }, { it.text.length }))
    }

    /** 是否存在（条件判断用，语义同 [matchBest] 但省一次对象构造）。 */
    fun anyMatch(query: String, regex: Boolean, boxes: List<TextBox>): Boolean {
        if (query.isEmpty() || boxes.isEmpty()) return false
        if (regex) {
            val re = runCatching { Regex(query) }.getOrNull() ?: return false
            return boxes.any { re.matches(it.text) }
        }
        return boxes.any { it.text.contains(query) }
    }
}

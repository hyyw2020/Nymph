package dev.nymph.auto.core.vision

/**
 * 文本框（OCR 识别结果的归一化表示）。
 * 与 app 层 NodeHit 解耦：core 只认"文字 + 中心点"，节点/OCR 两条通道
 * 都能转换进来，匹配逻辑因此可纯 JVM 单测。
 */
data class TextBox(
    val text: String,
    val cx: Int,
    val cy: Int,
)

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
        val re = if (regex) runCatching { Regex(query) }.getOrNull() else null
        return boxes
            .filter { b -> if (re != null) re.matches(b.text) else b.text.contains(query) }
            .minWithOrNull(compareBy({ it.cy }, { it.cx }, { it.text.length }))
    }

    /** 是否存在（条件判断用，语义同 [matchBest] 但省一次对象构造）。 */
    fun anyMatch(query: String, regex: Boolean, boxes: List<TextBox>): Boolean {
        if (query.isEmpty() || boxes.isEmpty()) return false
        val re = if (regex) runCatching { Regex(query) }.getOrNull() else null
        return boxes.any { b -> if (re != null) re.matches(b.text) else b.text.contains(query) }
    }
}

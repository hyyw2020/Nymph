// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.vision

/**
 * OCR 文字断言（v2.9.34 安全根基，纯 JVM、无坐标）。
 *
 * 设计动机：旧的 [TextBox] 把「文字内容」与「中心点坐标」绑定在同一个值里，
 * 任何拿到文字结果的调用方都顺带拿到了一个可直接注入的点击锚点——文字断言
 * 与定位坐标在类型层面无法分离。本类型把「断言文字是否存在」与「在哪里」切开：
 *  - [OcrTextAssertion] 只有文字、置信度与匹配器标识，**没有任何坐标字段**，
 *    因此它只能用于条件判定（如 APPEAR_TEXT），在类型系统上不可能产出一个点。
 *  - [OcrRegion] 只有矩形与来源，**没有任何文字内容**，定位消费方拿到它即可
 *    取中心点，但它无法反推"这是一段什么文字"。
 *
 * 文字与位置只在消费侧被显式组合，OCR 引擎不再输出「文字+坐标」的绑定体作为
 * 唯一形式。这是"主链不受 OCR 影响"在类型系统层面的落地：断言类型没有输出口。
 *
 * @param confidence 置信度，取值 [0,1]；[-1.0] 表示后端不提供该量（如 bundled
 *                   ML Kit v2 的 Text.Line 无置信度 API），消费方按"无置信度"处理，
 *                   绝不伪造数值。
 * @param matcherId 产生该断言的匹配器标识（脱敏日志用，不含原文）。
 */
data class OcrTextAssertion(
    val text: String,
    val confidence: Double = UNAVAILABLE_CONFIDENCE,
    val matcherId: String = DEFAULT_MATCHER,
) {
    /** 后端是否提供了可用置信度。 */
    val hasConfidence: Boolean get() = confidence >= 0.0 && confidence <= 1.0

    companion object {
        const val UNAVAILABLE_CONFIDENCE: Double = -1.0
        const val DEFAULT_MATCHER: String = "ocr_default"

        /**
         * 从文本框投影出纯文字断言：显式丢弃坐标，保留后端置信度。
         * v2.9.37：此前硬编码 [UNAVAILABLE_CONFIDENCE]，把 ORT CTC 辛辛苦苦算出的
         * 置信度抹成 -1.0；现改为透传 box.confidence（ML Kit 无 API 仍为 -1.0）。
         */
        fun from(box: TextBox, matcherId: String = DEFAULT_MATCHER): OcrTextAssertion =
            OcrTextAssertion(box.text, box.confidence, matcherId)
    }
}

/**
 * OCR/视觉区域（v2.9.34，纯 JVM、无文字内容）。
 *
 * 只描述屏幕上的一块矩形及其坐标来源，供定位消费方取中心点。刻意不携带文字
 * 内容，使其无法被用于"按文字判定场景成立"——那是 [OcrTextAssertion] 的职责。
 *
 * @param source 坐标来源标识：node（无障碍节点 bounds）/ template（模板矩形）/
 *               vision_region（OCR 检测框）。仅用于观测归因，不改变坐标语义。
 */
data class OcrRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val source: String = SOURCE_VISION,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    init {
        require(right >= left && bottom >= top) { "OcrRegion 矩形顶点非法" }
    }

    companion object {
        const val SOURCE_NODE: String = "node"
        const val SOURCE_TEMPLATE: String = "template"
        const val SOURCE_VISION: String = "vision_region"

        /**
         * 由识别阶段的完整矩形构造区域。刻意只接收四个顶点，不接收任何文字，
         * 从签名上杜绝"文字+坐标"再次绑定。
         */
        fun of(left: Int, top: Int, right: Int, bottom: Int, source: String = SOURCE_VISION): OcrRegion =
            OcrRegion(left, top, right, bottom, source)
    }
}

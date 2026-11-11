// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

/**
 * 多模态锚点选择器（纯 JVM）。
 *
 * ## 为什么需要锚点优先级
 *
 * 朴素的录制器只记录「(x, y) 坐标」，换一台手机 / 换个分辨率 / APP 改版
 * 就全盘失效——这正是自动点击类工具最常见的痛点。本选择器把一次交互
 * 同时表达为多个候选锚点，按跨设备稳定性降序排列，编译成脚本时优先用
 * 最稳的（节点 ID），回放时若找不到再逐级降级到文字 / 模板 / 坐标。
 *
 * 稳定性分值（经验排序，非精确概率）：
 * | 锚点 | 分值 | 换机 | 改版 | 说明 |
 * |---|---|---|---|---|
 * | NODE_ID | 95 | 稳 | 较稳 | viewId 由开发者定义，与坐标无关 |
 * | NODE_TEXT | 80 | 稳 | 中 | 文案改了会失效，但可读、可人工修 |
 * | OCR_TEXT | 65 | 稳 | 中 | 自绘界面唯一文本通道，依赖离线 OCR |
 * | TEMPLATE | 45 | 中 | 差 | 图标/图片，受 DPI、主题、缩放影响 |
 * | COORD | 10 | 差 | 差 | 最后兜底，配合 refWidth/refHeight 映射 |
 *
 * **数据来源约束**：TEMPLATE 与 OCR_TEXT 的输入均来自无障碍 `takeScreenshot()`，
 * 不涉及 MediaProjection，故不与系统录屏争权限。
 */
object AnchorSelector {

    private const val STABILITY_NODE_ID = 95
    private const val STABILITY_NODE_TEXT = 80
    private const val STABILITY_OCR_TEXT = 65
    private const val STABILITY_TEMPLATE = 45
    private const val STABILITY_COORD = 10

    private const val MAX_TEXT_LEN = 40

    /** 列出该事件全部可用锚点，按稳定性降序。列表永不为 empty（至少含 COORD）。 */
    fun candidates(e: RecordEvent, options: RecordOptions = RecordOptions()): List<Anchor> {
        val out = ArrayList<Anchor>(5)
        val n = e.node

        if (n != null) {
            val id = n.viewId.trim().removePrefix("${n.packageName}:id/")
            if (id.isNotBlank() && id != "null") {
                out += Anchor(AnchorKind.NODE_ID, STABILITY_NODE_ID, "节点 $id")
            }
            val label = pickNodeLabel(n)
            // v2.3.0 真机修复：contentDescription 常带「位置指示器」类动态
            // 片段（B站顶部 Tab 的「推荐,7之2,标签」），录制时是 7之2、
            // 回放时变成 8之2 → 第一次命中、第二次「未命中」。这类文字
            // 绝不能当稳定锚点，让位给 viewId / 模板 / 坐标。
            if (label.isNotBlank() && !isVolatileLabel(label)) {
                out += Anchor(AnchorKind.NODE_TEXT, STABILITY_NODE_TEXT, "节点文字「${shorten(label)}」")
            }
        }

        val ocr = e.ocrText.trim()
        if (ocr.isNotBlank() && !isVolatileLabel(ocr)) {
            out += Anchor(AnchorKind.OCR_TEXT, STABILITY_OCR_TEXT, "屏幕文字「${shorten(ocr)}」")
        }

        if (e.templateB64.isNotBlank() && n?.bounds != null && n.bounds.valid &&
            n.bounds.width >= options.templateMinPx && n.bounds.height >= options.templateMinPx
        ) {
            out += Anchor(AnchorKind.TEMPLATE, STABILITY_TEMPLATE, "图像模板 ${n.bounds.width}×${n.bounds.height}")
        }

        if (e.hasScreen) {
            val pctX = pct(e.x, e.screenW)
            val pctY = pct(e.y, e.screenH)
            out += Anchor(AnchorKind.COORD, STABILITY_COORD, "坐标 ${"%.1f".format(pctX)}% / ${"%.1f".format(pctY)}%")
        }

        return out.sortedByDescending { it.stability }
    }

    /** 最强可用锚点；`allowCoordFallback=false` 且只剩坐标时返回 null。 */
    fun best(e: RecordEvent, options: RecordOptions = RecordOptions()): Anchor? {
        val all = candidates(e, options)
        if (all.isEmpty()) return null
        val top = all.first()
        return if (!options.allowCoordFallback && top.kind == AnchorKind.COORD) null else top
    }

    /**
     * 判定一段文字是否含「易变动态片段」：位置/进度指示器、页码、百分比、
     * 时钟、纯数字计数等。这类内容每次刷新都可能变，做锚点必然导致
     * 录制能过、回放失败。判定刻意保守，只拦明确模式，不误伤「第 2 步」
     * 「WiFi 2.4G」「2026年」这类含数字但稳定的文案。
     */
    private val VOLATILE_PATTERNS = listOf(
        Regex("""\d+\s*之\s*\d+"""),          // 7之2：轮播/列表位置指示器
        Regex("""\d+\s*/\s*\d+"""),           // 2/7：页码
        Regex("""\d{1,2}:\d{2}"""),             // 12:30：时钟/倒计时
        Regex("""^\s*\d+(?:[.,]\d+)?\s*%?\s*$"""), // 纯数字 / 百分比 / 计数
        Regex("""\d+\s*\+?\s*(?:万|亿|k|K|条|个|人|次|赞|评论|播放)\s*$"""), // 尾部计数（含 99+ 溢出计数）
    )

    fun isVolatileLabel(s: String): Boolean {
        if (s.isBlank()) return false
        // 逗号/顿号拼接的复合 contentDescription（如「推荐,7之2,标签」）
        // 只要含任意易变片段就整体判为易变。
        return VOLATILE_PATTERNS.any { it.containsMatchIn(s) }
    }

    /** 节点的可读标签：text 优先，其次 contentDescription。剔除过长/纯空白。 */
    fun pickNodeLabel(n: RecordNode): String {
        val t = n.text.trim()
        if (t.isNotEmpty() && t.length <= MAX_TEXT_LEN * 3 && !t.equals("null", true)) return t
        val d = n.contentDesc.trim()
        if (d.isNotEmpty() && !d.equals("null", true)) return d
        return ""
    }

    fun shorten(s: String, max: Int = MAX_TEXT_LEN): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    /** 像素转百分比，钳制到 [0, 100]。 */
    fun pct(px: Int, total: Int): Double =
        if (total <= 0) 0.0 else (px * 100.0 / total).coerceIn(0.0, 100.0)
}

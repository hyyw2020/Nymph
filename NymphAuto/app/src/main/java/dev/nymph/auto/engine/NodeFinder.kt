package dev.nymph.auto.engine

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点查找：不依赖截图的识别通道（全版本 Android 8+ 可用）。
 *
 * 能力对齐自动精灵「点击节点 / 出现节点 / 点击文字」：
 *  - id / class / text / depth 组合条件
 *  - 支持跨窗口（系统状态栏、悬浮窗、分屏、系统弹窗、输入法）—— flags 已配 FLAG_RETRIEVE_INTERACTIVE_WINDOWS
 *  - 节点直接点击（被遮挡时也可点，对应自动精灵的"节点直接点击模式"）
 */
class NodeFinder {

    fun find(
        id: String,
        className: String,
        text: String,
        depthMin: Int,
        depthMax: Int,
    ): NodeHit? {
        val root = AutoClickService.live()?.rootInActiveWindow() ?: return null
        val ids = id.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val classes = className.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val texts = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return dfs(root, 0, ids, classes, texts, depthMin, depthMax)
    }

    /** 按文本查找（"点击文字"的节点兜底通道，OCR 不可用时降级）。 */
    fun findByText(text: String, regex: Boolean): NodeHit? {
        val root = AutoClickService.live()?.rootInActiveWindow() ?: return null
        val re = if (regex) runCatching { Regex(text) }.getOrNull() else null
        return dfs(root, 0, emptyList(), emptyList(), listOf(text), 0, 0)?.takeIf { hit ->
            re == null || re.matches(hit.text)
        }
    }

    private fun dfs(
        node: AccessibilityNodeInfo,
        depth: Int,
        ids: List<String>,
        classes: List<String>,
        texts: List<String>,
        depthMin: Int,
        depthMax: Int,
    ): NodeHit? {
        val viewId = node.viewIdResourceName.orEmpty()
        val cls = node.className?.toString().orEmpty()
        val txt = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
        val inDepth = (depthMin == 0 || depth >= depthMin) && (depthMax == 0 || depth <= depthMax)
        val match = inDepth && when {
            ids.isNotEmpty() && ids.none { viewId.contains(it) } -> false
            classes.isNotEmpty() && classes.none { cls.contains(it) } -> false
            texts.isNotEmpty() && texts.none { txt.contains(it) } -> false
            ids.isEmpty() && classes.isEmpty() && texts.isEmpty() -> false
            else -> true
        }
        if (match) {
            val (cx, cy) = node.centerInScreen()
            return NodeHit(cx, cy, txt, viewId, cls, depth)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dfs(child, depth + 1, ids, classes, texts, depthMin, depthMax)?.let { return it }
        }
        return null
    }

    /** 点击节点（优先 ACTION_CLICK；被遮挡时退回强制坐标点击）。 */
    fun click(hit: NodeHit, direct: Boolean): Boolean {
        val root = AutoClickService.live()?.rootInActiveWindow() ?: return false
        val target = locate(root, hit) ?: return false
        if (!direct) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var p: AccessibilityNodeInfo? = target
            while (p != null) {
                if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                p = p.parent
            }
        }
        return false
    }

    private fun locate(node: AccessibilityNodeInfo, hit: NodeHit): AccessibilityNodeInfo? {
        if ((node.viewIdResourceName.orEmpty() == hit.viewId) &&
            (node.text?.toString() ?: node.contentDescription?.toString().orEmpty()) == hit.text
        ) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { locate(it, hit)?.let { found -> return found } }
        }
        return null
    }

    /** 向当前聚焦输入框写入文本（IME 不可用时的降级输入）。 */
    fun setText(text: String): Boolean {
        val root = AutoClickService.live()?.rootInActiveWindow() ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ------------------------------------------------------------ 命中预览（裁定阶段 1.5）

    /**
     * 命中预览（仅语义路径成立）："这一下会打在哪个节点"。
     *
     * 算法（对标 android-mouse-cursor 的 findSmallestNodeAtPoint）：
     * 窗口层级从顶往下，每个窗口内深度优先取**最小的、bounds 包含该点**的可交互节点。
     *
     * 两个平台坑：
     *  1. getWindows() 的 z-order 方向平台不保证——先试活动窗口根（通常是顶层
     *     交互窗口），再两种顺序各扫一遍，取第一个命中；
     *  2. Android 15 系统对话框的 boundsInScreen 可能超出屏幕——取值前按真实
     *     尺寸裁剪校验（越界窗口/节点直接跳过）。
     *
     * 像素路径（Unity/Flutter/游戏）拿不到预览：无可访问节点时返回 null，
     * 由调用方降级为"邻域放大 + 准星"，确认只能靠实注。
     */
    fun findSmallestNodeAt(x: Int, y: Int): NodeHit? {
        val service = AutoClickService.live() ?: return null
        val frame = service.frame() ?: return null
        val cx = x.coerceIn(0, frame.width - 1)
        val cy = y.coerceIn(0, frame.height - 1)
        val roots = mutableListOf<AccessibilityNodeInfo>()
        service.rootInActiveWindow()?.let { roots += it }
        runCatching { service.windows }.getOrNull()?.forEach { w ->
            w.root?.let { roots += it }
        }
        // 活动根 → 列表顺序 → 反序（覆盖两种 z-order 约定）
        val ordered = roots + roots.reversed()
        for (root in ordered) {
            smallestAt(root, cx, cy, frame.width, frame.height)?.let { return it.first }
        }
        return null
    }

    /** @return (命中节点, 面积) 或 null；同层取面积最小者。 */
    private fun smallestAt(
        node: AccessibilityNodeInfo,
        x: Int, y: Int, w: Int, h: Int,
    ): Pair<NodeHit, Long>? {
        val r = Rect().also { node.getBoundsInScreen(it) }
        // 裁剪校验：Android 15 系统对话框 bounds 可能超屏，按真实尺寸夹取
        val l = r.left.coerceAtLeast(0)
        val t = r.top.coerceAtLeast(0)
        val rr = r.right.coerceAtMost(w)
        val bb = r.bottom.coerceAtMost(h)
        if (l >= rr || t >= bb) return null        // 与屏幕无有效交集
        if (x < l || x >= rr || y < t || y >= bb) return null // 不含该点
        val area = (rr - l).toLong() * (bb - t).toLong()
        var best: Pair<NodeHit, Long>? = null
        if (isInteractive(node)) {
            val (cx, cy) = node.centerInScreen()
            best = NodeHit(
                cx, cy,
                node.text?.toString() ?: node.contentDescription?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(),
                node.className?.toString().orEmpty(),
                0,
            ) to area
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cand = smallestAt(child, x, y, w, h) ?: continue
            if (best == null || cand.second < best.second) best = cand
        }
        return best
    }

    private fun isInteractive(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable || node.isEditable ||
            !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()

    // ------------------------------------------------------------ 敏感页探测（裁定补充项）

    /**
     * accessibilityDataSensitive 探测（Android 13+ 标记、16+ 严格执行）：
     * 开发者标记敏感视图后，未声明 isAccessibilityTool 的无障碍服务读不到
     * 该节点内容、也无法模拟操作——语义吸附、命中预览、后置断言在这类页面
     * 全部不可用，必须作为一等状态检测并提示（裁定补充项）。
     *
     * @return 当前窗口树中标记为敏感的节点数（0=非敏感页）
     */
    fun countSensitiveNodes(): Int {
        if (Build.VERSION.SDK_INT < 33) return 0
        val root = AutoClickService.live()?.rootInActiveWindow() ?: return 0
        var count = 0
        fun walk(n: AccessibilityNodeInfo) {
            // 方法自 API 36 公开；低版本不存在时 NoSuchMethodError 被 runCatching 吞掉
            val sensitive = runCatching { n.isAccessibilityDataSensitive }.getOrDefault(false)
            if (sensitive) count++
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return count
    }
}

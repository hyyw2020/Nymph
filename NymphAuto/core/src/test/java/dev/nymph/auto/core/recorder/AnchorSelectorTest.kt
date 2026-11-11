// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锚点选择器：易变文本判定（纯 JVM）。 */
class AnchorSelectorTest {

    @Test
    fun `位置指示器类易变文本被识别`() {
        listOf(
            "推荐,7之2,标签",     // 真机：B站顶部 Tab 的 contentDescription
            "7之2",
            "第 3 / 12 页",
            "2/7",
            "12:30",
            "78%",
            "1024",
            "1.5万",
            "99+条",
        ).forEach { assertTrue("$it 应判为易变", AnchorSelector.isVolatileLabel(it)) }
    }

    @Test
    fun `含数字但稳定的文案不误伤`() {
        listOf(
            "确定",
            "第 2 步",
            "WiFi 2.4G",
            "2026年11月",
            "不想看该视频",
            "不感兴趣当前视频",
            "会员购",
            "我的",
        ).forEach { assertFalse("$it 不应判为易变", AnchorSelector.isVolatileLabel(it)) }
    }

    @Test
    fun `空白与null字样安全`() {
        assertFalse(AnchorSelector.isVolatileLabel(""))
        assertFalse(AnchorSelector.isVolatileLabel("   "))
    }

    @Test
    fun `易变文本不产出NODE_TEXT锚点`() {
        val e = RecordEvent(
            atMs = 100, kind = RecordEventKind.CLICK, x = 300, y = 600,
            screenW = 1080, screenH = 2400,
            // 故意不给 viewId：只剩 NODE_TEXT 与 COORD
            node = RecordNode(
                packageName = "tv.danmaku.bili", className = "android.widget.TextView",
                viewId = "", text = "", contentDesc = "推荐,7之2,标签",
                bounds = RecordBounds(280, 570, 320, 630), clickable = true,
            ),
        )
        val kinds = AnchorSelector.candidates(e).map { it.kind }
        assertFalse("不应产出 NODE_TEXT", kinds.contains(AnchorKind.NODE_TEXT))
        assertTrue("应保留坐标兜底", kinds.contains(AnchorKind.COORD))
    }

    @Test
    fun `稳定文本仍正常产出NODE_TEXT锚点`() {
        val e = RecordEvent(
            atMs = 100, kind = RecordEventKind.CLICK, x = 300, y = 600,
            screenW = 1080, screenH = 2400,
            node = RecordNode(
                packageName = "com.demo.app", className = "android.widget.Button",
                viewId = "", text = "确定", contentDesc = "",
                bounds = RecordBounds(280, 570, 320, 630), clickable = true,
            ),
        )
        assertTrue(AnchorSelector.candidates(e).any { it.kind == AnchorKind.NODE_TEXT })
    }
}

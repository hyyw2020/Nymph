// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.regression

import dev.nymph.auto.core.editor.NodeFallback
import dev.nymph.auto.core.editor.SceneEvidence
import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.importer.ScriptCompressor
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.44 无障碍节点 fallback 的纯 JVM 回归。
 *
 * 收录（全部 core 纯 JVM，不依赖 Android 节点树）：
 *  - schema 零回归：老脚本 JSON 缺新字段 → 反序列化补默认、不启用 fallback；
 *    压缩落盘 encodeDefaults=false → 默认值字段不写、存量脚本字节不变；
 *  - applicable / matches / toObservation 各分支；
 *  - Tier 新增 NODE 枚举值不影响既有 NONE 判定。
 */
class V2944NodeFallbackTest {

    // ---------------- schema 零回归 ----------------

    @Test
    fun `老脚本缺节点字段反序列化补默认且不启用`() {
        // v2.9.43 及更早脚本：sceneRule 里没有 nodeText/nodeViewId/nodeClass/nodeFallbackEnabled
        val oldJson = """
            {"name":"老脚本","schemaVersion":1,"actions":[
              {"type":"tap","sceneRule":{"similarity":82,"colorArgb":-1234567}}
            ]}
        """.trimIndent()
        val script = ScriptCodec.decode(oldJson)
        assertTrue(script != null)
        val rule = script!!.actions[0].sceneRule!!
        assertEquals("", rule.nodeText)
        assertEquals("", rule.nodeViewId)
        assertEquals("", rule.nodeClass)
        assertFalse(rule.nodeFallbackEnabled)
        assertFalse(NodeFallback.applicable(rule))
    }

    @Test
    fun `明文ScriptCodec缺字段补默认`() {
        val rule = ScriptCodec.json.decodeFromString(
            SceneRule.serializer(),
            """{"similarity":82}""",
        )
        assertFalse(rule.nodeFallbackEnabled)
        assertEquals("", rule.nodeText)
    }

    @Test
    fun `压缩落盘默认值节点字段不写入`() {
        // encodeDefaults=false：默认值字段（含四个新字段）不进压缩容器 → 老脚本字节不变
        val rule = SceneRule(similarity = 82)
        val action = Action(type = dev.nymph.auto.core.model.ActionType.TAP, sceneRule = rule)
        val script = Script(actions = listOf(action))
        val encoded = ScriptCompressor.encode(script)
        assertFalse("nodeFallbackEnabled 默认值不应写入落盘", encoded.contains("nodeFallbackEnabled"))
        assertFalse("nodeText 默认值不应写入落盘", encoded.contains("nodeText"))
        // 回读仍拿到默认值
        val decoded = ScriptCompressor.decodeAny(encoded)
        assertTrue(decoded != null)
        val back = decoded!!.actions[0].sceneRule!!
        assertFalse(back.nodeFallbackEnabled)
        assertEquals("", back.nodeText)
    }

    @Test
    fun `启用但启用态字段全空时applicable为false`() {
        val rule = SceneRule(nodeFallbackEnabled = true)
        assertFalse(NodeFallback.applicable(rule))
    }

    @Test
    fun `启用且有任一语义时applicable为true`() {
        assertTrue(NodeFallback.applicable(SceneRule(nodeFallbackEnabled = true, nodeText = "提交")))
        assertTrue(NodeFallback.applicable(SceneRule(nodeFallbackEnabled = true, nodeViewId = "com.x:id/btn")))
        assertTrue(NodeFallback.applicable(SceneRule(nodeFallbackEnabled = true, nodeClass = "Button")))
    }

    // ---------------- matches 判定 ----------------

    @Test
    fun `matches按非空项逐项包含匹配`() {
        val rule = SceneRule(nodeFallbackEnabled = true, nodeText = "提交", nodeViewId = "btn_ok")
        val hit = NodeFallback.NodeSignal(
            text = "确认提交订单", viewId = "com.demo:id/btn_ok", className = "Button",
            centerX = 100, centerY = 200,
        )
        assertTrue(NodeFallback.matches(hit, rule))
    }

    @Test
    fun `matches任一项不匹配即false`() {
        val rule = SceneRule(nodeFallbackEnabled = true, nodeText = "提交", nodeViewId = "btn_ok")
        val hit = NodeFallback.NodeSignal(
            text = "取消", viewId = "com.demo:id/btn_ok", className = "Button",
            centerX = 100, centerY = 200,
        )
        assertFalse(NodeFallback.matches(hit, rule))
    }

    @Test
    fun `matches语义全空拒绝`() {
        val rule = SceneRule(nodeFallbackEnabled = true)
        val hit = NodeFallback.NodeSignal("任意", "any", "View", 1, 1)
        assertFalse(NodeFallback.matches(hit, rule))
    }

    @Test
    fun `未启用规则的match语义仍在但applicable拦截`() {
        // matches 只做语义比对，是否启用由 applicable 决定（app 层先过 applicable）
        val rule = SceneRule(nodeFallbackEnabled = false, nodeText = "提交")
        val hit = NodeFallback.NodeSignal("提交", "", "", 1, 1)
        assertTrue(NodeFallback.matches(hit, rule))
        assertFalse(NodeFallback.applicable(rule))
    }

    // ---------------- toObservation ----------------

    @Test
    fun `toObservation命中构造present与坐标尺寸`() {
        val hit = NodeFallback.NodeSignal("提交", "", "Button", 360, 800)
        val obs = NodeFallback.toObservation(hit, 1080, 1920)
        assertTrue(obs.present)
        assertEquals(NodeFallback.NODE_HIT_SCORE, obs.score, 0.0)
        assertEquals(360, obs.cxPx)
        assertEquals(800, obs.cyPx)
        assertEquals(1080, obs.frameW)
        assertEquals(1920, obs.frameH)
    }

    @Test
    fun `toObservation坐标或尺寸无效则present为false`() {
        val hit = NodeFallback.NodeSignal("提交", "", "Button", -1, 800)
        assertFalse(NodeFallback.toObservation(hit, 1080, 1920).present)
        val ok = NodeFallback.NodeSignal("提交", "", "Button", 10, 10)
        assertFalse(NodeFallback.toObservation(ok, 0, 1920).present)
    }

    @Test
    fun `Tier新增NODE不影响既有NONE判定`() {
        // 既有分层判据的 NONE 仍可判定（NODE 只是新增值，未改 NONE 语义）
        val v = SceneEvidence.Verdict(
            false, -1, -1, SceneEvidence.Tier.NONE, "本轮无匹配结果", false, false, false, false,
        )
        assertFalse(v.present)
        assertEquals(SceneEvidence.Tier.NONE, v.tier)
        assertEquals(4, SceneEvidence.Tier.entries.size)
    }
}

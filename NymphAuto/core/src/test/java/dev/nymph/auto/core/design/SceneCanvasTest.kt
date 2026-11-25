// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * 场景画布持久化单测：锁定「画布随脚本保存 → 重开还原 → 直接判定」闭环，
 * 且老脚本零回归、序列化往返无损。纯 JVM 无 Android 依赖。
 */
class SceneCanvasTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sampleCanvas() = SceneCanvas(
        backgroundKey = "canvas:abcdef0123456789",
        rotation = 1,
        bind = SceneBoard.OrientationBinding.LANDSCAPE,
        combine = Combine.ALL,
        minCount = 1,
        clickZone = Region(10.0, 20.0, 60.0, 80.0),
        pointX = 33.0,
        pointY = 44.0,
        features = listOf(
            SceneCanvas.CanvasFeature("f1", "特征1·按钮", Region(5.0, 5.0, 25.0, 25.0), 88, "feat:1111"),
            SceneCanvas.CanvasFeature("f2", "特征2·图标", Region(70.0, 5.0, 95.0, 25.0), 90, "feat:2222"),
        ),
    )

    // ---- 诉求：后续维护——画布序列化往返无损 ----
    @Test
    fun `画布序列化往返无损`() {
        val c = sampleCanvas()
        val s = json.encodeToString(SceneCanvas.serializer(), c)
        val back = json.decodeFromString(SceneCanvas.serializer(), s)
        assertEquals(c, back)
        assertEquals(2, back.features.size)
        assertEquals("canvas:abcdef0123456789", back.backgroundKey)
        assertEquals(Region(10.0, 20.0, 60.0, 80.0), back.clickZone)
        assertEquals(Combine.ALL, back.combine)
        assertEquals(SceneBoard.OrientationBinding.LANDSCAPE, back.bind)
    }

    @Test
    fun `画布作为Script字段往返无损且底图走assets不内嵌`() {
        val c = sampleCanvas()
        val script = Script(
            name = "画布脚本",
            assets = mapOf(
                c.backgroundKey to "BASE64_OF_FULL_FRAME",
                "feat:1111" to "BASE64_OF_FEAT1",
                "feat:2222" to "BASE64_OF_FEAT2",
            ),
            canvas = c,
            actions = listOf(Action()),
        )
        val encoded = ScriptCodec.encode(script)
        val back = ScriptCodec.decode(encoded)
        assertNotNull(back)
        assertEquals(c, back!!.canvas)
        // 大图在 assets 里，canvas 对象只存 key——不内嵌 base64 内容
        assertTrue(back.assets.containsKey(c.backgroundKey))
        assertTrue(back.canvas!!.features.all { back.assets.containsKey(it.templateKey) })
    }

    // ---- 老脚本零回归 ----
    @Test
    fun `无canvas字段的老脚本解析后canvas为空`() {
        val oldJson = """{"name":"老脚本","schemaVersion":1,"actions":[]}"""
        val s = ScriptCodec.decode(oldJson)
        assertNotNull(s)
        assertNull(s!!.canvas)
        assertEquals("老脚本", s.name)
    }

    @Test
    fun `canvas为空时判定不读它仍可独立工作`() {
        val s = Script(canvas = null)
        assertNull(s.canvas)
        // 老判定入口不受影响
        val v = SceneBoard.evaluate(
            bind = SceneBoard.OrientationBinding.ANY,
            currentRotation = 3,
            features = emptyList(),
            rawObservations = emptyList(),
            combine = Combine.ANY, minCount = 1,
            desiredX = 50.0, desiredY = 50.0,
            clickZone = Region(),
        )
        assertFalse(v.triggered) // 无特征无触发
    }

    // ---- 画布 → 判定桥接：多特征 AND + 方向 + 落点约束一次算清 ----
    @Test
    fun `画布判定_横屏场景在竖屏整板失活`() {
        val c = sampleCanvas() // bind=LANDSCAPE
        val input = SceneBoard.fromCanvas(c, currentRotation = 0, observationsByFeatureId = mapOf(
            "f1" to 0.99, "f2" to 0.97,
        ))
        val v = SceneBoard.evaluate(input)
        assertFalse(v.active)
        assertFalse(v.triggered)
        assertTrue(v.reason.contains("方向不符"))
    }

    @Test
    fun `画布判定_多特征AND全命中才触发且落点被约束`() {
        val c = sampleCanvas().copy(bind = SceneBoard.OrientationBinding.ANY)
        // 只命中 f1，f2 缺观测 → ALL 不触发
        val miss = SceneBoard.evaluate(SceneBoard.fromCanvas(c, 1, mapOf("f1" to 0.99)))
        assertFalse(miss.triggered)
        assertTrue(miss.reason.contains("特征未齐"))

        // 两个都命中 → 触发；落点 (33,44) 在 clickZone(10..60,20..80) 内，不被钳
        val hit = SceneBoard.evaluate(SceneBoard.fromCanvas(c, 1, mapOf("f1" to 0.99, "f2" to 0.98)))
        assertTrue(hit.active)
        assertTrue(hit.triggered)
        assertEquals(setOf("f1", "f2"), hit.firedFeatureIds.toSet())
        assertEquals(33.0, hit.clickXPct, 1e-9)
        assertEquals(44.0, hit.clickYPct, 1e-9)
    }

    @Test
    fun `画布判定_落点越界被钳到限制区且原因可见`() {
        val c = sampleCanvas().copy(
            bind = SceneBoard.OrientationBinding.ANY,
            pointX = 5.0, pointY = 90.0, // 越界
        )
        val v = SceneBoard.evaluate(SceneBoard.fromCanvas(c, 1, mapOf("f1" to 0.99, "f2" to 0.99)))
        assertTrue(v.triggered)
        assertEquals(10.0, v.clickXPct, 1e-9)  // l=10
        assertEquals(80.0, v.clickYPct, 1e-9)  // b=80
        assertTrue(v.reason.contains("落点已约束"))
    }

    @Test
    fun `hasBackground与hasFeatures与summary语义正确`() {
        val empty = SceneCanvas()
        assertFalse(empty.hasBackground())
        assertFalse(empty.hasFeatures())
        val c = sampleCanvas()
        assertTrue(c.hasBackground())
        assertTrue(c.hasFeatures())
        assertTrue(c.summary().contains("2 个特征"))
        assertTrue(c.summary().contains("全部同时出现"))
        assertTrue(c.summary().contains("仅横屏"))
    }

    // 确保 Serializable import 被使用（避免 lint 提醒；实际靠 kotlinx 插件生成序列化器）
    @Suppress("unused")
    @Serializable
    private data class _Marker(val x: Int = 0)
}

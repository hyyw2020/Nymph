// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import dev.nymph.auto.core.editor.SceneTrigger
import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.importer.ScriptCompressor
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.model.SceneClickResolution
import dev.nymph.auto.core.model.SceneObservation
import org.junit.Assert.*
import org.junit.Test

/**
 * v3.0.4 路径乙集成测试（纯 JVM）：
 *   保存画布 → 序列化/反序列化脚本 → 编译出 Action → 断言这些 Action
 *   满足 runSceneDriven 的门控消费契约。
 *
 * 覆盖用户硬门禁（S3 合取门控）：
 *  ① 载体挂 COMBINE(ALL/N, children=m×APPEAR_IMAGE)、N−1 点位不挂门控
 *  ② clickOffset 落进 resolveSceneClick 的 Relative 分支（Hit，非 Absolute/NoValid）
 *  ③ 编译产物按编号顺序、手势映射正确；combine/minCount 编进门控、bind 非 ANY 不假生效。
 *  保留：运行链只读 Action、不接画布模型、K 不再抬到点位数。
 *
 * 说明：真 ScriptRunner.runSceneDriven 的 GestureEngine.dispatch 端到端需要 Android
 * 运行时（Path/GestureDescription/AutoClickService 单例），本套件为 JVM 单测，
 * 以「消费契约 + 派发意图」断言覆盖；真机命中率未验证。
 */
class CanvasCompileIntegrationTest {

    private val templateB64 = "BASE64_OF_ANCHOR_TEMPLATE"
    private val featKey = "feat:aaaabbbbccccdddd"

    /** 一张底图 + 单锚（区域 40..60 / 40..60，中心 50/50）+ 3 个编号点位。 */
    private fun sampleScript(): Script {
        val canvas = SceneCanvas(
            backgroundKey = "canvas:1111222233334444",
            widthPx = 1080, heightPx = 2400,
            rotation = 1,
            bind = SceneBoard.OrientationBinding.LANDSCAPE, // 非 ANY：编译期不得假生效
            combine = Combine.ALL, minCount = 3,             // 编译期必须忽略
            features = listOf(
                SceneCanvas.CanvasFeature("a1", "门控锚", Region(40.0, 40.0, 60.0, 60.0), 88, featKey),
            ),
            points = listOf(
                SceneCanvas.CanvasPoint("p1", "点位1", x = 70.0, y = 50.0,
                    gesture = SceneCanvas.PointGesture.TAP, waitMs = 0),
                SceneCanvas.CanvasPoint("p2", "点位2", x = 50.0, y = 80.0,
                    gesture = SceneCanvas.PointGesture.LONG_PRESS, waitMs = 300),
                SceneCanvas.CanvasPoint("p3", "点位3", x = 30.0, y = 50.0, x2 = 20.0, y2 = 50.0,
                    gesture = SceneCanvas.PointGesture.SWIPE, waitMs = 200),
                // 一个显式绝对兜底点位
                SceneCanvas.CanvasPoint("p4", "点位4", x = 25.0, y = 25.0,
                    gesture = SceneCanvas.PointGesture.MULTI_TAP, useAbsolute = true),
            ),
        )
        return Script(
            name = "画布编译集成",
            sceneDriven = true,
            canvas = canvas,
            assets = mapOf(canvas.backgroundKey to "FULL_FRAME", featKey to templateB64),
        )
    }

    /** 保存 → ScriptCodec 往返 → 取回 canvas 与 assets。 */
    private fun roundTrip(): Script {
        val encoded = ScriptCodec.encode(sampleScript())
        return ScriptCodec.decode(encoded)!!
    }

    @Test
    fun `保存画布经反序列化后编译出按编号顺序的动作`() {
        val script = roundTrip()
        val actions = script.canvas!!.compileToActions(script.assets)
        assertEquals(4, actions.size)
        assertEquals(ActionType.TAP, actions[0].type)
        assertEquals(ActionType.LONG_PRESS, actions[1].type)
        assertEquals(ActionType.SWIPE, actions[2].type)
        assertEquals(ActionType.MULTI_TAP, actions[3].type)
        assertEquals(2, actions[3].times) // 双击 times=2
        assertTrue(actions.all { it.desc.startsWith(SceneCanvas.CANVAS_ACTION_TAG) })
        assertEquals(0L, actions[0].waitMs)
        assertEquals(300L, actions[1].waitMs)
        assertEquals(200L, actions[2].waitMs)
        // 手势参数落到既有字段，不新增 Action 字段
        assertEquals(20.0, actions[2].x2, 1e-9)
        assertEquals(50.0, actions[2].y2, 1e-9)
    }

    @Test
    fun `① S3载体挂COMBINE合取门控且N减1点位不挂门控`() {
        val script = roundTrip()
        val actions = script.canvas!!.compileToActions(script.assets)
        assertEquals(4, actions.size)
        // 只有第 1 条（载体）挂门控，且是 COMBINE(ALL, children=1×APPEAR_IMAGE)。
        val carrier = actions[0]
        assertTrue("载体须被 isSceneTrigger 认作场景门控", SceneTrigger.isSceneTrigger(carrier))
        val gate = SceneTrigger.sceneGateOf(carrier)!!
        assertEquals(ConditionKind.COMBINE, gate.kind)
        assertEquals(Combine.ALL, gate.combine)
        assertEquals(1, gate.children.size)
        assertEquals(ConditionKind.APPEAR_IMAGE, gate.children[0].kind)
        assertEquals(templateB64, gate.children[0].imageData)
        assertEquals(1, SceneTrigger.gateTemplatesOf(carrier).size)
        // N−1 点位不挂 condition、不进 rules：gateTemplatesOf 返回空、isSceneTrigger=false。
        actions.drop(1).forEach { p ->
            assertFalse("N−1 点位不得挂门控", SceneTrigger.isSceneTrigger(p))
            assertTrue(SceneTrigger.gateTemplatesOf(p).isEmpty())
            assertNull(p.condition)
        }
    }

    @Test
    fun `② clickOffset 进 Relative 分支且落点为锚中心加偏移`() {
        val script = roundTrip()
        val actions = script.canvas!!.compileToActions(script.assets)
        // 锚区域 40..60 中心 = 50/50；p1=(70,50) → ox=20, oy=0
        val p1 = actions[0]
        assertTrue(p1.sceneRule!!.usesRelativeClick)
        assertEquals(20.0, p1.sceneRule!!.clickOffsetXPct!!, 1e-9)
        assertEquals(0.0, p1.sceneRule!!.clickOffsetYPct!!, 1e-9)

        // 构造一个「锚匹配中心恰在锚中心」的有效观测 → 落点应≈点位原坐标
        val obs = SceneObservation(
            present = true, score = 0.95,
            cxPx = 50 * 1080 / 100, cyPx = 50 * 2400 / 100, // 锚中心像素
            frameW = 1080, frameH = 2400,
        )
        val resolved = SceneTrigger.resolveSceneClick(obs, p1.sceneRule!!, p1.x, p1.y)
        // 口径收紧：正常路径只接受 Relative Hit；Absolute 只允许出现在显式 useAbsolute 用例。
        assertTrue(p1.sceneRule!!.usesRelativeClick)
        assertFalse("正常路径不得走 Absolute 兜底", resolved is SceneClickResolution.Absolute)
        assertTrue("必须进 Relative Hit 分支，不得 Absolute/NoValid", resolved is SceneClickResolution.Hit)
        val hit = resolved as SceneClickResolution.Hit
        assertEquals("相对匹配坐标", hit.via)
        assertEquals(70.0, hit.xPct, 1e-6) // 50 + 20
        assertEquals(50.0, hit.yPct, 1e-6) // 50 + 0
    }

    @Test
    fun `② 显式绝对兜底点位不写 clickOffset 走 Absolute 分支`() {
        val script = roundTrip()
        val p4 = script.canvas!!.compileToActions(script.assets)[3]
        assertFalse(p4.sceneRule!!.usesRelativeClick)
        assertNull(p4.sceneRule!!.clickOffsetXPct)
        assertNull(p4.sceneRule!!.clickOffsetYPct)
        val obs = SceneObservation(true, 0.95, 500, 500, 1080, 2400)
        val resolved = SceneTrigger.resolveSceneClick(obs, p4.sceneRule!!, p4.x, p4.y)
        assertTrue(resolved is SceneClickResolution.Absolute)
        val abs = resolved as SceneClickResolution.Absolute
        assertEquals(25.0, abs.xPct, 1e-9)
        assertEquals(25.0, abs.yPct, 1e-9)
    }

    @Test
    fun `锚模板几何基准写入 sceneRule 供跨分辨率残差核对`() {
        val script = roundTrip()
        val a = script.canvas!!.compileToActions(script.assets)[0]
        assertEquals(1080, a.sceneRule!!.templateSrcW)
        assertEquals(2400, a.sceneRule!!.templateSrcH)
        assertEquals(1, a.sceneRule!!.pickRotation)
        assertEquals(88, a.sceneRule!!.similarity)
        assertEquals(Region(40.0, 40.0, 60.0, 60.0), a.sceneRule!!.anchorRectPct)
    }

    @Test
    fun `③ S3把combine与minCount编进COMBINE且方向绑定不假生效`() {
        val script = roundTrip()
        val actions = script.canvas!!.compileToActions(script.assets)
        // S3：combine/minCount 现在真正编进载体的 COMBINE 门控（不再忽略）。
        val gate = SceneTrigger.sceneGateOf(actions[0])!!
        assertEquals(ConditionKind.COMBINE, gate.kind)
        assertEquals(script.canvas!!.combine, gate.combine)
        assertEquals(script.canvas!!.minCount, gate.runCountLimit)
        actions.forEach { a ->
            // 方向绑定非 ANY 不写入任何「方向失活」字段（bind 仍留在视图模型供再编辑）
            // SceneRule 没有 bind 字段；pickRotation 仅来自抓帧方向，非 UI 绑定。
            assertNull("不得写互斥组把多点位压成一条", a.sceneRule!!.mutexGroup)
            assertEquals("默认 ON_APPEAR，不新开聚合边沿", dev.nymph.auto.core.model.TriggerMode.ON_APPEAR, a.sceneRule!!.triggerMode)
        }
        // 视图模型仍保留 combine/minCount/bind（反序列化兼容 / 再编辑）
        assertEquals(Combine.ALL, script.canvas!!.combine)
        assertEquals(3, script.canvas!!.minCount)
        assertEquals(SceneBoard.OrientationBinding.LANDSCAPE, script.canvas!!.bind)
    }

    @Test
    fun `锚失配无有效中心时严格模式不派发`() {
        val script = roundTrip()
        val p1 = script.canvas!!.compileToActions(script.assets)[0]
        val invalidObs = SceneObservation(false, 0.4, -1, -1, 1080, 2400)
        val resolved = SceneTrigger.resolveSceneClick(
            invalidObs, p1.sceneRule!!, p1.x, p1.y, lastValid = null, strictNoFallback = true,
        )
        assertTrue("没锚也盲点是禁止的：必须 NoValidLocation", resolved is SceneClickResolution.NoValidLocation)
    }

    @Test
    fun `4落点偏差护栏编译期写更紧值而非引擎默认15`() {
        val script = roundTrip()
        val actions = script.canvas!!.compileToActions(script.assets)
        actions.forEach { a ->
            assertEquals("护栏须为画布更紧值", SceneCanvas.MAX_DEV_PCT, a.sceneRule!!.locMaxDeviationPct, 1e-9)
            assertTrue("须比引擎默认 15 更紧", a.sceneRule!!.locMaxDeviationPct < 15.0)
        }
        // 超阈即 SuspiciousMismatch（曼哈顿 |dx|+|dy|=9 > 8），不派发
        val p1 = actions[0]
        val obs = SceneObservation(true, 0.95,
            cxPx = (32 + 5) * 1080 / 100, cyPx = (68 + 4) * 2400 / 100, frameW = 1080, frameH = 2400)
        val over = SceneTrigger.resolveSceneClick(obs, p1.sceneRule!!, p1.x, p1.y)
        assertTrue("dev=9>8 须拒绝", over is SceneClickResolution.SuspiciousMismatch)
    }

    @Test
    fun `3b画布加其他场景规则混合脚本K全局副作用有断言`() {
        // 混合脚本：已有 1 条手工场景规则 + 画布 4 点位。走真实保存入口 SceneCanvas.mergeInto。
        val manual = dev.nymph.auto.core.model.Action(
            type = ActionType.TAP, desc = "手工场景规则",
            condition = Condition(ConditionKind.APPEAR_IMAGE, imageData = "MANUAL_TPL"),
        )
        val base = Script(
            name = "混合脚本", sceneDriven = true, sceneMaxFiringPerRound = 1,
            actions = listOf(manual), canvas = sampleScript().canvas,
            assets = sampleScript().assets,
        )
        val merged = SceneCanvas.mergeInto(base, base.canvas!!, base.assets)
        // 手工规则保留、旧画布产物不重复；S3 合取只有载体 1 条门控规则触发，K 保持 1。
        assertTrue("手工规则须保留", merged.actions.any { it.desc == "手工场景规则" })
        assertEquals("4 点位编译产物", 4, merged.actions.count {
            it.desc.startsWith(SceneCanvas.CANVAS_ACTION_TAG)
        })
        assertEquals("S3 后 K 保持 1（不再抬到点位数）", 1, merged.sceneMaxFiringPerRound)
        // 再次编辑：mergeInto 幂等，旧画布产物被替换而非累加
        val remerged = SceneCanvas.mergeInto(merged, merged.canvas!!, merged.assets)
        assertEquals("重复保存不累加", 4, remerged.actions.count {
            it.desc.startsWith(SceneCanvas.CANVAS_ACTION_TAG)
        })
        assertEquals(1, remerged.sceneMaxFiringPerRound)
    }

    @Test
    fun `K为脚本级全局旋钮且canvas未参与时老脚本零回归`() {
        // sceneMaxFiringPerRound 是 Script 顶层单值（非 per-rule）：planSceneFiringStateful
        // 一次调用拿单个 K，对当轮全部候选规则统一取前 K 条 => 全局旋钮。
        // 画布编译仅在 EditorScreen.onConfirm 里按 compiled.size 改写；canvas 未参与的老脚本
        // 不进该分支，K 必须保持默认 1，sceneDriven 保持 false。
        val legacy = Script(name = "老脚本-无画布")
        assertEquals(1, legacy.sceneMaxFiringPerRound)
        assertNull(legacy.canvas)
        assertFalse(legacy.sceneDriven)
        val rt = ScriptCodec.decode(ScriptCodec.encode(legacy))!!
        assertEquals("往返后 K 零回归", 1, rt.sceneMaxFiringPerRound)
        assertFalse("往返后驱动链零回归", rt.sceneDriven)
        assertNull("往返后画布仍为空", rt.canvas)
    }

    @Test
    fun `压缩器往返后points模板资源与oxoy不丢`() {
        // 用户门禁 4b：ScriptCompressor 压缩→解压→compileToActions 全链不丢。
        val original = sampleScript()
        val packed = ScriptCompressor.encode(original)
        val unpacked = ScriptCompressor.decodeAny(packed)!!
        val c = unpacked.canvas!!
        assertEquals("points 数量不丢", 4, c.points.size)
        assertEquals("点位手势不丢", SceneCanvas.PointGesture.SWIPE, c.points[2].gesture)
        assertEquals("底图资源不丢", "FULL_FRAME", unpacked.assets[c.backgroundKey])
        assertEquals("锚模板资源不丢", templateB64, unpacked.assets[featKey])
        // 解压后重新编译，ox/oy 仍由 pointPCT - 锚中心算出（p1: 70-50=20, 50-50=0）
        val acts = c.compileToActions(unpacked.assets)
        assertEquals(4, acts.size)
        assertEquals(20.0, acts[0].sceneRule!!.clickOffsetXPct!!, 1e-9)
        assertEquals(0.0, acts[0].sceneRule!!.clickOffsetYPct!!, 1e-9)
        assertEquals("门控模板 key 仍在 points 对应锚上", featKey, c.features[0].templateKey)
    }

    @Test
    fun `老画布脚本无points时降级为单TAP`() {
        val legacy = Script(
            canvas = SceneCanvas(
                backgroundKey = "canvas:old", widthPx = 720, heightPx = 1600,
                features = listOf(SceneCanvas.CanvasFeature("a", "锚", Region(45.0, 45.0, 55.0, 55.0), 82, featKey)),
                pointX = 60.0, pointY = 70.0,
                points = emptyList(),
            ),
            assets = mapOf(featKey to templateB64),
        )
        val actions = legacy.canvas!!.compileToActions(legacy.assets)
        assertEquals(1, actions.size)
        assertEquals(ActionType.TAP, actions[0].type)
        assertTrue(actions[0].sceneRule!!.usesRelativeClick)
    }
}

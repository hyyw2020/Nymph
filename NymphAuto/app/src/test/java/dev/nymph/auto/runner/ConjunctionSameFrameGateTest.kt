// SPDX-License-Identifier: MIT
package dev.nymph.auto.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.0.3 S3/S4 静态门禁：把「m 子条件求值期间 frame() 恰好 1 次」固化为源码结构断言。
 *
 * 运行时的同帧由两层保证，本测试锁定这两层不被无意改坏：
 *  1. ScriptRunner.runSceneDriven 每轮只调用一次 vision.findTemplates（一次调用=一次抓帧）；
 *  2. ScreenSource.findTemplates 内抓帧表达式只有一处（if freshFrame grabFreshFrame else frame），
 *     m 条 spec 共享同一 base；S4 整图门在同一 base 上跑，不额外抓帧。
 *
 * 说明：这是静态结构门禁（与 RuntimeCanvasZeroReferenceTest 同风格）；真机逐帧行为未验证。
 */
class ConjunctionSameFrameGateTest {

    private fun appSrc(): File {
        val module = File(System.getProperty("user.dir"))
        val src = File(module, "src/main/java/dev/nymph/auto")
        assertTrue("工作目录异常：$module", src.isDirectory)
        return src
    }

    @Test
    fun `场景驱动每轮只调用一次findTemplates_单次抓帧`() {
        val f = File(appSrc(), "runner/ScriptRunner.kt")
        val text = f.readText()
        val count = Regex("""vision\.findTemplates\s*\(""").findAll(text).count()
        assertEquals("runSceneDriven 每轮只允许一次 findTemplates 调用（=一次抓帧供 m 模板共享）", 1, count)
    }

    @Test
    fun `findTemplates内抓帧点唯一且共享base_整图门不额外抓帧`() {
        val f = File(appSrc(), "engine/ScreenSource.kt")
        val text = f.readText()
        // 唯一抓帧决策点：if (freshFrame) grabFreshFrame() else frame()
        val grab = Regex("""if\s*\(freshFrame\)\s*grabFreshFrame\(\)\s*else\s*frame\(\)""")
            .findAll(text).count()
        assertEquals("findTemplates 只能有一处抓帧决策点", 1, grab)
        // 整图门传入的是同一份 base（SceneTrigger.verifyWholeFrame 的 current = base），
        // 不得在整图门内再次抓帧（源码中 verifyWholeFrame 调用不包裹任何 frame/grab）。
        assertTrue("整图门必须复用同帧 base",
            text.contains("current = base") || text.contains("current=base"))
    }

    @Test
    fun `合取聚合只用生产纯函数_不另写一套求值`() {
        val f = File(appSrc(), "runner/ScriptRunner.kt")
        val text = f.readText()
        assertTrue("合取聚合必须调用 SceneTrigger.aggregateObservations",
            text.contains("SceneTrigger.aggregateObservations"))
        assertTrue("点位展开必须复用 SceneTrigger.canvasPointIndices",
            text.contains("SceneTrigger.canvasPointIndices"))
        // 不得回退到 N 条各挂 spec 的老写法：rules 装配里 gateTemplatesOf 逐 children 展开，
        // 派发由载体单点触发（sceneGatePreChecked=true），不重复评估 condition。
        assertTrue("派发必须经 sceneGatePreChecked 短路二次求值",
            text.contains("sceneGatePreChecked = true"))
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.runner

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.0.4 防回退门禁（对应硬门禁：运行链对 canvas/SceneBoard 零引用）。
 *
 * 路径乙要求：画布只在 UI 层做可视化编辑视图，保存时编译进现有 Action/sceneRule；
 * 运行链（runner/service/engine）不得直接引用 SceneCanvas/SceneBoard/compileToActions，
 * 否则等于回退成「在运行时保留第二套画布模型」（路径甲）。
 *
 * 以源码文本扫描固化，避免未来有人无意把 canvas 接进执行链。
 */
class RuntimeCanvasZeroReferenceTest {

    private val runtimeDirs = listOf("runner", "service", "engine")

    @Test
    fun `运行链对 canvas与SceneBoard零引用`() {
        val moduleRoot = File(System.getProperty("user.dir"))
        val srcRoot = File(moduleRoot, "src/main/java/dev/nymph/auto")
        assertTrue("测试工作目录异常：$moduleRoot", srcRoot.isDirectory)

        val forbidden = listOf("SceneBoard", "SceneCanvas", "fromCanvas", "compileToActions", "CanvasFeature")
        val hits = mutableListOf<String>()
        runtimeDirs.forEach { dir ->
            File(srcRoot, dir).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    val text = f.readText()
                    forbidden.forEach { token ->
                        if (text.contains(token)) hits += "${f.name}: $token"
                    }
                }
        }
        assertTrue(
            "运行链不得引用画布模型（路径甲回退），发现：${hits.joinToString()}",
            hits.isEmpty(),
        )
    }
}

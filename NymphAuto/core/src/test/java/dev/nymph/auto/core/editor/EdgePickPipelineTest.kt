// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.vision.ImageFinder
import dev.nymph.auto.core.vision.StableAnchor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贴边拾取的装配链路回归：拾取点贴近屏幕右边缘（横坐标 97% 量级）时，
 * 逐步验证裁剪、邻域采样、区域收敛与锚点选取各自的有效性，
 * 用于把「装配失败」定位到具体阶段，而非统一归为采样失败。
 */
class EdgePickPipelineTest {

    private fun texturedImage(w: Int, h: Int): ImageFinder.Image {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val checker = ((x / 16) + (y / 16)) and 1
                px[row + x] = if (checker == 0) 0xFF203040.toInt() else 0xFFD0C0B0.toInt()
            }
        }
        return ImageFinder.Image(w, h, px)
    }

    @Test
    fun edgePick_eachStageRemainsValid() {
        val w = 1080
        val h = 2400
        val img = texturedImage(w, h)
        val roi = Region(leftPct = 85.0, topPct = 30.2, rightPct = 100.0, bottomPct = 41.2)
        val pickXPct = 97.03
        val pickYPct = 35.68
        val pickXPx = (pickXPct / 100.0 * w).toInt()
        val pickYPx = (pickYPct / 100.0 * h).toInt()

        val crop = SceneTrigger.cropPx(roi, w, h)
        assertTrue("裁剪应为四元组", crop.size == 4)
        val roiPx = StableAnchor.PxRect(crop[0], crop[1], crop[2], crop[3])
        assertTrue("贴边 ROI 的像素矩形应有效：$roiPx", roiPx.valid)
        assertTrue("拾取点应落在 ROI 内", SceneAssist.contains(roi, pickXPct, pickYPct))

        val pixelAt: (Int, Int) -> Int? = { x, y ->
            if (x in 0 until w && y in 0 until h) img.argb.getOrNull(y * w + x) else null
        }
        val sample = SceneAssist.sampleNeighborhood(pixelAt, pickXPx, pickYPx, w, h)
        println("样本点数=${sample.sampleCount} 代表色=#${Integer.toHexString(sample.argb)} 离散度=${sample.spread}")
        assertTrue("贴边拾取仍应采到样本，实际 ${sample.sampleCount}", sample.valid)

        val converged = SceneAssist.convergeRoi(pickXPct, pickYPct, sample, w, h)
        println("收敛区域=${converged.expr}")
        assertTrue("收敛区域应非空", !SceneAssist.isEmptyRoi(converged))

        val pick = StableAnchor.pickAnchor(img, roiPx, pickXPx, pickYPx, StableAnchor.anchorSizePx(w, h))
        println("锚点 stable=${pick.stable} rect=${pick.rect} energy=${pick.edgeEnergy}")
        assertTrue("纹理区域应存在稳定锚点", pick.stable && pick.rect.valid)
    }

    @Test
    fun cornerPick_samplingWindowIsBoundedByFrame() {
        val w = 720
        val h = 1280
        val img = texturedImage(w, h)
        val pixelAt: (Int, Int) -> Int? = { x, y ->
            if (x in 0 until w && y in 0 until h) img.argb.getOrNull(y * w + x) else null
        }
        // 右下角：窗口近半越界，仍需返回有效样本
        val sample = SceneAssist.sampleNeighborhood(pixelAt, w - 1, h - 1, w, h)
        println("角落样本点数=${sample.sampleCount}")
        assertTrue("角落拾取仍应采到样本", sample.valid)
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.vision.ImageFinder
import dev.nymph.auto.core.vision.ImageMask

/**
 * **拾取即自测**（v2.9.15 / A5，core 纯 JVM，可单测）。
 *
 * 保存前把锚点模板在全屏复算一遍，产出最高命中分、次高干扰分、命中位置偏差三项
 * 原始观测量，用于识别 secure 黑帧 / dismiss 时序竞争 / 裁剪偏移三类静默失败。
 * 三项观测量交由 `SceneVerdict` 判定成可执行结论，界面不直接呈现原始分数。
 *
 * 本对象复算三项（全部基于现成 NCC 匹配，不重写识别引擎）：
 *  - [Result.best]：搜索 ROI 内对锚点模板的**全局最佳分**（0~1，bestScore 不套阈值）；
 *  - [Result.runnerUp]：**ROI 之外**的最高误匹配分（把搜索 ROI 抑制成背景后再全屏扫，
 *    近似「次高干扰分」），供 [SceneTrigger.calibrateSimilarity] 抬阈值；
 *  - [Result.locDeviationPct]：命中中心相对拾取点的偏差（屏幕百分比），偏大 = 裁歪/错位。
 *
 * 掩码/抑制只作用于**自测用副本**（`argb.copyOf()`），不改真实屏幕、不改存证、不改点击。
 * 拾取是一次性、用户等待中的操作，允许多次 NCC；A6「一轮一帧」约束的是运行时每轮，
 * 不约束拾取瞬间。ROI 外干扰分是「抑制 ROI 后全屏 bestScore」的单帧启发式近似，
 * 不是严格的全图次高峰，按实际状态标注。
 */
object SceneSelfTest {

    /** 自测结果。best/runnerUp ∈ [0,1]；无命中时 locDeviationPct = -1（未测）。 */
    data class Result(
        val best: Double,
        val runnerUp: Double,
        val locDeviationPct: Double,
        val hitCxPx: Int,
        val hitCyPx: Int,
        /** v2.9.31：装配期多帧 NCC 最低值（0~1）。-1 = 未测（单帧 / 老脚本 / 采集失败）。 */
        val crossFrameFloor: Double = -1.0,
    ) {
        /** 是否明显是「secure 黑帧 / 全屏无有效纹理」：连 ROI 内都几乎匹配不上。 */
        val looksLikeBlankFrame: Boolean get() = best < 0.45
    }

    /**
     * @param screen 拾取瞬间全屏图（core 像素数组，调用方勿 recycle 本对象持有的视图）
     * @param anchor 稳定局部锚点小图（core 像素数组）
     * @param searchRoiPct 搜索 ROI（屏幕百分比四元组）
     * @param pickXPct/pickYPct 拾取点（屏幕百分比），仅用于位置偏差
     * @param minSimilar 该锚点当前采用的相似度阈值（百分制）；命中位置用它判定
     * @param refDpi/curDensity 模板尺度比（与运行时 imageScale 一致）
     */
    fun run(
        screen: ImageFinder.Image,
        anchor: ImageFinder.Image,
        searchRoiPct: ImageFinder.RegionPct,
        pickXPct: Double,
        pickYPct: Double,
        minSimilar: Int,
        refDpi: Double = 0.0,
        curDensity: Double = 1.0,
    ): Result {
        // 1) ROI 内全局最佳分（不套阈值）+ 命中中心
        val best = ImageFinder.bestScore(
            screen, anchor,
            searchRoiPct.left, searchRoiPct.top, searchRoiPct.right, searchRoiPct.bottom,
            refDpi, curDensity,
        )
        val detailed = ImageFinder.findDetailed(
            screen, anchor,
            searchRoiPct.left, searchRoiPct.top, searchRoiPct.right, searchRoiPct.bottom,
            minSimilar, refDpi, curDensity,
        )
        val hit = detailed.hit

        // 2) ROI 之外次高干扰分：把搜索 ROI 抑制成背景行，再全屏 bestScore
        val outside = ImageFinder.Image(screen.width, screen.height, screen.argb.copyOf())
        ImageMask.suppress(
            outside,
            listOf(
                ImageMask.RectPct(
                    searchRoiPct.left, searchRoiPct.top, searchRoiPct.right, searchRoiPct.bottom,
                ),
            ),
        )
        val runnerUp = ImageFinder.bestScore(
            outside, anchor, 0.0, 0.0, 100.0, 100.0, refDpi, curDensity,
        )

        // 3) 命中中心与拾取点的偏差（屏幕百分比，x/y 绝对差之和）
        val dev = if (hit != null && screen.width > 0 && screen.height > 0) {
            val hitXPct = 100.0 * hit.cx / screen.width
            val hitYPct = 100.0 * hit.cy / screen.height
            kotlin.math.abs(hitXPct - pickXPct) + kotlin.math.abs(hitYPct - pickYPct)
        } else -1.0

        return Result(
            best = best,
            runnerUp = runnerUp,
            locDeviationPct = dev,
            hitCxPx = hit?.cx ?: -1,
            hitCyPx = hit?.cy ?: -1,
        )
    }

    /**
     * **跨帧 NCC 下界**（v2.9.31，纯函数，可单测）。
     *
     * 装配期连采多帧（间隔 ≥100ms）后，对每一帧在同一 ROI 内跑一次
     * `bestScore`，取**最小值**作为「目标在下一帧最低能到多少」的实测上界约束，
     * 交给 [SceneTrigger.calibrateSimilarity] 限制阈值。
     *
     * 这直接针对真机 RCA 的「同帧自证」缺陷：旧 `run` 在同一张图上裁锚点又
     * 在同一张图上比对，命中必然 1.00，从未测量过跨帧复现性。本函数不做任何
     * 抑制或模板改动，只测「同一模板跨帧的最低相似度」。
     *
     * 少于 2 帧 / 帧尺寸不一致 / 任一帧匹配失败一律返回 -1（未测），调用方
     * 逐字退回单帧校准逻辑，零回归。
     */
    fun crossFrameFloor(
        screens: List<ImageFinder.Image>,
        anchor: ImageFinder.Image,
        searchRoiPct: ImageFinder.RegionPct,
        refDpi: Double = 0.0,
        curDensity: Double = 1.0,
    ): Double {
        if (screens.size < 2) return -1.0
        val w = screens[0].width
        val h = screens[0].height
        if (w <= 0 || h <= 0) return -1.0
        if (screens.any { it.width != w || it.height != h }) return -1.0
        var floor = Double.POSITIVE_INFINITY
        for (s in screens) {
            val v = ImageFinder.bestScore(
                s, anchor,
                searchRoiPct.left, searchRoiPct.top, searchRoiPct.right, searchRoiPct.bottom,
                refDpi, curDensity,
            )
            if (v < 0.0 || !v.isFinite()) return -1.0
            if (v < floor) floor = v
        }
        return if (floor.isFinite()) floor else -1.0
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

import dev.nymph.auto.core.model.MaskRect
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.vision.ImageFinder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **方向无关的坐标/帧变换**（v2.9.32，core 纯 JVM 单一真源，零 Android 依赖）。
 *
 * ## 修的是什么
 *
 * 三道「方向硬闸」把横竖屏切换判成致命错误：预检把「截图脚本 + 方向相反」升级为
 * problems 直接拦下；执行主循环见到 `frame.rotation != refRotation` 就中止；帧几何
 * 闭环见宽高比互为倒数就判 `geometryOk=false`、不跑 NCC。三者叠加，竖屏拾取、
 * 横屏运行（或运行中旋转）的脚本必然中断、落点错位。
 *
 * 竞品（Klick'r）对旋转的处理是「重标定后继续、绝不中止检测」：方向变化时按新
 * 屏幕尺寸重算条件坐标再续跑。本对象把这套**控制模型**移植到无障碍截图通道
 * （不引入 MediaProjection、不新增权限），用四分之一转的角度差把「拾取参考系」
 * 与「当前帧参考系」严格互转：
 *
 * ```
 * 帧归一化：当前帧旋转 q 个 90° → 与拾取锚点同几何 → NCC / 取色 / ROI 全部复用既有逻辑
 * 落点反变换：匹配中心按 -q 旋回当前帧 → 注入到真实屏幕方向
 * ```
 *
 * ## 为什么用「帧归一化」而不是「旋转模板」
 *
 * 稳定锚点、颜色样本、收敛 ROI、掩码块全部以拾取参考系的**屏幕百分比**落盘。
 * 把当前帧旋回拾取几何后，这些既有百分比、NCC、取色链一字不改即可继续工作；
 * 只有最终注入落点需要一次逆旋转。相比旋转每一份模板图，这条路径只转一次帧，
 * 一轮一帧共享，开销与方向一致时相同。
 *
 * ## 角度约定
 *
 * 采用 Android `Surface.ROTATION_*` 的四分之一转数值（0/1/2/3，顺时针递增）。
 * `turns = ((cur - ref) mod 4 + 4) mod 4` 表示「当前帧相对拾取帧顺时针转了几格」。
 * 帧归一化要把当前帧**逆时针**转回拾取几何，即旋转 `-turns` 格；落点注入则把
 * 拾取参考系百分比顺时针转 `turns` 格回到当前屏幕。
 *
 * 纯函数：不读时钟、不持位图、不依赖 Android。全部状态由调用方显式传入，便于
 * JVM 单测穷举 0/90/180/270 四种角度。
 */
object OrientationTransform {

    /** 把任意 rotation 归一到 0..3（兼容传感器给出的负数/超界值）。 */
    fun norm(rotation: Int): Int = ((rotation % 4) + 4) % 4

    /**
     * 当前帧相对拾取参考系的顺时针四分之一转数（0/1/2/3）。
     * [refRotation]=0 的老脚本等价于「按拾取帧尺寸推断」，见 [fromDimensions]。
     */
    fun turns(refRotation: Int, curRotation: Int): Int = norm(curRotation - refRotation)

    /**
     * 仅凭宽高推断角度差（老脚本 refRotation 缺省 0 时的兜底）。
     * 同向（都竖或都横）→ 0；宽高互换 → 1（90°），方向由调用方用 rotation 精化。
     */
    fun fromDimensions(refW: Int, refH: Int, curW: Int, curH: Int): Int {
        if (refW <= 0 || refH <= 0 || curW <= 0 || curH <= 0) return 0
        val refLandscape = refW > refH
        val curLandscape = curW > curH
        return if (refLandscape == curLandscape) 0 else 1
    }

    /** 帧是否相对参考旋转了 90°/270°（宽高互换）。 */
    fun isQuarterTurn(turns: Int): Boolean {
        val t = norm(turns)
        return t == 1 || t == 3
    }

    /** 旋转 q 个 90° 后的帧宽高：奇数格互换宽高。 */
    fun rotatedSize(width: Int, height: Int, turns: Int): Pair<Int, Int> {
        val t = norm(turns)
        return if (isQuarterTurn(t)) height to width else width to height
    }

    /**
     * 帧几何兼容判定（取代旧「宽高比必须近等」的硬闭环）。
     *
     * 只要当前帧与拾取帧是「同一方向」或「恰好互转 90°」即兼容；两者都可以通过
     * [rotateImage] 归一化到拾取几何。宽高比异常（既非同向也非互换，如异形屏裁剪）
     * 才判不兼容，维持 fail-closed。
     */
    fun geometryCompatible(refW: Int, refH: Int, curW: Int, curH: Int): Boolean {
        if (refW <= 0 || refH <= 0 || curW <= 0 || curH <= 0) return true // 老脚本无尺寸：零回归
        val refRatio = refW.toDouble() / refH
        val curRatio = curW.toDouble() / curH
        val direct = relDiff(refRatio, curRatio) <= GEOMETRY_TOL
        val reciprocal = relDiff(refRatio, 1.0 / curRatio) <= GEOMETRY_TOL
        return direct || reciprocal
    }

    private fun relDiff(a: Double, b: Double): Double {
        if (a <= 0.0) return Double.MAX_VALUE
        return abs(a - b) / a
    }

    /**
     * 把 [img] 顺时针旋转 [turns] 个 90°，返回新图像（不修改入参）。
     * 用于「当前帧 → 拾取几何」时传 `-turns`（norm 后即 4-turns）。
     */
    fun rotateImage(img: ImageFinder.Image, turns: Int): ImageFinder.Image {
        val t = norm(turns)
        if (t == 0) return img
        val w = img.width
        val h = img.height
        if (w <= 0 || h <= 0) return img
        val (nw, nh) = rotatedSize(w, h, t)
        val out = IntArray(nw * nh)
        val src = img.argb
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (dx, dy) = rotatePixel(x, y, w, h, t)
                if (dx in 0 until nw && dy in 0 until nh) {
                    out[dy * nw + dx] = src[y * w + x]
                }
            }
        }
        return ImageFinder.Image(nw, nh, out)
    }

    /** 像素坐标顺时针旋转 q 个 90°。 */
    private fun rotatePixel(x: Int, y: Int, w: Int, h: Int, turns: Int): Pair<Int, Int> =
        when (norm(turns)) {
            1 -> (h - 1 - y) to x           // 90° CW: (x,y) -> (H-1-y, x)
            2 -> (w - 1 - x) to (h - 1 - y) // 180°
            3 -> y to (w - 1 - x)           // 90° CCW: (x,y) -> (y, W-1-x)
            else -> x to y
        }

    /**
     * 屏幕百分比点从「拾取参考系」映射到「当前帧参考系」：顺时针转 [turns] 格。
     * 与 [rotateImage] 的帧归一化互逆——匹配中心在归一化帧上算出后，用本函数旋回
     * 当前屏幕即可得到注入落点。
     */
    fun pointToCurrent(xPct: Double, yPct: Double, turns: Int): Pair<Double, Double> {
        val t = norm(turns)
        // 以帧中心为原点、把 [0,100] 归一到 [-1,1] 正方形再做 90° 旋转，
        // 归一化坐标下的旋转与矩形宽高无关，规避横竖屏宽高不等带来的拉伸。
        val nx = xPct / 100.0 * 2.0 - 1.0
        val ny = yPct / 100.0 * 2.0 - 1.0
        val (rx, ry) = rotateUnit(nx, ny, t)
        return ((rx + 1.0) / 2.0 * 100.0).coerceIn(0.0, 100.0) to
            ((ry + 1.0) / 2.0 * 100.0).coerceIn(0.0, 100.0)
    }

    /** 归一化坐标 [-1,1] 的顺时针四分之一转（数学 y 向下屏幕系）。 */
    private fun rotateUnit(x: Double, y: Double, turns: Int): Pair<Double, Double> =
        when (norm(turns)) {
            1 -> -y to x
            2 -> -x to -y
            3 -> y to -x
            else -> x to y
        }

    /** Region（屏幕百分比矩形）从拾取参考系旋回当前帧参考系。 */
    fun regionToCurrent(r: Region, turns: Int): Region {
        if (norm(turns) == 0) return r
        val c1 = pointToCurrent(r.leftPct, r.topPct, turns)
        val c2 = pointToCurrent(r.rightPct, r.bottomPct, turns)
        return Region(
            min(c1.first, c2.first), min(c1.second, c2.second),
            max(c1.first, c2.first), max(c1.second, c2.second),
        )
    }

    /** 掩码块从拾取参考系旋回当前帧参考系。 */
    fun maskToCurrent(m: MaskRect, turns: Int): MaskRect {
        if (norm(turns) == 0) return m
        val c1 = pointToCurrent(m.leftPct, m.topPct, turns)
        val c2 = pointToCurrent(m.rightPct, m.bottomPct, turns)
        return MaskRect(
            min(c1.first, c2.first), min(c1.second, c2.second),
            max(c1.first, c2.first), max(c1.second, c2.second),
        )
    }

    /**
     * 落点可信度护栏：旋转后落点相对「上一有效落点」的偏移超过阈值时视为失效。
     * 用于方向切换瞬间的去重——避免把旋转映射出的坐标当成新一次命中重复触发。
     */
    fun driftPct(aX: Double, aY: Double, bX: Double, bY: Double): Double =
        abs(aX - bX) + abs(aY - bY)

    const val GEOMETRY_TOL = 0.08
}

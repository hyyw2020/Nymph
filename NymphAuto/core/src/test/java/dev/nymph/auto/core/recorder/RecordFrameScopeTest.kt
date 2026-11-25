// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import dev.nymph.auto.core.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.27：录制事件的坐标系必须「按每个事件自身」换算，而不是全局复用首个
 * 事件的 screenW/H。
 *
 * 现场日志：录制服务启动于 1280×2800（竖屏），录制期间设备转为 2800×1280
 * （横屏）后采集的手势仍带竖屏参考，AnchorSelector.pct 用 1280 当分母，
 * 把 1400px 算成 109% 再 coerceIn 到 100% —— 坐标静默落到屏幕边缘。
 *
 * 这里锁定编译器的既有正确契约（common 用 e.screenW/e.screenH），配合
 * RecorderBus.currentFrameProvider 让每个事件携带实时坐标系，构成完整闭环；
 * 防止后续有人把分母改回「脚本 refWidth」而重新引入该缺陷。
 */
class RecordFrameScopeTest {

    private val compiler = RecordCompiler()

    /** 纯坐标点击：无任何节点/文字/模板锚点，编译后必为 TAP + 百分比坐标。 */
    private fun coordClick(atMs: Long, x: Int, y: Int, w: Int, h: Int, rotation: Int) =
        RecordEvent(
            atMs = atMs,
            seq = atMs,
            kind = RecordEventKind.CLICK,
            x = x,
            y = y,
            screenW = w,
            screenH = h,
            rotation = rotation,
        )

    @Test
    fun `方向切换后每个事件按自身坐标系换算百分比`() {
        // 竖屏：640/1280 = 50%，840/2800 = 30%
        val portrait = coordClick(100L, 640, 840, 1280, 2800, 0)
        // 横屏：1400/2800 = 50%，384/1280 = 30%
        val landscape = coordClick(600L, 1400, 384, 2800, 1280, 1)

        val r = compiler.compile("转屏录制", listOf(portrait, landscape))
        val taps = r.script.actions.filter { it.type == ActionType.TAP }
        assertEquals("应保留两个坐标动作", 2, taps.size)

        // 关键：两个动作都应是 50% / 30%，证明各自分母是自己的 screenW/H。
        // 若误用首个事件的 1280×2800 作全局分母，第二个会是 100% / 13.7%。
        for ((i, tap) in taps.withIndex()) {
            assertEquals("第 ${i + 1} 个动作 X", 50.0, tap.x, 0.01)
            assertEquals("第 ${i + 1} 个动作 Y", 30.0, tap.y, 0.01)
        }
    }

    @Test
    fun `越界像素被收敛而不是静默变成远处坐标`() {
        // 极端情形：事件坐标系小于实际像素（旧分母问题），pct 必须夹到 100%，
        // 而不是产生 >100% 的坐标交给回放层。
        val e = coordClick(100L, 3000, 100, 1280, 2800, 0)
        val r = compiler.compile("越界", listOf(e))
        val tap = r.script.actions.first { it.type == ActionType.TAP }
        assertEquals(100.0, tap.x, 0.01)
    }

    @Test
    fun `脚本参考尺寸取首个事件的坐标系`() {
        val a = coordClick(100L, 100, 100, 1280, 2800, 0)
        val b = coordClick(600L, 100, 100, 2800, 1280, 1)
        val r = compiler.compile("ref", listOf(a, b))
        assertEquals(1280, r.script.refWidth)
        assertEquals(2800, r.script.refHeight)
        assertEquals(0, r.script.refRotation)
    }

    @Test
    fun `转屏事件各自携带的 rotation 不为空`() {
        val a = coordClick(100L, 100, 100, 1280, 2800, 0)
        // 像素坐标与首个事件不同，避开编译器的「同锚点快速连点合并」，
        // 以单独验证方向切换不会导致动作丢失。
        val b = coordClick(600L, 2600, 300, 2800, 1280, 1)
        val r = compiler.compile("rotation", listOf(a, b))
        assertTrue(a.rotation != b.rotation)
        assertEquals(2, r.script.actions.count { it.type == ActionType.TAP })
    }
}

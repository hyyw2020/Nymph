// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPolicyTest {

    @Test
    fun `0 是无限循环 不能被当成执行零次`() {
        assertEquals(0, LoopPolicy.normalize(0))
        assertTrue(LoopPolicy.isInfinite(0))
        assertEquals("无限循环", LoopPolicy.label(0))
    }

    @Test
    fun `负数和越界次数被钳制到合法范围`() {
        assertEquals(1, LoopPolicy.normalize(-5))
        assertEquals(1, LoopPolicy.normalize(0 + 1))
        assertEquals(LoopPolicy.MAX_FINITE, LoopPolicy.normalize(999999))
        assertEquals(50, LoopPolicy.normalize(50))
    }

    @Test
    fun `解析中文无限与圈符号与数字零`() {
        assertEquals(0, LoopPolicy.parse("无限"))
        assertEquals(0, LoopPolicy.parse(" 无限 "))
        assertEquals(0, LoopPolicy.parse("∞"))
        assertEquals(0, LoopPolicy.parse("0"))
    }

    @Test
    fun `解析有限次数并归一化上限`() {
        assertEquals(1, LoopPolicy.parse("1"))
        assertEquals(10, LoopPolicy.parse("10"))
        assertEquals(LoopPolicy.MAX_FINITE, LoopPolicy.parse("100000"))
    }

    @Test
    fun `非法输入返回空 由界面提示而不是静默写成一次`() {
        assertNull(LoopPolicy.parse("abc"))
        assertNull(LoopPolicy.parse(""))
        assertNull(LoopPolicy.parse("-1"))
        assertNull(LoopPolicy.parse("1.5"))
    }

    @Test
    fun `标签与描述覆盖三种语义`() {
        assertEquals("执行 1 次", LoopPolicy.label(1))
        assertEquals("重复 3 次", LoopPolicy.label(3))
        assertTrue(LoopPolicy.description(0).contains("无限"))
        assertTrue(LoopPolicy.description(3).contains("3"))
        assertFalse(LoopPolicy.isInfinite(3))
    }

    @Test
    fun `isFinished 负次数归一为一次 不再被当成无限循环`() {
        assertFalse(LoopPolicy.isFinished(0, 1))
        assertTrue(LoopPolicy.isFinished(1, 1))
        assertFalse(LoopPolicy.isFinished(1, 3))
        assertTrue(LoopPolicy.isFinished(3, 3))
        assertTrue(LoopPolicy.isFinished(99, 3))
        // v2.9.4：旧式子 `repeatCount in 1..loops` 对负数恒 false，
        // 手写 JSON 的负 repeatCount 会被当成无限循环跑到手动停止。
        assertTrue(LoopPolicy.isFinished(1, -5))
        assertTrue(LoopPolicy.isFinished(1, Int.MIN_VALUE))
    }

    @Test
    fun `isFinished 零为无限 永不自动结束`() {
        repeat(1000) { n -> assertFalse(LoopPolicy.isFinished(n, 0)) }
        assertFalse(LoopPolicy.isFinished(Int.MAX_VALUE, 0))
    }

    // ------------------------------------------------------------ v2.9.20 范式作用域

    @Test
    fun `范式作用域由 sceneDriven 唯一决定`() {
        assertEquals(LoopPolicy.LoopScope.SEQUENTIAL, LoopPolicy.scopeOf(false))
        assertEquals(LoopPolicy.LoopScope.SCENE_DRIVEN, LoopPolicy.scopeOf(true))
        assertTrue(LoopPolicy.appliesTo(LoopPolicy.LoopScope.SEQUENTIAL))
        assertFalse(LoopPolicy.appliesTo(LoopPolicy.LoopScope.SCENE_DRIVEN))
    }

    @Test
    fun `顺序范式文案沿用既有轮次语义`() {
        assertEquals(LoopPolicy.label(5), LoopPolicy.labelFor(5, LoopPolicy.LoopScope.SEQUENTIAL))
        assertEquals("无限循环", LoopPolicy.labelFor(0, LoopPolicy.LoopScope.SEQUENTIAL))
    }

    @Test
    fun `场景范式文案不呈现循环次数数值`() {
        val scoped = LoopPolicy.labelFor(5, LoopPolicy.LoopScope.SCENE_DRIVEN)
        assertEquals(LoopPolicy.SCENE_DRIVEN_LABEL, scoped)
        assertEquals(scoped, LoopPolicy.labelFor(0, LoopPolicy.LoopScope.SCENE_DRIVEN))
        assertEquals(scoped, LoopPolicy.labelFor(9999, LoopPolicy.LoopScope.SCENE_DRIVEN))
        assertFalse(scoped.contains("5"))
    }

    @Test
    fun `场景范式说明显式声明字段作用域与结束条件`() {
        val notice = LoopPolicy.sceneDrivenNotice()
        assertTrue(notice.contains("不约束本范式主循环"))
        assertTrue(notice.contains("中止条件"))
        assertTrue(notice.contains("通知栏停止"))
    }

    @Test
    fun `范式扩展不改变既有刹停语义`() {
        assertTrue(LoopPolicy.isFinished(5, 5))
        assertFalse(LoopPolicy.isFinished(4, 5))
        assertEquals(LoopPolicy.INFINITE, LoopPolicy.parse("无限"))
        assertNull(LoopPolicy.parse("非数字输入"))
    }

    @Test
    fun `场景范式说明提供按次数停止的可操作指引`() {
        val notice = LoopPolicy.sceneDrivenNotice()
        assertTrue(notice.contains("不提供次数维度上限"))
        assertTrue(notice.contains("配置中止条件"))
    }
}

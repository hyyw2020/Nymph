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
}

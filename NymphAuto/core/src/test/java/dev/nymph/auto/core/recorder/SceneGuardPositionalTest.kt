// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import dev.nymph.auto.core.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.1 真机回归：2026-11-10 调试包里，用户在「应用抽屉」录了 97 个 SCROLL
 * → 编译成 38 个 SWIPE，回放却停在桌面主屏 / 日历 / 搜索历史页（还误触搜索框
 * 弹出键盘）。抽屉、主屏、搜索页同属一个 launcher 包名，旧守卫只比包名 →
 * PROCEED 静默放行；rootInActiveWindow() 取不到或刚启动仍在自家界面 → SKIP
 * 静默放行。两条路径都不提示，用户只看到「命中 0、失败 0」却不知动作全打错。
 *
 * 这里锁死：含坐标手势时，这三条静默放行路径必须升级为 PROCEED_WITH_WARNING；
 * 不含坐标手势时（默认参数 false）既有四种决策逐字不变。
 */
class SceneGuardPositionalTest {

    private val launchable = { p: String -> p == "com.demo.app" }
    private val launcher = "com.miui.home"

    // ---- 1) 位置敏感动作识别 ----

    @Test
    fun `翻页点击等直接派发手势属于位置敏感`() {
        assertTrue(SceneGuard.hasPositionalActions(listOf(ActionType.SWIPE)))
        assertTrue(SceneGuard.hasPositionalActions(listOf(ActionType.TAP)))
        assertTrue(SceneGuard.hasPositionalActions(listOf(ActionType.CURVE, ActionType.PINCH_IN)))
    }

    @Test
    fun `查找类动作与纯逻辑动作不算位置敏感`() {
        assertFalse(SceneGuard.hasPositionalActions(listOf(ActionType.CLICK_IMAGE)))
        assertFalse(SceneGuard.hasPositionalActions(listOf(ActionType.CLICK_TEXT)))
        assertFalse(SceneGuard.hasPositionalActions(listOf(ActionType.WAIT, ActionType.GOTO, ActionType.SET_VAR)))
        assertFalse(SceneGuard.hasPositionalActions(emptyList()))
    }

    @Test
    fun `混合脚本只要含一个坐标手势即为位置敏感`() {
        assertTrue(SceneGuard.hasPositionalActions(
            listOf(ActionType.WAIT, ActionType.CLICK_IMAGE, ActionType.SWIPE),
        ))
    }

    // ---- 2) 含坐标手势时，三条静默放行路径升级为必达告警 ----

    @Test
    fun `同包名但含翻页手势时必须告警而非静默 PROCEED`() {
        val r = SceneGuard.decide(launcher, launcher, launchable, hasPositionalActions = true)
        assertEquals(SceneGuard.Decision.PROCEED_WITH_WARNING, r.decision)
        assertTrue(r.message.contains("同包名不等于同界面"))
        assertTrue(r.message.contains("滚动位置"))
    }

    @Test
    fun `取不到前台包名且含翻页手势时必须告警而非静默 SKIP`() {
        val r = SceneGuard.decide(launcher, null, launchable, hasPositionalActions = true)
        assertEquals(SceneGuard.Decision.PROCEED_WITH_WARNING, r.decision)
        assertTrue(r.message.contains("取不到前台包名"))
    }

    @Test
    fun `回放启动仍在自家界面且含翻页手势时必须告警`() {
        val r = SceneGuard.decide(launcher, "dev.nymph.auto", launchable, hasPositionalActions = true)
        assertEquals(SceneGuard.Decision.PROCEED_WITH_WARNING, r.decision)
        assertTrue(r.message.contains("NymphAuto 自身界面"))
    }

    @Test
    fun `告警文案单一真源且空包名时语句通顺`() {
        val r = SceneGuard.decide("", null, launchable, hasPositionalActions = true)
        // 老脚本无起始包名：仍保持 SKIP（行为与 v2.4.0 一致，不制造新决策）
        assertEquals(SceneGuard.Decision.SKIP, r.decision)
        val msg = SceneGuard.positionalWarning("", "界面校验已关闭或脚本未记录起始包名")
        assertTrue(msg.contains("翻页/点击"))
        assertFalse(msg.contains("都是 "))   // 空包名不应留下半截句子
    }

    // ---- 3) 既有决策保持逐字不变（默认 hasPositionalActions=false）----

    @Test
    fun `默认不含坐标手势时四种决策完全不变`() {
        assertEquals(SceneGuard.Decision.SKIP, SceneGuard.decide("", "com.demo.app", launchable).decision)
        assertEquals(SceneGuard.Decision.SKIP, SceneGuard.decide("com.demo.app", null, launchable).decision)
        assertEquals(SceneGuard.Decision.PROCEED, SceneGuard.decide("com.demo.app", "com.demo.app", launchable).decision)
        assertEquals(SceneGuard.Decision.RESTORE_APP, SceneGuard.decide("com.demo.app", "com.other", launchable).decision)
        assertEquals(SceneGuard.Decision.REQUIRE_MANUAL, SceneGuard.decide("com.miui.home", "com.other", launchable).decision)
        assertEquals(SceneGuard.Decision.SKIP, SceneGuard.decide("com.demo.app", "dev.nymph.auto", launchable).decision)
    }

    @Test
    fun `同包名且不含坐标手势仍为 PROCEED 不产生噪音告警`() {
        val r = SceneGuard.decide("com.demo.app", "com.demo.app", launchable, hasPositionalActions = false)
        assertEquals(SceneGuard.Decision.PROCEED, r.decision)
        assertTrue(r.message.isEmpty())
    }

    @Test
    fun `包名不同时仍按可启动性拦截不受坐标手势影响`() {
        assertEquals(
            SceneGuard.Decision.RESTORE_APP,
            SceneGuard.decide("com.demo.app", "com.other", launchable, hasPositionalActions = true).decision,
        )
        assertEquals(
            SceneGuard.Decision.REQUIRE_MANUAL,
            SceneGuard.decide("com.miui.home", "com.other", launchable, hasPositionalActions = true).decision,
        )
    }

    @Test
    fun `告警只提示不阻断_决策本身仍是放行语义`() {
        // 契约：PROCEED_WITH_WARNING 必须被 app 层当作「返回 true 继续执行」，
        // 用它表达告警而不是拦截，避免误伤已手动回到正确界面的正常回放。
        val all = SceneGuard.Decision.entries
        assertTrue(all.contains(SceneGuard.Decision.PROCEED_WITH_WARNING))
        assertEquals(5, all.size)
    }
}

package dev.nymph.auto.core.runner

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁定阶段 0/2/3.1/3.4 的纯逻辑单测：
 * 能力矩阵定档、依赖清单推导、预检拦截、漂移预算、时间抖动。
 */
class ReplayChainTest {

    // ------------------------------------------------------------ 能力矩阵（阶段 0）

    @Test
    fun `AAPM 开启判不可用且优先于其他项`() {
        val s = CapabilitySnapshot(
            a11yOnline = true,
            aapmEnabled = true,
            screenshotSupported = true,
            secureWindowDetected = true,
        )
        assertEquals(CapabilityTier.UNAVAILABLE, CapabilityMatrix.tierOf(s))
        assertFalse(CapabilityMatrix.closedLoopUsable(CapabilityTier.UNAVAILABLE))
        assertFalse(CapabilityMatrix.semanticUsable(CapabilityTier.UNAVAILABLE))
    }

    @Test
    fun `无无障碍判不可用`() {
        assertEquals(
            CapabilityTier.UNAVAILABLE,
            CapabilityMatrix.tierOf(CapabilitySnapshot(a11yOnline = false, screenshotSupported = true)),
        )
    }

    @Test
    fun `secure 或 sensitive 页判受限`() {
        assertEquals(
            CapabilityTier.RESTRICTED,
            CapabilityMatrix.tierOf(
                CapabilitySnapshot(a11yOnline = true, screenshotSupported = true, secureWindowDetected = true),
            ),
        )
        assertEquals(
            CapabilityTier.RESTRICTED,
            CapabilityMatrix.tierOf(
                CapabilitySnapshot(a11yOnline = true, screenshotSupported = true, sensitivePageDetected = true),
            ),
        )
        // 受限档不承诺闭环（裁定：不在 secure/sensitive 页承诺闭环与断言）
        assertFalse(CapabilityMatrix.closedLoopUsable(CapabilityTier.RESTRICTED))
    }

    @Test
    fun `缺截图判降级`() {
        val tier = CapabilityMatrix.tierOf(
            CapabilitySnapshot(a11yOnline = true, screenshotSupported = false),
        )
        assertEquals(CapabilityTier.DEGRADED, tier)
        assertFalse(CapabilityMatrix.closedLoopUsable(tier))
        assertTrue(CapabilityMatrix.semanticUsable(tier))
    }

    @Test
    fun `全功能档闭环与语义均可用`() {
        val tier = CapabilityMatrix.tierOf(
            CapabilitySnapshot(a11yOnline = true, screenshotSupported = true),
        )
        assertEquals(CapabilityTier.FULL, tier)
        assertTrue(CapabilityMatrix.closedLoopUsable(tier))
        assertTrue(CapabilityMatrix.semanticUsable(tier))
    }

    // ------------------------------------------------------------ 依赖清单（阶段 2）

    @Test
    fun `依赖清单收集包名与截图步骤`() {
        val script = Script(
            name = "测试",
            refWidth = 1080,
            refHeight = 2400,
            actions = listOf(
                Action(type = ActionType.OPEN_APP, packageName = "com.example.app"),
                Action(type = ActionType.CLICK_IMAGE, imageData = "AAAA"),
                Action(
                    type = ActionType.RUN_ACTIONS,
                    children = listOf(
                        Action(
                            type = ActionType.TAP,
                            condition = Condition(kind = ConditionKind.APPEAR_IMAGE),
                        ),
                        Action(type = ActionType.OPEN_APP, packageName = "com.other"),
                    ),
                ),
            ),
        )
        val deps = script.dependencies()
        assertEquals(listOf("com.example.app", "com.other"), deps.packages)
        assertTrue(deps.needsScreenshot)
        assertEquals(2, deps.imageStepCount) // CLICK_IMAGE + 嵌套 image 条件
        val line = deps.oneLine()
        assertTrue(line.contains("1080×2400"))
        assertTrue(line.contains("com.example.app"))
        assertTrue(line.contains("截图"))
    }

    @Test
    fun `无图片步骤的脚本不依赖截图`() {
        val script = Script(
            name = "纯点击",
            actions = listOf(Action(type = ActionType.TAP, x = 50.0, y = 50.0)),
        )
        assertFalse(script.dependencies().needsScreenshot)
    }

    @Test
    fun `AI 步骤计入截图依赖并单独标记`() {
        val script = Script(
            name = "AI 脚本",
            actions = listOf(
                Action(type = ActionType.CLICK_AI, aiLabel = "手机"),
                Action(
                    type = ActionType.TAP,
                    condition = Condition(kind = ConditionKind.APPEAR_AI, aiLabel = "电视"),
                ),
            ),
        )
        val deps = script.dependencies()
        assertTrue("AI 步骤要抓屏，一并计入截图依赖", deps.needsScreenshot)
        assertTrue(deps.needsAi)
        assertEquals(2, deps.aiStepCount)
        assertTrue(deps.oneLine().contains("AI找目标"))
    }

    // ------------------------------------------------------------ 预检（阶段 3.1）

    private fun envOk() = EnvironmentSnapshot(
        a11yOnline = true,
        screenshotSupported = true,
        screenWidth = 1080,
        screenHeight = 2400,
        installedPackages = setOf("com.example.app"),
    )

    @Test
    fun `环境全匹配时预检通过`() {
        val script = Script(
            name = "s",
            refWidth = 1080,
            refHeight = 2400,
            actions = listOf(
                Action(type = ActionType.OPEN_APP, packageName = "com.example.app"),
                Action(type = ActionType.TAP),
            ),
        )
        assertTrue(Preflight.check(script, envOk()).ok)
    }

    @Test
    fun `方向相反时预检拦截并说明`() {
        val script = Script(
            name = "s",
            refWidth = 1080,
            refHeight = 2400,
            actions = listOf(Action(type = ActionType.TAP)),
        )
        val report = Preflight.check(script, envOk().copy(screenWidth = 2400, screenHeight = 1080))
        assertFalse(report.ok)
        assertTrue(report.problems.any { it.contains("方向") })
        assertTrue(report.explain().contains("预检未通过"))
    }

    @Test
    fun `目标应用未安装时预检拦截`() {
        val script = Script(
            name = "s",
            actions = listOf(Action(type = ActionType.OPEN_APP, packageName = "com.missing")),
        )
        val report = Preflight.check(script, envOk())
        assertFalse(report.ok)
        assertTrue(report.problems.any { it.contains("未安装") })
    }

    @Test
    fun `缺截图能力且脚本有图片步时预检拦截`() {
        val script = Script(
            name = "s",
            actions = listOf(Action(type = ActionType.CLICK_IMAGE, imageData = "x")),
        )
        val report = Preflight.check(script, envOk().copy(screenshotSupported = false))
        assertFalse(report.ok)
        assertTrue(report.problems.any { it.contains("截图") })
    }

    // ------------------------------------------------------------ 漂移预算（阶段 3.4）

    @Test
    fun `漂移在预算内返回 null 并累计`() {
        val t = DriftTracker(maxSinglePx = 8.0, maxCumulativePx = 40.0)
        assertNull(t.record(3.0, 4.0)) // 5px
        assertEquals(5.0, t.cumulative, 0.01)
        assertNull(t.record(0.0, 2.0)) // 2px，累计 7px
        assertEquals(7.0, t.cumulative, 0.01)
    }

    @Test
    fun `单次漂移超限判布局已变`() {
        val t = DriftTracker(maxSinglePx = 8.0, maxCumulativePx = 40.0)
        val over = t.record(10.0, 0.0)
        assertNotNull(over)
        assertTrue(over!!.contains("布局已变"))
        assertTrue(over.contains("单次修正"))
    }

    @Test
    fun `累计漂移超限判布局已变`() {
        val t = DriftTracker(maxSinglePx = 8.0, maxCumulativePx = 20.0)
        assertNull(t.record(6.0, 0.0))  // 累计 6
        assertNull(t.record(6.0, 0.0))  // 累计 12
        assertNull(t.record(6.0, 0.0))  // 累计 18，仍在预算内
        val over = t.record(6.0, 0.0)   // 累计 24 > 20
        assertNotNull(over)
        assertTrue(over!!.contains("累计漂移"))
    }

    @Test
    fun `reset 清空累计`() {
        val t = DriftTracker()
        t.record(5.0, 5.0)
        t.reset()
        assertEquals(0.0, t.cumulative, 0.01)
    }

    // ------------------------------------------------------------ 时间抖动（阶段 3.2）

    @Test
    fun `sigma 为零时抖动退化为常量`() {
        val j = JitterSampler(seed = 1L)
        assertEquals(500L, j.intervalMs(500L, sigma = 0.0))
        assertEquals(100L, j.uniformDeltaMs(100L, ratio = 0.0))
    }

    @Test
    fun `对数正态采样保持中位数附近且有界`() {
        val j = JitterSampler(seed = 42L)
        var min = Long.MAX_VALUE
        var max = 0L
        repeat(2000) {
            val v = j.intervalMs(500L)
            min = minOf(min, v)
            max = maxOf(max, v)
        }
        // sigma=0.06 → 1σ 约 ±6%；2000 次采样极值约 3.5σ（±23%）。
        // 边界放到 ±30%：挡住量级错误，但不误伤对数正态的正常尾部。
        assertTrue("min=$min 偏离过大", min > 350L)
        assertTrue("max=$max 偏离过大", max < 650L)
    }

    @Test
    fun `同种子序列可复现`() {
        val a = JitterSampler(seed = 7L)
        val b = JitterSampler(seed = 7L)
        repeat(50) { assertEquals(a.intervalMs(300L), b.intervalMs(300L)) }
    }

    @Test
    fun `均匀抖动在正负比例内`() {
        val j = JitterSampler(seed = 3L)
        repeat(1000) {
            val v = j.uniformDeltaMs(100L, ratio = 0.10)
            assertTrue("v=$v 越界", v in 90L..110L)
        }
    }

    @Test
    fun `均匀抖动同种子可复现且能取到区间两端`() {
        // 修 Bug（Java 17 API 在 API 26–32 崩溃）后改用 nextDouble，
        // 需守住「确定性」与「真的均匀分布到边界」，防止退化成常量。
        val a = JitterSampler(seed = 11L)
        val b = JitterSampler(seed = 11L)
        val seen = mutableSetOf<Long>()
        repeat(4000) {
            val v = a.uniformDeltaMs(100L, ratio = 0.10)
            assertEquals(v, b.uniformDeltaMs(100L, ratio = 0.10))
            seen += v
        }
        assertTrue("未覆盖到下界 90", 90L in seen)
        assertTrue("未覆盖到上界 110", 110L in seen)
    }

    @Test
    fun `极小基准与极小比例不崩溃且结果有界`() {
        val j = JitterSampler(seed = 5L)
        repeat(100) {
            val v = j.uniformDeltaMs(1L, ratio = 0.001)
            assertTrue("v=$v 越界", v in 0L..2L)
        }
    }

    // ------------------------------------------------------------ 失败归因（阶段 4）

    @Test
    fun `四类归因均有对应动作`() {
        val reasons = FailReason.entries
        assertTrue(reasons.contains(FailReason.COORD_MISS))
        assertTrue(reasons.contains(FailReason.ASSERT_FAIL))
        assertTrue(reasons.contains(FailReason.WAIT_TIMEOUT))
        assertTrue(reasons.contains(FailReason.CHANNEL_LOST))
        assertTrue(reasons.contains(FailReason.ENV_MISMATCH))
        reasons.forEach { assertTrue(it.label.isNotEmpty() && it.action.isNotEmpty()) }
    }

    @Test
    fun `等待浮层文案含目标与秒数`() {
        val st = WaitStatus("确认按钮", 3200L, 15000L)
        val text = st.render()
        assertTrue(text.contains("确认按钮"))
        assertTrue(text.contains("3.2s"))
        assertTrue(text.contains("15s"))
    }

    @Test
    fun `无超时上限的等待文案`() {
        assertTrue(WaitStatus("X", 1000L, 0L).render().contains("无超时上限"))
    }

    // ---- v1.2.2：系统组件包不得触发「目标应用未安装」误拦截 ----

    @Test
    fun `依赖清单排除系统组件包`() {
        val script = Script(name = "s", actions = listOf(
            Action(type = ActionType.OPEN_APP, packageName = "com.android.systemui"),
            Action(type = ActionType.OPEN_APP, packageName = "com.huawei.android.launcher"),
            Action(type = ActionType.OPEN_APP, packageName = "com.example.app"),
        ))
        assertEquals(listOf("com.example.app"), script.dependencies().packages)
    }

    @Test
    fun `系统组件包名不触发未安装拦截`() {
        val script = Script(name = "桌面试录", actions = listOf(
            Action(type = ActionType.OPEN_APP, packageName = "com.android.systemui"),
            Action(type = ActionType.OPEN_APP, packageName = "com.huawei.android.launcher"),
            Action(type = ActionType.TAP),
        ))
        val report = Preflight.check(script, envOk())
        assertTrue(report.explain(), report.ok)
        assertFalse(report.problems.any { it.contains("未安装") })
    }

    @Test
    fun `系统组件与真实未安装应用混合时只拦截真实应用`() {
        val script = Script(name = "混合", actions = listOf(
            Action(type = ActionType.OPEN_APP, packageName = "com.android.systemui"),
            Action(type = ActionType.OPEN_APP, packageName = "com.missing"),
        ))
        val report = Preflight.check(script, envOk())
        assertFalse(report.ok)
        assertTrue("应拦截真实未安装应用：${report.problems}", report.problems.any { it.contains("com.missing") })
        assertFalse("不应把系统组件包列为未安装：${report.problems}",
            report.problems.any { it.contains("systemui") })
    }

    @Test
    fun `极端抖动参数不产生负延时或 Long_MAX_VALUE 卡死`() {
        // v2.9.4：exp 溢出后 toLong() 饱和成 Long.MAX_VALUE，脚本会卡在
        // delay(Long.MAX_VALUE)；span 溢出会让 base+delta 变负。正常分布不受影响。
        val j = JitterSampler(seed = 9L)
        repeat(200) {
            val a = j.intervalMs(Long.MAX_VALUE / 4, sigma = 50.0)
            assertTrue("intervalMs 溢出：$a", a in 1L until Long.MAX_VALUE)
            val b = j.intervalMs(500L, sigma = 1000.0)
            assertTrue("intervalMs 溢出：$b", b in 1L until Long.MAX_VALUE)
            val c = j.uniformDeltaMs(Long.MAX_VALUE / 2, ratio = 3.0)
            assertTrue("uniformDeltaMs 变负：$c", c >= 0L)
            val d = j.uniformDeltaMs(Long.MAX_VALUE, ratio = 1.0)
            assertTrue("uniformDeltaMs 变负：$d", d >= 0L)
        }
    }
}

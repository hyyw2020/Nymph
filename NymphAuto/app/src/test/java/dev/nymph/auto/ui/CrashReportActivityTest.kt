// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.42 补测（⑤a）：[CrashReportActivity.isCrashProcessName] 纯判定逻辑回归。
 *
 * v2.9.41 把防循环判定抽成该纯函数（KDoc 明确写「抽离以便纯 JVM 回归」），
 * 但 ADR-059 声称的 6 项 core 回归全是 ①③，⑤a 零测试覆盖。本类补齐 null / 空 /
 * 普通进程名 / 主包名 / :crash 后缀各分支，锁定「保守多判、宁多判不漏判」语义。
 *
 * 只调用 companion 纯函数（纯字符串 endsWith），不构造 Activity、不触碰 Android 运行时。
 */
class CrashReportActivityTest {

    @Test
    fun `空与null进程名都不是崩溃进程`() {
        assertFalse(CrashReportActivity.isCrashProcessName(null))
        assertFalse(CrashReportActivity.isCrashProcessName(""))
        assertFalse(CrashReportActivity.isCrashProcessName("   "))
    }

    @Test
    fun `主进程与普通进程名不是崩溃进程`() {
        assertFalse(CrashReportActivity.isCrashProcessName("dev.nymph.auto"))
        assertFalse(CrashReportActivity.isCrashProcessName("dev.nymph.auto:service"))
        assertFalse(CrashReportActivity.isCrashProcessName("dev.nymph.auto:crashx"))
        assertFalse(CrashReportActivity.isCrashProcessName("other.app:crash_helper"))
    }

    @Test
    fun `以冒号crash结尾判定为崩溃进程`() {
        assertTrue(CrashReportActivity.isCrashProcessName("dev.nymph.auto:crash"))
        assertTrue(CrashReportActivity.isCrashProcessName(":crash"))
        assertTrue(CrashReportActivity.isCrashProcessName("com.example:crash"))
    }

    @Test
    fun `多判语义下含crash子串但非后缀仍保守放行主流程`() {
        // endsWith 精确锚定 :crash 结尾，不把中间含 crash 的普通名误判，避免主进程被错杀。
        assertFalse(CrashReportActivity.isCrashProcessName("dev.nymph.auto:crash:worker"))
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.runner

/**
 * 一次回放的执行统计（core 纯 JVM，可单测）。
 *
 * ## v2.9.1 真机修复：执行总结不再全是 0
 *
 * 2026-11-10 真机调试包的日志里，脚本明明回放了 3 组 / 16 组 SWIPE 翻页，
 * 两次总结却都是「命中 0、跳过 0、失败 0」。根因是旧的统计类放在 app 层
 * `ScriptRunner` 内部，且 `hit/miss` 只在 `withRetry()`（找图/找色/找文字命中）
 * 里自增：SWIPE / TAP 这类**直接派发、无目标校验**的手势走 `dispatch()`，
 * 不计入任何口径；`executed` 字段定义了却从未自增。于是纯翻页脚本必然
 * 「全是 0」，用户以为脚本没跑，实际手势已全部打在错误界面上。
 *
 * 现在把口径集中到 core 并接线：
 *  - [executed] 由主循环对每个顶层动作组自增，反映「真的执行了多少步」；
 *  - [hit]/[miss]/[skip]/[fail] 保持原语义（查找类命中、未命中、跳过、断言失败），
 *    不把坐标手势伪装成「命中」，避免制造新的口径谎言；
 *  - [summary] 同时给出「已执行 N 步」与三类结果，纯手势脚本不再显示成全 0。
 *
 * 全部字段可写是因为 app 层 `ScriptRunner` 在既有分支里直接 `stats.hit++`；
 * 抽到 core 只为让口径与格式化可被 JVM 单测锁死，不改变任何自增时机。
 */
class RunStats {
    /** 已处理的顶层动作组数（含 GOTO / WAIT 等无手势动作）。 */
    var executed = 0

    /** 查找类动作命中目标的次数（找图/找色/找文字/找节点/AI）。 */
    var hit = 0

    /** 查找类动作重试耗尽仍未命中的次数。 */
    var miss = 0

    /** 因禁用或运行条件不满足而跳过的动作数。 */
    var skip = 0

    /** 后置断言失败（坐标命中但结果不符）次数。 */
    var fail = 0

    fun reset() {
        executed = 0; hit = 0; miss = 0; skip = 0; fail = 0
    }

    /** 结果口径片段：命中 / 跳过 / 失败（不含「已执行」，供轮次日志复用）。 */
    fun resultText(): String = "命中 $hit、跳过 $skip、失败 ${miss + fail}"

    /**
     * 完整执行总结。
     *
     * @param seconds 本轮耗时（秒）
     * @param round   当前轮次（1-based）
     * @param total   总轮数（0 = 无限循环）
     */
    fun summary(seconds: Double, round: Int = 1, total: Int = 1): String {
        val tail = when {
            total == 0 -> "（无限循环，已跑 $round 轮）"
            total > 1 -> "（共 $total 轮）"
            else -> ""
        }
        // 纯手势脚本（执行了若干步，但没有任何查找类结果）如实标注口径，
        // 不把「没有找图」显示成「什么都没做」。
        val gestureOnly = executed > 0 && hit == 0 && miss == 0 && skip == 0 && fail == 0
        val note = if (gestureOnly) {
            "；本轮为直接派发的坐标手势，无目标校验，命中/失败口径不适用"
        } else ""
        return "执行总结$tail：已执行 $executed 步，${resultText()}$note，" +
            "耗时 ${"%.1f".format(seconds)}s"
    }
}

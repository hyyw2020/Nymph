// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.model

/**
 * 脚本循环策略（v2.9.2，core 纯 JVM 单一真源）。
 *
 * 引擎早就支持 [Script.repeatCount]（`0` = 无限循环，见 [dev.nymph.auto.runner.ScriptRunner]），
 * 但界面一直没有入口：用户想让「扫荡 → 结算 → 领取 → 再扫荡」反复跑，只能去源码视图
 * 手写 `"repeatCount": 0`。这是「不能实现类似操作」的确定性缺口之一。
 *
 * 本类把「输入 → 合法 repeatCount → 展示文案」收敛到一处，供编辑器与视频导入共用，
 * 避免两处各自解析出不同语义（尤其 `0` 的「无限」含义不能写成「执行 0 次」）。
 */
object LoopPolicy {

    /** 无限循环。引擎约定 0 = 无限，由中止条件 / 手动停止 / 停止条件刹停。 */
    const val INFINITE = 0

    /** 有限循环的最小与最大次数。上限防止手滑输入 99999999 把设备跑到没电。 */
    const val MIN_FINITE = 1
    const val MAX_FINITE = 9999

    /** 把任意输入归一化为引擎可接受的 repeatCount。 */
    fun normalize(raw: Int): Int = when {
        raw == INFINITE -> INFINITE
        raw < MIN_FINITE -> MIN_FINITE
        raw > MAX_FINITE -> MAX_FINITE
        else -> raw
    }

    /**
     * 解析用户在输入框里填的内容。支持「无限 / ∞ / 0」与纯数字；
     * 非法输入返回 null，由 UI 提示而不是静默写成 1 次。
     */
    fun parse(input: String): Int? {
        val s = input.trim()
        if (s.equals("无限", ignoreCase = true) || s == "∞" || s == "0") return INFINITE
        val n = s.toIntOrNull() ?: return null
        if (n < 0) return null
        return normalize(n)
    }

    /** 界面展示文案。 */
    fun label(count: Int): String = when (val n = normalize(count)) {
        INFINITE -> "无限循环"
        MIN_FINITE -> "执行 1 次"
        else -> "重复 $n 次"
    }

    /** 供脚本描述字段使用的一句人话。 */
    fun description(count: Int): String = when (val n = normalize(count)) {
        INFINITE -> "无限循环执行，由中止条件或手动停止结束"
        MIN_FINITE -> "执行 1 次"
        else -> "重复执行 $n 轮"
    }

    fun isInfinite(count: Int): Boolean = normalize(count) == INFINITE

    /**
     * v2.9.4：回放主循环「是否已跑满轮次」的单一真源。
     *
     * 此前 app 层写的是 `repeatCount in 1..loops`：负 repeatCount（导入的旧脚本、
     * 手写 JSON 常有）永远落不进该区间，脚本被当成无限循环跑到手动停止；
     * 空脚本叠加 repeatCount=0 更会在主循环里无限空转、afterScript 不执行。
     * 这里把负值按 [normalize] 归一为 1 次，0 保持无限语义，边界由 core 单测锁死。
     */
    fun isFinished(completedRounds: Int, rawRepeatCount: Int): Boolean {
        val n = normalize(rawRepeatCount)
        return n != INFINITE && completedRounds >= n
    }
}

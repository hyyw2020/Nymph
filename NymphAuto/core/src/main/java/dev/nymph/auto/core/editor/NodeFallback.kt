// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.SceneObservation
import dev.nymph.auto.core.model.SceneRule

/**
 * 无障碍节点 fallback 的**纯 JVM 判定**（v2.9.44，core 单测可覆盖）。
 *
 * 背景：系统录屏时无障碍 `takeScreenshot` 被占用，场景驱动每帧拿不到画面 →
 * 全部 `present=false` → 永不派发动作。无障碍节点树 `rootInActiveWindow` 不受
 * 录屏影响、全程零截图。装配期若锚点命中了有判别度的语义节点，运行期可在截图
 * 失败/未命中时以「该语义节点是否出现」作为等价的「目标画面出现」判据。
 *
 * 本文件只做**数据形状与匹配判定**，不含任何 Android / AccessibilityNodeInfo
 * 依赖：app 层负责从节点树读出 [NodeSignal] 并传入，这里只回答三件事——
 *  1. 这条规则是否允许走节点 fallback（[SceneRule.nodeFallbackApplicable]）；
 *  2. 读到的节点信号是否匹配落盘的锚点语义（[matches]）；
 *  3. 匹配时如何构造供状态机消费的 [SceneObservation]（[toObservation]）。
 *
 * 判定结果喂入与 NCC 完全相同的 `SceneTrigger.planSceneFiringStateful` 状态机与
 * `resolveSceneClick` 落点换算，所有迟滞 / cooldown / 仲裁 / 错位护栏原样复用。
 */
object NodeFallback {

    /** app 层从无障碍节点树读出的最小信号（与平台类型解耦，纯数据）。 */
    data class NodeSignal(
        val text: String,
        val viewId: String,
        val className: String,
        /** 命中节点中心（当前屏幕像素坐标）。 */
        val centerX: Int,
        val centerY: Int,
    )

    /** 节点命中赋给的观测分数：语义命中是二值判据，用固定满分与 NCC 分数区分。 */
    const val NODE_HIT_SCORE: Double = 1.0

    /**
     * 这条规则是否具备节点 fallback 资格：
     * 装配期显式启用 + 三类语义至少一类非空。空语义即使启用也不判——
     * 避免把「任意节点」当成命中（那会导致满屏乱触发）。
     */
    fun applicable(rule: SceneRule): Boolean =
        rule.nodeFallbackEnabled &&
            (rule.nodeText.isNotBlank() || rule.nodeViewId.isNotBlank() || rule.nodeClass.isNotBlank())

    /**
     * 节点信号是否匹配落盘锚点语义。语义匹配是按**非空项**逐项包含：
     * - 落盘的某一项为空 → 该项不参与约束（装配期没采集到）；
     * - 三项全空 → 拒绝（无约束的匹配没有意义）。
     *
     * 包含匹配对齐 `NodeFinder.dfs` 的 `contains` 口径，避免两套语义漂移。
     */
    fun matches(signal: NodeSignal, rule: SceneRule): Boolean {
        val t = rule.nodeText
        val v = rule.nodeViewId
        val c = rule.nodeClass
        if (t.isBlank() && v.isBlank() && c.isBlank()) return false
        if (t.isNotBlank() && !signal.text.contains(t)) return false
        if (v.isNotBlank() && !signal.viewId.contains(v)) return false
        if (c.isNotBlank() && !signal.className.contains(c)) return false
        return true
    }

    /**
     * 命中时构造供状态机消费的观测：
     * `present=true`、固定满分、节点中心作为匹配中心、当前帧尺寸用于百分比换算。
     * 坐标无效（负中心或非正尺寸）时返回 `present=false`，交由上层维持 fail-closed。
     */
    fun toObservation(signal: NodeSignal, frameW: Int, frameH: Int): SceneObservation {
        if (signal.centerX < 0 || signal.centerY < 0 || frameW <= 0 || frameH <= 0) {
            return SceneObservation(present = false)
        }
        return SceneObservation(
            present = true,
            score = NODE_HIT_SCORE,
            cxPx = signal.centerX,
            cyPx = signal.centerY,
            frameW = frameW,
            frameH = frameH,
        )
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.GlobalVerifyMethod
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneClickResolution
import dev.nymph.auto.core.model.SceneFiringDecision
import dev.nymph.auto.core.model.SceneFiringState
import dev.nymph.auto.core.model.SceneObservation
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.TriggerMode
import dev.nymph.auto.core.vision.ImageFinder
import java.util.Locale
import kotlin.math.min

/**
 * **场景触发（Scene → Trigger）单一真源**（v2.9.14 引入，v2.9.15 升级，core 纯 JVM，零 Android 依赖）。
 *
 * ## 范式转变（v2.9.14 需求基线）
 *
 * 旧模型是「**步骤序列**」：录制/拾取得到一串 `Action`，运行时 `pc++` 从头到尾
 * 顺序无画面校验的顺序触发——第 1 步点 (x1,y1)、第 2 步点 (x2,y2)…… 至于「该点的画面此刻是否
 * 真的出现」并不做判定。该模型缺少画面门控，由场景驱动范式替代。
 *
 * 新模型是「**场景 → 触发规则集合**」：每一条规则 = 「当这个场景（拾取点周围自动
 * 框出的 ROI 里的稳定锚点）出现在屏幕上时，才在匹配得到的高精度坐标触发某个点击/动作」。
 * `Script.actions` 仍是存储载体（有序只是集合下标，**不再是执行顺序**）；真正决定
 * 谁被触发的是 [planSceneFiringStateful] —— 谁的场景出现，就触发谁。
 *
 * ## v2.9.15：三处「落盘锁定」语义收口（ADR-031）
 *
 * 分界线只有一条：**是否落盘锁定**。三处一旦写进存量脚本就要背迁移包袱，故发版前定死：
 *  - **A1 模板结构**：v2.9.14 把整块 ROI 当模板 = 点击改变 ROI → 判消失 → 判再现 →
 *    边沿再点火的**自激振荡**根因。本版改用 `core.vision.StableAnchor` 选 ROI 内稳定
 *    局部锚点当模板、ROI 退回搜索范围；[attachSceneRule] 装配新格式并写 `SceneRule`。
 *  - **A2 坐标语义**：[resolveSceneClick] 用匹配中心 + 相对偏移算真实落点，附带「场景疑似
 *    错位」二次校验；`SceneRule.clickOffsetX/Ypct` 缺省 null 走老绝对坐标，零回归。
 *  - **A3 触发语义**：[planSceneFiringStateful] 带状态纯函数，覆盖三种 triggerMode、迟滞、
 *    cooldown、retryTimeout（修复「触发后场景持续在场导致无新上升沿」P0 缺陷）、priority/mutexGroup
 *    仲裁、同轮最多 K 条。旧 [planSceneFiring] 改为委托（默认参数下逐字等价，406 单测零回归）。
 *
 * 另有 A4 fail-closed（见 app 层 `runSceneDriven`）、A5 可观测性（[calibrateSimilarity]
 * 阈值自校准 + 每轮决策 reasons + 拾取即自测落盘）、A6 一轮一帧（app 层 `findTemplates`）。
 *
 * ## 为什么能落在 core 且零引擎重写
 *
 * 引擎侧「场景门控」早已完整存在：`Action.condition`（`APPEAR_IMAGE` + ROI + 模板 +
 * 相似度）、`RoiExpr.resolveRegion`、`evaluateCondition` → `vision.imageExists`（本版委托
 * `findTemplate`）、`runAction.gateCond`。v2.9.14 补了拾取自动 ROI + 监视循环；v2.9.15
 * 只把「模板/坐标/触发」三处语义钉死并把观测从布尔升级为带分数/位置，识别引擎不重写。
 *
 * ## 能力边界（未验证项）
 *
 * - 场景模板来自拾取瞬间的无障碍截图裁剪（稳定局部锚点）；截图不可用（未连接 / secure
 *   窗口 / SDK<30）时 [attachSceneRule] 返回 hasGate=false，规则**不触发**（fail-closed，
 *   A4），调用方显式提示，不误报场景判定已启用、不回退至无画面校验的顺序触发。
 * - 稳定锚点选取基于**单帧空间统计**近似（core 无法做跨帧时域方差），阈值保守，
 *   需真机诊断数据（A5 拾取即自测）回来后校准；无数据前不做运行时自适应。
 * - 老脚本（`sceneDriven=false`）与 v2.9.14 过渡格式（`sceneRule=null`）执行语义逐字不变。
 */
object SceneTrigger {

    /** ROI 半边长占「屏幕短边」的比例（默认 12% → 直径约 24% 短边）。 */
    const val DEFAULT_RADIUS_FRAC = 0.12

    /** ROI 最小跨度（百分比），避免贴边拾取退化成零面积导致 NCC 恒 null。 */
    const val MIN_SPAN_PCT = 1.0

    /** 场景模板默认相似度阈值（百分制），与 `SceneRule.SIMILARITY_BASELINE` 对齐。 */
    const val DEFAULT_SIMILARITY = SceneRule.SIMILARITY_BASELINE

    /**
     * 以拾取点 [xPct]/[yPct] 为中心，自动框选一个**物理正方形** ROI。
     *
     * 物理正方形是关键：横屏 1768×800、竖屏 800×1768、折叠屏/异形屏下，若直接按
     * 「x±12%、y±12%」取矩形，横竖两个方向的实际像素尺寸差一倍以上，模板匹配会
     * 系统性错位。这里统一以「屏幕短边 × [radiusFrac]」算出物理半边长（像素），
     * 再各自换算回该轴的百分比，因此无论分辨率/方向如何，ROI 在物理上都近似正方形。
     *
     * 贴边拾取（点在四角）只做 clamp，不把 ROI 平移出屏；并保证最小 [MIN_SPAN_PCT]
     * 跨度。返回四元组百分比 + Locale.US 的 [Region.expr]（运行时由 `RoiExpr` 优先解析）。
     */
    fun autoRoi(
        xPct: Double,
        yPct: Double,
        screenW: Int,
        screenH: Int,
        radiusFrac: Double = DEFAULT_RADIUS_FRAC,
    ): Region {
        val cx = if (xPct.isFinite()) xPct.coerceIn(0.0, 100.0) else 50.0
        val cy = if (yPct.isFinite()) yPct.coerceIn(0.0, 100.0) else 50.0
        val frac = if (radiusFrac.isFinite()) radiusFrac.coerceIn(0.01, 0.49) else DEFAULT_RADIUS_FRAC
        val shortSide = min(screenW, screenH).coerceAtLeast(1)
        val halfPx = shortSide * frac
        val halfWPct = if (screenW > 0) 100.0 * halfPx / screenW else 100.0 * frac
        val halfHPct = if (screenH > 0) 100.0 * halfPx / screenH else 100.0 * frac
        val (l, r) = ensureSpan((cx - halfWPct).coerceIn(0.0, 100.0), (cx + halfWPct).coerceIn(0.0, 100.0))
        val (t, b) = ensureSpan((cy - halfHPct).coerceIn(0.0, 100.0), (cy + halfHPct).coerceIn(0.0, 100.0))
        val expr = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", l, t, r, b)
        return Region(l, t, r, b, expr)
    }

    /** 把 [roi] 的百分比四元组换算到一张 [bmpW]×[bmpH] 位图上的像素裁剪矩形 [l,t,r,b]。 */
    fun cropPx(roi: Region, bmpW: Int, bmpH: Int): IntArray {
        if (bmpW <= 0 || bmpH <= 0) return intArrayOf()
        val l = (roi.leftPct / 100.0 * bmpW).toInt().coerceIn(0, bmpW - 1)
        val r = (roi.rightPct / 100.0 * bmpW).toInt().coerceIn(l + 1, bmpW)
        val t = (roi.topPct / 100.0 * bmpH).toInt().coerceIn(0, bmpH - 1)
        val b = (roi.bottomPct / 100.0 * bmpH).toInt().coerceIn(t + 1, bmpH)
        return intArrayOf(l, t, r, b)
    }

    /**
     * **v2.9.14 兼容装配**（旧格式：整块 ROI 当模板 + 绝对坐标 + 布尔边沿 + fail-open 降级）。
     *
     * 保留旧签名与逐字语义供 406 单测 / v2.9.14 兼容路径使用；**新拾取一律走
     * [attachSceneRule]**，本函数不再被拾取链路调用。二者并存是为了让已发布的
     * v2.9.14 脚本仍能被正确解释执行。
     */
    fun attachScene(
        action: Action,
        roi: Region,
        templateB64: String,
        similarity: Int = DEFAULT_SIMILARITY,
    ): Pair<Action, Boolean> {
        val withRoi = action.copy(region = roi, waitAppear = false)
        if (templateB64.isBlank()) return withRoi to false
        val gate = Condition(
            kind = ConditionKind.APPEAR_IMAGE,
            imageData = templateB64,
            imageSimilarity = similarity.coerceIn(1, 100),
            region = roi,
            repeatCheckUntilOk = false,
            repeatCheckIntervalMs = 300L,
            repeatCheckMax = 0,
        )
        return withRoi.copy(condition = gate) to true
    }

    /**
     * **v2.9.15 / A1+A2+A3+A5 新格式装配**（三处落盘锁定项一次定死）。
     *
     * - [rule] 承载稳定锚点位置 / 搜索 ROI / 掩码 / 相对偏移 / templateSrcSize /
     *   触发配置 / 自校准阈值 / 拾取即自测结果，全部落盘到 `Action.sceneRule`。
     * - [anchorB64] 是**稳定局部锚点小图**（不是整块 ROI），挂到 `APPEAR_IMAGE` 门控；
     *   `region` = [SceneRule.searchRoi]（搜索范围）。
     * - [anchorB64] 为空（截图不可用 / 未选到稳定锚点）：仍写 [rule] 以便可观测，但
     *   **不挂图像门控**、返回 hasGate=false → 运行时该规则 fail-closed 不触发（A4），
     *   由调用方显式提示，不误报场景判定已启用。
     */
    fun attachSceneRule(
        action: Action,
        rule: SceneRule,
        anchorB64: String,
    ): Pair<Action, Boolean> {
        val withRule = action.copy(
            region = rule.searchRoi,
            waitAppear = false,
            sceneRule = rule,
        )
        if (anchorB64.isBlank()) return withRule to false
        val gate = Condition(
            kind = ConditionKind.APPEAR_IMAGE,
            imageData = anchorB64,
            imageSimilarity = rule.similarity.coerceIn(1, 100),
            region = rule.searchRoi,
            repeatCheckUntilOk = false,
            repeatCheckIntervalMs = 300L,
            repeatCheckMax = 0,
        )
        return withRule.copy(condition = gate) to true
    }

    /**
     * 该动作是否已挂「场景门控」。新旧格式通用。
     * S3：除单 `APPEAR_IMAGE` 模板条件外，扩认 `ConditionKind.COMBINE`——只要 children 里
     * 有任意有效 APPEAR_IMAGE 图片子条件即算门控。不改则编译产物的 COMBINE 载体在装配期
     * 被判「无门控」→ 走空模板 spec → fail-closed 永不触发，重演「COMBINE 空壳」缺陷。
     */
    fun isSceneTrigger(a: Action): Boolean = a.condition?.let { c ->
        when (c.kind) {
            ConditionKind.APPEAR_IMAGE -> c.imageData.isNotBlank()
            ConditionKind.COMBINE -> c.children.any {
                it.kind == ConditionKind.APPEAR_IMAGE && it.imageData.isNotBlank()
            }
            else -> false
        }
    } == true

    /** 取动作的场景门控条件（无门控返回 null，供 runSceneDriven 观测用）。 */
    fun sceneGateOf(a: Action): Condition? = a.condition?.takeIf { isSceneTrigger(a) }

    /**
     * S3：把一个门控条件展开成 m 条「图片子模板」集，供装配循环逐条建 TemplateSpec
     * （m 条进同一次 findTemplates、共享同一帧）。
     * - 单 `APPEAR_IMAGE`：返回它自身 1 条（老行为逐字不变）。
     * - `COMBINE`：返回 children 中全部有效 APPEAR_IMAGE 子条件，携带各自的 region /
     *   相似度 / filters / 模板，供运行期按 [Combine] 语义聚合 present。
     * - 无门控：空列表（调用方据此跳过，不建 spec）。
     */
    fun gateTemplatesOf(a: Action): List<Condition> {
        val c = sceneGateOf(a) ?: return emptyList()
        return when (c.kind) {
            ConditionKind.APPEAR_IMAGE -> listOf(c)
            ConditionKind.COMBINE -> c.children.filter {
                it.kind == ConditionKind.APPEAR_IMAGE && it.imageData.isNotBlank()
            }
            else -> emptyList()
        }
    }

    /** S3：该门控是否为合取单元（有 m>1 条图片子模板）。装配与观测聚合据此分流。 */
    fun isConjunctionGate(a: Action): Boolean = gateTemplatesOf(a).size > 1

    /**
     * S3：把 m 条子模板的本轮观测聚合成载体规则的**唯一一条** SceneObservation。
     *
     * - `present`：ALL = 全部在场；ANY = 任一在场；N = 在场数 >= minCount（与
     *   [ScriptRunner.evaluateCondition] 的 COMBINE 语义一致，但这里是同帧子模板聚合，
     *   不重新抓帧）。
     * - `valid`：只有全部子模板都有效才有效（任一截图失败 / 几何错配 → 整组 fail-closed，
     *   冻结状态机，杜绝缺帧误判离场）。
     * - 落点几何（cx/cy/frameW/frameH / score）：取**锁点锚**（第 0 条子模板）的值——
     *   N 点位的 Relative clickOffset 都是相对它算的，必须同源；其余子模板只贡献 present。
     * 纯函数、无 Android 依赖，供 core 单测锁定合取语义。
     */
    fun aggregateObservations(
        children: List<SceneObservation>,
        combine: Combine,
        minCount: Int,
    ): SceneObservation {
        if (children.isEmpty()) {
            return SceneObservation(present = false, valid = false)
        }
        val present = when (combine) {
            Combine.ALL -> children.all { it.present }
            Combine.ANY -> children.any { it.present }
            Combine.N -> children.count { it.present } >= minCount.coerceAtLeast(1)
        }
        val anchor = children[0]
        return SceneObservation(
            present = present,
            score = anchor.score,
            cxPx = anchor.cxPx,
            cyPx = anchor.cyPx,
            frameW = anchor.frameW,
            frameH = anchor.frameH,
            valid = children.all { it.valid },
        )
    }

    /**
     * 可触发场景数（v2.9.25，纯函数）。
     *
     * 场景驱动脚本里，没有画面模板的规则在运行时一律 fail-closed 不触发。全部规则都
     * 没有模板时，`runSceneDriven` 会立刻中止 —— 此时「开始运行」这个动作本身没有
     * 任何意义。启动前用本函数做一次静态检查，可以在起服务、建悬浮球之前就把
     * 「这次运行必然不会产生任何动作」讲清楚，而不是让用户看到界面闪一下然后什么都没发生。
     *
     * 只做计数，不改任何判定语义；返回 0 时调用方决定如何提示。
     */
    fun triggerableCount(actions: List<Action>): Int = actions.count { isSceneTrigger(it) }

    /** 该动作是否为本版新格式（带落盘 SceneRule）。null = 老 / v2.9.14 过渡格式。 */
    fun sceneRuleOf(a: Action): SceneRule? = a.sceneRule

    /**
     * **场景命中调度（v2.9.14 布尔边沿兼容入口）**。
     *
     * v2.9.15 起实现委托到 [planSceneFiringStateful]：用全默认规则（ON_APPEAR、
     * 无迟滞、无 cooldown、无 retry）且 `maxFiringPerRound=Int.MAX_VALUE`，在默认参数下
     * 与新函数逐字等价 —— 但**保留 v2.9.14「多个场景同时出现一并触发」的历史语义**
     * （K 上限是新场景驱动路径才启用的安全约束，不回溯改旧行为）。
     *
     * 这样 15 例 v2.9.14 场景单测 / v2.9.14 兼容执行路径零回归，同时调度逻辑只有
     * 一个真源（状态机），不存在两套布尔/状态语义并存。
     */
    fun planSceneFiring(observed: List<Boolean>, previousPresent: Set<Int>): Pair<List<Int>, Set<Int>> {
        val obs = observed.map { SceneObservation(present = it) }
        val prev = SceneFiringState(present = previousPresent)
        val d = planSceneFiringStateful(obs, emptyList(), prev, 0L, Int.MAX_VALUE)
        return d.fire to d.next.present
    }

    /**
     * **v2.9.15 / A3 带状态调度纯函数**（消除「步骤」概念的核心，单测全覆盖）。
     *
     * 输入形状（现在定死）：本轮 [observed] 观测（含 score/loc）、每规则 [rules] 配置、
     * 上轮 [prev] 状态、[nowMs] 单调时钟、脚本级 [maxFiringPerRound]（K 上限，默认 1）。
     * 输出 [SceneFiringDecision]：本轮触发列表 + 新状态 + 每规则决策 reasons（A5 可观测）。
     *
     * 逐规则逻辑：
     *  1. **迟滞**（施密特触发）：不在场需连续 `presentStreak` 帧命中才转「在场」；
     *     在场需连续 `absentStreak` 帧未命中才转「离场」。默认 1/1 = 不迟滞 = 旧行为。
     *  2. **triggerMode**：ON_APPEAR=上升沿（默认）、WHILE_PRESENT=在场电平、ON_DISAPPEAR=下降沿。
     *  3. **retryTimeout**（P0 修复）：ON_APPEAR 触发过且场景一直在场超过重试时长 → 再触发，
     *     修「触发后场景持续在场 → 无新上升沿 → 无法再次触发」。
     *  4. **cooldown**：触发后冷却期内抑制连点。
     *  5. **冲突仲裁**：候选按 priority 降序 / 下标升序；`mutexGroup` 非空时同组只取最高
     *     优先级一条；最后全局截断到前 K 条（K=1 最安全）。
     *
     * 纯函数：不读系统时钟（nowMs 由入参给）、不依赖任何 Android / 全局状态。
     */
    fun planSceneFiringStateful(
        observed: List<SceneObservation>,
        rules: List<SceneRule>,
        prev: SceneFiringState,
        nowMs: Long,
        maxFiringPerRound: Int = 1,
    ): SceneFiringDecision {
        val n = observed.size
        val ruleAt = { i: Int -> rules.getOrNull(i) ?: SceneRule() }

        val nowPresent = HashSet<Int>()
        val hitStreak = HashMap<Int, Int>()
        val missStreak = HashMap<Int, Int>()
        val reasons = LinkedHashMap<Int, String>()
        // 候选：(index, priority, mutexGroup)
        val candidates = ArrayList<Triple<Int, Int, String?>>()

        for (i in 0 until n) {
            val r = ruleAt(i)
            val o = observed[i]
            val wasPresent = i in prev.present
            // v2.9.46：无效帧（截图失败 / 几何错配 / 通道不可用）冻结状态机。
            // 「没拍到」≠「场景离场」。此前把失败帧当 present=false 推进 missStreak，
            // 会让 ON_DISAPPEAR 在「曾在场 + 连续 N 帧截图失败」时误派发下降沿动作。
            // 冻结 = 计数沿用上轮、在场状态沿用 wasPresent、不进入触发意图判定。
            // 节点 fallback 的在场观测 valid=true，照常推进，语义不变。
            if (!o.valid) {
                hitStreak[i] = prev.hitStreak[i] ?: 0
                missStreak[i] = prev.missStreak[i] ?: 0
                if (wasPresent) nowPresent.add(i)
                reasons[i] = "无效帧（无有效观测），冻结状态机：不推进计数 / 不产生边沿"
                continue
            }
            // ---- 1. 连续帧计数与迟滞 ----
            val hs = if (o.present) (prev.hitStreak[i] ?: 0) + 1 else 0
            val ms = if (!o.present) (prev.missStreak[i] ?: 0) + 1 else 0
            hitStreak[i] = hs
            missStreak[i] = ms
            val presentNow = if (wasPresent) {
                // 在场：需连续 absentStreak 帧未命中才离场
                ms < r.absentStreak.coerceAtLeast(1)
            } else {
                // 不在场：需连续 presentStreak 帧命中才在场
                hs >= r.presentStreak.coerceAtLeast(1)
            }
            if (presentNow) nowPresent.add(i)

            // ---- 2/3. 触发意图（边沿/电平/retry）----
            val lastFire = prev.lastFireAtMs[i]
            var wantFire = false
            var why = ""
            val retryDue = r.retryTimeoutMs > 0 && lastFire != null &&
                (nowMs - lastFire) >= r.retryTimeoutMs
            when (r.triggerMode) {
                TriggerMode.ON_APPEAR -> {
                    val edge = !wasPresent && presentNow
                    // 点击未使场景消失：一直在场 + 曾点火 + 超时 → 重试（P0）
                    val retry = retryDue && wasPresent && presentNow && i in prev.everFired
                    wantFire = edge || retry
                    why = when {
                        edge -> "上升沿（场景出现）"
                        retry -> "重试（场景未消失，超时 ${r.retryTimeoutMs}ms）"
                        presentNow -> "场景常驻，不重复触发"
                        else -> "场景未出现"
                    }
                }
                TriggerMode.WHILE_PRESENT -> {
                    wantFire = presentNow
                    why = if (presentNow) "在场电平" else "场景未出现"
                }
                TriggerMode.ON_DISAPPEAR -> {
                    wantFire = wasPresent && !presentNow
                    why = if (wantFire) "下降沿（场景消失）" else "未到消失边沿"
                }
            }

            // ---- 4. cooldown 抑制 ----
            if (wantFire && r.cooldownMs > 0 && lastFire != null) {
                val elapsed = nowMs - lastFire
                if (elapsed < r.cooldownMs) {
                    wantFire = false
                    why = "冷却抑制（剩余 ${r.cooldownMs - elapsed}ms）"
                }
            }

            if (wantFire) {
                candidates.add(Triple(i, r.priority, r.mutexGroup))
            }
            val scoreTxt = if (o.score >= 0.0) String.format(Locale.US, "%.2f", o.score) else "n/a"
            // v2.9.27：状态机的在场判定（含迟滞）与本轮观测是两个量，同名 present=
            // 会在同一行里出现相反取值。改为「状态=」，与日志的「观测=」区分。
            reasons[i] = "状态=$presentNow score=$scoreTxt → $why${if (wantFire) " ✓触发" else ""}"
        }

        // ---- 5. 冲突仲裁：priority 降序 / index 升序；mutexGroup 同组只取最高；全局 K 上限 ----
        val sorted = candidates.sortedWith(compareByDescending<Triple<Int, Int, String?>> { it.second }
            .thenBy { it.first })
        val seenGroups = HashSet<String>()
        val fire = ArrayList<Int>()
        // v2.9.35：k=0 时第一轮即 break、全部候选被丢弃，但原因串已写"✓触发"，
        // 日志与行为矛盾；且与 ExecutionParadigm 展示层的 coerceAtLeast(1) 不一致。
        val k = maxFiringPerRound.coerceAtLeast(1)
        for (c in sorted) {
            if (fire.size >= k) break
            val g = c.third
            if (g != null && !seenGroups.add(g)) {
                reasons[c.first] = reasons[c.first].orEmpty() + "（同互斥组被更高优先级占用）"
                continue
            }
            fire.add(c.first)
        }
        fire.sort()

        // ---- 新状态 ----
        val lastFireAt = HashMap(prev.lastFireAtMs)
        val everFired = HashSet(prev.everFired)
        for (i in fire) { lastFireAt[i] = nowMs; everFired.add(i) }
        val next = SceneFiringState(
            present = nowPresent,
            hitStreak = hitStreak,
            missStreak = missStreak,
            lastFireAtMs = lastFireAt,
            everFired = everFired,
        )
        return SceneFiringDecision(fire, next, reasons)
    }

    /**
     * **A5 阈值自校准**（纯函数）：取 `max(82, ceil((最佳干扰分 + 0.08) × 100))`，
     * v2.9.31 起叠加**跨帧下界上界约束**。
     *
     * 拾取即自测时在 ROI **之外**扫一遍最高误匹配分 [runnerUpScore]（0~1），
     * 阈值 = max(基线 82, 干扰分 + 0.08 余量)。把拍脑袋的 82 变成「有干扰就有依据」，
     * 成本极低且是拾取时一次性校准（非运行时自适应，不引入不可解释行为）。
     * 干扰分 <0（未测）时回退基线 82。
     *
     * ## v2.9.31 修的是什么
     *
     * 真机 RCA 确证：旧阈值完全由「同一帧里最像的干扰物」顶上去（本例 0.87），
     * 而运行期要求的是**跨帧可复现性**。模板与自身比对必然 1.00，跨帧却恒 0.65，
     * 于是 59 帧 0 触发。现在装配期连采多帧、取目标区跨帧 NCC 最低值
     * [crossFrameFloor]，阈值**不得高于该下界减安全余量**——否则下一帧在数学上
     * 就过不了。两约束取较小值，并用 [SceneEvidence.MIN_SAFE_SIMILARITY] 兜底，
     * 避免为了迁就低分模板把阈值压到会放行误匹配的程度。
     *
     * [crossFrameFloor] < 0（单帧采集 / 老脚本 / 采集失败）时逐字退回旧逻辑，零回归。
     */
    fun calibrateSimilarity(
        runnerUpScore: Double,
        baseline: Int = SceneRule.SIMILARITY_BASELINE,
        crossFrameFloor: Double = -1.0,
    ): Int {
        val spatial = if (runnerUpScore < 0.0 || !runnerUpScore.isFinite()) {
            baseline
        } else {
            // -1e-6 抵消二进制浮点误差：0.79 + 0.08 = 0.8700000000000001，
            // ×100 = 87.00000000000001 会被 ceil 误抬到 88。减微小 epsilon 后
            // ceil 稳定得到语义值 87，避免阈值在边界上不可复现地跳 1。
            val withMargin = kotlin.math.ceil(
                (runnerUpScore + SceneRule.CALIBRATION_MARGIN) * 100.0 - 1e-6,
            ).toInt()
            maxOf(baseline, withMargin)
        }
        val result = if (crossFrameFloor >= 0.0 && crossFrameFloor <= 1.0 && crossFrameFloor.isFinite()) {
            // 跨帧最低分换算成百分制后留 3 分余量，作为阈值上界。
            val cap = (crossFrameFloor * 100.0 - CROSS_FRAME_MARGIN).toInt()
            minOf(spatial, cap.coerceAtLeast(SceneEvidence.MIN_SAFE_SIMILARITY))
        } else spatial
        return result.coerceIn(1, 100)
    }

    /** 跨帧下界换算成百分制阈值时留出的余量（分）。 */
    const val CROSS_FRAME_MARGIN = 3

    /**
     * **A2 拾取时相对偏移**（纯函数）：`clickOffset = 拾取点% − 锚点中心%`。
     * 运行时 [resolveSceneClick] 用「匹配中心 + 该偏移」还原真实落点。
     */
    fun computeClickOffset(pickXPct: Double, pickYPct: Double, anchorRectPct: Region): Pair<Double, Double> {
        val acx = (anchorRectPct.leftPct + anchorRectPct.rightPct) / 2.0
        val acy = (anchorRectPct.topPct + anchorRectPct.bottomPct) / 2.0
        return (pickXPct - acx) to (pickYPct - acy)
    }

    /**
     * **A2 相对匹配坐标解析 + 二次校验**（纯函数，可单测）。
     *
     * - [rule].clickOffset 缺省 null → [SceneClickResolution.Absolute]，走老绝对坐标，零回归。
     * - 有偏移：把匹配中心 [obs] 换算成屏幕百分比，先与「记录的锚点中心」比对，偏离超过
     *   [SceneRule.locMaxDeviationPct] → [SceneClickResolution.SuspiciousMismatch]（不点、
     *   报「场景疑似错位」，相较无校验触发更安全）；否则落点 = 匹配中心 + 相对偏移，clamp 到 0..100。
     * - ON_DISAPPEAR 等无有效匹配中心（cxPx<0）时回退绝对坐标，避免误用 -1。
     *
     * ## v2.9.31：重试落点不退化（RCA 条款 5）
     *
     * 旧实现在 `loc=-` 时一律回退 [SceneClickResolution.Absolute]，而 `Action.x/y`
     * 默认 50/50 → 真机日志确证「重试 → TAP @ 50.0%,50.0%」把点击派到屏幕正中央。
     * 现在相对坐标语义下：本帧无有效中心时优先沿用 [lastValid]（上次有效落点，
     * 仍走同一套偏差二次校验）；两者都没有时，只有运行期显式传入
     * [strictNoFallback]=true 才返回 [SceneClickResolution.NoValidLocation]（不派发），
     * 默认仍回退 [SceneClickResolution.Absolute] —— 这样历史单测、ON_DISAPPEAR
     * 的「离场时本就无匹配中心」语义与老绝对坐标格式全部逐字零回归。
     * 场景驱动主链只在 ON_APPEAR / WHILE_PRESENT 上开启严格模式。
     */
    fun resolveSceneClick(
        obs: SceneObservation,
        rule: SceneRule,
        absoluteXPct: Double,
        absoluteYPct: Double,
        lastValid: SceneObservation? = null,
        strictNoFallback: Boolean = false,
    ): SceneClickResolution {
        if (!rule.usesRelativeClick) return SceneClickResolution.Absolute(absoluteXPct, absoluteYPct)
        val ox = rule.clickOffsetXPct ?: return SceneClickResolution.Absolute(absoluteXPct, absoluteYPct)
        val oy = rule.clickOffsetYPct ?: return SceneClickResolution.Absolute(absoluteXPct, absoluteYPct)

        val currentUsable = obs.cxPx >= 0 && obs.cyPx >= 0 && obs.frameW > 0 && obs.frameH > 0
        val lastUsable = lastValid != null && lastValid.cxPx >= 0 && lastValid.cyPx >= 0 &&
            lastValid.frameW > 0 && lastValid.frameH > 0
        val src = when {
            currentUsable -> obs
            lastUsable -> lastValid
            else -> null
        } ?: return if (strictNoFallback) {
            SceneClickResolution.NoValidLocation(
                "本轮无有效匹配中心且无可沿用的落点，不派发（不回退默认坐标）",
            )
        } else {
            // 默认分支：老格式 / ON_DISAPPEAR / 历史单测逐字零回归。
            SceneClickResolution.Absolute(absoluteXPct, absoluteYPct)
        }
        val via = if (currentUsable) "相对匹配坐标" else "沿用上次有效落点"
        val matchCxPct = 100.0 * src.cxPx / src.frameW
        val matchCyPct = 100.0 * src.cyPx / src.frameH
        val anchorCxPct = (rule.anchorRectPct.leftPct + rule.anchorRectPct.rightPct) / 2.0
        val anchorCyPct = (rule.anchorRectPct.topPct + rule.anchorRectPct.bottomPct) / 2.0
        val dev = kotlin.math.abs(matchCxPct - anchorCxPct) + kotlin.math.abs(matchCyPct - anchorCyPct)
        val maxPct = rule.locMaxDeviationPct.coerceAtLeast(0.0)
        if (dev > maxPct) return SceneClickResolution.SuspiciousMismatch(dev, maxPct)
        val x = (matchCxPct + ox).coerceIn(0.0, 100.0)
        val y = (matchCyPct + oy).coerceIn(0.0, 100.0)
        return SceneClickResolution.Hit(x, y, via)
    }

    /**
     * v3.0.3 S3：从动作列表按 desc 前缀圈定「画布编译点位」下标（下标升序）。
     *
     * 唯一合取载体命中后据此把 N 点位按序展开派发；N−1 点位不进 rules/specs。
     * 抽成纯函数供运行链（ScriptRunner）与门禁集成测试共用，杜绝测试另写一套展开逻辑。
     */
    fun canvasPointIndices(actions: List<Action>, descTag: String): List<Int> {
        if (descTag.isEmpty()) return emptyList()
        return actions.indices.filter { actions[it].desc.startsWith(descTag) }
    }

    /** 「场景 N / M」文案单一真源（替换所有「第 N 步」话术）。 */
    fun sceneLabel(index: Int, total: Int, desc: String = ""): String {
        val head = "场景 ${index + 1} / $total"
        return if (desc.isBlank()) head else "$head · $desc"
    }

    private fun ensureSpan(a: Double, b: Double): Pair<Double, Double> {
        if (b - a >= MIN_SPAN_PCT) return a to b
        val m = (a + b) / 2.0
        var lo = (m - MIN_SPAN_PCT / 2.0).coerceIn(0.0, 100.0)
        var hi = (lo + MIN_SPAN_PCT).coerceAtMost(100.0)
        if (hi - lo < MIN_SPAN_PCT) lo = (hi - MIN_SPAN_PCT).coerceAtLeast(0.0)
        return lo to hi
    }

    // ---------------------------------------------------------------- v3.0.3 S4 整图缩放 NCC 门

    /** 整图门判定结果（纯数据，供上层决定是否继续局部合取求值）。 */
    data class WholeFrameVerdict(
        /** 是否配置并启用了整图门。false = 老脚本/未配置 → 跳过，直接进局部门（零回归）。 */
        val enabled: Boolean,
        /** 几何入口门是否通过（宽高比 2%）。未启用时恒 true。 */
        val geometryOk: Boolean,
        /** 整图缩放 NCC 分数（0~1）。未跑时为 -1。 */
        val score: Double = -1.0,
        /** 整图门是否通过（几何门通过 且 NCC 分数达阈值）。未启用时恒 true。 */
        val passed: Boolean = true,
        /** 可读原因（可观测日志用）。 */
        val reason: String = "",
    )

    /**
     * v3.0.3 S4：整图缩放 NCC 校验门（core 纯 JVM，复用 [ImageFinder.bestScore] 的积分图 NCC）。
     *
     * 求值顺序（用户已认可）：**几何入口门 → 整图 NCC → 局部合取 → 派发**，与局部匹配共用
     * 同一帧快照（调用方保证 baseline/current 来自同一帧抓拍）。两门是**串联**而非同一条件
     * 双重拒绝：`geometryMatches` 只校验宽高比（相对容差 2%），不过则**直接不跑 NCC** 并
     * fail-closed；通过后才用整图 NCC 分数比阈值。
     *
     * 生效边界（写死，交付明示）：仅「同宽高比 + 同方向 + 同 UI」生效。跨宽高比 / 转方向
     * 由几何门 fail-closed，列为已知未覆盖、排后续阶段。
     *
     * 阈值 [similarityPct] 为**保守初值、待真机回调**——宁可不点非提高命中：收紧只会把
     * 「疑似换 UI / 跨分辨率形变」判为不通过（fail-closed），牺牲覆盖换准确性。真机命中率
     * 与 NCC 精度**未验证**。
     */
    fun verifyWholeFrame(
        baseline: ImageFinder.Image?,
        current: ImageFinder.Image,
        method: GlobalVerifyMethod,
        similarityPct: Int,
        geometryRelTol: Double = SceneEvidence.GEOMETRY_REL_TOL,
    ): WholeFrameVerdict {
        // 未启用 / 无基线：跳过整图门，直接进局部门（老脚本逐字零回归）。
        if (method == GlobalVerifyMethod.OFF || baseline == null ||
            baseline.width <= 0 || baseline.height <= 0 || baseline.argb.isEmpty()
        ) {
            return WholeFrameVerdict(
                enabled = false, geometryOk = true, passed = true, reason = "整图门未启用",
            )
        }
        // 门 1：几何入口门（宽高比 2%），不过直接 fail-closed，不跑 NCC（串联非双重拒绝）。
        val geoOk = geometryWithinTol(
            baseline.width, baseline.height, current.width, current.height, geometryRelTol,
        )
        if (!geoOk) {
            return WholeFrameVerdict(
                enabled = true, geometryOk = false, score = -1.0, passed = false,
                reason = "整图几何门 fail-closed：宽高比偏差 > ${(geometryRelTol * 100).toInt()}%（跨宽高比/转方向未覆盖）",
            )
        }
        // 门 2：整图缩放 NCC（复用 ImageFinder 多尺度积分图 NCC，抗亮度）。
        val score = ImageFinder.bestScore(
            current, baseline, 0.0, 0.0, 100.0, 100.0,
            refDpi = 0.0, curDensity = 1.0,
        )
        val thr = similarityPct.coerceIn(1, 100) / 100.0
        return WholeFrameVerdict(
            enabled = true, geometryOk = true, score = score,
            passed = score >= thr,
            reason = if (score >= thr) "整图 NCC 通过" else
                "整图 NCC 未达阈值（保守初值，宁可不点）score=${String.format(Locale.US, "%.3f", score)} thr=${String.format(Locale.US, "%.3f", thr)}",
        )
    }

    /** 宽高比相对容差校验，与 [SceneEvidence.geometryMatches] 同口径（供纯 JVM 单测直接调）。 */
    internal fun geometryWithinTol(
        srcW: Int, srcH: Int, frameW: Int, frameH: Int, relTol: Double,
    ): Boolean {
        if (srcW <= 0 || srcH <= 0 || frameW <= 0 || frameH <= 0) return true
        val sr = srcW.toDouble() / srcH.toDouble()
        val fr = frameW.toDouble() / frameH.toDouble()
        return kotlin.math.abs(sr - fr) / sr <= relTol
    }
}

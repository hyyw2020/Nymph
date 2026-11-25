// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Region
import kotlinx.serialization.Serializable

/** 画布数据 → 判定输入的值对象（避免 UI 层重复拼装 Feature 列表；纯数据、无状态）。 */
data class CanvasDecisionInput(
    val bind: SceneBoard.OrientationBinding,
    val currentRotation: Int,
    val features: List<SceneBoard.Feature>,
    /** 本帧各特征原始观测（已按 features 顺序对齐，缺项为无效观测）。 */
    val observations: List<SceneBoard.Observation>,
    val combine: Combine,
    val minCount: Int,
    val clickZone: Region,
    val desiredX: Double,
    val desiredY: Double,
)

/**
 * **场景画板（Scene Board）—— 场景编辑器 2.0 的语义真源**（core 纯 JVM，零 Android 依赖）。
 *
 * ## 为什么要重新设计（对应用户 4 条批评）
 *
 * 旧模型把「落点坐标」「触发特征」「限制区域」都塞进一个 `Action` 的 `x/y/region`，
 * UI 又用同一句话「框选取样范围」呈现三者。于是用户分不清：
 *  - 蓝框到底是「点哪里」还是「看哪里」；
 *  - 横屏拾取的点为什么竖屏还在点（旧 `OrientationTransform` 把方向当"自适应旋转继续跑"，
 *    而用户要的是"横屏场景在竖屏根本不该触发"）；
 *  - 多张图同时出现才触发没有入口（引擎只认一条规则一个模板，跨规则是 OR）；
 *  - 相似度是裸滑块 50–100，用户无从判断该填多少。
 *
 * 本对象把三件事**彻底分开命名、分开判定、分开呈现**：
 *
 * ```
 * SceneBoard（一张底图 = 拾取瞬间原画质截图）
 *  ├─ clickZone  : Region       落点限制区——点击落点只能在这里，越界不改点/改判失败
 *  ├─ features   : List[Feature]触发特征框——每个框有独立模板+阈值，N 个按 combine 组合
 *  └─ bindMode   : OrientationBinding  方向绑定——横屏画板在竖屏直接失活（fail-closed）
 * ```
 *
 * 一切判定都是纯函数，输出 [BoardVerdict] 带可读原因，UI 直接渲染原因，
 * 杜绝「界面显示 PASS、运行时另一套判据」的偏斜。
 */
class SceneBoard private constructor() {

    /** 方向绑定模式。默认 [ANY] 保证老脚本零回归；新画板由 UI 按拾取方向选定。 */
    @Serializable
    enum class OrientationBinding {
        /** 不绑定：任意方向都判（老行为）。 */
        ANY,
        /** 仅竖屏（rotation 0/2）判；横屏时整个画板失活。 */
        PORTRAIT,
        /** 仅横屏（rotation 1/3）判；竖屏时整个画板失活。 */
        LANDSCAPE,
    }

    /** 一个触发特征框：画板底图上的百分比矩形 + 模板 + 独立阈值 + 用户可读名。 */
    data class Feature(
        val id: String,
        val label: String,
        val region: Region,
        val similarity: Int,
        val templateB64: String = "",
    )

    /** 一帧里对某个特征框的原始观测（尚未过阈值）。 */
    data class Observation(
        val featureId: String,
        val score: Double,        // 0..1 的 NCC 分；<0 表示无效观测（截图失败/几何错配）
        val centerXPct: Double = -1.0,
        val centerYPct: Double = -1.0,
    ) {
        val valid: Boolean get() = score >= 0.0 && score.isFinite()
    }

    /** 整块画板在一帧上的判定结论。 */
    data class BoardVerdict(
        val active: Boolean,          // 方向是否允许该画板参与本帧
        val triggered: Boolean,       // 是否最终触发
        val firedFeatureIds: List<String>,
        val clickXPct: Double,
        val clickYPct: Double,
        val reason: String,
    )

    /** 阈值判定带：把裸数字变成「目标分 vs 干扰分」的可解释区间。 */
    data class ThresholdBand(
        val suggested: Int,       // 建议阈值（百分制）
        val targetPct: Int,       // 目标命中分
        val runnerUpPct: Int,     // 最强干扰分
        val low: Int,             // 滑杆可视下限
        val high: Int,            // 滑杆可视上限
        val separable: Boolean,   // 目标与干扰是否可分
        val advice: String,       // 给用户的中文建议（直接上 UI）
    )

    companion object {
        const val DEFAULT_SIMILARITY = 85
        const val MIN_SIMILARITY = 50
        const val MAX_SIMILARITY = 100
        /** 判定带安全余量：建议阈值离干扰分/目标分各留的安全间距（百分点）。 */
        const val BAND_MARGIN_PCT = 3
        /** 目标与干扰分差小于此值判"不可分"，提示重框而不是让用户猜阈值。 */
        const val MIN_SEPARATION_PCT = 6

        /** Surface.ROTATION_* → 竖/横。0/2 竖，1/3 横。 */
        fun isPortrait(rotation: Int): Boolean {
            val r = ((rotation % 4) + 4) % 4
            return r == 0 || r == 2
        }

        /**
         * 方向门控（替代旧"自适应旋转继续跑"）：画板绑定的方向若与当前方向不符，
         * 整个画板本帧失活——这正是"横屏拾取的点不该在竖屏误点"的正解。
         * [ANY] 恒 true（老脚本零回归）。
         */
        fun orientationAllows(bind: OrientationBinding, currentRotation: Int): Boolean =
            when (bind) {
                OrientationBinding.ANY -> true
                OrientationBinding.PORTRAIT -> isPortrait(currentRotation)
                OrientationBinding.LANDSCAPE -> !isPortrait(currentRotation)
            }

        /** 方向绑定的中文名（UI 直接渲染）。 */
        fun bindingLabel(bind: OrientationBinding): String = when (bind) {
            OrientationBinding.ANY -> "任意方向"
            OrientationBinding.PORTRAIT -> "仅竖屏"
            OrientationBinding.LANDSCAPE -> "仅横屏"
        }

        /**
         * 多特征组合判定：N 个特征框按 [combine] 聚合。
         *  - ALL：全部特征都命中才触发（用户要的"特征1,2,3,4同时出现才有效"）；
         *  - ANY：任一命中即触发（旧跨规则 OR 语义）；
         *  - N：至少 [minCount] 个命中。
         *
         * [allFeatureIds] 是画板上全部特征 id，[passedIds] 是本帧已过阈值的特征 id。
         * ALL 必须检查"全部特征都在 passed 里"，不能只看 passed 自身——否则只传
         * 一个命中项时 ALL 会误判为真。无效观测已被 [applyThresholds] 剔除，此处
         * 只处理 id 集合，fail-closed。
         */
        fun combineHits(
            allFeatureIds: List<String>,
            passedIds: List<String>,
            combine: Combine,
            minCount: Int = 1,
        ): List<String> = when (combine) {
            Combine.ALL ->
                if (allFeatureIds.isNotEmpty() && allFeatureIds.all { it in passedIds }) passedIds
                else emptyList()
            Combine.ANY -> passedIds
            Combine.N ->
                if (passedIds.size >= minCount.coerceAtLeast(1)) passedIds else emptyList()
        }

        /**
         * 把原始观测按每个特征框的相似度阈值折算为"已过阈"观测。
         * score(0..1) * 100 >= similarity(百分制) 即命中。
         */
        fun applyThresholds(
            observations: List<Observation>,
            features: List<Feature>,
        ): List<Observation> {
            val thrById = features.associate { it.id to it.similarity }
            return observations.filter { o ->
                val thr = thrById[o.featureId] ?: DEFAULT_SIMILARITY
                o.valid && (o.score * 100.0) >= thr.toDouble()
            }
        }

        /** 组合条件的中文名。 */
        fun combineLabel(combine: Combine): String = when (combine) {
            Combine.ALL -> "全部同时出现"
            Combine.ANY -> "任一出现"
            Combine.N -> "至少 N 个出现"
        }

        /**
         * 落点约束：点击落点必须落在 [zone] 内。越界时钳到最近的边界点，
         * 并返回是否被钳制——UI 据此提示"落点已约束到限制区"，不静默改点。
         * 空 zone（全屏 0..100）等于不约束。
         */
        fun clampClick(
            xPct: Double,
            yPct: Double,
            zone: Region,
        ): Pair<Double, Double> {
            val l = zone.leftPct.coerceIn(0.0, 100.0)
            val t = zone.topPct.coerceIn(0.0, 100.0)
            val r = zone.rightPct.coerceIn(l, 100.0)
            val b = zone.bottomPct.coerceIn(t, 100.0)
            return xPct.coerceIn(l, r) to yPct.coerceIn(t, b)
        }

        /** 落点是否在限制区内（用于触发前校验，越界可判失败）。 */
        fun inClickZone(xPct: Double, yPct: Double, zone: Region): Boolean =
            xPct >= zone.leftPct && xPct <= zone.rightPct &&
                yPct >= zone.topPct && yPct <= zone.bottomPct

        /**
         * 阈值判定带（替代裸滑块的核心）：用目标命中分 [targetScore] 与
         * 最强干扰分 [runnerUpScore] 算出可解释的建议阈值与文案。
         *
         * 建议值 = 干扰分向上留 BAND_MARGIN_PCT，且不超过目标分减 BAND_MARGIN_PCT，
         * 收敛到 [MIN_SIMILARITY, MAX_SIMILARITY]。若目标与干扰间隔 <
         * [MIN_SEPARATION_PCT] 判不可分，直接提示"重框特征/换更独特区域"，
         * 而不是让用户在一个数学上无解的阈值上反复试。
         */
        fun thresholdBand(
            targetScore: Double,
            runnerUpScore: Double,
        ): ThresholdBand {
            val target = (targetScore * 100.0).toInt().coerceIn(0, 100)
            val runner = if (runnerUpScore < 0 || !runnerUpScore.isFinite()) -1
            else (runnerUpScore * 100.0).toInt().coerceIn(0, 100)
            val separation = target - runner
            if (runner < 0) {
                return ThresholdBand(
                    suggested = DEFAULT_SIMILARITY, targetPct = target, runnerUpPct = runner,
                    low = MIN_SIMILARITY, high = MAX_SIMILARITY, separable = true,
                    advice = "尚无干扰样本，沿用建议值 $DEFAULT_SIMILARITY%；运行一次后可据干扰分再校准。",
                )
            }
            val separable = separation >= MIN_SEPARATION_PCT
            val fromRunner = runner + BAND_MARGIN_PCT
            val fromTarget = target - BAND_MARGIN_PCT
            val suggested = fromRunner.coerceAtMost(fromTarget)
                .coerceIn(MIN_SIMILARITY, MAX_SIMILARITY)
            val advice = if (separable) {
                "目标 $target% / 最强干扰 $runner%（相差 $separation%）。建议阈值 $suggested%：" +
                    "高于干扰、低于目标，能稳定区分。"
            } else {
                "目标 $target% 与干扰 $runner% 只差 $separation%，没有可靠阈值。请重新框一个更独特的特征区域。"
            }
            return ThresholdBand(
                suggested = suggested,
                targetPct = target,
                runnerUpPct = runner,
                low = MIN_SIMILARITY,
                high = MAX_SIMILARITY,
                separable = separable,
                advice = advice,
            )
        }

        /**
         * 从持久化画布 [SceneCanvas] 直接构造判定输入。
         * 每个 [CanvasFeature] 的模板 key 只用于取图，不进判定；判定只看 region + 阈值。
         * [observationsByFeatureId] 是本帧各特征 id 的原始 NCC 分，缺项按无效观测(score<0)处理——fail-closed。
         */
        fun fromCanvas(
            canvas: SceneCanvas,
            currentRotation: Int,
            observationsByFeatureId: Map<String, Double>,
        ): CanvasDecisionInput {
            val feats = canvas.features.map { cf ->
                Feature(
                    id = cf.id,
                    label = cf.label,
                    region = cf.region,
                    similarity = cf.similarity,
                    templateB64 = cf.templateKey, // 这里承载 asset key，判定不读
                )
            }
            // 按 features 顺序对齐观测，缺项 = 无效观测(-1)，fail-closed。
            val obs = feats.map { f -> Observation(f.id, observationsByFeatureId[f.id] ?: -1.0) }
            return CanvasDecisionInput(
                bind = canvas.bind,
                currentRotation = currentRotation,
                features = feats,
                observations = obs,
                combine = canvas.combine,
                minCount = canvas.minCount,
                clickZone = canvas.clickZone,
                desiredX = canvas.pointX,
                desiredY = canvas.pointY,
            )
        }

        /** 对一个 [CanvasDecisionInput] 直接判定（观测随输入携带，纯函数无隐藏状态）。 */
        fun evaluate(input: CanvasDecisionInput): BoardVerdict =
            evaluate(
                bind = input.bind,
                currentRotation = input.currentRotation,
                features = input.features,
                rawObservations = input.observations,
                combine = input.combine,
                minCount = input.minCount,
                desiredX = input.desiredX,
                desiredY = input.desiredY,
                clickZone = input.clickZone,
            )

        /**
         * 整板判定（把方向 + AND + 落点约束一次算清），返回可直接渲染的 [BoardVerdict]。
         * [rawObservations] 是各特征框的本帧原始分；先过阈值，再按 [combine] 组合。
         */
        fun evaluate(
            bind: OrientationBinding,
            currentRotation: Int,
            features: List<Feature>,
            rawObservations: List<Observation>,
            combine: Combine,
            minCount: Int,
            desiredX: Double,
            desiredY: Double,
            clickZone: Region,
        ): BoardVerdict {
            if (!orientationAllows(bind, currentRotation)) {
                val orient = if (isPortrait(currentRotation)) "竖屏" else "横屏"
                return BoardVerdict(
                    active = false, triggered = false, firedFeatureIds = emptyList(),
                    clickXPct = desiredX, clickYPct = desiredY,
                    reason = "方向不符：本场景绑定${bindingLabel(bind)}，当前为$orient，本次不触发。",
                )
            }
            val passed = applyThresholds(rawObservations, features)
            val fired = combineHits(features.map { it.id }, passed.map { it.featureId }, combine, minCount)
            if (fired.isEmpty()) {
                val need = when (combine) {
                    Combine.ALL -> "全部 ${features.size} 个特征同时出现"
                    Combine.ANY -> "任一特征出现"
                    Combine.N -> "至少 $minCount 个特征出现"
                }
                return BoardVerdict(
                    active = true, triggered = false, firedFeatureIds = emptyList(),
                    clickXPct = desiredX, clickYPct = desiredY,
                    reason = "特征未齐：需要$need，本帧命中 ${passed.size}/${features.size}。",
                )
            }
            val (cx, cy) = clampClick(desiredX, desiredY, clickZone)
            val clamped = cx != desiredX || cy != desiredY
            return BoardVerdict(
                active = true, triggered = true, firedFeatureIds = fired,
                clickXPct = cx, clickYPct = cy,
                reason = "触发：命中 ${fired.size}/${features.size} 个特征" +
                    (if (clamped) "；落点已约束到限制区（$cx%, $cy%）" else "；落点在限制区内"),
            )
        }
    }
}

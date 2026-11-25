// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.design

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.Script
import kotlinx.serialization.Serializable

/**
 * 场景画布（Scene Canvas）——「截取原画质截图当画布 → 在画布上框坐标与特征」操作逻辑的
 * **可持久化载体**（core 纯 JVM，零 Android 依赖）。
 *
 * ## 为什么需要它（对应用户"强调后续维护"）
 *
 * 旧 `FeatureCaptureDialog` 是「实时屏幕上框一小块 → 裁成特征小图」，整帧底图不留存：
 * 脚本保存后，用户再打开只看到一个孤立的小图，**回不到当时的完整画面**去改坐标限制区、
 * 增删特征框。本模型把「一张底图 + 画在其上的全部框」整体存进脚本：
 *
 * ```
 * SceneCanvas（一次拾取会话 = 一张原画质底图）
 *  ├─ backgroundKey : String   底图在 Script.assets 里的 key（整帧原画质 PNG base64）
 *  ├─ widthPx/heightPx         底图像素尺寸（把百分比框换回像素用）
 *  ├─ rotation                 抓帧时屏幕方向（0/1/2/3）
 *  ├─ bind                     方向绑定（横屏画布在竖屏失活）
 *  ├─ combine/minCount         多特征组合方式（N 个同时出现才触发）
 *  ├─ clickZone : Region       落点限制区——只约束"点哪里"，与特征搜索窗解耦
 *  ├─ pointX/pointY            点击落点（百分比）
 *  └─ features : List[Feature] 触发特征框（各自 region/阈值/模板 key/标签）
 * ```
 *
 * 保存后重开编辑器：按 [backgroundKey] 从 assets 取回底图铺成画布，按上述字段还原所有框，
 * 用户可在同一张图上继续增删框、改阈值、挪落点——这就是"后续维护"。
 *
 * ## 存量脚本零回归
 *
 * 带默认值 + kotlinx `ignoreUnknownKeys`/`encodeDefaults`：老脚本无 `canvas` 字段时为 null，
 * 运行链完全不读它，行为与 v3.0.0 逐字一致；`schemaVersion` 保持 1，无迁移链。
 */
@Serializable
data class SceneCanvas(
    /** 底图在 Script.assets 中的 key，形如 `canvas:<16hex>`；空 = 未抓帧。 */
    val backgroundKey: String = "",
    /** 抓帧时的屏幕方向（Surface.ROTATION_*，0/1/2/3）。 */
    val rotation: Int = 0,
    /** 方向绑定：横屏画布在竖屏失活（fail-closed）。 */
    val bind: SceneBoard.OrientationBinding = SceneBoard.OrientationBinding.ANY,
    /** 多特征组合方式。默认 ALL——N 个特征同时出现才触发（用户明确要的语义）。 */
    val combine: Combine = Combine.ALL,
    /** combine=N 时至少命中个数。 */
    val minCount: Int = 1,
    /** 落点限制区：只约束点击落点，不再兼作特征搜索窗。空区（全屏）等于不约束。 */
    val clickZone: Region = Region(),
    /** 点击落点（百分比 0–100）。v3.0.4 起仅作老画布脚本的单点兼容，新画布用 [points]。 */
    val pointX: Double = 50.0,
    val pointY: Double = 50.0,
    /** 底图像素尺寸，编译期写入 SceneRule.templateSrc 供跨分辨率几何校验/残差核对。 */
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    /**
     * 画板上的特征框（可为 0 个）。S3 起 [features] 的**全部有效项**编成一条
     * `Condition(COMBINE, combine, children=m×APPEAR_IMAGE)` 合取门控，挂在编译产物的
     * 第 1 条点位 Action 上；第 1 个有效特征兼作「锁点锚」提供落点几何，其余只作合取校验。
     */
    val features: List<CanvasFeature> = emptyList(),
    /**
     * N 个编号点位：每个点位相对锁点锚、各自绑手势与间隔，编译期产出一条顶层 Action
     * （各带 Relative clickOffset）。只有第 1 条挂 COMBINE 合取门控作关联载体，其余 N−1 条
     * 不挂 condition、运行链不进 specs/rules，命中后按序展开共享载体同帧观测。
     * 空 = 老画布脚本，由 [compileToActions] 降级为单 TAP（用 pointX/pointY）。
     */
    val points: List<CanvasPoint> = emptyList(),
) {
    /** 点位手势类型（编译映射到 ActionType，不新增 Action 字段）。 */
    @Serializable
    enum class PointGesture {
        TAP,        // 轻击 → ActionType.TAP
        MULTI_TAP,  // 双击 → ActionType.MULTI_TAP(times=2)
        LONG_PRESS, // 长按 → ActionType.LONG_PRESS
        SWIPE,      // 滑动 → ActionType.SWIPE(x,y → x2,y2)
    }

    /** 一个编号点位：相对锚的落点 + 手势 + 间隔。 */
    @Serializable
    data class CanvasPoint(
        val id: String,
        val label: String = "",
        /** 点位百分比坐标（画布底图 0–100）。 */
        val x: Double = 50.0,
        val y: Double = 50.0,
        /** 滑动终点（仅 SWIPE 用）。 */
        val x2: Double = 0.0,
        val y2: Double = 0.0,
        val gesture: PointGesture = PointGesture.TAP,
        /** 动作前等待（毫秒），作点位间隔。 */
        val waitMs: Long = 0,
        val holdMs: Long = 100,
        val durationMs: Long = 300,
        /**
         * true = 显式用绝对 PCT 兜底（不写 clickOffset，走 resolveSceneClick 的 Absolute 分支）；
         * false（默认）= 写 Relative clickOffset，以锚匹配中心 + 偏移落点。绝不静默退化。
         */
        val useAbsolute: Boolean = false,
    )
    /** 画布上的一个触发特征框。模板存 assets，这里只存 key + 几何 + 阈值。 */
    @Serializable
    data class CanvasFeature(
        val id: String,
        /** 用户可读名（UI 直接渲染，如"特征1·按钮"）。 */
        val label: String = "",
        /** 框在底图上的归一化矩形（百分比）。 */
        val region: Region = Region(),
        /** 该框独立的相似度阈值（百分制 0–100）。 */
        val similarity: Int = SceneBoard.DEFAULT_SIMILARITY,
        /** 该框原画质模板在 Script.assets 中的 key，形如 `feat:<16hex>`。 */
        val templateKey: String = "",
    )

    /** 该画布是否已有可用底图。 */
    fun hasBackground(): Boolean = backgroundKey.isNotBlank()

    /** 该画布是否配置了至少一个特征框。 */
    fun hasFeatures(): Boolean = features.isNotEmpty()

    /** 用户可读的框数量摘要（UI 直接渲染）。 */
    fun summary(): String =
        "${features.size} 个特征 · ${SceneBoard.combineLabel(combine)} · ${SceneBoard.bindingLabel(bind)}"

    /**
     * S3：把画布编译为**顶层 Action 列表**（路径乙——画布只做可视化编辑视图，
     * 保存时编译进现有 Action/sceneRule，运行链只读 Action、绝不读 canvas）。
     *
     * - 合取门控：[features] 的全部有效项编成一条 `Condition(COMBINE, combine, children=m×
     *   APPEAR_IMAGE)`，只挂在编译产物的第 1 条点位 Action 上；第 1 个有效特征兼作锁点锚。
     * - N 点位：[points] 每条产出一个顶层 Action，各带 `clickOffset` = 点位百分比 −
     *   锁点锚中心百分比（Relative），`useAbsolute=true` 时才退回绝对；N−1 条不挂门控。
     * - 按序 + 间隔：返回列表即编号顺序，引擎在载体命中后按序展开派发；每个 Action 的
     *   `waitMs` 作点位间隔。
     *
     * @return 编译后的动作列表；无任何有效特征模板（未框特征 / 模板未裁出）返回空列表。
     */
    fun compileToActions(assets: Map<String, String>): List<Action> {
        // S3：m 个有效特征（模板非空的才参与合取）。第一个有效特征作唯一「锁点锚」：
        // 落点偏移、几何基准、首条 spec 的落点都相对它的区域中心（与 resolveSceneClick 同口径）。
        // 其余特征只作合取校验，不提供落点。
        val validFeatures = features.mapNotNull { f ->
            val b64 = assets[f.templateKey]?.takeIf { it.isNotBlank() }
            if (b64 == null) null else f to b64
        }
        if (validFeatures.isEmpty()) return emptyList()
        val anchor = validFeatures.first().first
        val anchorB64 = validFeatures.first().second
        // 锚中心（百分比）——与 resolveSceneClick 读取 anchorRectPct 中心同口径。
        val anchorCx = (anchor.region.leftPct + anchor.region.rightPct) / 2.0
        val anchorCy = (anchor.region.topPct + anchor.region.bottomPct) / 2.0

        // S3 门控载体：Condition(COMBINE, ALL/N, children=m×APPEAR_IMAGE)。
        // 每个 child 来自一个 feature：自己的模板 / 阈值 / 搜索区。装配期逐条展开进
        // 同一次 findTemplates（同帧）；合取语义由 children 的 present 聚合得到。
        // 合取标记只落 Condition，绝不挪用 S4 的 SceneRule.globalFingerprint/globalMethod。
        val gateCondition = Condition(
            kind = ConditionKind.COMBINE,
            combine = combine,
            runCountLimit = minCount,
            children = validFeatures.map { (f, b64) ->
                Condition(
                    kind = ConditionKind.APPEAR_IMAGE,
                    imageData = b64,
                    imageSimilarity = f.similarity.coerceIn(1, 100),
                    region = f.region,
                    repeatCheckUntilOk = false,
                )
            },
        )
        fun anchorRule(ox: Double?, oy: Double?) = SceneRule(
            searchRoi = anchor.region,
            anchorRectPct = anchor.region,
            // 相对锚定核心：点位百分比 − 锚中心百分比；useAbsolute 时为 null → 走 Absolute。
            clickOffsetXPct = ox,
            clickOffsetYPct = oy,
            templateSrcW = widthPx,
            templateSrcH = heightPx,
            pickRotation = rotation,
            similarity = anchor.similarity.coerceIn(1, 100),
            // 收紧落点偏差护栏：画布是原画质底图，锚中心匹配须精确；默认 15pp 过松，
            // 编译期写更紧值，超阈即 SuspiciousMismatch → failLoudSkip（见交付轻项 4）。
            locMaxDeviationPct = MAX_DEV_PCT,
            // 默认 ON_APPEAR / cooldown=0 / mutexGroup=null ——不设互斥组，
            // 避免 planSceneFiringStateful 的同组压制把多点位合成一条。
        )

        // S3 编译形状（ADR-074 确认 3）：N 条顶层 Action 里只有第 1 条挂 COMBINE 合取门控
        // （它兼挂自身 sceneRule.clickOffset，作「关联载体」）；其余 N−1 条只带 Relative
        // clickOffset、不挂 condition —— 运行链据此把它们排除在 specs/rules 之外，
        // 合取命中后按 desc tag + 下标升序展开为 N 点位按序派发，共享载体同帧 observation。
        if (points.isNotEmpty()) {
            return points.mapIndexed { idx, p ->
                val type = when (p.gesture) {
                    PointGesture.TAP -> ActionType.TAP
                    PointGesture.MULTI_TAP -> ActionType.MULTI_TAP
                    PointGesture.LONG_PRESS -> ActionType.LONG_PRESS
                    PointGesture.SWIPE -> ActionType.SWIPE
                }
                val (ox, oy) = if (p.useAbsolute) null to null
                else (p.x - anchorCx) to (p.y - anchorCy)
                Action(
                    type = type,
                    desc = CANVAS_ACTION_TAG + (p.label.ifBlank { "点位${idx + 1}" }),
                    unit = CoordUnit.PCT,
                    // 绝对 PCT 仅作显式兜底；Relative 下 x/y 仍写入供 no-loc 分支参考，
                    // 但引擎在 Relative 分支用「匹配中心 + clickOffset」覆盖它。
                    x = p.x,
                    y = p.y,
                    x2 = p.x2,
                    y2 = p.y2,
                    waitMs = p.waitMs.coerceAtLeast(0),
                    holdMs = p.holdMs,
                    durationMs = p.durationMs,
                    times = if (p.gesture == PointGesture.MULTI_TAP) 2 else 1,
                    imageData = anchorB64,
                    imageSimilarity = anchor.similarity,
                    region = anchor.region,
                    // 只有载体（idx=0）挂合取门控；N−1 条不挂，运行链不进 specs/rules。
                    condition = if (idx == 0) gateCondition else null,
                    sceneRule = anchorRule(ox, oy),
                )
            }
        }

        // 老画布脚本兼容：无 points 时降级为单 TAP（载体即唯一命中点，挂合取门控）。
        val ox = pointX - anchorCx
        val oy = pointY - anchorCy
        return listOf(
            Action(
                type = ActionType.TAP,
                desc = CANVAS_ACTION_TAG + "画布落点",
                unit = CoordUnit.PCT,
                x = pointX,
                y = pointY,
                imageData = anchorB64,
                imageSimilarity = anchor.similarity,
                region = anchor.region,
                condition = gateCondition,
                sceneRule = anchorRule(ox, oy),
            ),
        )
    }

    companion object {
        /**
         * 画布编译产出的 Action.desc 前缀标记：EditorScreen 据此在再次编辑时替换旧产物，
         * 而不碰用户手工添加的动作。稳定常量，不新增 Action 字段。
         */
        const val CANVAS_ACTION_TAG = "画布·"

        /** 画布底图 asset key 前缀（与特征模板、其它资源区分）。 */
        const val BG_PREFIX = "canvas:"
        /** 特征模板 asset key 前缀。 */
        const val FEAT_PREFIX = "feat:"

        /** 画布编译产物的落点偏差护栏（曼哈顿 pp）：比引擎默认 15 更紧。 */
        const val MAX_DEV_PCT = 8.0

        /**
         * 把画布编译进脚本（EditorScreen 保存的唯一入口，测试共用同一函数）。
         * 用 desc 前缀替换旧产物、不碰手工动作。S3：合取后画布只有 1 条门控规则会触发
         * （N−1 点位不进 rules、由运行链按序展开），故 K 保持 1——不再设为点位数，
         * 避免混合脚本里其他场景规则被同轮多放。
         */
        fun mergeInto(script: Script, canvas: SceneCanvas, assets: Map<String, String>): Script {
            val compiled = canvas.compileToActions(assets)
            val kept = script.actions.filterNot { it.desc.startsWith(CANVAS_ACTION_TAG) }
            // v3.0.3 S4：画布有原画质底图 → 把它登记为脚本级整图校验基线，启用 NCC 整图门。
            // 生效边界仅「同宽高比 + 同方向 + 同 UI」；阈值保守初值、待真机回调、宁可不点。
            // 无底图（老画布 / 未抓帧）→ 保持 OFF，运行链跳过整图门，逐字零回归。
            val hasBg = canvas.hasBackground() && assets.containsKey(canvas.backgroundKey)
            val verifyMethod = if (hasBg) dev.nymph.auto.core.model.GlobalVerifyMethod.NCC
                               else dev.nymph.auto.core.model.GlobalVerifyMethod.OFF
            val fingerprintKey = if (hasBg) canvas.backgroundKey else ""
            return script.copy(
                canvas = canvas,
                assets = assets,
                actions = kept + compiled,
                sceneDriven = compiled.isNotEmpty(),
                sceneMaxFiringPerRound = 1.coerceAtLeast(1),
                globalFingerprint = fingerprintKey,
                globalMethod = verifyMethod,
                globalVerifySimilarity = Script.DEFAULT_GLOBAL_VERIFY_SIMILARITY,
            )
        }
    }
}

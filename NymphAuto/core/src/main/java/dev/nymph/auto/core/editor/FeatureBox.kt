// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.editor

import dev.nymph.auto.core.model.Region

/**
 * 多特征框（v2.9.49）。
 *
 * 一次「框选搜索区域 / 模板范围」拾取会话可产出 N 个独立特征框，每个框：
 *  - [region]：归一化四角矩形（百分比 + 可选表达式），相对屏幕/视频分辨率；
 *  - [similarity]：该框独立的匹配相似度下限（0–100），对应目标动作/条件的
 *    `imageSimilarity`。各框互不影响，可按特征差异分别控制严格度。
 *
 * 运行期语义（沿用现有引擎，零 schema / 零运行期改动）：
 *  - 每个框展开成一个真实的找图/找色/找文字目标：`RoiApplier.applyMulti` 把
 *    框 i 的 region + similarity 写到脚本内第 i 个真正读取 region 的目标；
 *  - 目标数 ≠ 框数时短边轮转复用，保证每个目标都拿到一个框；
 *  - 单框时行为与 v2.7.1 的 [RoiApplier.apply] 完全一致（兼容映射：旧单 ROI
 *    + 全局阈值提升为 `boxes=[FeatureBox(oldRegion, oldSimilarity)]`）。
 *
 * 注：本类只在拾取/应用期承载，不进入 Script 的序列化 schema——落盘仍是
 * 现有 `region` + `imageSimilarity` 字段，`schemaVersion` 不变。
 */
data class FeatureBox(
    val region: Region,
    val similarity: Int = 85,
) {
    init {
        require(similarity in 0..100) { "similarity 须在 0..100，实际 $similarity" }
    }

    companion object {
        /** 默认相似度（需求建议值 85）。 */
        const val DEFAULT_SIMILARITY = 85

        /** 相似度合法区间。 */
        fun clampSimilarity(v: Int): Int = v.coerceIn(0, 100)
    }
}

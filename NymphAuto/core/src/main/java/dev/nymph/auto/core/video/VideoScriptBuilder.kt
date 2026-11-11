package dev.nymph.auto.core.video

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.LoopPolicy
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script

/**
 * 视频候选触点 → 特征脚本（v2.7.0，纯 JVM 可测）。
 *
 * 这是「导入 mp4 自动生成脚本」链路的**最后一公里**，也是刻意做成半自动的
 * 原因：视频里没有触点标签，[TapCandidateDetector] 只给候选，用户确认后才
 * 在这里落脚本。生成的是**特征锚定**动作（CLICK_IMAGE 找图点击），而不是
 * 固定坐标回放——目标移动、换分辨率、界面轻微改版时仍会重新定位。
 *
 * 模板截取失败的动作如实降级为坐标点击（TAP），不假装成找图。
 *
 * v2.9.2：新增 [build] 的 `repeatCount` 参数（默认 1，老调用零回归）。
 * 用户录的往往是一段「重复操作」（如游戏连续扫荡），若生成脚本只跑一遍，
 * 仍无法「实现类似操作」。循环策略统一由 [LoopPolicy] 归一化。
 *
 * v2.9.6：**门控模式**（[Options.waitForAppearance]，默认开启）。此前生成的
 * 找图动作只有 `waitMs = gap`（≤3s），而找图自身重试窗口默认仅 2s；游戏里
 * 「扫荡 → 十秒级开箱动画 → 结算确认 → 再扫荡」的阶段时长随机，固定延时脚本
 * 会在动画期间超时空转。现在每个有模板的动作自带 [WaitGate] 条件，变成
 * 「等到该画面出现 → 再点」的事件驱动循环，不依赖任何时长猜测。
 * 传 `waitForAppearance = false` 可完全回到 v2.9.5 的固定延时行为。
 */
object VideoScriptBuilder {

    data class Tap(
        val tMs: Long,
        /** 候选点在视频帧内的百分比位置。 */
        val xPct: Double,
        val yPct: Double,
        /** 从该帧对应时刻截取的模板 base64；空 = 截模板失败，降级为坐标点击。 */
        val templateB64: String = "",
    )

    data class Options(
        val imageSimilarity: Int = 80,
        val minGapMs: Long = 0L,
        val maxGapMs: Long = 3000L,
        val defaultGapMs: Long = 400L,
        /**
         * v2.9.6：为有模板的找图动作挂「等待画面出现」门控条件。
         * 开启后该动作的 `waitMs` 归零（门控本身即同步点）；
         * 关闭则保持 v2.9.5 的固定延时语义。
         */
        val waitForAppearance: Boolean = true,
    )

    fun build(
        name: String,
        taps: List<Tap>,
        videoWidth: Int,
        videoHeight: Int,
        opts: Options = Options(),
        repeatCount: Int = 1,
    ): Script {
        val ordered = taps.sortedBy { it.tMs }
        val actions = ArrayList<Action>(ordered.size)
        // v2.9.4：Options 允许调用方自定义，若 minGapMs > maxGapMs，
        // coerceIn 会因空区间抛 IllegalArgumentException，整条视频导入链路崩溃。
        // 先归一化区间并保证非负，正常默认配置行为完全不变。
        val minGap = minOf(opts.minGapMs, opts.maxGapMs).coerceAtLeast(0L)
        val maxGap = maxOf(opts.minGapMs, opts.maxGapMs).coerceAtLeast(minGap)
        var prevT = 0L
        var gated = 0
        ordered.forEachIndexed { i, tap ->
            val gap = if (i == 0) 0L else (tap.tMs - prevT).coerceIn(minGap, maxGap)
            val desc = "视频 ${tap.tMs}ms 触点" +
                if (tap.templateB64.isEmpty()) "（模板缺失·坐标兜底）" else ""
            actions += if (tap.templateB64.isNotEmpty()) {
                val gate = opts.waitForAppearance
                if (gate) gated++
                Action(
                    type = ActionType.CLICK_IMAGE,
                    desc = desc + if (gate) "（等待画面出现）" else "",
                    imageData = tap.templateB64,
                    imageSimilarity = opts.imageSimilarity.coerceIn(1, 100),
                    region = Region(),
                    // 门控模式：等到画面出现即点，固定延时反而拖慢且无意义。
                    waitMs = if (gate) 0L else gap,
                    condition = if (gate) WaitGate.image(
                        tap.templateB64, opts.imageSimilarity,
                    ) else null,
                    waitAppear = gate,
                )
            } else {
                Action(
                    type = ActionType.TAP,
                    desc = desc,
                    unit = dev.nymph.auto.core.model.CoordUnit.PCT,
                    x = tap.xPct.coerceIn(0.0, 100.0),
                    y = tap.yPct.coerceIn(0.0, 100.0),
                    waitMs = gap,
                )
            }
            prevT = tap.tMs
        }
        val loop = LoopPolicy.normalize(repeatCount)
        val mode = if (opts.waitForAppearance) {
            "门控模式：${WaitGate.description()}；$gated 个找图动作等待画面出现，" +
                "${ordered.size - gated} 个模板缺失动作按坐标+固定延时兜底（如实降级）"
        } else {
            "固定延时模式：按视频触点时间间隔回放（v2.9.5 行为）"
        }
        return Script(
            name = name.ifBlank { "视频生成脚本" },
            refWidth = videoWidth,
            refHeight = videoHeight,
            repeatCount = loop,
            description = "由 mp4 帧差候选触点半自动生成的特征脚本；" +
                "触点位置经用户确认，回放时按图像模板重新定位，不依赖固定坐标。" +
                " $mode。循环设置：${LoopPolicy.description(loop)}。",
            actions = actions,
        )
    }
}

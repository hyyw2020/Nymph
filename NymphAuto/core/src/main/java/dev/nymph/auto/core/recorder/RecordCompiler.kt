// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.model.TargetPackages

/**
 * 录制事件流 → 明文脚本的编译器（core 纯 JVM）。
 *
 * ## 强化点（相对「只记坐标」的朴素录制器）
 *
 * 1. **多模态锚点**：每一步优先用 viewId / 节点文字 / 离线 OCR 文字 / 图像模板，
 *    只有全都没有时才降级到百分比坐标（见 [AnchorSelector]）。换机、改版时不易失效。
 * 2. **事件去噪**：无障碍服务对一次点击常上报 CLICK + FOCUS + WINDOW_CONTENT_CHANGED
 *    等多个事件；这里按「同类型 + 近距离 + 短间隔」合并，并剔除 FOCUS 噪声。
 * 3. **滚动合并**：一次手指滑动会触发多次 TYPE_VIEW_SCROLLED，累加为单个 SWIPE。
 * 4. **应用包守卫**：目标包名变化时插入 OPEN_APP，回放不会串到别的应用上。
 * 5. **自动等待**：按事件真实时间间隔给动作设 waitMs（钳制上限），还原人的操作节奏。
 * 6. **参考分辨率**：写入 refWidth/refHeight/refRotation，回放按比例映射坐标。
 * 7. **自过滤**：录制器自身浮窗上的点击绝不会进入脚本（selfPackage）。
 *
 * 产物是 [Script]，经 `ScriptCodec` 序列化为**明文 JSON（.zjs）**——
 * 不加密、不混淆，可逐行审计，符合硬性约束。
 *
 * ## 权限约束
 *
 * 本编译器不接触任何 Android API，输入中的模板图与 OCR 文本均约定来自
 * 无障碍 `takeScreenshot()`，全链路零 MediaProjection、零触摸拦截。
 */
class RecordCompiler(private val options: RecordOptions = RecordOptions()) {

    data class Result(val script: Script, val report: RecordReport)

    fun compile(name: String, raw: List<RecordEvent>): Result {
        val warnings = mutableListOf<String>()
        val capped = if (raw.size > options.maxEvents) {
            warnings += "事件数 ${raw.size} 超过上限 ${options.maxEvents}，仅保留前 ${options.maxEvents} 条"
            raw.take(options.maxEvents)
        } else raw

        // 1) 剔除自身 UI 事件与无意义噪声
        var selfDropped = 0
        var systemUiDropped = 0
        var invalidCoordDropped = 0
        val filtered = capped.filter { e ->
            val pkg = e.node?.packageName.orEmpty()
            when {
                pkg == options.selfPackage || e.activityName.startsWith(options.selfPackage) -> {
                    selfDropped++; false
                }
                e.kind == RecordEventKind.FOCUS -> false
                // 系统桌面/通知栏/最近任务/锁屏的窗口事件：包名是无启动入口的系统组件，
                // 若录成 OPEN_APP，会在预检被判「目标应用未安装」而拦死整个脚本（v1.2.1 实测）。
                e.kind == RecordEventKind.WINDOW_CHANGED && !TargetPackages.isLaunchableTarget(pkg) -> false
                // v2.2.0 真机修复：系统 UI 覆盖层（statusbar/navbar/recents/锁屏）的
                // 节点交互只在系统窗口里存在，回放时 rootInActiveWindow 永远找不到，
                // 录进脚本就是必然失败的首步（实测 recent_apps 成了第 1 步 CLICK_NODE）。
                // 只过滤系统 UI 包，不动桌面 Launcher —— 桌面点应用图标是有效录制内容。
                (e.kind == RecordEventKind.CLICK || e.kind == RecordEventKind.LONG_CLICK ||
                    e.kind == RecordEventKind.SCROLL) &&
                    TargetPackages.isSystemUiOverlay(pkg) -> {
                    systemUiDropped++; false
                }
                // v2.2.0 真机修复：窗口切换常伴随一条没有任何锚点、坐标退化为
                // (0,0) 的 CLICK 事件，旧版会降级成「点击 坐标 0.0%/0.0%」，
                // 回放时点屏幕左上角并触发负 Path bounds 崩溃。无有效坐标即丢弃。
                (e.kind == RecordEventKind.CLICK || e.kind == RecordEventKind.LONG_CLICK) &&
                    e.x <= 0 && e.y <= 0 &&
                    e.node == null && e.ocrText.isBlank() && e.templateB64.isBlank() -> {
                    invalidCoordDropped++; false
                }
                else -> true
            }
        }

        // 2) 按时间排序（采集侧可能因异步截图而乱序）
        val sorted = filtered.sortedBy { it.atMs }

        // 3) 去重 + 滚动合并
        val dedup = mutableListOf<RecordEvent>()
        var dupMerged = 0
        var scrollMerged = 0
        var tapMerged = 0
        for (e in sorted) {
            val last = dedup.lastOrNull()
            // ① 连点聚合必须先于去重：旧版把 320ms 内的同点重复点击一律丢弃，
            //    用户有意的连点（连点赞、连加号）会被吞成一次，回放步数对不上。
            //    这里只把「系统对同一次点击的重复上报」（<=130ms）当噪声，
            //    130~600ms 的同锚点点击累加为 repeat，交给动作的 times 表达。
            if (last != null && isTapContinuation(last, e)) {
                dedup[dedup.lastIndex] = mergeTap(last, e)
                tapMerged++
                continue
            }
            if (last != null && isDuplicate(last, e)) {
                // 保留锚点信息更丰富的那条（后到的常带 OCR/模板结果）
                if (richness(e) > richness(last)) dedup[dedup.lastIndex] = e
                dupMerged++
                continue
            }
            // v2.3.0：窗口层级变化事件不携带任何可回放语义，统一留到动作生成
            // 阶段整体丢弃并如实计数，不再在此做同包合并——避免两处逻辑互相
            // 干扰，也让报告里的「忽略窗口事件」反映真实丢弃量。
            if (last != null && last.kind == RecordEventKind.SCROLL && e.kind == RecordEventKind.SCROLL &&
                e.atMs - last.atMs <= options.mergeScrollWindowMs && sameDirection(last, e) &&
                last.repeat < options.maxSwipeTimes
            ) {
                // v2.5.0：不再累加 dx/dy（那是「列表内容滚了多少」，累加会把一次
                // 翻页放大成横贯全屏的超长滑动）。方向只保留组内首个有效符号，
                // 次数用 repeat 承载，回放由 ScriptRunner 的 times 循环连续快速翻页。
                val keepFirst = last.dx != 0 || last.dy != 0
                dedup[dedup.lastIndex] = last.copy(
                    dx = if (keepFirst) last.dx else e.dx,
                    dy = if (keepFirst) last.dy else e.dy,
                    x2 = e.x2, y2 = e.y2, atMs = e.atMs,
                    repeat = (last.repeat + e.repeat).coerceAtMost(options.maxSwipeTimes),
                )
                scrollMerged++
                continue
            }
            dedup += e
        }

        // 4) 生成动作
        val actions = mutableListOf<Action>()
        val anchorCounts = mutableMapOf<AnchorKind, Int>()
        var appGuards = 0
        var waits = 0
        var windowDropped = 0
        var lastPkg = ""
        var lastAt = 0L

        val first = dedup.firstOrNull()
        val refW = first?.screenW ?: 0
        val refH = first?.screenH ?: 0
        val refRot = first?.rotation ?: 0

        // v2.5.0：记录录制起始界面。窗口事件本身被丢弃（v2.3.0 真机教训：
        // 编成 OPEN_APP 会反复拉起应用、重置页面），且窗口事件可能只是切页
        // 过渡态，包名不代表实际交互所在界面。因此只从「交互事件」里取
        // 第一个非自身、非系统 UI 的包名，作为回放前界面校验的基准。
        val startPkg = dedup.firstOrNull { e ->
            e.kind != RecordEventKind.WINDOW_CHANGED && e.kind != RecordEventKind.FOCUS &&
                e.node?.packageName.let { p ->
                    !p.isNullOrBlank() && p != options.selfPackage && !TargetPackages.isSystemUiOverlay(p)
                }
        }?.node?.packageName.orEmpty()

        // 方向不可知的滚动条数：真机上 TYPE_VIEW_SCROLLED 的 scrollDelta
        // 常常是 0，此时只保留「向上翻页」的默认语义，必须如实告警。
        val unknownDirScrolls = dedup.count { e ->
            e.kind == RecordEventKind.SCROLL && e.dx == 0 && e.dy == 0 && e.x2 == 0 && e.y2 == 0
        }
        if (unknownDirScrolls > 0) {
            warnings += "$unknownDirScrolls 组滚动未上报方向，已按默认向上翻页重建（被动无障碍录制无法获取真实手指轨迹）"
        }
        if (startPkg.isNotEmpty() && dedup.any { it.kind == RecordEventKind.SCROLL }) {
            warnings += "录制含滚动动作：回放前请手动回到录制起始界面（$startPkg），滚动位置无法自动还原"
        }

        for ((index, e) in dedup.withIndex()) {
            val pkg = e.node?.packageName.orEmpty()

            // v2.3.0 真机修复：窗口层级变化（TYPE_WINDOWS_CHANGED /
            // TYPE_WINDOW_STATE_CHANGED）在滚动、切页、弹窗时高频上报（真机
            // 31 事件里 14 条是窗口事件），旧版每条可启动包的窗口事件都编成
            // 一个 OPEN_APP「切换到 xxx」，5 步脚本里出现 3 个 OPEN_APP 反复
            // 拉起同一个应用；回放时 startActivity 会把当前页面状态重置
            // （弹窗关闭、列表归位），真机表现为第一次回放命中、第二次全失败。
            // 窗口事件不携带任何可回放语义，全部丢弃；应用切换由包守卫
            // （依据交互事件的包名变化）统一负责。
            if (e.kind == RecordEventKind.WINDOW_CHANGED) {
                windowDropped++
                continue
            }

            // 包守卫：目标应用变化时先打开它（避免回放到错误的前台应用）。
            // 系统组件包（桌面/系统 UI）无启动入口，不生成守卫，也不参与安装预检。
            if (options.insertAppGuard && TargetPackages.isLaunchableTarget(pkg) && pkg != lastPkg &&
                e.kind != RecordEventKind.WINDOW_CHANGED
            ) {
                actions += Action(
                    type = ActionType.OPEN_APP,
                    desc = "进入 ${shortPkg(pkg)}",
                    packageName = pkg,
                )
                appGuards++
            }

            // 自动等待：还原人的操作节奏（首个动作不等待）
            if (index > 0 && e.atMs - lastAt > options.minWaitMs) {
                val w = (e.atMs - lastAt).coerceAtMost(options.maxWaitMs)
                // 落到下一个动作的 waitMs，而不是插入 WAIT 动作，脚本更紧凑
                actions += pendingWaitMarker(w)
                waits++
            }

            val action = toAction(e, anchorCounts, warnings) ?: continue
            actions += action
            if (pkg.isNotEmpty()) lastPkg = pkg
            lastAt = e.atMs
        }

        // 把 waitMs 标记合并进紧随其后的动作；翻页动作的等待单独收紧，
        // 否则一段 12s 的连续翻页会被 8 段长停顿拖成 19s 的慢速滑动。
        val merged = mergeWaits(actions).map { a ->
            if (a.type == ActionType.SWIPE && a.waitMs > options.swipeWaitCapMs) {
                a.copy(waitMs = options.swipeWaitCapMs)
            } else a
        }

        val report = RecordReport(
            rawEvents = raw.size,
            keptEvents = dedup.size,
            duplicatesMerged = dupMerged,
            scrollsMerged = scrollMerged,
            tapsMerged = tapMerged,
            selfEventsDropped = selfDropped,
            systemUiEventsDropped = systemUiDropped,
            invalidCoordDropped = invalidCoordDropped,
            windowEventsDropped = windowDropped,
            appGuardsInserted = appGuards,
            waitsInserted = waits,
            actions = merged.size,
            anchorCounts = anchorCounts.toSortedMap(compareBy { ordinal(it) }),
            warnings = warnings,
        )

        val script = Script(
            name = name.ifBlank { "录制脚本" },
            refWidth = refW,
            refHeight = refH,
            refRotation = refRot,
            startPackage = startPkg,
            actions = merged,
            description = buildDescription(report),
        )
        return Result(script, report)
    }

    // ---- 事件归一化 ----

    /** 同锚点的有意连点（间隔大于重复上报窗口、且不超过聚合窗口）。 */
    private fun isTapContinuation(a: RecordEvent, b: RecordEvent): Boolean {
        if (a.kind != RecordEventKind.CLICK || b.kind != RecordEventKind.CLICK) return false
        val gap = b.atMs - a.atMs
        if (gap <= options.duplicateReportWindowMs || gap > options.tapMergeWindowMs) return false
        return sameAnchor(a, b)
    }

    /** 同一目标：viewId 相同（非空）或都无 id 时按中心距判定。 */
    private fun sameAnchor(a: RecordEvent, b: RecordEvent): Boolean {
        val ra = a.node?.viewId.orEmpty()
        val rb = b.node?.viewId.orEmpty()
        if (ra != rb) return false
        val r = options.dedupeRadiusPx
        return kotlin.math.abs(a.x - b.x) <= r && kotlin.math.abs(a.y - b.y) <= r
    }

    /** 合并连点，保留信息更丰富的那条的锚点与增强结果。 */
    private fun mergeTap(a: RecordEvent, b: RecordEvent): RecordEvent {
        val richer = if (richness(b) > richness(a)) b else a
        return a.copy(
            atMs = b.atMs,
            repeat = (a.repeat + b.repeat).coerceAtMost(MAX_TAP_REPEAT),
            node = richer.node ?: a.node,
            templateB64 = richer.templateB64.ifBlank {
                a.templateB64.ifBlank { b.templateB64 }
            },
            ocrText = richer.ocrText.ifBlank { a.ocrText.ifBlank { b.ocrText } },
        )
    }

    private fun isDuplicate(a: RecordEvent, b: RecordEvent): Boolean {
        if (a.kind != b.kind) return false
        if (b.atMs - a.atMs > options.dedupeWindowMs) return false
        if (a.kind == RecordEventKind.SCROLL || a.kind == RecordEventKind.WINDOW_CHANGED ||
            a.kind == RecordEventKind.TEXT_INPUT || a.kind == RecordEventKind.KEY
        ) return false
        val r = options.dedupeRadiusPx
        return kotlin.math.abs(a.x - b.x) <= r && kotlin.math.abs(a.y - b.y) <= r &&
            a.node?.viewId == b.node?.viewId
    }

    /**
     * v2.5.0：无障碍滚动上报的 scrollDelta 常常是 0（真机 39 次滚动里多数为 0），
     * 旧版要求「两者至少有一个非零且同号」，导致连续翻页几乎无法合并，
     * 39 次滚动散成 15 个零长度 SWIPE。现在：
     *  - 主轴不同（一横一竖）不合并；
     *  - delta 全 0 视为同向（同一次连续翻页的重复上报）；
     *  - 有符号时同号才合并，避免把「下滑后又上滑」折成一次。
     */
    private fun sameDirection(a: RecordEvent, b: RecordEvent): Boolean {
        val av = kotlin.math.abs(a.dy) >= kotlin.math.abs(a.dx)
        val bv = kotlin.math.abs(b.dy) >= kotlin.math.abs(b.dx)
        if (av != bv && (a.dx != 0 || a.dy != 0) && (b.dx != 0 || b.dy != 0)) return false
        if ((a.dx == 0 && a.dy == 0) || (b.dx == 0 && b.dy == 0)) return true
        return if (av) a.dy.toLong() * b.dy >= 0 else a.dx.toLong() * b.dx >= 0
    }

    private fun richness(e: RecordEvent): Int = listOf(
        e.node?.viewId?.isNotBlank() == true,
        AnchorSelector.pickNodeLabel(e.node ?: RecordNode()).isNotBlank(),
        e.ocrText.isNotBlank(),
        e.templateB64.isNotBlank(),
    ).count { it }

    // ---- 动作生成 ----

    private fun toAction(
        e: RecordEvent,
        counts: MutableMap<AnchorKind, Int>,
        warnings: MutableList<String>,
    ): Action? {
        val common = { desc: String ->
            Action(
                desc = desc,
                unit = CoordUnit.PCT,
                x = AnchorSelector.pct(e.x, e.screenW),
                y = AnchorSelector.pct(e.y, e.screenH),
            )
        }
        return when (e.kind) {
            RecordEventKind.WINDOW_CHANGED -> {
                // v2.3.0：窗口事件已在主循环统一丢弃，绝不再生成 OPEN_APP。
                // 保留此分支作为防御——即便将来有人改了过滤逻辑，也不会
                // 复活「反复拉起应用」的真机回归。
                null
            }

            RecordEventKind.KEY -> Action(
                type = ActionType.KEY, desc = "按键 ${e.keyName.ifBlank { "BACK" }}",
                keyName = e.keyName.ifBlank { "BACK" },
            )

            RecordEventKind.TEXT_INPUT -> {
                if (e.text.isBlank()) null
                else Action(type = ActionType.INPUT, desc = "输入「${AnchorSelector.shorten(e.text, 20)}」", text = e.text)
            }

            RecordEventKind.SCROLL -> {
                // v2.5.0 真机修复：无障碍只给「滚动容器中心 + 内容滚动增量」，
                // 不给手指落点。旧版把 x/y（容器中心）当起点、把 dx/dy（内容
                // 滚动量）当位移，两者都常常是 0/异常值，于是生成
                // ① 起点=终点的零长度 SWIPE → 回放退化成点击，误触打开内容；
                // ② 幅度被 coerceIn 拉到屏幕边缘 → 横贯全屏的超长滑动误触系统手势。
                //
                // 现在改为「受控幅度的标准翻页」：以屏幕中轴为基准，沿主轴
                // 对称展开固定百分比的行程，全程落在安全边距内。方向只取
                // delta 符号（内容上滚 = 手指上滑），幅度不再依赖不可靠的绝对值。
                val margin = options.swipeSafeMarginPct.coerceIn(8.0, 30.0)
                val span = options.swipeSpanPct.coerceIn(12.0, 60.0)
                val half = span / 2.0
                val vertical = kotlin.math.abs(e.dy) >= kotlin.math.abs(e.dx)
                // dy>0：内容向上翻（看到后面的内容）→ 手指上滑 → 终点在上方。
                // delta 全 0 时方向不可知，默认向上翻页（列表/抽屉最常见），
                // 由 compile() 汇总告警，如实告知用户。
                val sign = if (vertical) (if (e.dy > 0) -1.0 else 1.0) else (if (e.dx > 0) -1.0 else 1.0)
                val cx = 50.0
                val cy = 50.0
                val x1: Double
                val y1: Double
                val x2: Double
                val y2: Double
                if (vertical) {
                    x1 = cx; x2 = cx
                    y1 = (cy - half * sign).coerceIn(margin, 100.0 - margin)
                    y2 = (cy + half * sign).coerceIn(margin, 100.0 - margin)
                } else {
                    y1 = cy; y2 = cy
                    x1 = (cx - half * sign).coerceIn(margin, 100.0 - margin)
                    x2 = (cx + half * sign).coerceIn(margin, 100.0 - margin)
                }
                // 连续翻页次数交由 ScriptRunner 的 times 循环快速连续执行，
                // 而不是把 39 次滚动压成 15 个 400ms 的慢速长滑。
                val times = e.repeat.coerceAtLeast(1).coerceAtMost(options.maxSwipeTimes)
                Action(
                    type = ActionType.SWIPE,
                    desc = if (times > 1) "连续翻页 ×$times" else "翻页",
                    unit = CoordUnit.PCT, x = x1, y = y1, x2 = x2, y2 = y2,
                    durationMs = options.swipeDurationMs,
                    times = times,
                    gapMs = if (times > 1) options.swipeGapMs else 0L,
                )
            }

            RecordEventKind.CLICK, RecordEventKind.LONG_CLICK -> {
                val anchor = AnchorSelector.best(e, options)
                if (anchor == null) {
                    warnings += "第 ${e.atMs}ms 的交互无任何可用锚点，已跳过（可关闭「禁止坐标降级」后重录）"
                    return null
                }
                // 最后防线：坐标降级时若坐标退化为 (0,0)，绝不生成点击动作——
                // 回放时会点屏幕左上角，且在开启形态随机时抖出负 Path bounds 崩溃。
                if (anchor.kind == AnchorKind.COORD && e.x <= 0 && e.y <= 0) {
                    warnings += "第 ${e.atMs}ms 的交互无有效坐标锚点，已跳过（避免误点左上角）"
                    return null
                }
                counts[anchor.kind] = (counts[anchor.kind] ?: 0) + 1
                val base = common(anchor.summary)
                val n = e.node
                val act = when (anchor.kind) {
                    AnchorKind.NODE_ID -> base.copy(
                        type = if (e.kind == RecordEventKind.LONG_CLICK) ActionType.LONG_PRESS else ActionType.CLICK_NODE,
                        desc = "点击 ${anchor.summary}",
                        nodeId = n?.viewId.orEmpty(),
                        nodeClass = n?.className.orEmpty(),
                        nodeText = AnchorSelector.pickNodeLabel(n ?: RecordNode()),
                        holdMs = if (e.kind == RecordEventKind.LONG_CLICK) 800 else 100,
                    )

                    AnchorKind.NODE_TEXT -> base.copy(
                        type = ActionType.CLICK_TEXT,
                        desc = "点击 ${anchor.summary}",
                        text = AnchorSelector.pickNodeLabel(n ?: RecordNode()),
                        holdMs = if (e.kind == RecordEventKind.LONG_CLICK) 800 else 100,
                    )

                    AnchorKind.OCR_TEXT -> base.copy(
                        type = ActionType.CLICK_TEXT,
                        desc = "点击 ${anchor.summary}（OCR）",
                        text = e.ocrText.trim(),
                        holdMs = if (e.kind == RecordEventKind.LONG_CLICK) 800 else 100,
                    )

                    AnchorKind.TEMPLATE -> base.copy(
                        type = ActionType.CLICK_IMAGE,
                        desc = "点击 ${anchor.summary}",
                        imageData = e.templateB64,
                        imageSimilarity = 80,
                        region = templateRegion(e),
                        holdMs = if (e.kind == RecordEventKind.LONG_CLICK) 800 else 100,
                    )

                    AnchorKind.COORD -> base.copy(
                        type = if (e.kind == RecordEventKind.LONG_CLICK) ActionType.LONG_PRESS else ActionType.TAP,
                        desc = "点击 ${anchor.summary}（坐标兜底）",
                        holdMs = if (e.kind == RecordEventKind.LONG_CLICK) 800 else 100,
                    )
                }
                // 连点：次数交给通用循环（ScriptRunner 对任何动作都会重复 times 次），
                // 不降级成 MULTI_TAP——后者只支持坐标，会丢掉刚记下的节点/文字锚点。
                if (e.repeat > 1) {
                    val rep = e.repeat.coerceAtMost(MAX_TAP_REPEAT)
                    act.copy(times = rep, desc = "${act.desc} ×$rep")
                } else {
                    act
                }
            }

            RecordEventKind.FOCUS -> null
        }
    }

    /** 图像模板的搜索区域：锚点周围 ±25% 屏幕，避免过度扫描与误匹配。 */
    private fun templateRegion(e: RecordEvent): Region {
        val cx = AnchorSelector.pct(e.x, e.screenW)
        val cy = AnchorSelector.pct(e.y, e.screenH)
        return Region(
            leftPct = (cx - 25.0).coerceIn(0.0, 100.0),
            topPct = (cy - 25.0).coerceIn(0.0, 100.0),
            rightPct = (cx + 25.0).coerceIn(0.0, 100.0),
            bottomPct = (cy + 25.0).coerceIn(0.0, 100.0),
        )
    }

    // ---- 等待合并 ----

    private val waitMarkerType = ActionType.WAIT
    private fun pendingWaitMarker(ms: Long) = Action(type = waitMarkerType, desc = "__WAIT__", waitMs = ms)

    private fun mergeWaits(list: List<Action>): List<Action> {
        val out = mutableListOf<Action>()
        var pendingWait = 0L
        for (a in list) {
            if (a.type == ActionType.WAIT && a.desc == "__WAIT__") {
                pendingWait += a.waitMs
                continue
            }
            out += if (pendingWait > 0) {
                val v = a.copy(waitMs = pendingWait)
                pendingWait = 0
                v
            } else a
        }
        return out
    }

    private fun shortPkg(pkg: String): String = pkg.substringAfterLast('.')

    private fun ordinal(k: AnchorKind) = when (k) {
        AnchorKind.NODE_ID -> 0; AnchorKind.NODE_TEXT -> 1; AnchorKind.OCR_TEXT -> 2
        AnchorKind.TEMPLATE -> 3; AnchorKind.COORD -> 4
    }

    private fun buildDescription(r: RecordReport): String = buildString {
        append("由无障碍被动录制生成（零 MediaProjection、不消费触摸）。")
        append(r.oneLine()).append("。")
        if (r.anchorCounts.isNotEmpty()) {
            append(" 锚点分布：")
            append(r.anchorCounts.entries.joinToString("、") { "${label(it.key)} ${it.value}" })
            append("。")
        }
        if (r.warnings.isNotEmpty()) {
            append(" 提示：").append(r.warnings.joinToString("；")).append("。")
        }
        append(" 脚本为明文 JSON，未加密、未混淆。")
    }

    companion object {
        /** 单条动作最多聚合的点击次数（防止误把长按连击录成几百次）。 */
        const val MAX_TAP_REPEAT = 50
    }

    private fun label(k: AnchorKind) = when (k) {
        AnchorKind.NODE_ID -> "节点ID"; AnchorKind.NODE_TEXT -> "节点文字"
        AnchorKind.OCR_TEXT -> "OCR文字"; AnchorKind.TEMPLATE -> "图像模板"; AnchorKind.COORD -> "坐标"
    }
}

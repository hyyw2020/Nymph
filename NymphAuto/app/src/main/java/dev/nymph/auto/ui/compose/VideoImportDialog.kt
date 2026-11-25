// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.graphics.BitmapFactory
import dev.nymph.auto.core.model.LoopPolicy
import dev.nymph.auto.core.video.TapEvidence
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.video.ReplayFalsifier
import dev.nymph.auto.core.video.TapCandidateDetector
import dev.nymph.auto.core.video.VideoScriptBuilder
import dev.nymph.auto.ui.FramePreviewCache
import dev.nymph.auto.ui.KeyframeTimeline
import dev.nymph.auto.ui.VideoFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 从 mp4 半自动生成特征脚本的工作台（v2.7.0）。
 *
 * 流程：选择本地视频 → 抽帧 + 帧差检测候选触点 → **用户勾选确认** →
 * 从对应时刻的全分辨率帧截模板 → 生成 CLICK_IMAGE 特征脚本 → 保存。
 *
 * 刻意不是「全自动总结点击特征」：视频里没有触点标签，纯算法会把滚动、
 * 转场、动画误判为点击。候选只是高召回的提示，最终动作由用户一次勾选定稿。
 * 全程只读用户选中的文件，零录屏权限、零网络。
 */
@Composable
fun VideoImportDialog(
    onDismiss: () -> Unit,
    onSave: (Script) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("请选择一个本地 mp4 视频") }
    var candidates by remember { mutableStateOf<List<TapCandidateDetector.TapCandidate>>(emptyList()) }
    // v2.9.47：四证据投票的可观测明细（置信度 / 待确认 / 冲突 / 来源），与 candidates 同序。
    var evidence by remember { mutableStateOf<List<TapCandidateDetector.EvidenceCandidate>>(emptyList()) }
    // v2.9.47：离线回放证伪用的采样帧（生成脚本时一次性回放，不碰真机）。
    var sampledFrames by remember {
        mutableStateOf<List<TapCandidateDetector.FrameSample>>(emptyList())
    }
    var checked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var videoSize by remember { mutableStateOf(0 to 0) }
    var sampledSize by remember { mutableStateOf(0 to 0) }

    // ---- v2.9.48：体验增强状态 ----
    // ① 证伪前置：分析阶段就跑完，列表每行直接显示"回放通过/失败+原因"，
    //    而不是等生成时才发现不可执行。与 evidence 同序。
    var verdicts by remember { mutableStateOf<List<ReplayFalsifier.Verdict>>(emptyList()) }
    // ② 默认折叠：只展示 pickDefaults 高价值子集，避免 53 行逐行看。
    var expandAll by remember { mutableStateOf(false) }
    // ③ 放大图：点开候选的 index（null=未打开）；在该图上可拖拽准星微调归一化坐标。
    var zoomIndex by remember { mutableStateOf<Int?>(null) }
    // 拖拽修正后的坐标（采样帧像素坐标），key=候选 index；生成 taps 时优先采用。
    var corrected by remember { mutableStateOf<Map<Int, Pair<Int, Int>>>(emptyMap()) }
    // v2.9.2：录进来的往往是「重复操作」。默认只跑一遍，用户可一键设成循环，
    // 不必事后进编辑器改。默认 1 次，不擅自替用户开无限循环（可能跑飞）。
    var loopCount by remember { mutableStateOf(1) }
    // v2.9.6：默认「等画面出现再点」。游戏循环常有十秒级随机动画，
    // 按视频时间生成的固定延时脚本会在动画期间超时空转。
    var waitForAppearance by remember { mutableStateOf(true) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { u -> uri = u }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        analyzing = true
        // v2.9.48：切换视频先清空缩略图瞬态缓存与所有逐视频状态，防止跨视频串图。
        FramePreviewCache.clear()
        expandAll = false
        zoomIndex = null
        corrected = emptyMap()
        verdicts = emptyList()
        status = "正在抽帧并检测候选触点…"
        val result = withContext(Dispatchers.Default) {
            // v2.9.47：先读关键帧时间表（零解码预算），再据此稀疏化采样。
            // 任一通道失败（时间表读不到）自动退化为原固定间隔采样，零回归。
            val schedule = KeyframeTimeline.read(context, u)
            VideoFrameExtractor.sampleFramesAdaptive(
                context, u,
                schedule = schedule ?: dev.nymph.auto.core.video.TapEvidence.KeyframeSchedule(),
            )
        }
        if (result == null) {
            analyzing = false
            status = "无法读取该视频或有效帧不足，请换一个文件"
            candidates = emptyList()
            evidence = emptyList()
            checked = emptySet()
            return@LaunchedEffect
        }
        videoSize = result.width to result.height
        sampledSize = result.frames.firstOrNull()?.let { it.width to it.height } ?: (0 to 0)
        // v2.9.47：走四证据融合入口（证据①稀疏化 + ②帧差块匹配 + ③涟漪 + ④遮挡）。
        val evidenceFound = withContext(Dispatchers.Default) {
            TapCandidateDetector.detectWithEvidence(
                result.frames,
                TapCandidateDetector.Options(
                    keyframeSchedule = result.keyframeSchedule,
                    enableEnhancedEvidence = true,
                ),
            )
        }
        val found = evidenceFound.map { it.candidate }
        // v2.9.48：证伪前置——分析阶段就离线回放一遍，供列表每行直接显示回放结论，
        // 把"生成时才发现不可执行"提前到"确认时就有依据"。纯 JVM、不碰真机。
        val verdictsFound = withContext(Dispatchers.Default) {
            if (found.isEmpty()) emptyList()
            else ReplayFalsifier.falsifyTaps(result.frames, found.map { it.tMs })
        }
        evidence = evidenceFound
        verdicts = verdictsFound
        sampledFrames = result.frames
        candidates = found
        // v2.9.6：按时间分桶只勾几个覆盖各阶段的候选（默认折叠时只显示这些）。
        checked = TapCandidateDetector.pickDefaults(found)
        analyzing = false

        // v2.9.48：证据命中诊断——把"四证据投票"从空泛数字变成可行动信息。
        val rippleHits = evidenceFound.count { it.sources.contains(dev.nymph.auto.core.video.TapEvidence.Kind.RIPPLE) }
        val occlusionHits = evidenceFound.count { it.sources.contains(dev.nymph.auto.core.video.TapEvidence.Kind.OCCLUSION) }
        val swipeHits = evidenceFound.count { it.sources.contains(dev.nymph.auto.core.video.TapEvidence.Kind.SWIPE) }
        val reviewCount = evidenceFound.count { it.needsReview }
        val notExecutable = verdictsFound.count { !it.executable }
        status = if (found.isEmpty()) {
            "未检测到候选触点（画面变化过于平缓或全是整屏切换）"
        } else {
            val b = StringBuilder()
            b.append("检测到 ${found.size} 个候选 · 视频 ${result.durationMs}ms · ${result.frames.size} 帧；")
            b.append("证据命中：帧差 ${found.size} · 涟漪 $rippleHits · 遮挡 $occlusionHits · 滑动 $swipeHits；")
            b.append("待确认 $reviewCount")
            if (notExecutable > 0) b.append(" · 回放不可执行 $notExecutable")
            b.append("。已勾选 ${checked.size} 个覆盖各阶段的触点。")
            // 诊断引导：涟漪/遮挡几乎不命中时，视频缺"点按反馈"证据，
            // 引导重录比逐行裁决 53 个候选的投入产出比高一个量级。
            if (rippleHits == 0 && occlusionHits == 0) {
                b.append(" 本视频未检测到点按涟漪/遮挡反馈，建议开启系统「显示点按操作」后重录，" +
                    "多数候选可自动确认，无需逐条勾选。")
            }
            b.toString()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("从 mp4 生成脚本", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "半自动：先检测候选触点，由你勾选确认，再从该时刻截图像模板，" +
                        "生成「按特征找图点击」的脚本（不是固定坐标回放）。不申请录屏权限、不联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { launcher.launch("video/*") }, enabled = !analyzing) {
                        Text("选择视频")
                    }
                    Spacer(Modifier.width(10.dp))
                    if (analyzing) CircularProgressIndicator(Modifier.height(20.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                // v2.9.2：视频里是一段重复操作时，直接在这里设循环。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "循环：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    listOf(1, 10, 0).forEach { n ->
                        FilterChip(
                            selected = loopCount == n,
                            onClick = { loopCount = n },
                            label = {
                                Text(
                                    when (n) {
                                        0 -> "无限"
                                        1 -> "1 次"
                                        else -> "$n 次"
                                    }
                                )
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "执行方式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = waitForAppearance,
                        onClick = { waitForAppearance = true },
                        label = { Text("等画面出现（推荐）") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = !waitForAppearance,
                        onClick = { waitForAppearance = false },
                        label = { Text("按视频时间") },
                    )
                }
                Text(
                    if (waitForAppearance) {
                        "每个勾选的触点会等到该画面出现再点，不猜动画时长；" +
                            "适合「战斗 → 开箱动画 → 结算领奖 → 再战」这类阶段时长随机的循环。" +
                            "模板缺失的场景按显式降级策略保留坐标点击 + 固定延时。"
                    } else {
                        "按视频里触点的时间间隔回放（≤3 秒），适合节奏固定、无长动画的界面。"
                    } + " 无限循环 / 无限等待请设脚本中止条件，或用通知栏随时停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // v2.9.48：批量操作栏——把"逐行勾"变成"按置信/证据批量裁决"。
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        // 全选正常档（ReviewLevel=OK）。
                        checked = candidates.indices.filter { i ->
                            val e = evidence.getOrNull(i)
                            e != null && TapEvidence.levelOf(
                                e.conflict, e.needsReview, e.votes, e.strengthAvg,
                            ) == TapEvidence.ReviewLevel.OK
                        }.toSet()
                    }) { Text("选正常") }
                    TextButton(onClick = {
                        // 忽略全部待确认（只留正常档勾选）。
                        checked = candidates.indices.filter { i ->
                            val e = evidence.getOrNull(i)
                            e != null && !e.needsReview && !e.conflict
                        }.toSet()
                    }) { Text("忽略待确认") }
                    TextButton(onClick = { checked = candidates.indices.toSet() }) { Text("全选") }
                    TextButton(onClick = { checked = emptySet() }) { Text("清空") }
                }

                val defaultSet = TapCandidateDetector.pickDefaults(candidates)
                val visible = if (expandAll) candidates.indices.toList()
                    else candidates.indices.filter { it in defaultSet }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(visible) { _, i ->
                        val c = candidates[i]
                        val ev = evidence.getOrNull(i)
                        val correctedPos = corrected[i]
                        val px = correctedPos?.first ?: c.x
                        val py = correctedPos?.second ?: c.y
                        val xp = if (sampledSize.first > 0) 100.0 * px / sampledSize.first else 0.0
                        val yp = if (sampledSize.second > 0) 100.0 * py / sampledSize.second else 0.0
                        val level = if (ev != null) TapEvidence.levelOf(
                            ev.conflict, ev.needsReview, ev.votes, ev.strengthAvg,
                        ) else TapEvidence.ReviewLevel.OK
                        val verdict = verdicts.getOrNull(i)
                        val levelColor = when (level) {
                            TapEvidence.ReviewLevel.CONFLICT -> MaterialTheme.colorScheme.error
                            TapEvidence.ReviewLevel.WEAK_SINGLE,
                            TapEvidence.ReviewLevel.NEEDS_REVIEW -> Color(0xFFB26A00L)
                            TapEvidence.ReviewLevel.OK -> Color(0xFF2E7D32L)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = i in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + i else checked - i
                                },
                            )
                            // 缩略图：抽帧时已缓存的彩色帧，点开进入放大拖拽图。
                            val thumbBytes = FramePreviewCache.getThumb(uri?.toString() ?: "", c.frameIndex)
                            if (thumbBytes != null) {
                                val bmp = remember(thumbBytes) {
                                    BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)?.asImageBitmap()
                                }
                                if (bmp != null) {
                                    Box(
                                        Modifier
                                            .size(56.dp, 40.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .border(1.dp, levelColor, RoundedCornerShape(4.dp))
                                            .clickable { zoomIndex = i },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Image(bmp, contentDescription = "候选帧缩略图，点按放大并修正落点",
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth())
                                        // 准星：红圈标出触点归一化位置（最小绘制尺寸保证可读）。
                                        Box(
                                            Modifier
                                                .offset(
                                                    x = ((xp / 100.0).coerceIn(0.0, 1.0) * 52).dp,
                                                    y = ((yp / 100.0).coerceIn(0.0, 1.0) * 36).dp,
                                                )
                                                .size(10.dp)
                                                .border(2.dp, Color.Red, RoundedCornerShape(50)),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                val levelText = when (level) {
                                    TapEvidence.ReviewLevel.CONFLICT -> "冲突·需确认"
                                    TapEvidence.ReviewLevel.WEAK_SINGLE -> "单证据弱·需确认"
                                    TapEvidence.ReviewLevel.NEEDS_REVIEW -> "需确认"
                                    TapEvidence.ReviewLevel.OK -> "正常"
                                }
                                val sourcesText = ev?.sources?.joinToString("/") { s ->
                                    when (s) {
                                        TapEvidence.Kind.TAP_FINGER -> "帧差"
                                        TapEvidence.Kind.RIPPLE -> "涟漪"
                                        TapEvidence.Kind.OCCLUSION -> "遮挡"
                                        TapEvidence.Kind.SWIPE -> "滑动"
                                    }
                                } ?: ""
                                val replayText = when {
                                    verdict == null -> ""
                                    !verdict.executable -> " · 回放失败"
                                    verdict.needsReview -> " · 回放待确认"
                                    else -> " · 回放通过"
                                }
                                Text(
                                    "${c.tMs}ms · (${xp.toInt()}%, ${yp.toInt()}%)$replayText",
                                    style = Ds.Mono.copy(fontSize = Ds.BodySecondary.fontSize),
                                    color = levelColor,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "$levelText · $sourcesText" + if (correctedPos != null) " · 已修正" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (!expandAll && candidates.size > visible.size) {
                        item {
                            TextButton(onClick = { expandAll = true }) {
                                Text("展开全部 ${candidates.size} 个候选（当前仅显示 ${visible.size} 个高价值项）")
                            }
                        }
                    }
                    if (expandAll && visible.size > defaultSet.size) {
                        item {
                            TextButton(onClick = { expandAll = false }) { Text("收起，仅看高价值项") }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = !analyzing && candidates.isNotEmpty() && checked.isNotEmpty(),
                        onClick = {
                            val u = uri ?: return@TextButton
                            val selected = checked.sorted()
                            val snap = candidates
                            val sw = sampledSize.first
                            val sh = sampledSize.second
                            val vw = videoSize.first
                            val vh = videoSize.second
                            scope.launch {
                                analyzing = true
                                status = "正在截取模板并生成脚本…"
                                val taps = withContext(Dispatchers.Default) {
                                    selected.mapNotNull { i ->
                                        val c = snap.getOrNull(i) ?: return@mapNotNull null
                                        // v2.9.48：优先采用放大图拖拽修正后的采样帧坐标。
                                        val pos = corrected[i]
                                        val px = pos?.first ?: c.x
                                        val py = pos?.second ?: c.y
                                        val xp = if (sw > 0) 100.0 * px / sw else 50.0
                                        val yp = if (sh > 0) 100.0 * py / sh else 50.0
                                        val tpl = VideoFrameExtractor.templateAt(context, u, c.tMs, xp, yp)
                                            .orEmpty()
                                        VideoScriptBuilder.Tap(c.tMs, xp, yp, tpl)
                                    }
                                }
                                // v2.9.48：复用分析阶段已前置算好的证伪结论（按候选下标对齐），
                                // 生成期不再重复回放。不可执行/待确认只做标注供复核，
                                // 不静默丢弃用户已勾选的动作——决定权在用户。
                                val selectedVerdicts = selected.mapNotNull { verdicts.getOrNull(it) }
                                val notExecutable = selectedVerdicts.count { !it.executable }
                                val needReview = selectedVerdicts.count { it.needsReview }
                                val falsifyNote = if (selectedVerdicts.isEmpty()) "" else
                                    " 离线证伪：${selectedVerdicts.size} 条已回放，" +
                                        "$notExecutable 条前置未满足（建议复核），$needReview 条待确认。"
                                val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                                val built = VideoScriptBuilder.build(
                                    "视频脚本 $stamp", taps, vw, vh,
                                    opts = VideoScriptBuilder.Options(
                                        waitForAppearance = waitForAppearance,
                                    ),
                                    repeatCount = LoopPolicy.normalize(loopCount),
                                )
                                val script = if (falsifyNote.isEmpty()) built else
                                    built.copy(description = built.description + falsifyNote)
                                onSave(script)
                                analyzing = false
                                onDismiss()
                            }
                        },
                    ) { Text("生成并保存") }
                }
            }
        }
    }

    // v2.9.48：放大图对话框——按需解码全帧，拖拽准星微调归一化坐标。
    // 独立的 Dialog，避免主 Surface 重组时反复解码；缓存由 FramePreviewCache LRU 持有。
    val zIdx = zoomIndex
    if (zIdx != null && zIdx in candidates.indices) {
        ZoomCandidateDialog(
            context = context,
            uri = uri,
            candidate = candidates[zIdx],
            frameIndex = candidates[zIdx].frameIndex,
            sw = sampledSize.first,
            sh = sampledSize.second,
            initialCorrected = corrected[zIdx],
            onDismiss = { zoomIndex = null },
            onConfirm = { px, py ->
                corrected = corrected + (zIdx to (px to py))
                zoomIndex = null
            },
        )
    }
}

/**
 * v2.9.48：候选放大与落点修正对话框。
 *
 * 打开时按需解码约 720px 宽的全帧（[FramePreviewCache] LRU 命中则不重复解码），
 * 红圈准星定位到候选的归一化坐标；用户在图上拖拽准星即可微调落点，
 * 确认后把采样帧像素坐标写回主界面的 corrected 映射，生成脚本时优先采用。
 * 全程不碰 schema（x_norm/y_norm 本就是可编辑浮点），零联网、零新依赖。
 */
@Composable
private fun ZoomCandidateDialog(
    context: android.content.Context,
    uri: android.net.Uri?,
    candidate: TapCandidateDetector.TapCandidate,
    frameIndex: Int,
    sw: Int,
    sh: Int,
    initialCorrected: Pair<Int, Int>?,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val u = uri ?: return
    var fullBytes by remember { mutableStateOf<ByteArray?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(u, candidate.tMs, frameIndex) {
        fullBytes = withContext(Dispatchers.Default) {
            VideoFrameExtractor.frameFullAt(context, u, candidate.tMs, frameIndex)
        }
        loadFailed = fullBytes == null
    }

    // 采样帧像素坐标（写回值）。初值取已修正值或候选原始坐标。
    var curX by remember { mutableStateOf(initialCorrected?.first ?: candidate.x) }
    var curY by remember { mutableStateOf(initialCorrected?.second ?: candidate.y) }

    val bmp = remember(fullBytes) {
        fullBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val imgW = bmp?.width ?: 1
    val imgH = bmp?.height ?: 1

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("定位与修正落点", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "第 ${candidate.tMs}ms 帧 · 拖拽红圈到准确触点位置；归一化坐标会写回脚本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (bmp != null) {
                    // 显示宽度取屏幕可用宽（近似 fillMaxWidth），按图比例换算出准星像素偏移。
                    var boxW by remember { mutableStateOf(1f) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (boxW <= 1f) return@detectDragGestures
                                    // 图上拖拽像素 → 采样帧像素坐标（sw/imgW 为缩放比）。
                                    val sx = (sw.toFloat() / imgW) * dragAmount.x
                                    val sy = (sh.toFloat() / imgH) * dragAmount.y
                                    curX = (curX + sx).toInt().coerceIn(0, sw.coerceAtLeast(1) - 1)
                                    curY = (curY + sy).toInt().coerceIn(0, sh.coerceAtLeast(1) - 1)
                                }
                            }
                            .onSizeChanged { boxW = it.width.toFloat() },
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Image(bmp, contentDescription = "候选帧放大图", contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth())
                        // 准星位置：采样帧坐标 → 归一化 → 显示框像素。最小 10px 圈保证可读。
                        val xp = if (sw > 0) curX.toFloat() / sw else 0.5f
                        val yp = if (sh > 0) curY.toFloat() / sh else 0.5f
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(
                                        (xp * boxW).toInt() - 18,
                                        (yp * boxW * imgH / imgW).toInt() - 18,
                                    )
                                }
                                .size(36.dp)
                                .border(2.dp, Color.Red, RoundedCornerShape(50)),
                        )
                    }
                } else if (loadFailed) {
                    Text("该帧解码失败，可返回列表直接勾选使用该候选。", color = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator(Modifier.height(24.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "当前落点：${if (sw > 0) (100.0 * curX / sw).toInt() else 0}%, " +
                        "${if (sh > 0) (100.0 * curY / sh).toInt() else 0}%",
                    style = Ds.Mono.copy(fontSize = Ds.BodySecondary.fontSize),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { onConfirm(curX, curY) }) { Text("使用此落点") }
                }
            }
        }
    }
}

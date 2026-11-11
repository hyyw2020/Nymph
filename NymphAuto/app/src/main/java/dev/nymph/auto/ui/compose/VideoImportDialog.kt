// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.nymph.auto.core.model.LoopPolicy
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.video.TapCandidateDetector
import dev.nymph.auto.core.video.VideoScriptBuilder
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
    var checked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var videoSize by remember { mutableStateOf(0 to 0) }
    var sampledSize by remember { mutableStateOf(0 to 0) }
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
        status = "正在抽帧并检测候选触点…"
        val result = withContext(Dispatchers.Default) {
            VideoFrameExtractor.sampleFrames(context, u)
        }
        if (result == null) {
            analyzing = false
            status = "无法读取该视频或有效帧不足，请换一个文件"
            candidates = emptyList()
            checked = emptySet()
            return@LaunchedEffect
        }
        videoSize = result.width to result.height
        sampledSize = result.frames.firstOrNull()?.let { it.width to it.height } ?: (0 to 0)
        val found = withContext(Dispatchers.Default) {
            TapCandidateDetector.detect(result.frames)
        }
        candidates = found
        // v2.9.6：不再默认全选。含全屏转场特效的视频会检出几十个动画噪声候选，
        // 全选 + 门控无限等待会让脚本卡在永不再现的动画帧上。改为按时间分桶
        // 只勾几个覆盖各阶段的候选，用户再核对保留关键按钮。
        checked = TapCandidateDetector.pickDefaults(found)
        analyzing = false
        status = if (found.isEmpty()) {
            "未检测到候选触点（画面变化过于平缓或全是整屏切换）"
        } else {
            "检测到 ${found.size} 个候选 · 视频 ${result.durationMs}ms · ${result.frames.size} 帧；" +
                "已默认勾选 ${checked.size} 个覆盖各阶段的触点，请核对，只保留关键按钮（动画噪声会拖慢门控）"
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
                            "模板缺失的动作如实降级为坐标点击 + 固定延时。"
                    } else {
                        "按视频里触点的时间间隔回放（≤3 秒），适合节奏固定、无长动画的界面。"
                    } + " 无限循环 / 无限等待请设脚本中止条件，或用通知栏随时停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(candidates) { i, c ->
                        val xp = if (sampledSize.first > 0) 100.0 * c.x / sampledSize.first else 0.0
                        val yp = if (sampledSize.second > 0) 100.0 * c.y / sampledSize.second else 0.0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = i in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + i else checked - i
                                },
                            )
                            Text(
                                "${c.tMs}ms · (%.1f%%, %.1f%%) · 得分 %.2f".format(xp, yp, c.score),
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                                        val xp = if (sw > 0) 100.0 * c.x / sw else 50.0
                                        val yp = if (sh > 0) 100.0 * c.y / sh else 50.0
                                        val tpl = VideoFrameExtractor.templateAt(context, u, c.tMs, xp, yp)
                                            .orEmpty()
                                        VideoScriptBuilder.Tap(c.tMs, xp, yp, tpl)
                                    }
                                }
                                val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                                val script = VideoScriptBuilder.build(
                                    "视频脚本 $stamp", taps, vw, vh,
                                    opts = VideoScriptBuilder.Options(
                                        waitForAppearance = waitForAppearance,
                                    ),
                                    repeatCount = LoopPolicy.normalize(loopCount),
                                )
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
}

package dev.nymph.auto.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nymph.auto.core.editor.ExecutionParadigm
import dev.nymph.auto.core.editor.ScenePreset
import dev.nymph.auto.core.runner.PollMode
import dev.nymph.auto.core.runner.RecognitionTuning
import dev.nymph.auto.core.visual.VisualConfig
import dev.nymph.auto.ui.DragDiagnostics
import java.util.Locale

// ---------------------------------------------------------------- 脚本库

@Composable
fun ScriptsScreen(
    state: AppUiState,
    onQuery: (String) -> Unit,
    onRun: (String) -> Unit,
    onEdit: (String) -> Unit,
    onFloatEdit: (String) -> Unit = {},
    onHistory: (String) -> Unit = {},
    onCreate: () -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onVideoImport: () -> Unit = {},
    onSimpleTap: () -> Unit = {},
    onStopRun: () -> Unit,
    onOpenA11y: () -> Unit = {},
    onOpenOverlay: () -> Unit = {},
    onRecord: () -> Unit = {},
) {
    val filtered = if (state.query.isBlank()) {
        state.scripts
    } else {
        state.scripts.filter {
            it.name.contains(state.query, ignoreCase = true) ||
                it.depsLine.contains(state.query, ignoreCase = true) ||
                it.packages.any { p -> p.contains(state.query, ignoreCase = true) }
        }
    }
    val totalSteps = state.scripts.sumOf { it.steps }

    // A 减负：次要入口（快速点击 / 视频导入）收进溢出菜单，顶栏只留主动作。
    var moreExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (state.running) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "正在运行",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            state.runningName.ifBlank { "脚本" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    TextButton(onClick = onStopRun) { Text("停止") }
                }
            }
        }

        // 系统能力状态条：仅当缺权限时出现，把「为什么点了没反应」提前到首屏，
        // 而不是埋在设置页深处。全部就绪时不占任何垂直空间。
        if (!state.a11yOn || !state.overlayOn) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Ds.SM),
            ) {
                if (!state.a11yOn) {
                    StatusChip("无障碍未开启", Tone.BAD, onOpenA11y)
                }
                if (!state.overlayOn) {
                    StatusChip("悬浮窗未授权", Tone.WARN, onOpenOverlay)
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("搜索脚本名 / 目标应用") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ds.LG, vertical = Ds.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 ${state.scripts.size} 个脚本 · 合计 $totalSteps 个场景",
                style = Ds.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 主动作：新建脚本——首屏唯一实心强调色块，其余动作让位。
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = Ds.RadiusPill,
                onClick = onCreate,
            ) {
                Row(
                    Modifier.padding(horizontal = Ds.LG, vertical = Ds.SM),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "新建脚本",
                        style = Ds.Label,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (state.loading) {
                Spacer(Modifier.width(Ds.SM))
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
            // 次要入口收进溢出菜单：快速点击 / 视频导入
            Spacer(Modifier.width(Ds.XS))
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多入口")
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("快速点击") },
                        onClick = { moreExpanded = false; onSimpleTap() },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("视频导入") },
                        onClick = { moreExpanded = false; onVideoImport() },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    )
                }
            }
        }

        // LazyColumn / 空态必须放在带 weight 的容器里：
        // 直接 fillMaxSize 会按父约束取满高度，把底部导航栏挤出屏幕。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.scripts.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                filtered.isEmpty() -> EmptyScripts(
                    hasQuery = state.query.isNotBlank(),
                    onImport = onImport,
                    onCreate = onCreate,
                    onRecord = onRecord,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Ds.LG, end = Ds.LG, top = Ds.SM, bottom = Ds.XL),
                    verticalArrangement = Arrangement.spacedBy(Ds.MD),
                ) {
                    // v2.9.39：key 必须用文件名 id，不能用 name。老版本遗留的
                    // script.json 与新版同名脚本会解出相同 name，以 name 作 key
                    // 触发 `Key "未命名脚本" was already used` 崩溃。
                    items(filtered, key = { it.id }) { item ->
                        ScriptCard(
                            item = item,
                            onRun = { onRun(item.name) },
                            onEdit = { onEdit(item.name) },
                            onFloatEdit = { onFloatEdit(item.name) },
                            onHistory = { onHistory(item.name) },
                            onShare = { onShare(item.name) },
                            onDelete = { onDelete(item.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScripts(
    hasQuery: Boolean,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onRecord: () -> Unit,
) {
    if (hasQuery) {
        // 搜索无结果：轻量提示，不需要三入口。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Ds.XXL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(Ds.LG))
            Text("没有匹配的脚本", style = Ds.Title)
            Spacer(Modifier.height(Ds.SM))
            Text(
                "换个关键词，或清空搜索框",
                style = Ds.BodySecondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // 空库：三入口 Hero，把「如何迈出第一步」变成可点的三条路。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ds.LG, vertical = Ds.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(Ds.LG))
        Text("建立你的第一条自动化", style = Ds.Title)
        Spacer(Modifier.height(Ds.SM))
        Text(
            "三条路径通向同一条流水线：录制 → 建档 → 运行",
            style = Ds.BodySecondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Ds.XL))
        EntryCard(
            icon = Icons.Filled.PlayArrow,
            title = "录制",
            subtitle = "照常操作目标应用，被动观察并生成脚本",
            onClick = onRecord,
        )
        Spacer(Modifier.height(Ds.MD))
        EntryCard(
            icon = Icons.Filled.Edit,
            title = "新建脚本",
            subtitle = "进入工作台，从零编排点击 / 找图 / 找字场景",
            onClick = onCreate,
        )
        Spacer(Modifier.height(Ds.MD))
        EntryCard(
            icon = Icons.Filled.Add,
            title = "导入脚本",
            subtitle = "导入自动精灵 .zjs 或明文 JSON 脚本",
            onClick = onImport,
        )
        Spacer(Modifier.height(Ds.XL))
    }
}

/** 空态入口卡：图标 + 标题副标题，整卡可点。 */
@Composable
private fun EntryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Ds.RadiusMd,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(Ds.LG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Ds.MD))
            Column(Modifier.weight(1f)) {
                Text(title, style = Ds.ItemTitle)
                Text(
                    subtitle,
                    style = Ds.BodySecondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 首屏权限状态 chip：可点跳转系统设置，缺哪项显示哪项。 */
@Composable
private fun StatusChip(text: String, tone: Tone, onClick: () -> Unit) {
    val (bg, fg) = when (tone) {
        Tone.BAD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(shape = Ds.RadiusPill, color = bg, onClick = onClick) {
        Row(
            Modifier.padding(horizontal = Ds.MD, vertical = Ds.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(fg, CircleShape))
            Spacer(Modifier.width(Ds.XS))
            Text(text, style = Ds.Label, color = fg)
        }
    }
}

// ---------------------------------------------------------------- 录制

@Composable
fun RecordScreen(
    state: AppUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenA11y: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        if (state.recording) {
            RecordingCard(state = state, onStop = onStop)
        } else {
            StartRecordCard(enabled = state.a11yOn, onStart = onStart)
        }

        if (!state.a11yOn) {
            Spacer(Modifier.height(10.dp))
            InfoRow(
                icon = Icons.Filled.Settings,
                title = "无障碍服务未开启",
                subtitle = "录制与回放都依赖它注入手势、读取控件信息",
                tone = Tone.BAD,
                statusText = "未开启",
                actionText = "去开启",
                onAction = onOpenA11y,
            )
        }
        if (!state.overlayOn) {
            Spacer(Modifier.height(10.dp))
            InfoRow(
                icon = Icons.Filled.Info,
                title = "悬浮窗未授权",
                subtitle = "没有它也能录，只是录完要用通知栏停止",
                tone = Tone.WARN,
                statusText = "未授权",
                actionText = "去授权",
                onAction = onOpenOverlay,
            )
        }

        NoteCard(
            title = "录制须知",
            lines = listOf(
                "录制订阅无障碍事件，不申请录屏权限，可与系统录屏并行。",
                "停止后立即编译为明文 JSON 并进入脚本库，可直接运行。",
            ),
        )

        if (state.scripts.isNotEmpty()) {
            SectionTitle("最近保存")
            val latest = state.scripts.first()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = Ds.RadiusMd,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant, Ds.RadiusMd),
            ) {
                Row(
                    Modifier.padding(Ds.LG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Ds.MD))
                    Column(Modifier.weight(1f)) {
                        Text(latest.name, style = Ds.ItemTitle)
                        Text(
                            "${latest.steps} 个场景 · ${latest.depsLine}",
                            style = Ds.BodySecondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StartRecordCard(enabled: Boolean, onStart: () -> Unit) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        onClick = { if (enabled) onStart() },
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (enabled) "开始录制" else "先开启无障碍服务",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "不占录屏权限 · 可随时从通知栏停止",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingCard(state: AppUiState, onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "录制中 · ${state.recordName.ifBlank { "未命名" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "不占录屏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    clockText(state.recordSeconds),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "${state.recordCount} 个场景",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.error,
                shape = CircleShape,
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "停止并保存",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onError,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }
}

private fun clockText(sec: Long): String =
    String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

// ---------------------------------------------------------------- 设置

@Composable
fun SettingsScreen(
    state: AppUiState,
    onOpenA11y: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestNotify: () -> Unit,
    onOpenLogs: () -> Unit,
    onExportBundle: () -> Unit,
    onClearLogs: () -> Unit,
    onRefresh: () -> Unit,
    onVisual: (VisualConfig) -> Unit,
    sceneGuard: Boolean = true,
    onSceneGuard: (Boolean) -> Unit = {},
    recognition: RecognitionTuning = RecognitionTuning(),
    onRecognition: (RecognitionTuning) -> Unit = {},
    onCloseFloatingEditor: () -> Unit = {},
    onShowOnboarding: () -> Unit = {},
) {
    val visual = state.visual
    // v2.9.17 / D3：进入设置页即记录一次两路位移自检快照，供日志页导出取证。
    LaunchedEffect(Unit) { DragDiagnostics.writeLog() }
    // v3.1.0：高级参考默认折叠，把设置页从「技术说明墙」收敛为「可扫描的状态清单」。
    var showAdvanced by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // v3.1.0：权限状态提到首位——用户进设置页的首要诉求是「为什么没生效」，
        // 而不是看功能开关。缺哪项点哪项，一眼扫完。
        SectionTitle("权限")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow(
                icon = Icons.Filled.Settings,
                title = "无障碍服务",
                subtitle = "手势注入 · 控件读取 · 无障碍截图",
                tone = if (state.a11yOn) Tone.OK else Tone.BAD,
                statusText = if (state.a11yOn) "已开启" else "未开启",
                actionText = if (state.a11yOn) null else "去开启",
                onAction = if (state.a11yOn) null else onOpenA11y,
            )
            InfoRow(
                icon = Icons.Filled.Info,
                title = "悬浮窗",
                subtitle = "运行/录制时的悬浮球与控制面板",
                tone = if (state.overlayOn) Tone.OK else Tone.WARN,
                statusText = if (state.overlayOn) "已授权" else "未授权",
                actionText = if (state.overlayOn) null else "去授权",
                onAction = if (state.overlayOn) null else onOpenOverlay,
            )
            InfoRow(
                icon = Icons.Filled.Notifications,
                title = "通知权限",
                subtitle = if (state.sdk >= 33) "前台服务状态与「停止并保存」入口"
                else "Android 13 以下无需单独授权",
                tone = if (state.notifyOn || state.sdk < 33) Tone.OK else Tone.WARN,
                statusText = if (state.notifyOn) "已允许" else "未允许",
                actionText = if (!state.notifyOn && state.sdk >= 33) "去开启" else null,
                onAction = if (!state.notifyOn && state.sdk >= 33) onRequestNotify else null,
            )
            InfoRow(
                icon = Icons.Filled.Star,
                title = "电池优化",
                subtitle = if (state.batteryFree) "已列入不优化，长脚本不易被系统冻结"
                else "未列入不优化，长时间运行可能被厂商省电策略中断",
                tone = if (state.batteryFree) Tone.OK else Tone.NEUTRAL,
                statusText = if (state.batteryFree) "不受限" else "受系统管理",
            )
        }

        SectionTitle("回放可视化", trailing = "回放时显示落点/轨迹")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VisualSwitch(
                title = "回放可视化总开关",
                subtitle = "开启后回放时在屏幕叠加手势特效，可被系统录屏录到",
                checked = visual.enabled,
                onChecked = { onVisual(visual.copy(enabled = it)) },
            )
            VisualSwitch(
                title = "场景规则诊断框",
                subtitle = "独立开关：显示每条场景规则的搜索区域、命中状态与落点，不叠加手势特效",
                checked = visual.sceneDebugEnabled,
                onChecked = { onVisual(visual.copy(sceneDebugEnabled = it)) },
            )
            VisualSwitch(
                title = "点击与长按反馈",
                subtitle = "圆点 + 扩散圆环 / 长按进度环",
                checked = visual.tapEnabled,
                enabled = visual.enabled,
                onChecked = { onVisual(visual.copy(tapEnabled = it)) },
            )
            VisualSwitch(
                title = "滑动与多指轨迹",
                subtitle = "轨迹带 + 方向箭头 + 多指编号",
                checked = visual.trailEnabled,
                enabled = visual.enabled,
                onChecked = { onVisual(visual.copy(trailEnabled = it)) },
            )
            VisualSwitch(
                title = "场景定位角标",
                subtitle = "预告时显示 3/12 形式的定位号",
                checked = visual.stepBadgeEnabled,
                enabled = visual.enabled,
                onChecked = { onVisual(visual.copy(stepBadgeEnabled = it)) },
            )
            VisualSwitch(
                title = "落点预告（每场景 +350ms）",
                subtitle = "执行前先画虚线细环 + 倒计时，会有意放慢脚本",
                checked = visual.previewEnabled,
                enabled = visual.enabled,
                onChecked = { onVisual(visual.copy(previewEnabled = it)) },
            )
            VisualSwitch(
                title = "克制模式",
                subtitle = "只留半透明圆点与细轨迹，去掉辉光/角标/箭头",
                checked = visual.restrained,
                enabled = visual.enabled,
                onChecked = { onVisual(visual.copy(restrained = it)) },
            )
        }
        SectionTitle("录制回放", trailing = "场景保真度")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VisualSwitch(
                title = "回放界面校验",
                subtitle = "界面与录制时不一致就中止并提示，避免落点打在错误界面上",
                checked = sceneGuard,
                onChecked = onSceneGuard,
            )
        }
        SectionTitle("识别调度", trailing = "v2.7.0 · 跟上移动目标")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val fast = recognition.pollMode != PollMode.LEGACY_300 &&
                recognition.pollMode != PollMode.LEGACY_100
            VisualSwitch(
                title = "帧同步高频识别",
                subtitle = "默认 33ms（≈30Hz）轮询找图/条件；关闭退回旧版 300ms",
                checked = fast,
                onChecked = {
                    onRecognition(
                        recognition.copy(pollMode = if (it) PollMode.FRAME_33 else PollMode.LEGACY_300),
                    )
                },
            )
            VisualSwitch(
                title = "60Hz 极速档",
                subtitle = "16ms 采样，更跟手但更耗电；仅在上项开启时生效",
                checked = recognition.pollMode == PollMode.FRAME_16,
                enabled = fast,
                onChecked = {
                    onRecognition(
                        recognition.copy(pollMode = if (it) PollMode.FRAME_16 else PollMode.FRAME_33),
                    )
                },
            )
            VisualSwitch(
                title = "运动预测 + 窄 ROI",
                subtitle = "用历史命中点外推下一帧位置，优先局部找图，跟上高速移动目标",
                checked = recognition.motionPrediction,
                onChecked = { onRecognition(recognition.copy(motionPrediction = it, narrowRoi = it)) },
            )
        }
        // v2.8.0：系统级悬浮动作编辑器——脱离本 APP 浮在其他 APK 上编辑场景，
        // 通知栏呼出/收起/关闭，折叠成 40dp 小球不遮挡。
        InfoRow(
            icon = Icons.Filled.Edit,
            title = "系统悬浮场景编辑器",
            subtitle = "脚本菜单「悬浮编辑」或编辑器「悬浮」开启；切后台仍显示",
            tone = Tone.NEUTRAL,
            statusText = "v2.8.0",
            actionText = "关闭浮窗",
            onAction = onCloseFloatingEditor,
        )

        SectionTitle("能力与环境")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow(
                icon = Icons.Filled.PlayArrow,
                title = "截图通道",
                subtitle = if (state.sdk >= 30) "无障碍 takeScreenshot，不占用系统录屏"
                else "系统低于 Android 11，仅节点/文字场景可用",
                tone = if (state.sdk >= 30) Tone.OK else Tone.WARN,
                statusText = if (state.sdk >= 30) "可用" else "不可用",
            )
            InfoRow(
                icon = Icons.Filled.Info,
                title = "运行环境",
                subtitle = "Android API ${state.sdk} · ${state.abi.ifBlank { "未知 ABI" }}",
                tone = Tone.NEUTRAL,
                statusText = "v${state.versionName.ifBlank { "?" }}",
            )
        }

        SectionTitle("诊断")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionRow("运行日志", "查看导入、执行、异常的时间线", Icons.AutoMirrored.Filled.List, onOpenLogs)
            ActionRow("导出调试包", "日志 + 失败现场截图，zip 分享", Icons.Filled.Share, onExportBundle)
            ActionRow("清空日志", "只清本机记录，不影响脚本", Icons.Filled.Clear, onClearLogs)
            ActionRow("重新检测状态", "权限变更后立即刷新", Icons.Filled.Refresh, onRefresh)
            ActionRow("新手引导", "重新查看首次启动引导", Icons.Filled.Info, onShowOnboarding)
        }

        // v3.1.0：纯参考内容统一折叠。解释性说明卡、档位后果、范式对照、位移自检、
        // 关于都不再平铺——主层只保留可操作的状态与开关，参考内容按需展开。
        CollapsibleSection(
            title = "高级参考",
            expanded = showAdvanced,
            onToggle = { showAdvanced = !showAdvanced },
        ) {
            NoteCard(
                title = "关于「1ms 找到坐标」",
                lines = listOf(
                    "无障碍全屏截图本身需 16~60ms，特征匹配无法突破采样帧率下限，故不承诺 1ms。",
                    "等效方案：帧同步高频采样 + 运动外推预测 + 预测点附近窄 ROI 局部匹配。",
                ),
            )
            Spacer(Modifier.height(Ds.SM))
            NoteCard(
                title = "关于被动录制的边界",
                lines = listOf(
                    "无障碍只上报语义事件（点击/滚动），拿不到真实手指轨迹与页面状态。",
                    "滚动按受控幅度的标准翻页重建，方向未知时按实际状态提示，不推断落点。",
                    "回放前请手动回到录制起始界面；列表滚动位置无法自动还原。",
                    "关闭本开关可退回旧行为（不校验、不中止）。老脚本不受影响。",
                ),
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Ds.SM))
            NoteCard(
                title = "零录屏权限 · 只加可视化",
                lines = listOf(
                    "只用无障碍悬浮窗（TYPE_APPLICATION_OVERLAY），不申请录屏/音频权限。",
                    "窗口 FLAG_NOT_TOUCHABLE，不拦截任何点击、不影响下层应用操作。",
                    "手势数据来自脚本真实落点，不猜手指位置；默认关闭，不改变原有行为。",
                    "特效层在找图匹配副本里被抑制，不影响找色找图；空闲时彻底停帧。",
                ),
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // v2.9.17 / D1：档位后果查阅区。主层只呈现档位名，实际参数、运行时效果
            // 与权衡在此按维度分组展开，界面信息自足，不依赖真机回归确认档位语义。
            SectionTitle("意图档位后果", trailing = "展开后的实际效果与权衡")
            val optionCatalog = ScenePreset.optionCatalog()
            listOf("触发时机", "防抖强度", "重复与重试").forEach { dimension ->
                NoteCard(
                    title = dimension,
                    lines = optionCatalog.filter { it.dimension == dimension }.map {
                        "${it.choice}：${it.effect}；权衡：${it.tradeoff}。实际生效：${it.actual}"
                    },
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // v2.9.17 / D2：两种执行范式共用一组四字段维度，随时可查，不依赖首次
            // 装配的一次性提示。
            SectionTitle("执行范式对照", trailing = "两种范式共用一组维度")
            NoteCard(
                title = "场景触发 与 顺序执行",
                lines = ExecutionParadigm.comparison().map {
                    "${it.label}｜场景触发：${it.sceneDriven}；顺序执行：${it.sequential}"
                },
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // v2.9.17 / D3：两路悬浮窗口最近一次手势的结构化结论与原始观测量。
            SectionTitle("悬浮窗口位移自检", trailing = "一次拖动即可取证")
            DragDiagnostics.snapshot().forEach { s ->
                NoteCard(
                    title = "${s.target}：${s.report.headline}",
                    lines = listOfNotNull(
                        s.report.detail,
                        s.report.action,
                        "结论与原始观测量已写入运行日志，可在「运行日志」或「导出调试包」中导出。",
                    ),
                    accent = if (s.report.ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }

            SectionTitle("关于")
            NoteCard(
                title = "离线 · 明文 · 可审计",
                lines = listOf(
                    "无 INTERNET 权限，全部数据留在本机。",
                    "脚本是明文 JSON，不加密、不混淆，可直接打开阅读。",
                    "release 构建关闭 R8 混淆，APK 内的类名与方法名保持可读。",
                    "仅 arm64-v8a 原生库，对应 64 位设备。",
                ),
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 回放可视化开关行：标题 + 副标题 + Material3 Switch。 */
@Composable
private fun VisualSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChecked(!checked) },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 可折叠分组：把纯参考内容收进默认折叠的区块，主层保持可扫描。 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Ds.RadiusMd,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = Ds.LG, vertical = Ds.MD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = Ds.ItemTitle, modifier = Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = Ds.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(Modifier.padding(bottom = Ds.MD)) { content() }
            }
        }
    }
}

@Composable
fun CrashBanner(onViewLogs: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "上次运行发生崩溃，堆栈已记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onViewLogs) { Text("查看") }
            TextButton(onClick = onDismiss) { Text("忽略") }
        }
    }
}

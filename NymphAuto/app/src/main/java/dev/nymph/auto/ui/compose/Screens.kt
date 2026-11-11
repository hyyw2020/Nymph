package dev.nymph.auto.ui.compose

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nymph.auto.core.runner.PollMode
import dev.nymph.auto.core.runner.RecognitionTuning
import dev.nymph.auto.core.visual.VisualConfig
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
    onStopRun: () -> Unit,
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
                .padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 ${state.scripts.size} 个脚本 · 合计 $totalSteps 步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建脚本")
            }
            TextButton(onClick = onVideoImport) { Text("视频导入") }
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
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
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.name }) { item ->
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
private fun EmptyScripts(hasQuery: Boolean, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (hasQuery) "没有匹配的脚本" else "还没有脚本",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasQuery) "换个关键词，或清空搜索框" else "录一段操作，或导入自动精灵 .zjs 脚本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!hasQuery) {
            Spacer(Modifier.height(18.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                onClick = onImport,
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "导入脚本",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
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

        SectionTitle("这一版怎么录")
        NoteCard(
            title = "只观察，不抢权限",
            lines = listOf(
                "录制订阅系统广播的无障碍事件（点击/长按/滚动/输入），不拦截触摸。",
                "不申请 MediaProjection，不创建 VirtualDisplay——你可以同时开系统录屏。",
                "需要看屏幕时只用无障碍 takeScreenshot()，系统录屏优先。",
                "录制期间其他 APP 的点击、滑动、输入法行为完全不变。",
            ),
        )

        SectionTitle("录制时会自动做什么")
        NoteCard(
            title = "把「能点中」放在第一位",
            lines = listOf(
                "优先记控件 ID / 控件文字，其次离线 OCR 文字，再其次小图模板，最后才用坐标。",
                "快速连点会合并成多击，一次滑动只记一条，避免脚本里堆满噪声。",
                "界面切换时自动补「打开应用」守卫，回放不会串到别的 APP。",
                "停止后立即编译成明文 JSON，自动进入脚本库，可直接运行。",
            ),
            accent = MaterialTheme.colorScheme.secondary,
        )

        if (state.scripts.isNotEmpty()) {
            SectionTitle("最近保存")
            val latest = state.scripts.first()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(latest.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${latest.steps} 步 · ${latest.depsLine}",
                            style = MaterialTheme.typography.bodySmall,
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
                    "${state.recordCount} 步",
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
) {
    val visual = state.visual
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SectionTitle("动作可视化", trailing = "回放时显示落点/轨迹")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VisualSwitch(
                title = "动作可视化总开关",
                subtitle = "开启后回放时在屏幕叠加手势特效，可被系统录屏录到",
                checked = visual.enabled,
                onChecked = { onVisual(visual.copy(enabled = it)) },
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
                title = "步骤数字角标",
                subtitle = "预告时显示 3/12 形式的步骤号",
                checked = visual.stepBadgeEnabled,
                enabled = visual.enabled,
                onChecked = { onVisual(visual.copy(stepBadgeEnabled = it)) },
            )
            VisualSwitch(
                title = "落点预告（每动作 +350ms）",
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
        SectionTitle("录制回放", trailing = "动作保真度")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VisualSwitch(
                title = "回放界面校验",
                subtitle = "界面与录制时不一致就中止并提示，避免动作打在错误界面上",
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
        NoteCard(
            title = "关于「1ms 找到坐标」",
            lines = listOf(
                "无障碍全屏截图本身需 16~60ms，特征匹配无法突破采样帧率下限，故不承诺 1ms。",
                "等效方案：帧同步高频采样 + 运动外推预测 + 预测点附近窄 ROI 局部匹配。",
            ),
        )
        NoteCard(
            title = "关于被动录制的边界",
            lines = listOf(
                "无障碍只上报语义事件（点击/滚动），拿不到真实手指轨迹与页面状态。",
                "滚动按受控幅度的标准翻页重建，方向未知时如实告警，绝不猜落点。",
                "回放前请手动回到录制起始界面；列表滚动位置无法自动还原。",
                "关闭本开关可退回旧行为（不校验、不中止）。老脚本不受影响。",
            ),
            accent = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            // v2.8.0：系统级悬浮动作编辑器说明。它用 TYPE_APPLICATION_OVERLAY
            // + 前台服务，能脱离本 APP 浮在其他 APK 上编辑、切后台仍可呼出。
            InfoRow(
                icon = Icons.Filled.Edit,
                title = "系统悬浮动作编辑器",
                subtitle = "脚本菜单「悬浮编辑」或编辑器「悬浮」开启：可浮在其他 APP 上编辑动作，" +
                    "切后台仍显示，通知栏呼出/收起/关闭；折叠成 40dp 小球不遮挡",
                tone = Tone.NEUTRAL,
                statusText = "v2.8.0",
                actionText = "关闭浮窗",
                onAction = onCloseFloatingEditor,
            )
            InfoRow(
                icon = Icons.Filled.Notifications,
                title = "通知权限",
                subtitle = if (state.sdk >= 33) "前台服务状态与「停止并保存」入口"
                else "Android 13 以下无需单独授权",
                tone = if (state.notifyOn || state.sdk < 33) Tone.OK else Tone.WARN,
                statusText = if (state.notifyOn) "已允许" else "未允许",
                // v2.0 漏接：此项此前只读展示，点击无任何动作，
                // Android 13+ 用户无法在应用内完成授权。
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

        SectionTitle("能力与环境")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow(
                icon = Icons.Filled.PlayArrow,
                title = "截图通道",
                subtitle = if (state.sdk >= 30) "无障碍 takeScreenshot，不占用系统录屏"
                else "系统低于 Android 11，仅节点/文字动作可用",
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
        Spacer(Modifier.height(24.dp))
    }
}

/** 动作可视化开关行：标题 + 副标题 + Material3 Switch。 */
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

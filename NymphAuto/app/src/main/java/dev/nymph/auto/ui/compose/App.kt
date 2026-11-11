package dev.nymph.auto.ui.compose

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.store.ScriptGrant
import dev.nymph.auto.service.FloatingEditorService
import dev.nymph.auto.ui.compose.EditorScreen

/**
 * 应用主壳：三个一级页（脚本 / 录制 / 设置）+ 二级日志页。
 *
 * 组件全部无状态化：状态来自 [AppViewModel] 的 StateFlow，动作以 lambda 注入。
 * 这样旋转、进程重建、深色切换都不会丢失正在编辑的搜索词与当前页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NymphApp(vm: AppViewModel, onPickImport: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var consent by remember { mutableStateOf<Script?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // 编辑器二级页：当前正在编辑的脚本（null=不在编辑器）
    var editing by remember { mutableStateOf<Script?>(null) }
    // v2.7.0：脚本历史版本 / 回滚工作台
    var historyFor by remember { mutableStateOf<String?>(null) }
    // v2.7.0：从 mp4 半自动生成特征脚本的工作台
    var showVideoImport by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // 二级日志页未接入系统返回：此前在日志页按返回键会直接退出整个应用。
    BackHandler(enabled = showLogs) { showLogs = false }

    // 通知权限（Android 13+ 运行时权限）：拒绝过一次后系统不再弹框，
    // 此时 launch 会立即回调 denied，引导用户去系统通知设置手动开启。
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.refresh()
        if (!granted) {
            toast("未允许通知：可在系统「应用通知设置」中手动开启")
        }
    }

    fun launch(intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun run(name: String) {
        when (val r = vm.attemptRun(name)) {
            is RunAttempt.Started -> {
                toast("已启动「${r.name}」")
                (context.findActivity())?.moveTaskToBack(true)
            }
            is RunAttempt.NeedConsent -> consent = r.script
            is RunAttempt.Failed -> toast(r.message)
        }
    }

    /**
     * v2.8.0：把「编辑动作」切到系统级悬浮窗（TYPE_APPLICATION_OVERLAY + 前台服务）。
     * 与 [run] 一样主动把本 Activity 退到后台，让用户直接看到浮在其他 APP
     * 界面之上的编辑器——这才是差评要的「脱离 NymphAuto 运行」。
     */
    fun floatEdit(name: String) {
        if (!Settings.canDrawOverlays(context)) {
            toast("请先授予悬浮窗权限，才能在其他 APP 上编辑")
            return
        }
        FloatingEditorService.show(context, name, 0)
        toast("悬浮编辑器已开启：切到任意 APP 都能编辑，通知栏可呼出/关闭")
        (context.findActivity())?.moveTaskToBack(true)
    }

    fun startWith(script: Script, grant: ScriptGrant) {
        when (val r = vm.startAfterConsent(script, grant)) {
            is RunAttempt.Started -> {
                toast("已启动「${r.name}」")
                (context.findActivity())?.moveTaskToBack(true)
            }
            is RunAttempt.Failed -> toast(r.message)
            is RunAttempt.NeedConsent -> consent = r.script
        }
    }

    if (showLogs) {
        LogScreen(
            loadLines = { vm.logTail() },
            onBack = { showLogs = false },
            onShare = { vm.shareLogs()?.let(::toast) },
            onExport = { vm.exportBundle()?.let(::toast) },
            onClear = { vm.clearLogs() },
        )
        return
    }

    // 动作编辑器（v2.6.0）：新建脚本或「编辑动作」时进入，覆盖主界面。
    editing?.let { script ->
        EditorScreen(
            initial = script,
            viewModel = vm,
            onBack = { editing = null },
            onSave = { next ->
                val err = vm.saveScript(next)
                if (err == null) {
                    toast("已保存「${next.name}」")
                    editing = null
                } else {
                    toast(err)
                }
            },
            onFloatEdit = ::floatEdit,
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NymphAuto", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "本地 · 离线 · 不占录屏权限",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新检测")
                    }
                    IconButton(onClick = onPickImport) {
                        Icon(Icons.Filled.Add, contentDescription = "导入脚本")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("脚本") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("录制") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when (tab) {
                0 -> Column(Modifier.fillMaxSize()) {
                    if (state.crashPending) {
                        CrashBanner(
                            onViewLogs = { showLogs = true },
                            onDismiss = { vm.clearCrash() },
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        ScriptsScreen(
                            state = state,
                            onQuery = vm::setQuery,
                            onRun = ::run,
                            onEdit = { name ->
                                vm.loadScript(name)?.let { editing = it }
                                    ?: toast("脚本加载失败")
                            },
                            onFloatEdit = ::floatEdit,
                            onCreate = { editing = Script(name = "未命名脚本") },
                            onHistory = { historyFor = it },
                            onShare = { vm.shareScript(it)?.let(::toast) },
                            onDelete = { pendingDelete = it },
                            onImport = onPickImport,
                            onVideoImport = { showVideoImport = true },
                            onStopRun = { vm.stopRunning(); toast("正在停止…") },
                        )
                    }
                }

                1 -> RecordScreen(
                    state = state,
                    onStart = {
                        vm.startRecording()?.let(::toast) ?: toast("录制中：照常操作其他 APP，点浮窗或通知栏停止")
                    },
                    onStop = { vm.stopRecording() },
                    onOpenA11y = {
                        launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenOverlay = {
                        launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )

                else -> SettingsScreen(
                    state = state,
                    onOpenA11y = { launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenOverlay = {
                        launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    onOpenLogs = { showLogs = true },
                    onRequestNotify = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onExportBundle = { vm.exportBundle()?.let(::toast) },
                    onClearLogs = { vm.clearLogs(); toast("日志已清空") },
                    onRefresh = { vm.refresh(); toast("已重新检测") },
                    onVisual = vm::setVisual,
                    sceneGuard = state.sceneGuard,
                    onSceneGuard = vm::setSceneGuard,
                    recognition = state.recognition,
                    onRecognition = vm::setRecognition,
                    onCloseFloatingEditor = {
                        FloatingEditorService.stop(context)
                        toast("系统悬浮编辑器已关闭")
                    },
                )
            }
        }
    }

    consent?.let { s ->
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text("「${s.name}」包含 JS 代码") },
            text = {
                Column {
                    Text(
                        "该脚本含「运行 JS 代码」动作，可调用设备能力、读写变量。" +
                            "只在你信任脚本来源时允许。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { consent = null; startWith(s, ScriptGrant.ALLOW_ALWAYS) }) {
                        Text("允许，以后不再提示")
                    }
                    TextButton(onClick = { consent = null; startWith(s, ScriptGrant.DENY_ONCE) }) {
                        Text("不允许，此次运行不再提示")
                    }
                    TextButton(onClick = { consent = null; startWith(s, ScriptGrant.DENY_ALWAYS) }) {
                        Text("不允许，以后不再提示")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { consent = null; startWith(s, ScriptGrant.ALLOW_ONCE) }) {
                    Text("允许一次")
                }
            },
            dismissButton = {
                TextButton(onClick = { consent = null }) { Text("取消") }
            },
        )
    }

    if (showVideoImport) {
        VideoImportDialog(
            onDismiss = { showVideoImport = false },
            onSave = { script ->
                val err = vm.saveScript(script)
                vm.refresh()
                if (err == null) toast("已从视频生成「${script.name}」（${script.actions.size} 个特征动作）")
                else toast(err)
            },
        )
    }

    historyFor?.let { name ->
        val versions = remember(name) { vm.scriptVersions(name) }
        AlertDialog(
            onDismissRequest = { historyFor = null },
            title = { Text("历史版本 · $name") },
            text = {
                Column {
                    if (versions.isEmpty()) {
                        Text(
                            "暂无历史版本。每次保存会自动留存上一版（最多最近 8 版），" +
                                "误删动作或自动覆盖后可在这里回滚。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "点击某个版本恢复为当前脚本；不会删除历史，修订号继续递增。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        versions.forEach { v ->
                            TextButton(onClick = {
                                val err = vm.restoreScript(name, v.revision)
                                if (err == null) toast("已回滚到 r${v.revision} 并保存为新版")
                                else toast(err)
                                historyFor = null
                            }) {
                                Text("r${v.revision} · ${v.steps} 步 · ${fmtHistoryTime(v.savedAt)}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyFor = null }) { Text("关闭") }
            },
        )
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除脚本") },
            text = { Text("确定删除「$name」？脚本文件会从本机移除，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteScript(name) { ok ->
                        toast(if (ok) "已删除「$name」" else "删除失败：文件可能已被移除")
                    }
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun fmtHistoryTime(ms: Long): String = runCatching {
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}.getOrDefault("--")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

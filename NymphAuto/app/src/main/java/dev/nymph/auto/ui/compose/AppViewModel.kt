package dev.nymph.auto.ui.compose

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.auto.core.importer.Importer
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.store.ScriptGrant
import dev.nymph.auto.core.store.ScriptGrants
import dev.nymph.auto.core.store.ScriptRepository
import dev.nymph.auto.core.store.ScriptStore
import dev.nymph.auto.core.runner.RecognitionTuning
import dev.nymph.auto.core.visual.VisualConfig
import dev.nymph.auto.engine.AutoClickService
import dev.nymph.auto.visual.RecognitionSettings
import dev.nymph.auto.visual.SceneGuardSettings
import dev.nymph.auto.visual.VisualSettings
import dev.nymph.auto.log.LogStore
import dev.nymph.auto.service.RecordingService
import dev.nymph.auto.service.RunnerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 脚本列表项（只含展示所需字段，不把整份动作树带进 UI 状态）。 */
data class ScriptUi(
    val name: String,
    val steps: Int,
    val savedAt: Long,
    val depsLine: String,
    val packages: List<String>,
    val needsScreenshot: Boolean,
    val needsAi: Boolean,
)

data class AppUiState(
    val loading: Boolean = true,
    val scripts: List<ScriptUi> = emptyList(),
    val query: String = "",
    val a11yOn: Boolean = false,
    val overlayOn: Boolean = false,
    val notifyOn: Boolean = false,
    val batteryFree: Boolean = false,
    val running: Boolean = false,
    val runningName: String = "",
    val recording: Boolean = false,
    val recordCount: Int = 0,
    val recordSeconds: Long = 0L,
    val recordName: String = "",
    val sdk: Int = Build.VERSION.SDK_INT,
    val abi: String = "",
    val versionName: String = "",
    val crashPending: Boolean = false,
    /** 动作可视化开关快照（v2.4.0）；默认全关。 */
    val visual: VisualConfig = VisualConfig(),
    /** 回放界面校验（v2.5.0）；默认开启，老脚本自动跳过。 */
    val sceneGuard: Boolean = true,
    /** 识别调度调参（v2.7.0）；默认帧同步 33ms + 运动预测 + 窄 ROI。 */
    val recognition: RecognitionTuning = RecognitionTuning(),
)

/** 运行前的判定结果：要么直接启动，要么需要用户先回答 JS 授权。 */
sealed interface RunAttempt {
    data class NeedConsent(val script: Script) : RunAttempt
    data class Started(val name: String) : RunAttempt
    data class Failed(val message: String) : RunAttempt
}

/**
 * 主界面状态与动作的单一来源。
 *
 * 设计取舍：
 *  - 文件 IO 全部在 IO 线程，StateFlow 只承载不可变快照，Compose 侧零阻塞；
 *  - 运行/录制状态从两个服务的进程内静态标量以 1s 轮询读取——
 *    同进程、两个标量，广播/Binder 的成本都不值得；
 *  - 所有会改变脚本库的操作完成后立即 refresh，避免 UI 与磁盘不一致。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()

    private val store = ScriptStore(File(ctx.filesDir, "scripts"))
    private val repo = ScriptRepository(store, File(ctx.filesDir, "scripts/.history"))
    private val grants = ScriptGrants(File(ctx.filesDir, "scripts"))

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
        // 运行/录制状态的轻量轮询：只在两个服务活跃时才有实际变化，
        // 空闲时读两个 volatile 标量的成本可以忽略。
        viewModelScope.launch {
            while (isActive) {
                val running = RunnerService.liveState != RunnerService.State.IDLE
                val recording = RecordingService.liveActive
                val startedAt = RecordingService.liveStartedAt
                val seconds = if (recording && startedAt > 0) {
                    (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000
                } else 0L
                _state.update { cur ->
                    val next = cur.copy(
                        running = running,
                        runningName = RunnerService.liveScriptName,
                        recording = recording,
                        recordCount = RecordingService.liveCount,
                        recordSeconds = seconds,
                        recordName = RecordingService.liveName,
                    )
                    // 空闲时值不变就不发布新快照：否则每秒一次 copy 会让
                    // 整个 Compose 树无谓重组，列表滚动明显掉帧。
                    if (next == cur) cur else next
                }
                delay(1000L)
            }
        }
    }

    // ------------------------------------------------------------ 刷新

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { buildSnapshot() }
            _state.update { snapshot }
        }
    }

    private fun buildSnapshot(): AppUiState {
        val items = runCatching { store.list() }.getOrDefault(emptyList()).map { item ->
            ScriptUi(
                name = item.name,
                steps = item.steps,
                savedAt = item.savedAt,
                depsLine = item.deps.oneLine(),
                packages = item.deps.packages,
                needsScreenshot = item.deps.needsScreenshot,
                needsAi = item.deps.needsAi,
            )
        }
        return AppUiState(
            loading = false,
            scripts = items,
            query = _state.value.query,
            a11yOn = AutoClickService.isEnabled(ctx),
            overlayOn = Settings.canDrawOverlays(ctx),
            notifyOn = notificationGranted(),
            batteryFree = batteryUnrestricted(),
            running = RunnerService.liveState != RunnerService.State.IDLE,
            runningName = RunnerService.liveScriptName,
            recording = RecordingService.liveActive,
            recordCount = RecordingService.liveCount,
            recordName = RecordingService.liveName,
            sdk = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            versionName = versionName(),
            crashPending = LogStore.hasCrash(),
            visual = runCatching { VisualSettings.load(ctx) }.getOrDefault(VisualConfig()),
            sceneGuard = runCatching { SceneGuardSettings.load(ctx) }.getOrDefault(true),
            recognition = runCatching { RecognitionSettings.load(ctx) }.getOrDefault(RecognitionTuning()),
        )
    }

    /**
     * 更新动作可视化配置（写本机 SharedPreferences + 立即刷新 UI 状态）。
     * RunnerService 在每个动作派发前重新读取同一份配置，因此运行中的开关
     * 变更会在下一个动作生效；总开关关闭时脚本时序与 v2.3.0 完全一致。
     */
    fun setVisual(next: VisualConfig) {
        runCatching { VisualSettings.save(ctx, next) }
        _state.update { it.copy(visual = next) }
    }

    /** 回放界面校验开关（v2.5.0）：下一次运行生效，不影响正在执行的脚本。 */
    fun setSceneGuard(enabled: Boolean) {
        runCatching { SceneGuardSettings.save(ctx, enabled) }
        _state.update { it.copy(sceneGuard = enabled) }
    }

    /** 识别调度调参（v2.7.0）：下一次运行由 RunnerService 读取生效。 */
    fun setRecognition(next: RecognitionTuning) {
        runCatching { RecognitionSettings.save(ctx, next) }
        _state.update { it.copy(recognition = next) }
    }

    private fun notificationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun batteryUnrestricted(): Boolean = runCatching {
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }.getOrDefault(false)

    private fun versionName(): String = runCatching {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        info.versionName.orEmpty()
    }.getOrDefault("")

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
    }

    // ------------------------------------------------------------ 导入

    fun import(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = ctx.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()
                    if (raw.isBlank()) return@runCatching "导入失败：文件为空或无法读取"
                    val (res, fail) = Importer.import(raw, Script.SCHEMA_VERSION)
                    if (res == null) {
                        "导入失败：${fail?.reason ?: "未知原因"}"
                    } else {
                        store.save(res.script)
                        RunLog.i("Import", "导入成功「${res.script.name}」${res.script.totalActionCount()} 步${res.note}")
                        "已导入「${res.script.name}」${res.script.totalActionCount()} 步"
                    }
                }.getOrElse { e ->
                    RunLog.e("Import", "读取失败：${e.message}")
                    "读取文件失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
            refresh()
            withContext(Dispatchers.Main) { onDone(message) }
        }
    }

    // ------------------------------------------------------------ 运行

    fun attemptRun(name: String): RunAttempt {
        val script = runCatching { store.load(name) }.getOrNull()
            ?: return RunAttempt.Failed("脚本读取失败（文件可能已损坏）")
        if (!AutoClickService.isEnabled(ctx)) return RunAttempt.Failed("请先开启无障碍服务")
        if (!Settings.canDrawOverlays(ctx)) return RunAttempt.Failed("请先授予悬浮窗权限")
        if (ScriptGrants.needsConsent(script) && grants.effective(script.name) == ScriptGrant.ASK) {
            return RunAttempt.NeedConsent(script)
        }
        return startRun(script)
    }

    fun startAfterConsent(script: Script, grant: ScriptGrant): RunAttempt {
        when (grant) {
            ScriptGrant.ALLOW_ONCE -> grants.grantOnce(script.name, ScriptGrant.ALLOW_ONCE)
            ScriptGrant.ALLOW_ALWAYS -> grants.persist(script.name, ScriptGrant.ALLOW_ALWAYS)
            ScriptGrant.DENY_ONCE -> {
                grants.grantOnce(script.name, ScriptGrant.DENY_ONCE)
                return RunAttempt.Failed("已拒绝运行（本次运行内不再提示）")
            }
            ScriptGrant.DENY_ALWAYS -> {
                grants.persist(script.name, ScriptGrant.DENY_ALWAYS)
                return RunAttempt.Failed("已拒绝运行（以后不再提示）")
            }
            ScriptGrant.ASK -> Unit
        }
        return startRun(script)
    }

    private fun startRun(script: Script): RunAttempt {
        return try {
            // 重新入库：脚本可能来自导入后未落盘的路径，服务端按名加载
            runCatching { store.save(script) }
            RunnerService.startByName(ctx, script.name)
            RunLog.i("Main", "启动运行服务：「${script.name}」")
            RunAttempt.Started(script.name)
        } catch (e: Exception) {
            RunLog.e("Main", "启动服务失败：${e.javaClass.simpleName}: ${e.message}")
            RunAttempt.Failed("启动运行服务失败：${e.javaClass.simpleName}")
        }
    }

    fun stopRunning() {
        runCatching {
            ctx.startService(
                Intent(ctx, RunnerService::class.java).setAction(RunnerService.ACTION_STOP),
            )
        }
    }

    // ------------------------------------------------------------ 录制

    fun startRecording(): String? {
        if (!AutoClickService.isEnabled(ctx)) return "请先开启无障碍服务"
        return try {
            RecordingService.start(ctx)
            null
        } catch (e: Exception) {
            RunLog.e("Main", "启动录制失败：${e.javaClass.simpleName}: ${e.message}")
            "启动录制失败：${e.javaClass.simpleName}"
        }
    }

    fun stopRecording() = RecordingService.stop(ctx)

    // ------------------------------------------------------------ 脚本库操作

    fun deleteScript(name: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val deleted = store.delete(name)
                if (deleted) repo.purge(name)
                deleted
            }
            if (ok) RunLog.i("Store", "删除脚本「$name」及其历史版本")
            refresh()
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** 历史版本（新→旧），不含当前版本。 */
    fun scriptVersions(name: String): List<ScriptRepository.Version> =
        runCatching { repo.versions(name) }.getOrDefault(emptyList())

    /** 把某个历史版本恢复为当前脚本（会再存一次、修订号继续递增，不覆盖历史）。 */
    fun restoreScript(name: String, revision: Int): String? {
        val old = runCatching { repo.restore(name, revision) }.getOrNull()
            ?: return "历史版本 r$revision 不存在或已损坏"
        return saveScript(old.copy(name = name))
    }

    /** 编辑器：按名加载脚本全文（找不到返回 null）。 */
    fun loadScript(name: String): Script? =
        runCatching { store.load(name) }.getOrNull()

    /**
     * 编辑器 / 视频导入：保存（新建或更新）脚本并刷新列表。返回 null=成功，否则为错误文案。
     * v2.7.0 走 [ScriptRepository]：每次保存自动递增 `revision`、把上一版压进
     * `.history/`，误删动作或自动覆盖后可回滚；老脚本首次保存从 r2 起，
     * 读取侧完全兼容，无需迁移。
     */
    fun saveScript(script: Script): String? {
        return try {
            val saved = repo.save(script)
            RunLog.i(
                "Store",
                "保存脚本「${saved.name}」r${saved.revision}（${saved.actions.size} 个顶层动作，上一版已入历史）",
            )
            refresh()
            null
        } catch (e: Exception) {
            "保存失败：${e.javaClass.simpleName}"
        }
    }

    /** 把脚本明文 JSON 经系统分享面板导出（无网络权限，不经过任何服务器）。 */
    fun shareScript(name: String): String? {
        val item = runCatching { store.list().firstOrNull { it.name == name } }.getOrNull()
            ?: return "脚本不存在"
        return try {
            val uri = FileProvider.getUriForFile(ctx, "dev.nymph.auto.fileprovider", item.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(
                Intent.createChooser(send, "导出脚本（明文 JSON）")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        } catch (e: Exception) {
            "导出失败：${e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------ 日志

    fun logTail(lines: Int = 800): List<String> = runCatching { LogStore.tail(lines) }
        .getOrDefault(emptyList())

    fun clearLogs() {
        runCatching { LogStore.clear() }
        RunLog.i("Log", "日志已清空")
        refresh()
    }

    fun shareLogs(): String? = runCatching {
        val text = buildString {
            appendLine("== NymphAuto 运行日志 ==")
            append(LogStore.exportText().ifEmpty { "（暂无日志）" })
            LogStore.readCrash()?.let {
                appendLine().appendLine().appendLine(it)
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NymphAuto 运行日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(
            Intent.createChooser(send, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    }.getOrElse { "分享失败：${it.javaClass.simpleName}" }

    /** 调试包：logs/ + debug/（失败现场截图）打成 zip，供离线排查。 */
    fun exportBundle(): String? {
        val zip = runCatching { buildBundle() }.getOrNull()
            ?: return "调试包生成失败（无日志或无截图）"
        return try {
            val uri = FileProvider.getUriForFile(ctx, "dev.nymph.auto.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, "NymphAuto 调试包")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(
                Intent.createChooser(send, "发送调试包（含失败截图）")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        } catch (e: Exception) {
            "发送失败：${e.javaClass.simpleName}"
        }
    }

    private fun buildBundle(): File? {
        val logsDir = File(ctx.filesDir, "logs")
        val debugDir = File(ctx.filesDir, "debug")
        val inputs = buildList {
            logsDir.listFiles()?.forEach { add(it) }
            debugDir.listFiles()?.forEach { add(it) }
        }
        if (inputs.isEmpty()) return null
        val outDir = File(ctx.cacheDir, "bundles").apply { mkdirs() }
        val out = File(outDir, "nymphauto-debug-${System.currentTimeMillis()}.zip")
        // 显式指定 UTF-8：默认构造虽按 UTF-8 编码条目名，却不置 general
        // purpose bit 11（EFS），Windows 资源管理器和部分文件管理器会按
        // 本地编码（GBK）解读，导致中文文件名（如失败截图标签）乱码。
        ZipOutputStream(out.outputStream().buffered(), Charsets.UTF_8).use { zos ->
            inputs.forEach { f ->
                runCatching {
                    val prefix = if (f.parentFile?.name == "debug") "debug" else "logs"
                    zos.putNextEntry(ZipEntry("$prefix/${f.name}"))
                    f.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        outDir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(3)
            ?.forEach { runCatching { it.delete() } }
        return out
    }

    fun clearCrash() {
        LogStore.clearCrash()
        refresh()
    }
}

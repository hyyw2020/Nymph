// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nymph.auto.core.editor.ActionEditGuard
import dev.nymph.auto.core.editor.RoiApplier
import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.LoopPolicy
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.vision.PickerGeometry
import dev.nymph.auto.ui.CoordinatePicker
import dev.nymph.auto.ui.PickerMode
import dev.nymph.auto.ui.PickerRegion
import dev.nymph.auto.ui.PickerResult
import dev.nymph.auto.ui.compose.AppViewModel

/**
 * 脚本动作编辑器（v2.7.0）。
 *
 * ## 修复的 v2.6.0 严重 bug
 *
 * `kindOf()` 对非 4 种简易类型返回 0，打开一个 WAIT / RUN_ACTIONS /
 * CLICK_IMAGE 动作会被当成「点击」；确认时用 `Action(...)` 重新构造，
 * `children` / `listeners` / `condition` / `imageData` / `jsCode` 等字段
 * 被**静默清空**。录制或导入的复杂脚本一编辑就残废。
 *
 * 本版：
 *  - 非简易类型改为**只读提示**，引导到「源码」视图，确定按钮禁用，
 *    绝不把未知类型伪装成点击（[ActionEditGuard.kindOf] 返回 -1）；
 *  - 简易类型一律走 [ActionEditGuard.apply]（基于 `copy`），
 *    编辑坐标不会丢任何其它字段；
 *  - 新增**步骤 / 源码双视图**，源码视图覆盖全部 30 种动作、目标、条件、
 *    变量、子脚本，带实时 JSON 语法校验；
 *  - 新增**撤销 / 重做**（60 步）与**框选 ROI**（[PickerMode.REGION]）。
 *
 * 主题沿用 Material3 深色风格；坐标支持手输与「屏幕拾取」（非模态穿透浮层）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    initial: Script,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onSave: (Script) -> Unit,
    /** v2.8.0：把当前脚本切到系统级悬浮编辑器（可浮在其他 APP 上）。 */
    onFloatEdit: (String) -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    val actions = remember { mutableStateListOf<Action>().apply { addAll(initial.actions) } }
    var editingIndex by remember { mutableIntStateOf(-1) } // -2 = 新增，-1 = 关闭
    var draft by remember { mutableStateOf<Action?>(null) }

    var sourceMode by remember { mutableStateOf(false) }
    var sourceText by remember { mutableStateOf(ScriptCodec.encode(initial)) }
    var sourceError by remember { mutableStateOf<String?>(null) }

    // v2.9.2：脚本级循环次数入口。引擎早支持 repeatCount（0=无限），
    // 但此前只能改源码视图手写 JSON，用户想让重复操作循环跑无处可设。
    var loopDialog by remember { mutableStateOf(false) }
    var loopInput by remember { mutableStateOf(if (initial.repeatCount == 0) "无限" else initial.repeatCount.toString()) }
    var loopError by remember { mutableStateOf<String?>(null) }

    /**
     * v2.7.1：脚本级非 actions 字段（listeners / abortCondition / variables 等）
     * 的编辑底座。ROI 应用会改动 listeners 里的找图动作与中止条件，若仍以
     * [initial] 为底会被 buildScript() 丢掉，故单独承载。
     */
    var scriptBase by remember { mutableStateOf(initial) }

    // v2.7.1：历史栈升级为完整 Script 快照。ROI 应用会改脚本级 listeners /
    // abortCondition，只存 List<Action> 的话撤销回退不了脚本级字段。
    val undoStack = remember { ArrayList<Script>() }
    val redoStack = remember { ArrayList<Script>() }
    // 仅用于让 TopAppBar 的撤销/重做 enabled 状态随堆栈变化重算
    var historyVer by remember { mutableIntStateOf(0) }

    fun buildScript(): Script {
        val w = context.realWidthPx()
        val h = context.realHeightPx()
        return scriptBase.copy(
            name = name.ifBlank { "未命名脚本" },
            actions = actions.toList(),
            refWidth = if (scriptBase.refWidth > 0) scriptBase.refWidth else w,
            refHeight = if (scriptBase.refHeight > 0) scriptBase.refHeight else h,
        )
    }

    /** 从完整快照恢复：actions 列表 + name + 脚本级 base。 */
    fun restore(s: Script) {
        actions.clear(); actions.addAll(s.actions)
        name = s.name
        scriptBase = s.copy(actions = emptyList())
    }

    fun pushUndo() {
        undoStack.add(buildScript())
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
        historyVer++
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(buildScript())
        restore(undoStack.removeAt(undoStack.size - 1))
        historyVer++
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(buildScript())
        restore(redoStack.removeAt(redoStack.size - 1))
        historyVer++
    }

    /**
     * v2.7.1：把框选出的 ROI 真正作用到脚本内所有读取 region 的位置
     * （找图/找色/找文字/AI/节点动作、条件、children、listeners、中止条件）。
     * 用 RoiApplier 作唯一真源，pushUndo 后可完整撤销；返回命中数供 UI 如实反馈。
     */
    fun applyRoiToScript(region: Region): Int {
        val (next, hits) = RoiApplier.apply(buildScript(), region)
        if (hits <= 0) return 0
        pushUndo()
        restore(next)
        return hits
    }

    // ------------------------------------------------------------ 源码视图
    if (sourceMode) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("源码视图") },
                navigationIcon = {
                    IconButton(onClick = { sourceMode = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "返回步骤视图")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val parsed = ScriptCodec.decode(sourceText)
                        if (parsed == null) {
                            sourceError = "JSON 无法解析或字段类型不匹配，未应用"
                            return@TextButton
                        }
                        pushUndo()
                        actions.clear(); actions.addAll(parsed.actions)
                        name = parsed.name
                        // v2.7.1：源码视图改的脚本级字段（variables / listeners /
                        // abortCondition / revision 等）一并保留，避免「应用」后又以
                        // initial 为底把它们丢掉（actions 真源仍是上面的列表）。
                        scriptBase = parsed.copy(actions = emptyList())
                        sourceError = null
                        sourceMode = false
                    }) { Text("应用") }
                },
            )
            Text(
                "支持全部 30 种动作、图片/文字/颜色/节点/AI 目标、条件、变量、JS、子脚本。" +
                    "改完点「应用」回到步骤视图；解析失败会在此提示，绝不写坏脚本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (sourceError != null) {
                Text(
                    "⚠ $sourceError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            OutlinedTextField(
                value = sourceText,
                onValueChange = {
                    sourceText = it
                    sourceError = if (ScriptCodec.decode(it) == null) "JSON 语法错误" else null
                },
                label = { Text("脚本 JSON · 实时语法校验") },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            )
        }
        return
    }

    // ------------------------------------------------------------ 步骤视图
    Column(Modifier.fillMaxSize()) {
        // v2.9.7：5 个 TextButton 的默认 12dp 左右内边距把标题挤成「编/辑/动/作」
        // 竖排四行（真机截图可证）。标题强制单行 + actions 收紧内边距让出宽度，
        // 按钮数量与功能一个不变。
        TopAppBar(
            title = { Text("编辑动作", maxLines = 1, softWrap = false) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "返回")
                }
            },
            actions = {
                val tight = PaddingValues(horizontal = 4.dp)
                TextButton(
                    onClick = ::undo,
                    enabled = historyVer >= 0 && undoStack.isNotEmpty(),
                    contentPadding = tight,
                ) { Text("撤销") }
                TextButton(
                    onClick = ::redo,
                    enabled = historyVer >= 0 && redoStack.isNotEmpty(),
                    contentPadding = tight,
                ) { Text("重做") }
                TextButton(
                    onClick = { sourceText = ScriptCodec.encode(buildScript()); sourceMode = true },
                    contentPadding = tight,
                ) { Text("源码") }
                // v2.8.0：先落盘再切系统悬浮窗，保证悬浮编辑器读到的是最新稿件
                TextButton(
                    onClick = {
                        val s = buildScript()
                        onSave(s)
                        onFloatEdit(s.name)
                    },
                    contentPadding = tight,
                ) { Text("悬浮") }
                TextButton(
                    onClick = { onSave(buildScript()) },
                    contentPadding = tight,
                ) { Text("保存") }
            },
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            label = { Text("脚本名") },
            singleLine = true,
        )

        // v2.9.2：循环次数入口。重复扫荡 / 连续领取这类操作需要「跑 N 轮或无限」，
        // 此前只能去源码视图手写 "repeatCount":0。这里显式可见、可撤销。
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            onClick = {
                loopInput = if (scriptBase.repeatCount == 0) "无限" else scriptBase.repeatCount.toString()
                loopError = null
                loopDialog = true
            },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "循环：${LoopPolicy.label(scriptBase.repeatCount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "点此修改 ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        if (loopDialog) {
            AlertDialog(
                onDismissRequest = { loopDialog = false },
                title = { Text("脚本循环次数") },
                text = {
                    Column {
                        Text(
                            "游戏 / 自绘界面里的「扫荡 → 结算 → 领取 → 再扫荡」这类重复操作，" +
                                "推荐用「视频导入」生成脚本：默认每个动作会等到对应画面出现再点，" +
                                "不猜动画时长。手动建的找图动作默认只有 2 秒重试窗口，" +
                                "有十秒级转场动画时请在视频导入里勾选触点生成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = loopInput,
                            onValueChange = { loopInput = it; loopError = null },
                            label = { Text("次数（填「无限」或 0 = 无限循环）") },
                            singleLine = true,
                            isError = loopError != null,
                        )
                        if (loopError != null) {
                            Text(
                                loopError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = false,
                                onClick = { loopInput = "1"; loopError = null },
                                label = { Text("1 次") },
                            )
                            FilterChip(
                                selected = false,
                                onClick = { loopInput = "10"; loopError = null },
                                label = { Text("10 次") },
                            )
                            FilterChip(
                                selected = false,
                                onClick = { loopInput = "无限"; loopError = null },
                                label = { Text("无限") },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsed = LoopPolicy.parse(loopInput)
                        if (parsed == null) {
                            loopError = "请输入正整数，或填「无限」/「0」表示无限循环"
                        } else {
                            pushUndo()
                            scriptBase = scriptBase.copy(repeatCount = parsed)
                            loopDialog = false
                        }
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { loopDialog = false }) { Text("取消") } },
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            onClick = { pushUndo(); draft = Action(); editingIndex = -2 },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    "添加动作（点击 / 长按 / 双击 / 滑动）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        if (actions.isEmpty()) {
            Text(
                "脚本为空，先添加一个动作。\n\n拾取坐标：点「屏幕拾取」→ 拖动手柄到目标位置 → 确认；" +
                    "手柄之外的区域仍可正常操作其他 APP。\n复杂动作请切到「源码」视图编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(actions) { i, a ->
                ActionItemRow(
                    index = i,
                    total = actions.size,
                    action = a,
                    onEdit = { draft = a; editingIndex = i },
                    onDelete = { pushUndo(); actions.removeAt(i) },
                    onUp = {
                        if (i > 0) {
                            pushUndo()
                            actions.add(i - 1, actions.removeAt(i))
                        }
                    },
                    onDown = {
                        if (i < actions.size - 1) {
                            pushUndo()
                            actions.add(i + 1, actions.removeAt(i))
                        }
                    },
                )
            }
        }
    }

    draft?.let { d ->
        ActionEditDialog(
            initial = d,
            roiTargetCount = RoiApplier.countTargets(buildScript()),
            onApplyRoiToScript = ::applyRoiToScript,
            onDismiss = { draft = null; editingIndex = -1 },
            onConfirm = { next ->
                pushUndo()
                when {
                    editingIndex == -2 -> actions.add(next)
                    editingIndex >= 0 -> actions[editingIndex] = next
                }
                draft = null
                editingIndex = -1
            },
        )
    }
}

@Composable
private fun ActionItemRow(
    index: Int,
    total: Int,
    action: Action,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
    ) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}. ${typeName(action.type)}" +
                        if (!ActionEditGuard.supportsSimpleEdit(action)) "（只读）" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onUp, enabled = index > 0, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDown, enabled = index < total - 1, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                describe(action),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionEditDialog(
    initial: Action,
    roiTargetCount: Int,
    onApplyRoiToScript: (Region) -> Int,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val supported = ActionEditGuard.supportsSimpleEdit(initial)

    var kind by remember { mutableStateOf(ActionEditGuard.kindOf(initial)) }
    var x by remember { mutableStateOf(fmt(initial.x)) }
    var y by remember { mutableStateOf(fmt(initial.y)) }
    var x2 by remember { mutableStateOf(fmt(initial.x2)) }
    var y2 by remember { mutableStateOf(fmt(initial.y2)) }
    var holdMs by remember { mutableStateOf(if (initial.holdMs > 0) initial.holdMs.toString() else "100") }
    var durationMs by remember { mutableStateOf(if (initial.durationMs > 0) initial.durationMs.toString() else "400") }
    var times by remember { mutableStateOf(maxOf(1, initial.times).toString()) }
    var waitMs by remember { mutableStateOf(initial.waitMs.toString()) }
    var desc by remember { mutableStateOf(initial.desc) }
    // v2.7.1：已有显式 region 的动作打开时要算「已框选」，否则显示成未框选且重存会丢
    var pickedRegion by remember {
        mutableStateOf<Region?>(if (RoiApplier.hasExplicitRegion(initial.region)) initial.region else null)
    }
    var roiMessage by remember { mutableStateOf("") }
    // v2.8.0：高级参数默认折叠，避免对话框铺满整屏（差评「太大了」）
    var showMore by remember { mutableStateOf(false) }

    // 拾取目标：0=起点，1=滑动终点；-1 = 无待回填。框选用 pendingRegion 单列。
    var pickTarget by remember { mutableIntStateOf(-1) }
    var pendingRegion by remember { mutableStateOf(false) }
    // 记录 show 前的结果序号：ON_RESUME 只在序号变大时补填，杜绝陈旧/重复回填
    var seqBefore by remember { mutableIntStateOf(CoordinatePicker.resultSeq) }

    /** 空串（fmt(0)==""）与非法输入一律收敛为 0，绝不静默 return 让确定按钮失灵。 */
    fun num(s: String, fallback: Double = 0.0): Double = s.toDoubleOrNull() ?: fallback

    fun applyPoint(r: PickerResult) {
        // Locale.US 强制小数点，de/fr 区不会回填 "47,4074" 导致解析失败
        val sx = PickerGeometry.fmt4(r.xPct)
        val sy = PickerGeometry.fmt4(r.yPct)
        if (pickTarget == 0) { x = sx; y = sy } else { x2 = sx; y2 = sy }
    }

    fun applyRegion(r: PickerRegion) {
        // 数值四元组 + expr 表达式一起写入，运行时 RoiExpr 优先按 expr 解析
        pickedRegion = Region(r.leftPct, r.topPct, r.rightPct, r.bottomPct, r.toSpec())
        roiMessage = ""
    }

    fun startPick(target: Int) {
        pickTarget = target
        pendingRegion = false
        seqBefore = CoordinatePicker.resultSeq
        val initPct = num(if (target == 0) x else x2) to num(if (target == 0) y else y2)
        val ok = CoordinatePicker.show(
            context,
            initialPct = initPct,
            snap = dev.nymph.auto.ui.NodeSnapProvider(),
            onPicked = { r -> applyPoint(r); pickTarget = -1 },
        )
        if (!ok) { pickTarget = -1; roiMessage = "无悬浮窗权限，无法拾取（可手输坐标）" }
    }

    fun startRegionPick() {
        pickTarget = -1
        pendingRegion = true
        seqBefore = CoordinatePicker.resultSeq
        val ok = CoordinatePicker.show(
            context,
            mode = PickerMode.REGION,
            onRegionPicked = { r -> applyRegion(r); pendingRegion = false },
        )
        if (!ok) { pendingRegion = false; roiMessage = "无悬浮窗权限，无法框选" }
    }

    // v2.7.1 关键修复：key 恒为 Unit，只注册一次。
    // v2.7.0 用会自增的 pickStamp 作 key，每次 startPick 触发重组 → 旧 effect 的
    // onDispose 执行 dismiss()，把刚 show 的浮层与回调一起清掉（浮层一闪就没）。
    // 现在只有对话框真正销毁时才 dismiss，拾取过程中绝不误关。
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            // 兜底：配置变更/进程重建导致即时回调丢失时，回到前台用最近结果补填一次；
            // 用 resultSeq 严格判断「本次 show 之后才产生的新结果」。
            if (e == Lifecycle.Event.ON_RESUME && CoordinatePicker.resultSeq > seqBefore) {
                if (pendingRegion) {
                    CoordinatePicker.lastRegion?.let { applyRegion(it); pendingRegion = false }
                } else if (pickTarget >= 0) {
                    CoordinatePicker.lastResult?.let { applyPoint(it); pickTarget = -1 }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            CoordinatePicker.dismiss() // 对话框销毁才清理残留浮层，避免污染截图
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // v2.8.0：限制对话框最大宽度，避免在大屏 / 平板上铺满整屏
        modifier = Modifier.widthIn(max = 340.dp),
        title = { Text(if (supported) "编辑动作" else "只读动作") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!supported) {
                    Text(
                        "「${typeName(initial.type)}」不在简易编辑器支持范围（仅点击/长按/双击/滑动）。" +
                            "为避免静默改动作或丢失 children / listeners / 条件，此处只读；" +
                            "请返回后点「源码」编辑完整 JSON。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        describe(initial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = kind == 0, onClick = { kind = 0 }, label = { Text("点击") })
                        FilterChip(selected = kind == 1, onClick = { kind = 1 }, label = { Text("长按") })
                        FilterChip(selected = kind == 2, onClick = { kind = 2 }, label = { Text("双击") })
                        FilterChip(selected = kind == 3, onClick = { kind = 3 }, label = { Text("滑动") })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "坐标（屏幕百分比，可手输或拾取）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = x, onValueChange = { x = it },
                            label = { Text("X %") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = y, onValueChange = { y = it },
                            label = { Text("Y %") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { startPick(0) }) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("屏幕拾取起点（非模态）")
                        }
                    }
                    TextButton(onClick = { startRegionPick() }) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (pickedRegion == null) "框选搜索区域 ROI / 模板范围"
                            else "已框选 ${PickerGeometry.fmt4(pickedRegion!!.leftPct)},${PickerGeometry.fmt4(pickedRegion!!.topPct)}" +
                                " ~ ${PickerGeometry.fmt4(pickedRegion!!.rightPct)},${PickerGeometry.fmt4(pickedRegion!!.bottomPct)} % · 点击重选",
                        )
                    }
                    if (pickedRegion != null) {
                        Text(
                            "ROI 表达式：${
                                pickedRegion!!.expr.ifBlank {
                                    listOf(
                                        pickedRegion!!.leftPct, pickedRegion!!.topPct,
                                        pickedRegion!!.rightPct, pickedRegion!!.bottomPct,
                                    ).joinToString(",") { PickerGeometry.fmt4(it) }
                                }
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "搜索区域只对「找图 / 找色 / 找文字 / AI / 节点」动作与条件生效；" +
                                "纯坐标点击动作不读取区域。点下方按钮把该 ROI 应用到脚本内这些目标，" +
                                "或到「源码」视图把它填进目标动作的 region。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            val hits = onApplyRoiToScript(pickedRegion!!)
                            roiMessage = if (hits > 0) "✓ 已应用到脚本内 $hits 处找图/找色目标（可撤销）"
                            else "脚本内暂无找图/找色目标（$roiTargetCount 处）：请先在源码视图添加，" +
                                "或把某动作改为找图类。本动作的 region 仍会保存。"
                        }) {
                            Text("应用 ROI 到脚本内目标（$roiTargetCount 处）")
                        }
                    }
                    if (roiMessage.isNotEmpty()) {
                        Text(
                            roiMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (roiMessage.startsWith("✓")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (kind == 3) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = x2, onValueChange = { x2 = it },
                                label = { Text("终点 X %") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = y2, onValueChange = { y2 = it },
                                label = { Text("终点 Y %") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TextButton(onClick = { startPick(1) }) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("屏幕拾取终点")
                        }
                        OutlinedTextField(
                            value = durationMs, onValueChange = { durationMs = it },
                            label = { Text("滑动时长（毫秒）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // v2.8.0：高级参数默认折叠 + 限宽，回应「界面太大」差评。
                    // 常用项（类型 / 坐标 / 拾取 / 框选）保持可见，其余按需展开。
                    TextButton(onClick = { showMore = !showMore }) {
                        Text(if (showMore) "收起高级参数 ▲" else "更多参数（时长 / 次数 / 等待 / 描述）▼")
                    }
                    if (showMore) {
                        if (kind == 0 || kind == 1) {
                            OutlinedTextField(
                                value = holdMs, onValueChange = { holdMs = it },
                                label = { Text(if (kind == 1) "按下时间（毫秒，长按建议 800+）" else "按下时间（毫秒）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = times, onValueChange = { times = it },
                                label = { Text("重复次数") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = waitMs, onValueChange = { waitMs = it },
                                label = { Text("执行前等待 ms") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = desc, onValueChange = { desc = it },
                            label = { Text("动作描述（可选）") }, singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = supported,
                onClick = {
                    // 空串（fmt(0)==""）/非法输入收敛为 0，绝不静默 return 让按钮失灵
                    val dx = num(x)
                    val dy = num(y)
                    val patch = ActionEditGuard.Patch(
                        kind = kind,
                        x = dx,
                        y = dy,
                        x2 = num(x2, dx),
                        y2 = num(y2, dy),
                        holdMs = holdMs.toLongOrNull() ?: 100L,
                        durationMs = durationMs.toLongOrNull() ?: 400L,
                        times = times.toIntOrNull() ?: 1,
                        waitMs = waitMs.toLongOrNull() ?: 0L,
                        desc = desc,
                    )
                    // 基于 copy 保真：children/listeners/condition/imageData 等全部继承
                    val next = ActionEditGuard.apply(initial, patch)
                        .let { if (pickedRegion != null) it.copy(region = pickedRegion!!) else it }
                    onConfirm(next)
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 委托给纯 JVM 守卫：非简易类型返回 -1，不再静默映射为点击。 */
internal fun kindOf(a: Action): Int = ActionEditGuard.kindOf(a)

private fun typeName(t: ActionType): String = when (t) {
    ActionType.TAP -> "点击"
    ActionType.LONG_PRESS -> "长按"
    ActionType.MULTI_TAP -> "多击"
    ActionType.SWIPE -> "滑动"
    ActionType.CURVE -> "曲线滑动"
    ActionType.REPLAY -> "手势回放"
    ActionType.PINCH_OUT -> "双指放大"
    ActionType.PINCH_IN -> "双指缩小"
    ActionType.MULTI_FINGER -> "多指手势"
    ActionType.CLICK_IMAGE -> "点击图片"
    ActionType.CLICK_TEXT -> "点击文字"
    ActionType.CLICK_COLOR -> "点击颜色"
    ActionType.CLICK_NODE -> "点击节点"
    ActionType.CLICK_AI -> "AI 点击"
    ActionType.KEY -> "按键"
    ActionType.INPUT -> "输入文字"
    ActionType.OPEN_APP -> "打开应用"
    ActionType.OPEN_LINK -> "打开链接"
    ActionType.CLOSE_APP -> "关闭应用"
    ActionType.RUN_ACTIONS -> "运行动作组"
    ActionType.RUN_SCRIPT -> "运行子脚本"
    ActionType.RUN_JS -> "执行 JS"
    ActionType.WAIT -> "等待"
    ActionType.GOTO -> "跳转"
    ActionType.STOP -> "停止"
    ActionType.SET_VAR -> "设置变量"
    ActionType.CAPTURE_TO_VAR -> "截取到变量"
    ActionType.TOAST -> "提示"
    ActionType.VAR_DIALOG -> "变量弹窗"
}

internal fun fmt(v: Double): String =
    if (v == 0.0) "" else if (v == v.toLong().toDouble()) v.toLong().toString() else PickerGeometry.fmt4(v)

internal fun describe(a: Action): String {
    val coord = when (a.type) {
        ActionType.SWIPE ->
            "(${fmtPct(a.x)},${fmtPct(a.y)}) → (${fmtPct(a.x2)},${fmtPct(a.y2)}) ${a.durationMs}ms"
        ActionType.WAIT -> "${a.waitMs}ms"
        ActionType.OPEN_APP, ActionType.OPEN_LINK -> a.packageName.ifBlank { a.linkUrl }
        ActionType.INPUT -> a.text
        ActionType.RUN_JS -> "JS"
        else -> "(${fmtPct(a.x)},${fmtPct(a.y)}) 按下 ${a.holdMs}ms" +
            (if (a.times > 1) " ×${a.times}" else "")
    }
    val extra = buildList {
        if (a.times > 1) add("重复 ${a.times}")
        if (a.waitMs > 0) add("等待 ${a.waitMs}ms")
        if (a.children.isNotEmpty()) add("子动作 ${a.children.size}")
        if (a.listeners.all().isNotEmpty()) add("监听 ${a.listeners.all().size}")
    }
    return coord + (if (extra.isNotEmpty()) " · " + extra.joinToString(" · ") else "") +
        (if (a.desc.isNotBlank()) " · ${a.desc}" else "")
}

internal fun fmtPct(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else PickerGeometry.fmt2(v)

/** 全屏像素宽高（含状态栏，与截图帧同源）。 */
internal fun android.content.Context.realWidthPx(): Int = screenBounds().width()

internal fun android.content.Context.realHeightPx(): Int = screenBounds().height()

private fun android.content.Context.screenBounds(): android.graphics.Rect {
    val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    return if (android.os.Build.VERSION.SDK_INT >= 30) {
        wm.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let {
            android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels)
        }
    }
}

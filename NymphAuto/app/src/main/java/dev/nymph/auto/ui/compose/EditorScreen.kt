// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nymph.auto.core.editor.ActionEditGuard
import dev.nymph.auto.core.editor.RoiApplier
import dev.nymph.auto.core.importer.ScriptCodec
import dev.nymph.auto.core.design.SceneCanvas
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.LoopPolicy
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.model.TriggerMode
import dev.nymph.auto.core.vision.PickerGeometry
import dev.nymph.auto.ui.CoordinatePicker
import dev.nymph.auto.ui.PickerMode
import dev.nymph.auto.ui.PickerRegion
import dev.nymph.auto.ui.PickerResult
import dev.nymph.auto.ui.compose.AppViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    // v3.0.1：图片特征截取对话框（框选→原画质裁剪→存脚本→缩略图可查看）
    var showFeatureCapture by remember { mutableStateOf(false) }
    // v3.0.2：场景画布编辑器（抓原画质整帧当画布 → 画布上框特征/限制区/落点 → 随脚本留存可再编辑）
    var showSceneCanvas by remember { mutableStateOf(false) }

    var sourceMode by remember { mutableStateOf(false) }
    var sourceText by remember { mutableStateOf(ScriptCodec.encode(initial)) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    // A 减负：撤销/重做/源码/悬浮收进溢出菜单，顶栏只留保存主动作。
    var moreExpanded by remember { mutableStateOf(false) }

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
     * 用 RoiApplier 作唯一真源，pushUndo 后可完整撤销；返回命中数供 UI 反馈实际值。
     */
    fun applyRoiToScript(region: Region): Int {
        val (next, hits) = RoiApplier.apply(buildScript(), region)
        if (hits <= 0) return 0
        pushUndo()
        restore(next)
        return hits
    }

    /**
     * v2.9.49：多特征框落盘——N 个框按遍历序写到脚本内 N 个真正读取 region 的
     * 目标，每框的相似度写入对应目标的 imageSimilarity（唯一真源 RoiApplier.applyMulti）。
     * 旧单框行为由 [applyRoiToScript] 保持零回归，本函数仅供多框会话调用。
     */
    fun applyRoiBoxes(boxes: List<dev.nymph.auto.core.editor.FeatureBox>): Int {
        val (next, hits) = RoiApplier.applyMulti(buildScript(), boxes)
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
                        Icon(Icons.Filled.Close, contentDescription = "返回场景视图")
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
                "支持全部 30 类场景、图片/文字/颜色/节点/AI 目标、条件、变量、JS、子脚本。" +
                    "改完点「应用」回到场景视图；解析失败会在此提示，绝不写坏脚本。",
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

    // ADR-075：单一工作台——与源码视图同构，early-return 独占整屏。
    // 修复「界面重合」：此前本分支与步骤视图同处一个 Composable 作用域，两个
    // Column(fillMaxSize) 被同时组合、层叠绘制，底层「未命名脚本/执行1次」等透出。
    // 采用 early-return 后，工作台与步骤视图互斥，任一时刻只组合其一。
    if (showSceneCanvas) {
        SceneCanvasEditorDialog(
            initial = scriptBase.canvas,
            initialAssets = scriptBase.assets,
            globalMethod = scriptBase.globalMethod,
            globalVerifySimilarity = scriptBase.globalVerifySimilarity,
            onDismiss = { showSceneCanvas = false },
            onConfirm = { canvas, updatedAssets, method, similarity ->
                pushUndo()
                // v3.0.4：画布仅作可视化编辑视图，保存时编译进现有 Action/sceneRule。
                // 编译 + 合并统一走 SceneCanvas.mergeInto（测试共用同一函数，杜绝门控绕过）。
                // N5 整图校验配置写回 Script 级字段（不进 canvas、不进运行链模型）。
                scriptBase = SceneCanvas.mergeInto(scriptBase, canvas, updatedAssets).copy(
                    globalMethod = method,
                    globalVerifySimilarity = similarity.coerceIn(1, 100),
                )
                showSceneCanvas = false
            },
        )
        return
    }

    // ------------------------------------------------------------ 步骤视图
    Column(Modifier.fillMaxSize()) {
        // v2.9.7：5 个 TextButton 的默认 12dp 左右内边距把标题挤成「编/辑/动/作」
        // 竖排四行（真机截图可证）。v2.9.7 加 maxLines=1 只把竖排变成横排截断——真机
        // 仍只剩一个「编」字（PTP-AN70 真机截图 2026-11-23 可证）。根因是 title 没有
        // 最小宽度保证，actions 把空间吃光。v2.9.49 真正修复：给 title 设 minWidth，
        // 让 Compose 先满足标题的 4 字宽度再把余量分给按钮；按钮字号/内边距同步收紧。
        // 按钮数量与功能一个不变。
        TopAppBar(
            title = {
                Text(
                    "编辑场景", maxLines = 1, softWrap = false,
                    fontSize = 17.sp,
                    modifier = Modifier.widthIn(min = 96.dp),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "返回")
                }
            },
            actions = {
                val tight = PaddingValues(horizontal = 2.dp)
                // 主动作：保存（唯一常驻 TextButton，标题宽度不再被 5 个按钮挤压）
                TextButton(
                    onClick = { onSave(buildScript()) },
                    contentPadding = tight,
                ) { Text("保存", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                // 次要动作收进溢出菜单：撤销 / 重做 / 源码 / 悬浮
                // onClick 与 enabled 逐字沿用原按钮，零行为变更。
                Box {
                    IconButton(onClick = { moreExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("撤销") },
                            onClick = { moreExpanded = false; undo() },
                            enabled = historyVer >= 0 && undoStack.isNotEmpty(),
                        )
                        DropdownMenuItem(
                            text = { Text("重做") },
                            onClick = { moreExpanded = false; redo() },
                            enabled = historyVer >= 0 && redoStack.isNotEmpty(),
                        )
                        DropdownMenuItem(
                            text = { Text("源码") },
                            onClick = {
                                moreExpanded = false
                                sourceText = ScriptCodec.encode(buildScript())
                                sourceMode = true
                            },
                        )
                        // v2.8.0：先落盘再切系统悬浮窗，保证悬浮编辑器读到的是最新稿件
                        DropdownMenuItem(
                            text = { Text("悬浮编辑") },
                            onClick = {
                                moreExpanded = false
                                val s = buildScript()
                                onSave(s)
                                onFloatEdit(s.name)
                            },
                        )
                    }
                }
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
        // v2.9.20：该字段仅作用于顺序范式；场景驱动为事件常驻，入口按范式显式
        // 声明作用域并禁用编辑，避免把不生效的数值呈现为当前脚本的有效配置。
        val loopScope = LoopPolicy.scopeOf(scriptBase.sceneDriven)
        val loopApplicable = LoopPolicy.appliesTo(loopScope)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            onClick = {
                if (loopApplicable) {
                    loopInput = if (scriptBase.repeatCount == 0) "无限" else scriptBase.repeatCount.toString()
                }
                loopError = null
                loopDialog = true
            },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "循环：${LoopPolicy.labelFor(scriptBase.repeatCount, loopScope)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (loopApplicable) "点此修改 ›" else "范式说明 ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        if (loopDialog) {
            AlertDialog(
                onDismissRequest = { loopDialog = false },
                title = { Text(if (loopApplicable) "脚本循环次数" else "循环次数作用域") },
                text = {
                    Column {
                        Text(
                            "游戏 / 自绘界面里的「扫荡 → 结算 → 领取 → 再扫荡」这类重复操作，" +
                                "推荐用「视频导入」生成脚本：默认每个场景会等到对应画面出现再点，" +
                                "不猜动画时长。手动建的找图场景默认只有 2 秒重试窗口，" +
                                "有十秒级转场动画时请在视频导入里勾选触点生成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!loopApplicable) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "当前脚本为场景驱动范式，按场景出现触发对应场景，事件循环常驻。" +
                                    LoopPolicy.sceneDrivenNotice() +
                                    "。该字段落盘值保持不变，仅供顺序范式使用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = loopInput,
                            onValueChange = { loopInput = it; loopError = null },
                            label = { Text("次数（填「无限」或 0 = 无限循环）") },
                            singleLine = true,
                            enabled = loopApplicable,
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
                                enabled = loopApplicable,
                                onClick = { loopInput = "1"; loopError = null },
                                label = { Text("1 次") },
                            )
                            FilterChip(
                                selected = false,
                                enabled = loopApplicable,
                                onClick = { loopInput = "10"; loopError = null },
                                label = { Text("10 次") },
                            )
                            FilterChip(
                                selected = false,
                                enabled = loopApplicable,
                                onClick = { loopInput = "无限"; loopError = null },
                                label = { Text("无限") },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (loopApplicable) {
                            val parsed = LoopPolicy.parse(loopInput)
                            if (parsed == null) {
                                loopError = "请输入正整数，或填「无限」/「0」表示无限循环"
                            } else {
                                pushUndo()
                                scriptBase = scriptBase.copy(repeatCount = parsed)
                                loopDialog = false
                            }
                        } else {
                            loopDialog = false
                        }
                    }) { Text(if (loopApplicable) "确定" else "知道了") }
                },
                dismissButton = { TextButton(onClick = { loopDialog = false }) { Text("取消") } },
            )
        }

        // ADR-075：三并列入口（添加场景 / 场景画布 / 添加图片特征）合并为单一工作台入口。
        // 三态（标点 / 框特征 / 整图门）全部在工作台内完成，不再有四个并列弹窗。
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            onClick = { showSceneCanvas = true },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (scriptBase.canvas?.hasBackground() == true) "场景工作台（原图与标注可再次编辑）"
                    else "场景工作台：抓原画质画面，标点 / 框特征 / 整图门",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        if (actions.isEmpty()) {
            Text(
                "脚本为空，先拾取坐标创建一个场景。\n\n拾取坐标：点「屏幕拾取」→ 拖动手柄到目标位置 → 确认；" +
                    "手柄之外的区域仍可正常操作其他 APP。\n复杂配置请切到「源码」视图编辑。",
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
            onApplyRoiBoxes = ::applyRoiBoxes,
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

    // ADR-075 D6：FeatureCaptureDialog 的调用已停用——「框选原画质 → 特征模板 + 相似度」
    // 能力并入单一工作台的「框特征」态（captureFeatureTemplate + 每框相似度滑块）。
    // 文件保留不删（避免「删了又改」），showFeatureCapture 状态不再有入口触发。

    // ADR-075 D4/D6：ActionEditDialog（通用 Action 编辑载体，只读分支已删）与
    // FeatureCaptureDialog（调用已停用，能力并入工作台「框特征」态）见上文各自处理。
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
                    // ADR-075 门禁①：任意已存在 Action 都可编辑，不再有「（只读）」标签。
                    "${index + 1}. ${typeName(action.type)}",
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
    onApplyRoiBoxes: (List<dev.nymph.auto.core.editor.FeatureBox>) -> Int,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val isVisionAction = ActionEditGuard.isVisionType(initial)
    // v3.0.3 S5 完整档：视觉类动作也进统一工作台编辑（悬浮面板仍走旧只读契约，互不干扰）。
    // ADR-075 门禁①单一真源：任意已存在 Action 可编辑（含 CLICK_IMAGE）；
    // 悬浮面板的 supportsSimpleEdit 只读契约另入口保留，与此互不干扰。
    val supported = ActionEditGuard.isWorkbenchEditable(initial)

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
    // v2.9.49：多特征框列表（每框独立相似度，PickerRegion.similarity 即该值）。
    // 单框会话时本列表至多一项，行为与 pickedRegion 完全一致（零回归）。
    val pickedBoxes = remember { mutableStateListOf<PickerRegion>() }
    // 每框纹理强度参考（0–100，拾取期采样一次），与 pickedBoxes 同序。
    val boxStrengths = remember { mutableStateListOf<Int>() }
    var roiMessage by remember { mutableStateOf("") }
    // v2.8.0：高级参数默认折叠，避免对话框铺满整屏（评估结论「界面尺寸超标」）
    var showMore by remember { mutableStateOf(false) }

    // v3.0.3 S5 完整档：门控与视觉字段在统一工作台内可见可改。
    // null = 该字段不存在，确认时以 null 哨兵交回守卫保真（不改）。
    val isVision = isVisionAction
    var editedCondition by remember { mutableStateOf(initial.condition) }
    var editedRule by remember { mutableStateOf(initial.sceneRule) }
    var editedImageData by remember {
        mutableStateOf(if (isVision) initial.imageData else null)
    }
    var simText by remember { mutableStateOf(initial.imageSimilarity.toString()) }
    // 有门控/规则/视觉字段时默认展开，确保「可见」；纯坐标动作默认折叠免打扰。
    var showVisionGate by remember {
        mutableStateOf(initial.condition != null || initial.sceneRule != null || isVision)
    }

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

    /** v2.9.49：写入多框结果；单框时与旧 applyRegion 完全等价（pickedRegion 取首框）。 */
    fun applyRegions(list: List<PickerRegion>) {
        if (list.isEmpty()) return
        pickedBoxes.clear()
        pickedBoxes.addAll(list)
        boxStrengths.clear()
        // 数值四元组 + expr 一起写入；多框时当前动作的 region 取首框（坐标动作不读 region，保真兼容）
        val first = list.first()
        pickedRegion = Region(first.leftPct, first.topPct, first.rightPct, first.bottomPct, first.toSpec())
        roiMessage = ""
        // 拾取期采样一次截图算每框纹理强度参考（异步、失败不影响主流程）
        scope.launch {
            val bmp = runCatching { dev.nymph.auto.engine.AutoClickService.live()?.screenshotBitmap() }.getOrNull()
                ?: return@launch
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }
            boxStrengths.clear()
            list.forEach { r ->
                val rect = PickerGeometry.normalizeRegion(
                    r.leftPx.toFloat(), r.topPx.toFloat(), r.rightPx.toFloat(), r.bottomPx.toFloat(),
                    r.widthPx, r.heightPx,
                )
                // 屏幕尺寸与截图尺寸一致时直接算；不一致按比例缩放到截图坐标系
                val sx = w.toFloat() / r.widthPx.coerceAtLeast(1)
                val sy = h.toFloat() / r.heightPx.coerceAtLeast(1)
                val scaled = PickerGeometry.Rect(
                    (rect.l * sx).toInt(), (rect.t * sy).toInt(),
                    (rect.r * sx).toInt(), (rect.b * sy).toInt(),
                )
                boxStrengths += dev.nymph.auto.core.editor.FeatureBoxAnalysis.textureStrength(px, w, h, scaled)
            }
        }
    }

    /** 单框兼容包装（ON_RESUME / 兜底路径调用），等价 applyRegions(listOf(r))。 */
    fun applyRegion(r: PickerRegion) = applyRegions(listOf(r))

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
        // v2.9.49：走多特征框会话——可连续＋加框，确认一次提交全部；取消无副作用。
        val ok = CoordinatePicker.show(
            context,
            mode = PickerMode.REGION,
            multiBox = true,
            onRegionsPicked = { list -> applyRegions(list); pendingRegion = false },
            onRegionPicked = { r -> applyRegion(r); pendingRegion = false }, // 兜底兼容旧投递
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
                    if (CoordinatePicker.lastRegions.isNotEmpty()) {
                        applyRegions(CoordinatePicker.lastRegions); pendingRegion = false
                    } else {
                        CoordinatePicker.lastRegion?.let { applyRegion(it); pendingRegion = false }
                    }
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
        title = { Text("编辑场景") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ADR-075 门禁①：任意已存在 Action 都可编辑——删除只读分支，无「去源码 JSON」引导。
                // 复杂类型走 typeOf→null 时不重映射 type（apply copy 保真），condition/sceneRule
                // 由下方 VisionGateEditor 可见可改；悬浮面板的只读契约在 ActionEditGuard 另入口保留。
                run {
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
                            if (pickedBoxes.isEmpty()) "框选特征框（可多个 · ROI / 模板范围）"
                            else "已框选 ${pickedBoxes.size} 个特征框 · 点击重选",
                        )
                    }
                    if (pickedBoxes.isNotEmpty()) {
                        val overlaps = remember(pickedBoxes.map { it.toSpec() }.joinToString("|")) {
                            pickedBoxes.mapIndexed { i, a ->
                                (0 until pickedBoxes.size).any { j ->
                                    j != i && dev.nymph.auto.core.editor.FeatureBoxAnalysis.overlapRatio(
                                        PickerGeometry.normalizeRegion(
                                            a.leftPx.toFloat(), a.topPx.toFloat(), a.rightPx.toFloat(), a.bottomPx.toFloat(),
                                            a.widthPx, a.heightPx,
                                        ),
                                        PickerGeometry.normalizeRegion(
                                            pickedBoxes[j].leftPx.toFloat(), pickedBoxes[j].topPx.toFloat(),
                                            pickedBoxes[j].rightPx.toFloat(), pickedBoxes[j].bottomPx.toFloat(),
                                            pickedBoxes[j].widthPx, pickedBoxes[j].heightPx,
                                        ),
                                    ) > dev.nymph.auto.core.editor.FeatureBoxAnalysis.OVERLAP_WARN
                                }
                            }
                        }
                        pickedBoxes.forEachIndexed { i, box ->
                            Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "#${i + 1} ${PickerGeometry.fmt4(box.leftPct)},${PickerGeometry.fmt4(box.topPct)}" +
                                            " ~ ${PickerGeometry.fmt4(box.rightPct)},${PickerGeometry.fmt4(box.bottomPct)} %",
                                        style = Ds.Mono.copy(fontSize = Ds.Label.fontSize),
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = {
                                        pickedBoxes.removeAt(i)
                                        if (boxStrengths.size > i) boxStrengths.removeAt(i)
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除特征框", modifier = Modifier.size(16.dp))
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "相似度 ${box.similarity}%",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    androidx.compose.material3.Slider(
                                        value = box.similarity.toFloat(),
                                        onValueChange = { v ->
                                            pickedBoxes[i] = box.copy(similarity = v.roundToInt().coerceIn(0, 100))
                                        },
                                        valueRange = 0f..100f,
                                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                    )
                                }
                                val strength = boxStrengths.getOrNull(i)
                                Text(
                                    (if (strength != null) "纹理参考 $strength／100（越高越易匹配）" else "纹理参考采样中…") +
                                        (if (overlaps.getOrNull(i) == true) " · ⚠与其他框重叠偏高，可能冗余" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (overlaps.getOrNull(i) == true) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "特征框只对「找图 / 找色 / 找文字 / AI / 节点」场景与条件生效；" +
                                "纯坐标点击不读取区域。各框相似度独立、互不影响。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            val fbs = pickedBoxes.map { r ->
                                dev.nymph.auto.core.editor.FeatureBox(
                                    Region(r.leftPct, r.topPct, r.rightPct, r.bottomPct, r.toSpec()),
                                    r.similarity,
                                )
                            }
                            val hits = onApplyRoiBoxes(fbs)
                            roiMessage = if (hits > 0) "✓ 已把 ${fbs.size} 个框应用到脚本内 $hits 处目标（可撤销）"
                            else "脚本内暂无找图/找色目标（$roiTargetCount 处）：请先在源码视图添加，" +
                                "或把某场景改为找图类。本场景的 region 仍会保存。"
                        }) {
                            Text("应用 ${pickedBoxes.size} 个特征框到脚本（$roiTargetCount 处）")
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
                    // v2.8.0：高级参数默认折叠 + 限宽，回应「界面尺寸超标」评估结论。
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
                            label = { Text("场景名（可选）") }, singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                    // v3.0.3 S5：门控/视觉字段可见可改。有门控字段时默认展开。
                    TextButton(onClick = { showVisionGate = !showVisionGate }) {
                        Text(if (showVisionGate) "收起门控与视觉参数 ▲" else "门控与视觉参数（imageData / 条件 / 场景规则）▼")
                    }
                    if (showVisionGate) {
                        VisionGateEditor(
                            condition = editedCondition,
                            rule = editedRule,
                            imageData = editedImageData,
                            imageSimilarity = simText.toIntOrNull()?.coerceIn(1, 100) ?: initial.imageSimilarity,
                            onCondition = { editedCondition = it },
                            onRule = { editedRule = it },
                            onImageData = { editedImageData = it },
                            onSimilarity = { simText = it.toString() },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // ADR-075 门禁①：确定恒可用（任意 Action 可编辑，含 CLICK_IMAGE）。
                enabled = supported,
                onClick = {
                    // 空串（fmt(0)==""）/非法输入收敛为 0，绝不静默 return 让按钮失灵
                    val dx = num(x)
                    val dy = num(y)
                    val patch = ActionEditGuard.Patch(
                        // 视觉动作显式用 KIND_VISION 放行（守卫据此只改视觉/门控字段、不重映射 type）；
                        // 简易动作沿用 chip 的 kind。悬浮面板不传 KIND_VISION，契约不变。
                        kind = if (isVision) ActionEditGuard.KIND_VISION else kind,
                        x = dx,
                        y = dy,
                        x2 = num(x2, dx),
                        y2 = num(y2, dy),
                        holdMs = holdMs.toLongOrNull() ?: 100L,
                        durationMs = durationMs.toLongOrNull() ?: 400L,
                        times = times.toIntOrNull() ?: 1,
                        waitMs = waitMs.toLongOrNull() ?: 0L,
                        desc = desc,
                        // v3.0.3 S5：门控/视觉字段。VisionGateEditor 未展示时这些值为
                        // initial 原值或 null，守卫以 copy 保真合并，绝不丢字段。
                        imageData = editedImageData,
                        imageSimilarity = simText.toIntOrNull()?.coerceIn(1, 100),
                        condition = editedCondition,
                        sceneRule = editedRule,
                    )
                    // 基于 copy 保真：children/listeners/... 全部继承；动作级 region 仍由框选覆盖。
                    val next = ActionEditGuard.apply(initial, patch)
                        .let { if (pickedRegion != null) it.copy(region = pickedRegion!!) else it }
                    onConfirm(next)
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * v3.0.3 S5 完整档：统一工作台内的门控 / 视觉字段编辑器。
 *
 * 把 imageData / region / similarity / condition / sceneRule 全部暴露为可改控件，
 * 而不是最小档的「只读展示 + 未知字段透传」。所有改动通过回调写回对话框状态，
 * 确认时由 [ActionEditGuard.apply] 以 copy 保真合并，绝不重建 Action、绝不丢字段。
 */
@Composable
private fun VisionGateEditor(
    condition: Condition?,
    rule: SceneRule?,
    imageData: String?,
    imageSimilarity: Int,
    onCondition: (Condition?) -> Unit,
    onRule: (SceneRule?) -> Unit,
    onImageData: (String?) -> Unit,
    onSimilarity: (Int) -> Unit,
) {
    fun d(s: String, fallback: Double = 0.0): Double = s.toDoubleOrNull() ?: fallback

    @Composable
    fun regionRow(label: String, r: Region, onChange: (Region) -> Unit) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "左" to r.leftPct, "上" to r.topPct, "右" to r.rightPct, "下" to r.bottomPct,
            ).forEachIndexed { i, (lb, v) ->
                OutlinedTextField(
                    value = fmt(v),
                    onValueChange = { s ->
                        val nv = d(s)
                        onChange(
                            when (i) {
                                0 -> r.copy(leftPct = nv)
                                1 -> r.copy(topPct = nv)
                                2 -> r.copy(rightPct = nv)
                                else -> r.copy(bottomPct = nv)
                            },
                        )
                    },
                    label = { Text(lb) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("门控与视觉参数（S5 完整档）", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)

    // ---- imageData / similarity（动作级，视觉动作与载体共用）----
    if (imageData != null) {
        OutlinedTextField(
            value = imageData,
            onValueChange = { onImageData(it) },
            label = { Text("图片模板数据 imageData（base64，可粘贴替换，${imageData.length} 字符）") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("相似度 ${imageSimilarity}%", style = MaterialTheme.typography.labelSmall)
        androidx.compose.material3.Slider(
            value = imageSimilarity.toFloat(),
            onValueChange = { onSimilarity(it.roundToInt().coerceIn(1, 100)) },
            valueRange = 1f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
    }

    // ---- condition 门控 ----
    if (condition != null) {
        Text("触发条件：${condition.kind.name}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (condition.kind == ConditionKind.COMBINE) {
            Text("合取方式（m 特征同帧求值一次）", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Combine.entries.forEach { cb ->
                    FilterChip(
                        selected = condition.combine == cb,
                        onClick = { onCondition(condition.copy(combine = cb)) },
                        label = { Text(cb.name) },
                    )
                }
            }
            if (condition.combine == Combine.N) {
                OutlinedTextField(
                    value = condition.runCountLimit.toString(),
                    onValueChange = { onCondition(condition.copy(runCountLimit = it.toIntOrNull()?.coerceAtLeast(1) ?: 1)) },
                    label = { Text("N 个中至少满足几个（runCountLimit）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            condition.children.forEachIndexed { idx, child ->
                Column(Modifier.padding(start = 8.dp, top = 6.dp).fillMaxWidth()) {
                    Text("特征 #${idx + 1} · ${child.kind.name}", style = MaterialTheme.typography.labelSmall)
                    if (child.imageData.isNotBlank()) {
                        Text("模板 ${child.imageData.length} 字符", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("相似度 ${child.imageSimilarity}%", style = MaterialTheme.typography.labelSmall)
                        androidx.compose.material3.Slider(
                            value = child.imageSimilarity.toFloat(),
                            onValueChange = { v ->
                                onCondition(condition.copy(children = condition.children.mapIndexed { i, c ->
                                    if (i == idx) c.copy(imageSimilarity = v.roundToInt().coerceIn(1, 100)) else c
                                }))
                            },
                            valueRange = 1f..100f,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        )
                    }
                    regionRow("搜索范围 region（屏幕百分比）", child.region) { nr ->
                        onCondition(condition.copy(children = condition.children.mapIndexed { i, c ->
                            if (i == idx) c.copy(region = nr) else c
                        }))
                    }
                }
            }
        } else if (condition.kind == ConditionKind.APPEAR_IMAGE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("条件相似度 ${condition.imageSimilarity}%", style = MaterialTheme.typography.labelSmall)
                androidx.compose.material3.Slider(
                    value = condition.imageSimilarity.toFloat(),
                    onValueChange = { onCondition(condition.copy(imageSimilarity = it.roundToInt().coerceIn(1, 100))) },
                    valueRange = 1f..100f,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            }
            regionRow("条件搜索范围 region", condition.region) { nr ->
                onCondition(condition.copy(region = nr))
            }
        }
    }

    // ---- sceneRule ----
    if (rule != null) {
        Spacer(Modifier.height(6.dp))
        Text("场景规则 sceneRule", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("规则相似度 ${rule.similarity}%", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.Slider(
                value = rule.similarity.toFloat(),
                onValueChange = { onRule(rule.copy(similarity = it.roundToInt().coerceIn(1, 100))) },
                valueRange = 1f..100f,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
        Text("触发模式", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TriggerMode.entries.forEach { tm ->
                FilterChip(
                    selected = rule.triggerMode == tm,
                    onClick = { onRule(rule.copy(triggerMode = tm)) },
                    label = { Text(tm.name) },
                )
            }
        }
        OutlinedTextField(
            value = rule.cooldownMs.toString(),
            onValueChange = { onRule(rule.copy(cooldownMs = it.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)) },
            label = { Text("冷却 cooldownMs") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        regionRow("搜索范围 searchRoi", rule.searchRoi) { nr ->
            onRule(rule.copy(searchRoi = nr))
        }
    }
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
    ActionType.RUN_ACTIONS -> "运行场景组"
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
        if (a.children.isNotEmpty()) add("子场景 ${a.children.size}")
        if (a.listeners.all().isNotEmpty()) add("监听 ${a.listeners.all().size}")
    }
    return coord + (if (extra.isNotEmpty()) " · " + extra.joinToString(" · ") else "") +
        (if (a.desc.isNotBlank()) " · ${a.desc}" else "")
}

internal fun fmtPct(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else PickerGeometry.fmt2(v)

/** 全屏像素宽高（含状态栏，与截图帧及窗口坐标空间同源）。 */
internal fun android.content.Context.realWidthPx(): Int = screenBounds().width()

internal fun android.content.Context.realHeightPx(): Int = screenBounds().height()

private fun android.content.Context.screenBounds(): android.graphics.Rect {
    // v2.9.19：复用窗口坐标空间单一真源，避免同一进程内多套屏幕尺寸口径。
    val size = dev.nymph.auto.ui.OverlayWindowMetrics.screenSize(this)
    return android.graphics.Rect(0, 0, size.width, size.height)
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import dev.nymph.auto.core.design.SceneBoard
import dev.nymph.auto.core.design.SceneCanvas
import dev.nymph.auto.core.design.SceneCanvas.CanvasPoint
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.GlobalVerifyMethod
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 单一工作台（ADR-075）：一张原画质底图 + 同屏三态（标点 / 框特征 / 整图门）。
 *
 * - 建档只走已证通的无障碍链：`AutoClickService.screenshotBitmap()` → [grabFrame] →
 *   PNG（无损、不降采样、压缩器不重编码）+ Base64 → assets → `SceneCanvas.mergeInto`。
 *   MediaProjection / HudCaptureService 本轮不接线、不进改动集。
 * - 标点态：N 个落点，每点独立手势 / 坐标 / 间隔，执行关系默认「按编号顺序」，
 *   「与某点同时」灰标「后续」（裁决 3：不提供可勾选项，禁止勾了没反应）。
 * - 框特征态：1~m 个特征框，各自模板 / 阈值 / 搜索区；组合 ALL / N-of-M 接
 *   [SceneCanvas.combine] / [SceneCanvas.minCount]（编译层 S3 已支持，本态仅 UI 接线）。
 * - 整图门态：开关 [GlobalVerifyMethod] + 阈值（保守初值、真机回调、宁可不点非提高命中）。
 * - 保存只写 `Action` / `sceneRule`，编辑态 canvas 不进运行链（N7/N8 保留不动）。
 *
 * 文件保留、不再以 Dialog 弹出——由 EditorScreen 作为全屏工作台页面宿主。
 * 真机验证边界：无障碍截图时序、FLAG_SECURE 页面、大字体框拖拽、命中率 / NCC 精度均未验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneCanvasEditorDialog(
    initial: SceneCanvas?,
    initialAssets: Map<String, String>,
    /** 脚本级整图校验配置（N5）：由 EditorScreen 从 Script 读入，保存时写回。 */
    globalMethod: GlobalVerifyMethod = GlobalVerifyMethod.OFF,
    globalVerifySimilarity: Int = Script.DEFAULT_GLOBAL_VERIFY_SIMILARITY,
    onDismiss: () -> Unit,
    onConfirm: (
        canvas: SceneCanvas,
        assets: Map<String, String>,
        method: GlobalVerifyMethod,
        similarity: Int,
    ) -> Unit,
    /**
     * 抓帧瞬间静默落盘：底图一抓到就写回脚本并存盘，防丢失。
     * 与 [onConfirm] 的区别——不关闭工作台、不弹提示，用户可继续标注。
     */
    onAutoSave: (
        canvas: SceneCanvas,
        assets: Map<String, String>,
        method: GlobalVerifyMethod,
        similarity: Int,
    ) -> Unit = { _, _, _, _ -> },
) {
    val scope = rememberCoroutineScope()

    var canvas by remember { mutableStateOf(initial ?: SceneCanvas()) }
    var assets by remember { mutableStateOf(initialAssets.toMap()) }
    var message by remember { mutableStateOf("") }
    var grabbing by remember { mutableStateOf(false) }
    var selectedFeature by remember { mutableIntStateOf(-1) } // -1=未选特征；>=0=第 i 个特征（第 0 个为锁点锚）
    var selectedPoint by remember { mutableIntStateOf(-1) }   // -1=未选点位；>=0=第 i 个点位
    // 同屏三态：0=标点，1=框特征，2=整图门（设计稿「标点 / 框特征 / 整图门」）
    var tab by remember { mutableIntStateOf(0) }
    // N5 整图门：从脚本级字段读入，本页可改，保存时写回 Script（不进 canvas、不进运行链之外）
    var workMethod by remember { mutableStateOf(globalMethod) }
    var workSimilarity by remember { mutableStateOf(globalVerifySimilarity) }
    // 保存门槛：至少抓到画面 + 1 个点击坐标 + 1 个识别特征；缺项时给出明确提示。
    val canSave = canvas.hasBackground() && canvas.points.isNotEmpty() && canvas.features.isNotEmpty()

    // 底图 Bitmap（从 assets 取回；空则占位）
    val bgBitmap by remember(canvas.backgroundKey, assets) {
        mutableStateOf(
            if (canvas.backgroundKey.isBlank()) null
            else assets[canvas.backgroundKey]?.let { b64 ->
                runCatching {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        )
    }

    // 抓帧瞬间落盘：rememberUpdatedState 保证协程内回调读到最新的宿主闭包。
    val latestAutoSave by rememberUpdatedState(onAutoSave)

    /** 抓一帧原画质整屏作为画布底图。走 AutoClickService 唯一合法取屏出口。 */
    fun grabFrame() {
        message = ""
        grabbing = true
        scope.launch(Dispatchers.IO) {
            try {
                val svc = dev.nymph.auto.engine.AutoClickService.live()
                if (svc == null) {
                    withContext(Dispatchers.Main) {
                        message = "无障碍服务未连接，无法抓帧。请先开启 NymphAuto 无障碍服务。"
                    }
                    return@launch
                }
                val bmp = svc.screenshotBitmap()
                if (bmp == null || bmp.isRecycled) {
                    withContext(Dispatchers.Main) {
                        message = "抓帧失败（${svc.lastScreenshotError}）。支付/隐私等页面禁止截图，请换界面再抓。"
                    }
                    return@launch
                }
                val bos = ByteArrayOutputStream()
                val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                val b64 = if (ok) Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP) else ""
                if (b64.isEmpty()) {
                    withContext(Dispatchers.Main) { message = "底图编码失败，请重试。" }
                    return@launch
                }
                val key = SceneCanvas.BG_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)
                withContext(Dispatchers.Main) {
                    val nextAssets = assets.toMutableMap().apply { put(key, b64) }
                    val nextCanvas = canvas.copy(
                        backgroundKey = key,
                        widthPx = bmp.width, heightPx = bmp.height,
                        rotation = 0,             // 方向由用户在下方显式选择，避免依赖服务内部状态
                        features = emptyList(),   // 换底图即换场景，旧框坐标不再对应，清空
                        points = emptyList(),     // 点位随底图一起失效
                        clickZone = Region(),
                        pointX = 50.0, pointY = 50.0,
                    )
                    assets = nextAssets
                    canvas = nextCanvas
                    selectedFeature = -1
                    selectedPoint = -1
                    message = "已抓取原画质画布 ${bmp.width}×${bmp.height}px，已自动保存，可继续标注。"
                    // 抓帧瞬间落盘：底图一到位就写回脚本，用户直接返回也不会丢。
                    // onAutoSave 用 rememberUpdatedState 持有，闭包内读到的是最新 method/similarity。
                    latestAutoSave(nextCanvas, nextAssets, workMethod, workSimilarity)
                }
            } catch (t: Throwable) {
                RunLog.w("SceneCanvas", "抓帧失败：${t.message}")
                withContext(Dispatchers.Main) { message = "抓帧异常：${t.message ?: "未知错误"}" }
            } finally {
                withContext(Dispatchers.Main) { grabbing = false }
            }
        }
    }

    // ADR-075 裁决 4 修复：相机球点击走最新抓帧闭包（rememberUpdatedState 避免持有过期 state）。
    val latestGrab by rememberUpdatedState({ grabFrame() })
    val context = LocalContext.current
    // 进入建档工作台 → 主动拉起 RunnerService 并让悬浮球切 EDIT_CAMERA（点球抓当前目标界面、
    // 屏蔽启停）；退出 → 复位 RUN_STATUS 并清中转回调。
    // 修复此前「服务未启动 → live()==null → 静默降级 → 相机球从不出现」的断链：
    // enterCameraCapture 在服务未启动时以 ACTION_ENTER_CAMERA 拉起前台服务，不加载任何脚本。
    // 悬浮窗权限缺失时 showBall 内部 toast + 静默降级，工作台按钮抓帧仍可用。
    DisposableEffect(Unit) {
        dev.nymph.auto.service.RunnerService.enterCameraCapture(context) { latestGrab() }
        onDispose {
            dev.nymph.auto.service.RunnerService.exitCameraCapture()
        }
    }

    /** 从底图上某个百分比矩形裁出原画质特征模板，存 assets 并挂到特征框。 */
    fun captureFeatureTemplate(index: Int, rect: Region) {
        val bmp = bgBitmap ?: return
        val l = (rect.leftPct / 100.0 * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val t = (rect.topPct / 100.0 * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val r = (rect.rightPct / 100.0 * bmp.width).toInt().coerceIn(l + 1, bmp.width)
        val b = (rect.bottomPct / 100.0 * bmp.height).toInt().coerceIn(t + 1, bmp.height)
        val cut = runCatching { Bitmap.createBitmap(bmp, l, t, r - l, b - t) }.getOrNull() ?: return
        val b64 = try {
            val bos = ByteArrayOutputStream()
            if (cut.compress(Bitmap.CompressFormat.PNG, 100, bos))
                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            else ""
        } finally {
            if (cut !== bmp) runCatching { cut.recycle() }
        }
        if (b64.isEmpty()) return
        val key = SceneCanvas.FEAT_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)
        val updated = canvas.features.toMutableList()
        updated[index] = updated[index].copy(templateKey = key)
        assets = assets.toMutableMap().apply { put(key, b64) }
        canvas = canvas.copy(features = updated)
    }

    fun addFeature() {
        if (!canvas.hasBackground()) {
            message = "请先抓取画布底图，再在图上框特征。"
            return
        }
        // ADR-075 / 裁决 2：解放 UI——框特征可 1~m 个，追加而非替换。
        // 第 0 个特征兼作锁点锚（编译层 S3 口径，compileToActions 不动）。
        val seq = canvas.features.size + 1
        val id = "feat" + UUID.randomUUID().toString().take(8)
        val feat = SceneCanvas.CanvasFeature(
            id = id,
            label = "特征$seq",
            region = Region(5.0 + (seq - 1) * 2.0, 5.0, 25.0 + (seq - 1) * 2.0, 25.0),
            similarity = SceneBoard.DEFAULT_SIMILARITY,
            templateKey = "",
        )
        canvas = canvas.copy(features = canvas.features + feat)
        selectedFeature = canvas.features.size - 1
        message = "已加特征$seq：拖到画面上稳定不变的元素，拖结束自动裁原画质模板。" +
            if (seq == 1) " 第 1 个特征兼作点位锁点锚。" else " 组合方式在「框特征」态设 ALL / N-of-M。"
    }

    /** 新增一个编号点位（相对同一锚，各自绑手势与间隔）。 */
    fun addPoint() {
        if (!canvas.hasBackground()) {
            message = "请先抓取画布底图，再拾取点击坐标。"
            return
        }
        val id = "p" + UUID.randomUUID().toString().take(8)
        val point = CanvasPoint(
            id = id,
            label = "点位${canvas.points.size + 1}",
            x = canvas.pointX, y = canvas.pointY,
            gesture = SceneCanvas.PointGesture.TAP,
        )
        canvas = canvas.copy(points = canvas.points + point)
        selectedPoint = canvas.points.size - 1
        message = "已添加点击坐标 ${canvas.points.size}：在画布上拖动青色坐标点定位，下方选手势与间隔。"
    }

    fun updatePoint(index: Int, transform: (CanvasPoint) -> CanvasPoint) {
        val updated = canvas.points.toMutableList()
        updated[index] = transform(updated[index])
        canvas = canvas.copy(points = updated)
    }

    fun updateFeatureRect(index: Int, rect: Region) {
        val updated = canvas.features.toMutableList()
        updated[index] = updated[index].copy(region = rect)
        canvas = canvas.copy(features = updated)
    }

    fun updateFeatureSim(index: Int, sim: Int) {
        val updated = canvas.features.toMutableList()
        updated[index] = updated[index].copy(similarity = sim)
        canvas = canvas.copy(features = updated)
    }

    fun removeFeature(index: Int) {
        val f = canvas.features[index]
        val newAssets = assets.toMutableMap().apply { f.templateKey.takeIf { it.isNotBlank() }?.let { remove(it) } }
        assets = newAssets
        canvas = canvas.copy(features = canvas.features.filterIndexed { i, _ -> i != index })
        selectedFeature = -1
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("场景工作台") },
            navigationIcon = {
                // 返回前先静默落盘当前画布（含抓到的底图与标注），用户直接返回不丢场景。
                // mergeInto 幂等（替换旧画布产物），与抓帧瞬间的落盘重复调用也无副作用。
                IconButton(onClick = {
                    if (canvas.hasBackground()) {
                        latestAutoSave(canvas, assets, workMethod, workSimilarity)
                    }
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "返回并保存")
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "抓一张当前画面作底图，然后在图上拾取点击坐标、框住要识别的元素。画面会自动保存，可随时回来继续编辑。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 同屏三态切换：拾取坐标 / 框特征 / 整图校验
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("拾取坐标") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("框特征") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("整图校验") })
            }

            // 建档：悬浮球点一下 = 抓当前帧（无障碍链），本页按钮同能力；相机球见工作台入口。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { grabFrame() }, enabled = !grabbing) {
                    Text(if (grabbing) "抓帧中…" else if (canvas.hasBackground()) "重新抓画布" else "抓取当前画面")
                }
                if (tab == 1) TextButton(onClick = { addFeature() }, enabled = canvas.hasBackground()) { Text("+ 加特征框") }
                if (tab == 0) TextButton(onClick = { addPoint() }, enabled = canvas.hasBackground()) { Text("+ 拾取坐标") }
            }

                if (bgBitmap != null) {
                    CanvasBoard(
                        bitmap = bgBitmap!!,
                        canvas = canvas,
                        selectedFeature = selectedFeature,
                        selectedPoint = selectedPoint,
                        onSelectFeature = { selectedFeature = it },
                        onSelectPoint = { selectedPoint = it },
                        onFeatureRect = { i, r -> updateFeatureRect(i, r) },
                        onFeatureTemplate = { i, r -> captureFeatureTemplate(i, r) },
                        onZone = { r -> canvas = canvas.copy(clickZone = r) },
                        // 拖拽回调携带明确索引，不再依赖 selectedPoint 状态推断目标点。
                        // 原因：onDragStart 里设置 selectedPoint 是异步 state 更新，drag 首帧
                        // 闭包读到的可能仍是旧值 -1，导致走兼容落点分支、points 纹丝不动。
                        onPointMove = { idx, x, y ->
                            if (idx in canvas.points.indices) {
                                updatePoint(idx) { it.copy(x = x, y = y) }
                            } else {
                                // idx == -1：老脚本单落点（points 为空）的兼容分支
                                canvas = canvas.copy(pointX = x, pointY = y)
                            }
                        },
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (grabbing) "抓取中…" else "尚无画布，点「抓取当前画面」",
                            color = Color.White,
                        )
                    }
                }

                // ── 框特征态（tab==1）：1~m 个特征 + 组合 ALL / N-of-M。第 0 个兼作锁点锚。
                if (tab == 1) {
                    Text("触发门控 · 框特征（1 个或多个）", fontWeight = FontWeight.Bold)
                    if (canvas.features.isEmpty()) {
                        Text("尚未框特征。点上方「+ 加特征框」，在底图上拖出稳定元素。", style = MaterialTheme.typography.bodyMedium)
                    }
                    canvas.features.forEachIndexed { i, f ->
                        Column(Modifier.fillMaxWidth().border(1.dp, if (selectedFeature == i) Color(0xFFFFD54F) else Color.Transparent).padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = selectedFeature == i, onClick = { selectedFeature = i },
                                    label = { Text(f.label.ifBlank { "特征${i + 1}" } + if (i == 0) " · 锁点锚" else "") })
                                Text(if (f.templateKey.isNotBlank()) "模板已裁" else "未裁模板", style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { removeFeature(i) }) { Text("删") }
                            }
                            Text("相似度 ${f.similarity}%（${SceneBoard.combineLabel(canvas.combine)}）", style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = f.similarity.toFloat(),
                                valueRange = SceneBoard.MIN_SIMILARITY.toFloat()..SceneBoard.MAX_SIMILARITY.toFloat(),
                                onValueChange = { updateFeatureSim(i, it.toInt()) },
                            )
                        }
                    }
                    if (canvas.features.size > 1) {
                        Text("组合（m 特征同帧合取一次求值）", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Combine.entries.forEach { cb ->
                                FilterChip(
                                    selected = canvas.combine == cb,
                                    onClick = { canvas = canvas.copy(combine = cb) },
                                    label = { Text(SceneBoard.combineLabel(cb)) },
                                )
                            }
                        }
                        if (canvas.combine == Combine.N) {
                            OutlinedTextField(
                                value = canvas.minCount.coerceAtLeast(1).toString(),
                                onValueChange = { s ->
                                    val n = s.toIntOrNull()?.coerceIn(1, canvas.features.size) ?: 1
                                    canvas = canvas.copy(minCount = n)
                                },
                                label = { Text("至少满足几个（N-of-M）") }, singleLine = true,
                            )
                        }
                        Text(
                            "运行时先校验画面和特征，都匹配了才会按坐标点击；只要有一项对不上就不会动作。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // ── 标点态（tab==0）：每点独立手势 + 参数 + 执行关系
                if (tab == 0) {
                Text("点击坐标（${canvas.points.size}）· 按编号顺序执行", fontWeight = FontWeight.Bold)
                if (canvas.points.isEmpty()) {
                    Text(
                        "尚未拾取坐标。点上方「拾取坐标」后，在画布上拖动青色坐标点定位，逐点选手势与间隔。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                canvas.points.forEachIndexed { i, p ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (selectedPoint == i) Color(0xFF448AFF) else Color.Transparent)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = selectedPoint == i,
                                onClick = { selectedPoint = i },
                                label = { Text(p.label.ifBlank { "坐标${i + 1}" }) },
                            )
                            Text(
                                "(${fmt(p.x)}, ${fmt(p.y)})%",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TextButton(onClick = {
                                updatePoint(i) { it.copy(useAbsolute = !it.useAbsolute) }
                            }) {
                                Text(if (p.useAbsolute) "绝对兜底" else "锚定相对")
                            }
                            TextButton(onClick = {
                                canvas = canvas.copy(points = canvas.points.filterIndexed { idx, _ -> idx != i })
                                if (selectedPoint == i) selectedPoint = -1
                            }) { Text("删") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SceneCanvas.PointGesture.entries.forEach { g ->
                                FilterChip(
                                    selected = p.gesture == g,
                                    onClick = { updatePoint(i) { it.copy(gesture = g) } },
                                    label = { Text(gestureLabel(g)) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("间隔 ${p.waitMs}ms", style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(
                            value = p.waitMs.coerceAtMost(2000).toFloat(),
                            valueRange = 0f..2000f,
                            onValueChange = { ms -> updatePoint(i) { it.copy(waitMs = ms.toLong()) } },
                        )
                        if (p.gesture == SceneCanvas.PointGesture.SWIPE) {
                            Text("滑动终点 (${fmt(p.x2)}, ${fmt(p.y2)})% · 在画布上拖动另设", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                // 执行关系：默认按编号顺序 + 间隔 ms；「与某点同时」为后续能力，不提供不可用的勾选项。
                Text(
                    "执行顺序：按编号依次点击，每步之间等待设定的间隔。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                } // tab==0 标点态结束

                // ── 整图门态（tab==2）：开关 + 阈值（N5）。字段落 Script 级，保存写回。
                if (tab == 2) {
                    Text("整图基线校验（NCC）", fontWeight = FontWeight.Bold)
                    Text(
                        "以当前画布作参照，画面不一致时不会动作。仅在相同界面、相同方向下有效。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = workMethod == GlobalVerifyMethod.NCC,
                            onClick = { workMethod = GlobalVerifyMethod.NCC },
                            label = { Text("开（NCC）") },
                        )
                        FilterChip(
                            selected = workMethod == GlobalVerifyMethod.OFF,
                            onClick = { workMethod = GlobalVerifyMethod.OFF },
                            label = { Text("关（OFF）") },
                        )
                    }
                    Text("阈值 ${workSimilarity}%（保守初值 ${Script.DEFAULT_GLOBAL_VERIFY_SIMILARITY}，真机回调）",
                        style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = workSimilarity.toFloat(),
                        valueRange = 1f..100f,
                        onValueChange = { workSimilarity = it.toInt().coerceIn(1, 100) },
                    )
                    Text(
                        "阈值越高越严格：画面稍有不同就不会动作。建议先用默认值试跑，再按实际效果调整。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 方向绑定：非 ANY 当前无运行链支持，灰标"未生效"，且编译期不写入 sceneRule
                Text("方向绑定（非「任意方向」暂未生效）", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SceneBoard.OrientationBinding.entries.forEach { b ->
                        FilterChip(
                            selected = canvas.bind == b,
                            enabled = b == SceneBoard.OrientationBinding.ANY,
                            onClick = { canvas = canvas.copy(bind = b) },
                            label = { Text(
                                if (b == SceneBoard.OrientationBinding.ANY) SceneBoard.bindingLabel(b)
                                else "${SceneBoard.bindingLabel(b)}（未生效）"
                            ) },
                        )
                    }
                }

                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)

                // 保存门槛提示：明确告知还差什么，避免「抓了图、加了坐标，保存却是灰的」无从判断。
                if (!canSave) {
                    val missing = buildList {
                        if (!canvas.hasBackground()) add("抓取画面")
                        if (canvas.points.isEmpty()) add("拾取至少一个点击坐标")
                        if (canvas.features.isEmpty()) add("框选至少一个识别特征")
                    }
                    Text(
                        "还差：${missing.joinToString("、")}即可保存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        // 底部保存条：把整图门配置一并写回 Script（onConfirm 参数扩展）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                // 至少标 1 个落点 + 至少 1 个特征才允许保存，杜绝空画布降级成裸 TAP。
                enabled = canSave,
                onClick = {
                    // 保存前为尚无模板的特征从底图裁一次原画质模板（同步更新 canvas/assets state）
                    canvas.features.forEachIndexed { i, f ->
                        if (f.templateKey.isBlank()) captureFeatureTemplate(i, f.region)
                    }
                    onConfirm(canvas, assets, workMethod, workSimilarity)
                },
            ) { Text("保存") }
        }
    }
}

/** 画布本体：底图 + 可拖拽的三类框。所有几何以百分比运算，与显示尺寸解耦。 */
@Composable
private fun CanvasBoard(
    bitmap: Bitmap,
    canvas: SceneCanvas,
    selectedFeature: Int,
    selectedPoint: Int,
    onSelectFeature: (Int) -> Unit,
    onSelectPoint: (Int) -> Unit,
    onFeatureRect: (Int, Region) -> Unit,
    onFeatureTemplate: (Int, Region) -> Unit,
    onZone: (Region) -> Unit,
    /** 点位拖拽：索引 + 绝对百分比坐标。idx==-1 表示老脚本单落点兼容分支。 */
    onPointMove: (Int, Double, Double) -> Unit,
) {
    var boxW by remember { mutableStateOf(1f) } // 画布实测宽度（px），与 onSizeChanged / dragAmount 同单位
    var boxH by remember { mutableStateOf(1f) } // 画布实测高度（px）
    val density = LocalDensity.current
    // 点位拖拽手势内读取最新数据：pointerInput 的 lambda 捕获的是组合时的旧值，
    // 用手势开始时快照 + 累计 dragAmount 计算绝对坐标，避免每帧都基于冻结的旧基准。
    val latestPoints by rememberUpdatedState(canvas.points)
    val imgW = bitmap.width.coerceAtLeast(1)
    val imgH = bitmap.height.coerceAtLeast(1)

    // 底图按原图宽高比铺满宽度，高度由 aspectRatio 精确推导。
    // 旧实现 shownH=boxW*imgH/imgW（px）却写成 .height(shownH.dp)，把像素当 dp，
    // 画布被放大 density 倍 → 截图只占顶部一条、下方纯黑、坐标点算到屏幕外。
    // 此处交给 Compose 用同一密度换算，px/dp 全程自洽。
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(imgW.toFloat() / imgH)
            .background(Color.Black)
            .onSizeChanged { boxW = it.width.toFloat(); boxH = it.height.toFloat() },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "场景画布底图",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )

        // 落点限制区（蓝框）——拖拽整体平移。用 latestZone 取手势起始快照，避免捕获旧 region。
        val zone = canvas.clickZone
        DraggableRect(
            region = zone,
            boxW = boxW, boxH = boxH,
            color = Color(0xFF448AFF),
            onDrag = { startRegion, dxPct, dyPct ->
                onZone(startRegion.copy(
                    leftPct = (startRegion.leftPct + dxPct).coerceIn(0.0, 100.0),
                    rightPct = (startRegion.rightPct + dxPct).coerceIn(0.0, 100.0),
                    topPct = (startRegion.topPct + dyPct).coerceIn(0.0, 100.0),
                    bottomPct = (startRegion.bottomPct + dyPct).coerceIn(0.0, 100.0),
                ))
            },
        )

        // 特征框（红/黄框）——拖拽整体平移；选中项高亮
        canvas.features.forEachIndexed { i, f ->
            DraggableRect(
                region = f.region,
                boxW = boxW, boxH = boxH,
                color = if (selectedFeature == i) Color(0xFFFFD54F) else Color(0xFFE53935),
                label = f.label.ifBlank { "特征${i + 1}" },
                onDragStart = { onSelectFeature(i) },
                onDrag = { startRegion, dxPct, dyPct ->
                    onFeatureRect(i, startRegion.copy(
                        leftPct = (startRegion.leftPct + dxPct).coerceIn(0.0, 100.0),
                        rightPct = (startRegion.rightPct + dxPct).coerceIn(0.0, 100.0),
                        topPct = (startRegion.topPct + dyPct).coerceIn(0.0, 100.0),
                        bottomPct = (startRegion.bottomPct + dyPct).coerceIn(0.0, 100.0),
                    ))
                },
                onDragEnd = { endRegion -> onFeatureTemplate(i, endRegion) },
            )
        }

        // 落点标记（N 个编号圆点）：每个点位独立拖拽定位。
        // 关键修复：拖拽回调携带点位索引 idx（不再依赖有重组延迟的 selectedPoint），
        // 且手势内用「起点快照 + 累计 dragAmount」算绝对坐标——pointerInput lambda 捕获的
        // p.x/p.y 在手势期间是冻结的旧值，逐帧加到旧基准上会导致点位跳一下就卡住。
        // 视觉圆点仍 24dp，但触摸热区扩到 44dp，解决小圆点难以命中的问题。
        val hitHalfPx = with(density) { 22.dp.toPx() }.toInt() // 44dp 热区半径
        canvas.points.forEachIndexed { i, p ->
            val px = (p.x / 100.0 * boxW).toInt()
            val py = (p.y / 100.0 * boxH).toInt()
            val markerColor = if (selectedPoint == i) Color(0xFF00E5FF) else Color.Cyan
            Box(
                Modifier
                    .offset { IntOffset(px - hitHalfPx, py - hitHalfPx) }
                    .size(44.dp)
                    .pointerInput(p.id) {
                        // 手势起点百分比快照（点按瞬间取最新值），累计位移只在本协程内累积。
                        var startX = p.x
                        var startY = p.y
                        var accDx = 0f
                        var accDy = 0f
                        detectDragGestures(
                            onDragStart = {
                                latestPoints.getOrNull(i)?.let { fresh ->
                                    startX = fresh.x; startY = fresh.y
                                }
                                accDx = 0f; accDy = 0f
                                onSelectPoint(i)
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            accDx += dragAmount.x
                            accDy += dragAmount.y
                            onPointMove(
                                i,
                                (startX + accDx / boxW * 100.0).coerceIn(0.0, 100.0),
                                (startY + accDy / boxH * 100.0).coerceIn(0.0, 100.0),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .border(2.dp, markerColor, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}",
                        color = markerColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        // 老画布脚本（无 points）仍显示单个兼容落点，idx=-1 走宿主兼容分支。
        if (canvas.points.isEmpty()) {
            val px = (canvas.pointX / 100.0 * boxW).toInt()
            val py = (canvas.pointY / 100.0 * boxH).toInt()
            Box(
                Modifier
                    .offset { IntOffset(px - hitHalfPx, py - hitHalfPx) }
                    .size(44.dp)
                    .pointerInput(Unit) {
                        var startX = canvas.pointX
                        var startY = canvas.pointY
                        var accDx = 0f
                        var accDy = 0f
                        detectDragGestures(
                            onDragStart = { accDx = 0f; accDy = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            accDx += dragAmount.x
                            accDy += dragAmount.y
                            onPointMove(
                                -1,
                                (startX + accDx / boxW * 100.0).coerceIn(0.0, 100.0),
                                (startY + accDy / boxH * 100.0).coerceIn(0.0, 100.0),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .border(2.dp, Color.Cyan, RoundedCornerShape(50)),
                )
            }
        }
    }
}

/** 点位手势的中文短标签（UI 直接渲染）。 */
private fun gestureLabel(g: SceneCanvas.PointGesture): String = when (g) {
    SceneCanvas.PointGesture.TAP -> "轻击"
    SceneCanvas.PointGesture.MULTI_TAP -> "双击"
    SceneCanvas.PointGesture.LONG_PRESS -> "长按"
    SceneCanvas.PointGesture.SWIPE -> "滑动"
}

/** 画布上一个可拖拽的百分比矩形。 */
@Composable
private fun DraggableRect(
    region: Region,
    boxW: Float,
    boxH: Float,
    color: Color,
    label: String = "",
    onDragStart: () -> Unit = {},
    /** [start] 是手势起点快照；[dxPct]/[dyPct] 是相对起点的累计位移（百分比）。 */
    onDrag: (start: Region, dxPct: Double, dyPct: Double) -> Unit,
    /** 拖拽结束时回传最终矩形，供宿主裁模板等。 */
    onDragEnd: (end: Region) -> Unit = {},
) {
    // boxW/boxH 与 below 的 l/t 均为 px：offset 用 IntOffset(px) 正确；
    // size 必须把 px 转 dp，否则把像素当 dp，框被放大 density 倍错位。
    val density = LocalDensity.current
    // pointerInput lambda 捕获组合时的旧 region；手势开始时取最新快照，累计 dragAmount 算绝对位移。
    val latestRegion by rememberUpdatedState(region)
    val l = (region.leftPct / 100.0 * boxW).toInt()
    val t = (region.topPct / 100.0 * boxH).toInt()
    val w = ((region.rightPct - region.leftPct) / 100.0 * boxW).toInt().coerceAtLeast(8)
    val h = ((region.bottomPct - region.topPct) / 100.0 * boxH).toInt().coerceAtLeast(8)
    val wDp = with(density) { w.toDp() }
    val hDp = with(density) { h.toDp() }
    Box(
        Modifier
            .offset { IntOffset(l, t) }
            .size(wDp, hDp)
            .border(2.dp, color)
            .pointerInput(Unit) {
                var start = latestRegion
                var accDx = 0f
                var accDy = 0f
                detectDragGestures(
                    onDragStart = {
                        start = latestRegion
                        accDx = 0f; accDy = 0f
                        onDragStart()
                    },
                    onDragEnd = {
                        val dx = accDx / boxW * 100.0
                        val dy = accDy / boxH * 100.0
                        onDragEnd(start.copy(
                            leftPct = (start.leftPct + dx).coerceIn(0.0, 100.0),
                            rightPct = (start.rightPct + dx).coerceIn(0.0, 100.0),
                            topPct = (start.topPct + dy).coerceIn(0.0, 100.0),
                            bottomPct = (start.bottomPct + dy).coerceIn(0.0, 100.0),
                        ))
                    },
                ) { change, dragAmount ->
                    change.consume()
                    accDx += dragAmount.x
                    accDy += dragAmount.y
                    onDrag(start, accDx / boxW * 100.0, accDy / boxH * 100.0)
                }
            },
    ) {
        if (label.isNotBlank()) {
            Text(
                label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(2.dp),
            )
        }
    }
}

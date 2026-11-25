// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nymph.auto.core.capture.FeatureCapture
import dev.nymph.auto.core.editor.FeatureBoxAnalysis
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.vision.PickerGeometry
import dev.nymph.auto.ui.CoordinatePicker
import dev.nymph.auto.ui.PickerMode
import dev.nymph.auto.ui.PickerRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 图片特征截取对话框 —— 补齐竞品那条**可操作交互闭环**：
 *
 *   点「截取特征」→ 框选屏幕区域（悬浮浮层，四角手柄 + 百分比刻度）→
 *   抓一帧无障碍截图 → 按框裁出**原画质**PNG → base64 存为"特征" →
 *   对话框内显示缩略图（保存脚本后重开仍可查看）→ 设相似度阈值 / 限制区域 →
 *   确定生成 [ActionType.CLICK_IMAGE] 动作写回脚本。
 *
 * 设计约束：
 *  - 抓帧只走 [dev.nymph.auto.engine.AutoClickService.screenshotBitmap]（内部委托
 *    `CompatFallback` 唯一出口），不引入 MediaProjection / ImageReader，不抢录屏。
 *  - 裁剪几何走 core 纯 JVM [FeatureCapture]，平台层只做 Bitmap ↔ base64。
 *  - 取消无副作用；浮层权限不足时 fail-loud 显示原因，绝不静默。
 */
@Composable
fun FeatureCaptureDialog(
    initial: Action?,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var similarity by remember {
        mutableIntStateOf(
            FeatureCapture.clampSimilarity(initial?.imageSimilarity?.takeIf { it > 0 } ?: FeatureCapture.DEFAULT_SIMILARITY)
        )
    }
    var imageBase64 by remember { mutableStateOf(initial?.imageData.orEmpty()) }
    var region by remember { mutableStateOf(initial?.region ?: Region()) }
    var message by remember { mutableStateOf("") }
    var capturing by remember { mutableStateOf(false) }

    // 缩略图：base64 → Bitmap（与主工程 VideoImportDialog 同一范式）
    val thumbnail by remember(imageBase64) {
        mutableStateOf(
            if (imageBase64.isEmpty()) null
            else runCatching {
                val bytes = Base64.decode(imageBase64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        )
    }

    // 框选结果序号：ON_RESUME 只认本次 show 之后的新结果，杜绝陈旧回填
    var seqBefore by remember { mutableIntStateOf(CoordinatePicker.resultSeq) }
    var pendingCapture by remember { mutableStateOf(false) }

    /** 框选落定 → 抓帧 → 原画质裁剪 → base64 + region 回填。 */
    fun applyRegionPick(r: PickerRegion) {
        pendingCapture = false
        capturing = true
        scope.launch(Dispatchers.IO) {
            try {
                val svc = dev.nymph.auto.engine.AutoClickService.live()
                if (svc == null) {
                    message = "无障碍服务未连接，无法截图。请先开启 NymphAuto 无障碍服务。"
                    return@launch
                }
                val bmp = svc.screenshotBitmap()
                if (bmp == null || bmp.isRecycled) {
                    message = "截图失败（${svc.lastScreenshotError}）。若当前是支付/隐私页（不允许截图）请换个界面再截取。"
                    return@launch
                }
                val rect = FeatureCapture.cropRect(
                    r.leftPx, r.topPx, r.rightPx, r.bottomPx,
                    r.widthPx, r.heightPx, bmp.width, bmp.height,
                )
                if (!FeatureCapture.isUsable(rect)) {
                    message = "框选区域太小（需至少 ${FeatureCapture.MIN_TEMPLATE_SIDE}px），请重新框选。"
                    return@launch
                }
                val cut = Bitmap.createBitmap(bmp, rect!!.left, rect.top, rect.width, rect.height)
                val b64 = try {
                    val bos = ByteArrayOutputStream()
                    if (!cut.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                        message = "特征图编码失败，请重试。"
                        ""
                    } else {
                        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    }
                } finally {
                    if (cut !== bmp) runCatching { cut.recycle() }
                }
                if (b64.isEmpty()) return@launch
                val pickedRegion = FeatureCapture.toRegion(rect, bmp.width, bmp.height) ?: Region()
                withContext(Dispatchers.Main) {
                    imageBase64 = b64
                    region = pickedRegion
                    message = "已截取特征 ${rect.width}×${rect.height}px（原画质，随脚本保存）"
                }
            } catch (t: Throwable) {
                RunLog.w("FeatureCapture", "截取失败：${t.message}")
                withContext(Dispatchers.Main) { message = "截取异常：${t.message ?: "未知错误"}" }
            } finally {
                withContext(Dispatchers.Main) { capturing = false }
            }
        }
    }

    fun startCapture() {
        message = ""
        seqBefore = CoordinatePicker.resultSeq
        pendingCapture = true
        val ok = CoordinatePicker.show(
            context,
            mode = PickerMode.REGION,
            onRegionPicked = { r -> applyRegionPick(r) },
            onRegionsPicked = { list -> list.firstOrNull()?.let { applyRegionPick(it) } },
        )
        if (!ok) {
            pendingCapture = false
            message = "无悬浮窗权限，无法框选。请授予 NymphAuto 悬浮窗权限后重试。"
        }
    }

    // 配置变更/进程重建导致即时回调丢失时，回前台用新结果补填一次（同 ActionEditDialog 范式）
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && pendingCapture && CoordinatePicker.resultSeq > seqBefore) {
                val r = CoordinatePicker.lastRegions.firstOrNull() ?: CoordinatePicker.lastRegion
                if (r != null) applyRegionPick(r)
                pendingCapture = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            CoordinatePicker.dismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("图片特征") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "框选屏幕上一块区域，截成特征存进脚本。运行时在限制区域内找到它才点击。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 缩略图预览：有图显示原画质，无图显示占位
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = thumbnail
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "特征缩略图",
                            modifier = Modifier.size(140.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            if (capturing) "截取中…" else "尚无特征图",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { startCapture() }, enabled = !capturing) {
                        Text(if (imageBase64.isEmpty()) "截取特征" else "重新截取")
                    }
                    if (imageBase64.isNotEmpty()) {
                        TextButton(onClick = { imageBase64 = ""; message = "" }) { Text("清除") }
                    }
                }

                // 相似度阈值（竞品滑块 50–90，这里对齐 core 合法区间 50–100）
                Text(
                    "相似度阈值 ${similarity}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = similarity.toFloat(),
                    valueRange = FeatureCapture.MIN_SIMILARITY.toFloat()..FeatureCapture.MAX_SIMILARITY.toFloat(),
                    onValueChange = { similarity = it.toInt() },
                )

                // 限制区域（截取时框选范围，运行时搜索范围）
                Text(
                    "限制区域：(${fmtPct(region.leftPct)}%-${fmtPct(region.rightPct)}%, " +
                        "${fmtPct(region.topPct)}%-${fmtPct(region.bottomPct)}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = imageBase64.isNotEmpty(),
                onClick = {
                    onConfirm(
                        initial?.copy(
                            type = ActionType.CLICK_IMAGE,
                            imageData = imageBase64,
                            imageSimilarity = similarity,
                            region = region,
                        ) ?: Action(
                            type = ActionType.CLICK_IMAGE,
                            imageData = imageBase64,
                            imageSimilarity = similarity,
                            region = region,
                            desc = "图片特征",
                        )
                    )
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

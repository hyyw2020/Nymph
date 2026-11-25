// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.nymph.auto.core.simpletap.InjectionChannel
import dev.nymph.auto.core.simpletap.SimpleTapPlan
import dev.nymph.auto.engine.ShizukuBridge
import dev.nymph.auto.engine.AutoClickService
import dev.nymph.auto.runner.SimpleTapRunner

/**
 * 轻量定点点击快路径对话框（v3.0.0）。
 *
 * 三步心智：① 填坐标（屏幕百分比，0–100）→ ② 设间隔/次数 → ③ 选通道并运行。
 * 无锚点、无模板、无相似度滑杆。runner 在对话框生命周期内创建与释放。
 *
 * 通道三选：真值（Shizuku，需授权）、标准（dispatchGesture，无需额外授权）、
 * 高级（沿用现有取证链，从主界面进入，不在本对话框内）。真值模式但 Shizuku
 * 不可用时，[SimpleTapRunner] 会 fail-loud 拒绝，不静默回退截图猜。
 */
@Composable
fun SimpleTapDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizuku = remember { ShizukuBridge(context) }

    var xText by remember { mutableStateOf("50") }
    var yText by remember { mutableStateOf("50") }
    var intervalText by remember { mutableStateOf("500") }
    var repeatText by remember { mutableStateOf("10") }
    var channel by remember { mutableStateOf(InjectionChannel.GESTURE) }
    var status by remember { mutableStateOf("就绪：填坐标 → 设间隔/次数 → 选通道运行") }
    var runner by remember { mutableStateOf<SimpleTapRunner?>(null) }

    DisposableEffect(Unit) {
        onDispose { runner?.stop(); shizuku.release() }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(16.dp)) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                Text("快速点击", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "坐标用屏幕百分比（0–100）。例：500×1000 屏上点 (250,700) 填 x=50 y=70。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = xText, onValueChange = { xText = it },
                        label = { Text("X (%)") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = yText, onValueChange = { yText = it },
                        label = { Text("Y (%)") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = intervalText, onValueChange = { intervalText = it },
                        label = { Text("间隔 ms") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = repeatText, onValueChange = { repeatText = it },
                        label = { Text("次数 (0=循环)") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))

                Text("注入通道", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = channel == InjectionChannel.GESTURE,
                        onClick = { channel = InjectionChannel.GESTURE },
                        label = { Text("标准") },
                    )
                    FilterChip(
                        selected = channel == InjectionChannel.SHIZUKU,
                        onClick = { channel = InjectionChannel.SHIZUKU },
                        label = { Text("真值") },
                    )
                }
                Text(
                    when (channel) {
                        InjectionChannel.SHIZUKU ->
                            if (shizuku.isInstalled()) "真值：将用 Shizuku 注入（需已授权）。"
                            else "真值：未检测到 Shizuku，运行时会拒绝并提示，不会回退截图猜。"
                        InjectionChannel.GESTURE ->
                            if (AutoClickService.live() != null) "标准：系统手势注入，无需额外授权。"
                            else "标准：需先在系统设置开启 NymphAuto 无障碍服务。"
                        else -> "高级：请从主界面进入脚本编辑器。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { runner?.stop() }) { Text("停止") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val plan = runCatching {
                            SimpleTapPlan(
                                x = xText.toDouble(), y = yText.toDouble(),
                                intervalMs = intervalText.toLong(), repeat = repeatText.toInt(),
                            )
                        }.getOrNull()
                        if (plan == null) {
                            status = "⛔ 参数不合法：坐标 0–100、间隔 ≥40ms、次数 ≥0。"
                            toast(status); return@Button
                        }
                        val svc = AutoClickService.live()
                        val w = svc?.frame()?.width ?: context.resources.displayMetrics.widthPixels
                        val h = svc?.frame()?.height ?: context.resources.displayMetrics.heightPixels
                        val r = SimpleTapRunner(scope, shizuku, onStatus = { status = it })
                        runner = r
                        r.start(plan, channel, w, h)
                    }) { Text("运行") }
                }
            }
        }
    }
}

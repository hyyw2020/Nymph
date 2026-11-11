package dev.nymph.auto.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 小圆点状态标签：绿=正常、红=缺失、灰=中性。 */
@Composable
fun StatusPill(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (tone) {
        Tone.OK -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Tone.WARN -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        Tone.BAD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = bg, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

enum class Tone { OK, WARN, BAD, NEUTRAL }

/** 分组标题。 */
@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 权限/能力行：图标 + 标题副标题 + 右侧状态或操作。 */
@Composable
fun InfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tone: Tone,
    statusText: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (actionText != null && onAction != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    onClick = onAction,
                ) {
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            } else {
                StatusPill(statusText, tone)
            }
        }
    }
}

/** 说明卡：一组要点 + 可选强调色。 */
@Composable
fun NoteCard(
    title: String,
    lines: List<String>,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(Modifier.padding(14.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
                Spacer(Modifier.height(6.dp))
                lines.forEach { line ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}


/** 脚本卡片：名称/依赖/时间 + 运行/更多菜单。 */
@Composable
fun ScriptCard(
    item: ScriptUi,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onFloatEdit: () -> Unit = {},
    onHistory: () -> Unit = {},
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("${item.steps} 步", Tone.OK)
                        Spacer(Modifier.width(6.dp))
                        if (item.needsScreenshot) {
                            StatusPill("截图", Tone.NEUTRAL)
                            Spacer(Modifier.width(6.dp))
                        }
                        if (item.needsAi) StatusPill("AI", Tone.NEUTRAL)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑动作") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onEdit() },
                        )
                        // v2.8.0：系统级悬浮编辑器，可脱离本 APP 在其他 APK 界面上编辑
                        DropdownMenuItem(
                            text = { Text("悬浮编辑（可浮在其他 APP 上）") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onFloatEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("历史版本 / 回滚") },
                            leadingIcon = { Icon(Icons.Filled.Info, null) },
                            onClick = { menu = false; onHistory() },
                        )
                        DropdownMenuItem(
                            text = { Text("导出明文脚本") },
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { menu = false; onShare() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                text = item.depsLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeText(item.savedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    onClick = onRun,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "运行",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

private fun timeText(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

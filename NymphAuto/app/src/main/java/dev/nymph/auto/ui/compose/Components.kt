package dev.nymph.auto.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用组件层 —— 统一遵循 "Quiet Precision" art direction：
 *  - 状态用色相区分，但文字取 onContainer 色（craft floor：彩色面上从该色相取字，不用灰）；
 *  - 卡片用完整描边 + 分层底色，不用单侧竖条装饰（craft floor 绝对禁令）；
 *  - 分组标题用有分量的字号，不做 eyebrow 小标签（craft floor 绝对禁令）；
 *  - 组内紧、组间松，标题上方留白大于下方。
 */

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
    Surface(shape = Ds.RadiusPill, color = bg, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = Ds.SM, vertical = Ds.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 设计稿 After 栏：选中态 chip 前置小方块，让「正常/已就绪」一眼可扫。
            // 仅 OK 态带方块，NEUTRAL 不带 —— 强调色克制使用，不靠色块撑视觉。
            if (tone == Tone.OK) {
                Box(Modifier.size(5.dp).background(fg, CircleShape))
                Spacer(Modifier.width(Ds.XS))
            }
            Text(text = text, style = Ds.Label, color = fg)
        }
    }
}

enum class Tone { OK, WARN, BAD, NEUTRAL }

/** 分组标题：承担区块层级，用有分量的标题字号而非 eyebrow 小标签。 */
@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Ds.LG, end = Ds.LG, top = Ds.XL, bottom = Ds.SM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = Ds.Title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = Ds.Label,
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
        shape = Ds.RadiusMd,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Ds.LG, vertical = Ds.MD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(Ds.MD))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = Ds.ItemTitle)
                Text(
                    subtitle,
                    style = Ds.BodySecondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Ds.SM))
            if (actionText != null && onAction != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = Ds.RadiusPill,
                    onClick = onAction,
                ) {
                    Text(
                        text = actionText,
                        style = Ds.Label,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = Ds.LG, vertical = Ds.SM),
                    )
                }
            } else {
                StatusPill(statusText, tone)
            }
        }
    }
}

/** 说明卡：一组要点 + 可选强调色。
 *
 * craft floor：不用「圆角盒子左侧竖条」这种装饰（a bar down one side of a
 * rounded box）。改用完整描边 + 标题字重强调；强调色只用于标题文字，
 * 干干净净，不靠色条撑视觉。
 */
@Composable
fun NoteCard(
    title: String,
    lines: List<String>,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Ds.RadiusMd,
        modifier = modifier
            .fillMaxWidth()
            .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant, Ds.RadiusMd),
    ) {
        Column(Modifier.padding(Ds.LG)) {
            Text(
                title,
                style = Ds.ItemTitle,
                color = accent,
            )
            Spacer(Modifier.height(Ds.SM))
            lines.forEach { line ->
                Row(Modifier.padding(vertical = Ds.XS / 2)) {
                    Text(
                        "·",
                        style = Ds.BodySecondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Ds.SM))
                    Text(
                        line,
                        style = Ds.BodySecondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 脚本卡片：名称/依赖/时间 + 运行/更多菜单。
 *
 * 视觉层级：名称（ItemTitle 半粗）→ 场景数/标签（pill）→ 依赖摘要（次正文）
 * → 时间（标签）+ 运行（实心主按钮）。行距按「组内紧、组间松」重排。
 */
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
        shape = Ds.RadiusLg,
        modifier = Modifier
            .fillMaxWidth()
            .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant, Ds.RadiusLg),
    ) {
        Column(Modifier.padding(start = Ds.LG, top = Ds.LG, end = Ds.SM, bottom = Ds.MD)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = Ds.ItemTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Ds.SM))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("${item.steps} 个场景", Tone.OK)
                        Spacer(Modifier.width(Ds.SM))
                        if (item.needsScreenshot) {
                            StatusPill("截图", Tone.NEUTRAL)
                            Spacer(Modifier.width(Ds.SM))
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
                            text = { Text("编辑场景") },
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
            Spacer(Modifier.height(Ds.MD))
            Text(
                text = item.depsLine,
                style = Ds.BodySecondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = Ds.MD),
            )
            Spacer(Modifier.height(Ds.MD))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeText(item.savedAt),
                    style = Ds.Mono.copy(fontSize = Ds.Label.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = Ds.RadiusPill,
                    onClick = onRun,
                ) {
                    Row(
                        Modifier.padding(horizontal = Ds.LG, vertical = Ds.SM),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Ds.SM))
                        Text(
                            "运行",
                            style = Ds.Label,
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

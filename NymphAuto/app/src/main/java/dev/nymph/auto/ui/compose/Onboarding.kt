// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.auto.service.RunnerService
import kotlinx.coroutines.launch

/**
 * 首次启动新手引导（v3.1.0）。
 *
 * ## 设计定位
 *
 * 引导只解决一件事：把「新用户在前 60 秒内做不出第一条脚本」的空白期补上。
 * 它不承载执行语义，只做三件事——
 *  1. 建立心智模型：本地离线自动化，录制 → 建档 → 运行；
 *  2. 把「两项系统能力」提前到首次会话，用实时状态消除「为什么点了没反应」；
 *  3. 用明确的下一步收口，不把用户留在空列表里。
 *
 * 视觉完全落在既有 Quiet Precision 体系内：深底 + 青绿强调 + 状态 pill，
 * 不引入新的颜色或装饰语言（craft floor：拒绝 AI 模板感的渐变/emoji/彩条）。
 *
 * ## 一次性门控
 *
 * 复用 [RunnerService.PREFS]（`nymphauto` SharedPreferences）的布尔标记，
 * 与 v2.9.16 的「场景范式引导一次性标记」同一范式：不新增文件、不新增权限。
 * 标记只在「开始使用 / 跳过 / 系统返回」三个出口写盘；再次进入不重复弹出。
 */

private const val KEY_ONBOARDING_DONE = "onboarding_done_v1"

object OnboardingPrefs {
    fun isDone(ctx: Context): Boolean =
        ctx.getSharedPreferences(RunnerService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)

    fun markDone(ctx: Context) {
        ctx.getSharedPreferences(RunnerService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }
}

/**
 * 引导主壳：四页横向分页 + 底部进度/下一步。
 *
 * @param a11yOn 无障碍服务实时开关状态（第二页权限卡用）。
 * @param overlayOn 悬浮窗实时授权状态（第二页权限卡用）。
 */
@Composable
fun OnboardingScreen(
    a11yOn: Boolean,
    overlayOn: Boolean,
    onOpenA11y: () -> Unit,
    onOpenOverlay: () -> Unit,
    onFinish: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    val last = page == 3

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { i ->
            when (i) {
                0 -> PagePositioning()
                1 -> PagePermissions(a11yOn, overlayOn, onOpenA11y, onOpenOverlay)
                2 -> PageWorkflow()
                else -> PageReady()
            }
        }

        // 底部固定操作区：进度点 + 主动作，保证翻页时按钮位置稳定。
        Column(Modifier.padding(horizontal = Ds.XL, vertical = Ds.LG)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (i == page) 8.dp else 6.dp)
                            .background(
                                if (i == page) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(Ds.MD))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = Ds.RadiusPill,
                onClick = {
                    if (last) onFinish()
                    else scope.launch { pagerState.animateScrollToPage(page + 1) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (last) "开始使用" else "下一步",
                    style = Ds.Title.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
            if (!last) {
                Spacer(Modifier.height(Ds.XS))
                TextButton(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "跳过",
                        style = Ds.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 末页底部留白，避免与系统导航手势区过近。
                Spacer(Modifier.height(Ds.SM))
            }
        }
    }
}

// ------------------------------------------------------------ 页 1：定位

@Composable
private fun PagePositioning() {
    PageScaffold(
        icon = Icons.Filled.PlayArrow,
        title = "在目标应用之上，自动执行重复操作",
        subtitle = "NymphAuto 通过无障碍服务注入手势、识别界面，把「录制 → 建档 → 运行」" +
            "收敛为一条离线流水线。",
    ) {
        Bullet("本地离线：无 INTERNET 权限，全部数据留在设备内")
        Bullet("零录屏占用：不申请 MediaProjection，可与系统录屏并行")
        Bullet("明文可审计：脚本为明文 JSON，release 关闭 R8 混淆")
    }
}

// ------------------------------------------------------------ 页 2：权限

@Composable
private fun PagePermissions(
    a11yOn: Boolean,
    overlayOn: Boolean,
    onOpenA11y: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    PageScaffold(
        icon = Icons.Filled.Settings,
        title = "先配置两项系统能力",
        subtitle = "手势注入与界面识别依赖无障碍服务；悬浮球与控制面板依赖悬浮窗授权。" +
            "状态会随系统设置实时回读。",
    ) {
        OnboardingPermRow(
            title = "无障碍服务",
            subtitle = "手势注入 · 控件读取 · 无障碍截图",
            ok = a11yOn,
            onOpen = onOpenA11y,
        )
        Spacer(Modifier.height(Ds.SM))
        OnboardingPermRow(
            title = "悬浮窗",
            subtitle = "运行/录制时的悬浮球与控制面板",
            ok = overlayOn,
            onOpen = onOpenOverlay,
        )
    }
}

@Composable
private fun OnboardingPermRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Ds.RadiusMd,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = Ds.LG, vertical = Ds.MD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Ds.ItemTitle)
                Text(
                    subtitle,
                    style = Ds.BodySecondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Ds.SM))
            if (ok) {
                StatusPill("已就绪", Tone.OK)
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = Ds.RadiusPill,
                    onClick = onOpen,
                ) {
                    Text(
                        "去开启",
                        style = Ds.Label,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = Ds.LG, vertical = Ds.SM),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ 页 3：工作流

@Composable
private fun PageWorkflow() {
    PageScaffold(
        icon = Icons.AutoMirrored.Filled.List,
        title = "三步建立一条脚本",
        subtitle = "每一步都有明确的入口与退出，不依赖记忆菜单位置。",
    ) {
        StepCard("1", "录制", "切到目标应用照常操作，被动观察点击/滑动/输入，自动编译为场景")
        Spacer(Modifier.height(Ds.SM))
        StepCard("2", "建档", "在工作台逐场景精确编辑，框选特征、配置合取与整图门")
        Spacer(Modifier.height(Ds.SM))
        StepCard("3", "运行", "悬浮球一键启停，命中率与轨迹实时可视化")
    }
}

@Composable
private fun StepCard(order: String, title: String, subtitle: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Ds.RadiusMd,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(Ds.LG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    order,
                    style = Ds.Mono.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(Ds.MD))
            Column(Modifier.weight(1f)) {
                Text(title, style = Ds.ItemTitle)
                Text(
                    subtitle,
                    style = Ds.BodySecondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------ 页 4：开始

@Composable
private fun PageReady() {
    PageScaffold(
        icon = Icons.Filled.CheckCircle,
        title = "准备就绪",
        subtitle = "从「录制」开始最快；也可先导入现有 .zjs 脚本，或直接新建脚本进入工作台建档。",
    ) {
        Bullet("首次运行会请求 JS 授权，仅在脚本含「运行 JS 代码」场景时出现")
        Bullet("引导可随时在「设置 → 新手引导」重新查看")
        Bullet("全部能力离线，随时可在设置页关闭可视化与高频识别")
    }
}

// ------------------------------------------------------------ 通用页骨架

@Composable
private fun PageScaffold(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Ds.XL),
    ) {
        Spacer(Modifier.height(Ds.XXL))
        Box(
            Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(Ds.XL))
        Text(title, style = Ds.Display, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Ds.SM))
        Text(
            subtitle,
            style = Ds.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Ds.XL))
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = Ds.XS)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(Ds.MD))
        Text(
            text,
            style = Ds.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
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
 * 首次启动新手引导（v3.1.1 重构）。
 *
 * ## 引导路径（严格按建档实操顺序）
 *
 * 引导只解决一件事：让新用户在首次会话内跑通「权限 → 整页截图 → 编辑 → 保存」的最小闭环。
 * 页序即操作序，不插入与建档无关的定位/理念页：
 *  1. [PagePermissions]：先授予无障碍服务与悬浮窗（实时回读状态，可一键跳系统设置）；
 *  2. [PageCapture]：整页截图——进入建档后点悬浮相机球抓当前整页画面作为画布底图；
 *  3. [PageEdit]：编辑——在画布上标落点/框特征/整图门，判定条件可自定义；
 *  4. [PageSave]：保存后可再编辑——保存不锁定，脚本随时可回到工作台继续改。
 *
 * ## 视觉与门控
 *
 * 沿用 Quiet Precision 设计系统：深底 + 青绿强调 + 状态 pill，不引入新颜色或装饰语言。
 * 一次性门控复用 [RunnerService.PREFS] 布尔标记，仅在「开始使用 / 跳过 / 系统返回」出口写盘。
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
 * @param a11yOn 无障碍服务实时开关状态（第一页权限卡用）。
 * @param overlayOn 悬浮窗实时授权状态（第一页权限卡用）。
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
                0 -> PagePermissions(a11yOn, overlayOn, onOpenA11y, onOpenOverlay)
                1 -> PageCapture()
                2 -> PageEdit()
                else -> PageSave()
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

// ------------------------------------------------------------ 页 1：权限（首屏）

@Composable
private fun PagePermissions(
    a11yOn: Boolean,
    overlayOn: Boolean,
    onOpenA11y: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    PageScaffold(
        icon = Icons.Filled.Settings,
        title = "先授予两项系统能力",
        subtitle = "这两项是执行与截图的前提，授权状态会实时回读；授予后再进入建档。",
    ) {
        OnboardingPermRow(
            title = "无障碍服务",
            subtitle = "手势注入 · 控件读取 · 整页截图取帧",
            ok = a11yOn,
            onOpen = onOpenA11y,
        )
        Spacer(Modifier.height(Ds.SM))
        OnboardingPermRow(
            title = "悬浮窗",
            subtitle = "建档时的相机球与运行时的悬浮球",
            ok = overlayOn,
            onOpen = onOpenOverlay,
        )
        Spacer(Modifier.height(Ds.MD))
        Text(
            if (a11yOn && overlayOn) "两项能力已就绪，点「下一步」继续"
            else "授予后返回本页，状态会自动变为「已就绪」",
            style = Ds.BodySecondary,
            color = if (a11yOn && overlayOn) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
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

// ------------------------------------------------------------ 页 2：整页截图

@Composable
private fun PageCapture() {
    PageScaffold(
        icon = Icons.Filled.Place,
        title = "切到目标界面，点相机球抓整页",
        subtitle = "进入脚本工作台建档后，先切到要自动化的应用界面，再点悬浮的相机球——" +
            "它会把当前整页画面抓成画布底图。",
    ) {
        StepCard("1", "新建脚本", "从脚本页点「新建脚本」进入工作台建档")
        Spacer(Modifier.height(Ds.SM))
        StepCard("2", "切到目标界面", "打开要自动化的应用，停在操作的起始画面")
        Spacer(Modifier.height(Ds.SM))
        StepCard("3", "点相机球抓整页", "悬浮相机球点一下即抓当前整页，可反复重抓直到满意")
        Spacer(Modifier.height(Ds.MD))
        Text(
            "相机球只在建档时出现，点它只抓帧、不会启动脚本；抓帧走无障碍链，不占录屏权限。",
            style = Ds.BodySecondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------ 页 3：编辑

@Composable
private fun PageEdit() {
    PageScaffold(
        icon = Icons.Filled.Edit,
        title = "在底图上编辑，条件可自定义",
        subtitle = "抓到的整页就是画布。在画布上直接标注要操作的位置，判定条件由你决定。",
    ) {
        Bullet("拾取坐标：在需要点击/滑动的位置拾取点击坐标，指定动作与顺序")
        Bullet("框特征：框住目标区域，按控件 ID / 文字 / 图像特征识别，不依赖固定坐标")
        Bullet("整图门：用整页匹配作为触发条件，满足才往下执行")
        Spacer(Modifier.height(Ds.MD))
        Text(
            "编辑是可选的，也不要求一次到位——先保存一条能跑的，再逐步细化条件即可。",
            style = Ds.BodySecondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------ 页 4：保存后可再编辑

@Composable
private fun PageSave() {
    PageScaffold(
        icon = Icons.Filled.Done,
        title = "保存后随时可再编辑",
        subtitle = "保存不是终点。任何已保存的脚本都能重新进入工作台，继续抓帧、改标注、调条件。",
    ) {
        Bullet("脚本以明文 JSON 存本机，可直接打开阅读与备份")
        Bullet("脚本页点任意脚本即可编辑，改完再保存不影响其他脚本")
        Bullet("运行时用悬浮球一键启停，命中率与轨迹实时可视化")
        Spacer(Modifier.height(Ds.MD))
        Text(
            "引导可随时在「设置 → 新手引导」重新查看。现在开始，点「开始使用」进入脚本页。",
            style = Ds.BodySecondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

package dev.nymph.auto.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运行日志页：内存环形缓冲 + 本地滚动文件的最新 [CAP] 行。
 *
 * 不做自动滚动到底：排查时用户往往在一次失败后打开，停在底部反而
 * 看不到失败前的上下文；页面从最近的记录开始（倒序不重要，保持时间正序
 * 但从顶部开始），让用户自己滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    loadLines: () -> List<String>,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // 日志在磁盘上，读取必须离开主线程：800 行 / 每 2s 一次，
    // 放在组合线程会让列表滚动与点击明显掉帧。
    LaunchedEffect(Unit) {
        while (true) {
            // v2.9.4：读盘失败（IO 异常 / 文件被清理）不能让整个轮询协程取消，
            // 否则日志页永久停更且没有任何提示。单帧失败保留上一帧继续轮询。
            val next = runCatching { withContext(Dispatchers.IO) { loadLines() } }.getOrNull()
            if (next != null) lines = next
            delay(2000L)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("运行日志", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            lines = withContext(Dispatchers.IO) { loadLines() }
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.Share, contentDescription = "导出调试包")
                    }
                    IconButton(onClick = {
                        onClear()
                        scope.launch {
                            lines = withContext(Dispatchers.IO) { loadLines() }
                        }
                    }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        if (lines.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无日志", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "导入、运行、录制的过程会实时记录在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        style = MonoStyle,
                        color = lineColor(line),
                    )
                }
            }
        }
    }
}

@Composable
private fun lineColor(line: String): Color = when {
    line.contains(" E/") -> MaterialTheme.colorScheme.error
    line.contains(" W/") -> MaterialTheme.colorScheme.tertiary
    line.contains(" I/") -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

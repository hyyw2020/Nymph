package dev.nymph.auto.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.nymph.auto.ui.compose.AppViewModel
import dev.nymph.auto.ui.compose.NymphApp
import dev.nymph.auto.ui.compose.NymphTheme

/**
 * 唯一的一级界面（Compose）。
 *
 * 这里只做三件事：装配 ViewModel、注册 SAF 导入、把系统窗口交给 Compose。
 * 所有业务动作（导入/运行/录制/删除/导出）都在 [AppViewModel] 与
 * [dev.nymph.auto.ui.compose.NymphApp] 中，Activity 不持有业务状态，
 * 旋转或配置变更不会丢失正在进行的操作。
 *
 * 仍是单 Activity + 零网络权限 + 零 MediaProjection：无障碍服务负责手势
 * 与截图，本界面不申请任何录屏相关权限。
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /**
     * SAF 导入：用 ActivityResultContracts 取代已废弃的 onActivityResult。
     * MIME 用通配是因为 .zjs / .txt / .json 在多数文件管理器里没有统一声明。
     */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            vm.import(uri) { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用固定深色：显式声明系统栏为「深底浅图标」，
        // 否则系统处于浅色模式时状态栏图标会是深色，在深底上不可见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            NymphTheme {
                NymphApp(
                    vm = vm,
                    onPickImport = { importLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回时权限可能已变化，重新探测
        vm.refresh()
    }
}

package dev.nymph.auto.runner

import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.engine.NodeFinder
import dev.nymph.auto.engine.VisionPipeline

/**
 * 自动精灵 zdjl.* JS API 的真实实现（QuickJS 原生上调的 Kotlin 侧）。
 *
 * 与脚本执行器 [ScriptRunner] 绑定后生效：
 *  - vars 直接引用执行器的变量表（live 引用），JS 读写的是同一张表；
 *  - check(name) 按动作描述（desc）查找运行条件并求值——JS 里可写
 *    `if (zdjl.check('等待登录完成')) { ... }` 门控脚本条件；
 *  - findImage/findText 走无障碍截图 + NCC/节点通道（不创建 MediaProjection，
 *    不与系统录屏争抢权限——录屏兼容性硬约束）。
 *
 * 未绑定时（引擎单测/装配前）所有调用安全降级，不会崩。
 */
class RunnerZdjlApi(
    private val vision: VisionPipeline,
    private val nodeFinder: NodeFinder,
    private val onToast: (String) -> Unit = {},
    private val deviceInfoProvider: () -> Map<String, Any> = { emptyMap() },
) : ZdjlApi {

    private var runner: ScriptRunner? = null

    /** 未绑定执行器时的兜底变量表（保证 vars 引用非空）。 */
    private val detachedVars = mutableMapOf<String, Any?>()

    /** 执行器创建后调用：把 JS API 接到本次运行的变量表与条件树上。 */
    fun bind(runner: ScriptRunner) {
        this.runner = runner
    }

    override val vars: MutableMap<String, Any?>
        get() = runner?.vars?.live() ?: detachedVars

    override suspend fun check(condName: String): Boolean {
        val r = runner
        if (r == null) {
            RunLog.w("Zdjl", "check('$condName')：执行器未绑定，返回 false")
            return false
        }
        return r.checkCondition(condName)
    }

    override fun log(msg: String) {
        RunLog.i("Zdjl", msg)
    }

    override suspend fun toast(msg: String) {
        onToast(msg)
    }

    override suspend fun sleep(ms: Long) {
        if (ms > 0) kotlinx.coroutines.delay(ms)
    }

    override suspend fun findImage(b64: String): List<Pair<Int, Int>> {
        if (b64.isEmpty()) return emptyList()
        // 全屏区域 + 默认阈值；密度按 1:1（坐标以原图像素返回，与脚本录制口径一致）
        val hit = vision.findImage(b64, Region(), 80, 0.0, 1.0) ?: return emptyList()
        return listOf(hit.cx to hit.cy)
    }

    override suspend fun findText(text: String): List<Pair<Int, Int>> {
        if (text.isEmpty()) return emptyList()
        val hit = vision.findTextNode(text, regex = false) ?: return emptyList()
        return listOf(hit.centerX to hit.centerY)
    }

    override fun deviceInfo(): Map<String, Any> = deviceInfoProvider()
}

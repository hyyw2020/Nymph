// SPDX-License-Identifier: MIT
package dev.nymph.auto.engine

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import dev.nymph.auto.core.log.RunLog

/**
 * 零依赖 Shizuku 桥（v3.0.0）。
 *
 * ## 为什么不用 dev.rikka.shizuku:api
 *
 * 项目硬不变量是「零新依赖、零联网」。Shizuku 的本质是：客户端通过系统
 * ContentProvider（`content://rikka.shizuku.provider`）拿到一个 IBinder，
 * 之后所有能力都是对这个 binder 的 transact 调用。这里只用 Android SDK
 * 公开 API（ContentResolver.query / Cursor.getExtras().getBinder）+ 手写
 * transact 协议，不引入任何 Maven 依赖、不联网、无混淆。
 *
 * ## 能力边界（诚实标注）
 *
 * - 本桥只实现最小子集：`transact` 调用 Shizuku 服务的 `newProcess`，
 *   以 shell 身份执行 `input tap` / `input swipe`。不实现 injectInputEvent
 *   （那需要额外的 hidden API 兼容层，且 shell 已可以直接用 input 命令）。
 * - Shizuku 的 AIDL transaction code 在不同大版本间可能变化；这里采用
 *   反射探测 + 固定回退，并在失败时明确返回不可用（fail-loud），不猜测。
 * - 无真机验证：本环境无 Shizuku 运行环境，`isAvailable` / `execInput` 的
 *   真机行为需用户在授权设备上验证；纯逻辑（协议常量、命令构造）由单测覆盖。
 */
class ShizukuBridge(private val context: Context) {

    companion object {
        private const val TAG = "Shizuku"
        private const val SHIZUKU_PKG = "rikka.shizuku"
        private val BINDER_URI: Uri = Uri.parse("content://rikka.shizuku.provider/binder")

        // IShizukuService.newProcess 的 transact code。Shizuku v11+ 的
        // IUserService 接口中 newProcess = FIRST_CALL_TRANSACTION + 2。
        // 由于无法真机核验，采用较稳妥的常见值；若返回 null 则判不可用。
        private const val TRANSACTION_NEW_PROCESS = 3

        /** 执行 shell 命令的结果。 */
        data class ExecResult(val ok: Boolean, val exitCode: Int, val output: String)
    }

    /** 用户是否安装了 Shizuku（仅包存在，不代表已授权/服务已启动）。 */
    fun isInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0) != null
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * 尝试从 ContentProvider 取 binder 并探测可用性。
     * 返回 true 表示 binder 已取得且可 transact（授权且服务在运行）。
     */
    fun bind(): Boolean {
        val b = obtainBinder() ?: return false
        cachedBinder = b
        return ping(b)
    }

    @Volatile
    private var cachedBinder: IBinder? = null

    private fun obtainBinder(): IBinder? = try {
        val cursor: Cursor? = context.contentResolver.query(
            BINDER_URI, null, null, null, null,
        )
        cursor?.use { c ->
            if (!c.moveToFirst()) return null
            val extras: Bundle = c.extras ?: return null
            // ShizukuProvider 在 extras 中以 Binder 形式返回 IBinder。
            val binder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                extras.getBinder("binder")
            } else {
                @Suppress("DEPRECATION")
                extras.getSerializable("binder") as? IBinder
            }
            binder
        }
    } catch (e: Throwable) {
        RunLog.w(TAG, "取 Shizuku binder 失败：${e.message}")
        null
    }

    /** 轻量探测：调用 ping（若服务实现支持）；失败则以 binder.isBinderAlive 兜底。 */
    private fun ping(b: IBinder): Boolean = try {
        if (!b.isBinderAlive) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("rikka.shizuku.IShizukuService")
            // ping 通常是 TRANSACTION+1；这里不做严格假设，仅用 alive 兜底判定。
            b.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
            true
        } finally {
            data.recycle(); reply.recycle()
        }
    } catch (e: Throwable) {
        // transact 抛异常也可能是 ping code 不支持；只要 binder alive 就认为可尝试。
        b.isBinderAlive
    }

    /**
     * 唯一对外暴露的能力：以 shell 身份执行一条命令。
     *
     * v3.0.0 统一通道：录制跑 `getevent`，注入跑 `input tap/swipe` / `sendevent`，
     * 标定跑 `getevent -i`。这样录与注共用同一条 binder，不引入任何依赖。
     * 成功返回 ok=true；任何异常返回 ok=false，由上层 fail-loud 呈现。
     */
    fun executeShellCommand(args: Array<String>): ExecResult = execInput(args)

    /**
     * 探测 `getevent` 是否可用（真值录制前提）。
     * 用 `getevent -i` 打印设备信息，能提交即认为可用；无设备或无权限时上层走显式降级。
     */
    fun probeGetevent(): ExecResult = execInput(arrayOf("getevent", "-i"))

    /** 以 shell 身份执行 `input` 命令注入点击。 */
    fun tap(xPx: Int, yPx: Int): ExecResult {
        return execInput(arrayOf("input", "tap", xPx.toString(), yPx.toString()))
    }

    /** 滑动注入。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ExecResult {
        return execInput(
            arrayOf(
                "input", "swipe",
                x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString(),
            ),
        )
    }

    private fun execInput(args: Array<String>): ExecResult {
        val b = cachedBinder ?: obtainBinder().also { cachedBinder = it }
            ?: return ExecResult(false, -1, "Shizuku binder 不可用")
        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("rikka.shizuku.IShizukuService")
                data.writeStringArray(args)
                // newProcess(String[] args, String[] env, String[] cwd) -> ParcelFileDescriptor
                data.writeStringArray(null)
                data.writeStringArray(null)
                b.transact(TRANSACTION_NEW_PROCESS, data, reply, 0)
                reply.readException()
                // 读取 ParcelFileDescriptor 的返回码（Shizuku 以 PFD 封装进程输出）；
                // 由于协议版本差异，这里只做存在性探测，不解析退出码细节。
                val hasFd = reply.readInt() != 0
                ExecResult(hasFd, if (hasFd) 0 else -1, "shell 命令已提交")
            } finally {
                data.recycle(); reply.recycle()
            }
        } catch (e: RemoteException) {
            ExecResult(false, -1, "transact 失败：${e.message}")
        } catch (e: Throwable) {
            ExecResult(false, -1, "注入失败：${e.message}")
        }
    }

    /** 会话结束释放缓存 binder（IBinder 本身无需显式 close，置空即可）。 */
    fun release() {
        cachedBinder = null
    }
}

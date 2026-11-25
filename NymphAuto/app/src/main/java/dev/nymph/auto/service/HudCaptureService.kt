// SPDX-License-Identifier: MIT
package dev.nymph.auto.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.nymph.auto.NymphApp
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.engine.FrameSourceSelector
import dev.nymph.auto.engine.MediaProjectionSource

/**
 * HUD 取帧服务——**争抢录屏权限模式**的宿主（用户显式选择时启用）。
 *
 * Android 14+（targetSdk 34+）要求 MediaProjection 以前台服务运行、类型 `mediaProjection`。
 * 本服务：
 *  - 接收用户授权的 resultCode/data，创建 [MediaProjectionSource] 并保持会话；
 *  - 通过 [LocalBinder] 暴露当前取帧源给 `VisionPipeline` 注入；
 *  - 授权过期 / Android 14+ 进程重启后，调用方须重新走授权流程（[FrameSourceSelector.mpAuthorized] 复位）。
 *
 * 默认模式（无障碍截图）不启动本服务，零 MediaProjection、不与录屏 App 抢权限。
 * 本服务零网络、不导出、不可被外部 App 绑定。
 */
class HudCaptureService : Service() {

    private val binder = LocalBinder()
    @Volatile var source: MediaProjectionSource? = null
        private set

    inner class LocalBinder : Binder() {
        fun service(): HudCaptureService = this@HudCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<android.content.Intent>(EXTRA_DATA)
        if (code == -1 || data == null) {
            RunLog.w("HudCapture", "缺少授权参数，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }
        val src = MediaProjectionSource(this, code, data)
        if (!src.start()) {
            RunLog.w("HudCapture", "MediaProjection 建立失败，降级无障碍模式")
            FrameSourceSelector.mpAuthorized = false
            stopSelf()
            return START_NOT_STICKY
        }
        source = src
        FrameSourceSelector.mpAuthorized = true
        FrameSourceSelector.mode = dev.nymph.auto.engine.FrameMode.MEDIA_PROJECTION
        RunLog.i("HudCapture", "争抢录屏权限取帧模式已启用")
        return START_STICKY
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, dev.nymph.auto.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, NymphApp.CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NymphAuto")
            .setContentText("HUD 取帧中（录屏权限模式）")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
    }

    override fun onDestroy() {
        source?.close()
        source = null
        FrameSourceSelector.mpAuthorized = false
        if (FrameSourceSelector.mode == dev.nymph.auto.engine.FrameMode.MEDIA_PROJECTION) {
            FrameSourceSelector.mode = dev.nymph.auto.engine.FrameMode.ACCESSIBILITY
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, HudCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /** 绑定取帧源；connection 由调用方管理并在不用时 unbind。 */
        fun bind(context: Context, connection: ServiceConnection) {
            context.bindService(
                Intent(context, HudCaptureService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }
}

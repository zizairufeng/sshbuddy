package com.czk.sshai

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 前台服务 —— 唯一目的：让进程在整个 App 退到后台后仍被视为「前台」，不被系统冻结。
 *
 * 常见误区：以为 SSH 断线是 App 崩了。实际上 Android 12+ 会在 App 进后台后冻结其网络与 CPU，
 * 挂在 ViewModel 里的连接自然就断了。挂在带常驻通知的前台服务上才是正解（Termius 同理）。
 *
 * 注意：真正的连接仍由 ViewModel 里的 SshSession 持有；服务只负责「保活 + 通知」，
 * 不做重复的连接管理，避免两处状态打架。
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            SessionBus.requestDisconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }

        NotificationHelper.ensureChannel(this)
        val n = NotificationHelper.build(this, SessionBus.snapshotTabs(), SessionBus.snapshotHosts())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTI_ID, n)
        }
        // 被系统杀掉后不自动重启（重连由 App 内逻辑负责，避免复活一个空壳服务）
        return START_NOT_STICKY
    }

    /**
     * Android 15 起 dataSync 前台服务有 6 小时上限，超时会回调这里。
     * 到点后主动断开并停止服务，比让系统直接杀进程更可控（用户至少能看到连接被关闭）。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        SessionBus.requestDisconnectAll()
        runCatching { stopSelf(startId) }
    }

    companion object {
        const val ACTION_DISCONNECT = "com.czk.sshai.DISCONNECT_ALL"
    }
}

/**
 * 一个极简的「服务 ↔ ViewModel」桥。
 * 服务被系统的通知按钮触发断开时，通过这里回调给 ViewModel，而不是自己持有连接。
 */
object SessionBus {

    @Volatile private var tabsProvider: (() -> List<Tab>)? = null
    @Volatile private var hostsProvider: (() -> List<SshConfig>)? = null
    @Volatile private var disconnectHandler: (() -> Unit)? = null

    fun bind(
        tabs: () -> List<Tab>,
        hosts: () -> List<SshConfig>,
        onDisconnectAll: () -> Unit,
    ) {
        tabsProvider = tabs
        hostsProvider = hosts
        disconnectHandler = onDisconnectAll
    }

    fun unbind() {
        tabsProvider = null
        hostsProvider = null
        disconnectHandler = null
    }

    fun snapshotTabs(): List<Tab> = tabsProvider?.invoke() ?: emptyList()

    fun snapshotHosts(): List<SshConfig> = hostsProvider?.invoke() ?: emptyList()

    fun requestDisconnectAll() {
        disconnectHandler?.invoke()
    }
}

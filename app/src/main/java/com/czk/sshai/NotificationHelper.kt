package com.czk.sshai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 常驻通知：让 SSH 会话挂在通知栏上，切到微信 / 熄屏也不会被系统回收。
 * 这也是主流客户端（Termius / JuiceSSH）保证「回消息回来连接还在」的做法。
 */
object NotificationHelper {

    const val CHANNEL_ID = "sshai.session"
    const val NOTI_ID = 1001

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "SSH 会话",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持 SSH 连接不断开"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    /** 按当前页签状态生成/刷新通知内容 */
    fun update(c: Context, tabs: List<Tab>, hosts: List<SshConfig>) {
        ensureChannel(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (tabs.none { it.isLive() }) {
            nm.cancel(NOTI_ID)
            return
        }
        val n = build(c, tabs, hosts)
        nm.notify(NOTI_ID, n)
    }

    /**
     * 正在退避等待重连的页签也算活跃：否则退避的那几十秒里通知会消失、
     * 前台服务被 MainActivity 停掉，App 进后台就可能直接被系统冻结。
     */
    private fun Tab.isLive(): Boolean = connected || connecting || reconnectingAt != null

    fun build(c: Context, tabs: List<Tab>, hosts: List<SshConfig>): Notification {
        val active = tabs.filter { it.isLive() }
        val title = when {
            active.size == 1 -> {
                val t = active.first()
                val h = hosts.find { it.id == t.hostId }
                val where = h?.let { "${it.user}@${it.host}" } ?: t.title
                when {
                    t.connected -> "已连接 $where"
                    t.reconnectingAt != null -> "重连中 $where（第 ${t.reconnectingAt} 次）"
                    else -> "正在连接 $where"
                }
            }
            active.isNotEmpty() -> "${active.size} 个 SSH 会话在线"
            else -> "SSH 会话"
        }
        val text = active.joinToString("、") { t ->
            val host = hosts.find { it.id == t.hostId }?.host ?: t.title
            if (t.reconnectingAt != null) "$host（重连中）" else host
        }.ifBlank { "点开回到终端" }

        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).apply {
                // 不能带 CLEAR_TOP：那会把栈里的 RdpActivity 一起清掉，正在跑的 RDP
                // 会话会被销毁。REORDER_TO_FRONT 只把主界面提到前面。
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            c, 1,
            Intent(c, SessionService::class.java).setAction(SessionService.ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "断开", disconnect)
            .build()
    }
}

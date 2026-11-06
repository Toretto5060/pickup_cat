package com.toretto5060.pickupcatapp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.toretto5060.pickupcatapp.R

/**
 * 守护服务：前台服务（静默常驻通知，IMPORTANCE_MIN）+ START_STICKY。
 *
 * 前台优先级能显著降低被 HyperOS「一键清理」杀掉的概率（进程没死 = 0 秒恢复）；
 * 万一还是被杀，[PermissionMonitor] 的系统侧秒级心跳会在 1 秒内把进程拉回来。
 *
 * 另有一路 **FGS 内的定时权限检查**（[tick]）：不经过 AlarmManager 广播，只要进程活着就会跑。
 * 这样即使「自启动」被关导致后台广播被系统限制，权限异常提醒仍能下发 —— 不能只用被禁的能力
 * 去提醒能力被禁。首页 `checkNow` 与心跳是另外两路兜底。
 *
 * 只在首页（前台）用 [start] 启动 —— Android 8+ 禁止后台启动服务、Android 12+ 禁止后台启动 FGS；
 * 系统重启 START_STICKY 服务时由系统发起，不受该限制。`startForeground` 用 runCatching 兜底。
 */
internal class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            runCatching { PermissionMonitor.checkNow(this@KeepAliveService) }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        handler.postDelayed(tick, CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        PermissionMonitor.onCheck(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PermissionMonitor.scheduleImmediate(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        PermissionMonitor.scheduleImmediate(this)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keep_alive_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            this, 11,
            Intent(this, ExpressListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 2002
        private const val CHECK_INTERVAL_MS = 5_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, KeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            }
        }
    }
}

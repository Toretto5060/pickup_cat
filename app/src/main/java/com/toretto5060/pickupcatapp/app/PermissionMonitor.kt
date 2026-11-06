package com.toretto5060.pickupcatapp.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.express.ExpressListenService

/**
 * 进程守护 + 后台权限轮询 + 权限异常通知。
 *
 * 目标：清理后台（HyperOS SwipeUpClean / OneKeyClean kill 进程并解绑 NotificationListenerService）后
 * **5 秒内**自动恢复。做法是链式秒级心跳：亮屏每 [HEARTBEAT_ACTIVE] 排一个 `setExact` 闹钟，进程死后
 * 系统侧这个闹钟仍在，触发 [PermissionCheckReceiver] 拉起进程，[onCheck] 主动 requestRebind 恢复监听；
 * 划掉卡片 / 服务断开时另排一个 [IMMEDIATE_DELAY] 的 `setAlarmClock` —— 它是系统最高优先级闹钟，
 * 不受 Doze / 厂商对齐影响，能在几百毫秒内送达。
 *
 * 权限状态每 [STATE_CHECK_INTERVAL] 检查一次，另外 FGS 内的 Handler 定时与首页 `checkNow` 会强制检查：
 * **提醒通知不能只依赖后台广播**，因为「自启动被关」恰恰会让广播/闹钟被系统限制，形成
 * 「用被禁的能力去提醒能力被禁」的死锁。状态变化才刷新通知，齐备时撤销。
 * 亮屏用 setExact 绕开小米 proxyAlarm 对 allow-while-idle 闹钟的对齐，熄屏才用 allow-while-idle。
 */
internal object PermissionMonitor {
    const val ACTION_CHECK = "com.toretto5060.pickupcatapp.PERMISSION_CHECK"
    const val ACTION_CHECK_IMMEDIATE = "com.toretto5060.pickupcatapp.PERMISSION_CHECK_IMMEDIATE"
    private const val CHANNEL_ID = "permission_alert"
    private const val NOTIFICATION_ID = 2001
    private const val PREFS = "permission_monitor"
    private const val KEY_LAST_ALERT = "last_alert_signature"
    private const val KEY_LAST_REBIND = "last_rebind_elapsed"
    private const val KEY_LAST_FULL_CHECK = "last_full_check_elapsed"
    private const val KEY_CAPTURE_ENABLED = "capture_enabled_cached"
    private const val HEARTBEAT_ACTIVE = 1_000L
    private const val HEARTBEAT_IDLE = 60_000L
    private const val IMMEDIATE_DELAY = 300L
    private const val STATE_CHECK_INTERVAL = 5_000L
    private const val REBIND_COOLDOWN = 2_000L

    fun start(ctx: Context) {
        createChannel(ctx)
        scheduleNext(ctx)
        onCheck(ctx)
        KeepAliveService.start(ctx) // 只在前台 Activity 里启动，避开 Android 8+ 后台启动服务限制
    }

    /** 强制按最新权限状态刷新提醒通知：首页 onResume / FGS 定时检查调用，不受 5 秒节流限制。 */
    fun checkNow(ctx: Context) = fullStateCheck(ctx, SystemClock.elapsedRealtime())

    /** 链式单次心跳：每次触发后再排下一次，进程被清掉也不影响系统侧已排的闹钟。 */
    fun scheduleNext(ctx: Context) = schedule(ctx, ACTION_CHECK, 7, heartbeatDelay(ctx))

    /** 亮屏 1 秒、熄屏 60 秒：熄屏本来就进 Doze，高频唤醒只会白耗电。 */
    private fun heartbeatDelay(ctx: Context): Long =
        if (isInteractive(ctx)) HEARTBEAT_ACTIVE else HEARTBEAT_IDLE

    private fun isInteractive(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    /**
     * 划掉卡片 / 监听服务断开 / 服务销毁：用 `setAlarmClock` 排最高优先级即时闹钟，
     * 系统会把它当「用户闹钟」，不会被 Doze 或厂商 proxyAlarm 对齐推迟；状态栏的闹钟图标只存在这几百毫秒。
     */
    fun scheduleImmediate(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am != null) {
            val pi = checkIntent(ctx, ACTION_CHECK_IMMEDIATE, 9)
            val show = PendingIntent.getActivity(
                ctx, 10,
                Intent(ctx, ExpressListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerAt = System.currentTimeMillis() + IMMEDIATE_DELAY
            if (runCatching {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
                }.isSuccess) return
        }
        schedule(ctx, ACTION_CHECK_IMMEDIATE, 9, IMMEDIATE_DELAY)
    }

    private fun schedule(ctx: Context, action: String, requestCode: Int, delay: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = checkIntent(ctx, action, requestCode)
        val triggerAt = System.currentTimeMillis() + delay
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        // 亮屏用 setExact（非 idle 分支），避免厂商把 allow-while-idle 闹钟对齐到 5 秒以上；
        // 熄屏才走 allow-while-idle，保证 Doze 下仍能唤醒。
        if (canExact) {
            val ok = if (isInteractive(ctx)) {
                runCatching { am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, intent) }.isSuccess
            } else {
                runCatching {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
                }.isSuccess
            }
            if (ok) return
        }
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent) }
    }

    private fun checkIntent(ctx: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, PermissionCheckReceiver::class.java).setAction(action)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 前台 / 后台 / 每次心跳共用：先续心跳，再按节流检查权限，再判定服务是否掉线并主动 rebind。 */
    fun onCheck(ctx: Context) {
        scheduleNext(ctx) // 无论后面发生什么都先把下一次心跳排上，链式不断
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - prefs.getLong(KEY_LAST_FULL_CHECK, -STATE_CHECK_INTERVAL) >= STATE_CHECK_INTERVAL) {
            fullStateCheck(ctx, elapsed)
        }

        // 权限齐备但监听服务没连上 = 被清理后台/解绑，立即主动重绑；2 秒冷却避免失败时死循环。
        val captureEnabled = prefs.getBoolean(KEY_CAPTURE_ENABLED, false)
        if (captureEnabled && !ExpressListenService.isConnected() &&
            elapsed - prefs.getLong(KEY_LAST_REBIND, -REBIND_COOLDOWN) >= REBIND_COOLDOWN
        ) {
            prefs.edit { putLong(KEY_LAST_REBIND, elapsed) }
            PermissionGuard.ensureCapture(ctx)
        }
    }

    /** 完整检查：实时读 4 项权限 → 缓存总闸 → 按状态签名更新/撤销提醒通知。 */
    private fun fullStateCheck(ctx: Context, elapsed: Long) {
        val state = PermissionGuard.state(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_LAST_FULL_CHECK, elapsed)
            putBoolean(KEY_CAPTURE_ENABLED, state.captureEnabled)
        }
        updateAlert(ctx, state)
    }

    /** 提醒通知只在权限状态真的变化时刷新；异常时常驻，权限齐备由这里撤销。 */
    private fun updateAlert(ctx: Context, state: PermissionState) {
        createChannel(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = state.missing
            .filter { it != PermissionKind.POST_NOTIFICATIONS }
            .joinToString(",") { it.name }
        val signature = "${state.captureEnabled}|$names|${state.postNotificationsGranted}"
        if (prefs.getString(KEY_LAST_ALERT, null) == signature) return
        prefs.edit { putString(KEY_LAST_ALERT, signature) }

        val mgr = NotificationManagerCompat.from(ctx)
        if (state.captureEnabled) {
            mgr.cancel(NOTIFICATION_ID)
            return
        }
        if (!PermissionGuard.postNotificationsGranted(ctx) || !mgr.areNotificationsEnabled()) return

        val text = ctx.getString(
            R.string.permission_alert_text,
            state.missing
                .filter { it != PermissionKind.POST_NOTIFICATIONS }
                .joinToString("、") { ctx.getString(nameOf(it)) }
                .ifBlank { ctx.getString(R.string.permission_alert_unknown) },
        )
        val openApp = PendingIntent.getActivity(
            ctx, 8,
            Intent(ctx, ExpressListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(ctx.getString(R.string.permission_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            // 权限异常期间常驻：用户划掉后若状态没变，签名去重会让通知再也不出现。
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { mgr.notify(NOTIFICATION_ID, notification) }
    }

    private fun nameOf(kind: PermissionKind): Int = when (kind) {
        PermissionKind.APP_LIST -> R.string.permission_name_app_list
        PermissionKind.NOTIFICATION_ACCESS -> R.string.permission_name_notification_access
        PermissionKind.AUTO_START -> R.string.permission_name_auto_start
        PermissionKind.BATTERY -> R.string.permission_name_battery
        PermissionKind.POST_NOTIFICATIONS -> R.string.permission_name_post_notifications
    }

    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.permission_alert_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}

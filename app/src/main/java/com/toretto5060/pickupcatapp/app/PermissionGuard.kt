package com.toretto5060.pickupcatapp.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.toretto5060.pickupcatapp.express.ExpressListenService

internal enum class PermissionKind { APP_LIST, NOTIFICATION_ACCESS, AUTO_START, BATTERY, POST_NOTIFICATIONS }

/** 自启动是厂商私有开关：只有小米系能用其自定义 AppOps 真实检测，其余 ROM 只能标记 UNKNOWN。 */
internal enum class AutoStartState { GRANTED, DENIED, UNKNOWN }

/**
 * 决定「取件码解析是否启用」的 4 项权限 + 1 项通知能力。
 *
 * - `APP_LIST`：国内厂商运行时权限 `com.android.permission.GET_INSTALLED_APPS`（AOSP 无此权限，视为已授权）。
 * - `NOTIFICATION_ACCESS`：系统通知使用权（特殊访问权限）。
 * - `AUTO_START` / `BATTERY`：后台自启动与电池优化白名单，项目要常驻后台监听。
 * - `POST_NOTIFICATIONS`：只影响「权限异常提醒通知」能否发出，**不阻塞取件码解析**。
 */
internal data class PermissionState(
    val appListGranted: Boolean,
    val notificationAccessGranted: Boolean,
    val autoStart: AutoStartState,
    val batteryUnrestricted: Boolean,
    val postNotificationsGranted: Boolean,
) {
    /** 解析总闸：4 项齐备才启用；自启动 UNKNOWN（无法检测的 ROM）不误伤。 */
    val captureEnabled: Boolean get() = appListGranted && notificationAccessGranted &&
        batteryUnrestricted && autoStart != AutoStartState.DENIED

    val allGranted: Boolean get() = captureEnabled && postNotificationsGranted

    val missing: List<PermissionKind> get() = buildList {
        if (!appListGranted) add(PermissionKind.APP_LIST)
        if (!notificationAccessGranted) add(PermissionKind.NOTIFICATION_ACCESS)
        if (autoStart == AutoStartState.DENIED) add(PermissionKind.AUTO_START)
        if (!batteryUnrestricted) add(PermissionKind.BATTERY)
        if (!postNotificationsGranted) add(PermissionKind.POST_NOTIFICATIONS)
    }
}

internal object PermissionGuard {
    const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
    const val REQ_APP_LIST = 1001
    const val REQ_POST_NOTIFICATIONS = 1002

    private const val PREFS = "permission_guard"
    private const val KEY_POST_NOTIF_ASKED = "post_notifications_asked"
    private const val MIUI_OP_AUTO_START = 10008

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun runtimePermissionExists(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPermissionInfo(APP_LIST_PERMISSION, 0) != null
    }.getOrDefault(false)

    fun appListGranted(ctx: Context): Boolean =
        if (!runtimePermissionExists(ctx)) true
        else ContextCompat.checkSelfPermission(ctx, APP_LIST_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun notificationAccessGranted(ctx: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }.getOrDefault(false)

    /** 电池优化白名单（电量无限制）；取不到 PowerManager 时按不受限处理，避免误停解析。 */
    fun batteryUnrestricted(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(true)

    fun postNotificationsGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun postNotificationsAsked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_POST_NOTIF_ASKED, false)

    fun markPostNotificationsAsked(ctx: Context) {
        prefs(ctx).edit { putBoolean(KEY_POST_NOTIF_ASKED, true) }
    }

    /**
     * 自启动检测：无自启动管理的 ROM（AOSP / 三星等）直接 GRANTED；
     * 小米系用 MIUI 自定义 `OP_AUTO_START = 10008` 真实读开关；其它国产 ROM 无可靠 API → UNKNOWN。
     */
    fun autoStartState(ctx: Context): AutoStartState {
        val maker = Build.MANUFACTURER.lowercase()
        val vendors = listOf("xiaomi", "redmi", "huawei", "honor", "oppo", "oneplus", "realme", "vivo", "iqoo", "meizu")
        if (vendors.none { maker.contains(it) }) return AutoStartState.GRANTED
        if (!maker.contains("xiaomi") && !maker.contains("redmi")) return AutoStartState.UNKNOWN
        // compileSdk 37 的 SDK stub 只剩 String op 重载，数字 op 版被隐藏，用反射调用；
        // hidden API 受限或 ROM 不支持时回落 UNKNOWN（不阻塞解析）。
        return runCatching {
            val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getDeclaredMethod(
                "checkOpNoThrow", Integer.TYPE, Integer.TYPE, String::class.java,
            )
            val mode = method.invoke(aom, MIUI_OP_AUTO_START, Process.myUid(), ctx.packageName) as Int
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> AutoStartState.GRANTED
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> AutoStartState.DENIED
                else -> AutoStartState.UNKNOWN
            }
        }.getOrDefault(AutoStartState.UNKNOWN)
    }

    fun state(ctx: Context): PermissionState = PermissionState(
        appListGranted = appListGranted(ctx),
        notificationAccessGranted = notificationAccessGranted(ctx),
        autoStart = autoStartState(ctx),
        batteryUnrestricted = batteryUnrestricted(ctx),
        postNotificationsGranted = postNotificationsGranted(ctx),
    )

    /** 捕获服务与首页红/绿指示灯共用的总闸。 */
    fun captureEnabled(ctx: Context): Boolean = state(ctx).captureEnabled

    fun requestAppList(activity: Activity) {
        ActivityCompat.requestPermissions(activity, arrayOf(APP_LIST_PERMISSION), REQ_APP_LIST)
    }

    /** Android 13+ 的 POST_NOTIFICATIONS 系统授权框（首次进入时请求）。 */
    fun requestPostNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS,
            )
        }
    }

    /** 权限仍被拒绝、且系统不会再弹框（勾过「拒绝且不再询问」或不提供动态申请）。 */
    fun shouldOpenAppDetails(activity: Activity): Boolean =
        runtimePermissionExists(activity) &&
            !appListGranted(activity) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, APP_LIST_PERMISSION)

    /** 提示条/设置项统一入口：所有授权都走系统页面或系统权限框。 */
    fun openSettingsFor(activity: Activity, kind: PermissionKind) {
        when (kind) {
            PermissionKind.APP_LIST -> requestAppList(activity)
            PermissionKind.NOTIFICATION_ACCESS -> openNotificationSettings(activity)
            PermissionKind.AUTO_START -> openAutoStartSettings(activity)
            PermissionKind.BATTERY -> requestBatteryExemption(activity)
            PermissionKind.POST_NOTIFICATIONS -> {
                // 勾过「不再询问」后系统不会再弹框，只能引导到应用通知设置页。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    postNotificationsAsked(activity) &&
                    !postNotificationsGranted(activity) &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.POST_NOTIFICATIONS,
                    )
                ) {
                    openAppNotificationSettings(activity)
                } else {
                    requestPostNotifications(activity)
                }
            }
        }
    }

    private fun openNotificationSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 会弹系统对话框直接申请加入白名单。 */
    private fun requestBatteryExemption(ctx: Context) {
        if (batteryUnrestricted(ctx)) return
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(direct) }.isSuccess) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 自启动没有系统 Intent，只能逐个尝试厂商设置页组件，全部失败回落到应用详情。 */
    private fun openAutoStartSettings(ctx: Context) {
        val components = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.meizu.safe/com.meizu.safe.security.SecureMainActivity",
        )
        components.forEach { flat ->
            val parts = flat.split("/", limit = 2)
            val intent = Intent().setComponent(ComponentName(parts[0], parts[1]))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(intent) }.isSuccess) return
        }
        openAppDetails(ctx)
    }

    private fun openAppNotificationSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(ctx) }
    }

    fun openAppDetails(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 权限齐备时主动重新绑定通知监听服务，保证解析立刻恢复。 */
    fun ensureCapture(ctx: Context) {
        if (!captureEnabled(ctx)) return
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(ctx, ExpressListenService::class.java),
            )
        }
    }
}

package com.toretto5060.pickupcatapp.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 唯一职责：提供系统锁屏能力 `GLOBAL_ACTION_LOCK_SCREEN`。
 *
 * 不接收/读取窗口内容（服务配置 canRetrieveWindowContent=false、eventTypes 只留最小的一项），
 * 不监听任何业务事件；只有 [com.toretto5060.pickupcatapp.express.ExpressWidgetProvider] 在
 * 「无待取件时隐藏组件」开启 + 0 件 + 用户开启「双击锁屏」并双击小部件时才调用 [lockScreen]。
 *
 * 第三方 App 没有免权限的锁屏 API（DevicePolicyManager 要激活设备管理器、PowerManager.goToSleep
 * 要系统签名权限），只能走无障碍。用户需在 系统设置 → 无障碍 里手动开启本服务；
 * 未开启时 [lockScreen] 返回 false，双击静默无效，不影响「空态点击不跳转」。
 */
class ExpressLockAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: ExpressLockAccessibilityService? = null

        /** 系统「无障碍」里本服务是否已启用（读 Settings.Secure，比进程内实例更可靠）。 */
        fun isEnabled(ctx: Context): Boolean = runCatching {
            val className = ExpressLockAccessibilityService::class.java.name
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            enabled.split(':').any { entry ->
                val cn = ComponentName.unflattenFromString(entry)
                cn != null && cn.packageName == ctx.packageName && cn.className == className
            }
        }.getOrDefault(false)

        /** 执行锁屏；服务未连接（没授权）时返回 false，调用方静默忽略。 */
        fun lockScreen(): Boolean = runCatching {
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: false
        }.getOrDefault(false)
    }
}

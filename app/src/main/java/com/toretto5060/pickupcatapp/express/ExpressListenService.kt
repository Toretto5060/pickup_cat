package com.toretto5060.pickupcatapp.express

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 取件码捕获的**唯一主通道**：获得「通知使用权」后收到全部 App 的通知（短信通知带完整正文），
 * 文本交给规则引擎，命中的才入库。独立 App 不依赖 root / LSPosed / Xposed。
 *
 * 捕获默认开启（没有总开关）；KEY_LISTEN_APPS 为空时解析全部通知，非空时只解析勾选的 App，
 * 短信 App（[SMS_PKGS]）始终解析，不受勾选影响。
 */
internal class ExpressListenService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val listen = ExpressStore.listenApps(this)
        // 短信始终解析；其余在勾选了白名单时只解析勾选的（空白名单 = 全部）。
        if (pkg !in SMS_PKGS && listen.isNotEmpty() && pkg !in listen) return

        val text = extractText(sbn)
        if (text.isBlank()) return

        val parsed = ExpressStore.parseForNotification(this, pkg, text) ?: return
        runCatching {
            ExpressStore.insertPickup(this, parsed)
            // 数据变化后刷新小部件（如已添加）。
            ExpressWidgetProvider().updateAll(this)
        }.onFailure { Log.w(TAG, "notify insert failed", it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        private const val TAG = "PickupCatListen"
        /** 短信 App 的包名（HyperOS 的信息是 com.android.mms；AOSP 旧信息是 com.android.messaging）。 */
        private val SMS_PKGS = setOf("com.android.mms", "com.android.messaging")
    }

    /** 把通知里的可读文本拼成一个字符串，供规则引擎解析。 */
    private fun extractText(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras ?: return ""
        val parts = ArrayList<String>()
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { parts.add(it) }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { parts.add(it) }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { parts.add(it) }
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.let { parts.add(it) }
        (extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))?.forEach {
            parts.add(it.toString())
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }
}

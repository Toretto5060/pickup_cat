package com.toretto5060.pickupcatapp.express

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.toretto5060.pickupcatapp.app.PermissionGuard
import com.toretto5060.pickupcatapp.app.PermissionMonitor

/**
 * 取件码捕获的**唯一主通道**：获得「通知使用权」后收到全部 App 的通知（短信通知带完整正文），
 * 文本交给规则引擎，命中的才入库。独立 App 不依赖 root / LSPosed / Xposed。
 *
 * **只有「获取应用列表」与「通知使用权」两项权限都开启时才解析**（[PermissionGuard.captureEnabled]），
 * 任一缺失直接丢弃通知；首页设置按钮旁的红/绿指示灯与这里共用同一判定。
 * KEY_LISTEN_APPS 为空时解析全部通知，非空时只解析勾选的 App，
 * 短信 App（[SMS_PKGS]）始终解析，不受勾选影响。
 *
 * **补录**：服务重新连上（进程从「未运行」到「运行」，含守护拉起 / 解锁后重绑）时，
 * [backfillActiveNotifications] 扫描通知栏里**仍挂着**的通知，复用 [parseSbn] 的同一套判定与规则，
 * 未取件列表已有相同取件码就跳过、没有才补录，弥补停机期间漏收的通知。
 * 已被清除 / 被新通知覆盖的历史通知拿不到（NLS 只暴露 active notifications），无法 100% 兜底。
 */
internal class ExpressListenService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "listener connected")
        // 刚绑定时 activeNotifications 可能还没就绪，延迟一点再补录；每次绑定/重绑都会跑，幂等。
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { backfillActiveNotifications() }
                .onFailure { Log.w(TAG, "backfill failed", it) }
        }, BACKFILL_DELAY_MS)
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.i(TAG, "listener disconnected, schedule immediate guard check")
        PermissionMonitor.scheduleImmediate(this)
    }

    override fun onDestroy() {
        connected = false
        PermissionMonitor.scheduleImmediate(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val parsed = parseSbn(sbn) ?: return
        runCatching {
            ExpressStore.insertPickup(this, parsed)
            // 数据变化后刷新小部件（如已添加）。
            ExpressWidgetProvider().updateAll(this)
        }.onFailure { Log.w(TAG, "notify insert failed", it) }
    }

    /**
     * 补录停机期间的通知：只看通知栏现存的 [activeNotifications]，且必须与实时通知走完全一样的
     * 权限总闸 / 解析 App 白名单 / 规则解析（[parseSbn]）。去重两道：
     * 1. 这里按「未取件列表」的 (code, address, cabinet) 先判，已存在直接跳过；
     * 2. [ExpressStore.insertPickup] 落到 Provider 后仍有 sms_hash(picked=0) +
     *    (code,address,cabinet,picked=0) 两层去重，全部只比对「待取件列表」，已取件历史不参与。
     */
    private fun backfillActiveNotifications() {
        if (!PermissionGuard.captureEnabled(this)) return
        val active = runCatching { activeNotifications }.getOrNull()
        if (active == null) {
            Log.w(TAG, "backfill: active notifications unavailable")
            return
        }
        Log.i(TAG, "backfill: scanning ${active.size} active notifications")
        val existing: MutableSet<Triple<String, String, String>> = runCatching {
            ExpressStore.queryAll(this, picked = false)
                .map { Triple(it.code, it.address, it.cabinet) }
                .toMutableSet()
        }.getOrDefault(mutableSetOf())
        var added = false
        for (sbn in active) {
            val parsed = parseSbn(sbn) ?: continue
            val key = Triple(parsed.code, parsed.address, parsed.cabinet)
            if (key in existing) {
                Log.i(TAG, "backfill skip existing: ${parsed.code}")
                continue
            }
            runCatching {
                ExpressStore.insertPickup(this, parsed)
                existing.add(key)
                added = true
                Log.i(TAG, "backfill inserted: ${parsed.code}")
            }.onFailure { Log.w(TAG, "backfill insert failed", it) }
        }
        Log.i(TAG, "backfill done: added=$added")
        if (added) ExpressWidgetProvider().updateAll(this)
    }

    /** 通知 → 取件码：总闸、自身包名、白名单、文本抽取、规则解析，实时通知与补录共用。 */
    private fun parseSbn(sbn: StatusBarNotification?): ParsedPickup? {
        // 总闸：任何时刻任一权限缺失都直接丢弃通知，不做任何解析/入库。
        if (!PermissionGuard.captureEnabled(this)) return null
        val sbn = sbn ?: return null
        val pkg = sbn.packageName ?: return null
        if (pkg == packageName) return null
        val listen = ExpressStore.listenApps(this)
        // 短信始终解析；其余在勾选了白名单时只解析勾选的（空白名单 = 全部）。
        if (pkg !in SMS_PKGS && listen.isNotEmpty() && pkg !in listen) return null
        val text = extractText(sbn)
        if (text.isBlank()) return null
        return ExpressStore.parseForNotification(this, pkg, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        private const val TAG = "PickupCatListen"
        private const val BACKFILL_DELAY_MS = 800L

        /** 同进程内的绑定状态：进程被清掉后静态变量归 false，守护检查据此主动 requestRebind。 */
        @Volatile
        private var connected = false

        @JvmStatic
        fun isConnected(): Boolean = connected

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

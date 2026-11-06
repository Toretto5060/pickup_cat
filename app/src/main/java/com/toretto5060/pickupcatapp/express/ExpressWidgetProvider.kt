package com.toretto5060.pickupcatapp.express

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.app.AppSettings
import com.toretto5060.pickupcatapp.app.ExpressListActivity
import com.toretto5060.pickupcatapp.app.ExpressLockAccessibilityService
import com.toretto5060.pickupcatapp.app.ExpressWidgetClickActivity

/**
 * 待取件小部件：桌面与负一屏共用同一个，列表**可上下滚动**（用户明确要求）。
 *
 * - **高度最多 3 格**（express_widget_info.xml 的 maxResizeHeight），宽度不限。
 * - **resize 零代码**：行高固定（express_widget_row.xml），ListView 用 match_parent 填满
 *   桌面给的可见区并保留上下滚动；尺寸变化由桌面重新布局自然适配，不需要重写
 *   onAppWidgetOptionsChanged。此前几轮「读桌面报告尺寸 → 动态换算行高」的方案全部推翻：
 *   HyperOS 的 newOptions 是拖拽过渡值、getAppWidgetOptions 返回缓存旧值、SIZES 只给
 *   可选档位上限，三个来源都不可靠，依赖它们算行高在 5×2↔5×3 来回 resize 时必然漂移、
 *   无法恢复（详见记忆库避坑指南）。
 * - **背景**：固定走「跟随系统」判定 [HyperOsGlass.isSoftGlassAvailable]——澎湃 OS4 隐藏
 *   自绘玻璃层、背板交给桌面渲染，其它设备自动回退自绘玻璃。无设置项、无强制模式。
 * - **0 件待取**：由设置项 `AppSettings.hideWidgetWhenEmpty` 决定两种形态（默认开启）：
 *   开启 → 清空标题两个 TextView、隐藏列表/自绘玻璃层、根背景置透明，整块透明隐形，
 *   不构建集合、不绑定 adapter；关闭 → 走正常渲染，显示「0 件待取」+ 空态行
 *   「还没有待取件」，背景照常。切换开关由设置页调 [updateAll] 立即重画。
 * - **reapply 铁律**：桌面在 layoutId 不变时复用旧视图树，只执行 actions。因此非空分支必须
 *   把空态改过的每一项显式反向恢复：`setBackgroundResource(express_widget_glass_bg)`、
 *   标题父容器 VISIBLE、列表 VISIBLE、后缀文本写回。漏一项就会出现「背景不回来 / 只显示数字」。
 * - **点击整块小部件**：有待取件、或「无待取件时隐藏组件」开关关闭（哪怕 0 件）→ 照常跳待取件页；
 *   隐藏开关开启且 0 件 → 挂 [ACTION_EMPTY_CLICK] 空广播，不跳转；若「双击锁屏」开关开启，
 *   该广播里做 350ms 双击计时并调 [ExpressLockAccessibilityService.lockScreen]。两条路径都**显式**
 *   `setOnClickPendingIntent`，因为 reapply 复用旧视图树时旧的 listener 不会自动消失。
 * - 逐码「已取」按行 id 精确标记；数据变化由各写入方调 [updateAll] 主动刷新。
 */
class ExpressWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) refreshOne(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PICKED -> {
                // 旧版本 PendingIntent 的兼容分支（逐码按 id，旧组级按地址）。
                val id = intent.getLongExtra(EXTRA_ROW_ID, -1L)
                if (id >= 0) {
                    ExpressStore.markPickedCode(context, id)
                } else {
                    ExpressStore.markPicked(context, intent.getStringExtra("address").orEmpty())
                }
                updateAll(context)
            }
            ACTION_MIUI_UPDATE -> updateAll(context)
            // 空态（隐藏组件开启且 0 件）点击整个小部件：绝不跳待取件页；若「双击锁屏」开启，
            // 在这里做双击计时并执行锁屏（服务未授权时静默无效）。
            // 这个广播 PendingIntent 是必须的：桌面 reapply 复用旧视图树，不显式覆盖的话
            // 上一次挂的 openApp listener 会残留、点击照样跳转。
            ACTION_EMPTY_CLICK -> onEmptyWidgetClick(context)
        }
    }

    /** Re-render every live widget instance from current data. Called after any data change. */
    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, ExpressWidgetProvider::class.java))
        for (id in ids) refreshOne(context, mgr, id)
    }

    private fun refreshOne(context: Context, mgr: AppWidgetManager, id: Int) {
        mgr.updateAppWidget(id, build(context, id))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // 低版本列表数据由 RemoteViewsService 提供，需单独通知它重新 onDataSetChanged()；
            // Android 12+ 的集合快照已随 updateAppWidget 提交，不需要（且该 API 已被弃用）。
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }

    private fun build(context: Context, widgetId: Int): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.express_widget)
        val pending = ExpressStore.queryAll(context, picked = false)

        // 设置项「无待取件时隐藏组件」关闭时走下面的正常渲染：显示「0 件待取」+ 空态行
        // 「还没有待取件」，背景照常；开启时才透明隐形。
        if (pending.isEmpty() && AppSettings.load(context).hideWidgetWhenEmpty) {
            // 0 件待取（开关开启）：不显示「0 件待取」/「件待取」、不显示空态行，也不构建集合；
            // 自绘玻璃层 + 根淡底色一起清掉 → 整个小部件透明隐形。
            // 点击：必须显式挂一个「空点击」PendingIntent 覆盖上次渲染遗留的 openApp listener，
            // 否则 reapply 复用旧视图树时点透明占位仍会跳待取件页。
            // 标题栏父容器保持 VISIBLE、只清空文本：桌面 reapply 复用旧视图树时，GONE 过的
            // ViewGroup 恢复后常只重绘「有 action」的子 view（曾导致只显示数字、件待取消失），
            // 这里同时显式设 VISIBLE，兼容上一版已把该父容器设成 GONE 的旧 widget。
            root.setViewVisibility(R.id.widget_title_bar, View.VISIBLE)
            root.setTextViewText(R.id.widget_title_count, "")
            root.setTextViewText(R.id.widget_title_suffix, "")
            root.setViewVisibility(R.id.widget_list, View.GONE)
            root.setViewVisibility(R.id.widget_custom_bg, View.GONE)
            // setBackgroundColor 是 View 的 @RemotableViewMethod，可覆盖 XML 的 drawable 背景。
            root.setInt(android.R.id.background, "setBackgroundColor", Color.TRANSPARENT)
            root.setOnClickPendingIntent(R.id.widget_root, emptyClick(context))
            return root
        }

        // 桌面复用旧视图树（layoutId 相同只 reapply actions，不重新 inflate），所以空态改过的
        // 每一项都必须在这里**显式反向恢复**，不能指望 XML 默认值。
        // 有待取件、或「隐藏组件」开关关闭（哪怕 0 件）→ 点击整块小部件照常跳待取件页。
        root.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        root.setInt(
            android.R.id.background,
            "setBackgroundResource",
            R.drawable.express_widget_glass_bg,
        )
        root.setViewVisibility(R.id.widget_title_bar, View.VISIBLE)
        root.setViewVisibility(R.id.widget_list, View.VISIBLE)
        val systemGlass = HyperOsGlass.isSoftGlassAvailable()
        root.setViewVisibility(R.id.widget_custom_bg, if (systemGlass) View.GONE else View.VISIBLE)
        // 标题拆成「数量(红/加粗) + 件待取」；后缀虽在 XML 里写死，但空态清空过就必须显式写回，
        // 否则 reapply 时静态文本不会重新绘制（用户实测「只显示数字、没有件待取」）。
        root.setTextViewText(R.id.widget_title_count, pending.size.toString())
        root.setTextViewText(
            R.id.widget_title_suffix,
            context.getString(R.string.express_widget_count_suffix),
        )

        // 行高固定由行布局给定，不读桌面报告尺寸（HyperOS 上不可靠，resize 来回必漂移）；
        // ListView match_parent + 可滚动，尺寸变化由桌面重新布局自然适配。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：官方推荐的集合快照，所有 item 随 RemoteViews 一次性提交，
            // 桌面无需绑定 RemoteViewsService，也不依赖已弃用的 notifyAppWidgetViewDataChanged。
            val builder = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(2)
            if (pending.isEmpty()) {
                // 开关关闭：0 件时仍显示一条空态行「还没有待取件」（低版本由 Factory 兜底）。
                builder.addItem(EMPTY_ITEM_ID, buildWidgetRow(context, null))
            } else {
                for (entry in pending.take(MAX_LIST_ITEMS)) {
                    builder.addItem(entry.id, buildWidgetRow(context, entry))
                }
            }
            root.setRemoteAdapter(R.id.widget_list, builder.build())
        } else {
            // Android 11 及以下仍走 RemoteViewsService（Intent 版 setRemoteAdapter）。
            val adapter = Intent(context, ExpressWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // 让不同 widgetId 的 adapter Intent 彼此不等，避免系统复用同一个 factory。
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            root.setRemoteAdapter(widgetId, R.id.widget_list, adapter)
        }
        root.setPendingIntentTemplate(R.id.widget_list, clickTemplate(context))
        return root
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, ExpressListActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 收到空态点击：只有「双击锁屏」开关开启才做双击计时。
     * 双击成立时调用无障碍服务的 GLOBAL_ACTION_LOCK_SCREEN；服务未授权时
     * [ExpressLockAccessibilityService.lockScreen] 返回 false，
     * 静默无效（空态点击本来也不跳页）。三连击不会触发两次：命中后把时间戳清零。
     */
    private fun onEmptyWidgetClick(context: Context) {
        if (!AppSettings.load(context).doubleTapLockScreen) return
        val now = SystemClock.elapsedRealtime()
        val last = lastEmptyClickAt
        if (last > 0L && now - last <= DOUBLE_TAP_TIMEOUT_MS) {
            lastEmptyClickAt = 0L
            ExpressLockAccessibilityService.lockScreen()
        } else {
            lastEmptyClickAt = now
        }
    }

    /**
     * 空态点击：发一条只发给自己的空广播（[ACTION_EMPTY_CLICK]），不跳页、不重绘。
     * 用广播而不是「不设置」是因为桌面 reapply 复用旧视图树时，旧的 openApp listener 不会自动消失；
     * 显式挂一个新 PendingIntent 才能把它覆盖掉（见 C40）。
     */
    private fun emptyClick(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQ_EMPTY_CLICK,
        Intent(context, ExpressWidgetProvider::class.java).setAction(ACTION_EMPTY_CLICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 模板必须 MUTABLE（Android 12+），否则 item 的 fillInIntent extras 无法合入。 */
    private fun clickTemplate(context: Context): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getActivity(
            context,
            2,
            Intent(context, ExpressWidgetClickActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    companion object {
        const val EXTRA_CLICK = "com.toretto5060.pickupcatapp.WIDGET_CLICK"
        const val EXTRA_ROW_ID = "row_id"
        const val CLICK_OPEN = "open"
        const val CLICK_PICK = "pick"

        private const val ACTION_PICKED = "com.toretto5060.pickupcatapp.EXPRESS_PICKED"
        private const val ACTION_MIUI_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"
        private const val ACTION_EMPTY_CLICK = "com.toretto5060.pickupcatapp.WIDGET_EMPTY_CLICK"
        private const val REQ_EMPTY_CLICK = 3
        /** 双击判定窗口（ms）；命中后清零，避免三连击触发两次锁屏。 */
        private const val DOUBLE_TAP_TIMEOUT_MS = 350L

        @Volatile
        private var lastEmptyClickAt = 0L

        // 0 件待取且开关关闭时，空态行的稳定 item id（与真实行 id 不冲突）。
        private const val EMPTY_ITEM_ID = -1L
        // 集合快照随 RemoteViews 走 Binder，限制上限避免异常多的历史记录把事务撑爆；
        // 真实总数仍显示在标题「N 件待取」里。
        private const val MAX_LIST_ITEMS = 100
    }
}

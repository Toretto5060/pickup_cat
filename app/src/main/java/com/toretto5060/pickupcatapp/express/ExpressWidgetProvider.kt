package com.toretto5060.pickupcatapp.express

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.app.ExpressListActivity
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
 * - **背景**：澎湃 OS4 柔光玻璃模式把自绘玻璃层隐藏，背板交给桌面渲染；其它设备/用户选择时
 *   用自绘玻璃（默认兜底）。判定见 [HyperOsGlass]。
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

        // 自绘玻璃是默认兜底；系统柔光玻璃模式只留淡底色，让桌面材质透出来。
        val systemGlass = HyperOsGlass.shouldUseSystemGlass(context)
        root.setViewVisibility(R.id.widget_custom_bg, if (systemGlass) View.GONE else View.VISIBLE)

        val pending = ExpressStore.queryAll(context, picked = false)
        // 标题拆成「数量(红/加粗) + 件待取」，数量更醒目；后缀文案在布局里写死。
        root.setTextViewText(R.id.widget_title_count, pending.size.toString())
        root.setOnClickPendingIntent(R.id.widget_root, openApp(context))

        // 行高固定由行布局给定，不读桌面报告尺寸（HyperOS 上不可靠，resize 来回必漂移）；
        // ListView match_parent + 可滚动，尺寸变化由桌面重新布局自然适配。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：官方推荐的集合快照，所有 item 随 RemoteViews 一次性提交，
            // 桌面无需绑定 RemoteViewsService，也不依赖已弃用的 notifyAppWidgetViewDataChanged。
            val builder = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(2)
            val items = pending.take(MAX_LIST_ITEMS)
            if (items.isEmpty()) {
                builder.addItem(EMPTY_ITEM_ID, buildWidgetRow(context, null))
            } else {
                for (entry in items) {
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
        private const val EMPTY_ITEM_ID = -1L
        // 集合快照随 RemoteViews 走 Binder，限制上限避免异常多的历史记录把事务撑爆；
        // 真实总数仍显示在标题「N 件待取」里。
        private const val MAX_LIST_ITEMS = 100
    }
}

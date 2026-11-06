package com.toretto5060.pickupcatapp.express

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.app.ExpressListActivity

/**
 * 待取件小部件：桌面与负一屏共用同一个。**逐码一行**（最多 5 行）——快递公司（品牌色）+ 取件码
 * （视觉主体）+ 时间 + 地址 + 大色块"已取"按钮。除"已取"外，点击任意位置都跳到 app 内的全部
 * 待取件列表（requirement 5）。
 *
 * 用固定行 + addView 而非 ListView：待取件通常 3~5 条，RemoteViewsService 在负一屏兼容差（设计文档结论）。
 * 数据变化由各写入方（通知 capture、短信 hook、列表操作、本接收器的"已取"）调用 [updateAll] 主动刷新。
 */
class ExpressWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = build(context)
        for (id in ids) mgr.updateAppWidget(id, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_PICKED) {
            // 逐码"已取"按行 id 精确定位（不同地址可能同码）；旧组级 PendingIntent 带 address 兼容。
            val id = intent.getLongExtra("row_id", -1L)
            if (id >= 0) {
                ExpressStore.markPickedCode(context, id)
            } else {
                // 旧组级 PendingIntent 兼容：按地址标记（同地址不同柜号一起处理）。
                ExpressStore.markPicked(context, intent.getStringExtra("address").orEmpty())
            }
            updateAll(context)
        }
    }

    /** Re-render every live widget instance from current data. Called after any data change. */
    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(context, ExpressWidgetProvider::class.java)) ?: return
        if (ids.isEmpty()) return
        val views = build(context)
        for (id in ids) mgr.updateAppWidget(id, views)
    }

    private fun build(context: Context): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.express_widget)
        root.removeAllViews(R.id.widget_container)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, ExpressListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        root.setOnClickPendingIntent(R.id.widget_root, open)

        // 逐码一行（取件码是主体）：快递公司 + 取件码 + 时间 + 地址 + 大色块"已取"。
        val rows = ExpressStore.queryAll(context, picked = false).take(MAX_ROWS)
        if (rows.isEmpty()) {
            val empty = RemoteViews(context.packageName, R.layout.express_widget_row)
            empty.setTextViewText(R.id.row_code, context.getString(R.string.express_list_empty))
            empty.setViewVisibility(R.id.row_company, android.view.View.GONE)
            empty.setViewVisibility(R.id.row_addr, android.view.View.GONE)
            empty.setViewVisibility(R.id.row_time, android.view.View.GONE)
            empty.setViewVisibility(R.id.row_pick, android.view.View.GONE)
            root.addView(R.id.widget_container, empty)
            return root
        }

        for (r in rows) {
            val row = RemoteViews(context.packageName, R.layout.express_widget_row)
            val company = r.company.ifEmpty { r.source }
            row.setTextViewText(R.id.row_company, company)
            row.setTextColor(R.id.row_company, brandColorArgb(company))
            row.setTextViewText(
                R.id.row_code,
                if (r.cabinet.isNotEmpty()) cabinetShort(r.cabinet) + " " + r.code else r.code,
            )
            row.setTextViewText(R.id.row_time, formatReceivedAt(r.receivedAt))
            row.setTextViewText(R.id.row_addr, r.address)
            row.setOnClickPendingIntent(R.id.row_root, open)
            val pick = PendingIntent.getBroadcast(
                context, r.id.toInt(),
                Intent(context, ExpressWidgetProvider::class.java)
                    .setAction(ACTION_PICKED)
                    .putExtra("row_id", r.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            row.setOnClickPendingIntent(R.id.row_pick, pick)
            root.addView(R.id.widget_container, row)
        }
        return root
    }

    companion object {
        private const val ACTION_PICKED = "com.toretto5060.pickupcatapp.EXPRESS_PICKED"
        private const val MAX_ROWS = 5
    }
}

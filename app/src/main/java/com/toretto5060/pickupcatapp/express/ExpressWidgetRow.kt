package com.toretto5060.pickupcatapp.express

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.toretto5060.pickupcatapp.R

/**
 * 构造一条待取件行（entry 为 null 时是空态行）。
 *
 * Android 12+ 的 RemoteCollectionItems 与低版本 RemoteViewsService 两条可滚动列表路径
 * 共用这一份渲染，保证「文字布局 / 动态行高 / 点击 fillIn」只维护一处。
 */
internal fun buildWidgetRow(
    context: Context,
    entry: ExpressRow?,
    rowHeightDp: Float,
): RemoteViews {
    val row = RemoteViews(context.packageName, R.layout.express_widget_row)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        row.setViewLayoutHeight(R.id.row_root, rowHeightDp, TypedValue.COMPLEX_UNIT_DIP)
    }
    // 集合 item 不能逐个 setOnClickPendingIntent（RemoteViews 会复用），统一用 fillInIntent。
    row.setOnClickFillInIntent(
        R.id.row_root,
        Intent().putExtra(ExpressWidgetProvider.EXTRA_CLICK, ExpressWidgetProvider.CLICK_OPEN),
    )

    if (entry == null) {
        row.setTextViewText(R.id.row_code, context.getString(R.string.express_list_empty))
        row.setTextViewTextSize(R.id.row_code, TypedValue.COMPLEX_UNIT_SP, 15f)
        row.setTextColor(R.id.row_code, context.getColor(R.color.express_widget_secondary))
        row.setViewVisibility(R.id.row_company, View.GONE)
        row.setViewVisibility(R.id.row_time, View.GONE)
        row.setViewVisibility(R.id.row_addr, View.GONE)
        row.setViewVisibility(R.id.row_pick, View.GONE)
        return row
    }

    val company = entry.company.ifEmpty { entry.source }
    row.setTextViewText(R.id.row_company, company)
    row.setTextColor(R.id.row_company, brandColorArgb(company))
    row.setTextViewText(
        R.id.row_code,
        if (entry.cabinet.isNotEmpty()) cabinetShort(entry.cabinet) + " " + entry.code
        else entry.code,
    )
    row.setTextViewText(R.id.row_time, formatReceivedAt(entry.receivedAt))
    row.setTextViewText(R.id.row_addr, entry.address)
    row.setOnClickFillInIntent(
        R.id.row_pick,
        Intent()
            .putExtra(ExpressWidgetProvider.EXTRA_CLICK, ExpressWidgetProvider.CLICK_PICK)
            .putExtra(ExpressWidgetProvider.EXTRA_ROW_ID, entry.id),
    )
    return row
}

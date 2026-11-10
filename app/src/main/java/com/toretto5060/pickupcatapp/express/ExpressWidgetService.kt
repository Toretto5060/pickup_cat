package com.toretto5060.pickupcatapp.express

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * Android 11 及以下的小部件可滚动列表数据源（RemoteViewsService 路径）。
 *
 * Android 12(API 31)+ 用官方推荐的 `RemoteViews.RemoteCollectionItems`：集合快照随
 * RemoteViews 一起提交，桌面不需要绑定本 Service，也避开 API 35 起对 Intent 版
 * `setRemoteAdapter` 的弃用。这里保留给 minSdk 29/30 兜底，行渲染与高版本共用 [buildWidgetRow]。
 */
class ExpressWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ExpressWidgetFactory(applicationContext, intent)

    internal class ExpressWidgetFactory(
        private val context: Context,
        @Suppress("unused") intent: Intent,
    ) : RemoteViewsFactory {
        private var rows: List<ExpressRow> = emptyList()
        private var brands: Map<String, String> = emptyMap()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            rows = ExpressStore.queryAll(context, picked = false)
            // 与高版本路径一致：同地址同批次共用一个快递品牌，避免脏值显示在公司名位置。
            brands = brandByGroup(rows)
        }

        override fun onDestroy() {
            rows = emptyList()
            brands = emptyMap()
        }

        override fun getCount(): Int = rows.size.coerceAtLeast(1)

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 2

        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id ?: -1L

        override fun hasStableIds(): Boolean = true

        override fun getViewAt(position: Int): RemoteViews {
            val row = rows.getOrNull(position)
            return buildWidgetRow(
                context,
                row,
                row?.let { brands[groupKey(it.address, it.batch)] },
            )
        }
    }
}

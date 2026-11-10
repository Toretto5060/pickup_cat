package com.toretto5060.pickupcatapp.express

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.toretto5060.pickupcatapp.express.ExpressConst as C

/**
 * HyperOS 4 MAML 小部件（.mtz 主题）的只读数据投影，仅作 [MamlWidgetNotifier] 广播之外的兜底
 * （主题在 init 时由 ContentProviderBinder 的 queryAtStart 拉一次初始数据）。
 *
 * MAML 宿主进程的包可见性不可靠，实时刷新已改由广播直推（见 [MamlWidgetSnapshot]）；
 * 这里仍恒返回 1 行，避免查询失败时 ContentProviderBinder 把变量写坏。
 */
class MamlWidgetProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context
        val columns: Array<String> = (projection?.takeIf { it.isNotEmpty() }?.toList()
            ?: MamlWidgetSnapshot.COLUMNS.toList()).toTypedArray()
        val cursor = MatrixCursor(columns)
        val values = MamlWidgetSnapshot.snapshot(ctx)
        cursor.addRow(Array<Any>(columns.size) { i -> values[columns[i]] ?: "" })
        ctx?.contentResolver?.let {
            cursor.setNotificationUri(it, Uri.parse(C.MAML_PENDING_URI))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/${C.MAML_AUTHORITY}.pending"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("MAML widget projection is read-only")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

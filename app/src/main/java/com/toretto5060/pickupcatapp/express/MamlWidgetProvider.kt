package com.toretto5060.pickupcatapp.express

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.toretto5060.pickupcatapp.express.ExpressConst as C
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HyperOS 4 MAML 小部件（.mtz 主题）的只读数据投影。
 *
 * 标准 AppWidget 的 RemoteViews 跑在本 App 进程，拿不到桌面的 backdrop blur；而 MAML
 * 小部件由「智能助理 / For设计师」在桌面进程渲染，能读 #hyper_material_* 系统变量并
 * 用 MaterialToken 申请真实柔光玻璃。代价是它只能通过 ContentProviderBinder 取数，因此
 * 这里把 ExpressStore 的待取件列表摊平成「单行、固定列名」的快照，不开放数据库本身。
 *
 * 安全约束：Provider 必须 exported=true 才能被桌面查询，但只允许小米桌面/智能助理等系统
 * 宿主读取；不返回 raw 短信、origin_pkg、sms_hash，也不接受任何写操作。
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
            ?: COLUMNS.toList()).toTypedArray()
        val cursor = MatrixCursor(columns)
        if (ctx != null && callerAllowed(ctx)) {
            val values = snapshot(ctx)
            cursor.addRow(Array<Any>(columns.size) { i -> values[columns[i]] ?: "" })
            // ContentProviderBinder 依赖 notifyChange 重新查询；ExpressProvider 在写入后会通知本 URI。
            cursor.setNotificationUri(ctx.contentResolver, Uri.parse(C.MAML_PENDING_URI))
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

    /** 把前 [MAX_ROWS] 条待取件摊平成 code1..code4 / company1.. / place1.. / time1.. 列。 */
    private fun snapshot(ctx: Context): Map<String, Any?> {
        val rows = try {
            ExpressStore.queryAll(ctx, picked = false)
        } catch (_: Throwable) {
            emptyList()
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val out = HashMap<String, Any?>()
        out[C.MAML_COL_COUNT] = rows.size
        out[C.MAML_COL_UPDATED_AT] = rows.maxOfOrNull { it.receivedAt } ?: 0L
        out[C.MAML_COL_EMPTY_TEXT] = if (rows.isEmpty()) "还没有待取件" else ""
        out[C.MAML_COL_SUFFIX] = "件待取"
        for (i in 0 until MAX_ROWS) {
            val row = rows.getOrNull(i)
            val n = i + 1
            out["code$n"] = row?.code.orEmpty()
            out["company$n"] = row?.company?.takeIf { it.isNotBlank() } ?: row?.source.orEmpty()
            out["place$n"] = listOf(row?.cabinet.orEmpty(), row?.address.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "—" }
            out["time$n"] = row?.let { time.format(it.receivedAt) }.orEmpty()
        }
        return out
    }

    /**
     * 允许本 App、系统进程以及小米小部件宿主（桌面 / 智能助理 / For设计师 / 主题）读取。
     * 普通第三方应用即使能解析这个 exported Provider，也只会拿到空 Cursor。
     */
    private fun callerAllowed(ctx: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid < Process.FIRST_APPLICATION_UID) return true
        val packages = runCatching {
            ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        return packages.any { pkg -> HOST_PREFIXES.any { prefix -> pkg.startsWith(prefix) } }
    }

    companion object {
        private const val MAX_ROWS = 4
        private val HOST_PREFIXES = listOf(
            "com.miui", "com.xiaomi", "com.android.systemui", "com.android.thememanager",
        )
        private val COLUMNS: Array<String> = buildList {
            add(C.MAML_COL_COUNT)
            add(C.MAML_COL_UPDATED_AT)
            add(C.MAML_COL_EMPTY_TEXT)
            add(C.MAML_COL_SUFFIX)
            for (i in 1..MAX_ROWS) {
                add("code$i")
                add("company$i")
                add("place$i")
                add("time$i")
            }
        }.toTypedArray()
    }
}

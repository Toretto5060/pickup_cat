package com.toretto5060.pickupcatapp.express

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.toretto5060.pickupcatapp.express.ExpressConst as C

/**
 * The single source of truth for the express (快递取件码) feature, running in the app process.
 *
 * Both the hooked telephony process (catching an SMS) and the app process (settings, widget, list
 * page) reach it through [C.ENTRIES_URI] / [C.SETTINGS_URI]; a write from any of them wakes the app
 * process up through the provider, so nothing depends on a process staying alive. The widget and the
 * list page read [C.ENTRIES_URI]; [C.SETTINGS_URI] holds the master switch, the notification-listen
 * app set, and the user's custom parse rules (JSON).
 */
internal class ExpressProvider : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(C.AUTHORITY, "entries", CODE_ENTRIES)
        addURI(C.AUTHORITY, "entries/#", CODE_ENTRIES_ID)
        addURI(C.AUTHORITY, "settings", CODE_SETTINGS)
        addURI(C.AUTHORITY, "settings/*", CODE_SETTINGS_KEY)
    }

    override fun onCreate(): Boolean {
        db = DbHelper(context ?: throw IllegalStateException("no context")).writableDatabase
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        if (!allowed()) throw SecurityException("express provider: uid ${Binder.getCallingUid()} not allowed")
        return when (matcher.match(uri)) {
            CODE_ENTRIES -> db.query(
                C.TABLE_ENTRIES, projection, selection, selectionArgs, null, null,
                sortOrder ?: "${C.COL_RECEIVED_AT} DESC",
            )
            CODE_ENTRIES_ID -> db.query(
                C.TABLE_ENTRIES, projection, "${C.COL_ID}=?",
                arrayOf(uri.lastPathSegment!!), null, null,
                "${C.COL_RECEIVED_AT} DESC",
            )
            CODE_SETTINGS, CODE_SETTINGS_KEY -> {
                val sel = if (matcher.match(uri) == CODE_SETTINGS_KEY) "${C.COL_KEY}=?" else null
                val args = if (matcher.match(uri) == CODE_SETTINGS_KEY) {
                    arrayOf(uri.lastPathSegment!!)
                } else selectionArgs
                db.query(C.TABLE_SETTINGS, projection, sel, args, null, null, null)
            }
            else -> throw IllegalArgumentException("unknown uri $uri")
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/${C.AUTHORITY}"

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (!allowed()) throw SecurityException("express provider: uid ${Binder.getCallingUid()} not allowed")
        val v = values ?: ContentValues()
        return when (matcher.match(uri)) {
            CODE_ENTRIES -> {
                // De-dupe an SMS that the provider would otherwise insert twice (some ROMs fire the
                // insert for both the inbox and a notify row). A row with the same sms_hash stays.
                // 只与「待取件列表」比对：同一地址可能隔很久（例如一年）再次收到内容完全相同、
                // 取件码也相同的短信，旧行那时已被标记 picked=1，不能让它把新件过滤掉。
                // 逻辑照旧，唯一变化是查询增加 ${C.COL_PICKED}=0 条件。
                val hash = v.getAsString(C.COL_SMS_HASH)
                if (!hash.isNullOrEmpty()) {
                    val existing = db.query(
                        C.TABLE_ENTRIES, arrayOf(C.COL_ID),
                        "${C.COL_SMS_HASH}=? AND ${C.COL_PICKED}=0",
                        arrayOf(hash), null, null, null, "1",
                    )
                    if (existing.moveToFirst()) {
                        existing.close()
                        return Uri.withAppendedPath(Uri.parse(C.ENTRIES_URI), "dup")
                    }
                    existing.close()
                }
                // Same pickup via a second source (notification listener AND the SMS hook) carries a
                // different hash - the listener prepends the sender title to the raw text. Dedupe on
                // the pickup itself: one open row per (code, address, cabinet); reminders of the same
                // code collapse into the row that is already there.
                val code = v.getAsString(C.COL_CODE)
                val address = v.getAsString(C.COL_ADDRESS)
                if (!code.isNullOrEmpty() && address != null) {
                    val cabinet = v.getAsString(C.COL_CABINET) ?: ""
                    val existing = db.query(
                        C.TABLE_ENTRIES, arrayOf(C.COL_ID),
                        "${C.COL_CODE}=? AND ${C.COL_ADDRESS}=? AND ${C.COL_CABINET}=? AND ${C.COL_PICKED}=0",
                        arrayOf(code, address, cabinet), null, null, null, "1",
                    )
                    if (existing.moveToFirst()) {
                        existing.close()
                        return Uri.withAppendedPath(Uri.parse(C.ENTRIES_URI), "dup")
                    }
                    existing.close()
                }
                if (!v.containsKey(C.COL_RECEIVED_AT)) v.put(C.COL_RECEIVED_AT, System.currentTimeMillis())
                // 冻结批次：该地址还有未取码 → 并入当前活跃批次；已全部取出（或首次）→ 另起一批。
                // 已取件卡片因此是"全部取出那一刻"的快照，之后同地址的新信息不会再把它拉回待取件。
                db.beginTransaction()
                val id: Long
                try {
                    v.put(C.COL_BATCH, nextBatch(v.getAsString(C.COL_ADDRESS).orEmpty()))
                    id = db.insert(C.TABLE_ENTRIES, null, v)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                val out = Uri.withAppendedPath(Uri.parse(C.ENTRIES_URI), id.toString())
                context?.contentResolver?.notifyChange(Uri.parse(C.ENTRIES_URI), null)
                // MAML 主题不走 AppWidget 更新广播：刷新广播 extras 携带完整快照，由 BroadcastBinder 直接注入变量。
                MamlWidgetNotifier.notifyDataChanged(context)
                out
            }
            CODE_SETTINGS -> {
                db.insertWithOnConflict(C.TABLE_SETTINGS, null, v, SQLiteDatabase.CONFLICT_REPLACE)
                context?.contentResolver?.notifyChange(Uri.parse(C.SETTINGS_URI), null)
                Uri.parse(C.SETTINGS_URI)
            }
            else -> throw IllegalArgumentException("unknown uri $uri")
        }
    }

    /**
     * 决定新行的冻结批次：
     *  - 该地址还有未取行 → 沿用它们的 batch（待取件内的聚合规则不变）；
     *  - 该地址已全部取出（或第一次出现）→ max(batch)+1 开新批次，旧批次永久冻结在此刻。
     * 旧数据迁移后 batch 全为 0：还有未取行时新信息并入 0，全已取时新信息从 1 开始。
     */
    private fun nextBatch(address: String): Int {
        // 与分组键一样按归一化地址比较，避免同一取件点因空格/大小写差异被拆到不同批次。
        val key = ExpressGroup.normalize(address)
        val c = db.query(
            C.TABLE_ENTRIES, arrayOf(C.COL_ADDRESS, C.COL_BATCH, C.COL_PICKED),
            null, null, null, null, null,
        )
        var activeBatch: Int? = null
        var maxBatch = 0
        c.use {
            while (it.moveToNext()) {
                if (ExpressGroup.normalize(it.getString(0)) != key) continue
                val b = it.getInt(1)
                if (b > maxBatch) maxBatch = b
                if (it.getInt(2) == 0 && activeBatch == null) activeBatch = b
            }
        }
        return activeBatch ?: (maxBatch + 1)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!allowed()) throw SecurityException("express provider: uid ${Binder.getCallingUid()} not allowed")
        val count = when (matcher.match(uri)) {
            CODE_ENTRIES -> db.delete(C.TABLE_ENTRIES, selection, selectionArgs)
            CODE_ENTRIES_ID -> db.delete(C.TABLE_ENTRIES, "${C.COL_ID}=?", arrayOf(uri.lastPathSegment!!))
            else -> throw IllegalArgumentException("unknown uri $uri")
        }
        if (count > 0) {
            context?.contentResolver?.notifyChange(Uri.parse(C.ENTRIES_URI), null)
            MamlWidgetNotifier.notifyDataChanged(context)
        }
        return count
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int {
        if (!allowed()) throw SecurityException("express provider: uid ${Binder.getCallingUid()} not allowed")
        val count = when (matcher.match(uri)) {
            CODE_ENTRIES -> db.update(C.TABLE_ENTRIES, values, selection, selectionArgs)
            CODE_ENTRIES_ID -> db.update(C.TABLE_ENTRIES, values, "${C.COL_ID}=?", arrayOf(uri.lastPathSegment!!))
            CODE_SETTINGS -> db.update(C.TABLE_SETTINGS, values, selection, selectionArgs)
            else -> 0
        }
        if (count > 0) {
            context?.contentResolver?.notifyChange(Uri.parse(C.ENTRIES_URI), null)
            context?.contentResolver?.notifyChange(Uri.parse(C.SETTINGS_URI), null)
            // 「已取」等状态变化同样要重推快照，否则 MAML 主题里的取件信息不会消失。
            MamlWidgetNotifier.notifyDataChanged(context)
        }
        return count
    }

    private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "express.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE ${C.TABLE_ENTRIES} (
                    ${C.COL_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                    ${C.COL_CODE} TEXT NOT NULL,
                    ${C.COL_COMPANY} TEXT NOT NULL DEFAULT '',
                    ${C.COL_SOURCE} TEXT NOT NULL DEFAULT '${C.SOURCE_SMS}',
                    ${C.COL_ADDRESS} TEXT NOT NULL DEFAULT '',
                    ${C.COL_CABINET} TEXT NOT NULL DEFAULT '',
                    ${C.COL_ORIGIN} TEXT NOT NULL DEFAULT '${C.ORIGIN_SMS}',
                    ${C.COL_ORIGIN_PKG} TEXT NOT NULL DEFAULT '',
                    ${C.COL_RECEIVED_AT} INTEGER NOT NULL,
                    ${C.COL_RAW} TEXT NOT NULL DEFAULT '',
                    ${C.COL_SMS_HASH} TEXT NOT NULL DEFAULT '',
                    ${C.COL_PICKED} INTEGER NOT NULL DEFAULT 0,
                    ${C.COL_PICKED_AT} INTEGER NOT NULL DEFAULT 0,
                    ${C.COL_BATCH} INTEGER NOT NULL DEFAULT 0
                )""",
            )
            db.execSQL("CREATE INDEX idx_express_addr ON ${C.TABLE_ENTRIES}(${C.COL_ADDRESS}, ${C.COL_CABINET})")
            db.execSQL(
                """CREATE TABLE ${C.TABLE_SETTINGS} (
                    ${C.COL_KEY} TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) {
                db.execSQL("ALTER TABLE ${C.TABLE_ENTRIES} ADD COLUMN ${C.COL_PICKED} INTEGER NOT NULL DEFAULT 0")
            }
            // v3: 已取时间必须持久保存（"全部取件"时已单独取过的条目保留它原本的时间）。
            // 迁移缺失会触发旧数据被清库（踩过一次），务必保留 ALTER。
            if (old < 3) {
                db.execSQL("ALTER TABLE ${C.TABLE_ENTRIES} ADD COLUMN ${C.COL_PICKED_AT} INTEGER NOT NULL DEFAULT 0")
            }
            // v4: 冻结批次。旧行统一 batch=0（升级前的行为不变），升级后新信息自动另起批次。
            if (old < 4) {
                db.execSQL("ALTER TABLE ${C.TABLE_ENTRIES} ADD COLUMN ${C.COL_BATCH} INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    companion object {
        private const val CODE_ENTRIES = 1
        private const val CODE_ENTRIES_ID = 2
        private const val CODE_SETTINGS = 3
        private const val CODE_SETTINGS_KEY = 4

        /**
         * Only the app itself (same UID) and system-ish processes (the telephony / systemui hooks that
         * LSPosed injected our code into run as system or phone UID, both below the first app UID) may
         * touch the provider. Third-party apps (UID >= 10000) are refused.
         */
        /** 独立 App 内所有调用方（列表、设置、通知监听、小部件）都同 UID，不再放行系统进程跨进程写入。 */
        private fun allowed(): Boolean = Binder.getCallingUid() == Process.myUid()
    }
}

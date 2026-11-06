package com.toretto5060.pickupcatapp.express

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.toretto5060.pickupcatapp.express.ExpressConst as C
import org.json.JSONArray

/**
 * Cross-process access to [ExpressProvider], used by both the hooked telephony process (capturing an
 * SMS) and the app process (settings, widget, list page). Everything goes through [Context
 * .getContentResolver] so the same code works from either side - the provider lives in the app
 * process and is reached by URI from anywhere.
 */
internal object ExpressStore {

    fun listenApps(ctx: Context): Set<String> =
        getSetting(ctx, C.KEY_LISTEN_APPS)
            ?.split('|')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    // --------------------------------------------------------------------- settings

    fun getSetting(ctx: Context, key: String): String? {
        // 用 settings/<key> 路径，让 Provider 走 CODE_SETTINGS_KEY 分支按 key 精确查。
        // 之前用 settings 目录 + selection 查询，Provider 会忽略 selection 返回任意一行，
        // 导致开关状态读错（"启用取件码"开过之后再进来又变回关闭）。
        val uri = Uri.parse(C.SETTINGS_URI).buildUpon().appendPath(key).build()
        val c: Cursor? = try {
            ctx.contentResolver.query(uri, arrayOf("value"), null, null, null)
        } catch (_: Throwable) { null }
        c.use {
            if (it != null && it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    fun setSetting(ctx: Context, key: String, value: String) {
        val v = ContentValues().apply { put(C.COL_KEY, key); put("value", value) }
        ctx.contentResolver.insert(Uri.parse(C.SETTINGS_URI), v)
    }

    /** Custom parse rules saved on the config page, tried before the built-in rule. */
    fun loadCustomRules(ctx: Context): List<ParseRule> {
        val json = getSetting(ctx, C.KEY_CUSTOM_RULES) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> ParseRule.fromJson(arr.getJSONObject(i)) }
        } catch (_: Throwable) { emptyList() }
    }

    // --------------------------------------------------------------------- capture

    /** Parse [text] as an SMS pickup, applying any custom rules the user saved. */
    fun parseForCapture(ctx: Context, text: String): ParsedPickup? =
        ExpressParse.parse(
            text,
            source = C.SOURCE_SMS,
            origin = C.ORIGIN_SMS,
            originPkg = "",
            extraRules = loadCustomRules(ctx),
        )

    /** App 的可读名，作为通知取件码的来源标签（短信则固定为"短信"）。 */
    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)

    /**
     * 解析一条来自 [pkg] 的通知文本。来源标签用 App 名、origin 带包名，便于后续按 App 区分。
     * 通知专属的抽取（标题/大文本/多行）已在此合并，但各 App 通知结构的精细解析留待后续迭代。
     */
    fun parseForNotification(ctx: Context, pkg: String, text: String): ParsedPickup? =
        ExpressParse.parse(
            text,
            source = appLabel(ctx, pkg),
            origin = "notify:$pkg",
            originPkg = pkg,
            extraRules = loadCustomRules(ctx),
        )

    /** Persist a parsed pickup; de-duped by the provider on [C.COL_SMS_HASH]. */
    fun insertPickup(ctx: Context, p: ParsedPickup) {
        val v = ContentValues().apply {
            put(C.COL_CODE, p.code)
            put(C.COL_COMPANY, p.company)
            put(C.COL_SOURCE, p.source)
            put(C.COL_ADDRESS, p.address)
            put(C.COL_CABINET, p.cabinet)
            put(C.COL_ORIGIN, p.origin)
            put(C.COL_ORIGIN_PKG, p.originPkg)
            put(C.COL_RAW, p.raw)
            put(C.COL_SMS_HASH, p.raw.trim().hashCode().toString())
            put(C.COL_RECEIVED_AT, System.currentTimeMillis())
            put(C.COL_PICKED, 0)
        }
        ctx.contentResolver.insert(Uri.parse(C.ENTRIES_URI), v)
    }

    // --------------------------------------------------------------------- reading

    fun queryAll(ctx: Context, picked: Boolean): List<ExpressRow> {
        val out = ArrayList<ExpressRow>()
        val c = ctx.contentResolver.query(
            Uri.parse(C.ENTRIES_URI), null,
            "${C.COL_PICKED}=?", arrayOf(if (picked) "1" else "0"),
            "${C.COL_RECEIVED_AT} DESC",
        ) ?: return out
        c.use {
            while (it.moveToNext()) out.add(row(it))
        }
        return out
    }

    /** 该 tab 的卡片分组，逻辑见 [groupRowsForTab]（按地址 + 冻结批次，已取件按全部取出时间倒序）。 */
    fun queryGroupsForTab(ctx: Context, pickedTab: Boolean): List<ExpressGroup> =
        groupRowsForTab(queryAllRows(ctx), pickedTab)

    /** 全部行（widget 只要未取的用 [queryAll]；列表分组需要已取行做划线态）。 */
    private fun queryAllRows(ctx: Context): List<ExpressRow> {
        val out = ArrayList<ExpressRow>()
        val c = ctx.contentResolver.query(
            Uri.parse(C.ENTRIES_URI), null, null, null, "${C.COL_RECEIVED_AT} DESC",
        ) ?: return out
        c.use { while (it.moveToNext()) out.add(row(it)) }
        return out
    }

    /** All pickups collapsed into display groups (same address + cabinet). [picked] 选已取件还是待取件。 */
    fun queryGroups(ctx: Context, picked: Boolean): List<ExpressGroup> {
        val rows = queryAll(ctx, picked)
        val byKey = LinkedHashMap<String, ExpressGroup>()
        for (r in rows) {
            val codes = ArrayList<String>().apply { add(r.code) }
            val companies = ArrayList<String>().apply { if (r.company.isNotEmpty()) add(r.company) }
            val g = ExpressGroup(r.address, r.cabinet, codes, companies, r.source, r.receivedAt, listOf(r))
            val prev = byKey[g.key]
            if (prev == null) {
                byKey[g.key] = g
            } else {
                // 同码不同地址是合法的（不同驿站可能发同格式短码），按 key 分组互不吞并；
                // 组内同码（同一包裹的重复提醒）合并显示但保留各自行，逐码"已取"互不影响。
                val nc = (prev.codes + r.code).distinct()
                val nco = (prev.companies + (if (r.company.isNotEmpty()) listOf(r.company) else emptyList())).distinct()
                byKey[g.key] = prev.copy(codes = nc, companies = nco,
                    receivedAt = maxOf(prev.receivedAt, r.receivedAt), rows = prev.rows + r)
            }
        }
        return byKey.values.toList()
    }

    fun deletePickup(ctx: Context, id: Long) {
        ctx.contentResolver.delete(Uri.parse("${C.ENTRIES_URI}/$id"), null, null)
    }

    /**
     * 一键取该批次的**未取**件：已提前单独取过的行不动，保留其原 picked_at。
     * [batch] 为 null 是 widget 旧组级 PendingIntent 的兼容路径（按地址 + picked=0，天然只命中活跃批次）。
     */
    fun markPicked(ctx: Context, address: String, batch: Int? = null) {
        val v = ContentValues().apply {
            put(C.COL_PICKED, 1)
            put(C.COL_PICKED_AT, System.currentTimeMillis())
        }
        val sel = if (batch == null) "${C.COL_ADDRESS}=? AND ${C.COL_PICKED}=0"
        else "${C.COL_ADDRESS}=? AND ${C.COL_BATCH}=? AND ${C.COL_PICKED}=0"
        val args = if (batch == null) arrayOf(address) else arrayOf(address, batch.toString())
        ctx.contentResolver.update(Uri.parse(C.ENTRIES_URI), v, sel, args)
    }

    /** 标记单条取件记录为"已取件"（按行 id，精确到一条——不同地址可能同码，不能用 code 定位）。 */
    fun markPickedCode(ctx: Context, id: Long) {
        val v = ContentValues().apply {
            put(C.COL_PICKED, 1)
            put(C.COL_PICKED_AT, System.currentTimeMillis())
        }
        ctx.contentResolver.update(
            Uri.parse(C.ENTRIES_URI), v,
            "${C.COL_ID}=? AND ${C.COL_PICKED}=0", arrayOf(id.toString()),
        )
    }

    /** 删除某一张已取件卡片（地址 + 批次）：只按地址删会连该地址新的待取件一起删掉。 */
    fun deleteGroup(ctx: Context, address: String, batch: Int? = null) {
        val sel = if (batch == null) "${C.COL_ADDRESS}=?" else "${C.COL_ADDRESS}=? AND ${C.COL_BATCH}=?"
        val args = if (batch == null) arrayOf(address) else arrayOf(address, batch.toString())
        ctx.contentResolver.delete(Uri.parse(C.ENTRIES_URI), sel, args)
    }

    private fun row(c: Cursor): ExpressRow {
        fun s(col: String) = c.getString(c.getColumnIndexOrThrow(col)) ?: ""
        fun l(col: String) = c.getLong(c.getColumnIndexOrThrow(col))
        fun i(col: String): Int {
            val idx = c.getColumnIndex(col)
            return if (idx < 0) 0 else c.getInt(idx)
        }
        fun lo(col: String): Long {
            val idx = c.getColumnIndex(col)
            return if (idx < 0) 0L else c.getLong(idx)
        }
        return ExpressRow(
            id = l(C.COL_ID),
            code = s(C.COL_CODE),
            company = s(C.COL_COMPANY),
            source = s(C.COL_SOURCE),
            address = s(C.COL_ADDRESS),
            cabinet = s(C.COL_CABINET),
            origin = s(C.COL_ORIGIN),
            originPkg = s(C.COL_ORIGIN_PKG),
            receivedAt = l(C.COL_RECEIVED_AT),
            raw = s(C.COL_RAW),
            smsHash = s(C.COL_SMS_HASH),
            picked = i(C.COL_PICKED),
            pickedAt = lo(C.COL_PICKED_AT),
            batch = i(C.COL_BATCH),
        )
    }
}

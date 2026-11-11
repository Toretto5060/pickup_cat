package com.toretto5060.pickupcatapp.express

import android.content.Context
import android.os.Binder
import android.util.Log
import com.toretto5060.pickupcatapp.express.ExpressConst as C
import java.util.Locale

/**
 * MAML 主题小部件的数据快照，两条通道共用同一份取值：
 *  1. [MamlWidgetProvider] 把它摊平成单行 Cursor，供 ContentProviderBinder 在 init 时拉取；
 *  2. [MamlWidgetNotifier] 把它塞进刷新广播的 extras，供 BroadcastBinder 直接注入变量（主通道）。
 *
 * 口径（用户 11-10 两轮反馈后的最终态）：
 *  - `count` = 取件点**组数**（归一化地址 + 冻结批次算 1 件），用于标题「N 件待取」；
 *  - `rows`  = 待取件**取件码条数**，用于逐行显示；`code1..5` 一行一个码，
 *    **绝不**把同地址同快递的多个码用「、」拼到同一行。
 *  - 同一组里的每一行共用该组归一化后的快递品牌名（跳过「2条」这类解析噪声）。
 *
 * [Binder.clearCallingIdentity]：本函数常跑在外部进程的 Binder 调用栈里 —— 桌面查询
 * MamlWidgetProvider、或 hook 进程写 ExpressProvider。此时内部再用 contentResolver 读私有
 * ExpressProvider，Binder.getCallingUid() 会**继承外部 uid**，被 allowed() 拒绝 →
 * db_error → count=0。必须清掉 calling identity 才能以本 App 的 uid 读到数据。
 *
 * count / rows 刻意用 Double：MAML BroadcastBinder 的 type="number" 走 Intent.getDoubleExtra，
 * 若这里放 Int，Bundle 类型不匹配会读出默认值 0。
 */
internal object MamlWidgetSnapshot {
    private const val TAG = "PickupCatMaml"
    const val MAX_ROWS = 5

    val COLUMNS: Array<String> = buildList {
        add(C.MAML_COL_STATUS)
        add(C.MAML_COL_COUNT)
        add(C.MAML_COL_ROWS)
        add("rows_shown")
        add(C.MAML_COL_UPDATED_AT)
        add(C.MAML_COL_EMPTY_TEXT)
        add(C.MAML_COL_SUFFIX)
        for (i in 1..MAX_ROWS) {
            add("id$i")
            add("company$i")
            add("company_color$i")
            add("code$i")
            add("time$i")
            add("addr$i")
        }
    }.toTypedArray()

    fun snapshot(ctx: Context?): Map<String, Any?> {
        if (ctx == null) return emptySnapshot("no_context")
        val (groups, rows) = try {
            withClearedIdentity {
                ExpressStore.queryGroupsForTab(ctx, false) to ExpressStore.queryAll(ctx, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot failed", t)
            return emptySnapshot("db_error")
        }
        Log.d(TAG, "snapshot ok: groups=${groups.size} codes=${rows.size}")
        // 归一化地址 + 批次 → 该取件点的快递品牌，组内每一行共用，避免脏值行显示空白公司名。
        val brandByKey = groups.associate { g ->
            "${ExpressGroup.normalize(g.address)}#${g.batch}" to
                g.companies.firstNotNullOfOrNull { companyBrand(it).takeIf(String::isNotEmpty) }.orEmpty()
        }
        val out = HashMap<String, Any?>()
        out[C.MAML_COL_STATUS] = "ok"
        out[C.MAML_COL_COUNT] = groups.size.toDouble()
        out[C.MAML_COL_ROWS] = rows.size.toDouble()
        // 显示上限封顶：组件只生成 MAX_ROWS 个行槽，pc_rows 真实条数可能超过它。
        // 若直接把真实 pc_rows 交给「行可见性」的 == 链（pc_rows==N||...||pc_rows==MAX_ROWS），
        // 一旦 pc_rows>MAX_ROWS 所有 == 都不命中 → 卡片整片消失且无法恢复。
        // 因此单独给一个封顶的 rows_shown 专供可见性/圆点使用，标题仍用真实 rows。
        out["rows_shown"] = if (rows.size > MAX_ROWS) MAX_ROWS.toDouble() else rows.size.toDouble()
        out[C.MAML_COL_UPDATED_AT] = rows.maxOfOrNull { it.receivedAt } ?: 0L
        out[C.MAML_COL_EMPTY_TEXT] = "还没有待取件"
        out[C.MAML_COL_SUFFIX] = "件待取"
        for (i in 0 until MAX_ROWS) {
            val r = rows.getOrNull(i)
            val n = i + 1
            val company = r?.let { brandByKey["${ExpressGroup.normalize(it.address)}#${it.batch}"] }.orEmpty()
            out["id$n"] = (r?.id ?: 0L).toDouble()
            out["company$n"] = company
            out["company_color$n"] = if (company.isEmpty()) "" else
                "#%08X".format(Locale.ROOT, brandColorArgb(company))
            out["code$n"] = r?.let {
                if (it.cabinet.isNotEmpty()) "${cabinetShort(it.cabinet)} ${it.code}" else it.code
            }.orEmpty()
            out["time$n"] = r?.let { formatReceivedAt(it.receivedAt) }.orEmpty()
            out["addr$n"] = r?.address.orEmpty()
        }
        return out
    }

    /** 无论调用来自哪个进程，都以本 App 的 uid 读私有 Provider。 */
    private inline fun <T> withClearedIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun emptySnapshot(status: String): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        out[C.MAML_COL_STATUS] = status
        out[C.MAML_COL_COUNT] = 0.0
        out[C.MAML_COL_ROWS] = 0.0
        out[C.MAML_COL_UPDATED_AT] = 0L
        out[C.MAML_COL_EMPTY_TEXT] = "还没有待取件"
        out[C.MAML_COL_SUFFIX] = "件待取"
        for (i in 1..MAX_ROWS) {
            out["id$i"] = 0.0
            out["company$i"] = ""
            out["company_color$i"] = ""
            out["code$i"] = ""
            out["time$i"] = ""
            out["addr$i"] = ""
        }
        return out
    }
}

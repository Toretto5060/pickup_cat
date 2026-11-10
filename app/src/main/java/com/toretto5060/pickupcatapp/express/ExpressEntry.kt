package com.toretto5060.pickupcatapp.express

/**
 * The result of parsing one raw text: the pieces a pickup needs. Built by [ExpressParse]; turned into
 * an [ExpressRow] (one DB row) by the capture side, and grouped into [ExpressGroup] for display.
 */
data class ParsedPickup(
    val code: String,          // bare code, e.g. 72307130
    val company: String,       // courier / 代收点 name (申通快递), "" when not found
    val address: String,       // base address, cabinet already removed
    val cabinet: String,       // "6号柜" or ""
    val source: String,        // 短信 / app label
    val origin: String,        // "sms" / "notify:<pkg>"
    val originPkg: String,     // source package; "" for sms
    val raw: String,
)

/** One persisted pickup. [smsHash] de-dupes an SMS that the provider would otherwise insert twice,
 *  but only against pending (picked=0) rows — a picked-up row must not swallow a later identical code. */
data class ExpressRow(
    val id: Long,
    val code: String,
    val company: String,
    val source: String,
    val address: String,
    val cabinet: String,
    val origin: String,
    val originPkg: String,
    val receivedAt: Long,
    val raw: String,
    val smsHash: String,
    val picked: Int = 0,       // 0 = 待取件, 1 = 已取件
    val pickedAt: Long = 0,    // 已取时间（0 表示未取或旧数据）
    val batch: Int = 0,        // 冻结批次：该地址全部取出后新来的信息另起一批
)

/**
 * A display group: every pickup sharing the same **address** (requirement 1: 同一地址的待取件
 * 整合在一起). 不同柜号也聚在同一张卡片里，柜号不再参与分组键，而是显示在各行 [rows] 的
 * `row.cabinet` 上（"取件码 · 6号柜 · 09:20 到站"）。因此组级 [cabinet] 恒为空，仅保留字段兼容
 * [displayCode]（widget 已改为逐码渲染，不再用它）。
 */
data class ExpressGroup(
    val address: String,
    val cabinet: String,
    val codes: List<String>,
    val companies: List<String>,
    val source: String,
    val receivedAt: Long,
    /** 组内原始行（receivedAt DESC），供逐码"已取"等行级操作使用；widget 不用它。 */
    val rows: List<ExpressRow> = emptyList(),
    val batch: Int = 0,        // 冻结批次；同地址的不同批次是两张独立的卡
    val pickedAt: Long = 0L,   // 全部取出时间 = 组内最晚的 picked_at（旧数据退化用到站时间）
) {
    /** The code line shown: "[6柜 ]c1、c2、…" . */
    val displayCode: String
        get() {
            val prefix = if (cabinet.isNotEmpty()) cabinetShort(cabinet) + " " else ""
            return prefix + codes.joinToString("、")
        }

    /** 分组键 = 归一化地址 + 冻结批次。批次让「全部取出」那一刻的已取件卡片永久冻结。 */
    val key: String get() = "${normalize(address)}#$batch"

    companion object {
        /** Whitespace- and case-insensitive address/cabinet normalisation for grouping. */
        fun normalize(s: String): String = s.trim().replace("\\s+".toRegex(), "").lowercase()
    }
}

/** "6号柜" -> "6柜"; falls back to the raw cabinet when it does not match the shape. */
internal fun cabinetShort(c: String): String {
    val m = Regex("""(\d+)\s*号?\s*[柜橱箱格]""").find(c)
    return if (m != null) "${m.groupValues[1]}柜" else c
}

/**
 * 快递品牌归一化：同品牌的不同写法（「韵达快递」/「韵达速递」/「【韵达快递】」）归到同一个键，
 * 用来判断「同一取件点里是不是真的混了不同快递」。
 *
 * 解析噪声（「2条」「通知」这类既不是已知品牌、又很短的串）返回空串：不能因为一条脏数据
 * 就把同一家快递的两条取件码误判成两家，从而叠出两个 logo。
 */
internal fun companyBrand(name: String): String {
    val s = name.trim()
    if (s.isEmpty()) return ""
    val aliases = listOf(
        "申通" to "申通快递", "圆通" to "圆通速递", "韵达" to "韵达快递",
        "中通" to "中通快递", "顺丰" to "顺丰", "京东" to "京东",
        "邮政" to "邮政", "EMS" to "EMS", "极兔" to "极兔",
        "菜鸟" to "菜鸟", "丰巢" to "丰巢", "兔喜" to "兔喜生活", "多多" to "多多代收点",
    )
    for ((key, canonical) in aliases) {
        if (s.contains(key, ignoreCase = true)) return canonical
    }
    return if (s.length < 3) "" else s
}

/** 分组键：与 [ExpressGroup.key] 同口径（归一化地址 + 冻结批次）。 */
internal fun groupKey(address: String, batch: Int): String =
    "${ExpressGroup.normalize(address)}#$batch"

/**
 * 同地址同批次里第一个可识别的快递品牌，供标准 AppWidget 逐行渲染共用。
 * 解析脏值（「2条」）会被 [companyBrand] 过滤掉，避免它出现在公司名位置。
 */
internal fun brandByGroup(rows: List<ExpressRow>): Map<String, String> =
    rows.groupBy { groupKey(it.address, it.batch) }
        .mapValues { (_, list) ->
            list.firstNotNullOfOrNull { companyBrand(it.company).takeIf(String::isNotEmpty) }.orEmpty()
        }

/** 快递品牌色（ARGB）：列表徽标与小部件公司文字共用同一映射，未知品牌按名取色。 */
internal fun brandColorArgb(company: String): Int {
    val known = mapOf(
        "申通快递" to 0xFF1C64B2, "圆通速递" to 0xFF1E6BCF, "韵达快递" to 0xFF0E4C9A,
        "中通快递" to 0xFF1B1B1B, "顺丰" to 0xFF000000, "京东" to 0xFFD4221E,
        "邮政" to 0xFF0A6CC4, "EMS" to 0xFF0A6CC4, "极兔" to 0xFFE2231A,
        "菜鸟" to 0xFFFE7300, "丰巢" to 0xFF2BB6A3, "兔喜生活" to 0xFFE8541E,
        "多多代收点" to 0xFFE02E1A,
    )
    return (known[company] ?: company.hashCode().let { h ->
        val r = (h shr 16 and 0xFF) / 255f; val g = (h shr 8 and 0xFF) / 255f; val b = (h and 0xFF) / 255f
        android.graphics.Color.HSVToColor(floatArrayOf((r * 360f), 0.55f, 0.8f))
    }).toInt()
}

/**
 * 收到（入库）/到站/已取时间的统一显示（列表、胶囊、小部件共用）：
 *  - 今天：`HH:mm`
 *  - 昨天 / 前天：`昨天 (周三) HH:mm` / `前天 (周二) HH:mm`（相对日词 + 周几）
 *  - 今年且更早：`MM.dd (周三) HH:mm`（如 `11.03 (周三) 09:20`）
 *  - 不是今年：`yyyy.MM.dd (周三) HH:mm`（如 `2025.12.30 (周二) 09:20`，跨年优先于"昨天/前天"）
 *
 * 所有非今天档都在"日期/相对日词"与时刻之间带周几。
 */
internal fun formatReceivedAt(ms: Long): String {
    val locale = java.util.Locale.getDefault()
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    fun dayStart(c: java.util.Calendar): java.util.Calendar = c.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val diffDays = ((dayStart(java.util.Calendar.getInstance()).timeInMillis -
        dayStart(java.util.Calendar.getInstance().apply { timeInMillis = ms }).timeInMillis) /
        java.util.concurrent.TimeUnit.DAYS.toMillis(1)).toInt()
    val clock = java.text.SimpleDateFormat("HH:mm", locale).format(java.util.Date(ms))
    val zh = locale.language == "zh"
    // 周几：中文用固定的"周三"短式，英文交给 SimpleDateFormat("E")（Wed）。
    val weekday = if (zh) {
        arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")[then.get(java.util.Calendar.DAY_OF_WEEK)]
    } else {
        java.text.SimpleDateFormat("E", locale).format(java.util.Date(ms))
    }
    val week = "($weekday)"
    return when {
        now.get(java.util.Calendar.YEAR) != then.get(java.util.Calendar.YEAR) ->
            java.text.SimpleDateFormat("yyyy.MM.dd", locale).format(java.util.Date(ms)) + " $week $clock"
        diffDays == 0 -> clock
        diffDays == 1 -> (if (zh) "昨天" else "Yesterday") + " $week $clock"
        diffDays == 2 -> (if (zh) "前天" else "2 days ago") + " $week $clock"
        else -> java.text.SimpleDateFormat("MM.dd", locale).format(java.util.Date(ms)) + " $week $clock"
    }
}

/**
 * 按「地址 + 冻结批次」分卡：组内还有未取码 → 待取件；全部已取 → 已取件（冻结快照，
 * 之后同地址的新信息属于另一个 batch，不会把旧卡拉回待取件）。
 * 已取件按「全部取出时间」倒序：多码卡片取组内最晚的 picked_at；待取件保持传入的行序
 * （调用方按 received_at DESC 取数）。纯函数，不依赖 Android，便于 JVM 单测。
 */
internal fun groupRowsForTab(rows: List<ExpressRow>, pickedTab: Boolean): List<ExpressGroup> {
    val byKey = LinkedHashMap<String, MutableList<ExpressRow>>()
    for (r in rows) {
        byKey.getOrPut("${ExpressGroup.normalize(r.address)}#${r.batch}") { ArrayList() }.add(r)
    }
    val groups = byKey.values.mapNotNull { list ->
        val hasPending = list.any { it.picked == 0 }
        if (hasPending == pickedTab) return@mapNotNull null
        // 迁移前的数据没有 picked_at（默认 0），退化到该批次最新的到站时间，避免显示 1970。
        val allPickedAt = list.maxOf { it.pickedAt }.takeIf { it > 0 } ?: list.maxOf { it.receivedAt }
        ExpressGroup(
            address = list.first().address,
            cabinet = "",
            codes = list.map { it.code }.distinct(),
            companies = list.mapNotNull { it.company.takeIf(String::isNotEmpty) }.distinct(),
            source = list.first().source,
            receivedAt = list.maxOf { it.receivedAt },
            rows = list,
            batch = list.first().batch,
            pickedAt = allPickedAt,
        )
    }
    return if (pickedTab) groups.sortedByDescending { it.pickedAt } else groups
}

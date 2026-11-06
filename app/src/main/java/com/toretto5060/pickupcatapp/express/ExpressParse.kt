package com.toretto5060.pickupcatapp.express

import com.toretto5060.pickupcatapp.express.ExpressConst.ORIGIN_SMS
import com.toretto5060.pickupcatapp.express.ExpressConst.SOURCE_SMS

/**
 * Turns a raw SMS / notification text into a [ParsedPickup] using a list of [ParseRule]s.
 *
 * The rules are data, not code: [BUILTIN_RULES] is the default set distilled from the first SMS
 * samples, and a user can later add rules through the config page (saved as JSON in the provider's
 * settings). [parse] tries the highest-priority enabled rule first and returns the first that yields
 * both a code and an address. A text that yields neither is not a pickup and is dropped.
 *
 * Cabinet handling: a rule's `cabinet` pattern is matched against the tail of the address; on a hit
 * the cabinet is moved out of the address so it can be shown as a code prefix ("6号柜" -> code
 * "6柜 72307130") rather than bloating the address (user requirement).
 */
internal object ExpressParse {

    /** The default rule, covering the five SMS templates gathered on 2026-11-04. */
    val BUILTIN_RULES: List<ParseRule> = listOf(
        ParseRule(
            id = "builtin_sms",
            priority = 0,
            enabled = true,
            // 不锚定行首：QQ 等通知会把发送者名（"Cry、遮掩"）拼在正文前，锚定会丢 company。
            // 只用于展示/配色，正文里的第一个【】足够可靠。
            company = """[【\[]\s*(.+?)\s*[】\]]""",
            codes = listOf(
                """凭\s*([0-9A-Za-z\-]{2,})""",
                """取件码\s*为?\s*[:：]?\s*([0-9A-Za-z\-]{2,})""",
                """取货码\s*为?\s*[:：]?\s*([0-9A-Za-z\-]{2,})""",
                """(?:取件|取货|提货|取物|柜|码)\D{0,3}([0-9]{1,3}-[0-9]{1,3}-[0-9]{2,6}|[0-9]{6,12})""",
            ),
            addresses = listOf(
                """地址[:：]\s*(.+)""",
                """到\s*(.+?)\s*取""",
                """已到\s*(.+?)(?:[，,。.\s].*)?${'$'}""",
                """到达\s*(.+?)[，,]""",
                """在\s*(.+?)\s*(?:取|拿|领)""",
            ),
            cabinet = """(\d+)\s*号?\s*[柜橱箱格]${'$'}""",
        ),
    )

    /**
     * Parse [text]. [source]/[origin]/[originPkg] label where it came from (短信, or an app); the
     * parser only fills code/company/address/cabinet. [extraRules] (custom, higher priority) are
     * tried before [BUILTIN_RULES].
     */
    fun parse(
        text: String,
        source: String = SOURCE_SMS,
        origin: String = ORIGIN_SMS,
        originPkg: String = "",
        extraRules: List<ParseRule> = emptyList(),
    ): ParsedPickup? {
        val rules = (extraRules + BUILTIN_RULES)
            .filter { it.enabled }
            .sortedByDescending { it.priority }
        for (rule in rules) {
            parseWith(rule, text)?.let {
                return it.copy(source = source, origin = origin, originPkg = originPkg, raw = text)
            }
        }
        return null
    }

    private fun parseWith(rule: ParseRule, text: String): ParsedPickup? {
        // 整体模板优先：一处正则匹配，通过命名捕获组同时拿到所有字段（纠错保存生成的规则走这里）。
        if (rule.template.isNotEmpty()) {
            val m = Regex(rule.template, RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
            val company = m.groups["company"]?.value?.trim().orEmpty()
            val code = m.groups["code"]?.value?.trim().orEmpty()
            val address = m.groups["address"]?.value?.trim().orEmpty()
            val cabinet = m.groups["cabinet"]?.value?.trim().orEmpty()
            if (code.isEmpty() || address.isEmpty()) return null
            return ParsedPickup(
                code = code, company = company, address = address, cabinet = cabinet,
                source = SOURCE_SMS, origin = ORIGIN_SMS, originPkg = "", raw = text,
            )
        }

        val company = if (rule.company.isNotEmpty()) {
            Regex(rule.company, RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        } else ""

        val code = rule.codes.firstNotNullOfOrNull { p ->
            Regex(p).find(text)?.groupValues?.getOrNull(1)?.trim()
        }?.takeIf { it.isNotEmpty() } ?: return null

        val rawAddress = rule.addresses.firstNotNullOfOrNull { p ->
            Regex(p, RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.getOrNull(1)?.trim()
        }?.takeIf { it.isNotEmpty() } ?: return null

        var address = rawAddress
        var cabinet = ""
        if (rule.cabinet.isNotEmpty()) {
            val cm = Regex(rule.cabinet).find(address)
            if (cm != null) {
                cabinet = cm.value.trim()
                address = address.removeRange(cm.range).trim().removeSuffix("、").removeSuffix("，").trim()
            }
        }

        if (address.isEmpty()) return null
        return ParsedPickup(
            code = code,
            company = company,
            address = address,
            cabinet = cabinet,
            source = SOURCE_SMS,
            origin = ORIGIN_SMS,
            originPkg = "",
            raw = text,
        )
    }
}

/**
 * 从一条样本 + 用户纠正后的字段生成整体模板规则。纠正值都是原文子串，所以直接 indexOf 定位，
 * 把原文按各字段下标切分成「固定段（字面量）+ 命名捕获组」，一处匹配即得所有字段。
 */
internal fun buildTemplateRule(
    sample: String,
    code: String,
    company: String,
    address: String,
    cabinet: String,
): ParseRule = ParseRule(
    id = "custom_${System.currentTimeMillis()}",
    enabled = true,
    priority = 10,
    template = buildTemplate(sample, company, code, address, cabinet),
)

/**
 * 按 company/code/address/cabinet 在原文中的下标切分原文，生成带命名捕获组的整体正则。
 * 字段为空或在原文中找不到就跳过（对应捕获组缺席，解析时取空串）。
 */
internal fun buildTemplate(sample: String, company: String, code: String, address: String, cabinet: String): String {
    data class Seg(val name: String, val start: Int, val end: Int)
    val segs = ArrayList<Seg>()
    fun put(name: String, value: String) {
        if (value.isEmpty()) return
        val idx = sample.indexOf(value)
        if (idx >= 0) segs.add(Seg(name, idx, idx + value.length))
    }
    put("company", company)
    put("code", code)
    put("address", address)
    put("cabinet", cabinet)
    segs.sortBy { it.start }
    var pos = 0
    val sb = StringBuilder()
    for (s in segs) {
        // 重叠段（例如 cabinet 是 address 尾部、未剥离干净）丢弃靠后的，避免正则错乱。
        if (s.start < pos) continue
        sb.append(Regex.escape(sample.substring(pos, s.start)))
        sb.append("(?<").append(s.name).append(">").append(captureFor(s.name)).append(")")
        pos = s.end
    }
    sb.append(Regex.escape(sample.substring(pos)))
    return sb.toString()
}

/** 每个字段在整体模板里的捕获表达式。 */
internal fun captureFor(name: String): String = when (name) {
    "code" -> "[0-9A-Za-z\\-]{2,}"
    "cabinet" -> "\\d+\\s*号?\\s*[柜橱箱格]"
    else -> ".+?"
}

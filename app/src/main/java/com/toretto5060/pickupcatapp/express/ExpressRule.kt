package com.toretto5060.pickupcatapp.express

import org.json.JSONArray
import org.json.JSONObject

/**
 * One set of regexes that turns a raw SMS / notification text into a [ParsedPickup].
 *
 * Kept as plain serialisable data (no compiled [Regex] inside) so a rule can be saved to the
 * provider's settings as JSON and edited on a config page later - the user asked for "贴一段文本 →
 * 纠错 → 保存规则". Only the engine holds the compiled patterns; this is just the recipe.
 *
 * Each list is tried in order; the first pattern that matches wins. A rule is "used" when every one
 * of [code] and [address] patterns finds something (company is optional). The [cabinet] pattern, if
 * it matches the tail of the extracted address, is pulled out so it can be shown as a code prefix
 * instead of confusing the address ("妈妈驿站6号柜" -> address "妈妈驿站", cabinet "6号柜").
 */
data class ParseRule(
    val id: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    /** Extract the courier name, e.g. `^[【\[]\s*(.+?)\s*[】\]]`. */
    val company: String = "",
    /** Candidate code patterns, each with exactly one capture group, tried in order. */
    val codes: List<String> = emptyList(),
    /** Candidate address patterns, each with exactly one capture group, tried in order. */
    val addresses: List<String> = emptyList(),
    /** Pull a cabinet suffix off the address tail (e.g. `(\d+)\s*号?\s*[柜橱箱格]$`). */
    val cabinet: String = "",
    /**
     * 整体模板正则（含命名捕获组 company/code/address/cabinet），优先于上面的分字段正则。
     * 由"贴文本 → 纠错 → 保存"生成：把原文按各字段下标切分成固定段 + 捕获组，一处匹配即得所有字段。
     */
    val template: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("priority", priority)
        put("company", company)
        put("codes", JSONArray(codes))
        put("addresses", JSONArray(addresses))
        put("cabinet", cabinet)
        put("template", template)
    }

    companion object {
        fun fromJson(o: JSONObject): ParseRule = ParseRule(
            id = o.optString("id", ""),
            enabled = o.optBoolean("enabled", true),
            priority = o.optInt("priority", 0),
            company = o.optString("company", ""),
            codes = jsonList(o.optJSONArray("codes")),
            addresses = jsonList(o.optJSONArray("addresses")),
            cabinet = o.optString("cabinet", ""),
            template = o.optString("template", ""),
        )

        private fun jsonList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) out.add(a.optString(i, ""))
            return out
        }
    }
}

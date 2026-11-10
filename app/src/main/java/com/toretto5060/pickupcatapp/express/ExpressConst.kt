package com.toretto5060.pickupcatapp.express

/**
 * Cross-process contract for the express (快递取件码) feature.
 *
 * Everything the feature stores lives in [ExpressProvider], which runs in the app process and is
 * reached from any hooked process through these URIs. The telephony process that catches an SMS and
 * the SystemUI process that may catch a notification both write through [ENTRIES_URI]; the widget,
 * the list page and the settings page all read through it. Settings (the master switch, the list of
 * apps whose notifications to listen to, the user's custom parse rules) go through [SETTINGS_URI].
 */
internal object ExpressConst {
    /** Authority of [ExpressProvider]; the app package with a suffix so it cannot clash. */
    const val AUTHORITY = "com.toretto5060.pickupcatapp.express"

    const val ENTRIES_URI = "content://$AUTHORITY/entries"
    const val SETTINGS_URI = "content://$AUTHORITY/settings"

    /** 供 HyperOS 4 MAML 小部件（.mtz 主题）读取的只读投影，独立于私有数据库 Provider。 */
    const val MAML_AUTHORITY = "com.toretto5060.pickupcatapp.maml"
    const val MAML_PENDING_URI = "content://$MAML_AUTHORITY/pending"
    const val MAML_COL_COUNT = "count"
    const val MAML_COL_UPDATED_AT = "updated_at"
    const val MAML_COL_EMPTY_TEXT = "empty_text"
    const val MAML_COL_SUFFIX = "suffix"

    /** SQLite table that holds one row per captured pickup. */
    const val TABLE_ENTRIES = "entries"
    /** SQLite table that holds one key/value row of settings. */
    const val TABLE_SETTINGS = "settings"

    // ----------------------------------------------------------------- entries columns
    const val COL_ID = "_id"
    const val COL_CODE = "code"               // bare code, cabinet stripped (e.g. 72307130)
    const val COL_COMPANY = "company"         // courier /代收点 name, for logo (申通快递)
    const val COL_SOURCE = "source"           // "短信" or an app's label
    const val COL_ADDRESS = "address"         // base address, cabinet removed
    const val COL_CABINET = "cabinet"         // "6号柜" or "" (grouping key part)
    const val COL_ORIGIN = "origin"           // "sms" or "notify:<pkg>"
    const val COL_ORIGIN_PKG = "origin_pkg"   // source package; "" for sms
    const val COL_RECEIVED_AT = "received_at"
    const val COL_RAW = "raw"                 // original sms / notification text
    const val COL_SMS_HASH = "sms_hash"       // de-dupe key for an incoming sms
    const val COL_PICKED = "picked"           // 0 = 待取件, 1 = 已取件（取件后标记，不再删除）
    const val COL_PICKED_AT = "picked_at"     // 标记已取的时间；"全部取件"时已取条目保留原值
    const val COL_BATCH = "batch"             // 冻结批次：该地址全部取出后，下一条新信息另起一批

    // ----------------------------------------------------------------- settings keys
    const val COL_KEY = "key"                       // settings table column
    const val KEY_LISTEN_APPS = "listen_apps"        // "|"-joined package names
    const val KEY_CUSTOM_RULES = "custom_rules"      // JSON array of ParseRule

    const val SOURCE_SMS = "短信"
    const val ORIGIN_SMS = "sms"
}

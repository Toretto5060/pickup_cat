package com.toretto5060.pickupcatapp.app
import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Settings that belong to the app itself - how the UI looks and what language it speaks.
 *
 * 取件喵只保留界面相关的设置：主题模式、语言、模糊开关与少量外观开关。
 */
data class AppSettings(
    val themeMode: String = "System",
    val isFloatingNavbar: Boolean = false,
    val isLiquidGlass: Boolean = false,
    val isBlurEnabled: Boolean = true,
    val language: String = "",
    /** 无待取件时隐藏组件：true = 清空文字 + 整块透明；false = 正常显示「0 件待取」+ 空态行。 */
    val hideWidgetWhenEmpty: Boolean = true,
    /** 双击锁屏：需开启无障碍服务；仅在 hideWidgetWhenEmpty 开启且 0 件时生效。默认关闭。 */
    val doubleTapLockScreen: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("themeMode", themeMode)
        put("isFloatingNavbar", isFloatingNavbar)
        put("isLiquidGlass", isLiquidGlass)
        put("isBlurEnabled", isBlurEnabled)
        put("language", language)
        put("hideWidgetWhenEmpty", hideWidgetWhenEmpty)
        put("doubleTapLockScreen", doubleTapLockScreen)
    }.toString(2)

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FLOATING_NAVBAR = "floating_navbar"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_HIDE_WIDGET_WHEN_EMPTY = "hide_widget_when_empty"
        private const val KEY_DOUBLE_TAP_LOCK = "double_tap_lock_screen"

        /**
         * 「无待取件时隐藏组件」关闭时，双击锁屏必须归零：它只在空态生效，
         * 且用户要求每次重新开启隐藏组件时双击锁屏都是默认关闭。
         * 放在数据层统一约束，导入 JSON / 任何调用方改设置都无法绕过。
         */
        private fun AppSettings.constrained(): AppSettings =
            if (hideWidgetWhenEmpty) this else copy(doubleTapLockScreen = false)

        /**
         * 读取旧 SP / 导入 JSON 时的防御：即使之前残留 hide=false + doubleTap=true，
         * 也读成 false，避免重新开启隐藏组件后又冒出来。
         */
        private fun resolveDoubleTap(hide: Boolean, stored: Boolean): Boolean = hide && stored

        fun fromJson(json: String): AppSettings = try {
            val obj = JSONObject(json)
            val hide = obj.optBoolean("hideWidgetWhenEmpty", true)
            AppSettings(
                themeMode = obj.optString("themeMode", "System"),
                isFloatingNavbar = obj.optBoolean("isFloatingNavbar", false),
                isLiquidGlass = obj.optBoolean("isLiquidGlass", false),
                isBlurEnabled = obj.optBoolean("isBlurEnabled", true),
                language = obj.optString("language", ""),
                hideWidgetWhenEmpty = hide,
                doubleTapLockScreen = resolveDoubleTap(
                    hide, obj.optBoolean("doubleTapLockScreen", false),
                ),
            )
        } catch (_: Exception) {
            AppSettings()
        }

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hide = prefs.getBoolean(KEY_HIDE_WIDGET_WHEN_EMPTY, true)
            return AppSettings(
                themeMode = prefs.getString(KEY_THEME_MODE, "System") ?: "System",
                isFloatingNavbar = prefs.getBoolean(KEY_FLOATING_NAVBAR, false),
                isLiquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, false),
                isBlurEnabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
                language = LocaleHelper.getSavedLanguage(context).code,
                hideWidgetWhenEmpty = hide,
                doubleTapLockScreen = resolveDoubleTap(
                    hide, prefs.getBoolean(KEY_DOUBLE_TAP_LOCK, false),
                ),
            )
        }

        fun save(context: Context, settings: AppSettings) {
            val s = settings.constrained()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_THEME_MODE, s.themeMode)
                putBoolean(KEY_FLOATING_NAVBAR, s.isFloatingNavbar)
                putBoolean(KEY_LIQUID_GLASS, s.isLiquidGlass)
                putBoolean(KEY_BLUR_ENABLED, s.isBlurEnabled)
                putBoolean(KEY_HIDE_WIDGET_WHEN_EMPTY, s.hideWidgetWhenEmpty)
                putBoolean(KEY_DOUBLE_TAP_LOCK, s.doubleTapLockScreen)
            }
            val lang = LocaleHelper.Language.entries.find { it.code == s.language }
                ?: LocaleHelper.Language.SYSTEM
            LocaleHelper.setLanguage(context, lang)
        }

        fun importFromJson(context: Context, json: String): AppSettings {
            val settings = fromJson(json)
            save(context, settings)
            return settings
        }
    }
}

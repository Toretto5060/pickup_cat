package com.toretto5060.pickupcatapp.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.toretto5060.pickupcatapp.app.AppSettings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 全站主题：从 AppSettings 读取用户选择的模式（跟随系统 / 浅色 / 深色）。
 * 设置页切换后调用 Activity.recreate()，composition 重建即会重新读取，无需改各页面调用点。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val modeName = remember(context) { AppSettings.load(context).themeMode }
    val mode = when (modeName) {
        "Light" -> ColorSchemeMode.Light
        "Dark" -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(controller = controller) {
        val surface = MiuixTheme.colorScheme.surface
        val isDark = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue < 0.5f
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
        content()
    }
}

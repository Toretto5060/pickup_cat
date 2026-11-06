package com.toretto5060.pickupcatapp.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.ui.theme.AppTheme
import com.toretto5060.pickupcatapp.ui.util.PageScaffold
import com.toretto5060.pickupcatapp.ui.util.firstCard
import com.toretto5060.pickupcatapp.ui.util.nextCard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 设置页（由首页右上角的设置按钮进入）。只保留用户要求的项目：
 * 解析通知 App、通知监听权限、短信模板与解析规则、语言、主题模式。
 * 取件码捕获默认开启，故没有总开关；首页就是列表，故没有「取件码列表」入口。
 */
class ExpressActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val blur = AppSettings.load(this).isBlurEnabled
        setContent {
            AppTheme {
                SettingsPage(
                    isBlurEnabled = blur,
                    onOpen = { target -> startActivity(Intent(this, target)) },
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(
    isBlurEnabled: Boolean,
    onOpen: (Class<*>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = AppSettings.load(context)

    val languageTitle = stringResource(R.string.settings_language)
    val languageLabels: Array<CharSequence> = arrayOf(
        stringResource(R.string.settings_language_default),
        stringResource(R.string.settings_language_zh),
        stringResource(R.string.settings_language_en),
    )
    val languageCodes = arrayOf("", "zh-CN", "en")
    val languageIndex = languageCodes.indexOf(settings.language).coerceAtLeast(0)

    val themeTitle = stringResource(R.string.settings_theme)
    val themeLabels: Array<CharSequence> = arrayOf(
        stringResource(R.string.settings_theme_system),
        stringResource(R.string.settings_theme_light),
        stringResource(R.string.settings_theme_dark),
    )
    val themeValues = arrayOf("System", "Light", "Dark")
    val themeIndex = themeValues.indexOf(settings.themeMode).coerceAtLeast(0)
    val cancelLabel = stringResource(R.string.settings_cancel)

    PageScaffold(
        title = stringResource(R.string.settings_title),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item {
            Column {
                Card(modifier = Modifier.firstCard()) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.express_listen_apps),
                            summary = stringResource(R.string.express_listen_apps_summary),
                            onClick = { onOpen(ExpressAppPickActivity::class.java) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.settings_notification_permission),
                            summary = stringResource(R.string.settings_notification_permission_summary),
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.express_rules),
                            summary = stringResource(R.string.express_rules_summary),
                            onClick = { onOpen(ExpressRulesActivity::class.java) },
                        )
                    }
                }
                Card(modifier = Modifier.nextCard()) {
                    Column {
                        ArrowPreference(
                            title = languageTitle,
                            summary = languageLabels[languageIndex].toString(),
                            onClick = {
                                showChoice(
                                    context, languageTitle, languageLabels, languageIndex, cancelLabel,
                                ) { which ->
                                    AppSettings.save(
                                        context,
                                        AppSettings.load(context).copy(language = languageCodes[which]),
                                    )
                                    restartHome(context)
                                }
                            },
                        )
                        ArrowPreference(
                            title = themeTitle,
                            summary = themeLabels[themeIndex].toString(),
                            onClick = {
                                showChoice(
                                    context, themeTitle, themeLabels, themeIndex, cancelLabel,
                                ) { which ->
                                    AppSettings.save(
                                        context,
                                        AppSettings.load(context).copy(themeMode = themeValues[which]),
                                    )
                                    restartHome(context)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 单选弹窗：切换语言/主题后保存设置并重建 Activity（attachBaseContext / AppTheme 会重新读取）。 */
private fun showChoice(
    context: Context,
    title: String,
    labels: Array<CharSequence>,
    selected: Int,
    cancelLabel: String,
    onPick: (Int) -> Unit,
) {
    AlertDialog.Builder(context)
        .setTitle(title)
        .setSingleChoiceItems(labels, selected) { dialog, which ->
            dialog.dismiss()
            onPick(which)
        }
        .setNegativeButton(cancelLabel, null)
        .show()
}

/** 切换语言/主题后重启任务栈到首页：后台的旧首页实例不会自动换 Context / 主题。 */
private fun restartHome(context: Context) {
    context.startActivity(
        Intent(context, ExpressListActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK,
        ),
    )
}

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
import androidx.compose.runtime.mutableStateOf
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
 * 两项权限状态在这里以 summary 展示；缺应用列表权限时点击走系统权限框。
 */
class ExpressActivity : ComponentActivity() {
    private val permissionState = mutableStateOf(
        PermissionState(
            appListGranted = true,
            notificationAccessGranted = false,
            autoStart = AutoStartState.UNKNOWN,
            batteryUnrestricted = true,
            postNotificationsGranted = true,
        ),
    )
    private var pendingOpenPicker = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionState.value = PermissionGuard.state(this)
        val blur = AppSettings.load(this).isBlurEnabled
        setContent {
            AppTheme {
                SettingsPage(
                    isBlurEnabled = blur,
                    state = permissionState.value,
                    onOpen = { target -> startActivity(Intent(this, target)) },
                    onRequestAppList = {
                        pendingOpenPicker = true
                        PermissionGuard.requestAppList(this)
                    },
                    onPermissionAction = { kind -> PermissionGuard.openSettingsFor(this, kind) },
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionState.value = PermissionGuard.state(this)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionGuard.REQ_APP_LIST) {
            permissionState.value = PermissionGuard.state(this)
            if (pendingOpenPicker) {
                pendingOpenPicker = false
                if (permissionState.value.appListGranted) {
                    startActivity(Intent(this, ExpressAppPickActivity::class.java))
                } else if (PermissionGuard.shouldOpenAppDetails(this)) {
                    // 勾过「不再询问」系统不会再弹框，只能引导到应用信息页手动开。
                    PermissionGuard.openAppDetails(this)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    isBlurEnabled: Boolean,
    state: PermissionState,
    onOpen: (Class<*>) -> Unit,
    onRequestAppList: () -> Unit,
    onPermissionAction: (PermissionKind) -> Unit,
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
                            summary = stringResource(
                                if (state.appListGranted) R.string.express_listen_apps_summary
                                else R.string.settings_app_list_need_grant,
                            ),
                            onClick = {
                                // 未授权不打开 picker：先弹系统权限框，回调里授权通过再进。
                                if (state.appListGranted) onOpen(ExpressAppPickActivity::class.java)
                                else onRequestAppList()
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.settings_notification_permission),
                            summary = stringResource(
                                R.string.settings_permission_status,
                                stringResource(
                                    if (state.notificationAccessGranted) R.string.settings_permission_on
                                    else R.string.settings_permission_off,
                                ),
                                stringResource(R.string.settings_notification_permission_summary),
                            ),
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.settings_auto_start),
                            summary = stringResource(
                                if (state.autoStart == AutoStartState.DENIED) R.string.settings_auto_start_off
                                else R.string.settings_auto_start_on,
                            ),
                            onClick = { onPermissionAction(PermissionKind.AUTO_START) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.settings_battery),
                            summary = stringResource(
                                if (state.batteryUnrestricted) R.string.settings_battery_on
                                else R.string.settings_battery_off,
                            ),
                            onClick = { onPermissionAction(PermissionKind.BATTERY) },
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

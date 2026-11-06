package com.toretto5060.pickupcatapp.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.express.ExpressConst.KEY_LISTEN_APPS
import com.toretto5060.pickupcatapp.express.ExpressStore
import com.toretto5060.pickupcatapp.ui.theme.AppTheme
import com.toretto5060.pickupcatapp.ui.util.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 选择要监听通知的 App（可选限定）。取件码捕获主通道是"通知使用权"（ExpressListenService），不依赖
 * LSP 作用域：**白名单为空 = 解析全部 App 的通知（含短信）**；勾选后只解析勾选的 App。列表是本机
 * 全部可启动的第三方应用，排除系统应用（短信通知已由"全部"覆盖）与模块自身功能目标。选择实时写入
 * KEY_LISTEN_APPS。依赖 QUERY_ALL_PACKAGES（Manifest 已声明）。
 */
class ExpressAppPickActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val blur = AppSettings.load(this).isBlurEnabled
        setContent {
            AppTheme { AppPickPage(isBlurEnabled = blur) }
        }
    }
}

/** 模块自身功能目标，即使被勾选也不进入取件码监听候选。 */
private val EXCLUDED = setOf(
    "com.autonavi.minimap",    // 高德：出行
    "com.apple.android.music", // Apple Music：封面
    "com.miui.voiceassist",    // 超级小爱
)

private data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap?)

@Composable
private fun AppPickPage(isBlurEnabled: Boolean) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var selected by remember { mutableStateOf(ExpressStore.listenApps(context)) }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        apps = withContext(Dispatchers.IO) {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            ).distinctBy { it.activityInfo.packageName }
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo.packageName
                    if (pkg in EXCLUDED || pkg == context.packageName) return@mapNotNull null
                    val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                        ?: return@mapNotNull null
                    if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
                    AppInfo(
                        label = ri.loadLabel(pm).toString(),
                        pkg = pkg,
                        icon = appIcon(context, pkg)?.asImageBitmap(),
                    )
                }.sortedBy { it.label }
        }
    }

    fun persist(set: Set<String>) {
        ExpressStore.setSetting(context, KEY_LISTEN_APPS, set.joinToString("|"))
    }

    // 搜索（按应用名/包名过滤）+ 排序：已选中的置顶，其余按应用名。
    var query by remember { mutableStateOf("") }
    val visible = apps
        .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedWith(compareBy({ it.pkg !in selected }, { it.label }))

    PageScaffold(
        title = stringResource(R.string.express_pick_app_title),
        isBlurEnabled = isBlurEnabled,
    ) {
        item {
            Text(
                if (selected.isEmpty()) stringResource(R.string.express_pick_none)
                else stringResource(R.string.express_pick_app_summary, selected.size),
                modifier = Modifier.padding(16.dp, 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        item(key = "search") {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.express_pick_search),
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        items(visible, key = { it.pkg }) { app ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    selected = if (app.pkg in selected) selected - app.pkg else selected + app.pkg
                    persist(selected)
                }.padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = app.icon
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, color = MiuixTheme.colorScheme.onSurface)
                    Text(app.pkg, fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Checkbox(
                    state = ToggleableState(app.pkg in selected),
                    onClick = {
                        selected = if (app.pkg in selected) selected - app.pkg else selected + app.pkg
                        persist(selected)
                    },
                )
            }
        }
    }
}

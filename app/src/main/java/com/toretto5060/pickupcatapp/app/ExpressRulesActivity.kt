package com.toretto5060.pickupcatapp.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.express.ExpressConst.KEY_CUSTOM_RULES
import com.toretto5060.pickupcatapp.express.ExpressParse
import com.toretto5060.pickupcatapp.express.ExpressStore
import com.toretto5060.pickupcatapp.express.ParseRule
import com.toretto5060.pickupcatapp.express.buildTemplateRule
import com.toretto5060.pickupcatapp.ui.theme.AppTheme
import com.toretto5060.pickupcatapp.ui.util.PageScaffold
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 短信模板与解析规则：列出内置规则与"我的规则"（可删除），并提供"贴文本 → 纠错 → 保存"：
 * 贴一条短信即解析出取件码/快递/地址/柜号，四个字段都可手动改（纠错），保存时用纠正后的值 +
 * 原文锚点生成一条模板规则——同一快递、同一模板的短信以后按同样方式提取。
 */
class ExpressRulesActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val blur = AppSettings.load(this).isBlurEnabled
        setContent {
            AppTheme { RulesPage(isBlurEnabled = blur) }
        }
    }
}

@Composable
private fun RulesPage(isBlurEnabled: Boolean) {
    val context = LocalContext.current
    var sample by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<com.toretto5060.pickupcatapp.express.ParsedPickup?>(null) }
    // 纠错字段：解析出来之后可手动改，保存时用这些值。
    var code by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var cabinet by remember { mutableStateOf("") }
    var customs by remember { mutableStateOf(ExpressStore.loadCustomRules(context)) }

    LaunchedEffect(sample) {
        val p = sample.takeIf { it.isNotBlank() }?.let { ExpressStore.parseForCapture(context, it) }
        parsed = p
        code = p?.code.orEmpty()
        company = p?.company.orEmpty()
        address = p?.address.orEmpty()
        cabinet = p?.cabinet.orEmpty()
    }

    fun saveRule() {
        if (parsed == null) return
        val rule = buildTemplateRule(sample, code, company, address, cabinet)
        val next = customs + rule
        customs = next
        ExpressStore.setSetting(context, KEY_CUSTOM_RULES, JSONArray(next.map { it.toJson() }).toString())
    }

    fun deleteRule(id: String) {
        val next = customs.filter { it.id != id }
        customs = next
        ExpressStore.setSetting(context, KEY_CUSTOM_RULES, JSONArray(next.map { it.toJson() }).toString())
    }

    PageScaffold(title = stringResource(R.string.express_rules_title), isBlurEnabled = isBlurEnabled) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    TextField(
                        value = sample,
                        onValueChange = { sample = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.express_rules_test_hint),
                        useLabelAsPlaceholder = true,
                    )
                    parsed?.let {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextField(value = code, onValueChange = { code = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.express_test_code),
                                useLabelAsPlaceholder = true)
                            TextField(value = company, onValueChange = { company = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.express_test_company),
                                useLabelAsPlaceholder = true)
                            TextField(value = address, onValueChange = { address = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.express_test_address),
                                useLabelAsPlaceholder = true)
                            TextField(value = cabinet, onValueChange = { cabinet = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.express_test_cabinet),
                                useLabelAsPlaceholder = true)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                text = stringResource(R.string.express_rules_save),
                                enabled = code.isNotBlank() && address.isNotBlank(),
                                onClick = { saveRule() },
                            )
                        }
                    } ?: run {
                        if (sample.isNotBlank()) {
                            Text(stringResource(R.string.express_test_none),
                                Modifier.padding(top = 10.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.express_rules_builtin), Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("builtin_sms", color = MiuixTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.express_rules_builtin), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        item {
            Text(stringResource(R.string.express_rules_custom), Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (customs.isEmpty()) {
            item {
                Text(stringResource(R.string.express_rules_no_custom),
                    Modifier.padding(16.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        items(customs, key = { it.id }) { rule ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.id, color = MiuixTheme.colorScheme.onSurface)
                        Text(rule.codes.joinToString(" | ").ifEmpty { "-" }, fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    TextButton(
                        text = stringResource(R.string.express_rules_delete),
                        onClick = { deleteRule(rule.id) },
                    )
                }
            }
        }
    }
}



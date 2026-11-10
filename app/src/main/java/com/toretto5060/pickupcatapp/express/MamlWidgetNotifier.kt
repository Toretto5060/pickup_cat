package com.toretto5060.pickupcatapp.express

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.toretto5060.pickupcatapp.express.ExpressConst as C

/**
 * 通知 HyperOS 4 MAML 主题小部件更新。
 *
 * 主通道：把 [MamlWidgetSnapshot] 的完整快照放进广播 extras。主题的 <BroadcastBinder> 用
 * `<Variable name="pc_count" type="number" extra="count"/>` 等直接写进变量，
 * **完全不依赖跨进程 ContentProvider 查询**（真机上常被 Android 11+ 包可见性挡掉）。
 * 反编译 For设计师 APK 内的 com.miui.maml.data.BroadcastBinder.updateVariables 可见：
 * type=string → getStringExtra、type=number → getDoubleExtra、type=long → getLongExtra。
 *
 * 兜底通道：notifyChange + 主题 init/resume 的 MAML_WIDGET_REQUEST 反向请求仍然保留；
 * 主题已去掉 init/resume 里的 BinderCommand refresh，避免查询失败覆盖广播刚注入的变量。
 */
internal object MamlWidgetNotifier {

    /**
     * MAML 小部件由智能助理（For设计师）或桌面渲染，两个宿主各发一遍显式广播。
     * 不发隐式广播：extras 里含取件码，避免被任意第三方 App 监听；显式投递也能命中动态注册的
     * BroadcastBinder。
     */
    private val HOST_PACKAGES = arrayOf(
        "com.miui.personalassistant",
        "com.miui.home",
    )

    fun notifyDataChanged(context: Context?) {
        val ctx = context ?: return
        runCatching {
            ctx.contentResolver.notifyChange(Uri.parse(C.MAML_PENDING_URI), null)
        }
        val values = MamlWidgetSnapshot.snapshot(ctx)
        for (pkg in HOST_PACKAGES) {
            runCatching {
                val intent = Intent(C.MAML_CHANGED_ACTION)
                if (pkg != null) intent.setPackage(pkg)
                fillExtras(intent, values)
                ctx.sendBroadcast(intent)
            }
        }
    }

    /** 只发刷新信号、不重算数据（供 App 启动等场景轻量调用）。 */
    fun notifySnapshot(context: Context?) = notifyDataChanged(context)

    private fun fillExtras(intent: Intent, values: Map<String, Any?>) {
        for ((key, value) in values) {
            when (value) {
                is String -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value.toDouble())
                is Long -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value.toDouble())
                is Number -> intent.putExtra(key, value.toDouble())
                null -> intent.putExtra(key, "")
            }
        }
    }
}

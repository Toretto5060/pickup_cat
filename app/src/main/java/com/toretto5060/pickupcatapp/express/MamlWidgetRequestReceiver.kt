package com.toretto5060.pickupcatapp.express

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.toretto5060.pickupcatapp.express.ExpressConst as C

/**
 * MAML 主题小部件在 init / resume 时发来的「请求重推」广播。
 *
 * 主题用 <IntentCommand ... broadcast="true" package="com.toretto5060.pickupcatapp"/>
 * 显式投递，所以即使 App 进程已被回收，静态注册的接收器也会被系统拉起；收到后把最新
 * [MamlWidgetSnapshot] 通过 [MamlWidgetNotifier] 重新广播给两个 MAML 宿主。
 * 这是解析通知那一刻刷新广播被漏收时的兜底，用户每次回到桌面都会看到正确数据。
 */
class MamlWidgetRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != C.MAML_REQUEST_ACTION) return
        MamlWidgetNotifier.notifyDataChanged(context.applicationContext)
    }
}

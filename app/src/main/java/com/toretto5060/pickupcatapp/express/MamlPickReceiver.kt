package com.toretto5060.pickupcatapp.express

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 玻璃主题小部件「我已取件」的接收器。
 *
 * MAML 的 <IntentCommand broadcast="true"> 会走 sendBroadcast（见
 * com.miui.maml.ActionCommand$IntentCommand 构造里 Boolean.parseBoolean(getAttribute("broadcast"))
 * → IntentType.Broadcast → doPerformBroadcastSafe）。因此本接收器静态注册且 exported=true，
 * 由 MAML 宿主跨 UID 显式投递，App 进程即便被回收也会被拉起；收到后直接按行 id 标记已取，
 * **不启动任何 Activity**，没有 task 切换、没有闪屏、不会误开列表页。
 */
class MamlPickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val extras = intent.extras
        // MAML 的 <Extra type="long"> 可能以 Long / Double(number 变量) / String 落地，全部兼容。
        val direct = when (val e = extras?.get(EXTRA_ROW_ID)) {
            is Long -> e
            is Int -> e.toLong()
            is Double -> e.toLong()
            is Float -> e.toLong()
            is String -> e.toLongOrNull() ?: -1L
            else -> -1L
        }
        // 兜底：Extra 完全没落地时，按按钮行号取当前待取列表同序行的真实 id。
        val rowId = if (direct > 0) direct else resolveByIndex(context, extras?.get(EXTRA_ROW_INDEX))
        if (rowId > 0) {
            ExpressStore.markPickedCode(context, rowId)
            ExpressWidgetProvider().updateAll(context)
        }
    }

    private fun resolveByIndex(context: Context, raw: Any?): Long {
        val idx = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: -1
            else -> -1
        }
        if (idx <= 0) return -1L
        return runCatching {
            ExpressStore.queryAll(context, false).getOrNull(idx - 1)?.id ?: -1L
        }.getOrDefault(-1L)
    }

    companion object {
        const val ACTION = "com.toretto5060.pickupcatapp.MAML_PICK"
        const val EXTRA_ROW_ID = "row_id"
        const val EXTRA_ROW_INDEX = "row_index"
    }
}

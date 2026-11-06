package com.toretto5060.pickupcatapp.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.toretto5060.pickupcatapp.express.ExpressStore
import com.toretto5060.pickupcatapp.express.ExpressWidgetProvider

/**
 * 小部件列表项点击的透明中转页。
 *
 * ListView 的 item 点击只能走 setPendingIntentTemplate + fillInIntent，一个模板只能指向一种
 * PendingIntent；用 Activity 模板（而不是 Broadcast → 再 startActivity，会踩 Android 10+ 后台
 * 启动 Activity 限制）在同一个入口里分发「打开列表」和「逐码已取」两种动作，处理完立即 finish。
 * 主题为系统全透明、noHistory、不进最近任务，用户看不到这一跳。
 */
class ExpressWidgetClickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(ExpressWidgetProvider.EXTRA_CLICK)) {
            ExpressWidgetProvider.CLICK_PICK -> {
                val rowId = intent.getLongExtra(ExpressWidgetProvider.EXTRA_ROW_ID, -1L)
                if (rowId >= 0) {
                    ExpressStore.markPickedCode(this, rowId)
                    ExpressWidgetProvider().updateAll(this)
                }
            }
            else -> startActivity(
                Intent(this, ExpressListActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        }
        finish()
    }
}

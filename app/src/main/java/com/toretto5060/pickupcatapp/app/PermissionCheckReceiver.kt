package com.toretto5060.pickupcatapp.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 看门狗 / 即时唤起 / 开机 / 覆盖安装 / 用户解锁：拉起进程后主动重绑监听服务并续上下一次闹钟。 */
internal class PermissionCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PermissionMonitor.onCheck(context)
        // 撤销运行时权限（应用列表 / POST_NOTIFICATIONS）会 kill 进程，FGS 可能要等系统指数退避才重启；
        // 进程一被广播拉起就强制按最新权限状态发/撤提醒，不等 FGS 的定时检查。
        PermissionMonitor.checkNow(context)
    }
}

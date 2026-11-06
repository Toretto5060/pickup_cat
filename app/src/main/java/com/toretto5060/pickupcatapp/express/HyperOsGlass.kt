package com.toretto5060.pickupcatapp.express

import android.content.Context
import android.os.Build
import com.toretto5060.pickupcatapp.app.AppSettings

/**
 * 澎湃 OS4「柔光玻璃」小部件背景的能力检测与模式判定。
 *
 * 柔光玻璃是**桌面（Launcher）渲染的材质**，没有提供给第三方标准 AppWidget 的公开 SDK：
 * 系统按背景层渲染玻璃，App 能做的是把背板让出去——根布局用小米规范固定 id
 * `@android:id/background`、背景保持极淡半透明（规范 7.1 要求不能全透明），不再自绘渐变。
 * 因此这里只做「设备是否可能提供该材质」的判断 + 用户可强制切换；真正是否呈现玻璃由桌面决定。
 * 非 HyperOS4 / 检测不到时自动回退自绘玻璃（用户要求的默认兜底）。
 */
internal object HyperOsGlass {
    const val MODE_AUTO = "Auto"
    const val MODE_SYSTEM = "System"
    const val MODE_CUSTOM = "Custom"

    @Volatile
    private var detected: Boolean? = null

    /** 当前这张小部件是否使用系统背板（自绘玻璃层 GONE）。 */
    fun shouldUseSystemGlass(context: Context): Boolean =
        when (AppSettings.load(context).widgetBackground) {
            MODE_SYSTEM -> true
            MODE_CUSTOM -> false
            else -> isSoftGlassAvailable()
        }

    /** 检测结果为进程内缓存；用户想强行试可在设置里选「系统柔光玻璃（强制）」。 */
    fun isSoftGlassAvailable(): Boolean = detected ?: detect().also { detected = it }

    private fun detect(): Boolean {
        val isXiaomi = listOf(Build.BRAND, Build.MANUFACTURER).any {
            it.equals("Xiaomi", ignoreCase = true) ||
                it.equals("Redmi", ignoreCase = true) ||
                it.equals("POCO", ignoreCase = true)
        }
        if (!isXiaomi) return false

        // 小米澎湃 OS 版本属性（adb shell getprop ro.mi.os.version.name / .code）。
        // 用 getprop 而不是反射 SystemProperties.get：后者是 hidden API，targetSdk 37 上会被限制。
        val name = getProp("ro.mi.os.version.name").ifBlank { getProp("ro.mi.os.version.code") }
        // 属性形如 "OS4.0.1.0.UNBCNXM"；取到的数字若不像大版本号（例如某些 ROM 写 816.x），
        // 视为无效走下面的 Android 大版本兜底，避免误判成 OS4+。
        val major = Regex("\\d+").find(name)?.value?.toIntOrNull()?.takeIf { it in 1..9 }
        if (major != null) return major >= 4
        // 属性读不到时按 Android 大版本兜底（HyperOS 4 基于 Android 16/17）。
        // 不用 Build.VERSION_CODES.BAKLAVA：该常量在部分 compileSdk 组合下不可见。
        return Build.VERSION.SDK_INT >= 36
    }

    private fun getProp(key: String): String = runCatching {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        // onUpdate 跑在广播主线程，给 getprop 加超时避免极端情况下卡住刷新。
        if (!process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            ""
        } else {
            text.trim()
        }
    }.getOrDefault("")
}

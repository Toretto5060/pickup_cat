package com.toretto5060.pickupcatapp.express

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * 小部件「高度 → 可见条数 → 行高 / 列表可视高度」的唯一计算处。
 *
 * 桌面格子 dp 没有公开 API 可读，不同 ROM 差异很大（小米 HyperOS 的一格明显比 AOSP 标准的
 * 70dp 高）。之前用 70dp/格 + 只把行高套到 item 上，5×3 实际高度比读到的 dp 大，
 * 结果首屏露出了第 4 条。现在改成两层保证：
 *  1. [heightDp] 优先取**当前实例**尺寸 `maxOf(minHeight, maxHeight)`（离散档位 resize 后
 *     min==max==当前，能跟随拖拽改高度）；`OPTION_APPWIDGET_SIZES` 的最大值只代表可选档位上限、
 *     不随 resize 变化，仅作极低版本兜底；格子换算 80dp、[MAX_CELLS] = 3；
 *  2. Provider 用 [listHeightFor] 把 **ListView 本身的可视高度** 也锁成 cells 行，
 *     即使桌面报告的高度不准 / 实际窗口更高，也只会有留白，绝不会多露出一条。
 * 行高按 widgetId 存 SharedPreferences，供低版本 RemoteViewsService 的 Factory 读取。
 */
internal object ExpressWidgetSizing {
    const val MAX_CELLS = 3

    private const val PREFS = "express_widget_sizing"
    private const val KEY_ROW_HEIGHT = "row_height_"
    private const val CELL_DP = 80f
    private const val DEFAULT_HEIGHT_DP = 240f
    /** 布局里除列表外的固定占用：paddingTop 10 + 标题区 40 + paddingBottom 16 = 66，与 express_widget.xml 一致。 */
    const val CHROME_DP = 66f
    // 行高下限按可见格数动态：3 格 58dp（比内容 53dp 高 5dp，内容离白卡边约 2.5dp、不贴底）；
    // 2 格 56dp（略大于内容，紧凑不溢出）；避免 2 格被 3 格行高撑成只显 1 条。1 格同 2 格逻辑。
    private fun minRowFor(cells: Int): Float = if (cells >= 3) 58f else 56f
    private const val MAX_ROW_DP = 120f

    fun visibleRows(heightDp: Float): Int =
        (heightDp / CELL_DP).roundToInt().coerceIn(1, MAX_CELLS)

    /** 纯函数：行高按可用高度 / 格数反推（只影响视觉舒展度）；下限随格数动态（见 [minRowFor]）。 */
    internal fun rowHeightFor(heightDp: Float, cells: Int): Float =
        ((heightDp - CHROME_DP) / cells).coerceIn(minRowFor(cells), MAX_ROW_DP)

    private fun defaultRowDp(): Float =
        rowHeightFor(DEFAULT_HEIGHT_DP, visibleRows(DEFAULT_HEIGHT_DP))

    fun rowHeightDp(context: Context, widgetId: Int): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_ROW_HEIGHT + widgetId, defaultRowDp())

    fun refresh(context: Context, widgetId: Int, options: Bundle?) {
        val height = heightDp(options)
        val row = rowHeightFor(height, visibleRows(height))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat(KEY_ROW_HEIGHT + widgetId, row)
        }
    }

    fun clear(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_ROW_HEIGHT + widgetId)
        }
    }

    /** 取桌面报告的**当前实例**高度（用于跟随 resize），而不是可选档位的上限。
     *  离散档位（HyperOS）在 onAppWidgetOptionsChanged 时 min==max==当前高度，优先用它；
     *  这样拖拽改高度后列表可视高度会跟着收缩/放大，最后一行不会被裁。
     *  SIZES 的最大值只代表「能选的最大档」，resize 后不变，绝不能用它当当前高度。 */
    internal fun heightDp(options: Bundle?): Float {
        if (options == null) return DEFAULT_HEIGHT_DP
        val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val current = maxOf(min, max).takeIf { it > 0 }
        if (current != null) return current.toFloat()
        // 极低版本（无 min/max）才退回 SIZES 的最大档
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) return sizes.maxOf { it.height }
        }
        return DEFAULT_HEIGHT_DP
    }
}

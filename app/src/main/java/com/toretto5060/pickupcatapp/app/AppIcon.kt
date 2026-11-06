package com.toretto5060.pickupcatapp.app

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/** 应用图标转 Bitmap（原项目 FpPickActivity 里的同名工具函数，独立 App 自带一份）。 */
internal fun appIcon(context: Context, pkg: String): Bitmap? = runCatching {
    context.packageManager.getApplicationIcon(pkg).toBitmap(120, 120)
}.getOrNull()

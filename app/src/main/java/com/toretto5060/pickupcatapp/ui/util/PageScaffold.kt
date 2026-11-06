package com.toretto5060.pickupcatapp.ui.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import com.toretto5060.pickupcatapp.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页等二级页面的统一外框（精简版：不依赖模块里的模糊 backdrop / 滚动渐显工具）。
 * 保留与原项目一致的调用签名，页面代码可以直接复制过来用。
 */
@Composable
fun PageScaffold(
    title: String,
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        popupHost = { },
        floatingActionButton = floatingActionButton,
        topBar = {
            TopAppBar(
                title = title,
                color = MiuixTheme.colorScheme.surface,
                navigationIcon = { if (onBack != null) PageBackButton(onBack) },
                actions = { actions() },
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val padding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
        )
        if (onRefresh == null) {
            PageList(padding, content)
        } else {
            var refreshing by remember { mutableStateOf(false) }
            PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = { onRefresh(); refreshing = false },
            ) {
                PageList(padding, content)
            }
        }
    }
}

@Composable
private fun PageList(
    contentPadding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        overscrollEffect = null,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun PageBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        val layoutDirection = LocalLayoutDirection.current
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = stringResource(R.string.settings_back),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

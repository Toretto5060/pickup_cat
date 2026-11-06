package com.toretto5060.pickupcatapp.app

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toretto5060.pickupcatapp.R
import com.toretto5060.pickupcatapp.express.ExpressConst
import com.toretto5060.pickupcatapp.express.ExpressGroup
import com.toretto5060.pickupcatapp.express.ExpressRow
import com.toretto5060.pickupcatapp.express.ExpressStore
import com.toretto5060.pickupcatapp.express.ExpressWidgetProvider
import com.toretto5060.pickupcatapp.express.brandColorArgb
import com.toretto5060.pickupcatapp.express.formatReceivedAt
import com.toretto5060.pickupcatapp.ui.theme.AppTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 取件码页，严格按设计稿还原**内容区**（状态栏与底部导航不做）。
 *
 * 内容区自上而下：大标题「取件码」+ 右上「N 件待取」、「分布在 M 个取件点」、胶囊
 * 「待取件/已取件」tab，然后每个取件点（地址 + 冻结批次）一张白卡片——站点徽标 + 站点名
 * （与「全部取件」同行垂直居中）+ 下方一行：待取件显示「N 件待取」、已取件多码显示
 * 「xx:xx 全部取出」/ 单码显示「xx:xx 取出」；卡片内逐码：meta 小字、大字加粗取件码
 * （与「我已取件」同行垂直居中）。
 * **单码卡片**时 meta 行拼到上述那行后面，码上方不再重复一行。
 * 已取的码只置灰、不划线，整组取完才一起挪到「已取件」tab 并冻结该批次。
 *
 * 数据由 provider 驱动：ContentObserver 让解析入库/标记已取实时反映；两页数据常驻，滑动不重新查库。
 * 标题与胶囊 tab 固定在顶部；**下拉刷新只作用于 tab 下方的列表**；左右滑动（或点击 tab）
 * 在待取件/已取件之间带动画切换（HorizontalPager + 胶囊颜色过渡）。
 */
class ExpressListActivity : ComponentActivity() {
    private val permissionState = mutableStateOf(
        PermissionState(
            appListGranted = true,
            notificationAccessGranted = false,
            autoStart = AutoStartState.UNKNOWN,
            batteryUnrestricted = true,
            postNotificationsGranted = true,
        ),
    )
    // 只防止同一次页面生命周期里 onResume 反复自动弹系统框，不阻止用户点提示条再次请求。
    private var autoRequested = false
    // 首次进入时先弹通知权限框，回调后再自动请求应用列表权限，避免两个系统框打架。
    private var autoAppListPending = false
    // 本次请求是否来自用户点击提示条：自动请求被拒后不主动跳应用信息页。
    private var manualRequest = false
    private var lastCaptureEnabled = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        // 独立于通知监听服务的后台轮询：开机/覆盖安装由 receiver 重新排程。
        PermissionMonitor.start(this)
        // 第一次进入程序时先请求「发送通知」的系统授权框（POST_NOTIFICATIONS）。
        if (!PermissionGuard.postNotificationsGranted(this) && !PermissionGuard.postNotificationsAsked(this)) {
            PermissionGuard.markPostNotificationsAsked(this)
            autoAppListPending = true
            PermissionGuard.requestPostNotifications(this)
        }
        setContent {
            AppTheme {
                ExpressListPage(
                    permission = permissionState.value,
                    onPermissionAction = { kind ->
                        if (kind == PermissionKind.APP_LIST) {
                            // 点提示条一律先请求系统权限框；只有回调判定「不再询问」才跳应用信息页。
                            manualRequest = true
                            PermissionGuard.requestAppList(this)
                        } else {
                            PermissionGuard.openSettingsFor(this, kind)
                        }
                    },
                )
            }
        }
    }

    /** 首次进入、从系统设置返回都重新检测；应用列表权限缺失时自动弹一次系统授权框。 */
    override fun onResume() {
        super.onResume()
        refreshPermissions()
        maybeAutoRequestAppList()
    }

    private fun maybeAutoRequestAppList() {
        if (autoRequested || autoAppListPending) return
        if (!permissionState.value.appListGranted) {
            autoRequested = true
            manualRequest = false
            PermissionGuard.requestAppList(this)
        }
    }

    /** 刷新指示灯/提示条/系统通知；权限从缺失变为齐备时主动重绑监听服务。 */
    private fun refreshPermissions() {
        val state = PermissionGuard.state(this)
        permissionState.value = state
        if (state.captureEnabled && !lastCaptureEnabled) PermissionGuard.ensureCapture(this)
        lastCaptureEnabled = state.captureEnabled
        PermissionMonitor.onCheck(this)
        // 从系统设置返回 / 冷启动立即按最新权限状态下发或撤销提醒，不等后台 5 秒轮询。
        PermissionMonitor.checkNow(this)
    }

    // 厂商适配文档用 onRequestPermissionsResult；Activity Result API 等价但需三处重写
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PermissionGuard.REQ_POST_NOTIFICATIONS -> {
                autoAppListPending = false
                refreshPermissions()
                maybeAutoRequestAppList()
            }
            PermissionGuard.REQ_APP_LIST -> {
                refreshPermissions()
                val fromUser = manualRequest
                manualRequest = false
                // 仅用户主动点提示条、且系统判定不会再弹框（拒绝且不再询问）时才跳应用信息页。
                if (fromUser && PermissionGuard.shouldOpenAppDetails(this)) {
                    PermissionGuard.openAppDetails(this)
                }
            }
        }
    }
}

@Composable
private fun ExpressListPage(
    permission: PermissionState,
    onPermissionAction: (PermissionKind) -> Unit,
) {
    val context = LocalContext.current
    // 0 = 待取件，1 = 已取件；tab 由 pager 当前页驱动，点击胶囊用动画滚过去。
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val tab = pagerState.currentPage
    // 两页数据常驻：左右滑动只切页面，不重新查库；写入方统一 reload 两页。
    var pendingGroups by remember { mutableStateOf(emptyList<ExpressGroup>()) }
    var doneGroups by remember { mutableStateOf(emptyList<ExpressGroup>()) }

    fun reload() {
        pendingGroups = ExpressStore.queryGroupsForTab(context, pickedTab = false)
        doneGroups = ExpressStore.queryGroupsForTab(context, pickedTab = true)
    }
    LaunchedEffect(Unit) { reload() }

    // 取件码表任何变化（新通知解析入库、标记已取、删除）都实时重载本页。
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reload() }
        }
        context.contentResolver.registerContentObserver(Uri.parse(ExpressConst.ENTRIES_URI), true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val pendingCount = pendingGroups.sumOf { g -> g.rows.count { it.picked == 0 } }
    val doneCount = doneGroups.sumOf { g -> g.rows.size }
    val pageBg = if (isSystemInDarkTheme()) Color(0xFF101114) else Color(0xFFF6F7F9)

    Box(
        Modifier.fillMaxSize().background(pageBg).statusBarsPadding().navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 标题 + 胶囊 tab 固定在顶部：不随列表滚动，也不参与下拉刷新。
            PageHeader(
                tab = tab,
                points = pendingGroups.size,
                pendingCount = pendingCount,
                doneCount = doneCount,
                parsingEnabled = permission.captureEnabled,
                onTab = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
                onSettings = { context.startActivity(Intent(context, ExpressActivity::class.java)) },
                modifier = Modifier.padding(top = 22.dp),
            )
            // 4 项权限 + 通知权限任一缺失时用非模态提示条引导，不自绘「是否允许」弹窗。
            val missing = permission.missing
            if (missing.isNotEmpty()) {
                PermissionBanner(missing, onPermissionAction)
            }
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                overscrollEffect = null,
                modifier = Modifier.weight(1f),
            ) { page ->
                // 下拉刷新只作用于 tab 下方这一页的列表。
                val groups = if (page == 0) pendingGroups else doneGroups
                var refreshing by remember(page) { mutableStateOf(false) }
                PullToRefresh(
                    isRefreshing = refreshing,
                    onRefresh = { reload(); refreshing = false },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 28.dp),
                    ) {
                        if (groups.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 72.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        stringResource(
                                            if (page == 1) R.string.express_list_empty_picked
                                            else R.string.express_list_empty,
                                        ),
                                        fontSize = 15.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                        items(groups, key = { it.key }) { g ->
                            PickupGroupCard(context, g, pickedTab = page == 1, onChanged = { reload() })
                        }
                    }
                }
            }
        }
    }
}

/** 权限未开启时的引导条：只负责引导，授权一律走系统权限框 / 系统设置页。 */
@Composable
private fun PermissionBanner(
    missing: List<PermissionKind>,
    onAction: (PermissionKind) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        missing.forEachIndexed { i, kind ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            PermissionRow(stringResource(bannerLabel(kind))) { onAction(kind) }
        }
        Spacer(Modifier.height(10.dp))
    }
}

private fun bannerLabel(kind: PermissionKind): Int = when (kind) {
    PermissionKind.APP_LIST -> R.string.permission_banner_app_list
    PermissionKind.NOTIFICATION_ACCESS -> R.string.permission_banner_notification
    PermissionKind.AUTO_START -> R.string.permission_banner_auto_start
    PermissionKind.BATTERY -> R.string.permission_banner_battery
    PermissionKind.POST_NOTIFICATIONS -> R.string.permission_banner_post_notifications
}

@Composable
private fun PermissionRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** 设计稿的标题区：大标题 + 右上件数（设置按钮旁带解析状态指示灯）+ 副标题 + 胶囊 tab。 */
@Composable
private fun PageHeader(
    tab: Int,
    points: Int,
    pendingCount: Int,
    doneCount: Int,
    parsingEnabled: Boolean,
    onTab: (Int) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 双权限齐备 = 启用解析（绿）；任一缺失 = 停用（红）。与 ExpressListenService 的 gate 同一判定。
    val indicatorColor by animateColorAsState(
        targetValue = if (parsingEnabled) Color(0xFF34C759) else Color(0xFFFF3B30),
    )
    val indicatorLabel = stringResource(
        if (parsingEnabled) R.string.permission_indicator_on else R.string.permission_indicator_off,
    )
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.express_list_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 设置按钮在「N 件待取」上方，和件数一起右对齐；左侧是红/绿解析状态灯。
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(50))
                            .background(indicatorColor)
                            .semantics { contentDescription = indicatorLabel },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.10f))
                            .clickable(onClick = onSettings),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (tab == 0) R.string.express_pending_count else R.string.express_done_count,
                        if (tab == 0) pendingCount else doneCount,
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        Text(
            if (tab == 0) stringResource(R.string.express_points, points) else "",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 5.dp),
        )
        Spacer(Modifier.height(18.dp))
        PillTabs(tab, onTab)
        Spacer(Modifier.height(14.dp))
    }
}

/** 胶囊 tab：选中蓝色填充，未选浅灰。 */
@Composable
private fun PillTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(stringResource(R.string.express_tab_pending), stringResource(R.string.express_tab_picked))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            // 左右滑动 / 点击切换时，胶囊底色与文字色平滑过渡，而不是瞬间硬切。
            val bg by animateColorAsState(
                targetValue = if (active) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.09f),
            )
            val fg by animateColorAsState(
                targetValue = if (active) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
            }
        }
    }
}

/** 一个取件点的卡片：站点头 + 取件码条目列表（已取的只置灰）。 */
@Composable
private fun PickupGroupCard(
    context: Context,
    g: ExpressGroup,
    pickedTab: Boolean,
    onChanged: () -> Unit,
) {
    val pending = g.rows.count { it.picked == 0 }
    // 单码卡片（两个 tab 一致）：柜号/到站/来源图标拼到地址下方的件数或「全部取出」后面。
    val single = g.rows.size == 1
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(18.dp)) {
            // 地址 +「N 件待取」是一个整体，与 logo 垂直居中；只有「全部取件」与地址那一行居中。
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteLogo(g.companies)
                // 地址块整体上移一点，让地址文字与 logo 顶部视觉对齐；按钮与地址仍在内层
                // Row 里垂直居中、随之一同上移，件数间距也保持不变。
                Column(Modifier.weight(1f).padding(start = 12.dp).offset(y = (-3).dp)) {
                    // 固定这一行的最小高度：有没有「全部取件」时地址→件数的间距都一样；
                    // 同时给地址与按钮之间留出间距，超长地址不会贴着按钮。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.heightIn(min = 36.dp),
                    ) {
                        // 显示「全部取件」时地址可能被挤压：放不下就公告式持续横向滚动。
                        BasicText(
                            text = g.address,
                            style = TextStyle(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.weight(1f).basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately,
                                initialDelayMillis = 0,
                            ),
                        )
                        // 用户要求：一个地址只剩（或只有）一个待取码时不需要一键取件。
                        if (!pickedTab && pending > 1) {
                            Pill(
                                text = stringResource(R.string.express_picked_all),
                                style = PillStyle.Filled,
                                onClick = {
                                    ExpressStore.markPicked(context, g.address, g.batch)
                                    ExpressWidgetProvider().updateAll(context)
                                    onChanged()
                                },
                            )
                        }
                    }
                    // 收紧与地址的行距（库 Text 自带行高，默认间隙过大）。
                    val tight = Modifier.offset(y = (-3).dp)
                    // 待取件 =「N 件待取」；已取件多码 =「xx:xx 全部取出」、单码 =「xx:xx 取出」。
                    // 时间统一取该批次最晚的 picked_at。
                    val headline = if (pickedTab) {
                        stringResource(
                            if (single) R.string.express_picked_at_single else R.string.express_all_picked_at,
                            formatReceivedAt(g.pickedAt),
                        )
                    } else {
                        stringResource(R.string.express_pending_count, pending)
                    }
                    if (single) {
                        // 单码卡片：码上方不再重复 meta，整行仍拼在站点 logo 后面。
                        // 待取件把件数放最前；已取件把「xx:xx 取出」拼到来源图标后面。
                        MetaLine(
                            row = g.rows.first(),
                            prefix = if (pickedTab) "" else headline,
                            suffix = if (pickedTab) headline else "",
                            modifier = tight,
                        )
                    } else {
                        Text(
                            headline,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = tight,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            g.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp)
                            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.10f)),
                    )
                }
                PickupRow(context, row, pickedTab, onChanged, showMeta = !single)
            }
            if (pickedTab) {
                // 删除是破坏性操作：细分隔线 + 留白把它与上面的取件码彻底分开，按钮降为灰色轻量样式。
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.10f)),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DeleteButton {
                        ExpressStore.deleteGroup(context, g.address, g.batch)
                        onChanged()
                    }
                }
            }
        }
    }
}

/** 单个取件码：meta 小字在上（单件快递时并进卡片头部，此处隐藏），大字码与胶囊同行居中。 */
@Composable
private fun PickupRow(
    context: Context,
    row: ExpressRow,
    pickedTab: Boolean,
    onChanged: () -> Unit,
    showMeta: Boolean = true,
) {
    val picked = row.picked == 1
    Column(Modifier.fillMaxWidth()) {
        if (showMeta) MetaLine(row)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // BasicText + 显式 TextStyle：绕开库 Text 对 fontWeight 的转发，Black 权重一定生效。
            BasicText(
                text = row.code,
                style = TextStyle(
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = if (picked) MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.42f)
                    else MiuixTheme.colorScheme.onSurface,
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            when {
                !picked && !pickedTab -> Pill(
                    text = stringResource(R.string.express_picked),
                    style = PillStyle.Outline,
                    onClick = {
                        ExpressStore.markPickedCode(context, row.id)
                        ExpressWidgetProvider().updateAll(context)
                        onChanged()
                    },
                )
                picked -> Pill(
                    text = if (row.pickedAt > 0) stringResource(
                        R.string.express_picked_at, formatReceivedAt(row.pickedAt),
                    ) else stringResource(R.string.express_tab_picked),
                    style = PillStyle.Muted,
                )
            }
        }
    }
}

private enum class PillStyle { Filled, Outline, Muted }

/**
 * 取件码上方的 meta 小字行：`x号柜 · HH:mm 到站 [来源图标]`。
 * 单码卡片复用时：[prefix] 拼在最前（待取件「1 件待取」）、[suffix] 拼在**来源图标之后**
 * （已取件「09:20 取出」，用户要求放在来源图标后面、整行末尾）；整行仍在站点 logo 后面。
 * 「x号柜」始终 13sp Bold + `onSurface`（浅色下为黑色），其余保持灰色小字。
 */
@Composable
private fun MetaLine(
    row: ExpressRow,
    prefix: String = "",
    suffix: String = "",
    modifier: Modifier = Modifier,
) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val arrived = stringResource(R.string.express_meta_arrived, formatReceivedAt(row.receivedAt))
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix.isNotEmpty()) {
            Text(prefix, fontSize = 13.sp, color = summary, maxLines = 1)
            Text(" · ", fontSize = 13.sp, color = summary)
        }
        if (row.cabinet.isNotEmpty()) {
            Text(
                row.cabinet,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(" · ", fontSize = 13.sp, color = summary)
        }
        Text(
            arrived,
            fontSize = 13.sp,
            color = summary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(5.dp))
        SourceIcon(row)
        // 已取件单码：取出时间拼在来源图标之后（整行末尾），到站时间与图标保持原位。
        if (suffix.isNotEmpty()) {
            Text(" · ", fontSize = 13.sp, color = summary)
            Text(
                suffix,
                fontSize = 13.sp,
                color = summary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 已取件卡片底部的删除：灰色小字 + 删除图标，点击区做成胶囊，视觉上弱于主操作。 */
@Composable
private fun DeleteButton(onClick: () -> Unit) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = null,
            tint = summary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            stringResource(R.string.express_delete),
            fontSize = 13.sp,
            color = summary,
        )
    }
}

/** 设计稿的三种胶囊：淡蓝填充（全部取件）、蓝边白底（我已取件）、浅灰（已取时间）。纯文字。 */
@Composable
private fun Pill(text: String, style: PillStyle, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    val primary = MiuixTheme.colorScheme.primary
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val bg = when (style) {
        PillStyle.Filled -> primary.copy(alpha = 0.12f)
        PillStyle.Outline -> Color.Transparent
        PillStyle.Muted -> summary.copy(alpha = 0.08f)
    }
    val fg = if (style == PillStyle.Muted) summary else primary
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .then(if (style == PillStyle.Outline) Modifier.border(1.dp, primary, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/**
 * 站点徽标：同一地址只有一家快递时是单个品牌色方块；有多家时做**错位叠加**（后层偏右下、
 * 前层带卡片底色描边），一眼能看出这张卡片混了不同快递。
 */
@Composable
private fun SiteLogo(companies: List<String>) {
    val list = companies.filter { it.isNotEmpty() }.distinct()
    if (list.size < 2) {
        val company = list.firstOrNull().orEmpty()
        val tint = Color(brandColorArgb(company))
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                company.firstOrNull()?.toString().orEmpty(),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
        return
    }
    val outline = if (isSystemInDarkTheme()) Color(0xFF1E1E22) else Color.White
    Box(Modifier.size(42.dp)) {
        val back = Color(brandColorArgb(list[1]))
        Box(
            Modifier.offset(x = 8.dp, y = 8.dp).size(30.dp)
                .clip(RoundedCornerShape(8.dp)).background(back.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(list[1].firstOrNull()?.toString().orEmpty(),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = back)
        }
        val front = Color(brandColorArgb(list[0]))
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                .background(front.copy(alpha = 0.16f))
                .border(2.dp, outline, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(list[0].firstOrNull()?.toString().orEmpty(),
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = front)
        }
    }
}

/**
 * meta 行尾的来源图标：通知来源取该 App 的真实应用图标（13dp，与 13sp 小字适配），
 * 短信来源取系统信息 App 图标，取不到时兜底为短信矢量图标。
 */
@Composable
private fun SourceIcon(row: ExpressRow) {
    val context = LocalContext.current
    val pkg = row.originPkg.ifEmpty {
        if (row.origin == ExpressConst.ORIGIN_SMS) "com.android.mms" else ""
    }
    val bmp = remember(pkg) {
        if (pkg.isEmpty()) null else runCatching {
            val d = context.packageManager.getApplicationIcon(pkg)
            if (d is BitmapDrawable) d.bitmap.asImageBitmap()
            else {
                val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 64, 64)
                d.draw(Canvas(b))
                b.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = row.source,
            modifier = Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)),
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.Sms,
            contentDescription = row.source,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(13.dp),
        )
    }
}

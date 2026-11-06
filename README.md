# 取件喵 (PickupCat)

「快递取件码」功能独立出来的普通 Android App。

- 包名：`com.toretto5060.pickupcatapp`
- 首页：取件码列表（待取件 / 已取件，左右滑动切换）
- 解析开关（自动）：「获取应用列表」「通知使用权」「自启动」「电量无限制」4 项**都开启才解析取件码**，
  任一缺失直接丢弃通知（每条通知实时判断，无需重启 App；权限齐备时首页主动 `requestRebind` 重绑监听服务）；
  首页设置按钮旁指示灯：红灯 = 已暂停，绿灯 = 已启用
- 后台：**进程守护看门狗**——`KeepAliveService` 是静默前台服务（`IMPORTANCE_MIN` 常驻通知 +
  `START_STICKY`），尽量让「一键清理」不杀进程（进程没死 = 0 秒）；`PermissionMonitor` 亮屏每 1 秒
  用 `setExact`、熄屏每 60 秒用 `setExactAndAllowWhileIdle` 排链式闹钟（开机 / 覆盖安装 / 解锁自动续期），
  划掉最近任务或监听服务断开时再排一个 0.3 秒 `setAlarmClock` 最高优先级闹钟（不会被 Doze / 厂商对齐推迟）；
  进程一旦被清掉由系统侧闹钟在 1 秒内拉起，并主动 `requestRebind` 恢复通知监听。权限状态每 5 秒检查一次
  （FGS 内 Handler 定时 + 心跳兜底，进 App / 从系统设置返回立即 `checkNow` 强制检查），状态变化才刷新提醒；
  权限异常通知为常驻（齐备时自动撤销），避免被划掉后签名未变就不再补发。
  提醒不能只依赖后台广播：「自启动被关」本身就会让广播/闹钟被系统限制，FGS 定时检查是独立的一路；
  要求系统「自启动」「电量无限制」，精确闹钟权限被关时自动回退普通 allow-while-idle 闹钟。
- 捕获：系统通知使用权（`NotificationListenerService`），**不需要 root，也不需要 LSPosed / Xposed**
- 补录：服务重新连上（守护拉起 / 解锁重绑）后 0.8 秒扫描通知栏**仍挂着**的通知，按勾选的解析 App + 规则解析，
  未取件列表已有相同取件码就跳过、没有才入库并刷小部件，弥补进程没运行期间漏收的通知；
  实时通知与补录共用同一套权限总闸 / 白名单 / 解析逻辑。只能补回仍挂在通知栏的通知，
  已被清除或被新通知覆盖的历史通知拿不到。
- 桌面小部件：高度最多 3 格、宽度不限；可见条数与高度匹配（3 格 3 条 / 2 格 2 条），
  剩余条目在小部件内上下滑动查看；顶部显示「N 件待取」，每行有「已取」按钮。
  背景默认自绘玻璃；检测到澎湃 OS4 时改用系统柔光玻璃（背板由桌面渲染），
  也可在「设置 → 小部件背景」里强制切换
- 设置：解析通知 App、通知监听权限、短信模板与解析规则、语言、主题模式、小部件背景

## 构建

需要 JDK 17、Android SDK 37。

```bash
# 开发
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest

# 发布（沿用源项目约定：-P 注入版本号，输出带 App 名与版本的 APK）
./gradlew :app:assembleRelease \
  -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
  -PmcVersionName=0.0.1 -PmcVersionCode=1000
```

- `-PmcVersionName` 默认 `0.0.1`，`-PmcVersionCode` 默认 `1`，另支持 `-PmcVersionSuffix`（如 nightly）。
- release 产物：`app/build/outputs/apk/release/PickupCat-v0.0.1.apk`。
- 没有 `keystore.properties` 时 release 回退 debug 签名（可直接安装）；正式发布请另配 `signingConfigs.release`。

## 安装后必做

1. 进入首页会检测两项权限，并以**系统弹窗**申请「获取应用列表」
   （`com.android.permission.GET_INSTALLED_APPS`，MIUI/HyperOS、ColorOS、OriginOS、Harmony 等
   国内厂商的运行时权限）。被拒后首页顶部出现提示条，点它仍会先弹系统授权框；
   只有在系统里勾过「拒绝且不再询问」时，才会跳「应用信息 → 权限管理」手动开启。
2. 通知使用权没有运行时权限框，首页提示条点一下会跳系统「通知使用权」设置；HyperOS 还需在应用详情里
   开启「自启动」，否则监听服务无法绑定。
3. 可选：在「解析通知 App」里限定要解析的 App（不勾选 = 解析全部，短信始终解析）；未授权时点击会先弹系统授权框。
   另在设置里开启「自启动」与「电量无限制」，保证后台监听不被系统杀掉；权限异常时通知中心会提醒。
   守护服务是静默前台服务（状态栏有一条可关闭渠道的常驻通知），一般不会被「一键清理」杀掉；
   万一被杀，亮屏约 1 秒内（划掉卡片时约 0.3 秒）自动拉起并重新绑定监听服务，不用解锁或手动打开 App，
   代价是亮屏期间每秒一次轻量唤醒；熄屏自动降频到 60 秒。若在系统应用详情点了「强行停止」，
   系统会取消全部闹钟并屏蔽广播，只能手动打开一次 App 恢复。
4. 长按桌面 → 小部件 → 添加「取件喵」。升级本版本后建议**删除旧小部件重新添加**，
   让桌面重新读取新的尺寸配置与列表布局。

## 桌面小部件适配说明

> **当前实现（2026-11-06 最新）**：**渲染完全不读桌面报告尺寸**。行高固定 58dp 写在
> `express_widget_row.xml`，`ListView` 用 `match_parent` + 滚动自然适配 resize（不再重写
> `onAppWidgetOptionsChanged`，不再有 `setViewLayoutHeight` / 动态行高 / SharedPreferences 换算，
> `ExpressWidgetSizing.kt` 已删除）——HyperOS 的尺寸回调（newOptions / getAppWidgetOptions /
> SIZES）都不可靠，依赖它们算行高在 5×2↔5×3 来回调整时必然漂移、无法恢复。
> 行卡片背景：浅色 `drawable/express_widget_row_bg.xml` `#99FFFFFF`、深色
> `drawable-night/express_widget_row_bg.xml` `#991C1C1E`（均 60% 不透明，14dp 圆角）。
> 两张卡片**之间的距离**由 `express_widget.xml` 里 `widget_list` 的 `dividerHeight`（当前 10dp）控制，
> 与 `row_root` 行高是两个独立参数，别混淆。
> 以下小节为**历史迭代快照**，仅保留作背景参考。

- **尺寸**：`express_widget_info.xml` 的 `minHeight=210dp` / `maxResizeHeight=210dp`（3 格 × 70dp）、
  `minResizeHeight=140dp`（2 格），`resizeMode="horizontal|vertical"`，**不设 `maxResizeWidth`**。
  `maxResizeHeight` 从 Android 12(API 31) 起生效；低版本桌面会忽略它，代码里由
  `ExpressWidgetSizing` 把可见行数 clamp 到 3 兜底（不能真正阻止拖拽到更高）。
- **可见条数（2026-11-06 校准）**：`ExpressWidgetSizing.heightDp()` 优先取桌面报告的
  `OPTION_APPWIDGET_SIZES`，否则取 `max(OPTION_APPWIDGET_MIN_HEIGHT, OPTION_APPWIDGET_MAX_HEIGHT)`——
  **不能只读 minHeight**，那往往只是 `widget_info` 里声明的值，比小米桌面 5×3 的实际窗口矮，
  会算出行高偏小、露出第 4 条。按 80dp/格换算格数并 clamp 到 3，
  行高 =「(高度 − 50dp chrome) / 格数」，按 widgetId 存 SharedPreferences。
  除给每行套 `setViewLayoutHeight`，还用 `setViewLayoutHeight` 把 **ListView 自身的可视高度**
  锁成 `cells × rowHeight`：桌面给的窗口再高也只会有留白，绝不会多露出一条；
  剩余条目仍在 ListView 里上下滑动查看。API 29/30 无 `setViewLayoutHeight`，退回 56dp 固定行高。
- **排版**：标题「N 件待取」15sp 粗体 / 30dp 高，左右 padding 18dp（避开 24dp 圆角）；
  取件码 19sp、公司 / 时间 / 地址 12sp、「已取」13sp，行基线 56dp。
- **滚动与点击**：RemoteViews 里只有 AdapterView 系列能滚动，所以从「固定行 + addView(≤5 行)」
  换成 `ListView`。Android 12+ 用官方推荐的 `RemoteViews.RemoteCollectionItems` 把所有条目
  作为集合快照随 RemoteViews 提交（桌面无需绑定 Service，也避开 API 35 起对 Intent 版
  `setRemoteAdapter` 的弃用）；Android 11 及以下保留 `RemoteViewsService` 兜底。
  条目不再截断为 5 条（上限 100 条，防 Binder 事务过大），点击走
  `setPendingIntentTemplate` + `setOnClickFillInIntent`，由透明中转页
  `ExpressWidgetClickActivity` 分发「打开列表 / 逐码已取」。
- **柔光玻璃**：HyperOS 4 的柔光玻璃是**桌面渲染的材质，没有提供给第三方标准 AppWidget 的公开 SDK**。
  能做的是把背板让给桌面——根布局用小米小部件规范的固定 id `@android:id/background`、
  背景只留 15% 半透明（规范 7.1 要求有背景色且不能全透明，同时兼顾桌面不叠玻璃时的可读性）、
  隐藏自绘玻璃层；
  `HyperOsGlass` 用 `getprop ro.mi.os.version.name` 检测澎湃 OS4，检测不到自动回退自绘玻璃。
  桌面是否真的叠加玻璃、以及旗舰芯片 / 静态壁纸等硬件门槛，需要真机确认；
  设置里也提供「系统柔光玻璃（强制）」和「自绘玻璃」两个可切换的兜底选项。
- 小米《小部件审核规范》写明「不支持在小部件内上下滑动」，那是**收录上架**的规范而非技术限制；
  本项目按需求实现了滑动列表，若要上架小米小部件中心需要另行评估。

## 与源项目的差异

- 删除了 `ExpressObserver`（`SmsProvider` 的 LSP hook）与所有 Xposed / libxposed 依赖、模块声明、`Xp` 日志。
- `ExpressProvider` 改为 `exported=false`，只允许同 UID 读写；authority 为 `com.toretto5060.pickupcatapp.express`。
- 「启用取件码」开关移除，捕获默认开启。
- 应用列表读取走厂商系统权限：Manifest 声明 `QUERY_ALL_PACKAGES` + `com.android.permission.GET_INSTALLED_APPS`，
  `PermissionGuard` 用 `requestPermissions` 弹系统授权框；`ExpressAppPickActivity` 改用受管控的
  `getInstalledApplications()`，系统权限拒绝时列表为空（`queryIntentActivities` 不受管控会绕过开关）。
- 首页直接是列表，设置从首页右上角进入。
- 新增语言（默认 / 简体中文 / English）与主题模式（跟随系统 / 浅色 / 深色）切换，切换后重启任务栈到首页生效。
- 保留源项目发布约定：`-PmcVersionName/-PmcVersionCode` 注入版本号，release 产物 `PickupCat-v<versionName>.apk`；
  无 keystore 时回退 debug 签名（可直接安装）。
- 数据不迁移：新包名对应新的私有数据库，旧模块里的取件记录、通知授权、桌面小部件都需要重新配置。

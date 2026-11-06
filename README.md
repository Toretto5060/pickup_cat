# 取件喵 (PickupCat)

「快递取件码」功能独立出来的普通 Android App。

- 包名：`com.toretto5060.pickupcatapp`
- 首页：取件码列表（待取件 / 已取件，左右滑动切换）
- 捕获：系统通知使用权（`NotificationListenerService`），**不需要 root，也不需要 LSPosed / Xposed**
- 桌面小部件：待取件逐码列表 + 每行「已取」按钮
- 设置：解析通知 App、通知监听权限、短信模板与解析规则、语言、主题模式

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

1. 打开 App，点右上角设置按钮 →「通知监听权限」，在系统设置里授予通知使用权。
   HyperOS 还需在应用详情里开启「自启动」，否则监听服务无法绑定。
2. 可选：在「解析通知 App」里限定要解析的 App（不勾选 = 解析全部，短信始终解析）。
3. 长按桌面 → 小部件 → 添加「取件喵」。

## 与源项目的差异

- 删除了 `ExpressObserver`（`SmsProvider` 的 LSP hook）与所有 Xposed / libxposed 依赖、模块声明、`Xp` 日志。
- `ExpressProvider` 改为 `exported=false`，只允许同 UID 读写；authority 为 `com.toretto5060.pickupcatapp.express`。
- 「启用取件码」开关移除，捕获默认开启。
- 首页直接是列表，设置从首页右上角进入。
- 新增语言（默认 / 简体中文 / English）与主题模式（跟随系统 / 浅色 / 深色）切换，切换后重启任务栈到首页生效。
- 保留源项目发布约定：`-PmcVersionName/-PmcVersionCode` 注入版本号，release 产物 `PickupCat-v<versionName>.apk`；
  无 keystore 时回退 debug 签名（可直接安装）。
- 数据不迁移：新包名对应新的私有数据库，旧模块里的取件记录、通知授权、桌面小部件都需要重新配置。

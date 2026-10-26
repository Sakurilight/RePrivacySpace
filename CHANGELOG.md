# Changelog

## 0.1.0 (1000)

首个正式维护版，包名 `com.reprivacyspace.app`。

### 激活与隐藏（Android 15–17 适配）

- 原版在 Android 15+ 无法激活：`PackageManagerService#getInstallerPackageName` 已被移除，
  `ConfigServer.start()` 抛 `NoSuchMethodError` 导致整个模块不加载。
  现改为在 `IPackageManager$Stub#onTransact` 的 `TRANSACTION_getInstallerPackageName`
  上应答，并以 `IPackageManagerBase#getInstallerPackageName` 作为 Java 层兜底。
- 原版依赖的 `com.android.server.pm.AppsFilter` 在 Android 15 已被拆分。
  现 hook `ComputerEngine#shouldFilterApplication(...)`（Android 16/17 所有包查询的汇聚点），
  老 ROM 自动回退到 `AppsFilterBase/AppsFilterImpl/AppsFilter`。
- **HMA-OSS 共存**：HMA-OSS 用自带原生引擎 hook `AppsFilterImpl#shouldFilterApplication`
  和 `ComputerEngine#getInstallerPackageName`；本模块刻意避开这两个方法，二者不再互相覆盖。
- **system uid 豁免**：框架对 `uid < 10000`（system/root/shell）永不过滤，本模块同样跳过，
  修复了桌面/窗口管理器（如 `com.libremobileos.freeform`）看不到隐藏应用、点击图标无法启动的问题。
- 每个 hook 步骤独立 try/catch，任一失败不再导致整个模块失效。

### 功能

- 恢复 1.3.11 的「其他设置」：**快速启动**（音量+/音量-/电源）、**详细日志**。
- 新增 **隐藏桌面图标**（`activity-alias` + `setComponentEnabledSetting`），隐藏时自动开启快速启动作为安全网。
- 修复 **重启桌面 / 重启设置**：以 system 身份调用 `IActivityManager#forceStopPackage`，
  并在重启桌面后重新拉起 HOME。
- 修复 **点击隐藏应用无法启动**：优先由 system_server 解析并启动显式组件，客户端多级兜底。
- 配置可从 `/data/system/cn.geektang.privacyspace/`、`/data/system/com.re.privacyspace/` 自动迁移。

### 界面

- Material 3 风格：M3 色板（**哔哩哔哩粉 #FB7299**）、8/12/16dp 圆角、M3 字阶、扁平化顶栏。
- 修复全屏白色时状态栏图标不可见：根据主题设置浅色/深色系统栏图标。
- 应用名：中文「隐私空间」，其它语言「PrivacySpace」。
- 关于页重写：开发者 Sakurilight、源码仓库、原项目与 MIT 声明，均可点击跳转；移除 Telegram/Coolapk。

### 其它

- 移除已停服的 AppCenter 统计；包名与源码目录整体迁移至 `com.reprivacyspace.app`。
- 版本号改为 0.1.0（versionCode 1000）。

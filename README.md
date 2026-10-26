# RePrivacySpace

**PrivacySpace 的维护分支**：在 Android 15 / 16 / 17 上恢复可用，并与 HMA-OSS 共存。
包名 **`com.reprivacyspace.app`**，当前版本 **0.1.0 (versionCode 1000)**。

- 维护者：**Sakurilight** — <https://github.com/Sakurilight>
- 源码：<https://github.com/Sakurilight/RePrivacySpace>
- 上游：**PrivacySpace by GeekTR** — <https://github.com/GeekTR/PrivacySpace>
- 协议：**MIT**（本项目基于 MIT 协议二次开发）

> 本分支不是官方版本，请自行判断风险。

## 功能

- 全局隐藏应用列表（系统级可见性过滤，普通 App 看不到被隐藏的应用）；
- 白名单 / 致盲（黑名单）/ 关联应用 / 多用户配置；
- **快速启动**：亮屏时依次按 音量+、音量-、电源 打开本应用；
- **隐藏桌面图标**：隐藏后只能通过快捷启动、LSPosed 或 adb 打开；
- **详细日志**开关；
- 重启桌面 / 重启设置 / 重启手机管家 / 重启系统。

## 兼容性

| 项目 | 说明 |
| --- | --- |
| Android | 8.0–17（API 26–37） |
| Xposed | LSPosed（作用域需包含「系统框架」） |
| HMA-OSS | 可同时启用，互不覆盖 hook |
| 签名 | 独立签名，不能覆盖安装官方版本 |

技术细节见 [CHANGELOG.md](CHANGELOG.md)。

## License

MIT。原始版权归 GeekTR 所有；本分支的修改同样以 MIT 协议发布。

# url_display_dlna

一个面向 Android 的 DLNA 视频投屏控制器，主要用于把 CloudMusic2KTV 等项目提供的视频直链投送到局域网内的 DLNA MediaRenderer。应用本身不解析网页，也不负责视频转码。

## 功能

- 从输入框或 Android 分享菜单接收 `http://` / `https://` 视频直链；
- 通过 SSDP 搜索局域网内的 DLNA 播放设备；
- 投送、暂停/继续、拖动进度、停止并断开，以及在同一设备上换投其他视频；
- 可选“手机中转”，让播放设备从手机的局域网地址读取视频；
- 从系统文件选择器选择本地视频，并自动使用手机中转；
- 投屏期间显示独立的控制详情页，并在播放结束或断开后返回主页。

## 使用条件与限制

- Android 8.0（API 26）及以上；
- 手机和播放设备连接到同一局域网，且网络允许 SSDP 多播和设备访问手机的局域网地址；
- 直链必须是播放设备可以访问的媒体 URL。网页地址、登录页面、需要 Cookie 的链接和需要转码的格式不在支持范围内；
- 手机中转模式要求手机能访问源站，播放设备能访问手机的 Wi-Fi 地址。签名参数仍由手机请求源站，过期链接不会被刷新；
- 播放格式、解码能力和 Seek 行为由接收端决定；
- Android 13 及以上首次使用中转时可能请求通知权限。部分厂商的省电策略会限制锁屏后的网络服务，需要在系统设置中允许应用后台运行；
- 当前实机验证主要使用 OPPO/ColorOS，其他 Android 设备请按 [兼容性说明](docs/platform-compatibility.md) 理解支持边界。

## 安装

从 GitHub **Releases** 下载 APK，在手机上确认安装即可。测试版 APK 使用调试签名，系统可能要求允许该来源安装应用。每个版本的下载地址和校验值应以对应 Release 页面为准。

## 基本操作

1. 将手机和播放设备接入同一 Wi-Fi。
2. 在输入框粘贴完整视频直链，或从其他应用通过分享菜单发送给本应用；也可以选择“选择本地视频”。
3. 直链需要手机中转时勾选“手机中转”。本地视频无论复选框状态如何都会自动中转。
4. 点击“搜索 DLNA 设备”，选择设备后进入投屏详情页。
5. 在详情页暂停、继续、拖动进度或停止并断开。播放结束后应用会自动回到主页。

## 从源码构建

不需要 Android Studio。准备 JDK 17 和 Android SDK（至少安装 Android 35 platform），使用仓库内的 Gradle Wrapper：

```bash
./gradlew test lint assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat test lint assembleDebug
```

首次构建需要联网下载 Gradle 和 Maven 依赖；依赖已经缓存后可以使用 `--offline`。生成的测试 APK 位于：

`app/build/outputs/apk/debug/app-debug.apk`

更完整的 JDK、SDK、代理和故障排查说明见 [构建与验证](docs/build-and-test.md)。

## 发布 APK

仓库提供 GitHub Actions 工作流：推送 `v*` 标签后会运行测试并把 APK 附加到 GitHub Release；普通分支推送只生成构建产物。发布步骤和调试签名、正式签名的区别见 [发布说明](docs/releasing.md)。

## 开发者文档

- [构建与验证](docs/build-and-test.md)：本地命令行环境、ADB 和日志；
- [跨 Android 平台兼容性](docs/platform-compatibility.md)：已验证范围、厂商差异和网络前提；
- [本机 DLNA 模拟接收端](docs/local-testing.md)：Mock Renderer、mpv 和 Windows 防火墙；
- [DLNA 会话与协议记录](docs/dlna-cast-session.md)：抓包、GENA、SOAP 和历史调试记录。


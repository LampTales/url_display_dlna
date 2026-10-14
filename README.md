# url_display_dlna

独立 Android DLNA Digital Media Renderer 控制器。第一版不实现二维码配对，依赖手机已经完成的局域网配对/授权状态。

当前实现：

- 接收系统分享的纯文本 URL；
- 通过 SSDP 搜索 `MediaRenderer:1`；
- 读取设备描述并定位 `AVTransport:1`；
- 调用 `SetAVTransportURI` 和 `Play` 播放视频。

项目使用标准 DLNA/UPnP 控制链路。实地测试前仍需确认设备的 SOAP 参数、元数据要求、HTTP/HTTPS 可达性和编码兼容性。

## 构建

使用 Android Studio 打开本目录，或准备 JDK 17 和 Android SDK 后使用项目自带的 Gradle Wrapper：

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
```

以上 Java 路径适用于 Apple Silicon macOS 的 Homebrew 安装；其他环境请使用对应的 JDK 17 路径。Wrapper 固定使用 Gradle 8.7，无需另外安装全局 Gradle。

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。覆盖安装时执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 没有实机时的本机模拟器

`tools/mock_dlna_renderer.py` 是一个只依赖 Python 标准库的本机测试接收端。它会在 UDP 1900 响应
SSDP `M-SEARCH`，在 HTTP 上提供 `MediaRenderer:1`、`AVTransport:1`、`RenderingControl:1` 和
`ConnectionManager:1`，接受 UPnP 事件 `SUBSCRIBE`、回发初始 `NOTIFY`，并将视频 URL 交给工作区内的
mpv 播放。它记录最近的 SOAP 请求和播放状态，可通过 `/state` 检查。

工作区还包含 Windows x86_64 便携版 mpv，位于 `tools/mpv/mpv.exe`。它不需要写入系统目录或加入
`PATH`，后续可由模拟器通过 IPC 控制，用于实际拉取并播放手机提供的 HTTP 视频地址。检查版本：

```powershell
.\tools\mpv\mpv.exe --version
```

在工作区的 PowerShell 中启动（默认启用 mpv）：

```powershell
python .\tools\mock_dlna_renderer.py --advertise-ip 192.168.1.101
```

也可以只测试 DLNA 协议而不启动播放器：

```powershell
python .\tools\mock_dlna_renderer.py --advertise-ip 192.168.1.101 --no-mpv
```

`--advertise-ip` 必须是手机能够访问的本机局域网 IPv4 地址；不确定时可以省略，脚本会尝试自动选择。
看到 `Mock renderer ready` 后，在手机应用中点击“搜索 DLNA 设备”，应出现 `CloudMusic2KTV Mock Renderer`。
点击该设备后，模拟器会在收到 `SetAVTransportURI` 时启动 mpv 并加载视频，在收到 `Play`、`Pause`、`Stop`
和 `Seek` 时同步控制播放器；视频结束或切换视频后会继续发送 DLNA 状态事件。可在电脑浏览器打开
`http://192.168.1.101:8765/state` 查看最后收到的 URI、播放器进程、播放状态和请求记录。若手机搜不到，先确认 Windows 防火墙允许 Python 接收 UDP 1900 和 TCP 8765；防火墙规则
属于系统安全设置，需要手动决定是否添加。

OPPO 视频会根据接收端报告的状态决定后续动作。模拟器现在采用 Macast 的握手：收到
`SetAVTransportURI` 后立即让 mpv 加载视频，但对手机报告 `PAUSED_PLAYBACK`，等待手机发出
`Play` 后再报告 `PLAYING`。这能避免手机把一次正常投屏误判为本地播放/Seek 流程；之后手机发来的
`Pause`、`Seek`、`Stop` 仍会同步到 mpv。

完整的设备发现、SCPD、GENA、SOAP、状态轮询和 Seek 交互记录见
[docs/dlna-cast-session.md](docs/dlna-cast-session.md)。

应用 ID 为 `com.cloudmusic2ktv.urldisplaydlna`，启动类为 `com.cloudmusic2ktv.dlna.MainActivity`。Manifest 使用完整类名，避免将 Gradle namespace 误当成 Java 包名。

## 本地验证

```bash
./gradlew testDebugUnitTest lintDebug
```

单元测试检查带命名空间的 UPnP 描述、缺少 AVTransport 的设备，以及向本机模拟端点按顺序发送 SetAVTransportURI / Play。Lint 检查 Manifest 中的 Activity 是否存在。这些检查不能替代真实 Android 启动和局域网投屏测试。

## 手机日志

在手机开发者选项中开启 USB 调试，连接 Mac，在手机上同意该电脑的调试授权，然后检查：

```bash
adb devices -l
```

设备状态应为 `device`；`unauthorized` 表示尚未在手机上授权。重新触发桌面打开或分享打开后的闪退，再导出崩溃日志（命令不会清空日志）：

```bash
adb logcat -b crash -d -v threadtime > "$HOME/Desktop/url-display-dlna-crash.txt"
```

设备发现、投送等操作的诊断日志可另外导出：

```bash
adb logcat -d -v threadtime UrlDisplayDlna:V AndroidRuntime:E '*:S' > "$HOME/Desktop/url-display-dlna-runtime.txt"
```

如连接了多个设备，在 `adb` 后面添加 `-s 设备序列号`。发送日志时同时提供手机型号、Android / ColorOS 版本、APK 版本及复现步骤。崩溃缓冲区可能包含其他应用的记录，发送前可只保留本应用的异常段落；日志中的签名视频地址也应遮盖。

可用下面两条命令分别打开桌面入口和模拟接收分享（示例地址只用于检查界面启动）：

```bash
adb shell am start -W -n com.cloudmusic2ktv.urldisplaydlna/com.cloudmusic2ktv.dlna.MainActivity
adb shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://example.com/video.mp4 -n com.cloudmusic2ktv.urldisplaydlna/com.cloudmusic2ktv.dlna.MainActivity
```

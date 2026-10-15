# url_display_dlna

独立 Android DLNA Digital Media Renderer 控制器。第一版不实现二维码配对，依赖手机已经完成的局域网配对/授权状态。

当前实现：

- 接收系统分享的纯文本 URL；
- 通过 SSDP 搜索 `MediaRenderer:1`；
- 读取设备描述并定位 `AVTransport:1`；
- 调用 `SetAVTransportURI` 和 `Play` 播放视频。
- 查询实际播放状态、时长和进度；支持暂停/继续、拖动进度、停止并断开；
- 向当前设备换投输入框中的链接，支持直接再次接收系统分享；
- 可选手机 HTTP 中转，让接收端访问手机局域网地址；支持 Range、HEAD 和前台服务；
- 可从 Android 系统文件选择器选择本地视频；本地文件通过手机中转以局域网 HTTP 地址投送；
- 控制请求串行执行，前台约每秒轮询一次，退到后台停止轮询，回到前台恢复。

0.4.1 使用进程内会话保存当前设备：旋转屏幕和前后台切换后可以继续控制；分享链接会
复用已有页面并通过 `onNewIntent()` 更新输入框，不会为每次分享创建新的任务页面；系统杀进程
或强制停止 App 后需重新搜索并投屏。点击“停止并断开”会发送 Stop；设备不可达时可以
选择“仅断开控制”，此时无法保证屏幕已经停止播放，但会关闭手机中转。输入链接需要是
HTTP/HTTPS 视频直链；网页解析、登录 Cookie 转发和视频转码尚未实现。

## 手机中转模式

输入框下勾选“手机中转”，再选择设备或点击“投送输入框中的视频”。未勾选时，屏幕直接
访问原始链接；勾选后，屏幕访问 `http://手机Wi-Fi地址:随机端口/video/随机标识`，手机
通过当前 Wi-Fi 从原链接下载并边读边转发。模式选择会保存，在下一次投送时生效；当前
播放状态旁会显示实际使用的模式。

- 支持 GET、HEAD、Range/If-Range、206/416 和最多 5 次 HTTP/HTTPS 重定向。
  原链接的签名参数保留在手机请求中，重定向也由手机处理。
- 不做转码或整片缓存；单个请求使用 64 KiB 缓冲区，最多同时处理 4 个请求。
  源站不支持 Range 时会如实转发 200，不能保证此类源站支持拖动。
- 中转使用带通知的前台服务和唤醒锁，便于切后台、熄屏后继续提供视频。
  Android 13+ 首次使用时会申请通知权限；拒绝通知仍可中转，但需要回到 App 停止。
- ColorOS/Android 的省电策略可能在锁屏时限制新的入站连接。勾选中转后，App 会提供
  “允许后台运行（减少锁屏中断）”入口；需要时在系统设置中允许本 App 忽略电池优化。
  这是用户主动选择的耗电权衡，不能保证所有厂商固件都允许锁屏接收新连接。
- 默认勾选“中转时保持此页亮屏”，仅在正在中转且此页前台可见时生效，可手动关闭。
  它不阻止手动锁屏；本机锁屏后新连接曾超时，后续解锁时也出现过局域网 HTTP 超时，
  尚不能确定唯一原因，当前不承诺锁屏投屏稳定性。
- 暂停或播放结束时保留中转，方便后续控制；使用“停止并断开”或通知栏“停止投屏”结束。
  切回直链、换投成功、仅断开控制会关闭旧地址；Wi-Fi 丢失或手机地址改变会终止中转，需重新投屏。

手机中转要求屏幕能访问手机局域网地址，且手机的 Wi-Fi 能访问源站。它会增加手机耗电及
局域网传输量，不能修复不兼容的视频编码，也不会让过期的签名链接重新有效。没有自动
下载缓存、蜂窝网络回退、自动刷新签名或网络恢复后自动续投。本功能尚需纯 K 实地验证。

### 本地视频投送

点击“选择本地视频”后，从 Android 系统文件选择器选取可读取的视频文件，再选择 DLNA
设备即可。本地文件不会整片复制或转码；手机使用 `content://` 文件描述符按需读取，并
为接收端提供 `GET`、`HEAD` 和 Range 请求，因此本地视频始终需要勾选手机中转。某些
云盘或特殊 DocumentProvider 如果不提供可随机读取的文件描述符则无法投送，文件编码仍
必须被接收端支持。

项目使用标准 DLNA/UPnP 控制链路。实地测试前仍需确认设备的 SOAP 参数、元数据要求、HTTP/HTTPS 可达性和编码兼容性。

## 构建

使用 Android Studio 打开本目录，或准备 JDK 17 和 Android SDK 后使用项目自带的 Gradle Wrapper：

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
```

以上 Java 路径适用于 Apple Silicon macOS 的 Homebrew 安装；其他环境请使用对应的 JDK 17 路径。
Wrapper 固定使用 Gradle 8.14.3，无需另外安装全局 Gradle。本机曾出现 transform
缓存目录移动失败；升级后仍遇到一次，处理失败的缓存目录后构建与离线构建均已通过。

本工作区 Windows 命令行构建：

```powershell
. ..\android-tools\activate.ps1
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

激活脚本将 JDK、SDK 和 `GRADLE_USER_HOME` 都指向工作区内的 `android-tools`。
依赖已缓存后可以添加 `--offline`。若首次下载需要本机代理，可在当前终端设置：

```powershell
$env:GRADLE_OPTS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897'
```

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

OPPO 视频会根据接收端报告的状态决定后续动作。模拟器在收到 `SetAVTransportURI` 后立即让
mpv 加载视频，并根据 mpv 的实际事件报告 `PAUSED_PLAYBACK` 或 `PLAYING`；手机发来的
`Play`、`Pause`、`Seek`、`Stop` 也会同步到 mpv。Windows 多网卡环境下，
`--advertise-ip` 同时决定设备描述中的地址和 SSDP 组播接收网卡，应填写手机可访问的
Wi-Fi IPv4 地址。

完整的设备发现、SCPD、GENA、SOAP、状态轮询和 Seek 交互记录见
[docs/dlna-cast-session.md](docs/dlna-cast-session.md)。

应用 ID 为 `com.cloudmusic2ktv.urldisplaydlna`，启动类为 `com.cloudmusic2ktv.dlna.MainActivity`。Manifest 使用完整类名，避免将 Gradle namespace 误当成 Java 包名。

## 本地验证

```bash
./gradlew testDebugUnitTest lintDebug
```

单元测试检查带命名空间的 UPnP 描述、缺少 AVTransport 的设备、带签名链接及媒体元数据、
控制命令与状态解析、SOAP Fault 和输入校验。Lint 检查包括 Manifest 中的 Activity 是否存在。
这些检查不能替代真实 Android 启动和局域网投屏测试。

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

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

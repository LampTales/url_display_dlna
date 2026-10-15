# 构建与验证

本文面向维护者，补充 README 中不适合放在主页面的环境细节。

## 命令行环境

项目使用 Gradle Wrapper 8.14.3 和 Android Gradle Plugin 8.5.2，建议使用 JDK 17。Android Studio 不是必需的，但 Android SDK 必须包含：

- Android SDK Platform 35；
- 对应的 Android SDK Build-Tools；
- Platform-Tools（需要使用 ADB 时）。

设置 `JAVA_HOME` 和 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`）后，在仓库根目录运行：

```powershell
.\gradlew.bat test lint assembleDebug
```

Linux/macOS：

```bash
./gradlew test lint assembleDebug
```

只运行单元测试和静态检查：

```bash
./gradlew testDebugUnitTest lintDebug
```

首次构建会下载 Gradle、Android 插件和 Maven 依赖。需要代理时由当前终端设置 Java/Gradle 的 HTTP(S) 代理；依赖缓存完成后可加 `--offline` 验证离线构建。不要把个人 SDK 路径、代理地址或 `local.properties` 提交到仓库。

## 安装到设备

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

设备状态应为 `device`。`unauthorized` 表示需要在手机上确认 USB 调试授权；`offline` 通常需要重新连接 ADB 或重新授权。

启动应用：

```bash
adb shell am start -W -n com.cloudmusic2ktv.urldisplaydlna/com.cloudmusic2ktv.dlna.MainActivity
```

模拟分享链接：

```bash
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT https://example.com/video.mp4 \
  -n com.cloudmusic2ktv.urldisplaydlna/com.cloudmusic2ktv.dlna.MainActivity
```

## 日志

崩溃日志：

```bash
adb logcat -b crash -d -v threadtime > url-display-dlna-crash.txt
```

应用运行日志：

```bash
adb logcat -d -v threadtime UrlDisplayDlna:V AndroidRuntime:E '*:S' > url-display-dlna-runtime.txt
```

分享或日志中可能包含带签名的视频地址，公开前应遮盖查询参数。

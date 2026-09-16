# url_display_dlna

独立 Android DLNA Digital Media Renderer 控制器。第一版不实现二维码配对，依赖手机已经完成的局域网配对/授权状态。

当前实现：

- 接收系统分享的纯文本 URL；
- 通过 SSDP 搜索 `MediaRenderer:1`；
- 读取设备描述并定位 `AVTransport:1`；
- 调用 `SetAVTransportURI` 和 `Play` 播放视频。

项目使用标准 DLNA/UPnP 控制链路。实地测试前仍需确认设备的 SOAP 参数、元数据要求、HTTP/HTTPS 可达性和编码兼容性。

## 构建

使用 Android Studio 打开本目录，或在安装 Android SDK/Gradle 后运行：

```bash
./gradlew assembleDebug
```

当前工作区未包含 Gradle wrapper，因此尚未在本机编译验证。

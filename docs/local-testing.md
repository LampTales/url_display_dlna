# 本机 DLNA 模拟接收端

这部分只面向维护者，不是日常用户使用应用的必要步骤。

`tools/mock_dlna_renderer.py` 是只依赖 Python 标准库的本机测试接收端。它响应 UDP 1900 的 SSDP 搜索，在 HTTP 上提供设备描述、`AVTransport`、`RenderingControl` 和 `ConnectionManager`，记录 SOAP/GENA 请求，并可把收到的媒体地址交给 mpv。

## 启动

工作区中的 Windows 便携版 mpv（如果已下载）位于 `tools/mpv/mpv.exe`，该目录被 `.gitignore` 排除。没有播放器时可用 `--no-mpv` 只验证协议。

```powershell
python .\tools\mock_dlna_renderer.py --advertise-ip 192.168.1.101
```

或：

```powershell
python .\tools\mock_dlna_renderer.py --advertise-ip 192.168.1.101 --no-mpv
```

`--advertise-ip` 必须填写手机能访问的本机 Wi-Fi IPv4 地址。启动后手机搜索设备，应看到 `CloudMusic2KTV Mock Renderer`。状态可在浏览器访问：

```text
http://192.168.1.101:8765/state
```

Windows 防火墙需要允许 Python 接收 UDP 1900 和 TCP 8765。若电脑有多个网卡、VPN 或虚拟网卡，应确认 SSDP 使用的是手机可访问的 Wi-Fi 网卡。

## 覆盖范围

Mock 会处理 `SetAVTransportURI`、`Play`、`Pause`、`Stop`、`Seek` 和状态查询，并同步播放状态。它适合验证应用自身的控制顺序、手机中转 URL、播放结束和设备切换；它不能证明真实纯 K 接收端对所有视频编码、元数据或网络策略都兼容。

官方 Macast 可以作为第二个接收端进行交叉验证。详细 SOAP、GENA、抓包和历史问题记录见 [DLNA 会话记录](dlna-cast-session.md)。

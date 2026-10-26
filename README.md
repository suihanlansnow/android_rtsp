# 视流 (ShiLiu Camera)

把一台旧 Android 手机变成局域网 RTSP 摄像头。原生 Kotlin 实现，无 FFmpeg、无第三方流媒体 SDK——从 Camera2 采集、OpenGL 旋转裁剪、MediaCodec H.264 编码，到 RTSP / RTP 协议打包全部自研，全部源码不到 4000 行。

## 功能特性

- **RTSP 拉流服务**（端口 8554）：支持 RTP over TCP（interleaved）与 UDP 两种传输，可选 Basic 鉴权，多客户端共享同一路码流
- **RTP/UDP 广播**（端口 9998）：无握手通路，客户端发一个探测包即可入会，适合快速接入 ffmpeg / 自研播放器
- **分辨率自适应**：分辨率与帧率列表来自相机输出尺寸 ∩ SurfaceTexture 通道 ∩ H.264 硬件编码器能力三方求交集，不写死档位
- **多镜头支持**：自动识别后置/前置/外接镜头并按视场角命名（超广角/广角/长焦），单镜头设备自动隐藏切换入口
- **横竖屏同源渲染**：预览与码流共用一套 GL 管线，方向、比例、镜像永远一致；切换仅重建编码器，百毫秒级完成
- **前台服务保活**：息屏、切后台、锁屏不断流，常驻通知展示推流地址
- **每客户端异步发送队列**：慢客户端只丢自己的帧，不会阻塞编码线程拖累其他观众
- **实时运行统计**：实测帧率、实际码率、RTSP/RTP 客户端数、累计丢包

## 工作原理

```
Camera2 ──► SurfaceTexture ──► GL 管线（旋转 / 镜像 / 裁剪）
                                   │
                                   ├──► 预览 EGLSurface（TextureView）
                                   └──► 编码 EGLSurface（MediaCodec InputSurface）
                                                │
                                          H.264 Annex-B
                                                │
                        ┌───────────────────────┴───────────────────────┐
                        │                                               │
                 RTSP Server :8554                              RTP Broadcaster :9998
             （TCP interleaved / UDP）                        （无握手 UDP 广播）
```

预览与编码走同一条 GL 管线，旋转通过纹理坐标实现而非顶点变换，因此方向与构图天然对齐。

## 环境要求

| 项目 | 版本要求 |
| --- | --- |
| Android Studio | Koala (2024.1.1) 或更新 |
| JDK | 17 |
| Android SDK | compileSdk 34，构建工具随 AGP 自动安装 |
| Gradle | 8.7（项目自带 wrapper，首次构建自动下载） |
| 运行设备 | Android 8.0（API 26）+，需支持 Camera2 与 H.264 硬件编码 |

技术栈：Kotlin 2.0.21 · AGP 8.5.2 · Jetpack Compose（BOM 2024.09.00）· Material3

## 编译构建

### 方式一：Android Studio（推荐）

1. `git clone` 本仓库后用 Android Studio 打开根目录
2. 等待 Gradle Sync 完成（依赖仅 Compose 与 AndroidX，无需 NDK）
3. 连接手机，直接 Run ▶ 安装启动

### 方式二：命令行

```bash
# Debug 包
./gradlew assembleDebug
# 产物位于 app/build/outputs/apk/debug/app-debug.apk

# 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 下使用 `gradlew.bat`，其余相同。

> 项目 `gradle-wrapper.properties` 使用腾讯云镜像下载 Gradle 8.7，国内网络无需额外配置；如需切换回官方源，将 `distributionUrl` 改为 `https://services.gradle.org/distributions/gradle-8.7-bin.zip`。

## 使用说明

### 1. 启动推流

首次启动会依次申请**相机权限**和**通知权限**（Android 13+，拒绝通知权限不影响推流，仅常驻通知不可见）。选择镜头与分辨率后点击「开始推流」，页面即展示两个拉流地址：

### 2. RTSP 拉流

```bash
# 未设密码
ffplay -rtsp_transport tcp rtsp://<手机IP>:8554/live/cam0

# 设置了推流密码（Basic 鉴权，用户名固定 admin）
ffplay -rtsp_transport tcp rtsp://admin:<密码>@<手机IP>:8554/live/cam0
```

VLC、PotPlayer 等标准播放器均可接入，建议优先使用 TCP 传输。多客户端可同时拉流。

### 3. RTP/UDP 广播拉流

广播通路无握手，接收端需先向手机 9998 端口发送一个探测包完成注册，再通过 SDP 解码（PT 96 = H.264）：

1. 保存以下内容为 `stream.sdp`（端口 6000 与接收端口一致）：

```
v=0
o=- 0 0 IN IP4 127.0.0.1
s=shiliu
c=IN IP4 127.0.0.1
t=0 0
m=video 6000 RTP/AVP 96
a=rtpmap:96 H264/90000
```

2. 先发探测包注册，再拉流：

```bash
echo -n "hi" | nc -u -w1 <手机IP> 9998
ffmpeg -protocol_whitelist file,udp,rtp -i stream.sdp -frames:v 1 out.png
```

> 注意：15 秒内没有任何发往手机 9998 端口数据包的客户端会被清理，长时间拉流请周期性发送心跳包。

## 可配置项

均在 [StreamController.kt](app/src/main/java/com/shiliu/stream/StreamController.kt) 常量区：

| 项 | 位置 | 默认值 |
| --- | --- | --- |
| RTSP 端口 | `RTSP_PORT` | 8554 |
| RTP 广播端口 | `RTP_BROADCAST_PORT` | 9998 |
| 目标码率系数 | `bitrateFor()` 中的 `0.07` | width × height × fps × 0.07，钳制在 0.8–12 Mbps |
| 关键帧间隔 | [H264Encoder.kt](app/src/main/java/com/shiliu/stream/media/H264Encoder.kt) `KEY_I_FRAME_INTERVAL` | 1 秒 |
| 发送队列上限 | [RtspServer.kt](app/src/main/java/com/shiliu/stream/rtsp/RtspServer.kt) | 1.5 MB / 512 包 |
| 广播客户端超时 | [RtpBroadcaster.kt](app/src/main/java/com/shiliu/stream/media/RtpBroadcaster.kt) `CLIENT_TIMEOUT_NS` | 15 秒 |

## 项目结构

```
app/src/main/java/com/shiliu/stream/
├── MainActivity.kt              单 Activity 入口、锁屏可见、生命周期
├── StreamController.kt          进程级单例，状态机与推流链路编排
├── StreamService.kt             前台服务、WakeLock、常驻通知
├── camera/
│   ├── CameraCatalog.kt         相机/编码器能力探测、镜头命名
│   └── CameraSession.kt         Camera2 设备与会话（单 HandlerThread）
├── media/
│   ├── RotatingGlPipeline.kt    EGL + OES 纹理 + 旋转/镜像/裁剪
│   ├── H264Encoder.kt           MediaCodec 编码、低延迟参数（CBR/无 B 帧）
│   ├── H264RtpPacketizer.kt     NAL → RTP（单包 / FU-A 分片）
│   ├── RtpBroadcaster.kt        UDP 广播与客户端管理
│   └── H264Utils.kt             Annex-B 拆分、profile-level-id
├── rtsp/RtspServer.kt           RTSP/1.0 服务端与每客户端发送队列
├── ui/                          Compose 界面与设计令牌
└── NetUtils.kt                  局域网 IPv4 选择（优先 wlan/eth）
```

深入的技术细节（方向与镜像推导、RTP 打包、发送队列设计、踩坑实录）见 [docs/视流-技术文档.md](docs/视流-技术文档.md)。

## 已知限制

- 仅推视频，不采集音频
- UDP 广播为明文无鉴权，仅建议在可信局域网使用
- 推流中不允许切换镜头或分辨率（避免重建链路导致断流）
- 只收不发的播放器（如 ffplay 配合 UDP 广播）会被 15 秒超时清理

## 实测参考

华为 MRD-AL00（Android 9，720×1560），1080P30 档位：编码输出约 27 fps、实际码率 3.8–4.4 Mbps（CBR 目标 4.35 Mbps）；双客户端 TCP 并发拉流正常；息屏 20 秒后仍可正常拉流。

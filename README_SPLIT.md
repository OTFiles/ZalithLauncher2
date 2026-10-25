# Zalith Launcher 2 — 服务端 / 客户端 拆分版

把 [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)（Android 上的 Minecraft: Java 版启动器）
拆成 **服务端** 与 **客户端** 两个 APK：让性能较弱的手机只装客户端，也能流畅玩 MC。

- **服务端**（`com.movtery.zalithlauncher.server`）：在性能好的设备上真正运行 Minecraft，
  把画面用硬件 H.264 编码后串流出去，同时接收远程输入。
  **默认没有桌面图标**，通过 `ServerSettingsActivity` 设置。
- **客户端**（`com.movtery.zalithlauncher.client`）：只做「连接 → 解码 → 显示 → 回传输入」，
  APK 约 8.7 MB，同一个包同时支持 **arm64-v8a** 与 **armeabi-v7a**。

基于 upstream **2.7.1**（`d2b6545`），该版本仍保留下载游戏等完整功能。

---

## 1. 构建产物

GitHub Actions（`.github/workflows/build_split.yml`）产出两个 APK：

| APK | 包名 | ABI | 说明 |
| --- | --- | --- | --- |
| `ZalithServer-2.7.1-arm64-v8a.apk` | `com.movtery.zalithlauncher.server` | arm64-v8a | 服务端，含完整 MC 运行环境 |
| `ZalithClient-2.7.1-arm-arm64.apk` | `com.movtery.zalithlauncher.client` | arm64-v8a + armeabi-v7a | 轻量客户端 |

需要服务端也支持 32 位时，手动触发 workflow 并把 `server_arch` 填成 `all`。

CI 会在上传前用 `aapt2` 校验：

- 两个包名正确
- 客户端同时包含 `arm64-v8a` 与 `armeabi-v7a`，且有启动图标
- 服务端默认**图标禁用**（`ServerIconAlias enabled=false`）
- 服务端导出 `…server.action.SETTINGS`、存在 `ServerBootReceiver` / `ServerService` / `RECORD_AUDIO`

本地构建（可选，需要 Android SDK + NDK 25.2.9519653）：

```bash
./gradlew :ZalithLauncher:assembleRelease -Darch=arm64   # 服务端
./gradlew :ZalithClient:assembleRelease                  # 客户端
```

---

## 2. 服务端

### 2.1 打开设置界面（没有桌面图标）

```bash
# 方式一：直接指定组件
adb shell am start -n com.movtery.zalithlauncher.server/com.movtery.zalithlauncher.server.ServerSettingsActivity

# 方式二：用约定的 action（其它 App 也能这样调起）
adb shell am start -a com.movtery.zalithlauncher.server.action.SETTINGS
```

设置界面里可以：

- 查看串流地址、在线客户端数、游戏状态、FPS / 码率
- 改端口（默认 `25580`）、访问口令、分辨率、码率、帧率
- 开关 **开机自启**、**保活**、**启动时自动拉起上次的游戏**
- 开关 **桌面图标**（`ServerIconAlias` 的启用状态，持久保存）
- 申请「所有文件访问权限」「忽略电池优化」「后台弹窗权限」「录制权限（音频）」

### 2.2 放置 Minecraft

服务端就是一个正常安装的 ZL2，游戏目录为：

```
Android/data/com.movtery.zalithlauncher.server/files/.minecraft/versions/<版本名>/
```

可以用原版 ZL2 下载/安装好版本后整个 `.minecraft` 拷过来（2.7.1 上游仍支持下载游戏与整合包）。

### 2.3 启动游戏

两种方式等价：

1. 设置界面点 **Launch game**
2. 客户端连上后点右侧 ☰ → **启动游戏**

服务端解包 JRE / LWJGL / JNA → 创建 H.264 编码器 → 把编码器的输入 Surface 直接交给
Minecraft 渲染（**零拷贝**，不需要屏幕录制）→ 采集到的访问单元分发给所有客户端。

### 2.4 保活

- `ServerService`：`START_STICKY` + `specialUse|dataSync` 前台服务
- `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`（可在设置里关闭）
- `ServerBootReceiver` 监听 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` /
  `MY_PACKAGE_REPLACED` / `QUICKBOOT_POWERON`
- `onTaskRemoved` 时主动重新拉起服务

> 内嵌 JVM 在一个进程里只能创建一次，所以 **游戏退出 / 停止后服务端会重启进程**，
> 并通过 `ServerSettings.pendingLaunch` 续跑，保证随时可以从客户端再次启动游戏。

---

## 3. 客户端

1. 输入服务端 IP 与端口（同一 Wi-Fi 下填服务端设置页显示的局域网地址），可选口令
2. 连接后即可看到画面

触摸操作（右侧 ☰ 切换）：

| 模式 | 操作 |
| --- | --- |
| **摇杆**（默认） | 左半屏拖动 = WASD；右半屏拖动 = 转视角 |
| **视角** | 整屏拖动 = 转视角 |
| **指针** | 手指位置即鼠标位置，适合点菜单 / 合成 |

底部虚拟按键：跳跃、潜行、打开背包、丢弃、快捷栏 1/2/3、ESC。
连物理键盘时按键会被映射成 GLFW 键码转发给游戏。

坐标全部以**比例**传输，因此客户端屏幕分辨率与服务端串流分辨率不同也能正确映射。

---

## 4. 协议（`StreamCore`）

单条 TCP，帧格式 `[1B type][4B big-endian length][payload]`：

| 方向 | 类型 | 说明 |
| --- | --- | --- |
| C→S | `HELLO(1)` | JSON 握手（协议版本 / 口令 / 客户端名） |
| S→C | `WELCOME(2)` | JSON 应答（是否通过、串流分辨率） |
| S→C | `VIDEO(3)` | H.264 Annex-B 访问单元（关键帧前自带 SPS/PPS） |
| S→C | `META(4)` | JSON 运行状态（state / fps / 码率 / 抓取模式 / 版本列表） |
| C→S | `INPUT(5)` | 二进制输入事件流（见下） |
| C→S | `COMMAND(10)` | JSON 远程指令：`list_versions` / `launch` / `stop` / `status` |
| S→C | `AUDIO(11)` | AAC 音频帧（需服务端授权音频采集） |
| 双方 | `PING(6)/PONG(7)` | 心跳，客户端 5 s 一次，8 s 无画面则请求关键帧 |
| S→C | `BYE(8)` | 服务端主动断开 |
| C→S | `KEYFRAME_REQUEST(9)` | 请求关键帧 |

`INPUT` 帧里可以串联多个事件：

- `POINTER`：指针 id、动作（down/move/up/cancel）、x/y 比例、是否相对位移、按键掩码
- `KEY`：GLFW 键码、按下/抬起、修饰键
- `SCROLL` / `TEXT` / `GRAB` / `TAP` / `KEYFRAME_REQUEST`

输入最终通过启动器原有的 `CallbackBridge`（`sendCursorPos` / `sendCursorDelta` /
`sendMouseButton` / `sendKeycode` / `sendScroll`）注入游戏，和服务端本地操作走同一条路。

---

## 5. 代码结构

```
StreamCore/                      共享协议（无 Android UI 依赖）
  StreamProtocol.kt              帧格式、握手、状态、指令
  InputCodec.kt                  输入事件编解码
  Keycodes.kt                    GLFW 键码子集

ZalithLauncher/                  服务端（在原启动器上改造）
  src/main/AndroidManifest.xml   服务端清单（无图标、导出设置页、开机自启）
  src/main/java/…/server/
    ServerSettings.kt            服务端设置（MMKV）
    ServerBootstrap.kt           冷启动补齐 JRE/LWJGL 解包与权限
    ServerService.kt             前台服务 + 唤醒锁 + WifiLock + 通知
    ServerBootReceiver.kt        开机自启
    ServerSettingsActivity.kt    设置界面（Compose）
    ServerStreamEngine.kt        TCP 服务、鉴权、码流分发、远程指令
    VideoEncoder.kt              MediaCodec H.264，硬件优先 + 软件兜底
    GameAudioCapture.kt          AudioPlaybackCapture → AAC（Android 10+）
    GameSession.kt               无界面启动 MC（渲染到编码器 Surface）
    RemoteInput.kt               网络输入 → GLFW/LWJGL 事件
    GameRuntime.kt               进程重启与续跑（JVM 单次生命周期）

ZalithClient/                    客户端（全新轻量模块）
  MainActivity.kt                填写地址
  StreamActivity.kt              播放 + 悬浮控制层 + 虚拟按键
  StreamConnection.kt            TCP 连接、心跳、输入上行
  VideoDecoder.kt                MediaCodec H.264 → SurfaceView
  AudioPlayer.kt                 MediaCodec AAC → AudioTrack
  InputController.kt             手势 / 键盘 → 输入事件
  AnnexB.kt                      NAL 切分与关键帧判定
```

服务端相对上游的主要改动只有三处，其余都是新增文件：

1. `GameLauncher` 的构造参数 `activity: Activity` → `context: Context`（服务端没有 Activity）
2. `ZLApplication` 的全局崩溃处理在服务端模式下不再弹不可见的 Activity，改为重启进程
3. `AndroidManifest.xml` 换成服务端版本，`applicationId` 改为 `…zalithlauncher.server`

---

## 6. 已知限制

- 服务端默认只打 arm64；32 位设备需要手动触发 workflow 选 `all`
- 音频串流需要 Android 10+ 且手动授权「录制权限」，未授权时静默降级为无声音
- 停游戏 / 游戏退出会重启服务端进程（JVM 无法在同一进程内二次启动），
  重连客户端会在约 1~2 秒后自动恢复
- 串流为单码流广播，多客户端共享同一画质；客户端数量多时建议降低码率

---

## 7. 许可

与上游一致，GPL-3.0。

#App 技术说明

> 包名 `com.mikun.mjapps`，基于 Kotlin + Jetpack Compose + Android 原生 `MediaCodec` / `OpenGL ES` / `WindowManager` 实现。
> 核心能力：把「左半 Alpha 遮罩 + 右半 RGB 彩色」的并排（Side-by-Side）视频解析成带透明度的合成画面，并提供「随机悬浮播放」与「常驻图标悬浮窗」两种观看方式。

---

## 1. 运行环境与依赖

| 项 | 版本 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| 最低 SDK | 23 (Android 6.0) |
| 目标 / 编译 SDK | 36 |
| Gradle / AGP | 9.1.0 / 9.0.0 |
| 核心依赖 | Compose BOM、activity-compose、lifecycle-runtime-ktx、kotlinx-coroutines |
| 额外权限 | 仅 `SYSTEM_ALERT_WINDOW`（悬浮窗） |

> 说明：项目早期曾接入语音唤醒（讯飞 → Vosk 离线），因 Android 14 后台麦克风受限、且部分 ROM（MIUI）对 overlay 输入法限制，已**彻底移除**，当前不依赖麦克风、不申请任何前台服务/录音权限。

---

## 2. 目录结构

```
app/src/main/
├── assets/
│   ├── mj.png                      # 常驻悬浮窗图标 (413×458)
│   └── video/
│       ├── video1/ 与 video3/      # 视频资源目录（目录名原样，含 vidoe2 拼写差异）
│       │   ├── config.json         # 解析配置（aFrame/rgbFrame 像素矩形等）
│       │   └── output.mp4          # 并排视频源
│       └── vidoe2/
│           ├── config.json
│           └── output.mp4
└── java/com/mikun/mjapps/
    ├── MainActivity.kt             # 入口 + 组合根 AlphaSbsApp
    ├── data/
    │   ├── SbsConfig.kt            # config.json 解析 → SbsVideoConfig / VideoEntry
    │   ├── VideoRepository.kt      # 递归扫描 assets/video
    │   └── FrameRateDetector.kt    # 自动识别真实帧率
    ├── player/
    │   ├── AlphaSbsPlayer.kt       # 解码 + 时钟同步 + 播放控制
    │   ├── SbsRenderView.kt        # OpenGL ES 合成渲染（4 种模式）
    │   ├── FloatingVideoWindow.kt  # 随机悬浮播放窗（播一遍自动消失）
    │   ├── MjImageFloatingWindow.kt# 常驻图标悬浮窗（拖动/吸附/暗号）
    │   ├── FloatingWindowState.kt  # 随机悬浮窗播放状态 StateFlow
    │   └── FloatingPosition.kt     # 位置/触发方式枚举 + 偏好持久化
    └── ui/
        ├── VideoListScreen.kt      # 主页（状态卡 + 常驻窗 + 随机播放 + 开发者）
        ├── PlayerScreen.kt         # 全屏播放页
        ├── Format.kt               # formatFps() 等工具
        └── theme/Theme.kt          # MJAPPSTheme
```

---

## 3. 视频解析原理（Alpha Side-by-Side）

每个视频由 `config.json` 描述两路像素矩形：

- **`alphaFrame`**：左半区域的灰度图，提供**透明度（Alpha）遮罩**；
- **`rgbFrame`**：右半区域的彩色图，提供**颜色（RGB）**；
- 合成时：取 `rgbFrame` 的颜色，以 `alphaFrame` 对应像素的亮度作为透明度，输出预乘（premultiplied）透明画面。

`SbsVideoConfig` 关键字段：

| 字段 | 含义 |
|---|---|
| `orientation` / `version` / `path` / `align` | 原始配置元数据 |
| `hasAudio` | 是否含音轨 |
| `alphaFrame` / `rgbFrame` | `SbsRect(x,y,w,h)`，两路像素矩形 |
| `videoWidth` / `videoHeight` | 源帧尺寸 |
| `width` / `height` | 输出（合成）尺寸 |
| `fps` | config 自带帧率（**不可信**，见 5.3） |

`SbsRenderView` 的 4 种渲染模式（`RenderMode`）：

| 模式 | 标签 | 说明 |
|---|---|---|
| `COMPOSITE` | 合成 | 默认。Alpha+RGB 合成，带透明度 |
| `ALPHA` | Alpha | 仅显示 Alpha 遮罩半边 |
| `RGB` | RGB | 仅显示彩色帧半边 |
| `RAW_SBS` | 原始SbS | 左右并排原始整帧 |

---

## 4. 播放核心（AlphaSbsPlayer + SbsRenderView）

- **解码**：`MediaCodec` 将 `output.mp4` 解码到 `SurfaceTexture`（OES 外部纹理）。
- **渲染**：`SbsRenderView`（继承 `GLSurfaceView`）在片元着色器中分别采样 `rgbFrame`（取色）与 `alphaFrame`（亮度取透明度），按 `RenderMode` 输出。
- **音频**：`AudioTrack` 播放解码出的 PCM。
- **时钟同步**：自研 `PlayClock`，按 **固定帧率节奏** 调度——`targetUs = frameIndex × 1_000_000 / fps`，彻底锁死帧率，避免 VFR/异常导致首遍过快。

```kotlin
// AlphaSbsPlayer 构造（fps 为检测值，<=0 回退 30）
val p = AlphaSbsPlayer(appContext, entry.dir, entry.config, entry.fps).apply {
    looping = false   // 随机悬浮窗：播一遍
}
```

---

## 5. 数据层

### 5.1 VideoRepository
递归扫描 `assets/video`，对每个子目录读取 `config.json` 生成 `VideoEntry`，并顺带调用帧率检测（失败不阻断列表）。

`VideoEntry`：`dir`（资源子目录）、`title`、`config: SbsVideoConfig`、`fps: Float`（检测值，0 表示未知）。

### 5.2 SbsConfig
用 `org.json` 解析 `config.json` → `SbsVideoConfig` / `SbsRect`；同时解析 `portrait` 下的 `aFrame` / `rgbFrame` 数组为矩形。

### 5.3 FrameRateDetector（自动识别真实帧率）
`config.json` 的 `fps` 字段不可信（实测写为 `91/90`）。检测逻辑：

- 读取视频轨前 **32** 个样本的 `presentationTimeUs`；
- 取相邻差值**中位数**推算 fps（抗可变帧率 VFR 与异常帧噪声）。

经验证：两个视频真实帧率均为 **30fps**（media timescale 15360 / delta 512 = 30）。UI 全面采用检测值（列表、播放页「真实帧率」、播放调度）。

---

## 6. 悬浮窗层

### 6.1 随机悬浮播放 — FloatingVideoWindow
- 在屏幕指定位置显示 `SbsRenderView`，`looping = false`，**播放一遍后自动移除**。
- 通过 `CoroutineScope` 轮询 `player.state.finished`（200ms 间隔）触发 `dismiss()`；
- 同时调用 `FloatingWindowState.setShowing(true/false)` 暴露随机窗播放状态；
- 尺寸：宽度固定 240dp，高度按 `height/width` 比例计算；窗口类型 `TYPE_APPLICATION_OVERLAY`（`FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`）；
- 位置由 `FloatingPosition` 决定（见 8.1）。

### 6.2 常驻图标悬浮窗 — MjImageFloatingWindow
常驻显示 `assets/mj.png`，生命周期为 **App 进程存活期间**（非前台 Service）。

**布局与旋转**
- 图标尺寸 `72dp`，`edgeGap = 0`（紧贴屏幕边缘，无间隙）；
- 拖动松手后按图标中心落在左/右半屏，**自动吸附**最近一边（`ValueAnimator` 180ms 平滑）；
- 旋转随吸附边变化：**左侧 −90°**，**右侧 +90°**；
- `StateFlow<Boolean> visible` 暴露显示状态。

**点击交互（由 `mode` 决定）**
- `DIRECT`（直接触发）：轻点即随机悬浮播放；
- `CODE`（暗号）：轻点弹出**本地按钮键盘**（不依赖系统输入法，适配 MIUI 等限制 overlay 软键盘的 ROM）。键盘内提供字符按钮（M / J / 蜘 / 蛛 / 侠 + 干扰字 米/网/神/精，顺序打乱），配「删除 / 确认」；输入 `MJ` 或 `蜘蛛侠` 正确后触发播放，否则提示重试。

**API**
```kotlin
class MjImageFloatingWindow(
    context: Context,
    onTriggerPlay: () -> Unit,   // 触发后的随机播放回调
) {
    var mode: MjWindowMode       // DIRECT / CODE
    fun show()                   // 自带去重：if(added) dismissView()
    fun hide()
    fun release()
    val visible: StateFlow<Boolean>
}
```

### 6.3 FloatingWindowState
`object` 持有 `MutableStateFlow<Boolean>`，`FloatingVideoWindow` 在 `show/dismiss` 时更新，供 UI（原状态卡）订阅。

---

## 7. 权限与启动流程

唯一权限：`SYSTEM_ALERT_WINDOW`。

- `MainActivity.onCreate` → `setContent { MJAPPSTheme { AlphaSbsApp() } }`；
- `AlphaSbsApp`：
  - `entries = VideoRepository.scan(context)`；
  - 创建 `floatingWindow = FloatingVideoWindow`（随机播放），`mjWindow = MjImageFloatingWindow(onTriggerPlay)`（常驻图标），`mode` 取自 `FloatingPrefs.getMjWindowMode`；
  - `DisposableEffect` 在组合销毁时 `release()` 两个窗；
  - **启动即显示**：`LaunchedEffect(Unit)` 中若已授权则 `mjWindow.show()`；
- **授权后自动显示**：`VideoListScreen` 监听 `ON_RESUME`，记录 `grantedPrev`；当 `canOverlay` 由 **未授权 → 已授权**（即从系统设置授权返回）时立即 `mjWindow.show()`；
- 申请权限统一走 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`。

---

## 8. 偏好与配置（SharedPreferences）

`FloatingPrefs`（文件 `floating_prefs`）：

| Key | 含义 | 取值 | 默认 |
|---|---|---|---|
| `position` | 随机悬浮窗位置 | `FloatingPosition` 枚举名 | `TOP_RIGHT` |
| `mj_window_mode` | 常驻窗触发方式 | `MjWindowMode` 枚举名 | `DIRECT` |

### 8.1 FloatingPosition（随机悬浮窗位置）
`TOP_RIGHT`(顶部右，默认) / `TOP_CENTER`(顶部居中) / `TOP_LEFT`(顶部左) / `RANDOM`(随机)。

### 8.2 MjWindowMode（常驻窗点击触发方式）
`DIRECT`(直接触发，默认) / `CODE`(暗号)。二者均持久化、实时同步到 `mjWindow.mode`。

---

## 9. 主页 UI（VideoListScreen）

设计规范：统一 `Card + padding(16.dp) + 分割线 + 小标题`，分组清晰。共 4 张卡片：

1. **状态卡**（顶部大卡）：`工作状态 = 悬浮窗权限是否授权`；权限已授权显示「工作中」+ 绿圈对号（Canvas 绘制），未授权显示「未工作」+ 红圈叉号；副标题同步提示。
2. **常驻悬浮窗 (MJ) 卡**（整合权限 / 触发方式 / 显隐）：
   - 标题行：图标 + 名称 + 显示/隐藏按钮；
   - 分割线 + 权限行：`已授权` / `去授权`（去授权跳系统设置）；
   - 分割线 + 触发方式：`直接触发` / `暗号` 两个 `FilterChip` + 行为说明。
3. **随机悬浮播放 卡**（整合播放 / 位置）：
   - 播放描述（无权限时禁用并提示）；
   - 分割线 + 显示位置：`顶部右 / 顶部居中 / 顶部左 / 随机` 四个 `FilterChip`。
4. **开发者网页 卡**：点击 `ACTION_VIEW` 打开 `https://mikun.dpdns.org`（带 `FLAG_ACTIVITY_NEW_TASK`）。

视频列表位于卡片下方，展示标题、输出尺寸、真实帧率、源帧与 `aFrame/rgbFrame` 矩形（`Monospace`），含音频显示 ♪。

---

## 10. 已知限制与编译说明

- **常驻窗范围**：指 App 进程存活期间常驻，进程被杀后图标消失（未做成前台 Service，符合移除保活/语音后的取向）。
- **帧率**：一律以 `FrameRateDetector` 检测值（实测 30fps）为准，不信任 `config.json` 的 `fps`。

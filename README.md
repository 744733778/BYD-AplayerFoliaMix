# BYD-AplayerFoliaMix

一个基于 [APlayer](https://github.com/rRemix/APlayer) 魔改的 Android 本地音乐播放器，把 [folia](https://github.com/chthollyphile/folia-major) 的「全屏沉浸式歌词」视觉搬到了 Android 端，并重做了联网歌词匹配 / 封面匹配流程。

> 安装到设备后显示的应用名为 **AFMplayer**。

---

## 上游 / Upstream

本仓库是一个「混合改版」，代码主要来自以下两个上游项目：

| 上游 | 仓库 | 协议 | 与本仓库的关系 |
| --- | --- | --- | --- |
| **APlayer** | [rRemix/APlayer](https://github.com/rRemix/APlayer) | GPL-3.0 | 基础：Android 音乐播放器（Jetpack Compose），本项目的整体骨架与播放逻辑 |
| **folia** | [chthollyphile/folia-major](https://github.com/chthollyphile/folia-major) | AGPL-3.0 | 视觉参考：Web / React 的全屏歌词播放器；本项目的逐字歌词渲染特效（扫光卡拉OK / 词弹辉光 / 光室点亮）移植、参考自它 |

- 本项目继承 APlayer 的 **GPL-3.0** 协议（见仓库根目录 `LICENSE`）。
- folia 为 **AGPL-3.0**，仅作为视觉与算法参考；相关效果以 Android `Canvas` 重新编写，**未直接包含其源代码**。

---

## 主要改动

### 全屏逐字歌词渲染器（纯 Canvas）
文件：`app/src/main/java/remix/myplayer/ui/widget/lyric/folia/FoliaLyricCanvasView.kt`

- **扫光卡拉OK（CLASSIC）**：整词高亮 + 软边扫光填充。
- **词弹辉光（GLOW_WORD）**：整词弹入 + 逐字辉光脉冲。
- **光室点亮（LUMIERE）**：字形点亮 + 闪点 + 崩解。
- 歌词整行**水平居中**，多行折行时每行各自居中。

### 联网歌词手动匹配
文件：`app/src/main/java/remix/myplayer/ui/screen/lyric/LyricMatchScreen.kt`

- 支持网易云 / 酷狗 / QQ 搜索；预览面板**动态跟随播放进度**高亮当前行。
- **应用歌词时同步匹配并写入对应封面**（网易云候选自带封面；QQ / 酷狗候选按标题 / 歌手回退搜索网易云封面）。

### 封面加载回退修复
文件：`app/src/main/java/remix/myplayer/glide/UriFetcher.kt`

- 优先探测文件内嵌 APIC，再回退网络；重新启用自定义封面（裁剪 / 匹配封面）读取逻辑，保存封面后自增 `albumVersion` 强制刷新。

### 全屏页左下角信息
- 方形封面小图标 + 歌名 / 歌手（二者行距已收紧）。

### 改名 / 换图标
- 应用名改为 **AFMplayer**，启动图标替换为 folia 风格图标。

---

## 构建

### 环境要求
- **Android SDK**（在 `local.properties` 中设置 `sdk.dir`）
- **JDK 21**
- **Gradle 9.5.1**（仓库自带 Gradle Wrapper，无需手动安装）

### 第三方依赖
- `third-party/taglib`：**源码已随仓库内置**（C++ 元数据解析，CMake 编译），无需拉取子模块。
- `androidx.media3`（ExoPlayer）：通过 Maven 依赖引入。原上游的 `third-party/media3` 子模块在本改版中未使用，已移除。

### 构建命令
```bash
./gradlew :app:assembleNormalDebug      # 普通版 debug 包
```
产物位置：`app/build/outputs/apk/normal/debug/app-normal-debug.apk`

> 说明：`local.properties` 与 `local.properties.bak` 已被 `.gitignore` 忽略，请在本机自行配置 `sdk.dir`。

---

## 协议

- 整体协议：**GPL-3.0**（继承自 APlayer）。
- 第三方组件：
  - **taglib**（内置）：请参阅 `third-party/taglib/LICENSE`。
  - **folia**：AGPL-3.0，仅作视觉 / 算法参考，本项目以其为灵感用 Android `Canvas` 重新实现，未包含其源代码。

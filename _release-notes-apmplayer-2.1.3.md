# AFMplayer v2.1.3

基于 [APlayer](https://github.com/rRemix/APlayer)（GPL-3.0）魔改的 Android 本地音乐播放器，把 [folia](https://github.com/chthollyphile/folia-major) 的全屏逐字歌词视觉搬到 Android 端。本版本累计了多项新功能与车机适配修复。

## 新增

- **等响度归一化（设备端自动分析，参考 mp3gain）**
  - 设置 → 播放 新增「等响度归一化」开关；ReplayGain 详情页可「扫描曲库响度」。
  - 在设备端用 `MediaExtractor` + `MediaCodec` 解码 PCM，过 K-weighting 频率加权，按窗口 RMS 的 95 百分位测算每首歌的感知响度，再算出各自增益并应用——**没有 ReplayGain 标签的歌也能统一响度**。
  - 后台扫描整库、可取消、带进度；结果存入 Room 表，按文件大小/修改时间失效重算；播放时优先用歌曲自带标签，无标签且开启自动分析则用本机测算增益。
- **均衡器预设方案**
  - 均衡器界面新增 **平直 / 摇滚 / 流行 / 古典 / 爵士 / 人声 / 低音增强 / 高音增强** 预设。
  - 所有预设统一同步低音增强滑条；选中状态持久化，下次打开仍高亮并显示「当前：xxx」。

## 修复 / 优化

- **封面**：未匹配到封面时按歌名/歌手/文件名回退匹配，不再留空；修复车机无标签歌曲共享 albumId 导致封面「串图」的问题。
- **车机全屏歌词**：右下角设置按钮与播放条上移避让车机底部状态栏，黑色圆按键改为透明；左下角封面图标上移露出歌名（隐藏歌手）；切歌后联网匹配歌词自动刷新，无需手动返回重进。
- **均衡器**：低音增强滑条在切换预设时正确回落，不再残留上一个预设的值。

## 安装

- 下载 `AFMplayer-v2.1.3-debug.apk` 并安装到 Android 设备（debug 签名，车机 / 模拟器可直接安装）。
- 源码构建：`./gradlew :app:assembleNormalDebug`，产物位于 `app/build/outputs/apk/normal/debug/app-normal-debug.apk`。

## 上游与协议

- 基础：[APlayer](https://github.com/rRemix/APlayer)（GPL-3.0）。
- 视觉参考：[folia](https://github.com/chthollyphile/folia-major)（AGPL-3.0），相关效果以 Android `Canvas` 重新实现，未包含其源码。
- 整体协议：**GPL-3.0**。

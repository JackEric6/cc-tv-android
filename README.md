# cc-tv-android

**丛丛影视 Android 客户端**，基于 TVBoxOSC / TVBoxOS 相关开源项目持续开发，提供影视点播、直播、弹幕和离线下载功能。当前主线最低支持 Android 7.0（API 24）。

## 功能

- **多源点播**：管理影视订阅源，跨源搜索并按来源查看结果；直播源支持 JSON、M3U 和 TXT 配置。
- **视频播放**：提供 ExoPlayer 与 IJK 播放内核、解码设置、字幕、选集和线路切换；可在播放时启用本地 M3U8 净化。
- **电视直播**：导入和管理直播源，查看频道与节目单，并按频道提供的线路播放。
- **弹幕**：按影片和集数匹配弹幕；候选不可用或内容无法解析时继续尝试其他候选。弹幕检索依赖配置的在线接口和网络连接。
- **离线下载**：支持可下载线路的任务管理、进度查看、暂停/继续及本地播放。具体支持情况取决于线路协议和站点限制。
- **本地配置**：提供播放、下载、显示和数据源设置；默认配置文件位于 `app/src/main/assets/`。

## 构建

构建环境：JDK 21、Android SDK 37、Android NDK `27.2.12479018`。仓库包含 Gradle Wrapper 9.7.1。

在 Windows PowerShell 中运行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

APK 输出在 `app/build/outputs/apk/`。Release 包只有在本机配置了签名文件时才会签名；不要将签名文件或真实凭据提交到仓库。

## 代码结构

- `app/`：Android 应用、页面、数据源适配、下载与播放协调逻辑。
- `player/`：视频播放内核和媒体数据源支持。
- `quickjs/`：QuickJS 脚本运行支持。
- `pyramid/`：Python 运行与桥接模块。
- `libs/backdrop/`：应用内使用的 Compose 视觉组件库。

## 来源与许可

本项目包含并改造了多个上游开源项目的代码，相关来源包括：

- [CatVodTVOfficial/TVBoxOSC](https://github.com/CatVodTVOfficial/TVBoxOSC)
- [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS)
- [FongMi/TV](https://github.com/FongMi/TV)

项目主体按仓库中的 [AGPL-3.0 许可](LICENSE)发布；各依赖库及上游组件仍分别适用其原有许可。

cc-tv 不托管视频文件或第三方服务。应用中的源配置和可访问内容可能随第三方站点变化；使用者应自行确认内容来源及使用行为符合所在地法律法规，并尊重相关权利人的合法权益。

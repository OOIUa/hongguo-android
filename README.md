<div align="center">

<img src="docs/images/logo.png" width="112" alt="红果独立版 logo">

# 红果独立版

**在手机上发现好剧，随时接着看。**

基于 Kotlin、Jetpack Compose 与 Media3 构建的原生 Android 短剧客户端。<br>请求、媒体处理、缓存与播放均在本机完成，无需部署服务器或配置 API key。

[![Release](https://img.shields.io/github/v/release/OOIUa/hongguo-android?label=Release&color=F15A2B)](https://github.com/OOIUa/hongguo-android/releases/latest) [![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#下载与安装) [![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)](#功能一览) [![License](https://img.shields.io/badge/License-GPL--3.0--only-blue)](LICENSE)

[**下载 APK**](https://github.com/OOIUa/hongguo-android/releases/latest) · [界面预览](#界面预览) · [使用说明](#快速上手) · [源码构建](#源码构建) · [反馈问题](https://github.com/OOIUa/hongguo-android/issues)

</div>

## 下载与安装

当前发布版本：**v0.7.8**（versionCode 19）· Android **8.0 / API 26** 及以上。

本版底栏支持按住左右拖动切换（仿 iOS：悬浮玻璃的选中胶囊跟随手指放大，经过每个 Tab 轻微震动，松手弹到目标 Tab，快速一甩多走一格；经典底栏同样可拖动）；「设置」与「主题样式」改为独立页面，切换 Tab 后再回到「我的」不再停留在设置页；沉浸暗场的首页推荐流和详情封面延伸到透明状态栏下方；顶部标签点击时不再闪现白色底色。0.7.7 起换用简约版红果图标（自适应启动图标，Android 13 起支持主题图标），首页标题旁同步使用新 logo；并补齐过渡动画：播放页上下滑动切集时当前画面随手指滑走、下一集带集数滑入，自动连播与选集跳转也以滑动切换，画面首帧淡入；进入详情、搜索与账号页从右侧滑入，返回时滑出，底栏切换淡入淡出，播放器打开时上滑出现、关闭时淡出。0.7.6 起播放页不再在视频后方显示半透明剧集封面，加载和切集时为纯黑背景；并新增「我的 › 设置 › 底栏样式」，可在经典底栏与悬浮玻璃胶囊之间切换：悬浮玻璃让页面在半透明胶囊下滚动，Android 12 及以上有模糊效果，Android 13 及以上带玻璃边缘折射；澎湃OS（小米、红米、POCO）默认悬浮玻璃，其他手机默认经典底栏。0.7.5 修正了竖屏播放被系统当作全屏的问题：竖屏看剧时保留状态栏与导航栏（透明背景、白色图标），时间、电量和通知照常显示，下拉通知栏与返回手势一次即可；横屏时仍为全屏播放。0.7.4 起三套主题各自拥有完整的页面布局：暖白精修是经典浅色布局，沉浸暗场首页可竖屏上下滑动刷剧，内容卡片提供频道切换、热度榜和双列瀑布流，可在「我的 › 设置 › 主题样式」随时切换。原有播放、倍速、续播、筛选和账号能力继续保留。实际账号片单与同步结果仍请用本人账号核对，详情见 [接入说明](docs/ACCOUNT_INTEGRATION.md)。

| 下载方式 | 链接 |
| --- | --- |
| GitHub 直连 | [下载 v0.7.8 APK](https://github.com/OOIUa/hongguo-android/releases/download/v0.7.8/hongguo-android-0.7.8.apk) |
| 加速线路 1 | [通过 gh-proxy.com 下载](https://gh-proxy.com/https://github.com/OOIUa/hongguo-android/releases/download/v0.7.8/hongguo-android-0.7.8.apk) |
| 加速线路 2 | [通过 ghfast.top 下载](https://ghfast.top/https://github.com/OOIUa/hongguo-android/releases/download/v0.7.8/hongguo-android-0.7.8.apk) |
| 发布说明与源码 | [全部版本](https://github.com/OOIUa/hongguo-android/releases) · [v0.7.8 完整源码](https://github.com/OOIUa/hongguo-android/releases/download/v0.7.8/hongguo-android-0.7.8-source.zip) · [SHA-256 校验文件](https://github.com/OOIUa/hongguo-android/releases/download/v0.7.8/SHA256SUMS-0.7.8.txt) |

下载后打开 APK，按 Android 系统提示完成安装。GitHub 连接不畅时可尝试加速线路，公共代理的可用性取决于网络与服务状态。

> **已有旧版？请直接覆盖安装，保留收藏、历史与续播位置。** 同签名版本可覆盖升级；卸载或清除应用数据会删除本机记录。0.1 / 0.2 版本需先手动覆盖安装一次新版，0.3.0 起支持应用内更新，0.4.0 起支持加速线路。

## 界面预览

<table>
  <tr>
    <th align="center">首页推荐</th>
    <th align="center">发现与筛选</th>
    <th align="center">我的与账号</th>
  </tr>
  <tr>
    <td><img src="docs/images/home.png" width="240" alt="红果独立版首页：本机继续观看、搜索入口与双列海报"></td>
    <td><img src="docs/images/categories.png" width="240" alt="发现页面：短剧、漫剧、AI 剧、动态筛选与双列海报"></td>
    <td><img src="docs/images/my.png" width="240" alt="我的页面：本机收藏与历史、账号与隐私、播放设置和检查更新"></td>
  </tr>
</table>

截图来自 v0.7.1 的 Android 模拟器实拍，内容列表随上游更新。

## 功能一览

| 功能 | 说明 |
| --- | --- |
| 首页与续看 | 本机最新观看记录一键续播；热度精选、最新短剧和漫剧榜单双列展示，支持继续翻页 |
| 发现与搜索 | 浏览短剧、漫剧、AI 剧，按上游提供的条件筛选；从首页或发现进入独立搜索页 |
| 竖屏播放 | 上划下一集、下划上一集，也可打开选集面板跳转；竖屏保留状态栏，横屏为全屏 |
| 播放控制 | 暂停 / 继续、拖动进度、缓冲进度显示、播放失败重试 |
| 长按临时加速 | 播放中长按视频左半屏临时以2倍速播放；松手、取消、离开页面后恢复原速度，不改变保存的倍速 |
| 六档倍速 | 0.5×、0.75×、1×、1.25×、1.5×、2×，自动记住选择 |
| 连播与预加载 | 可分别开关自动连播、下一集预加载，末集不循环 |
| 片单与续播 | 本机与红果账号切换来源；本机保存收藏、观看历史、集数与集内位置，支持单条移除与清空 |
| 个人红果账号 | 官网网页登录、扫码与手机号验证码入口；官网和手机云账号验证分开，失败可复制脱敏诊断 |
| 账户片单 | 仅在手机业务身份通过验证后读取收藏、最近 50 部历史及预约，与本机记录分开 |
| 可选进度上报 | 仅已确认的手机云账号可用，默认关闭；实际跨设备结果需本人验收 |
| 边播边缓存 | 对支持的片源按需下载与处理，不兼容时回退完整缓存播放 |
| 应用内更新 | 手动 / 启动检查，支持直连、两条加速线路及自动切换 |
| 界面主题 | 暖白精修、沉浸暗场、内容卡片三套主题，各有独立的首页、发现/剧场、片单、详情与我的页面布局，点选即时生效；可在系统深色模式时自动使用沉浸暗场；底栏可选经典或悬浮玻璃 |

首页按热度、上新及榜单展示内容；收藏和观看历史各最多保存 **200 部**，两份列表独立管理。

## 快速上手

底部导航为「首页 / 发现 / 片单 / 我的」，搜索从首页或发现的搜索栏进入独立页面。

1. 安装后从「首页」或「发现」选剧，也可点击搜索栏输入剧名。
2. 进入详情，选择集数开始播放；首页「继续观看」或「片单 → 本机 → 历史」可继续上次的集数与位置。
3. 播放中点击画面暂停 / 继续，拖动进度条跳转，上下滑动切集；右下角「倍速」调整速度；播放中长按视频左半屏可临时2倍速，松手恢复之前的速度。
4. 详情中分别提供本机与账号收藏操作；播放器点击「收藏」，在面板中选择「本机收藏」或「红果账号收藏」。到「片单」切换来源，查看对应历史、收藏；预约仅在红果账号来源显示。
5. 到「我的 → 播放设置」调整自动连播、下一集预加载和启动检查更新。
6. 到「我的 → 账号与隐私」登录个人账号；只有一部手机时选择「在本机打开官网登录」，待页面加载后点「打开官网登录窗口」，自行完成官方验证后点上方「完成登录」。也可在另一台设备的红果 App 中扫码确认。

若显示“官网已登录，手机云账号未接通”，官网会话已加密保留，可直接重试账号资料；点击“复制诊断信息”可提供不含凭据与身份值的结构摘要。此状态不读取或写入手机云片单。

应用需要联网访问上游内容源，无需填写服务器地址。切到后台会停止播放与预加载。

## 在线更新

打开「**我的 → 检查更新**」即可手动检查。开启启动检查后，每次重新启动应用会在后台检查一次，有兼容的新版本才弹窗；没有新版或检查失败时保持安静。普通前后台切换和旋转屏幕不会重复检查，手动检查不受启动开关限制。

发现新版后，点击「下载更新」才开始下载。下载完成会校验文件大小、SHA-256、包名、版本、最低系统要求与签名，再交给 Android 系统确认安装。首次更新可能需要允许本应用安装来自此来源的应用。

更新窗口可选择「自动切换」「GitHub 直连」「加速线路 1」「加速线路 2」，选择保存在本机。自动模式依次尝试三条线路；取消下载会停止本次任务。安装包使用相同的完整性与签名校验。

发布源为本仓库的 [GitHub Releases](https://github.com/OOIUa/hongguo-android/releases)，更新清单为 [update.json](https://github.com/OOIUa/hongguo-android/releases/latest/download/update.json)。只有更高的 `versionCode` 才会提示更新。下载与安装均由用户操作确认。

## 播放、缓存与本机数据

- **渐进播放**：常见非碎片 CENC MP4 使用 Range 按需取块并在本机处理，首帧无需等待整集下载。不支持此路径的媒体会回退为整集下载、处理后播放；实际效果取决于片源、编码、封装与网络。
- **本机记录**：收藏、历史、播放偏好与缓存保存在应用私有目录，无需广泛文件访问权限。历史在实际渲染首帧后记录，准备失败不会记为已观看。
- **账号记录**：个人会话和账户片单加密保存在本机，退出或切换账号隔离数据。原本机记录不会自动上传；详情的账号收藏入口和播放器面板中的「红果账号收藏」属于独立的云端操作。短信可能需要官网验证，收藏与跨设备续播需用本人账号核对。
- **缓存用途**：用于播放加速，可能随会话结束或空间策略被清理，不作为永久离线保存。目前仅支持前台播放，未提供后台播放服务或批量离线下载。

<details>
<summary>预加载与缓存额度</summary>

下一集预加载仅在应用处于前台、开关开启、当前集已渲染首帧且缓冲至少 5 秒后启动。除索引与样本探测外，额外预热最多 512 KiB 的文件前段；关闭开关或切到后台后停止预加载。

渐进缓存与完整缓存各有独立的 512 MiB 额度。不完整的渐进缓存会在会话关闭时清除，缓存也可能按空间策略淘汰。调整倍速保留当前播放位置和暂停状态，并在切集、连播及再次打开应用时继续使用。

</details>

## 源码构建

### 环境准备

- JDK **17 或 21**。
- Android SDK **Platform 36**，通过 `ANDROID_HOME` 或本机 `local.properties` 中的 `sdk.dir` 指定 SDK 位置。
- 仓库已包含 **Gradle Wrapper**；首次构建需要联网下载 Gradle 与依赖。也可使用 Android Studio 打开项目。

```bash
git clone https://github.com/OOIUa/hongguo-android.git
cd hongguo-android
```

Windows / PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Linux / macOS：

```bash
chmod +x gradlew
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 产物：`app/build/outputs/apk/debug/app-debug.apk`。

有 Android 设备或模拟器时，可运行设备测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Linux / macOS 使用 `./gradlew` 执行同一任务。联网用例会访问真实上游内容并下载视频，可用性与耗时取决于网络和内容源。

> 当前发布包沿用既有测试签名。自行构建时自动生成的其他测试密钥无法覆盖现有安装。发布须保留同一密钥并核对 [公开证书指纹](release-signing.sha256)；构建发布资产、对应源码和更新清单的步骤见 [发布指南](RELEASING.md)。

### 项目结构

```text
app/src/
├── main/
│   ├── java/com/hongguo/android/  # Compose 界面、播放、收藏历史与在线更新
│   ├── java/com/hongguo/api/      # Java 请求、签名与媒体处理核心
│   └── assets/                   # 上游参数与许可证通知
├── test/                         # JVM 回归测试
└── androidTest/                  # Android 设备与播放验证
scripts/prepare-release.ps1       # 发布资产生成脚本
```

### 验证范围

[验证记录](VALIDATION.md) 包含构建、静态检查、模拟器回归及覆盖更新的数据保留记录。实体手机、Android 8–15 设备及全部编码 / MP4 布局尚未全面验证，账号功能的实际登录与同步结果需由本人核对。

## 常见问题

**需要部署后端或安装 Node.js / FFmpeg 吗？**

不需要。请求、签名、媒体处理、缓存与播放在 APK 内执行，手机仍需联网访问内容源。

**为什么有些剧集需要等待较久？**

支持渐进播放的片源可以边播边缓存；不兼容时会先下载整集并处理。网络、上游响应与媒体格式都会影响准备时间，不能保证所有片源都能立即起播。

**能离线保存剧集或同步收藏吗？**

缓存仅用于播放加速，不提供永久离线保存。登录个人账号后可读取云端片单，并主动开启本次观看进度上报。本机旧记录不会自动上传，历史与预约按固定批次读取。卸载或清除应用数据会删除本机记录。

**播放提示“匿名播放设备注册失败”或“空响应”？**

0.7.2 起，首次播放会向平台注册一个匿名播放设备，身份只保存在内存里，与个人账号无关，重启应用后重新注册。注册失败多为网络问题，约 30 秒后再次播放会自动重试。0.7.1 及更早版本无法通过新的上游校验，请更新到 0.7.2 或以上。

**怎么切换界面主题？**

打开「我的」，点右上角齿轮或「设置」，进入「主题样式」即可切换，选择即时生效并会记住。三套主题的页面布局不同：暖白精修为经典布局，沉浸暗场首页可上下滑动刷剧，内容卡片以热度榜和瀑布流为主。主题选择只在首次打开 0.7.4 时出现一次，选“暂不选择”会保留当前主题。播放页始终为深色，三套主题共用。

**更新下载失败怎么办？**

在更新窗口切换线路重试，或从本文的 APK 下载链接手动覆盖安装。保留旧版应用数据，避免先卸载。

**如何反馈问题？**

请在 [Issues](https://github.com/OOIUa/hongguo-android/issues) 提供应用版本、Android 版本、手机型号、复现步骤与错误截图。播放问题可补充剧名和集数，方便定位。

## 来源与许可证

本 Android 应用按 **GPL-3.0-only** 分发。协议与媒体核心参考 [sunnysky123/hongguo-web](https://github.com/sunnysky123/hongguo-web)，其中部分实现注明来自 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama)；保留原有来源注释及 MIT / GPL 通知。

完整条款、来源版本与改动说明见 [LICENSE](LICENSE) 和 [第三方通知](THIRD_PARTY_NOTICES.md)。发布 APK 时应同时提供该版本对应的完整源码与构建文件。

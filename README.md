# 红果独立安卓端

当前版本：**0.3.0**（versionCode 3），支持 Android 8.0（API 26）及以上，沿用既有测试签名。

旧版没有在线更新入口，需先从 [GitHub Releases](https://github.com/OOIUa/hongguo-android/releases/latest) 手动安装一次 0.3.0；签名一致时可覆盖安装，保留设备身份、收藏、观看历史及续播位置。之后可在应用内检查并安装后续版本。

## 运行方式

安装 APK 后可从首页推荐或分类浏览选剧，也可直接搜索剧名，点击结果进入详情，选择集数即可准备并播放。请求、视频下载、媒体处理和缓存都在手机本机执行，首次播放需要等待整集准备完成，不需要配置服务器地址或 API key。网络用于访问上游内容源。

底部包含首页、分类、搜索、收藏、历史五个入口。首页的热度精选来自短剧热度排序，最新短剧和漫剧榜单分别显示各自内容；不声称提供个性化推荐。分类支持短剧、漫剧和 AI 剧，筛选维度及选项来自实时上游面板，改完条件点击“应用筛选”，可重置和继续翻页。

详情页点亮星标即可收藏。收藏与观看历史在手机本地独立保存，各最多 200 部；支持单条移除、清空确认和继续观看。历史仅在实际渲染首帧后记录，保留集号和集内播放位置，准备失败不会被记作已观看。清空历史不会清空收藏，清空收藏也不会清空历史。

现有搜索、分页、详情选集、本地视频准备、播放与拖动、上下集、自动连播、错误重试和取消继续保留。具体交付验证见 [VALIDATION.md](VALIDATION.md)。

## 在线更新

应用启动时，距上次检查已满 24 小时才会静默检查一次；没有新版或检查失败时不弹窗打扰。顶部“检查更新”按钮可随时手动检查。发现兼容的更高版本后显示版本与更新说明，点击“下载更新”才开始下载，也可选择“稍后”或取消下载。

下载完成后，应用检查文件大小、SHA-256、包名、versionCode、versionName、最低系统版本及签名证书；点击“安装更新”前还会重新校验。首次安装更新时，系统可能要求允许本应用安装来自此来源的应用，授权返回后继续打开系统安装界面，由用户确认。应用不会静默安装。

更新来源为 [OOIUa/hongguo-android](https://github.com/OOIUa/hongguo-android)，清单固定地址为 [update.json](https://github.com/OOIUa/hongguo-android/releases/latest/download/update.json)。只有更高的 `versionCode` 才视为新版，版本名称仅作显示。清单与更新包通过匿名 HTTPS 下载，APK 不包含 GitHub 凭据。

当前仍使用既有测试密钥签名。发布者应保留本地同一密钥；公开证书指纹见 [release-signing.sha256](release-signing.sha256)，它不是私钥。若将来换成不同的正式签名，当前安装无法直接被该新签名覆盖，不能承诺保留数据的原位升级；迁移方式需另行说明。

## 构建

安装 JDK 17 或 21、Android SDK Platform 36，并设置 `ANDROID_HOME`；或在本机 `local.properties` 中指定 `sdk.dir`。然后运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`，使用测试签名。发布构建必须核对签名与 [release-signing.sha256](release-signing.sha256) 一致；另一台机器自动生成的测试密钥不能用于覆盖现有安装。发布资产可在提交源码后通过 `pwsh -File scripts/prepare-release.ps1` 生成到 `../dist/`。后续版本的版本参数、清单生成及 GitHub 发布步骤见 [RELEASING.md](RELEASING.md)。

有可用 Android 设备或模拟器时，可运行联网搜索、选集和实际解码测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

联网测试会访问上游真实内容并下载剧集，执行时间和可用性取决于网络和内容源。更新功能的校验测试与真实发布检查结果以 `VALIDATION.md` 为准。

## 代码结构

- `app/src/main/java/com/hongguo/android`：原生界面、发现页与筛选模型、页面状态、收藏历史、更新清单、更新下载与系统安装交接。
- `app/src/main/java/com/hongguo/api`：从历史版本迁移并适配的请求与媒体核心。
- `app/src/main/assets/upstream.json`：公开上游接口参数，无自建服务地址或预置登录凭据。
- `app/src/test`：JVM 核心、筛选分页与更新校验回归测试。
- `app/src/androidTest`：安卓设备验证。

应用无需广泛文件访问权限。缓存及收藏历史保存于应用私有目录；清除应用数据或卸载会删除这些本机数据。此版本仅在前台播放，不提供后台播放服务或批量离线下载。

## 来源与许可证

本 Android 应用按 **GPL-3.0-only** 分发。协议和媒体核心参考 `sunnysky123/hongguo-web`，其中部分实现注明来自 `jackotom/hongguo-drama`；原有来源注释与上游 MIT/GPL 通知予以保留。许可证、来源版本和改动说明见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。发布 APK 时应同时提供该版本对应的完整源码和构建文件。

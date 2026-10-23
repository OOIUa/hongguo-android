# 0.3.0 验证记录

验证日期：2026-10-23（Asia/Shanghai）。包名 com.hongguo.android，versionCode 3；Android 8.0 及以上。

- assembleDebug 成功，APK 与此前 0.1/0.2 测试版使用相同证书，指纹见 release-signing.sha256。
- JVM 测试 72/72 通过：保留此前 47 项，新增清单模型 11、匿名 HTTP 6、更新包文件与地址边界 8 项。
- Lint：0 errors、23 warnings、1 hint。
- Android 16 模拟器真实内容回归：DiscoveryFlowTest、LibraryFlowTest、StandaloneFlowTest 全部通过，覆盖推荐、分类、搜索、选集、实际首帧、续播和收藏历史。
- 更新元数据测试覆盖整数版本比较、应用 ID、同仓库 APK 地址、HTTPS 跳转主机限制、128 KiB 上限、损坏 UTF-8、超时和不泄露地址的错误信息。
- 文件测试覆盖大小、ZIP 头和 SHA-256 校验、篡改、取消及异常下载地址。
- UpdateInstallerFlowTest：7/7 通过。使用设备已安装 APK，验证错误版本、错误包名、同长度篡改、截断、错误头、未经登记的文件和目录边界均被拒绝。两项安装权限用例由宿主 adb 在测试前允许、测试后恢复；测试进程不修改安装 app-op。

GitHub 发布后，另行记录真实在线下载、系统安装和数据保留验证。实体手机以及 Android 8–15 设备兼容性尚未验证。

# 0.2.0-test 验证记录

验证日期：2026-10-23（Asia/Shanghai）。

## 安装包

- 包名：com.hongguo.android；versionName：0.2.0-test；versionCode：2。
- Android 8.0（API 26）及以上，compileSdk / targetSdk 为 36。
- JDK 21、Gradle 8.13、Android Gradle Plugin 8.13.2。
- assembleDebug 成功；APK Signature Scheme v2 签名校验成功。
- 与 0.1.0-test 使用同一签名，保留 arm64-v8a、armeabi-v7a、x86、x86_64 支持。

## 自动检查

- JVM 测试 47/47 通过：核心 12、媒体 6、发现页分页 10、发现页映射 7、收藏历史编解码 12。
- Android Lint：0 errors、23 warnings、1 hint；未屏蔽错误。警告主要为依赖更新提示、Locale 与布局 API 建议。
- 三组 Android UI 测试全部通过；播放组另加非零时间点的历史续播验证并单独通过。

## 真实安卓链路

设备：Medium_Phone_API_36.1 模拟器，Android 16。内容源由 APK 直接访问；视频由手机进程下载和处理，没有使用自建后端或替代视频。

DiscoveryFlowTest：

- 首页真实短剧热度推荐首屏、续页、去重及游标前进。
- 最新短剧及明确标注的漫剧榜单均返回真实数据。
- 分类加载真实主题和排序等筛选项；修改草稿不改变已应用条件，应用后请求对应数据。
- 切换短剧/漫剧时重置旧条件并加载匹配的筛选面板与结果。

LibraryFlowTest：

- 收藏单条移除、清空确认/取消、历史单条删除及清空。
- 收藏和历史独立，清空一份不删除另一份。
- SharedPreferences 单快照落盘及重新构造读取；长 ID 保持字符串。
- 已删除的历史不会被旧位置更新重新创建。

StandaloneFlowTest：

- 保留真实搜索、选集、本机媒体处理、两集首帧播放、拖动进度及播放器重建恢复。
- 详情收藏不会提前创建历史，真正首帧才建立历史记录。
- 历史条目点击后自动恢复相应集数与集内位置；额外实测保存至少 4 秒的位置后继续播放，播放器进度达到保存点。
- 缓存重播仍可实际渲染视频帧。

## 覆盖安装和进程重启

先安装 0.1.0-test 并实际搜索/播放，再覆盖安装 0.2.0-test。设备身份文件及原续播配置的前后 SHA-256 保持一致。

新收藏和历史写入后，执行 am force-stop 并冷启动。两份列表均能从 UI 恢复，持久化快照前后校验一致。

## 本轮修复的接口问题

原项目列表请求默认未签名，实测上游会返回空 HTTP 200。本版默认使用已有签名逻辑发送列表请求，真实推荐、上新及筛选已验证可用；空响应会显示内容服务错误，不再伪装为空列表。

## 范围与限制

- 未连接实体手机，Android 8–15 的实际设备兼容性仍待测试。
- 未穷举全部剧集、筛选组合、编码与特殊 MP4 布局；片源可用性随上游变化。
- 首页为热度、上新及榜单，不提供个性化推荐。
- 收藏和历史仅在本机保存，各最多 200 部；清除数据或卸载会删除这些数据。
- 首播仍需整集下载并处理；没有边下载边播放、FFmpeg 兜底、后台播放或批量离线下载。

## 重跑

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

联网 UI 测试会使用真实内容并下载视频，需要可访问上游的网络。测试覆盖安装时，应先安装旧版，再用 adb install -r 安装新版；不要先卸载旧版。

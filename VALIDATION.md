# 0.6.2 官网会话保留与账号诊断

验证日期：2026-10-25（Asia/Shanghai）。versionName 0.6.2、versionCode 9。

- 用户在 0.6.1 提供实际结果：官网会话已经验证，但 App 资料没有有效业务身份。因此此前的官网解析修复不能等同于个人手机云账号接通，本次发布不宣称该问题已经解决。
- 网站与 App 来源分别验证；仅用户明确选择 App 登录态导入时跳过官网产品校验，仍严格要求有效且非设备/安装身份的业务 ID。没有使用 Passport UID、昵称、内部请求 ID 或 `code=0` 替代授权。
- 业务资料失败后加密保留已验证官网会话，支持显式重读、退出和重启恢复；没有账号所有者，不启动云片单或写入。新过期候选不能误删已保存的另一会话，迟到回调不能在退出后恢复会话；成功后清除旧失败诊断。
- 诊断只保留固定分类、白名单字段类型、布尔标志与严格数字网关状态，不保存身份值或服务端原文；只有主动点击才写入剪贴板。未添加猜测的 Cookie-to-token 转换、自动枚举登录接口或业务账号初始化写操作。
- 真实匿名只读对照再次确认：某些接口在无登录态时仍返回 `code=0`，不能作为鉴权探针；官网的通用 SDK 具备某项能力也不代表本产品有使用权限。证据在上级 `qa/account-062-anonymous-evidence-20261025.json`、`account-auth-cross-aid-evidence-20261025.md`。
- 最终构建、协议/状态/旧格式兼容回归与 Lint 通过；Android 16 模拟器验证网站会话恢复、云权限关闭、导入来源切换清空输入、显式退出，以及原有账号回执、加密保存、页面、片单与播放交互。证据为上级 `qa/build-062-final.log`、`device-062-website-session.log`、`device-062-final.log`。
- 从实际发布的 0.6.1 覆盖升级后，设备身份、设置、本机收藏与历史文件逐字节保持不变，记录为上级 `qa/upgrade-061-to-062-before.txt` / `upgrade-061-to-062-after.txt`。APK 元数据和签名与既有升级规则一致。

手机云账号互通仍需要用户本人失败响应的脱敏结构证据；本次包提供获取该证据及避免重复发码的路径。

# 0.6.1 登录验证兼容修复

验证日期：2026-10-25（Asia/Shanghai）。versionName 0.6.1、versionCode 8，包名及最低系统要求保持不变。

- 本次依据用户反馈定位到三种登录共同的后置校验。官网当前允许 `has_login=true/1` 而不返回数字 UID，也支持外层状态及 `data.user`；原实现强制数字官网 UID 并判断未有官方语义依据的标志，会拒绝官网支持的响应。已修正，并继续要求 App 返回有效业务身份。
- 对照官网当前账号组件与 Passport SDK 核对实际路径、状态解析和 CSRF 默认令牌回退；资料请求与网页登录使用同一桌面 UA。注册设备 Cookie 按原域、根路径与白名单筛选，单独加密保存，仅用于 App 请求，`install_id` 与已保存 `iid` 一致。旧加密凭据格式保持兼容。
- 构建、协议与状态回归、Lint 已通过。定向覆盖无公开 UID、外层/嵌套响应、匿名与异常信号、真实业务 ID 约束、CSRF、Cookie 作用域、设备身份复用、失败阶段及原始异常脱敏。
- Android 16 模拟器验证跨进程一次性登录回执、官网来源限制、包含新增设备 Cookie 的真实 Keystore 加密读写与篡改拒绝、登录入口及原有本机片单、设置和播放操作；匿名二维码和设备注册探测通过，未发送短信或操作个人账号。
- 以已发布的 0.6.0 APK 覆盖安装本次构建，升级前后 `installation.xml`、`hongguo_local.xml`、`hongguo_library.xml` 完全一致，未卸载或清除用户数据。
- 具体证据位于上级 `qa/build-061-final.log`、`device-061-final.log`、`upgrade-060-to-061-before.txt`、`upgrade-060-to-061-after.txt` 与 `account-auth-official-evidence-20261025.md`；对应 JVM XML 与 Lint 报告位于本地构建目录。
- 这些结果证明已修复的契约差异及回归范围；没有取得用户真实登录成功响应，不能据此确认此次手机登录已恢复。需本人升级后重新登录并核对身份与片单；如仍失败，新提示会区分官网会话、设备初始化及 App 资料校验。

# 0.6.0 个人账号本地测试版

验证日期：2026-10-24（Asia/Shanghai）。源码与本地 APK 为 versionName 0.6.0、versionCode 7，包名 `com.hongguo.android`，minSdk 26。GitHub 已发布版本和更新清单仍是 0.5.1。

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` 通过。147 项 JVM 测试全部通过；Lint 为 0 errors、41 warnings、1 hint，没有新增 baseline 或忽略错误。
- 账号协议测试覆盖官网与 App 资料双校验、匿名/空会话拒绝、19 位 ID、设备身份复用、响应大小与错误结构、同源 Cookie、禁止跨域重定向和写入自动重放、取消及签名固定向量。
- 状态测试覆盖账号恢复与断网、迟到磁盘读取/登录结果、账号切换、登录持久化失败、收藏回读不确定与旧读取保护、进度观测的 IO 持久化/15 秒节流/强制刷新、旧确认保护新进度、退出写入失败的加密文件擦除后备。
- Android 16 / API 36.1 模拟器完整同批通过 14 项设备测试：AccountSecurityDeviceTest 3 项、AccountUiFlowTest 1 项、AccountAnonymousProbeTest 2 项、MyPageFlowTest 1 项、LibraryFlowTest 1 项、PlayerControlsTest 5 项、VerticalPlayerFlowTest 1 项。
- 设备上验证真实 Keystore AES-GCM 读写、密文无明文凭据、篡改拒绝、跨进程一次性登录回执、官网顶层 HTTPS 来源限制、账号入口/无效手机号禁止发送、本机记录保持，以及原有控制与真实媒体的首帧、暂停、拖动、重建、上下划、自动下一集与选集。
- 匿名联网探测已取得可解码的官网二维码，并确认未扫码不能视为已认证；匿名设备注册返回有效设备及安装身份。没有发送短信、扫描确认二维码或登录任何个人账号。
- 手机官网页面实测已出现官方“登录红果短剧”对话框、验证码登录、获取验证码、登录/注册及扫码区域。修复了 Macintosh 桌面 UA 在触屏环境被官网当作 iPad、从而不挂载 PC 登录组件的问题，改用 Windows 桌面 UA；未注入平台/触屏属性，未发送短信或提交登录表单。XML 证据为上级 `qa/hongguo-060-official-login-windows.xml`。
- 真实切集测试首轮出现一次第二集准备失败，单独重试及随后完整 14 项同批回归均通过。未发现预加载取消向新请求传播的确定代码问题；保留该网络/上游瞬态记录，不将其解释为全部片源稳定性证明。
- APK 包名、versionCode/name、API 26 和签名证书已核对，证书 SHA-256 与 `release-signing.sha256` 一致；可以覆盖既有同签名安装。本次只提供本地测试包和对应源码，不替换 GitHub Release 或现有 `dist/update.json`。
- 个人账号及跨设备写回仍待本人验收：实际短信/官网授权、昵称与 UID 核对、收藏增删、手机片单回读、云端集号和毫秒位置。账户历史仅最近 50 部，预约各 20 部，收藏分页未宣称完整。实体手机及 Android 8–15 尚未验收。

最终交付 APK 重新安装后，14 项设备用例在同批再次全部通过（31.915 秒）；147 项 JVM 用例仍全部通过。主要证据：`build-060-final-package.log`、`device-060-delivery.log`、JVM XML 和 Lint 报告；界面及匿名网页登录证据在上级 `qa/`。

# 0.5.1 验证记录

验证日期：2026-10-24（Asia/Shanghai）。versionName 0.5.1、versionCode 6，包名 `com.hongguo.android`、minSdk 26，沿用既有签名证书。

- 最终升版后 assembleDebug、assembleDebugAndroidTest、107 项 JVM 测试通过；Lint 为 0 errors、30 warnings、1 hint。
- 最终 APK 在 Android 16 独立模拟器通过 12 项设备测试：5 项进度条控件交互、7 项更新安装包拒绝边界。倍速、暂停、拖动、Activity 重建、跨集和连播的真实上游回归在本次功能开发阶段通过，见下方记录。
- APK 元数据与签名已核对：versionCode 6 / versionName 0.5.1 / API 26，证书 SHA-256 与 `release-signing.sha256` 完全相同。
- GitHub Latest 已发布为正式 `v0.5.1`（非草稿、非 prerelease），对应源码提交 `2011b52`。APK、完整源码 ZIP、update.json、SHA256SUMS-0.5.1.txt 四份附件的 GitHub digest 和大小均与本地一致；源码 ZIP 与标签提交的源码一致，无密钥、本机配置或 Git 内部目录。
- GitHub 直连、gh-proxy.com、ghfast.top 三路匿名 latest 清单均为 versionCode 6 / versionName 0.5.1，753 字节，与本地清单逐字节一致。三路完整 APK 均为 15,360,111 字节，SHA-256 为 `a52ea4e53edda4631e3fa52633a6406c1d72d1c4232ed1f334c5835e8cdc68b3`，全部通过；本次完整 APK 获取时间依次约 5.38、9.34、5.87 秒，仅代表此次网络。
- 模拟器保留数据并覆盖装回真实已发布的 0.5.0；旧版从“我的 → 检查更新”发现 0.5.1，经加速线路 1 下载并通过应用校验，设备缓存包 SHA-256 与公开发布包完全相同。经 Android 系统更新确认、Play Protect 扫描显示安全并继续安装后，安装器显示“App updated”，PackageManager 确认为 code 6 / 0.5.1。
- 实际覆盖安装前后 `installation.xml`、`hongguo_local.xml`、`hongguo_library.xml` 的 SHA-256 完全一致，设备身份、收藏、历史与续播设置保持不变。测试数据包含 1 部收藏、1 部历史及非零集内续播位置；未卸载旧版或清除数据。
- 更新后在“我的”再次经加速线路 1 检查更新，显示“当前已是最新版本（0.5.1）”。截图及三路下载、安装包哈希与数据保留记录保存在项目上级 `qa/` 下。本轮升级验证为 Android 16 模拟器，未将其等同于实体手机验证。

# 播放页优化验证（升版前本地测试）

验证日期：2026-10-24（Asia/Shanghai）。保留 0.5.0 / versionCode 5，仅生成本地测试包，未更新 GitHub Release 或在线更新清单。

- 进度条改为统一绘制细轨道、缓冲进度、已播放进度和圆形滑块，触摸高度 48 dp；拖动时显示目标时间，松手立即同步播放器位置。倍速提供 0.5×、0.75×、1×、1.25×、1.5×、2×，在本机保存并跨剧集与播放器重建生效。
- assembleDebug、assembleDebugAndroidTest、107 项 JVM 测试通过；Lint 为 0 errors、30 warnings、1 hint，未增加警告。
- Android 16 独立模拟器的 5 项 PlayerControlsTest 全部通过：真实轨道首尾点击、无障碍 SetProgress、禁用控件、取消拖动后释放交互及再次拖动。端点测试使用实际布局坐标，避免误点 Material3 向两侧各扩出的 10 dp 无障碍语义边界。
- VerticalPlayerFlowTest 在同一独立模拟器复测通过：暂停时从 UI 选择 1.5×，实际 ExoPlayer 保持同一实例、位置、暂停状态与原始音调；拖动进度不误切集；Activity 重建恢复位置、暂停及倍率；新 ViewModel 读取已保存倍率；上下滑、自动连播、选集均继承倍率，始终至多一个播放器，离开播放页后释放。
- 测试中发生过上游准备失败，完整流程单独复测通过，不据此保证上游持续可用。旧模拟器仅剩约 387 MiB 时系统删除了活动媒体缓存，导致另一次跳转失败；最终验证使用新建 12 GiB 数据分区、约 10 GiB 可用空间的独立模拟器，未为此更改媒体缓存代码。
- 截图人工检查进度轨道、滑块及倍速面板正常，文件位于项目上级 `qa/hongguo-player-controls.png`、`qa/hongguo-player-speed-sheet.png`。本轮仅验证模拟器，未验证实体手机。
- 本地测试包：`../dist/hongguo-android-player-controls-test.apk`，15,716,754 字节，SHA-256 为 `25ef01f3a24a390c41c10d50807d67d1f44b069c2cb1643b340942275ceb3055`。签名证书与 `release-signing.sha256` 一致，可用于同签名本地覆盖测试。

# 0.5.0 验证记录

验证日期：2026-10-23（Asia/Shanghai）。versionName 0.5.0、versionCode 5，包名及签名沿用此前测试版。

- assembleDebug、assembleDebugAndroidTest 成功；107 项 JVM 测试全部通过，Lint 为 0 errors、30 warnings、1 hint。
- Android 16 模拟器共 16 项设备用例通过：发现页、收藏历史、真实搜索选集播放续播、全屏上下滑集、我的及设置各 1 项，渐进播放 3 项、安装包拒绝边界 7 项、代理线路 UI 集成 1 项。媒体兼容性修复后，重新安装构建并重跑真实播放、上下滑集和渐进播放 5 项，全部通过。
- ProgressivePlaybackTest 用设备 AVC 编码器生成视频，经生产渐进会话和真实 Media3 解码器，在受控限速下证明首帧出现时仍有未下载区块；拖到后段会获取缺失区块并继续解码。另覆盖 DataSpec/EOF、单个 reader 取消及会话复用、旧播放器读取取消后新 source 仍可使用。
- StandaloneFlowTest 删除首集完整缓存，强制真实上游首集使用渐进会话并渲染首帧；通过 Activity 重建恢复、第二集播放、进度拖动、重播、至少 4 秒的历史续播与收藏。未使用自建服务器或替代视频。
- 实网测试修复了对标准 roll/prol 恢复分组的过度拒绝；它们原样保留，加密覆盖 seig 及未知分组仍拒绝。新增两项 JVM 回归覆盖分组保留及拒绝边界。独立真实片源探针在 10,163,913 字节媒体中取得 1,251,017 字节即完成渐进准备，尚未下载整集；这只代表该次片源。
- VerticalPlayerFlowTest 通过首集边界拖动、短拖动后保持原暂停状态，上下滑、播完自动下一集、选集跳转和返回详情；同时校验最多绑定一个播放器，旧播放器释放。
- MyPageFlowTest 通过底部“我的”进入历史并返回；三个设置开关持久化并由新 ViewModel 恢复；更新从“我的”进入，首页无更新按钮，底部无独立历史导航。截图检查“我的”、系统设置页面布局正常。

## 0.5.0 发布与实际覆盖更新

- GitHub Latest 为正式 v0.5.0，对应源码提交 802fe0a；APK、对应完整源码 ZIP、update.json、SHA256SUMS-0.5.0.txt 四个资产的大小和 GitHub digest 均匹配本地。
- GitHub 直连与 gh-proxy.com、ghfast.top 三路匿名 latest 清单均为 code 5、786 字节，与本地清单逐字节一致；三路完整 APK 均为 15,702,481 字节，SHA-256 为 faa1d8ebbc5a77003c6128285790505c09a25f3aa043d10f73c4a99ae215ef63。
- 本次网络中，三路完整 APK 首次请求均成功，耗时依次为 12.30、7.47、8.57 秒。ghfast.top 的清单经同域根相对跳转取得，客户端支持该路径。时间只代表本次测试网络，公共代理不能保证持续可用。
- 模拟器保留数据并装回真实已发布的 0.4.0；在旧版更新窗口选择加速线路 1，实际下载公开 0.5.0，应用私有缓存包的 SHA-256 与发布包一致。
- 经系统更新确认、Play 保护机制扫描和继续安装，安装器显示“应用已更新”，PackageManager 确认为 versionCode 5 / versionName 0.5.0。安装前后 installation.xml、hongguo_local.xml、hongguo_library.xml 的 SHA-256 完全一致，设备身份、续播设置、收藏和历史均保留。
- 打开已安装版本，“我的”可进入原历史并继续真实视频；在“我的”检查更新，经加速线路 1 返回“当前已是最新版本（0.5.0）”。全屏画面、选集入口与进度布局截图检查正常。

## 播放与缓存范围

常见非碎片 CENC MP4 使用 Range 取块与按需解密，媒体不支持该路径时走完整缓存兼容回退。下一集预热仅在前台、开关开启、当前首帧已渲染且缓冲至少 5 秒时启动；索引与样本探测之外，额外预热最多 512 KiB 的文件前段。渐进缓存与完整缓存各有独立 512 MiB 额度，会话关闭会清除渐进缓存。本轮未测量所有网络下的流量与缓存峰值。

尚未在实体手机或 Android 8–15 设备验证，也未穷举全部编码与封装。下方保留旧版本当时的验证结果。

# 0.4.0 验证记录

验证日期：2026-10-23（Asia/Shanghai）。versionCode 4，包名及签名沿用此前测试版。

- assembleDebug 成功；96 项 JVM 测试全部通过，Lint 为 0 errors、27 warnings、1 hint。
- 新增自动线路顺序、指定单线路、HTTP/超时/HTML/错误清单后的回退、取消后禁止回退、每条线路独立临时文件与清理、错误 SHA-256 回退、APK 身份错误终止、低速停滞判断、代理白名单和嵌套目标边界验证。
- 代理实测只访问公开 GitHub 发布资源，不携带凭据。两代理均取得与 GitHub 原件一致的 0.3.0 更新清单，并完整下载 15,489,913 字节 APK；SHA-256 均为 a3d75d7d6c84e190dab68317638ca1be75d3f0c18b0ad7de95eb84d914c80bb6。
- 模拟器中真实 UI 选中加速线路 1、线路 2 并分别检查更新成功；选择持久化后，新 UpdateViewModel 实例能恢复线路，最后恢复测试前偏好。
- 11 项设备测试整组通过：发现页、收藏历史、真实搜索选集播放续播各 1 项，安装包拒绝边界 7 项，代理线路 UI 集成 1 项。
- 设备测试定位并修复了 Android URI.resolve 对代理路径中嵌入 https:// 的斜杠处理：共享跳转解析保留代理根相对 Location 的原始路径，随后重新检查 HTTPS、主机白名单与内嵌 GitHub Release 目标。实际 ghfast.top latest 的 302 跳转现已验证可读。

## 0.4.0 发布后的真实加速升级

- GitHub Latest 为 v0.4.0，对应应用源码提交 5d5dbcd；四个发布资产上传后均核对 GitHub 的 sha256 digest 与大小，公开清单为 versionCode 4。
- 两代理 latest 清单首次请求即返回版本 4，与本地发布清单逐字节一致。gh-proxy.com 为 HTTP 200，无跳转；ghfast.top 经一次同域根相对 302 跳转后 HTTP 200。
- 两代理均在单次匿名 GET 内完整返回本版 APK，无需续传：gh-proxy.com 约 13.10 秒，ghfast.top 约 9.47 秒。这些时间仅代表本次测试网络。
- APK 大小均为 15,520,706 字节，SHA-256 均为 bd12e246323d4a5708d89b87131c30b7934acfd6d5f76ccf3ff22c6c7d7a63d8，与 GitHub 和本地发布包一致。
- 模拟器安装仅供测试的同代码低版本号包（code 3 / 0.3.0-proxy-qa，不对外发布），在 UI 指定加速线路 1，实际下载公开的 0.4.0。应用校验通过，设备缓存包 SHA 与发布包一致；经系统安装确认和扫描后，安装器显示“应用已更新”，PackageManager 确认 code 4 / 0.4.0。
- 覆盖安装前后 installation.xml、hongguo_local.xml、hongguo_library.xml 的 SHA-256 完全相同，设备身份、续播、收藏和历史均保留。

公共代理可用性取决于网络及服务状态，不能保证任何网络下均可下载；应用保留直连与另一条代理作为选择。尚未在实体手机或 Android 8–15 设备验证。

# 0.3.0 验证记录

验证日期：2026-10-23（Asia/Shanghai）。包名 com.hongguo.android，versionCode 3；Android 8.0 及以上。

- assembleDebug 成功，APK 与此前 0.1/0.2 测试版使用相同证书，指纹见 release-signing.sha256。
- JVM 测试 72/72 通过：保留此前 47 项，新增清单模型 11、匿名 HTTP 6、更新包文件与地址边界 8 项。
- Lint：0 errors、23 warnings、1 hint。
- Android 16 模拟器真实内容回归：DiscoveryFlowTest、LibraryFlowTest、StandaloneFlowTest 全部通过，覆盖推荐、分类、搜索、选集、实际首帧、续播和收藏历史。
- 更新元数据测试覆盖整数版本比较、应用 ID、同仓库 APK 地址、HTTPS 跳转主机限制、128 KiB 上限、损坏 UTF-8、超时和不泄露地址的错误信息。
- 文件测试覆盖大小、ZIP 头和 SHA-256 校验、篡改、取消及异常下载地址。
- UpdateInstallerFlowTest：7/7 通过。使用设备已安装 APK，验证错误版本、错误包名、同长度篡改、截断、错误头、未经登记的文件和目录边界均被拒绝。两项安装权限用例由宿主 adb 在测试前允许、测试后恢复；测试进程不修改安装 app-op。

## GitHub 在线更新实测

发布 tag：v0.3.0，对应应用源码提交 32c6b57446ec0719d435c59ef3ee085d7ab3103b。

- 公开的 latest/download/update.json 可匿名取得；清单指向固定 v0.3.0 APK，匿名下载后的大小为 15,489,913 字节，SHA-256 为 a3d75d7d6c84e190dab68317638ca1be75d3f0c18b0ad7de95eb84d914c80bb6，与本地 APK 及 GitHub 上传资产 digest 一致。
- APK、对应完整源码 ZIP、update.json、SHA256SUMS-0.3.0.txt 四个资产均核对大小与 GitHub sha256 digest 后发布，release 为 Latest、非草稿、非 prerelease。
- 先保留旧版数据，以相同更新代码构建仅供 QA 的 versionCode=2 / versionName=0.2.0-update-qa；该包不作为发布资产。普通 0.2.0-test 本身没有更新器，需要先手动安装 0.3.0。
- Android 16 模拟器内手动检查实际 GitHub 清单，显示 0.3.0、更新说明与包大小；实际下载显示进度。中途取消显示已取消，cache/updates 不残留临时包；随后重试并完成真实公开 APK 下载与校验。
- 首次安装进入系统来源权限页面，授权返回后打开系统安装确认。经设备的 Play 保护机制扫描并继续安装，安装器显示“应用已更新”；PackageManager 实际版本为 code 3 / name 0.3.0。
- 系统安装前后 installation.xml、hongguo_local.xml、hongguo_library.xml 的 SHA-256 完全一致，设备身份、续播和收藏历史数据保留。
- 打开已安装的 0.3.0 再检查实际清单，显示“当前已是最新版本（0.3.0）”。

实体手机以及 Android 8–15 设备兼容性尚未验证。GitHub 和上游片源的网络可达性取决于用户网络。

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
- 0.2.0-test 首播仍需整集下载并处理，当时没有边下载边播放、FFmpeg 兜底、后台播放或批量离线下载；0.5.0 的渐进播放范围见文首。

## 重跑

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

联网 UI 测试会使用真实内容并下载视频，需要可访问上游的网络。测试覆盖安装时，应先安装旧版，再用 adb install -r 安装新版；不要先卸载旧版。

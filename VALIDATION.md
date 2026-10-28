# 0.7.7 简约红果图标、播放切集与页面过渡动画

验证日期：2026-10-28（Asia/Shanghai）。versionName 0.7.7、versionCode 18。

- 图标：启动图标换为简约红果（米白底、红果 + 播放键 + 叶片），改为自适应图标 `mipmap-anydpi/ic_launcher(_round).xml`（背景 / 前景 / 单色三层，Android 13 起支持主题图标），清单改用 `@mipmap/ic_launcher` 并补 `roundIcon`；旧的整幅 `drawable/ic_launcher.xml` 不再使用，已删除。首页标题旁的「果」字标改为同源矢量 `ic_logo.xml`，README 顶部加入 logo。Robolectric 确认安装包图标解析为 `AdaptiveIconDrawable`，首页标题 logo 显示正常；遮罩形状（圆形 / 圆角方形）由各品牌桌面决定。
- 播放页（`EpisodePlayerScreen.kt` / `VideoPlayer.kt`）：此前拖动一开始就释放当前播放器，0.7.6 去掉背景封面后上下滑动只剩黑屏，看不出切换。现在拖动期间保留当前画面随页面滑动，拖动开始即以「手势暂停」暂停（不改用户的播放/暂停意图、不切换状态文字、不强制上报进度），滑回本集继续播放，滑到其他集在停稳后释放；相邻页显示「第 N 集」。自动连播与选集面板跳转改为 320 ms 滑动切换，切换期间暂停用户滑动，避免中途被触摸打断落到错误的集；首帧渲染后画面淡入（仅在绘制层读取动画值）。
- 页面过渡（`MainActivity.kt`）：详情、搜索、账号页从右侧滑入、返回滑出；Tab 间淡入淡出；播放器打开上滑出现、关闭淡出。离场页面按键值绘制自己的状态（离场详情保留原剧集，历史/收藏各自按自身 Tab 绘制），离场期间不接收触摸，搜索、账号与我的页的返回处理也随之停用；播放器开始关闭即交还系统栏与前台状态，关闭途中再次打开会重新接管。
- 其他：经典底栏选中颜色与标记动画（标记在绘制层读取）；暖白片单与沉浸「继续看」删除条目时其余条目平滑移动。
- 独立代码审查发现并已修复：模型驱动滑动可被触摸打断后落到相邻集；关闭途中重开播放器时系统栏沿用主题样式；离场的历史页绘制成收藏；离场搜索/账号页抢先处理返回键；拖动暂停经用户意图路径导致每次滑动强制上报进度；沉浸追剧行重新分组时的残影；动画值在组合阶段读取导致逐帧重组。
- 云端构建（与发布签名无关）：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过，单元测试 241 项，Lint 无错误、无新增警告。Robolectric 逐帧检查：详情进入与返回、Tab 淡入淡出、播放器关闭、自动连播与远距离选集的滑动均有中间帧；关闭途中重开播放器后系统栏为浅色图标，关闭后恢复主题样式。
- 已知限制与待补充：未在实体手机上检查真实视频拖动时 SurfaceView 跟随滑动的流畅度；自动连播与选集跳转时上一集画面在切换开始即消失（以集数占位滑动），新一集在滑动结束后开始准备；`connectedDebugAndroidTest` 待发布机器补充。

# 0.7.6 底栏样式（经典 / 悬浮玻璃）与播放页去掉背景封面

验证日期：2026-10-28（Asia/Shanghai）。versionName 0.7.6、versionCode 17。本版内容原计划作为 0.7.5.1 发布，未单独发布，与播放页改动合并为 0.7.6。

- 播放页（issue #1）：去掉视频后方 24% 透明度的剧集封面（`EpisodePlayerScreen.kt`；横屏视频两侧原会露出封面），加载、缓冲、切集和出错时为纯黑背景；视频画面、手势、控件与竖屏系统栏处理不变。
- 功能：「我的 › 设置 › 外观」新增「底栏样式」，经典 / 悬浮玻璃二选一，点选即时生效并保存（`nav_bar_style`）。未选择过时按设备给默认值：澎湃OS（系统属性 `ro.mi.os.version.code` 存在，覆盖小米、红米、POCO）默认悬浮玻璃，其他手机默认经典。MIUI 不带该属性，按经典处理。
- 悬浮玻璃实现（`HyperOsNavigation.kt`，纯 Compose，无新增依赖）：页面录制到 GraphicsLayer，胶囊在自身区域重绘该层。Android 13+ 先模糊再经 AGSL 圆角矩形透镜在边缘折射（含轻微色散），Android 12 仅模糊，Android 8–11 退化为接近不透明的磨砂底。另有高光描边、投影，以及在 Tab 间弹性滑动的选中胶囊。页面延伸到胶囊下方，首页、发现/剧场、片单（本机与云端）、我的、设置、主题样式的列表底部加入胶囊高度留白，沉浸推荐流的剧集信息同步上移；经典底栏时留白为 0，布局与 0.7.5 相同。
- 依赖：曾试用 Miuix 0.7.2 的底栏组件，因其胶囊底栏无玻璃效果、带玻璃效果的新版要求 Kotlin 2.4 / Compose 1.12，最终未引入任何新依赖。
- 云端构建（与发布签名无关）：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过。新增 `SystemRomTest`（澎湃OS 判定、默认底栏与已保存选择优先）。
- 界面检查（Robolectric 硬件 PixelCopy，非真机）：三套主题下悬浮玻璃胶囊在彩色海报内容上的模糊、高光与选中胶囊；关闭折射对照渲染确认 AGSL 透镜生效（差异仅在胶囊区域）；Android 12（仅模糊）与 Android 11（磨砂实底）降级路径；饱和色背景下未选中文字对比度经调整后清晰。Tab 测试标签（`nav_home` 等）、选中语义及「片单 → 历史」映射保持不变。设置项在三套主题下显示正常。
- 发布前复核：云端重新应用补丁并加入播放页改动后构建、单元测试 241 项与 Lint 通过（无错误、无新增警告；`AppNavigation` 的 `modifier` 参数调整为首个可选参数）；README 中 v0.7.5.1 源码包与 SHA-256 校验文件链接改为 0.7.5.1 的文件名（0.7.5 README 的校验文件链接误指 0.7.4，一并更正）。
- 已知限制：未在实体手机上检查滚动时玻璃的流畅度、三键导航模式下胶囊位置；低端机实时模糊的帧率未测量。

# 0.7.5 竖屏播放不再全屏

验证日期：2026-10-27（Asia/Shanghai）。versionName 0.7.5、versionCode 16。

- 问题：自最早的竖屏播放实现起，播放页打开时不分方向隐藏状态栏与导航栏（`hide(systemBars)`），竖屏看剧也被系统当作全屏：可能出现“正在全屏查看”提示，时间、电量和手势条不可见，部分系统收起悬浮通知，下拉通知栏与返回手势需先唤出系统栏。
- 修复：`EpisodePlayerScreen.kt` 改为按方向处理（`playerHidesSystemBars`）：竖屏显示系统栏，横屏才隐藏；回到前台、旋转和关闭选集/倍速/收藏面板后按当前方向重新应用。播放期间系统栏为透明背景、白色图标（含 Android 8–9 与三键导航的导航栏），关闭播放页后由当前主题重新接管。顶部控件原本就按状态栏忽略可见性的安全区排布，位置不变；底部控件按导航栏留出空间。
- 云端构建（与发布签名无关）：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过，结果见下一条。新增 `PlayerSystemBarsTest`（竖屏不隐藏、仅横屏全屏）。Robolectric 中打开播放页逐步核对窗口请求的系统栏状态：竖屏可见且为浅色图标，横屏隐藏，转回竖屏重新可见，关闭播放页后恢复暖白主题的深色图标。
- 待发布机器补充：原签名构建与指纹核对、`connectedDebugAndroidTest`，以及实体手机竖屏/横屏观看检查。

# 0.7.4 三套主题各自的页面布局

验证日期：2026-10-27（Asia/Shanghai）。versionName 0.7.4、versionCode 15。

- 背景：0.7.3 的三套主题只替换配色，页面结构相同，用户反馈“选择后还是旧样式”。本版让首页、分类/剧场、片单、详情、我的与底栏按主题使用不同布局；播放器、设置、主题样式、账号与更新弹窗仍为共用页面。
- 范围：暖白精修保留 0.7.2/0.7.3 结构并新增今日推荐位、继续观看横滑与三列封面（大字号或窄屏回退两列）；沉浸暗场新增 `ImmersiveScreens.kt`（竖屏上下滑推荐流、剧场热播榜与筛选面板、追剧页、沉浸式详情）；内容卡片新增 `CardsScreens.kt`（频道切换、热度榜、双列瀑布流、带简介的发现列表与快捷筛选、三列进度网格片单、卡片式详情、搜索页“大家都在看”）。新增 `playDrama`：从推荐流、榜单与列表直接播放上次看到的集数。0.7.3 用户升级后会再看到一次主题选择（`theme_onboarding_version` = 2，已选主题预先选中）。
- 云端构建（与发布签名无关）：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过，结果见下一条。
- 界面检查（Robolectric 模拟，非真机）：三套主题下首页、发现/剧场、片单、我的、详情、搜索共 18 屏截图并排比对，布局各不相同，未见重叠或裁切（内容卡片详情标题与头图重叠已在截图检查中修正）。新增 `ThemeOnboardingFlowTest.answerFrom073IsAskedAgainOnceWithItsChoicePreselected`。
- 独立代码审查后修复：筛选面板未点“查看结果”就关闭时丢弃未应用的选择，避免“加载更多”、重试与快捷筛选被隐藏草稿卡住；沉浸推荐流末页显示加载失败重试或“已经看到最后一部了”，并新增刷新按钮；推荐页右侧操作栏并入底部信息区，矮屏/横屏时隐藏简介与提示，避免重叠；追剧页只有收藏时也能进入管理，列表清空后自动退出管理；从“立即播放”进入后详情读取失败再重试仍会自动播放；删除角标、来源切换、选集区间与卡片页签触控区域不小于 48dp；无剧集时播放按钮显示“暂无可播放剧集”；内容卡片详情标题随大字号向下扩展；沉浸“我的”打开主题样式后返回回到“我的”；暖白首页平板/横屏按宽度增加列数，推荐位文字区域加深遮罩。修复后重新执行构建、单元测试、Lint 与 18 屏截图检查。
- 已知限制：沉浸暗场推荐流为静态封面，不自动播放预览视频；三套主题共用同一播放器；冷启动系统启动窗口仍为浅色。
- 待发布机器补充：原签名构建与指纹核对、`connectedDebugAndroidTest`、从 0.7.3 覆盖安装后的数据保留，以及实体手机检查。

# 0.7.3 界面主题与首次启动主题选择

验证日期：2026-10-27（Asia/Shanghai）。versionName 0.7.3、versionCode 14。

- 范围：新增暖白精修 / 沉浸暗场 / 内容卡片三套主题；颜色改由主题调色板提供（`AppTheme.kt`），暖白精修数值与 0.7.2 一致。我的页新增齿轮与「设置」入口，设置新增 外观 › 主题样式；主题样式页点选即时生效并保存在 `hongguo_local`（`theme_style`、`theme_dark_follows_system`）。0.7.3 首次启动弹出主题选择，仅在弹窗内预览，确认或“暂不选择”/返回后写入 `theme_onboarding_done`，并以 Snackbar 提示可在设置中切换；启动更新提示在主题选择完成前只延迟显示，其生命周期回调照常运行。播放取链与 0.7.2 相同。
- 云端构建（与发布签名无关）：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过，单元测试 235 项全部通过，Lint 无错误、无新增警告。新增 `AppThemeStyleTest`：持久化键稳定、未知值回退暖白、只有沉浸暗场为深色、暖白关键色值与 0.7.2 一致，三套主题正文、次要文字、按钮文字、强调文字与提示条对比度均不低于 4.5:1。
- 界面流程（Robolectric 模拟，非真机，在代码审查修复前运行）：`ThemeOnboardingFlowTest`（选择并保存、不再出现；返回键保留默认）、`ThemeSettingsFlowTest`（设置入口、三选一、深色跟随开关、重建 ViewModel 后保持）与原有 `MyPageFlowTest` 分别单独运行通过；三套主题下首页、发现、我的、设置、主题样式和首次选择弹窗已截图检查。同一进程连续运行多个 Robolectric 用例时存在用例间状态串扰，原有 `NavigationFlowTest` 在未修改的 0.7.2 上同样失败，不计入本版结论。
- 独立代码审查后修复：播放页内旋转后返回时系统栏图标颜色与主题不符（播放期间主题不接管系统栏，关闭后重新应用）；系统栏改经 enableEdgeToEdge 按主题设置，使 Android 8–9 导航栏底色同样跟随主题；“可在设置中切换”提示保存在 ViewModel，等更新弹窗关闭后显示，旋转不丢失；账号页筛选标签、云片单标签与更新弹窗版本标签在深色主题下改用高对比强调色。修复后重新执行构建、单元测试与 Lint 通过，界面流程未重跑。
- 有意变化与已知限制：设置开关关闭态的滑块与描边改用次要文字色，三套主题（含暖白精修）都更易辨认，是相对 0.7.2 唯一的可见差异；冷启动时系统启动窗口仍为浅色（themes.xml），沉浸暗场用户可能看到短暂浅色闪屏。
- 待发布机器补充：原签名构建与指纹核对、`connectedDebugAndroidTest`（含新增两项，测试 runner 改为 `HongguoTestRunner` 以便既有用例跳过首次选择）、从 0.7.2 覆盖安装后本机数据保留与首次主题选择、深色模式自动切换，以及实体手机检查。

# 0.7.2 播放取链恢复

验证日期：2026-10-27（Asia/Shanghai）。versionName 0.7.2、versionCode 13。

- 背景：上游播放接口 `multi_video_model/v1` 自 2026-10-27 起拒绝每次随机合成的 iid/device_id，并不再接受签名请求，返回 HTTP 200 + 0 字节。0.7.1 的取链在同日实网对照中失败（“App 接口未返回播放数据”）。
- 修复同步自 tsukiyo-zero/hongguoTV `3889318`、jackotom/hongguo-drama 3.9 `5febca2`、sunnysky123/hongguo-web 1.1.5 `79babdf`：新增 `PlaybackDevice` 匿名注册（ttEncrypt），播放请求改用注册身份（device_id / iid / cdid）且不带签名头，UA 换为 `com.phoenix.read/71532`；优先使用内联 `video_model.video_list`，仅旧模型回退 `fallback_api`；内联片源声明加密却缺少 `spade_a` 时拒绝，不当作明文播放；空响应单独提示。目录、搜索与账号模块不变。
- 本地取舍：身份只存进程内存，与账号模块的注册设备隔离；成功注册由播放、预加载和重试共享；注册失败仅在 30 秒冷却内复用（上游 JS 实现缓存到重启），线程取消不计为失败。
- JVM 回归：新增 `PlaybackSourceTest` 17 项，覆盖 ttEncrypt 解密往返与 SHA-512 摘要、注册头无签名、19 位 ID 精确保留及非法值拒绝、并发共享一次注册、失败冷却与恢复、播放 URL 身份字段与 python quote 编码、播放头无签名、空响应提示、内联数组模型、清晰度选择、缺密钥拒绝、CDN 白名单与只取所请求分集。
- 发布机器本地构建（原签名密钥）：`:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 通过，单元测试 231 项全部通过，Lint 无错误；aapt2 核对包名、versionCode 13、versionName 0.7.2；`apksigner` 证书指纹与 `release-signing.sha256` 一致。证据为上级 `qa/release072/gradle-build.log`、`step1-summary.log`、`step1-result.json`。
- 实网对照（同一分集、同一时间）：0.7.1 原代码取链失败；0.7.2 取得 1080p 加密轨道。随后用推荐榜前 3 部剧各前 2 集走完整链路（取链 → 下载 → spade 解密 → 去加密信令）6/6 成功，ffprobe 均为 HEVC + AAC，时长 113–209 秒，其中一集完整解码无错误；渐进播放路径对 2 集实测首次读取在约 1.1–1.2 MB 已下载时即返回 `ftyp`，无需等待整集。所有取链复用同一进程内匿名身份，未逐集注册；身份、密钥与完整媒体 URL 未记录。
- Android 16 模拟器（API 36）：在已装 0.7.1 并有本机记录的设备上覆盖安装 0.7.2，versionCode 12 → 13，安装身份、片单、本机设置、更新设置等 10 个私有数据文件逐字节一致，记录在上级 `qa/release072/upgrade-071-to-072-before.txt` / `upgrade-071-to-072-after.txt`。随后 `StandaloneFlowTest`（真实上游搜索 → 选集 → 渐进播放并实际渲染首帧）、`ProgressivePlaybackTest`（3 项）与 `VerticalPlayerFlowTest` 共 5 项通过，证据为 `qa/release072/device-tests.log`。
- 范围与限制：云端实网对照在海外出口网络下进行，设备测试在本机模拟器完成；未覆盖实体手机及 Android 8–15 全部版本。平台接口可能再次变化，本次样本成功不代表长期可用。

# 0.6.3 业务账号标识格式修复

验证日期：2026-10-25（Asia/Shanghai）。versionName 0.6.3、versionCode 10。

- 本人实际脱敏诊断为 `BUSINESS_ID_INVALID`、`agw=1`、`biz_user_id:string`、`has_set_username=true`。这明确定位到本地数字格式限制，不能继续将该案例解释为资料缺失或会话必然不互通。没有索取或记录实际账号标识。
- 资料解析改为独立的有界不透明账号字符串规则，原样保留大小写、Unicode、前导零和合法符号；拒绝 JSON 对象/数组/布尔/浮点或原生负数被强制转成账号。新兼容格式要求同一份当前本人资料响应的正向网关状态；不回退其他 UID、昵称或设备身份。
- 同步修正账号资料、账号缓存键、登录和重启恢复，账号切换仍逐字精确比较；内容、设备与安装 ID 保持原数字规则。原始长整数的精度保护只处理正数，避免原生大负数被当作不透明字符串。
- 协议回归使用与真实诊断结构一致的合成账号，覆盖成功读取、授权未确认、来源门禁、Unicode/长度边界、无效类型、匿名/设备身份拒绝、存储往返、重启及旧播放器隔离。它验证已定位的代码缺陷，不等同于已经取得用户本人最终登录或片单成功结果。
- 最终构建、协议与状态回归、Lint 通过；Android 16 模拟器验证包含非数字账号 ID 的真实 Keystore 加密读写、官网会话保留/退出、登录入口及原有片单、设置和播放操作。证据为上级 `qa/build-063-final.log`、`device-063-final-account.log`、`device-063-final-website.log`、`device-063-final.log`。
- 从 0.6.2 覆盖升级，本机设备身份、设置、收藏与历史文件保持逐字节一致，记录在上级 `qa/upgrade-062-to-063-before.txt` / `upgrade-062-to-063-after.txt`。版本及签名与既有更新约定一致。

已保留的 0.6.2 官网会话可以在覆盖升级后直接重读资料。实际昵称、片单、收藏及跨设备进度继续由本人核对。

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

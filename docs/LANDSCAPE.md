# 横屏分栏

只影响浏览页：竖屏、播放器、悬浮玻璃底栏都不走这套代码。播放页本来就有自己的横屏全屏布局。

本文件随 PR 系列逐步补齐。骨架阶段（本 PR）只立容器与导航，页面仍是竖屏布局被拉宽；
后续每个 PR 接入一页，并在下面的表格里补上该页的左右栏内容。

## 结构

```
+------+---------------------------+---------------------------+
|      | 左列（固定可滚动）        | 右列（主内容）            |
| 导航 | 标题 / 筛选 / Tab         | 列表、网格或分区内容       |
| 栏   |                           |                           |
+------+---------------------------+---------------------------+
```

- 横屏时底部 Tab 栏竖到左侧（`AppNavigation.kt` 的 `AppNavigationRail`），页面内容只占剩下的宽度；
  悬浮玻璃底栏（`NavBarStyle.GLASS`）保持原样，不换成 rail。
- 页面左右分栏由 `WarmPanes.kt` 的 `WarmTwoPane` 负责：左列永远是可滚动的 `Column`，
  右列由页面决定是「自己滚的列表」还是「可滚动列」（`rightScroll = true`）。
- 竖屏时 `isWideLayout()` 为 false，页面保持原来的一整列，横屏分支整体绕开。

## 状态栏留白

0.8 的主界面会统一替页面让出状态栏：除了「详情页」和「沉浸暗场首页」，其余页面都由
`MainActivity` 的 `drawsUnderStatusBar` 判断加一条固定的上内边距，内容从状态栏下沿开始，
不会滚到状态栏下面。只有这两类页面自己压栏：

- 详情页（三主题共用 `CinemaDetailScreen`）：横屏自己算
  `val topPad = warmStatusBarTop() + 10.dp`，左栏用 `padding(top = topPad)`、右栏列表用
  `contentPadding.top = topPad`，两栏共用一个值；竖屏用列表顶部的 `statusTop` 与模糊头图；
- 沉浸暗场首页：整屏压栏，用 `statusBarsPadding()` 与渐变。

所以**普通横屏分栏页不要自己加顶部留白**——主界面已经让过一次，再叠会比竖屏还低一截
（「我的」「账号」两页早期就踩过这个坑）。`WarmTwoPane` 不再提供 `LocalWarmTopInset` /
`WarmTopInset()`；需要自己让白的压栏页面，用 `warmStatusBarTop()` 在页面里算一个 `topPad`，
左右两栏共用，不要一边用 Spacer、一边用 padding 各写一套。

深色主题的分栏要传 `dividerColor = Line`，暖白那条 `Muted.copy(alpha = .16f)` 在深色底上会发灰。

> 待办：0.7.33 那套「横屏内容压在透明状态栏下、留白随内容滚走」的行为，0.8 没有保留。
> 要恢复需要把 `MainActivity` 的固定留白改成按页面白名单（`isWideLayout() && key in ...`），
> 并给每个分栏页算好自己的 `topPad`；单列页（片单、设置）还要决定 header 是否跟着列表滚。

## 已接入的页面

| 页面 | 左列 | 右列 |
| --- | --- | --- |
| 榜单（`RankScreen`） | 返回与标题、榜单类型（综合 / 真人剧 / 漫剧 / AI 剧） | 榜单条目 |
| 搜索（`SearchScreen`） | 返回与标题、搜索框、联想词、大家都在看 | 搜索状态与结果列表 |
| 我的（`MyScreen`） | 账号卡（昵称/状态/收藏·看过·云片单·预约）与快捷数据（继续追剧、本机收藏/看过） | 设置菜单（外观与播放、账号与存储、检查更新、关于软件） |
| 本机收藏 / 播放历史（`LocalFavoritesScreen` / `PlayHistoryScreen`） | 不分栏 | 海报网格在横屏显示六列、竖屏显示三列 |

每接入一个页面，由对应的页面 PR 自己往这张表补一行；没合入的页面不会出现在这里。

## 0.8 的页面归属

0.8.0 把「三套主题各写一遍」的页面合并成了一份实现，横屏适配因此按页面拆，而不是按主题拆：

| 页面 | 0.8 里的入口 | 三主题共用 |
| --- | --- | --- |
| 剧集详情 | `CinemaDetailScreen`（`DetailScreen.kt`，`rootTag` 区分主题） | 是 |
| 追剧 / 片单 | `CinemaChaseScreen`（`LibraryScreen.kt`） | 是 |
| 我的 | `MyScreen`（`MyScreen.kt`） | 是 |
| 账号 | `AccountScreen` | 是 |
| 设置 / 主题样式 / 播放 / 账号 / 存储 / 关于 | `SystemSettingsScreen` 等 | 是 |
| 搜索 / 榜单 / 播放历史 / 本机收藏 | `SearchScreen` / `RankScreen` / `PlayHistoryScreen` / `LocalFavoritesScreen` | 是 |
| 首页 / 发现（暖白） | `HomeScreen` / `CategoryScreen`（`DiscoveryScreens.kt`） | 否 |
| 首页 / 发现（内容卡片） | `CardsHome` / `CardsDiscover`（`CardsScreens.kt`） | 否 |
| 首页 / 剧场（沉浸暗场） | `ImmersiveHome` / `ImmersiveTheater`（`ImmersiveScreens.kt`） | 否 |

设置类页面横屏暂不做分栏，保持单列即可。

## 加一个新分栏页

1. 页面里 `if (isWideLayout()) { WarmTwoPane(left = {...}, right = {...}); return }`，竖屏代码保持原样不动。
2. 横屏分支不要再自己加顶部留白：主界面已经让出状态栏，页面直接从栏下方开始（见上一节）；只有画到状态栏下面的页面才用 `warmStatusBarTop()` 算 `topPad`。
3. 测试用的 testTag 竖屏和横屏保持一致（`NavigationFlowTest` 等用例都按 tag 找控件）。
4. 竖屏回归一遍：横屏分支必须完全绕开竖屏布局。
5. 横屏分支里的区块优先复用竖屏同一批 composable（必要时把列表里的 `item {}` 抽成小 composable），不要复制一份布局。
6. 共用页（详情/片单/我的）的横屏一份服务三主题，评审时附三主题横屏截图。

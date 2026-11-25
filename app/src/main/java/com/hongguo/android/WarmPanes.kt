// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 横屏分栏的公共容器。
 *
 * 约定：
 * 1. 横屏时页面左右分栏，竖屏保持原来的一整列（所有分支都由 [isWideLayout] 判断）。
 * 2. 顶部留白由页面自己决定：0.8 的主界面已经替普通页面让出状态栏，分栏页不要再加一次；
 *    只有本来就画到状态栏下面的页面（目前是详情页）才需要自己算
 *    `val topPad = warmStatusBarTop() + 额外间距`，左右两栏共用这一个值。
 */

/** 浏览页在横屏时改成左右分栏，竖屏仍是原来的单列。 */
@Composable
internal fun isWideLayout(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 顶部留白按状态栏高度的这个比例取。
 * 1f = 完全让开状态栏；0.6f 是正在试的收紧版本，觉得挤就调回 1f。
 */
private const val WarmTopInsetScale = 0.6f

/** 画到状态栏下面的页面（目前只有详情页）用它算顶部留白；普通页面用主界面的固定留白。 */
@Composable
internal fun warmStatusBarTop(): Dp =
    if (isWideLayout()) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * WarmTopInsetScale else 0.dp

/**
 * 左列固定可滚动、右列由页面决定是否滚动的两栏布局。
 *
 * 左侧适合放标题、筛选、Tab 这类入口；右侧放主内容（网格或列表），
 * 需要滚动的右列用 `rightScroll = true`，列表自己会滚的就传 false。
 * [dividerColor] 默认是暖白的半透明灰；深色主题传 `Line`，否则分隔线在深色底上会发白。
 */
@Composable
internal fun WarmTwoPane(
    left: @Composable ColumnScope.() -> Unit,
    rightScroll: Boolean = false,
    dividerColor: Color = Muted.copy(alpha = .16f),
    right: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            left()
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(dividerColor))
        if (rightScroll) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                right()
            }
        } else {
            Box(Modifier.weight(1f).fillMaxHeight()) { right() }
        }
    }
}

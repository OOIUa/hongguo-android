// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * WARM 主题的横屏分栏。
 *
 * 约定只有两条：
 * 1. 横屏时页面左右分栏，竖屏保持原来的一整列（所有分支都由 [isWideLayout] 判断）。
 * 2. 状态栏是透明浮在内容上的，所以分栏页每一列的内容都要自己让出状态栏高度，
 *    并且这段留白要随内容滚走，而不是固定贴在页面顶部。
 *
 * 让出的方式和页面结构有关：
 * - 整列可滚动（`Column(verticalScroll)`）：列顶调用 [WarmTopInset]。
 * - 列里是 LazyColumn / LazyVerticalGrid：把 [LocalWarmTopInset] 加进列表的 `contentPadding`。
 * 两类页面都放在 [WarmTwoPane] 里，状态栏高度只由它在内部算一次（[warmStatusBarTop]）。
 */

/** 横屏分栏时由 [WarmTwoPane] 提供状态栏高度；竖屏以及未经过 [WarmTwoPane] 的地方恒为 0。 */
internal val LocalWarmTopInset = compositionLocalOf { 0.dp }

/** 浏览页在横屏时改成左右分栏，竖屏仍是原来的单列。 */
@Composable
internal fun isWideLayout(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 顶部留白按状态栏高度的这个比例取。
 * 1f = 完全让开状态栏；0.6f 是正在试的收紧版本，觉得挤就调回 1f。
 */
private const val WarmTopInsetScale = 0.6f

/** 横屏时要让出的状态栏高度；竖屏为 0。不走 [WarmTwoPane] 的页面用它自己算。 */
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
    CompositionLocalProvider(LocalWarmTopInset provides warmStatusBarTop()) {
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
}

/** 可滚动的列在列顶留出状态栏高度；竖屏为 0。 */
@Composable
internal fun WarmTopInset() {
    Spacer(Modifier.height(LocalWarmTopInset.current))
}

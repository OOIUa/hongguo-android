package com.hongguo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HongguoTheme {
                val pageStates = rememberSaveableStateHolder()
                val model: HongguoViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val accountModel: AccountViewModel = viewModel()
                val accountState by accountModel.state.collectAsStateWithLifecycle()
                val updateModel: UpdateViewModel = viewModel()
                val updateState by updateModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.playerOpen || state.selectedCard != null || state.tab != AppTab.HOME) {
                    when {
                        state.playerOpen -> model.closePlayer()
                        state.selectedCard != null -> model.backToSearch()
                        state.tab == AppTab.SEARCH -> model.closeSearch()
                        state.tab == AppTab.ACCOUNT -> model.closeAccount()
                        state.tab in setOf(AppTab.HISTORY, AppTab.FAVORITES) -> model.closeLibrary()
                        else -> model.selectTab(AppTab.HOME)
                    }
                }
                if (state.playerOpen && state.detail != null) EpisodePlayerScreen(state, model, accountModel)
                else {
                    Scaffold(containerColor = Canvas, bottomBar = {
                        if (state.selectedCard == null && state.tab !in setOf(AppTab.SEARCH, AppTab.ACCOUNT))
                            AppNavigation(state.tab, model::selectTab)
                    }) { insets ->
                        pageStates.SaveableStateProvider(state.selectedCard?.let { "detail_${it.id}" } ?: "tab_${state.tab.name}") {
                        Column(Modifier.fillMaxSize().padding(insets).semantics { testTagsAsResourceId = true }) {
                            if (state.selectedCard != null) DetailScreen(state, model, accountModel)
                            else when (state.tab) {
                                AppTab.HOME -> {
                                    BrandHeader()
                                    HomeScreen(state, model)
                                }
                                AppTab.CATEGORY -> CategoryScreen(state, model)
                                AppTab.SEARCH -> SearchScreen(state, model)
                                AppTab.FAVORITES, AppTab.HISTORY -> LibraryScreen(state, model, accountModel)
                                AppTab.MINE -> MyScreen(state, model, updateState, updateModel,
                                    accountName = if (accountState.sessionStatus == AccountSessionStatus.WEBSITE_ONLY)
                                        "官网登录已保存" else accountState.profile?.nickname,
                                    accountStatus = when (accountState.sessionStatus) {
                                        AccountSessionStatus.WEBSITE_ONLY -> "官网已登录，手机云账号未接通"
                                        AccountSessionStatus.LOGGED_IN -> "查看账号收藏、历史与预约"
                                        AccountSessionStatus.NEEDS_VERIFICATION -> "已保存账号，等待重新验证"
                                        else -> "登录后查看手机片单"
                                    })
                                AppTab.ACCOUNT -> AccountScreen(accountModel, onBack = model::closeAccount,
                                    onOpenLibrary = model::openLibrary)
                            }
                        }
                        }
                    }
                }
                UpdateDialogs(updateState, updateModel, this@MainActivity)
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The established typographic brand mark remains native, with no rasterized screen chrome.
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Orange), Alignment.Center) {
            Text("果", color = Color.White, fontSize = 24.sp / LocalDensity.current.fontScale, fontWeight = FontWeight.Bold)
        }
        Text("红果剧场", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

package cn.imgozi.planetport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import cn.imgozi.planetport.ui.detail.NetworkDetailView
import cn.imgozi.planetport.ui.diag.ApplyTraceView
import cn.imgozi.planetport.ui.home.HomeView
import cn.imgozi.planetport.ui.moon.MoonView
import cn.imgozi.planetport.ui.peers.PeerListView
import cn.imgozi.planetport.ui.planet.PlanetSettingsView
import cn.imgozi.planetport.ui.prefs.PrefsView

/**
 * 全 App 页面路由目标（对齐 iOS HomeRoute + 全页清单）。
 * 全部页面已接线（T3 主页 + T4 详情/设置/诊断 + T5 Moon/Peers）。
 */
sealed interface Page {
    data object Home : Page
    data class NetworkDetail(val nwid: String) : Page
    data object Prefs : Page
    data class PlanetSettings(val planetId: String) : Page
    data object Peers : Page
    data class Moon(val planetId: String) : Page
    data object ApplyTrace : Page
}

/** 页面导航口：push/pop 栈式（对齐 iOS NavigationStack path append/remove）。 */
interface Navigator {
    fun navTo(page: Page)
    fun pop()
}

/** 页内导航统一经此注入，避免层层传参（T4/T5 页面同样消费）。 */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator 未提供：须由 AppRoot 注入")
}

/**
 * 单例根 Composable：页面栈式渲染（栈顶全屏）。栈内返回键 pop，回到 Home 后
 * 不再拦截（再按一次 = 系统默认退出）；不引 Navigation 库（设计既定）。
 */
@Composable
fun AppRoot(activity: MainActivity) {
    val stack = remember { mutableStateListOf<Page>(Page.Home) }
    val navigator = remember(stack) {
        object : Navigator {
            override fun navTo(page: Page) {
                stack.add(page)
            }

            override fun pop() {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
            }
        }
    }
    BackHandler(enabled = stack.size > 1) { navigator.pop() }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        when (val page = stack.last()) {
            Page.Home -> HomeView(activity)
            is Page.NetworkDetail -> NetworkDetailView(page.nwid, onBack = navigator::pop)
            Page.Prefs -> PrefsView(onBack = navigator::pop)
            is Page.PlanetSettings -> PlanetSettingsView(
                planetId = page.planetId,
                onBack = navigator::pop,
                navToPeers = { navigator.navTo(Page.Peers) },
                navToMoon = { navigator.navTo(Page.Moon(page.planetId)) },
                navToApplyTrace = { navigator.navTo(Page.ApplyTrace) },
            )
            Page.Peers -> PeerListView(onBack = navigator::pop)
            // planetId 与 selectedPlanetId 的一致性依赖进入前已 selectPlanet
            //（HomeView 整卡点击/pager 切换）——MoonImporter 落盘目录与遗留
            // 记录归属都取 selectedPlanetId（T5 审查 concern c 前提，见 HomeView）
            is Page.Moon -> MoonView(planetId = page.planetId, onBack = navigator::pop)
            Page.ApplyTrace -> ApplyTraceView(onBack = navigator::pop)
        }
    }
}

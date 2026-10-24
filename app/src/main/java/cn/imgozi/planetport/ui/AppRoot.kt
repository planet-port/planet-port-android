package cn.imgozi.planetport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.ui.home.HomeView

/**
 * 全 App 页面路由目标（对齐 iOS HomeRoute + 全页清单）。T3 建全集：
 * Home 由本任务实现，其余页的 Composable 由 T4/T5 提供，AppRoot 的对应
 * when 分支届时替换（占位期为纯文本「页面建设中」，不引用未存在的类）。
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
            is Page.NetworkDetail -> PlaceholderPage("网络详情")
            Page.Prefs -> PlaceholderPage("设置")
            is Page.PlanetSettings -> PlaceholderPage("行星设置")
            Page.Peers -> PlaceholderPage("节点列表")
            is Page.Moon -> PlaceholderPage("卫星节点")
            Page.ApplyTrace -> PlaceholderPage("数据面诊断")
        }
    }
}

/** T4/T5 页面接入前的占位（纯文本；分支替换点归各页任务，AppRoot 独占维护）。 */
@Composable
private fun PlaceholderPage(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "页面建设中",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

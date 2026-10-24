package cn.imgozi.planetport.ui.prefs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.imgozi.planetport.store.AppPrefs
import cn.imgozi.planetport.store.StoreRepo
import cn.imgozi.planetport.tunnel.TunnelController
import cn.imgozi.planetport.tunnel.TunnelStateBus
import cn.imgozi.planetport.ui.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局偏好页状态。AppPrefs 是唯一事实源：每次变更「持久化（AppPrefs 字段）+
 * ThemeManager 内存态（即时生效）」双写——两者缺一不可（裁决 #2）。
 * 偏好写入不触发 configChanges（store 层语义），故本流由 VM 自行维护。
 */
class PrefsViewModel(application: Application) : AndroidViewModel(application) {

    private val _prefs = MutableStateFlow(AppPrefs())
    val prefs: StateFlow<AppPrefs> = _prefs.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = StoreRepo.store.readPrefs()
            _prefs.value = loaded
            // 防御性灌入：AppRoot（T3）启动时也应 seed；这里保证即使缺 seed，
            // 进入偏好页后内存态与持久化态一致（幂等，重复 seed 无副作用）
            ThemeManager.applyPrefs(loaded.themeColor, loaded.themeColorCustom, loaded.successDerived)
        }
    }

    /** 禁用 IPv6：落盘 + 隧道在跑则热重启（对齐 iOS VPNManager.restartIfRunning）。 */
    fun setDisableIpv6(on: Boolean) {
        write({ it.copy(disableIpv6 = on) })
        TunnelController.restartIfRunning(getApplication())
    }

    /** 同时连接网络软上限（2..6，纯偏好，UI 门控范围）。 */
    fun setMaxNetworks(value: Int) {
        if (value !in 2..6) return
        write({ it.copy(maxNetworks = value) })
    }

    /** 视差动画开关（无背景图时视图侧禁用，这里照写不拦）。 */
    fun setParallax(on: Boolean) {
        write({ it.copy(parallax = on) })
    }

    /** 选预设主色：清自定义色（对齐 iOS「色板即当前主色」语义）。 */
    fun setThemePreset(hex: Long) {
        writeWithTheme(persist = { it.copy(themeColor = hex, themeColorCustom = null) })
    }

    /**
     * 拾色器拖动中的实时预览：只改 ThemeManager 内存态，不落盘——
     * 滑杆逐帧回调，写盘由 [setThemeCustom] 在拖动结束时一次性完成。
     */
    fun previewThemeCustom(argb: Long) {
        ThemeManager.applyPrefs(null, argb and 0xFFFFFFFFL, _prefs.value.successDerived)
    }

    /** 拾色器自定义主色（ARGB）落盘 + 内存态；themeColor 保留为最后一次预设，custom 优先。 */
    fun setThemeCustom(argb: Long) {
        writeWithTheme(persist = { it.copy(themeColorCustom = argb and 0xFFFFFFFFL) })
    }

    /** 状态色是否随主色三角派生。 */
    fun setSuccessDerived(on: Boolean) {
        writeWithTheme(persist = { it.copy(successDerived = on) })
    }

    /**
     * 语言：仅建 UI + 持久化（"zh"/"zh-rTW"/"en"，null = 跟随系统）。
     * per-app locale 切换（AppCompatDelegate.setApplicationLocales）接线归 T7。
     */
    fun setLanguage(code: String?) {
        write({ it.copy(language = code) })
    }

    /** 节点快照（Core 版本取 nodeStatus?.coreVersion；停机为 null → 视图显示「未知」）。 */
    val nodeStatus: StateFlow<cn.imgozi.planetport.tunnel.NodeStatusInfo?> get() = TunnelStateBus.nodeStatus

    /** App 版本（versionName (versionCode)，对齐 iOS CFBundleShortVersionString 拼装）。 */
    val appVersion: String by lazy {
        runCatching {
            val info = getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0)
            "${info.versionName} (${info.versionCode})"
        }.getOrDefault("—")
    }

    /** 主题双写：持久化 + ThemeManager 内存态（即时生效）。 */
    private fun writeWithTheme(persist: (AppPrefs) -> AppPrefs) {
        write(persist)
        val p = _prefs.value
        ThemeManager.applyPrefs(p.themeColor, p.themeColorCustom, p.successDerived)
    }

    private fun write(mutate: (AppPrefs) -> AppPrefs) {
        viewModelScope.launch {
            StoreRepo.store.writePrefs(mutate)
            // 直接赋值而非 StateFlow.update{}：readPrefs 是挂起函数，
            // 不放进 update 的 CAS 重试循环里反复求值
            _prefs.value = StoreRepo.store.readPrefs()
        }
    }
}

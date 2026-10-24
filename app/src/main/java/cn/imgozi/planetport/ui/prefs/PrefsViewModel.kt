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

    /** 禁用 IPv6：落盘完成后隧道在跑才热重启（对齐 iOS VPNManager.restartIfRunning）。 */
    fun setDisableIpv6(on: Boolean) {
        write({ it.copy(disableIpv6 = on) }, onPersisted = {
            TunnelController.restartIfRunning(getApplication())
        })
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

    /** 主题双写：乐观更新内存态（新值即时灌 ThemeManager）+ 异步落盘。 */
    private fun writeWithTheme(persist: (AppPrefs) -> AppPrefs) {
        write(persist, onApplied = { p ->
            ThemeManager.applyPrefs(p.themeColor, p.themeColorCustom, p.successDerived)
        })
    }

    /**
     * 偏好写入（乐观更新）：先以当前内存值推演新值、同步灌 _prefs 与
     * [onApplied]（主题类经 ThemeManager 即时生效），再异步落盘——writePrefs 内
     * withContext(IO) 必挂起，若等落盘后再读内存灌主题，拿到的必是旧值（审查 C1）。
     *
     * 落盘用 [mutate] 而非整档替换：prefs.json 的 selectedPlanetId/activePlanetId
     * 还由 AppConfigStore 内部（selectPlanet/activatePlanet，首页调用）写入，本 VM
     * 快照会陈旧——RMW 作用于磁盘最新值，主题字段无第二写入方结果恒等（复核 I-NEW）。
     *
     * @param onApplied    推演完成即回调（新值），主线程同步执行
     * @param onPersisted  落盘完成后回调（如需依赖磁盘态的后续动作——热重启）
     */
    private fun write(
        mutate: (AppPrefs) -> AppPrefs,
        onApplied: ((AppPrefs) -> Unit)? = null,
        onPersisted: (() -> Unit)? = null,
    ) {
        val next = mutate(_prefs.value)
        _prefs.value = next
        onApplied?.invoke(next)
        viewModelScope.launch {
            StoreRepo.store.writePrefs(mutate)
            onPersisted?.invoke()
        }
    }
}

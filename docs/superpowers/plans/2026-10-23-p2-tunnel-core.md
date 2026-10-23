# P2 隧道核心实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 单网络连通闭环——VpnService 启动 → join 网络 → 拿到 netconf 建 TUN → ARP/路由数据面 → ping 通对端 → 停止退出（设计文档 §8 P2 完成标准）。

**Architecture:** 官方 ZeroTier SDK（vendor 入仓，Node.java）承载核心，Kotlin 服务层按设计文档 §4 五组件组装：ZeroTierVpnService（壳）、NodeRuntime（核心生命周期+dataStore hook+planet 重定向）、UdpCom（UDP 9994 物理收发）、TunnelManager（netconf 聚合→Builder.establish）、Dataplane+FrameConverter（TUN 读写与 L3↔以太网帧转换）。全部行为语义以 iOS 姊妹项目 PlanetPortTunnel/ 为唯一规格源逐段对齐。

**Tech Stack:** Kotlin 2.2.21 / kotlinx-coroutines（已在 catalog）/ vendor com.zerotier.sdk（core 模块，P0 已接线）/ VpnService + Builder（平台 API，零新依赖）。

## Global Constraints

1. **GPL 血统代码零引入**（设计文档 §1）：唯一参考源 = `planet-port-ios`（作者自有）+ vendor 官方 SDK；本计划全部 Kotlin 代码从零编写，语义对齐 iOS 但不逐行翻译其 Swift 文本以外的东西——iOS 文件是行为规格，不是拷贝源。
2. **路径契约**（对齐 iOS ZTBridge.mm 的 ztStateFilePath/ztHookedPath，Android JNI dataStore name 格式已核实于 core/jni/com_zerotierone_sdk_Node.cpp:404-421）：
   - `identity.public` / `identity.secret` → `files/zt/`（**身份跨 planet 共享**）
   - `planet` → 自定义 planet 时重定向 `files/planets/<fileName>`；official（fileName=null）时 `files/zt/planet`（不存在 → stateGet 返回 -1，核心用内置官方 planet）
   - `moons.d/<16hex>.moon`、`networks.d/<16hex>.conf`、`peers.d/<10dec>` → `files/zt/<planetId>/`（**按 planet 隔离**，即 P1 StorePaths.planetStateDir）
3. **protect() 硬约束**：UdpCom 的 DatagramSocket 必须在 establish 前调 `vpnService.protect(socket)`，否则 ZT 物理包被自家 TUN 路由抓回形成环回（iOS 无此概念，Android 特有）。
4. **线程模型**（设计文档 §4）：编排协程化（SupervisorJob scope，服务销毁整体 cancel）；TUN 读循环/UDP recv 循环专职线程（阻塞系统调用 + close 退出）；JNI 回调（帧/事件/配置）只做 `Channel.trySend`（无锁、不可阻塞——回调跑在 ZT 原生线程，attach 由 P0 的 ZT_jniguard 保证）。
5. **锁纪律**（P1 教训）：Kotlin Mutex 非重入——锁内复用必须走 `*Locked` 私有变体；`synchronized` 块内禁止引入挂起点；锁序恒为 configMutex → FrameConverter 内部锁。
6. **不写测试用例**（设计文档 §9）；每任务验证 = 编译双绿（assembleDebug/assembleRelease）+ 指定真机检查。
7. **注释/提交**：中文 KDoc 说明「为什么」；Conventional Commits 中文，Body 必写，禁止任何署名尾注。
8. **API 边界**：minSdk 23（NotificationChannel 需 26 分支）、targetSdk 33（FGS 无需声明 type；POST_NOTIFICATIONS 为 API 33+ 运行时权限）。**不启用 desugaring**：全代码避免 java.time/java.util.stream 等 API 26+ 库调用（文件 IO 用 java.io、时间用 System.currentTimeMillis）。
9. **设计文档偏差声明**（控制方裁决，随计划入库）：
   - **不实现「IPv4 源 NAT」**：iOS 规格无任何 NAT 逻辑，SDK 数据路径同构（TUN L3 包 → processVirtualNetworkFrame，源地址由内核按 tun 地址选择）；P3 复核，若真机实测出现源地址异常再立项。
   - **不实现「启动聚齐等待（≤8s）」**：iOS 无此逻辑——每个 netconf 到达都触发 300ms 去抖重套（增量聚合），语义等价且更简单。
   - **切网重启（iOS pathMonitor）留 P5**、**peers 查询留 P4**（对齐设计文档 §8 的 P5「切网重连」/P4 职责划分）。

## iOS → Android 组件映射（执行者背景）

| iOS (PlanetPortTunnel/) | Android (tunnel/) | 差异要点 |
|---|---|---|
| PacketTunnelProvider 生命周期 | ZeroTierVpnService | Intent 启停（startService+action）；VpnService.prepare 用户授权 |
| NEPacketTunnelProvider.packetFlow | Builder.establish() → ParcelFileDescriptor | **fd 每次 establish 重建** → Dataplane.swap() 换代读写线程 |
| setTunnelNetworkSettings | Builder.addAddress/addRoute/setMtu/setDnsServers | 同步返回，无 iOS 的异步单飞/dirty 机制 |
| ZTBridge.mm（C API 直包） | 官方 Node.java | 回调制接口（DataStore/Event/Frame/Config/Sender） |
| ZTBridge callbackQueue 串行 hop | 无界 Channel + 单消费协程 | 回调线程只 trySend |
| Darwin notify 配置同步 | AppConfigStore.configChanges（P1 已建） | 有损合并（16 溢出即丢）→ 订阅方 level-triggered 全量重读 |
| StatusStore（App Group defaults） | TunnelStateBus（同进程内存 StateFlow） | 跨进程契约原样存原始码，文案 UI 层生成 |
| UDP socket 手写 + 源 IP bind | UdpCom（DatagramSocket + protect） | Android 不 bind 源 IP（protect 后内核选路） |
| RoutePlanner（getifaddrs） | RoutePlanner（NetworkInterface） | 挖洞差集算法逐字对齐 |

---

### Task 1: 服务底座（manifest + 状态总线 + 前台通知 + 服务壳）

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/TunnelStateBus.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/VpnNotification.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`
- Create: `app/src/main/res/drawable/ic_notification.xml`

**Interfaces:**
- Consumes: 无（首个 tunnel/ 文件）
- Produces: `TunnelStateBus`（`tunnelState: StateFlow<TunnelState>`、`nodeStatus: StateFlow<NodeStatusInfo?>`、`networkStatuses: StateFlow<Map<String, Int>>`、`applyTrace/eventLog: StateFlow<String>`、`setTunnelState/setNodeStatus/setNetworkStatus(nwidHex, code)/clearNetworkStatuses/setApplyTrace/log(line)`）；`ZeroTierVpnService.start(context)/stop(context)`；`VpnNotification.start(service, text)/update(context, text)`

- [ ] **Step 1: 写 AndroidManifest.xml（全量替换）**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- exported=true + BIND_VPN_SERVICE：VpnService.prepare 返回的隐式 intent 由系统
             解析到本服务；permission 保证只有系统能绑定（官方 VpnService 声明惯例） -->
        <service
            android:name=".tunnel.ZeroTierVpnService"
            android:exported="true"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
    </application>

</manifest>
```

- [ ] **Step 2: 写 TunnelStateBus.kt**

```kotlin
package cn.imgozi.planetport.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date

/** 隧道宏观状态（语义对齐 iOS StatusStore.tunnelStatus 文案集合；文案在 UI 层生成） */
sealed interface TunnelState {
    data object Idle : TunnelState
    data object Starting : TunnelState
    data object Running : TunnelState
    data class Reconnecting(val reason: String) : TunnelState
    data class Stopped(val reason: String? = null) : TunnelState
    data class Failed(val reason: String, val recoverable: Boolean) : TunnelState
}

/** 节点信息快照（nodeId 十位十六进制、核心版本、在线位） */
data class NodeStatusInfo(
    val nodeId: String,
    val online: Boolean,
    val coreVersion: String,
)

/**
 * 隧道状态总线（对齐 iOS StatusStore；同进程内存态，服务写、UI 读）。
 *
 * networkStatuses 存 ZT_VirtualNetworkStatus 原始码（0..6，键为 16 位十六进制 nwid）——
 * 不在隧道侧生成文案：契约与展示语言解耦，文案由 UI 层生成（对齐 iOS 跨进程契约约定）。
 * StateFlow 无 replay 语义差异问题（热流始终有当前值），但 configChanges 的有损合并
 * 契约同样适用：订阅方收到通知后必须全量重读，不得假设收到每一次变更。
 */
object TunnelStateBus {
    private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _nodeStatus = MutableStateFlow<NodeStatusInfo?>(null)
    val nodeStatus: StateFlow<NodeStatusInfo?> = _nodeStatus.asStateFlow()

    private val _networkStatuses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val networkStatuses: StateFlow<Map<String, Int>> = _networkStatuses.asStateFlow()

    /** 聚合应用诊断快照（排查“已连接但不通”，对齐 iOS StatusStore.applyTrace） */
    private val _applyTrace = MutableStateFlow("")
    val applyTrace: StateFlow<String> = _applyTrace.asStateFlow()

    /** 隧道事件滚动日志（最近 40 条，对齐 iOS StatusStore.eventLog） */
    private val _eventLog = MutableStateFlow("")
    val eventLog: StateFlow<String> = _eventLog.asStateFlow()

    fun setTunnelState(state: TunnelState) { _tunnelState.value = state }
    fun setNodeStatus(info: NodeStatusInfo?) { _nodeStatus.value = info }

    fun setNetworkStatus(nwidHex: String, status: Int) {
        _networkStatuses.value = _networkStatuses.value + (nwidHex to status)
    }

    fun clearNetworkStatuses() { _networkStatuses.value = emptyMap() }

    fun setApplyTrace(trace: String) { _applyTrace.value = trace }

    /** 低频事件直写（配置回调/聚合决策/错误），高频帧不走这里 */
    fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
        val current = _eventLog.value
        val lines = (if (current.isEmpty()) emptyList() else current.split('\n')) + "$stamp $line"
        _eventLog.value = lines.takeLast(40).joinToString("\n")
    }
}
```

- [ ] **Step 3: 写 VpnNotification.kt（平台 API，零新依赖）**

```kotlin
package cn.imgozi.planetport.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import cn.imgozi.planetport.R

/** 前台通知：渠道幂等创建 + 状态文案更新（对齐设计文档 §4 VpnNotification 组件） */
internal object VpnNotification {
    private const val CHANNEL_ID = "planet_tunnel"
    private const val NOTIFICATION_ID = 1

    fun start(service: Service, text: String) {
        createChannel(service)
        service.startForeground(NOTIFICATION_ID, build(service, text))
    }

    fun update(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, build(context, text))
    }

    private fun build(context: Context, text: String): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Planet Port")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "隧道状态", NotificationManager.IMPORTANCE_LOW)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
```

- [ ] **Step 4: 写 ic_notification.xml（通知小图标，24dp 矢量：圆环行星+斜环带）**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- 行星本体（圆环描边） -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,4a8,8 0 1,0 0.001,0z M12,6a6,6 0 1,1 -0.001,0z" />
    <!-- 斜环带 -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M3.2,13.6 l17.2,-7.4 0.5,1.1 -17.2,7.4z" />
</vector>
```

- [ ] **Step 5: 写 ZeroTierVpnService.kt（T1 壳版：只做生命周期与通知，核心组件后续任务接入）**

```kotlin
package cn.imgozi.planetport.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * VpnService 壳（对齐设计文档 §4：≤200 行生命周期组装，具体逻辑在组件）。
 * T1 版只承载启停意图与前台通知；NodeRuntime/UdpCom/TunnelManager/Dataplane
 * 由后续任务接入 onCreate 组装。
 */
internal class ZeroTierVpnService : VpnService() {
    companion object {
        private const val ACTION_START = "cn.imgozi.planetport.action.START"
        private const val ACTION_STOP = "cn.imgozi.planetport.action.STOP"

        fun start(context: Context) {
            context.startService(
                Intent(context, ZeroTierVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ZeroTierVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                TunnelStateBus.setTunnelState(TunnelState.Running)
                VpnNotification.start(this, "运行中")
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    /** T1 版无组件可停；后续任务在此扩展组件停机序列 */
    private fun stopTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Stopped())
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        TunnelStateBus.setTunnelState(TunnelState.Idle)
        super.onDestroy()
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew assembleDebug assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 真机验证（Mi Note 3, 9311902b）**

```bash
adb -s 9311902b install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 9311902b shell am start-foreground-service \
  -n cn.imgozi.a.planetport/cn.imgozi.planetport.tunnel.ZeroTierVpnService \
  -a cn.imgozi.planetport.action.START
adb -s 9311902b shell dumpsys notification --noredact | grep -A2 "planet_tunnel\|Planet Port"
adb -s 9311902b shell am startservice \
  -n cn.imgozi.a.planetport/cn.imgozi.planetport.tunnel.ZeroTierVpnService \
  -a cn.imgozi.planetport.action.STOP
```

Expected: dumpsys 中出现通知渠道 planet_tunnel 与「运行中」通知；STOP 后通知移除、进程可被系统回收。

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat(tunnel): 服务底座——VpnService 壳、状态总线与前台通知

P2 隧道核心第一块：manifest 声明 VPN 服务与权限（INTERNET/
FOREGROUND_SERVICE/POST_NOTIFICATIONS，targetSdk 33 无需 FGS type）；
TunnelStateBus 以同进程 StateFlow 承载隧道状态/节点信息/网络状态原始码
（对齐 iOS StatusStore 语义，文案 UI 层生成）；VpnNotification 渠道幂等
创建（API 26 分支）；ZeroTierVpnService 壳只做生命周期组装，核心组件
由后续任务接入。"
```

---

### Task 2: NodeRuntime（核心生命周期 + dataStore hook + planet 重定向）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/NodeRuntime.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`（onCreate/onStartCommand 组装 runtime）

**Interfaces:**
- Consumes: `com.zerotier.sdk.*`（vendor，已随 core 模块依赖）；T1 的 TunnelStateBus
- Produces:
  - `NetConf(nwid, name, status: Int, type: Int, mac: Long, mtu: Int, broadcastEnabled, assignedAddrs: List<String>/*"ip/prefix"*/, routes: List<String>/*"dest/prefix [via gw]"*/, dns: List<String>)`
  - `NetConfEvent(nwid: Long, op: VirtualNetworkConfigOperation, conf: NetConf)`
  - `FrameEvent(nwid: Long, srcMac: Long, dstMac: Long, etherType: Int, vlanId: Int, frame: ByteArray)`
  - `NodeRuntime(dataStoreRoot: File, planetDir: File, planetFile: File?, scope: CoroutineScope)`；`start(sender: PacketSender): Boolean`、`shutdown()`、`join/leave(nwid: Long)`、`orbit(worldId: Long, seed: Long)`、`deorbit(worldId: Long)`、`multicastSubscribe(nwid: Long, group: Long, adi: Long)`、`processWirePacket(data: ByteArray, remote: InetSocketAddress): Boolean`、`processVirtualNetworkFrame(nwid, srcMac, dstMac, etherType: Int, vlanId: Int, frame: ByteArray)`、`address(): Long`、`version(): String`、`networkConf(nwid: Long): NetConf?`、`frameChannel: Channel<FrameEvent>`、`configChannel: Channel<NetConfEvent>`

- [ ] **Step 1: 写 NodeRuntime.kt（全量）**

```kotlin
package cn.imgozi.planetport.tunnel

import com.zerotier.sdk.DataStoreGetListener
import com.zerotier.sdk.DataStorePutListener
import com.zerotier.sdk.Event
import com.zerotier.sdk.EventListener
import com.zerotier.sdk.Node
import com.zerotier.sdk.PacketSender
import com.zerotier.sdk.ResultCode
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkConfigListener
import com.zerotier.sdk.VirtualNetworkConfigOperation
import com.zerotier.sdk.VirtualNetworkFrameListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

/** 网络配置快照（字段契约对齐 iOS ZTNetworkConfigData；status/type 存原始码） */
internal class NetConf(
    val nwid: Long,
    val name: String,
    val status: Int,
    val type: Int,
    val mac: Long,
    val mtu: Int,
    val broadcastEnabled: Boolean,
    /** "ip/prefix"（prefix 在 InetSocketAddress.port 字段，SDK JNI 已转主机序数值） */
    val assignedAddrs: List<String>,
    /** "dest/prefix" 或 "dest/prefix via gw" */
    val routes: List<String>,
    /** DNS server IP（不含端口） */
    val dns: List<String>,
)

/** 网络配置回调事件（JNI 线程投递，消费协程处理） */
internal class NetConfEvent(
    val nwid: Long,
    val op: VirtualNetworkConfigOperation,
    val conf: NetConf,
)

/** 帧回调事件（帧数据不含以太网头，SDK 语义同 iOS C API） */
internal class FrameEvent(
    val nwid: Long,
    val srcMac: Long,
    val dstMac: Long,
    val etherType: Int,
    val vlanId: Int,
    val frame: ByteArray,
)

/**
 * ZeroTier 核心运行时（对齐 iOS ZTBridge 职责）：Node 创建/销毁、
 * dataStore 读写 hook（planet 重定向 + 按 planet 隔离）、后台任务驱动、
 * join/leave/orbit、帧与配置回调投递。
 *
 * 回调跑在 ZT 原生线程（attach 由 ZT_jniguard 保证），实现只做
 * Channel.trySend / 内存字段写，禁止任何阻塞或锁等待。
 */
internal class NodeRuntime(
    private val dataStoreRoot: File,
    private val planetDir: File,
    private val planetFile: File?,
    private val scope: CoroutineScope,
) {
    val frameChannel = Channel<FrameEvent>(Channel.UNLIMITED)
    val configChannel = Channel<NetConfEvent>(Channel.UNLIMITED)

    var node: Node? = null
        private set

    // MARK: - 生命周期

    fun start(sender: PacketSender): Boolean {
        val n = Node(System.currentTimeMillis())
        val rc = n.init(
            StoreGetListener(), StorePutListener(), sender,
            EventListenerImpl(), FrameListenerImpl(), ConfigListenerImpl(), null,
        )
        if (rc != ResultCode.RESULT_OK) {
            n.close()
            TunnelStateBus.log("node_init 失败 rc=$rc")
            return false
        }
        node = n
        // 启动即驱动一次后台任务（对齐 iOS startWithDataStorePath：identity 生成/装载在此发生）
        n.processBackgroundTasks(System.currentTimeMillis(), longArrayOf(0))
        TunnelStateBus.setNodeStatus(
            NodeStatusInfo(nodeId = "%010x".format(n.address()), online = false, coreVersion = version())
        )
        TunnelStateBus.log("核心已启动 nodeId=%010x version=%s".format(n.address(), version()))
        startSupervisor()
        return true
    }

    fun shutdown() {
        node?.close()
        node = null
    }

    /** 后台任务 deadline 驱动（对齐 iOS bgTimer：50..100ms 自适应窗口） */
    private fun startSupervisor() {
        scope.launch(Dispatchers.IO) {
            val deadline = longArrayOf(0)
            while (isActive) {
                val n = node ?: break
                val rc = n.processBackgroundTasks(System.currentTimeMillis(), deadline)
                if (rc != ResultCode.RESULT_OK) {
                    TunnelStateBus.log("processBackgroundTasks rc=$rc，停止驱动")
                    break
                }
                val wait = (deadline[0] - System.currentTimeMillis()).coerceIn(50, 100)
                delay(wait)
            }
        }
    }

    // MARK: - 控制与数据入口

    fun join(nwid: Long) { node?.join(nwid) }
    fun leave(nwid: Long) { node?.leave(nwid) }
    fun orbit(worldId: Long, seed: Long) { node?.orbit(worldId, seed) }
    fun deorbit(worldId: Long) { node?.deorbit(worldId) }
    fun multicastSubscribe(nwid: Long, group: Long, adi: Long) {
        node?.multicastSubscribe(nwid, group, adi)
    }

    fun address(): Long = node?.address() ?: 0L

    fun version(): String {
        val v = node?.getVersion() ?: return ""
        return "${v.major}.${v.minor}.${v.revision}"
    }

    /** UDP 收包入口（UdpCom 收包线程调用；线程安全：C 核心内部有锁） */
    fun processWirePacket(data: ByteArray, remote: InetSocketAddress): Boolean {
        val n = node ?: return false
        val rc = n.processWirePacket(System.currentTimeMillis(), -1, remote, data, longArrayOf(0))
        return rc == ResultCode.RESULT_OK
    }

    /** TUN 帧入口（Dataplane 读线程经 FrameConverter 调用） */
    fun processVirtualNetworkFrame(
        nwid: Long, srcMac: Long, dstMac: Long, etherType: Int, vlanId: Int, frame: ByteArray,
    ) {
        val n = node ?: return
        n.processVirtualNetworkFrame(
            System.currentTimeMillis(), nwid, srcMac, dstMac, etherType, vlanId,
            frame, longArrayOf(0),
        )
    }

    /** 当前网络配置快照（applyConfig 热重配路径用，对齐 iOS bridge.networkConfig） */
    fun networkConf(nwid: Long): NetConf? = node?.networkConfig(nwid)?.toNetConf()

    // MARK: - dataStore 文件映射（Global Constraints 路径契约）

    /**
     * dataStore 对象名 → 落盘文件。返回 null 表示未知对象（读写各按错误码上报）。
     * identity 跨 planet 共享（files/zt/）；moon/peer/netconf 按激活 planet 隔离
     * （files/zt/<planetId>/）；planet 在自定义 planet 时重定向到导入文件
     * （官方 planet 时读核心内置——文件不存在 stateGet 返回 -1 即可）。
     */
    internal fun stateFile(name: String): File? = when {
        name == "identity.public" || name == "identity.secret" -> File(dataStoreRoot, name)
        name == "planet" -> planetFile ?: File(dataStoreRoot, "planet")
        name.startsWith("moons.d/") || name.startsWith("networks.d/") ||
            name.startsWith("peers.d/") -> File(planetDir, name)
        else -> null
    }

    private inner class StoreGetListener : DataStoreGetListener {
        override fun onDataStoreGet(name: String, outBuffer: ByteArray?): Long {
            val f = stateFile(name) ?: return -2
            val data = try {
                if (f.exists()) f.readBytes() else return -1
            } catch (e: IOException) {
                return -2
            }
            if (outBuffer == null) return data.size.toLong()
            val n = minOf(data.size, outBuffer.size)
            System.arraycopy(data, 0, outBuffer, 0, n)
            return n.toLong()
        }
    }

    private inner class StorePutListener : DataStorePutListener {
        override fun onDataStorePut(name: String, buffer: ByteArray?, secure: Boolean): Int {
            val f = stateFile(name) ?: return -1
            val bytes = buffer ?: return -1
            return try {
                f.parentFile?.mkdirs()
                // 原子写（对齐 iOS NSDataWritingAtomic）：核心状态文件崩溃残留半文件
                // 会导致下次启动解析失败
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    tmp.delete()
                    return -1
                }
                0
            } catch (e: IOException) {
                -1
            }
        }

        override fun onDelete(name: String): Int {
            stateFile(name)?.delete()
            return 0
        }
    }

    private inner class EventListenerImpl : EventListener {
        override fun onEvent(event: Event) {
            when (event) {
                Event.EVENT_ONLINE -> updateOnline(true)
                Event.EVENT_OFFLINE -> updateOnline(false)
                Event.EVENT_FATAL_ERROR_IDENTITY_COLLISION -> {
                    TunnelStateBus.setTunnelState(
                        TunnelState.Failed("节点身份冲突（同 nodeId 已在别处运行）", recoverable = false)
                    )
                    TunnelStateBus.log("FATAL_ERROR_IDENTITY_COLLISION")
                }
                else -> {}
            }
        }

        override fun onTrace(message: String?) {}
    }

    private fun updateOnline(online: Boolean) {
        val cur = TunnelStateBus.nodeStatus.value
        TunnelStateBus.setNodeStatus(
            NodeStatusInfo(
                nodeId = cur?.nodeId ?: "%010x".format(address()),
                online = online,
                coreVersion = cur?.coreVersion ?: version(),
            )
        )
        TunnelStateBus.log(if (online) "ONLINE" else "OFFLINE")
    }

    private inner class FrameListenerImpl : VirtualNetworkFrameListener {
        override fun onVirtualNetworkFrame(
            nwid: Long, srcMac: Long, destMac: Long, etherType: Long, vlanId: Long,
            frameData: ByteArray?,
        ) {
            val frame = frameData ?: return
            frameChannel.trySend(
                FrameEvent(nwid, srcMac, destMac, etherType.toInt(), vlanId.toInt(), frame)
            )
        }
    }

    private inner class ConfigListenerImpl : VirtualNetworkConfigListener {
        override fun onNetworkConfigurationUpdated(
            nwid: Long, op: VirtualNetworkConfigOperation, config: VirtualNetworkConfig?,
        ): Int {
            val conf = config ?: return 0
            configChannel.trySend(NetConfEvent(nwid, op, conf.toNetConf()))
            return 0
        }
    }

    // MARK: - VirtualNetworkConfig → NetConf（对齐 iOS ztConvertNetworkConfig）

    /**
     * SDK InetSocketAddress 语义：IP 装地址字段、prefix 装端口字段（核心 C API 约定）。
     * via 的「无网关」在 C 层为 family=0，JNI 转出后可能表现为 null 或全零地址，
     * 两者都按「无 via」处理（防御式，不依赖 JNI 边界行为）。
     */
    private fun VirtualNetworkConfig.toNetConf(): NetConf {
        val addrs = assignedAddresses?.mapNotNull { a ->
            a?.address?.hostAddress?.let { "$it/${a.port}" }
        } ?: emptyList()
        val routeList = routes?.mapNotNull { r ->
            val target = r?.target ?: return@mapNotNull null
            val dest = target.address?.hostAddress?.let { "$it/${target.port}" } ?: return@mapNotNull null
            val viaIp = r.via?.address?.hostAddress
            if (viaIp != null && viaIp != "0.0.0.0" && viaIp != "::") "$dest via $viaIp" else dest
        } ?: emptyList()
        val dnsList = dns?.servers?.mapNotNull { it?.address?.hostAddress } ?: emptyList()
        return NetConf(
            nwid = nwid,
            name = name ?: "",
            status = status?.ordinal ?: -1,
            type = type?.ordinal ?: -1,
            mac = mac,
            mtu = mtu,
            broadcastEnabled = broadcastEnabled,
            assignedAddrs = addrs,
            routes = routeList,
            dns = dnsList,
        )
    }
}
```

**注意**：`assignedAddresses`/`routes`/`dns`/`status`/`type` 等 getter 在 SDK 源码中声明为非空返回类型，但 JNI 层可能构造出 null 元素——上述代码统一按可空防御（平台类型上 Kotlin 允许安全调用）。若编译器对某个 `?.` 报「永远非空」警告，删掉该处安全调用运算符即可，不要改逻辑。**VirtualNetworkStatus/VirtualNetworkType 的 ordinal 即 C 层原始值**（vendor 冻结版本枚举按 0..6 / 0..1 顺序声明，实测前用 `VirtualNetworkStatus.fromInt` 反向验证一处即可）。

- [ ] **Step 2: 改 ZeroTierVpnService.kt（组装 runtime；T3 将接入 UdpCom，此处先用空 sender）**

替换类体为：

```kotlin
internal class ZeroTierVpnService : VpnService() {
    private var scope: CoroutineScope? = null
    private var runtime: NodeRuntime? = null

    companion object {
        private const val ACTION_START = "cn.imgozi.planetport.action.START"
        private const val ACTION_STOP = "cn.imgozi.planetport.action.STOP"

        fun start(context: Context) {
            context.startService(
                Intent(context, ZeroTierVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ZeroTierVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                VpnNotification.start(this, "启动中")
                startTunnel()
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    /** T2 版：起核心（空 sender——无物理收发，T3 接 UdpCom 后 ONLINE 才有意义） */
        TunnelStateBus.setTunnelState(TunnelState.Starting)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val store = AppConfigStore(StorePaths(filesDir))
        s.launch {
            val planetId = store.activePlanetId()
            val planet = store.readPlanets().firstOrNull { it.id == planetId }
                ?: PlanetProfile.OFFICIAL
            val r = NodeRuntime(
                dataStoreRoot = File(filesDir, "zt"),
                planetDir = store.paths.planetStateDir(planet.id),
                planetFile = planet.fileName?.let { File(store.paths.planetsDir, it) },
                scope = s,
            )
            val ok = r.start(sender = { _, _, _, _ -> -1 })
            if (!ok) {
                TunnelStateBus.setTunnelState(TunnelState.Failed("核心初始化失败", recoverable = true))
                stopTunnel()
                return@launch
            }
            runtime = r
            TunnelStateBus.setTunnelState(TunnelState.Running)
            VpnNotification.update(this@ZeroTierVpnService, "运行中")
        }
    }

    private fun stopTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Stopped())
        scope?.cancel()
        scope = null
        runtime?.shutdown()
        runtime = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        TunnelStateBus.setTunnelState(TunnelState.Idle)
        super.onDestroy()
    }
}
```

补充 import：`cn.imgozi.planetport.store.AppConfigStore`、`cn.imgozi.planetport.store.StorePaths`、`cn.imgozi.planetport.store.PlanetProfile`、`kotlinx.coroutines.*`、`java.io.File`。

其中 sender 以 lambda 传入需要 SAM 转换：`PacketSender { localSocket, remoteAddr, packetData, ttl -> -1 }`（显式 SAM 构造，直接写 lambda 位置参数按接口签名 `onSendPacketRequested(Long, InetSocketAddress?, ByteArray?, Int): Int`）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 真机验证（identity 落盘与 nodeId 生成）**

```bash
adb -s 9311902b install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 9311902b shell am start-foreground-service \
  -n cn.imgozi.a.planetport/cn.imgozi.planetport.tunnel.ZeroTierVpnService \
  -a cn.imgozi.planetport.action.START
sleep 3
adb -s 9311902b shell run-as cn.imgozi.a.planetport ls -la files/zt/
adb -s 9311902b shell run-as cn.imgozi.a.planetport cat files/zt/identity.public
adb -s 9311902b logcat -d | grep -E "核心已启动|ONLINE" | tail -5
adb -s 9311902b shell am startservice \
  -n cn.imgozi.a.planetport/cn.imgozi.planetport.tunnel.ZeroTierVpnService \
  -a cn.imgozi.planetport.action.STOP
```

Expected: `files/zt/` 下出现 `identity.public`（首启生成，约 1s 内）与 `identity.secret`；logcat 未见「核心已启动」时用 `adb logcat -d -s NodeService VpnService` 排查（TunnelStateBus 未打 logcat——验证以文件落盘为准）。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(tunnel): NodeRuntime——核心生命周期与 dataStore hook

官方 SDK Node 封装：init 回调接线（dataStore 读写/事件/帧/配置投递无锁
Channel）；dataStore 文件映射按路径契约——identity 跨 planet 共享、
moon/peer/netconf 按激活 planet 隔离、自定义 planet 重定向到导入文件
（对齐 iOS ZTBridge.mm ztHookedPath 语义）；后台任务 deadline 自适应
驱动（50..100ms）；原子写防核心状态半文件；planet 切换 = 换目录重建
Node（调用方组装，对齐设计文档 §4 Planet 隔离）。"
```

---

### Task 3: UdpCom（UDP 9994 物理收发 + protect）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/UdpCom.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`（真实 sender 接线 + ONLINE 验证）

**Interfaces:**
- Consumes: NodeRuntime.processWirePacket（收包上行）；VpnService.protect（socket 防环回）
- Produces: `UdpCom(vpnService: VpnService, onPacket: (ByteArray, InetSocketAddress) -> Unit)`；`start()/shutdown()/asPacketSender(): PacketSender`

- [ ] **Step 1: 写 UdpCom.kt（全量）**

```kotlin
package cn.imgozi.planetport.tunnel

import android.net.VpnService
import com.zerotier.sdk.PacketSender
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * UDP 9994 物理收发（对齐 iOS 手写 socket 路径；Android 不 bind 出口源 IP——
 * protect 后内核按物理路由选源，iOS 的源 IP bind 是 extension 环境特例）。
 *
 * socket 必须 protect：establish 后默认路由指向自家 TUN，不 protect 的
 * socket 发出的 ZT 物理包会被抓回 TUN，形成环回黑洞。
 */
internal class UdpCom(
    private val vpnService: VpnService,
    private val onPacket: (data: ByteArray, remote: InetSocketAddress) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var generation = 0

    fun start() {
        shutdown()
        val gen = ++generation
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(9994))
        vpnService.protect(s)
        socket = s
        val t = Thread {
            val buf = ByteArray(65536)
            while (generation == gen) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                } catch (e: SocketException) {
                    break // shutdown 关闭 socket 触发
                } catch (e: IOException) {
                    continue
                }
                if (pkt.length <= 0) continue
                val remote = InetSocketAddress(pkt.address, pkt.port)
                // 拷贝后投递，避免 buf 复用竞争（对齐 iOS udpReceiveLoop）
                onPacket(buf.copyOf(pkt.length), remote)
            }
        }
        t.name = "zt-udp"
        t.start()
        thread = t
    }

    /** 发物理包（PacketSender 回调，ZT 原生线程调用）；返回 0 成功（sendto 语义） */
    private fun send(remote: InetSocketAddress, data: ByteArray): Int {
        val s = socket ?: return -1
        val host: InetAddress = remote.address ?: return -1
        return try {
            s.send(DatagramPacket(data, data.size, host, remote.port))
            0
        } catch (e: IOException) {
            -1
        }
    }

    fun shutdown() {
        generation += 1
        socket?.close() // 使阻塞的 receive 抛 SocketException 退出
        socket = null
        thread = null
    }

    /** 桥接为 SDK PacketSender（Node init 的 sender 参数） */
    fun asPacketSender(): PacketSender = PacketSender { _, remoteAddr, packetData, _ ->
        if (remoteAddr == null || packetData == null) -1 else send(remoteAddr, packetData)
    }
}
```

- [ ] **Step 2: 改 ZeroTierVpnService.startTunnel（真实 sender 接线）**

将 T2 版 startTunnel 中的核心启动段替换为（顺序：UdpCom 先构造 → NodeRuntime.start(udpCom.asPacketSender()) → udpCom.start()）：

```kotlin
        s.launch {
            val planetId = store.activePlanetId()
            val planet = store.readPlanets().firstOrNull { it.id == planetId }
                ?: PlanetProfile.OFFICIAL
            var runtimeRef: NodeRuntime? = null
            val udp = UdpCom(this@ZeroTierVpnService) { data, remote ->
                runtimeRef?.processWirePacket(data, remote)
            }
            val r = NodeRuntime(
                dataStoreRoot = File(filesDir, "zt"),
                planetDir = store.paths.planetStateDir(planet.id),
                planetFile = planet.fileName?.let { File(store.paths.planetsDir, it) },
                scope = s,
            )
            runtimeRef = r
            val ok = r.start(sender = udp.asPacketSender())
            if (!ok) {
                TunnelStateBus.setTunnelState(TunnelState.Failed("核心初始化失败", recoverable = true))
                stopTunnel()
                return@launch
            }
            udp.start()
            runtime = r
            udpCom = udp
            TunnelStateBus.setTunnelState(TunnelState.Running)
            VpnNotification.update(this@ZeroTierVpnService, "运行中")
        }
```

新增字段 `private var udpCom: UdpCom? = null`；stopTunnel 中在 `runtime?.shutdown()` 前加 `udpCom?.shutdown(); udpCom = null`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 真机验证（物理通路：ONLINE + planet peer 握手）**

```bash
adb -s 9311902b install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 9311902b shell am start-foreground-service \
  -n cn.imgozi.a.planetport/cn.imgozi.planetport.tunnel.ZeroTierVpnService \
  -a cn.imgozi.planetport.action.START
sleep 8
adb -s 9311902b shell run-as cn.imgozi.a.planetport ls files/zt/
adb -s 9311902b shell "netstat -u -n 2>/dev/null | grep 9994" || \
  adb -s 9311902b shell ss -u -n 2>/dev/null | grep 9994
```

Expected: `files/zt/` 出现 `planet`（官方 planet 缓存核心写出）与 `peers.d/`（planet 节点握手后写入 peer 记录）；UDP 9994 绑定可见。peers.d 有文件 = 物理通路已通（SDK 在无 TUN 场景下照常与 planet 通信）。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(tunnel): UdpCom——UDP 9994 物理收发与 protect 防环回

DatagramSocket 绑定 ZeroTier 标准端口，establish 前经 VpnService.protect
豁免 TUN 路由（不 protect 会形成物理包环回黑洞，Android 特有约束）；
收包专职线程 + 代号退出（shutdown close 触发 receive 抛出）；发送走
PacketSender 桥（ZT 原生线程直调 sendto 语义）。"
```

---

### Task 4: 数据面（帧转换六文件 + Dataplane）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/Ethernet.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/ArpTable.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/NdpTable.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/NetworkContext.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/FrameConverter.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/RoutePlanner.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/Dataplane.kt`

**Interfaces:**
- Consumes: T2 NodeRuntime.processVirtualNetworkFrame；T2 NetConf
- Produces:
  - `FrameConverter(runtime: NodeRuntime)`：`handlePacketsFromTun(packets: List<ByteArray>)`、`handleFrameFromCore(nwid, srcMac, dstMac, etherType: Int, frame: ByteArray): List<ByteArray>`、`updateNetworkInfo(nwid, enabled, mac, ipv4: List<String>, ipv6: List<String>, routes: List<String>)`、`removeNetwork(nwid)`、`retainOnly(nwids: Set<Long>)`、`sendKeepalive()`、`debugSummary(): String`
  - `Dataplane(onTunPacket: (ByteArray) -> Unit)`：`swap(fd: ParcelFileDescriptor)`、`writePackets(packets: List<ByteArray>)`、`shutdown()`
  - `RoutePlanner`：`RouteEntry(addr: String, prefix: Int)`、`IPPrefix(bytes, length)`、`expandV4/expandV6(cidr: String, holes: List<IPPrefix>): List<RouteEntry>`、`physicalOnLinkPrefixes4/6(excludingNetworksOf: List<String>): List<IPPrefix>`、`ipOnly/isDefaultRoute/prefixLen/networkCIDR4/networkCIDR6`
  - `NetworkContextMath`：`detectConflicts(ordered: List<Triple<Long, List<String>, List<String>>>): Map<Long, String>`、`parseIPv4(s): UInt?`、`parseIPv6(s): Ipv6Address?`、`parseIPv4Route/parseIPv6Route`

**语义基准**：FrameConverter/NetworkContext/ArpTable/NdpTable 逐段对齐 iOS 同名文件（PlanetPortTunnel/）；IPv6 地址键用 `Ipv6Address` 包装类（ByteArray 作 map key 需 contentEquals/hashCode）。

- [ ] **Step 1: 写 Ethernet.kt**

```kotlin
package cn.imgozi.planetport.tunnel

/** 以太网常量（帧本体不经过 Node——SDK 语义为 MAC/ethertype 独立传参） */
internal object Ethernet {
    const val TYPE_IPV4 = 0x0800
    const val TYPE_ARP = 0x0806
    const val TYPE_IPV6 = 0x86DD
    /** 48 位全 1 广播地址（0xffffffffffff；Kotlin Long 字面量溢出，取负补码表示） */
    const val BROADCAST_MAC = -0x1000000000000L
}
```

**注意**：`0xffffffffffffL` 在 Kotlin 是 Long 溢出字面量（编译错），必须用负补码写法；KDoc 注明等值关系。

- [ ] **Step 2: 写 ArpTable.kt**

```kotlin
package cn.imgozi.planetport.tunnel

/** IPv4 ARP 表（IP 统一 UInt host order；报文构造/解析 RFC 826，字段大端） */
internal class ArpTable {
    private val map = HashMap<UInt, Long>()

    fun lookup(ip: UInt): Long? = synchronized(this) { map[ip] }
    fun update(ip: UInt, mac: Long) = synchronized(this) { map[ip] = mac }
    val count: Int get() = synchronized(this) { map.size }

    internal class ArpPacket(
        val op: Int,          // 1 请求 2 应答
        val senderMac: Long,
        val senderIp: UInt,
        val targetMac: Long,
        val targetIp: UInt,
    )

    companion object {
        private val HEADER = byteArrayOf(0, 1, 0x08, 0, 6, 4)  // htype=1 ptype=IPv4 hlen=6 plen=4

        fun request(senderIp: UInt, senderMac: Long, targetIp: UInt): ByteArray {
            val p = ByteWriter(28)
            p.bytes(HEADER); p.u16(1)
            p.mac(senderMac); p.ip(senderIp)
            p.mac(0); p.ip(targetIp)
            return p.build()
        }

        fun reply(senderIp: UInt, senderMac: Long, targetIp: UInt, targetMac: Long): ByteArray {
            val p = ByteWriter(28)
            p.bytes(HEADER); p.u16(2)
            p.mac(senderMac); p.ip(senderIp)
            p.mac(targetMac); p.ip(targetIp)
            return p.build()
        }

        fun parse(payload: ByteArray): ArpPacket? {
            if (payload.size < 28) return null
            if (payload[0] != 0.toByte() || payload[1] != 1.toByte() ||
                payload[2] != 0x08.toByte() || payload[3] != 0.toByte() ||
                payload[4] != 6.toByte() || payload[5] != 4.toByte()
            ) return null
            val op = ((payload[6].toInt() and 0xff) shl 8) or (payload[7].toInt() and 0xff)
            if (op != 1 && op != 2) return null
            return ArpPacket(
                op = op,
                senderMac = mac(payload, 8),
                senderIp = ip(payload, 14),
                targetMac = mac(payload, 18),
                targetIp = ip(payload, 24),
            )
        }

        private fun mac(p: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 6) v = (v shl 8) or (p[o + i].toLong() and 0xff)
            return v
        }

        private fun ip(p: ByteArray, o: Int): UInt {
            var v = 0u
            for (i in 0 until 4) v = (v shl 8) or (p[o + i].toUInt() and 0xffu)
            return v
        }
    }
}

/** 大端字节流构造器（ARP/NDP 报文构造用） */
internal class ByteWriter(capacity: Int) {
    private val buf = ByteArray(capacity)
    private var pos = 0

    fun bytes(b: ByteArray) { System.arraycopy(b, 0, buf, pos, b.size); pos += b.size }
    fun u16(v: Int) { buf[pos++] = (v shr 8).toByte(); buf[pos++] = (v and 0xff).toByte() }
    fun mac(m: Long) {
        for (shift in 40 downTo 0 step 8) buf[pos++] = ((m shr shift) and 0xff).toByte()
    }
    fun ip(v: UInt) {
        for (shift in 24 downTo 0 step 8) buf[pos++] = ((v shr shift) and 0xffu).toByte()
    }
    fun build(): ByteArray = buf
}
```

- [ ] **Step 3: 写 NdpTable.kt（IPv6 地址键用 Ipv6Address）**

```kotlin
package cn.imgozi.planetport.tunnel

/** IPv6 地址值对象（ByteArray 作 map key 需按内容判等） */
internal class Ipv6Address(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Ipv6Address && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** IPv6 邻居发现表（NS/NA 软件处理，对齐 iOS NDPTable） */
internal class NdpTable {
    private val map = HashMap<Ipv6Address, Long>()

    fun lookup(v6: Ipv6Address): Long? = synchronized(this) { map[v6] }
    fun update(v6: Ipv6Address, mac: Long) = synchronized(this) { map[v6] = mac }
    val count: Int get() = synchronized(this) { map.size }

    internal class NdpPacket(
        val type: Int,             // 135 NS / 136 NA
        val target: Ipv6Address,
        val senderMac: Long?,
    )

    companion object {
        /** NS/NA 的 ICMPv6 载荷（4 字节头 + 16 字节 target + 源 MAC 选项） */
        fun neighborSolicitation(target: Ipv6Address, senderMac: Long): ByteArray =
            icmpv6(135, target, senderMac, flagSolicited = false)

        fun neighborAdvertisement(target: Ipv6Address, senderMac: Long): ByteArray =
            icmpv6(136, target, senderMac, flagSolicited = true)

        /** IPv6 组播地址 → 组播 MAC（0x3333 + 低 32 位） */
        fun multicastMAC(v6: Ipv6Address): Long {
            val b = v6.bytes
            return 0x333300000000L or
                ((b[12].toLong() and 0xff) shl 24) or ((b[13].toLong() and 0xff) shl 16) or
                ((b[14].toLong() and 0xff) shl 8) or (b[15].toLong() and 0xff)
        }

        /** 请求节点组播地址（ff02::1:ffXX:XXXX） */
        fun solicitedNodeMulticast(target: Ipv6Address): Ipv6Address {
            val t = target.bytes
            val m = ByteArray(16)
            m[0] = 0xff.toByte(); m[1] = 0x02.toByte()
            m[11] = 0x01; m[12] = 0xff.toByte()
            m[13] = t[13]; m[14] = t[14]; m[15] = t[15]
            return Ipv6Address(m)
        }

        private fun icmpv6(type: Int, target: Ipv6Address, mac: Long, flagSolicited: Boolean): ByteArray {
            val p = ByteArray(4 + 16 + 8)
            p[0] = type.toByte(); p[1] = 0
            p[4] = if (flagSolicited) 0x60 else 0  // NA flags: Solicited+Override（RFC 4861 offset 4；NS 此处保留 0）
            target.bytes.copyInto(p, 4)
            p[20] = 1; p[21] = 1  // 源链路层地址选项
            for (shift in 40 downTo 0 step 8) {
                p[22 + (5 - shift / 8)] = ((mac shr shift) and 0xff).toByte()
            }
            return p
        }

        fun parse(payload: ByteArray): NdpPacket? {
            if (payload.size < 24) return null
            if (payload[0] != 135.toByte() && payload[0] != 136.toByte()) return null
            val target = Ipv6Address(payload.copyOfRange(8, 24))
            var senderMac: Long? = null
            if (payload.size >= 32 && payload[24] == 1.toByte() && payload[25] == 1.toByte()) {
                var v = 0L
                for (i in 2 until 8) v = (v shl 8) or (payload[24 + i].toLong() and 0xff)
                senderMac = v
            }
            return NdpPacket(payload[0].toInt(), target, senderMac)
        }

        /** 是否 NDP 报文（完整 IPv6 包：next header 58 且 ICMPv6 type 135/136） */
        fun isNDP(ipv6Packet: ByteArray): Boolean {
            if (ipv6Packet.size < 48) return false
            if (ipv6Packet[6] != 58.toByte()) return false
            val t = ipv6Packet[40].toInt() and 0xff
            return t == 135 || t == 136
        }
    }
}
```

**注意**：iOS NS/NA 构造带 `senderIPv6` 参数但两处调用均传 nil——Android 版直接去掉该参数（YAGNI）。checksum 由核心侧校验豁免（ZT 虚拟网内 NDP 软件处理不校验 ICMPv6 校验和，iOS 同语义）。

- [ ] **Step 4: 写 NetworkContext.kt（路由条目/网络上下文/配置期纯逻辑）**

```kotlin
package cn.imgozi.planetport.tunnel

/** IPv4 路由条目（地址均 UInt host order） */
internal class IPv4RouteEntry(val network: UInt, val prefix: Int, val gateway: UInt?) {
    fun matches(address: UInt): Boolean {
        val mask: UInt = if (prefix == 0) 0u else UInt.MAX_VALUE shl (32 - prefix)
        return address and mask == network and mask
    }
}

/** IPv6 路由条目 */
internal class IPv6RouteEntry(val network: Ipv6Address, val prefix: Int, val gateway: Ipv6Address?) {
    fun matches(address: Ipv6Address): Boolean {
        val n = network.bytes; val a = address.bytes
        val fullBytes = prefix / 8
        for (i in 0 until fullBytes) if (n[i] != a[i]) return false
        val remaining = prefix % 8
        if (remaining == 0) return true
        val mask = (0xff shl (8 - remaining)).toByte()
        return n[fullBytes] and mask == a[fullBytes] and mask
    }
}

/** 聚合表条目：路由 + 归属网络（出站最长前缀匹配选网络） */
internal class AggregateV4Entry(val nwid: Long, val route: IPv4RouteEntry)
internal class AggregateV6Entry(val nwid: Long, val route: IPv6RouteEntry)

/**
 * 单个网络在数据面的全部隔离状态：MAC、地址、ARP/NDP、待决包、路由
 * 互不串网——多网络隔离的最小单元。
 */
internal class NetworkContext(
    val nwid: Long,
    mac: Long,
    ipv4Strings: List<String>,
    ipv6Strings: List<String>,
    routes: List<String>,
) {
    var mac = mac; private set
    var ipv4Addresses: List<UInt> = emptyList(); private set
    var ipv6Addresses: List<Ipv6Address> = emptyList(); private set
    var routesV4: List<IPv4RouteEntry> = emptyList(); private set
    var routesV6: List<IPv6RouteEntry> = emptyList(); private set
    val arp = ArpTable()
    val ndp = NdpTable()
    /** 等 ARP/NA 应答的包（每下一跳上限 FrameConverter.MAX_PENDING_PER_TARGET） */
    val pendingV4 = HashMap<UInt, MutableList<ByteArray>>()
    val pendingV6 = HashMap<Ipv6Address, MutableList<ByteArray>>()
    // 诊断计数（FrameConverter 锁内递增，仅诊断展示）
    var statFwdV4 = 0; var statArpReq = 0; var statArpReply = 0
    var statInV4 = 0; var statKeepalive = 0

    init { update(mac, ipv4Strings, ipv6Strings, routes) }

    /**
     * 配置回调更新：仅刷新 MAC/地址/路由；ARP/NDP/pending 跨回调保留——
     * ZT 配置回调并不稀少（成员/路径变化即触发），整建重建会清缓存导致
     * ARP 风暴重放、在途待决包被静默丢弃。
     */
    fun update(mac: Long, ipv4Strings: List<String>, ipv6Strings: List<String>, routes: List<String>) {
        this.mac = mac
        ipv4Addresses = ipv4Strings.mapNotNull { NetworkContextMath.parseIPv4(RoutePlanner.ipOnly(it)) }
        ipv6Addresses = ipv6Strings.mapNotNull { NetworkContextMath.parseIPv6(RoutePlanner.ipOnly(it)) }
        routesV4 = (ipv4Strings.map { RoutePlanner.networkCIDR4(it) } + routes)
            .mapNotNull(NetworkContextMath::parseIPv4Route)
            .sortedByDescending { it.prefix }
        routesV6 = (ipv6Strings.map { RoutePlanner.networkCIDR6(it) } + routes)
            .mapNotNull(NetworkContextMath::parseIPv6Route)
            .sortedByDescending { it.prefix }
    }
}

/** 路由解析、冲突检测（后进入者让路）、跨网络聚合表构建（配置期纯逻辑，转发路径不碰） */
internal object NetworkContextMath {

    /** 冲突原因原始码（文案 UI 层生成，对齐 iOS NetworkConflict） */
    const val CONFLICT_ADDRESS = "address"
    const val CONFLICT_SUBNET = "subnet"

    /**
     * 冲突检测：输入按激活顺序。先到者登记已占 IP 与直连网段；后进入者
     * 撞车标记冲突（地址冲突优先于网段冲突）且不登记先占。冲突网络由
     * 调用方排除出数据面，保留 join 仅展示状态。
     */
    fun detectConflicts(ordered: List<Triple<Long, List<String>, List<String>>>): Map<Long, String> {
        val claimedIp4 = HashSet<UInt>()
        val claimedIp6 = HashSet<Ipv6Address>()
        val claimedSubnets = HashSet<String>()
        val conflicts = HashMap<Long, String>()
        for ((nwid, ipv4, ipv6) in ordered) {
            val subnets = ipv4.map { RoutePlanner.networkCIDR4(it) } +
                ipv6.map { RoutePlanner.networkCIDR6(it) }
            val ips4 = ipv4.mapNotNull { parseIPv4(RoutePlanner.ipOnly(it)) }
            val ips6 = ipv6.mapNotNull { parseIPv6(RoutePlanner.ipOnly(it)) }
            val conflict = when {
                ips4.any { it in claimedIp4 } || ips6.any { it in claimedIp6 } -> CONFLICT_ADDRESS
                subnets.any { it in claimedSubnets } -> CONFLICT_SUBNET
                else -> null
            }
            if (conflict != null) {
                conflicts[nwid] = conflict
                continue
            }
            claimedIp4.addAll(ips4)
            claimedIp6.addAll(ips6)
            claimedSubnets.addAll(subnets)
        }
        return conflicts
    }

    /** IPv4 聚合表：全部网络路由，前缀降序、同前缀按激活顺序（先到者胜） */
    fun buildAggregateV4(ordered: List<NetworkContext>): List<AggregateV4Entry> =
        ordered.flatMapIndexed { index, ctx ->
            ctx.routesV4.map { Triple(it.prefix, index, AggregateV4Entry(ctx.nwid, it)) }
        }.sortedWith(compareByDescending<Triple<Int, Int, AggregateV4Entry>> { it.first }.thenBy { it.second })
            .map { it.third }

    fun buildAggregateV6(ordered: List<NetworkContext>): List<AggregateV6Entry> =
        ordered.flatMapIndexed { index, ctx ->
            ctx.routesV6.map { Triple(it.prefix, index, AggregateV6Entry(ctx.nwid, it)) }
        }.sortedWith(compareByDescending<Triple<Int, Int, AggregateV6Entry>> { it.first }.thenBy { it.second })
            .map { it.third }

    /** "x.x.x.x" → UInt host order（手写解析：不走 InetAddress，避免任何解析服务介入） */
    fun parseIPv4(s: String): UInt? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0u
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || (p.length > 1 && p[0] == '0')) return null
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            v = (v shl 8) or n.toUInt()
        }
        return v
    }

    /** "x::y" → Ipv6Address（先格式校验再 InetAddress 解析——纯字面量不触发主机名查询） */
    fun parseIPv6(s: String): Ipv6Address? {
        if (!s.matches(Regex("^[0-9a-fA-F:.%]+$"))) return null
        return try {
            val addr = java.net.InetAddress.getByName(s)
            if (addr is java.net.Inet6Address) Ipv6Address(addr.address) else null
        } catch (e: Exception) {
            null
        }
    }

    /** "10.0.0.0/24" 或 "10.0.0.0/24 via 10.0.0.1" → IPv4RouteEntry */
    fun parseIPv4Route(value: String): IPv4RouteEntry? {
        val fields = value.split(' ')
        val target = fields.firstOrNull() ?: return null
        val parts = target.split('/')
        if (parts.size != 2) return null
        val address = parseIPv4(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val gateway = fields.indexOf("via").takeIf { it >= 0 }
            ?.let { fields.getOrNull(it + 1) }?.let { parseIPv4(ipOnly(it)) }
        return IPv4RouteEntry(address, prefix, gateway)
    }

    fun parseIPv6Route(value: String): IPv6RouteEntry? {
        val fields = value.split(' ')
        val target = fields.firstOrNull() ?: return null
        val parts = target.split('/')
        if (parts.size != 2) return null
        val address = parseIPv6(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..128) return null
        val gateway = fields.indexOf("via").takeIf { it >= 0 }
            ?.let { fields.getOrNull(it + 1) }?.let { parseIPv6(ipOnly(it)) }
        return IPv6RouteEntry(address, prefix, gateway)
    }

    fun ipOnly(cidr: String): String = cidr.substringBefore('/')

    /** UInt host order → 点分十进制（FrameConverter/RoutePlanner 共用） */
    fun dotted(ip: UInt): String {
        val a = (ip shr 24) and 0xffu
        val b = (ip shr 16) and 0xffu
        val c = (ip shr 8) and 0xffu
        val d = ip and 0xffu
        return "$a.$b.$c.$d"
    }
}
```

- [ ] **Step 5: 写 FrameConverter.kt（转发热路径）**

```kotlin
package cn.imgozi.planetport.tunnel

/**
 * IP 包 ↔ 虚拟网帧转换层。
 * - 下行（tun→核心）：L3 IP 包 → 聚合路由表最长前缀匹配选网络 → 查该网 ARP/NDP
 *   → processVirtualNetworkFrame
 * - 上行（核心→tun）：帧回调按 nwid 分流到对应 NetworkContext
 * 多网络隔离：MAC/地址/ARP/NDP/pending/路由全部按网络存放，互不串网。
 * 全方法持单一锁（对齐 iOS NSLock 模型）；锁内调用 Node JNI（核心内部有锁，
 * 不构成反向进入本类的路径）。
 */
internal class FrameConverter(private val runtime: NodeRuntime) {
    private val lock = Any()
    private val contexts = HashMap<Long, NetworkContext>()
    /** 激活顺序（首次 upsert 追加；聚合表同前缀先到者胜的裁决依据） */
    private val order = ArrayList<Long>()
    private var aggregateV4: List<AggregateV4Entry> = emptyList()
    private var aggregateV6: List<AggregateV6Entry> = emptyList()
    private var statNoRoute = 0

    companion object {
        /** 每下一跳待决包上限（超出丢包，防移动端内存失控） */
        const val MAX_PENDING_PER_TARGET = 16
    }

    // MARK: - 上下文管理（配置回调路径）

    fun updateNetworkInfo(
        nwid: Long, enabled: Boolean, mac: Long,
        ipv4: List<String>, ipv6: List<String>, routes: List<String>,
    ) {
        synchronized(lock) {
            if (!enabled) {
                removeNetworkLocked(nwid)
                return
            }
            val ctx = contexts[nwid]
            if (ctx != null) {
                ctx.update(mac, ipv4, ipv6, routes)
            } else {
                order.add(nwid)
                contexts[nwid] = NetworkContext(nwid, mac, ipv4, ipv6, routes)
            }
            rebuildAggregatesLocked()
        }
    }

    fun removeNetwork(nwid: Long) {
        synchronized(lock) { removeNetworkLocked(nwid) }
    }

    /** reconcile：仅保留给定网络集合，清理幽灵上下文（读文件与 leave 并发时防残留） */
    fun retainOnly(nwids: Set<Long>) {
        synchronized(lock) {
            val removed = contexts.keys.filter { it !in nwids }
            if (removed.isEmpty()) return
            for (nwid in removed) {
                contexts.remove(nwid)
                order.remove(nwid)
            }
            rebuildAggregatesLocked()
        }
    }

    private fun removeNetworkLocked(nwid: Long) {
        if (contexts.remove(nwid) == null) return
        order.remove(nwid)
        rebuildAggregatesLocked()
    }

    private fun rebuildAggregatesLocked() {
        val ordered = orderedContextsLocked()
        aggregateV4 = NetworkContextMath.buildAggregateV4(ordered)
        aggregateV6 = NetworkContextMath.buildAggregateV6(ordered)
    }

    private fun orderedContextsLocked(): List<NetworkContext> = order.mapNotNull { contexts[it] }

    /** 诊断快照（验证页/排查用） */
    fun debugSummary(): String = synchronized(lock) {
        val lines = mutableListOf(
            "contexts=${contexts.size} aggV4=${aggregateV4.size} aggV6=${aggregateV6.size} 无路由丢弃=$statNoRoute"
        )
        for (nwid in order) {
            val c = contexts[nwid] ?: continue
            lines.add(
                "ctx %016x mac=%012x v4=[%s] routes=${c.routesV4.size}/${c.routesV6.size} " +
                    "出站=${c.statFwdV4} 入站=${c.statInV4} ARP问=${c.statArpReq} ARP答=${c.statArpReply} " +
                    "保活=${c.statKeepalive} ARP表=${c.arp.count} 待决=${c.pendingV4.values.sumOf { it.size }}"
                    .format(nwid, c.mac, c.ipv4Addresses.joinToString(",") { NetworkContextMath.dotted(it) })
            )
        }
        lines.joinToString("\n")
    }

    // MARK: - tun -> core（Dataplane 读线程调用）

    fun handlePacketsFromTun(packets: List<ByteArray>) {
        synchronized(lock) {
            if (contexts.isEmpty()) return
            for (pkt in packets) {
                if (pkt.size < 20) continue
                when ((pkt[0].toInt() shr 4) and 0xf) {
                    4 -> handleV4Locked(pkt)
                    6 -> if (pkt.size >= 40) handleV6Locked(pkt)
                }
            }
        }
    }

    private fun handleV4Locked(pkt: ByteArray) {
        val dst = bigEndianIP32(pkt, 16)
        // 广播/组播直送所有有 IPv4 地址的网络（组播包发对应组播 MAC）
        if (dst == 0xffffffffu || isIPv4Multicast(dst)) {
            val dstMac = if (dst == 0xffffffffu) Ethernet.BROADCAST_MAC else ipv4MulticastMAC(dst)
            for (ctx in orderedContextsLocked()) {
                if (ctx.ipv4Addresses.isEmpty() || ctx.mac == 0L) continue
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, dstMac, Ethernet.TYPE_IPV4, 0, pkt
                )
            }
            return
        }
        // 聚合表最长前缀匹配（前缀降序、同前缀先激活者胜）；无命中丢弃
        val match = aggregateV4.firstOrNull { it.route.matches(dst) } ?: run {
            statNoRoute++
            return
        }
        val ctx = contexts[match.nwid] ?: return
        if (ctx.mac == 0L) return
        val nextHop = match.route.gateway ?: dst
        val mac = ctx.arp.lookup(nextHop)
        if (mac == null) {
            val pending = ctx.pendingV4.getOrPut(nextHop) { mutableListOf() }
            if (pending.size < MAX_PENDING_PER_TARGET) pending.add(pkt)
            ctx.statArpReq++
            sendARPRequestLocked(ctx, nextHop)
            return
        }
        runtime.processVirtualNetworkFrame(ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV4, 0, pkt)
        ctx.statFwdV4++
    }

    private fun handleV6Locked(pkt: ByteArray) {
        val dstBytes = pkt.copyOfRange(24, 40)
        val dst = Ipv6Address(dstBytes)
        if (isIPv6Multicast(dstBytes)) {
            val mac = NdpTable.multicastMAC(dst)
            for (ctx in orderedContextsLocked()) {
                if (ctx.ipv6Addresses.isEmpty() || ctx.mac == 0L) continue
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV6, 0, pkt
                )
            }
            return
        }
        val match = aggregateV6.firstOrNull { it.route.matches(dst) } ?: return
        val ctx = contexts[match.nwid] ?: return
        if (ctx.mac == 0L) return
        val nextHop = match.route.gateway?.let { Ipv6Address(it.bytes) } ?: dst
        val mac = ctx.ndp.lookup(nextHop)
        if (mac == null) {
            val pending = ctx.pendingV6.getOrPut(nextHop) { mutableListOf() }
            if (pending.size < MAX_PENDING_PER_TARGET) pending.add(pkt)
            sendNeighborSolicitationLocked(ctx, nextHop)
            return
        }
        runtime.processVirtualNetworkFrame(ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV6, 0, pkt)
    }

    // MARK: - core -> tun（消费协程调用）；返回 IP 包列表写入 tun

    fun handleFrameFromCore(
        nwid: Long, srcMac: Long, dstMac: Long, etherType: Int, frame: ByteArray,
    ): List<ByteArray> {
        synchronized(lock) {
            val ctx = contexts[nwid] ?: return emptyList()
            return when (etherType) {
                Ethernet.TYPE_ARP -> {
                    handleARPLocked(ctx, frame, srcMac)
                    emptyList()
                }
                Ethernet.TYPE_IPV6 -> {
                    if (NdpTable.isNDP(frame)) {
                        handleNDPLocked(ctx, frame, srcMac)
                        emptyList()
                    } else {
                        listOf(frame)
                    }
                }
                Ethernet.TYPE_IPV4 -> {
                    ctx.statInV4++
                    listOf(frame)
                }
                else -> emptyList()
            }
        }
    }

    /** ARP 处理（仅更新本网络缓存）：请求对本网持有地址应答；应答更新表并冲刷待决 */
    private fun handleARPLocked(ctx: NetworkContext, payload: ByteArray, senderMac: Long) {
        val p = ArpTable.parse(payload) ?: return
        when (p.op) {
            1 -> if (ctx.ipv4Addresses.contains(p.targetIp)) {
                val reply = ArpTable.reply(p.targetIp, ctx.mac, p.senderIp, p.senderMac)
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, senderMac, Ethernet.TYPE_ARP, 0, reply
                )
            }
            2 -> {
                ctx.statArpReply++
                ctx.arp.update(p.senderIp, p.senderMac)
                val waiting = ctx.pendingV4.remove(p.senderIp)
                if (waiting != null) {
                    for (pkt in waiting) {
                        runtime.processVirtualNetworkFrame(
                            ctx.nwid, ctx.mac, p.senderMac, Ethernet.TYPE_IPV4, 0, pkt
                        )
                    }
                }
            }
        }
    }

    private fun handleNDPLocked(ctx: NetworkContext, packet: ByteArray, senderMac: Long) {
        val icmp6 = packet.copyOfRange(40, packet.size)
        val srcIPv6 = Ipv6Address(packet.copyOfRange(8, 24))
        val p = NdpTable.parse(icmp6) ?: return
        when (p.type) {
            135 -> if (ctx.ipv6Addresses.contains(p.target)) {
                val na = NdpTable.neighborAdvertisement(p.target, ctx.mac)
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, senderMac, Ethernet.TYPE_IPV6, 0,
                    ipv6Packet(na, srcIPv6),
                )
            }
            136 -> {
                val mac = p.senderMac ?: return
                ctx.ndp.update(p.target, mac)
                val waiting = ctx.pendingV6.remove(p.target)
                if (waiting != null) {
                    for (pkt in waiting) {
                        runtime.processVirtualNetworkFrame(
                            ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV6, 0, pkt
                        )
                    }
                }
            }
        }
    }

    /** 闲置保活：每活跃网络广播一个 ARP 探测帧（防 peer 路径/NAT 冷却死亡） */
    fun sendKeepalive() {
        synchronized(lock) {
            for (ctx in orderedContextsLocked()) {
                if (ctx.mac == 0L) continue
                val probeIp = ctx.ipv4Addresses.firstOrNull() ?: continue
                ctx.statKeepalive++
                sendARPRequestLocked(ctx, probeIp)
            }
        }
    }

    // MARK: - 控制帧

    private fun sendARPRequestLocked(ctx: NetworkContext, target: UInt) {
        val req = ArpTable.request(ctx.ipv4Addresses.firstOrNull() ?: 0u, ctx.mac, target)
        runtime.processVirtualNetworkFrame(
            ctx.nwid, ctx.mac, Ethernet.BROADCAST_MAC, Ethernet.TYPE_ARP, 0, req
        )
    }

    private fun sendNeighborSolicitationLocked(ctx: NetworkContext, target: Ipv6Address) {
        val ns = NdpTable.neighborSolicitation(target, ctx.mac)
        runtime.processVirtualNetworkFrame(
            ctx.nwid, ctx.mac,
            NdpTable.multicastMAC(NdpTable.solicitedNodeMulticast(target)),
            Ethernet.TYPE_IPV6, 0, ipv6Packet(ns, target),
        )
    }

    /** ICMPv6 载荷包成最小 IPv6 包（next header 58，hop limit 255，src ::） */
    private fun ipv6Packet(payload: ByteArray, dst: Ipv6Address): ByteArray {
        val p = ByteArray(40 + payload.size)
        p[0] = 0x60
        p[4] = ((payload.size shr 8) and 0xff).toByte()
        p[5] = (payload.size and 0xff).toByte()
        p[6] = 58
        p[7] = 255
        dst.bytes.copyInto(p, 24)
        payload.copyInto(p, 40)
        return p
    }

    // MARK: - 地址工具

    private fun bigEndianIP32(pkt: ByteArray, offset: Int): UInt =
        ((pkt[offset].toUInt() and 0xffu) shl 24) or
            ((pkt[offset + 1].toUInt() and 0xffu) shl 16) or
            ((pkt[offset + 2].toUInt() and 0xffu) shl 8) or
            (pkt[offset + 3].toUInt() and 0xffu)

    private fun isIPv4Multicast(ip: UInt) = (ip shr 28) == 0xEu

    private fun isIPv6Multicast(addr: ByteArray) = addr[0] == 0xff.toByte()

    /** IPv4 组播 → 01:00:5e:0f:ff:ff 系组播 MAC（低 23 位，最高位清零） */
    private fun ipv4MulticastMAC(ip: UInt): Long =
        0x01005e000000L or (ip and 0x7FFFFFu).toLong()
}
```

- [ ] **Step 6: 写 RoutePlanner.kt（路由数学；挖洞差集逐字对齐 iOS）**

```kotlin
package cn.imgozi.planetport.tunnel

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Builder.addRoute 直接可用的路由条目 */
internal class RouteEntry(val addr: String, val prefix: Int)

/** 网络序 IP 前缀（IPv4 用 4 字节，IPv6 用 16 字节） */
internal class IPPrefix(val bytes: ByteArray, val length: Int) {
    override fun equals(other: Any?): Boolean =
        other is IPPrefix && length == other.length && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * length + bytes.contentHashCode()

    /** self ⊇ other（other 完全落在 self 网段内） */
    fun contains(other: IPPrefix): Boolean {
        if (length > other.length) return false
        for (i in 0 until length) if (bit(i) != other.bit(i)) return false
        return true
    }

    /** 一分为二（二叉前缀分解用；length < 128 保证不越界） */
    fun split(): Pair<IPPrefix, IPPrefix> {
        val byteIndex = length / 8
        val mask = (1 shl (7 - length % 8))
        val left = bytes.copyOf(); val right = bytes.copyOf()
        left[byteIndex] = (left[byteIndex].toInt() and mask.inv() and 0xff).toByte()
        right[byteIndex] = (right[byteIndex].toInt() or mask and 0xff).toByte()
        return IPPrefix(left, length + 1) to IPPrefix(right, length + 1)
    }

    private fun bit(i: Int): Int = (bytes[i / 8].toInt() shr (7 - i % 8)) and 1
}

/**
 * 路由数学与物理接口探测（纯函数无状态，对齐 iOS RoutePlanner）：
 * CIDR 换算、前缀差集（挖除物理 on-link 子网）、物理接口枚举。
 * Android 无 excludedRoutes 概念——挖洞同样用差集展开（Builder 只会
 * addRoute「抓进 TUN」的前缀，差集展开后物理网段自然留在物理网卡）。
 */
internal object RoutePlanner {

    private fun subtract(dest: IPPrefix, hole: IPPrefix): List<IPPrefix> {
        if (dest == hole || hole.contains(dest)) return emptyList()
        if (!dest.contains(hole)) return listOf(dest)
        val (left, right) = dest.split()
        return subtract(left, hole) + subtract(right, hole)
    }

    /** IPv4 CIDR（可带 "via 网关"）挖除若干前缀后展开 */
    fun expandV4(r: String, holes: List<IPPrefix>): List<RouteEntry> {
        val dest = r.split(' ').firstOrNull() ?: r
        val ip = NetworkContextMath.parseIPv4(NetworkContextMath.ipOnly(dest)) ?: return emptyList()
        var result = listOf(
            IPPrefix(
                byteArrayOf(
                    ((ip shr 24) and 0xffu).toByte(), ((ip shr 16) and 0xffu).toByte(),
                    ((ip shr 8) and 0xffu).toByte(), (ip and 0xffu).toByte(),
                ),
                prefixLen(dest).coerceAtMost(32),
            )
        )
        for (hole in holes) result = result.flatMap { subtract(it, hole) }
        return result.map { RouteEntry(v4String(it.bytes), it.length) }
    }

    fun expandV6(r: String, holes: List<IPPrefix>): List<RouteEntry> {
        val dest = r.split(' ').firstOrNull() ?: r
        val addr = NetworkContextMath.parseIPv6(NetworkContextMath.ipOnly(dest)) ?: return emptyList()
        var result = listOf(IPPrefix(addr.bytes, prefixLen(dest).coerceAtMost(128)))
        for (hole in holes) result = result.flatMap { subtract(it, hole) }
        return result.map { RouteEntry(v6String(it.bytes), it.length) }
    }

    /**
     * 物理接口 on-link IPv4 子网 → 待挖除前缀集合。ZT managed routes
     * （如 0.0.0.0/0）会把本地网关/DNS 抓进 TUN 黑洞，挖除后本地子网
     * 仍走物理网卡。excludingNetworksOf：子网与 ZT 分配地址所在网段相同
     * 时跳过，避免反过来打断 ZeroTier。
     */
    fun physicalOnLinkPrefixes4(excludingNetworksOf assigned: List<String>): List<IPPrefix> {
        val assignedNetworks = assigned.map { networkCIDR4(it) }.toHashSet()
        val seen = HashSet<String>()
        val prefixes = ArrayList<IPPrefix>()
        forEachPhysicalInterfaceAddress { address, prefix ->
            if (address !is Inet4Address) return@forEachPhysicalInterfaceAddress
            val network = maskBytes(address.address, prefix)
            val cidr = "${v4String(network)}/$prefix"
            if (cidr in seen || cidr in assignedNetworks) return@forEachPhysicalInterfaceAddress
            seen.add(cidr)
            prefixes.add(IPPrefix(network, prefix))
        }
        return prefixes
    }

    /** 物理接口 on-link IPv6 子网（跳过 fe80:: 链路本地——本就不经默认路由） */
    fun physicalOnLinkPrefixes6(excludingNetworksOf assigned: List<String>): List<IPPrefix> {
        val assignedNetworks = assigned.map { networkCIDR6(it) }.toHashSet()
        val seen = HashSet<String>()
        val prefixes = ArrayList<IPPrefix>()
        forEachPhysicalInterfaceAddress { address, prefix ->
            if (address !is Inet6Address) return@forEachPhysicalInterfaceAddress
            if (address.isLinkLocalAddress) return@forEachPhysicalInterfaceAddress
            val network = maskBytes(address.address, prefix)
            val cidr = "${v6String(network)}/$prefix"
            if (cidr in seen || cidr in assignedNetworks) return@forEachPhysicalInterfaceAddress
            seen.add(cidr)
            prefixes.add(IPPrefix(network, prefix))
        }
        return prefixes
    }

    /** 枚举物理接口（wlan/eth/rmnet/usb——排除 lo/tun 等虚拟接口）的全部接口地址 */
    private inline fun forEachPhysicalInterfaceAddress(block: (InetAddress, Int) -> Unit) {
        val nifs = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        } ?: return
        for (nif in nifs) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name
            if (!(name.startsWith("wlan") || name.startsWith("eth") ||
                    name.startsWith("rmnet") || name.startsWith("usb"))) continue
            for (ia in nif.interfaceAddresses) {
                val a = ia.address ?: continue
                block(a, ia.networkPrefixLength.toInt())
            }
        }
    }

    private fun maskBytes(bytes: ByteArray, prefix: Int): ByteArray {
        val out = bytes.copyOf()
        for (bit in prefix.coerceAtLeast(0) until out.size * 8) {
            out[bit / 8] = (out[bit / 8].toInt() and (1 shl (7 - bit % 8)).inv() and 0xff).toByte()
        }
        return out
    }

    // MARK: - CIDR 换算

    fun ipOnly(cidr: String): String = cidr.substringBefore('/')

    fun isDefaultRoute(route: String): Boolean {
        val destination = route.split(' ').firstOrNull() ?: route
        return destination == "0.0.0.0/0" || destination == "::/0"
    }

    /** "fd00::/24" → 24（无前缀按 64，对齐 iOS） */
    fun prefixLen(cidr: String): Int = cidr.substringAfter('/', "64").toIntOrNull() ?: 64

    fun networkCIDR4(cidr: String): String {
        val prefix = prefixLen(cidr).coerceAtMost(32)
        val ip = NetworkContextMath.parseIPv4(ipOnly(cidr)) ?: return cidr
        val mask: UInt = if (prefix == 0) 0u else UInt.MAX_VALUE shl (32 - prefix)
        return "${NetworkContextMath.dotted(ip and mask)}/$prefix"
    }

    fun networkCIDR6(cidr: String): String {
        val prefix = prefixLen(cidr).coerceAtMost(128)
        val addr = NetworkContextMath.parseIPv6(ipOnly(cidr)) ?: return cidr
        return "${v6String(maskBytes(addr.bytes, prefix))}/$prefix"
    }

    private fun v4String(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xff).toString() }

    private fun v6String(bytes: ByteArray): String =
        try {
            InetAddress.getByAddress(bytes).hostAddress ?: "::"
        } catch (e: Exception) {
            "::"
        }
}
```

**注意**：`dotted(UInt)` 在 NetworkContextMath 单一实现（本计划已统一，FrameConverter/RoutePlanner 均引用）。

- [ ] **Step 7: 写 Dataplane.kt（TUN fd 读写，fd 换代）**

```kotlin
package cn.imgozi.planetport.tunnel

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * TUN fd 读写：每次 Builder.establish 返回新 fd（Android 与 iOS 的
 * packetFlow 常驻不同），swap() 负责换代——旧 fd 关闭使读线程退出，
 * 新读线程与专职写线程随新代号启动。
 */
internal class Dataplane(private val onTunPacket: (ByteArray) -> Unit) {
    private var generation = 0
    private var currentFd: ParcelFileDescriptor? = null
    private var writeExecutor: ExecutorService? = null

    @Synchronized
    fun swap(fd: ParcelFileDescriptor) {
        generation += 1
        val gen = generation
        writeExecutor?.shutdownNow()
        currentFd?.close()
        currentFd = fd
        writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "zt-tun-write-$gen") }
        val input = FileInputStream(fd.fileDescriptor)
        Thread({
            val buf = ByteArray(65536)
            while (generation == gen) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    break // fd 关闭
                }
                if (n <= 0) break
                onTunPacket(buf.copyOf(n))
            }
            try { input.close() } catch (_: IOException) {}
        }, "zt-tun-read-$gen").start()
    }

    /** 上行 IP 包写入 tun（消费协程投递，专职写线程顺序化） */
    fun writePackets(packets: List<ByteArray>) {
        if (packets.isEmpty()) return
        val exec = writeExecutor ?: return
        exec.execute {
            val fd = currentFd ?: return@execute
            val out = FileOutputStream(fd.fileDescriptor)
            try {
                for (p in packets) out.write(p)
            } catch (_: IOException) {
                // fd 换代瞬间的写入失败可接受（下次 establish 已重排）
            }
        }
    }

    @Synchronized
    fun shutdown() {
        generation += 1
        writeExecutor?.shutdownNow()
        writeExecutor = null
        currentFd?.close()
        currentFd = null
    }
}
```

**注意**：读循环 `input.read(buf)` 在 TUN 设备上一次返回一个完整 IP 包（与 iOS packetFlow 的批量语义不同）；读到 -1 或 IOException 即线程收尾。

- [ ] **Step 8: 编译验证**

Run: `./gradlew assembleDebug assembleRelease`
Expected: BUILD SUCCESSFUL（T4 文件尚未被 Service 引用，编译通过即接口自洽）

- [ ] **Step 9: 装机冒烟（无引用不崩）**

```bash
adb -s 9311902b install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 9311902b shell am start -n cn.imgozi.a.planetport/cn.imgozi.planetport.ui.MainActivity
```

Expected: 启动无崩溃（logcat 无 FATAL EXCEPTION）。

- [ ] **Step 10: 提交**

```bash
git add -A && git commit -m "feat(tunnel): 数据面——帧转换六件套与 TUN 读写

FrameConverter 承载 L3↔以太网帧双向转换：出站聚合路由表最长前缀匹配
选网、ARP/NDP 软件应答与待决包冲刷（每下一跳上限 16）、组播直送、
45s 闲置保活探测；NetworkContext 按 nwid 隔离全部数据面状态；冲突
检测后进入者让路；RoutePlanner 前缀差集挖除物理 on-link 子网（防
本地网关被 TUN 黑洞）；Dataplane fd 换代读写（每次 establish 重建
读写线程）。语义对齐 iOS PlanetPortTunnel 数据面。"
```

---

### Task 5: TunnelManager（netconf 聚合 → Builder.establish → 全链路接线）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/TunnelManager.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/store/Models.kt`（AppPrefs 加 disableIpv6）
- Modify: `app/src/main/java/cn/imgozi/planetport/store/AppConfigStore.kt`（disableIpv6 读 API）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`（组装 TunnelManager/Dataplane/FrameConverter + 消费协程）

**Interfaces:**
- Consumes: T2 NodeRuntime（frameChannel/configChannel/join/leave/orbit/networkConf/multicastSubscribe/shutdown）；T4 FrameConverter/Dataplane/RoutePlanner/NetworkContextMath/Ethernet.BROADCAST_MAC；P1 AppConfigStore（readActiveNetworks/readActiveMoons/readPlanets/activePlanetId/configChanges/paths/StorePaths）；NetworkEntry.routeViaZeroTier/dnsMode/customDns；DnsMode
- Produces: `TunnelManager(scope, runtime, converter, dataplane, store)`：`start()/stop()`、`suspend onNetworkConfig(nwid, op, conf)`、`suspend applyConfig()`；`AppConfigStore.disableIpv6(): Boolean`；`AppPrefs.disableIpv6: Boolean`

- [ ] **Step 1: Models.kt 的 AppPrefs 加字段（在最后一个字段后追加）**

```kotlin
    /** 数据面禁用 IPv6（对齐 iOS network_disable_ipv6；默认 false） */
    val disableIpv6: Boolean = false,
```

**注意**：加默认值字段对既有 prefs.json 反序列化兼容（kotlinx.serialization 缺字段用默认值）。

- [ ] **Step 2: AppConfigStore.kt 加公开读 API（放在 resetAllNetworkActivation 之后）**

```kotlin
    /** 数据面是否禁用 IPv6（对齐 iOS network_disable_ipv6；写入口 P4 偏好页接） */
    suspend fun disableIpv6(): Boolean = readPrefs().disableIpv6
```

- [ ] **Step 3: 写 TunnelManager.kt（全量）**

```kotlin
package cn.imgozi.planetport.tunnel

import android.net.VpnService
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import com.zerotier.sdk.VirtualNetworkConfigOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * netconf 聚合与 TUN 建立器（对齐 iOS PacketTunnelProvider 的聚合应用路径）。
 *
 * 配置回调只登记快照；合并、冲突检测、Builder 组装与 establish 统一在
 * configMutex 内串行（iOS 靠 configQueue + setTunnelNetworkSettings 异步
 * 单飞；Android establish 同步返回，mutex 串行即等价，无需 dirty 机制）。
 * 去抖 300ms：连续配置回调合并为一次应用。
 */
internal class TunnelManager(
    private val scope: CoroutineScope,
    private val runtime: NodeRuntime,
    private val converter: FrameConverter,
    private val dataplane: Dataplane,
    private val store: AppConfigStore,
) {
    private val configMutex = Mutex()
    private var snapshots = HashMap<Long, NetConf>()
    private var settingsJob: Job? = null
    private var joinedNetworks = HashSet<Long>()
    private var orbitedMoons = HashSet<Long>()
    /** 已应用的用户侧配置（nwid → Triple(routeVia, dnsMode, customDns)），变更热重配 */
    private var appliedEntries = HashMap<Long, Triple<Boolean, DnsMode, List<String>>>()
    private var configChangesJob: Job? = null
    private var keepaliveJob: Job? = null

    fun start() {
        // 配置变更订阅：P1 configChanges 有损合并（16 溢出即丢），
        // 订阅方必须 level-triggered 全量重读——applyConfig 每次读文件现算
        configChangesJob = scope.launch {
            store.configChanges.collect { applyConfig() }
        }
        // 闲置保活：45s 周期广播（防 peer 路径/NAT 冷却死亡，对齐 iOS keepaliveTimer）
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(45_000)
                converter.sendKeepalive()
            }
        }
    }

    fun stop() {
        configChangesJob?.cancel(); configChangesJob = null
        keepaliveJob?.cancel(); keepaliveJob = null
        settingsJob?.cancel(); settingsJob = null
        snapshots = HashMap()
    }

    /**
     * 配置回调（消费协程调用）。状态所有回调都上报；快照只在
     * 「UP(1)/CONFIG_UPDATE(2) 且带地址」时登记——
     * 核心语义：端口首次初始化后的第一份完整 netconf 以 UP 回调，仅认
     * CONFIG_UPDATE 会漏掉首次加入的网络（磁盘有缓存后控制器重推才变 2，
     * 表现为“新网络永远不生效，重开一次就好”）；DOWN(3)/DESTROY(4) 的
     * 回调可能仍带旧地址，误存会把已离开网络的残留配置塞回快照。
     */
    suspend fun onNetworkConfig(nwid: Long, op: VirtualNetworkConfigOperation, conf: NetConf) {
        TunnelStateBus.setNetworkStatus("%016x".format(nwid), conf.status)
        TunnelStateBus.log(
            "cfg %016x op=%s status=%d addrs=%d".format(nwid, op.name, conf.status, conf.assignedAddrs.size)
        )
        if (op != VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_UP &&
            op != VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE
        ) return
        if (conf.assignedAddrs.isEmpty()) return
        configMutex.withLock {
            snapshots[nwid] = conf
            scheduleApplyLocked()
        }
    }

    /** 去抖：连续配置回调合并为一次应用（300ms 窗口） */
    private fun scheduleApplyLocked() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            delay(300)
            applyAggregated()
        }
    }

    /**
     * 配置同步（首启 + configChanges 回调共用）：join/leave/orbit 与
     * 用户侧配置热重配。level-triggered——每次全量重读文件现算差分。
     */
    suspend fun applyConfig() = configMutex.withLock {
        val networks = store.readActiveNetworks()
        val moons = store.readActiveMoons()
        val wanted = networks.filter { it.lastActivated }
            .mapNotNull { it.networkId.toULongOrNull(16)?.toLong() }
            .toHashSet()
        for (nwid in wanted) if (nwid !in joinedNetworks) {
            runtime.join(nwid)
            joinedNetworks.add(nwid)
            TunnelStateBus.log("join %016x".format(nwid))
        }
        for (nwid in joinedNetworks.toList()) if (nwid !in wanted) {
            runtime.leave(nwid)
            joinedNetworks.remove(nwid)
            converter.removeNetwork(nwid)
            snapshots.remove(nwid)
            TunnelStateBus.log("leave %016x".format(nwid))
            scheduleApplyLocked()
        }
        for (moon in moons) {
            val worldId = moon.worldId.toLong()
            if (worldId !in orbitedMoons) {
                runtime.orbit(worldId, moon.seed.toLong())
                orbitedMoons.add(worldId)
            }
        }
        // 用户侧配置（默认路由/DNS）变更 → 用核心当前配置热重套（对齐 iOS appliedEntries）
        for (nwid in joinedNetworks) {
            val entry = networks.firstOrNull {
                it.networkId.toULongOrNull(16)?.toLong() == nwid
            } ?: continue
            val applied = Triple(entry.routeViaZeroTier, entry.dnsMode, entry.customDns)
            if (appliedEntries[nwid] != applied) {
                appliedEntries[nwid] = applied
                runtime.networkConf(nwid)?.let { upsertSnapshotLocked(nwid, it) }
            }
        }
    }

    /** onNetworkConfig 锁内变体用的快照登记（Mutex 非重入，P1 教训） */
    private fun upsertSnapshotLocked(nwid: Long, conf: NetConf) {
        TunnelStateBus.setNetworkStatus("%016x".format(nwid), conf.status)
        if (conf.assignedAddrs.isEmpty()) return
        snapshots[nwid] = conf
        scheduleApplyLocked()
    }

    /** 聚合应用：冲突检测 → 转换器上下文 → 合并地址/路由/MTU/DNS → 单次 establish */
    private suspend fun applyAggregated() = configMutex.withLock {
        val entries = store.readActiveNetworks().filter { it.lastActivated }
        val disableIpv6 = store.disableIpv6()

        class Item(val nwid: Long, val entry: NetworkEntry, val conf: NetConf,
                   val v4: List<String>, val v6: List<String>)

        // 没有可用地址的配置不进数据面（残缺设置同样会黑洞物理路由）
        val items = entries.mapNotNull { entry ->
            val nwid = entry.networkId.toULongOrNull(16)?.toLong() ?: return@mapNotNull null
            val conf = snapshots[nwid] ?: return@mapNotNull null
            val v4 = conf.assignedAddrs.filter { it.contains('.') }
            val v6 = if (disableIpv6) emptyList() else conf.assignedAddrs.filter { it.contains(':') }
            if (v4.isEmpty() && v6.isEmpty()) return@mapNotNull null
            Item(nwid, entry, conf, v4, v6)
        }

        // 冲突检测（后进入者让路）→ 转换器上下文（冲突网络不建，保留 join 仅展示状态）
        val conflicts = NetworkContextMath.detectConflicts(
            items.map { Triple(it.nwid, it.v4, it.v6) }
        )
        for (item in items) {
            converter.updateNetworkInfo(
                item.nwid, enabled = !conflicts.containsKey(item.nwid),
                mac = item.conf.mac, ipv4 = item.v4, ipv6 = item.v6, routes = item.conf.routes,
            )
        }
        // 收敛：清理本次快照之外的网络上下文（读文件与 leave 并发时的幽灵路由）
        converter.retainOnly(items.map { it.nwid }.toHashSet())
        val valid = items.filter { !conflicts.containsKey(it.nwid) }
        TunnelStateBus.log(
            "apply items=${items.size} valid=${valid.size} conflicts=${conflicts.size} " +
                "快照=${snapshots.keys.sorted().joinToString(",") { "%016x".format(it) }}"
        )
        if (valid.isEmpty()) {
            TunnelStateBus.setApplyTrace(
                "items=${items.size} valid=0（无可用网络，未套设置）\n" + converter.debugSummary()
            )
            return@withLock
        }

        // 多网络下默认路由一律不生效；单网络按用户选择（对齐 iOS routeVia）
        val routeVia = valid.size == 1 && valid[0].entry.routeViaZeroTier
        val allV4 = valid.flatMap { it.v4 }
        val allV6 = valid.flatMap { it.v6 }
        // 物理 on-link 子网挖除：ZT managed routes 会覆盖本机物理局域网
        val v4Holes = RoutePlanner.physicalOnLinkPrefixes4(allV4)
        val v6Holes = RoutePlanner.physicalOnLinkPrefixes6(allV6)

        val v4Routes = ArrayList<RouteEntry>()
        val seenV4 = HashSet<String>()
        fun appendV4(routes: List<RouteEntry>) {
            for (r in routes) if (seenV4.add("${r.addr}/${r.prefix}")) v4Routes.add(r)
        }
        val v6Routes = ArrayList<RouteEntry>()
        val seenV6 = HashSet<String>()
        fun appendV6(routes: List<RouteEntry>) {
            for (r in routes) if (seenV6.add("${r.addr}/${r.prefix}")) v6Routes.add(r)
        }
        if (routeVia) {
            appendV4(RoutePlanner.expandV4("0.0.0.0/0", v4Holes))
            appendV6(RoutePlanner.expandV6("::/0", v6Holes))
        }
        for (item in valid) {
            // 分配地址的直连网段（分配地址本身不会自动保证其网段进 TUN）
            for (address in item.v4) {
                val cidr = RoutePlanner.networkCIDR4(address)
                appendV4(listOf(RouteEntry(RoutePlanner.ipOnly(cidr), RoutePlanner.prefixLen(cidr))))
            }
            for (address in item.v6) {
                val cidr = RoutePlanner.networkCIDR6(address)
                appendV6(listOf(RouteEntry(RoutePlanner.ipOnly(cidr), RoutePlanner.prefixLen(cidr))))
            }
            // managed 路由：默认路由仅在 routeVia 时纳入（非 routeVia 不抢物理默认路由）
            for (r in item.conf.routes) {
                if (r.contains('.') && (routeVia || !RoutePlanner.isDefaultRoute(r))) {
                    appendV4(RoutePlanner.expandV4(r, v4Holes))
                }
                if (r.contains(':') && (routeVia || !RoutePlanner.isDefaultRoute(r))) {
                    appendV6(RoutePlanner.expandV6(r, v6Holes))
                }
            }
        }

        val trace = buildString {
            append("items=${items.size} valid=${valid.size}\n")
            for (i in items) {
                append("item %016x v4=${i.v4} v6=${i.v6} conflict=${conflicts[i.nwid] ?: "-"}\n"
                    .format(i.nwid))
            }
            append("includedV4=" + v4Routes.joinToString(",") { "${it.addr}/${it.prefix}" } + "\n")
            append("includedV6=" + v6Routes.joinToString(",") { "${it.addr}/${it.prefix}" } + "\n")
            append(converter.debugSummary())
        }

        // Builder 组装（单网络 DNS 三模式；多网络不设——默认路由已禁用，ZT DNS 无意义）
        val builder = VpnService.Builder()
            .setSession("Planet Port")
            .setMtu(valid.map { it.conf.mtu }.filter { it > 0 }.minOrNull() ?: 2800)
        for (a in allV4) builder.addAddress(RoutePlanner.ipOnly(a), RoutePlanner.prefixLen(a))
        for (a in allV6) builder.addAddress(RoutePlanner.ipOnly(a), RoutePlanner.prefixLen(a))
        for (r in v4Routes) builder.addRoute(r.addr, r.prefix)
        for (r in v6Routes) builder.addRoute(r.addr, r.prefix)
        if (valid.size == 1) {
            val item = valid[0]
            val servers: List<String> = when (item.entry.dnsMode) {
                DnsMode.network -> item.conf.dns
                DnsMode.custom -> item.entry.customDns
                DnsMode.none -> emptyList()
            }
            for (dns in servers) builder.addDnsServer(dns)
        }
        val fd = withContext(Dispatchers.IO) { builder.establish() }
        if (fd == null) {
            TunnelStateBus.log("establish 失败（Builder 拒绝，多为路由/地址冲突）")
            TunnelStateBus.setTunnelState(TunnelState.Failed("TUN 建立失败", recoverable = true))
            return@withLock
        }
        dataplane.swap(fd)
        // 组播订阅：broadcast + ADI=各网络首个 IPv4（ARP 可靠性的关键，SDK 文档要求）
        for (item in valid) {
            val adi = item.v4.firstOrNull()
                ?.let { NetworkContextMath.parseIPv4(RoutePlanner.ipOnly(it)) } ?: continue
            runtime.multicastSubscribe(item.nwid, Ethernet.BROADCAST_MAC, adi.toLong())
        }
        TunnelStateBus.setApplyTrace(trace)
        TunnelStateBus.log("establish 成功")
    }
}
```

**实现要点核对**：
1. `NetConf`/`NetConfEvent`/`FrameEvent` 与 T2 NodeRuntime 定义的字段名一致（`assignedAddrs`/`routes`/`dns`/`mtu`/`mac`/`status`）。
2. `runtime.networkConf(nwid: Long): NetConf?` 已在 T2 落地（node.networkConfig 转换），直接使用。
3. `op.name` 输出枚举名（UP/CONFIG_UPDATE/DOWN/DESTROY），TunnelStateBus.log 可读。
4. `setSession` 为 API 21+；`addDnsServer`/`setDnsServers` 按 T1 壳实际可用方法（Builder 包装类）。

- [ ] **Step 4: 改 ZeroTierVpnService.kt（全链路组装）**

字段区与 startTunnel/stopTunnel 替换为：

```kotlin
    private var scope: CoroutineScope? = null
    private var runtime: NodeRuntime? = null
    private var udpCom: UdpCom? = null
    private var converter: FrameConverter? = null
    private var dataplane: Dataplane? = null
    private var tunnelManager: TunnelManager? = null

    private fun startTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Starting)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val store = AppConfigStore(StorePaths(filesDir))
        s.launch {
            val planetId = store.activePlanetId()
            val planet = store.readPlanets().firstOrNull { it.id == planetId }
                ?: PlanetProfile.OFFICIAL
            var runtimeRef: NodeRuntime? = null
            val udp = UdpCom(this@ZeroTierVpnService) { data, remote ->
                runtimeRef?.processWirePacket(data, remote)
            }
            val r = NodeRuntime(
                dataStoreRoot = File(filesDir, "zt"),
                planetDir = store.paths.planetStateDir(planet.id),
                planetFile = planet.fileName?.let { File(store.paths.planetsDir, it) },
                scope = s,
            )
            runtimeRef = r
            if (!r.start(sender = udp.asPacketSender())) {
                TunnelStateBus.setTunnelState(TunnelState.Failed("核心初始化失败", recoverable = true))
                stopTunnel()
                return@launch
            }
            val conv = FrameConverter(r)
            val dp = Dataplane { pkt -> conv.handlePacketsFromTun(listOf(pkt)) }
            val tm = TunnelManager(s, r, conv, dp, store)
            // 帧消费：核心回调帧 → 转换 → 写 TUN（IP 包无需协议族标注，TUN 按 IP 头自识别）
            s.launch {
                for (ev in r.frameChannel) {
                    val packets = conv.handleFrameFromCore(
                        ev.nwid, ev.srcMac, ev.dstMac, ev.etherType, ev.frame
                    )
                    if (packets.isNotEmpty()) dp.writePackets(packets)
                }
            }
            // 配置消费：netconf 事件 → TunnelManager
            s.launch {
                for (ev in r.configChannel) tm.onNetworkConfig(ev.nwid, ev.op, ev.conf)
            }
            udp.start()
            runtime = r; udpCom = udp; converter = conv
            dataplane = dp; tunnelManager = tm
            tm.start()
            tm.applyConfig() // 首启 join 已配置网络 + orbit moons
            TunnelStateBus.setTunnelState(TunnelState.Running)
            VpnNotification.update(this@ZeroTierVpnService, "运行中")
        }
    }

    private fun stopTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Stopped())
        scope?.cancel()
        scope = null
        tunnelManager?.stop()
        tunnelManager = null
        dataplane?.shutdown()
        dataplane = null
        converter = null
        udpCom?.shutdown()
        udpCom = null
        runtime?.shutdown()
        runtime = null
        TunnelStateBus.setNodeStatus(null)
        TunnelStateBus.clearNetworkStatuses()
        stopForeground(true)
        stopSelf()
    }
```

**注意**：
1. 帧消费传给 `Dataplane` 的是单包 lambda（Android TUN read 一次一包）；`handlePacketsFromTun` 接受 List 是为了与 iOS 语义对齐及批处理扩展。
2. `stopTunnel` 中 `scope.cancel()` 先行——`tm.stop()` 内若还有挂起清理会随 scope 取消；`tm.stop()` 在 cancel 后只做同步字段清理，安全。
3. T2/T3 版本 startTunnel 的旧实现整体替换；`onDestroy`/`onRevoke` 中对 `stopTunnel()` 的既有调用保持不变。
4. `import` 增补：`cn.imgozi.planetport.store.AppConfigStore`、`cn.imgozi.planetport.store.PlanetProfile`、`cn.imgozi.planetport.store.StorePaths`、`java.io.File`（若 T3 已引入则保留）。

- [ ] **Step 5: 编译验证**

Run: `./gradlew assembleDebug assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 真机验证（netconf → establish 链路）**

预置测试网络配置（run-as 直写 P1 配置文件，网络 ID 用占位 `<NWID>`，执行时替换为真实测试网络）：

```bash
adb -s 9311902b install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 9311902b shell "run-as cn.imgozi.a.planetport sh -c 'mkdir -p files/config && cat > files/config/networks.json <<EOF
[{\"networkId\":\"<NWID>\",\"networkName\":\"p2test\",\"lastActivated\":true,\"routeViaZeroTier\":false,\"dnsMode\":\"none\",\"customDns\":[],\"planetId\":\"official\"}]
EOF'"
adb -s 9311902b shell am start-foreground-service \
  -n cn.imgozi.a.planetport/cn.imgozi.planetport.tunnel.ZeroTierVpnService \
  -a cn.imgozi.planetport.action.START
sleep 20
adb -s 9311902b shell ip addr show tun0
adb -s 9311902b shell run-as cn.imgozi.a.planetport ls files/zt/networks.d/
```

Expected: 私有测试网络未授权前无 netconf（无 tun0，属正常——验证页应显示「待授权(6)」）；用公开网络或已授权私有网络时 `tun0` 出现且持有 ZT 管理地址（172.28.x.x 或 fd00 系），`networks.d/<NWID>.conf` 存在。

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat(tunnel): TunnelManager——netconf 聚合与 TUN 建立

配置回调登记快照（只认 UP/CONFIG_UPDATE 且带地址——首份完整 netconf
以 UP 回调，仅认 CONFIG_UPDATE 会漏首次加入的网络）；300ms 去抖后
聚合应用：冲突检测后进入者让路、转换器上下文按网隔离、物理 on-link
子网挖除、单网络 DNS 三模式与 routeVia 默认路由；Builder.establish
同步返回故 mutex 串行即等价 iOS 的异步单飞；establish 成功后组播订阅
（broadcast + ADI=首 IPv4）；AppPrefs 增 disableIpv6；服务壳全链路
组装（帧/配置消费协程、45s 保活、configChanges level-triggered 重读）。"
```

---

### Task 6: P2 验证页与端到端闭环（加入 → ping 通 → 退出）

**Files:**
- Delete: `app/src/main/java/cn/imgozi/planetport/ui/P1SelfCheck.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/ui/P2SelfCheck.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/MainActivity.kt`（挂 P2SelfCheck + VPN 授权 + 通知权限）

**Interfaces:**
- Consumes: TunnelStateBus（tunnelState/nodeStatus/networkStatuses/applyTrace/eventLog）；ZeroTierVpnService.start/stop（T1 壳方法）；AppConfigStore（activePlanetId/writeNetworks）；NetworkEntry/DnsMode；StorePaths
- Produces: P2 验证页（临时脚手架，P4 换正式 UI 时整体替换）

- [ ] **Step 1: 删除 P1SelfCheck.kt**

```bash
git rm app/src/main/java/cn/imgozi/planetport/ui/P1SelfCheck.kt
```

- [ ] **Step 2: 写 P2SelfCheck.kt**

```kotlin
package cn.imgozi.planetport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.store.StorePaths
import cn.imgozi.planetport.tunnel.TunnelState
import cn.imgozi.planetport.tunnel.TunnelStateBus
import cn.imgozi.planetport.tunnel.ZeroTierVpnService
import kotlinx.coroutines.launch

/** ZT_VirtualNetworkStatus 原始码 → 验证页文案（正式文案映射 P4 落地） */
private fun ztStatusText(code: Int): String = when (code) {
    0 -> "请求配置中"; 1 -> "正常"; 2 -> "拒绝访问"; 3 -> "网络不存在"
    4 -> "端口错误"; 5 -> "客户端过旧"; 6 -> "待授权"; else -> "未知($code)"
}

private fun stateText(state: TunnelState): String = when (state) {
    is TunnelState.Idle -> "空闲"
    is TunnelState.Starting -> "启动中"
    is TunnelState.Running -> "运行中"
    is TunnelState.Reconnecting -> "重连中(${state.reason})"
    is TunnelState.Stopped -> "已停止${state.reason?.let { "($it)" } ?: ""}"
    is TunnelState.Failed -> "失败(${state.reason})"
}

/**
 * P2 验证页（临时脚手架，P4 换正式 UI）：启停隧道、临时 join 网络、
 * 状态/事件日志观察。join 按钮写 networks.json——P1 configChanges 自动
 * 触发 applyConfig，形成「UI 写 store → 隧道 join」的正式数据通路。
 */
@Composable
internal fun P2SelfCheckScreen(activity: MainActivity) {
    val state by TunnelStateBus.tunnelState.collectAsState()
    val node by TunnelStateBus.nodeStatus.collectAsState()
    val statuses by TunnelStateBus.networkStatuses.collectAsState()
    val trace by TunnelStateBus.applyTrace.collectAsState()
    val log by TunnelStateBus.eventLog.collectAsState()
    var nwid by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("隧道: ${stateText(state)}", style = MaterialTheme.typography.titleMedium)
        Text("节点: ${node?.nodeId ?: "-"}  核心 ${node?.coreVersion ?: "-"}  在线=${node?.online ?: false}")
        for ((n, s) in statuses) Text("网络 $n: ${ztStatusText(s)}")

        OutlinedTextField(
            value = nwid, onValueChange = { nwid = it.trim() },
            label = { Text("网络 ID（16 位 hex）") }, singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                activity.requestVpnPermission {
                    ZeroTierVpnService.start(activity)
                }
            }) { Text("启动") }
            Button(
                enabled = nwid.length == 16 &&
                    nwid.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' },
                onClick = {
                    val s = AppConfigStore(StorePaths(activity.filesDir))
                    scope.launch {
                        val planetId = s.activePlanetId()
                        val existing = s.readNetworks(planetId).filter { it.networkId != nwid.lowercase() }
                        val entry = NetworkEntry(
                            networkId = nwid.lowercase(),
                            networkName = "p2-${nwid.takeLast(4)}",
                            lastActivated = true,
                            routeViaZeroTier = false,
                            dnsMode = DnsMode.none,
                            customDns = emptyList(),
                            planetId = planetId,
                        )
                        // 其余网络置静默（P2 单网验证语义）
                        s.writeNetworks(
                            existing.map { it.copy(lastActivated = false) } + entry, planetId
                        )
                    }
                },
            ) { Text("加入") }
            Button(onClick = { ZeroTierVpnService.stop(activity) }) { Text("停止") }
        }

        Text("applyTrace:", fontSize = 12.sp)
        Text(trace.ifEmpty { "（空）" }, fontSize = 12.sp)
        Text("事件日志:", fontSize = 12.sp)
        Text(log.ifEmpty { "（空）" }, fontSize = 12.sp)
    }
}
```

- [ ] **Step 3: 改 MainActivity.kt（挂 P2SelfCheck + VPN 授权 + 通知权限）**

```kotlin
package cn.imgozi.planetport.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var vpnGrantedCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                vpnGrantedCallback?.invoke()
                vpnGrantedCallback = null
            }
        }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {}

        // API 33+ 通知运行时权限（FGS 通知可见性；拒绝不阻塞功能）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface {
                    P2SelfCheckScreen(this)
                }
            }
        }
    }

    /** VPN 系统授权（prepare 返回 null 表示已授权过，直接执行） */
    fun requestVpnPermission(onGranted: () -> Unit) {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            onGranted()
        } else {
            vpnGrantedCallback = onGranted
            vpnPermissionLauncher.launch(prepare)
        }
    }
}
```

**注意**：
1. P1 版 MainActivity 的 JNI 加载 try/catch（System.loadLibrary + UnsatisfiedLinkError 兜底）保留在 onCreate 最前；P1SelfCheck 挂载行整体替换为 P2SelfCheckScreen。
2. Material3 依赖：执行者先查 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`——P1 已用 Material3（P1SelfCheck 用过 Button/Text）则直接用；缺则补 `implementation(libs.androidx.material3)` 并在 catalog 加 `material3 = "1.3.2"` 别名。主题包裹用裸 `MaterialTheme { }` 即可（P0 无正式 theme 工程，P4 再建 PlanetPortTheme）。
3. `nodeStatus`/`networkStatuses`/`applyTrace`/`eventLog` 的 StateFlow 类型以 T1 TunnelStateBus 实际定义为准（`nodeStatus: StateFlow<NodeStatus?>`、`networkStatuses: StateFlow<Map<String, Int>>` 等）；字段名 `nodeId/coreVersion/online` 与 T2 EventListenerImpl 上报一致。

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 端到端真机验证（P2 完成标准，需测试网络）**

前置：一个可用的 ZeroTier 网络（公开网络或用户可授权节点的私有网络）+ 网内一台可 ping 的对端（另一台 ZT 设备）。**控制方执行前向用户索取：网络 ID、对端 ZT IP**。

```bash
adb -s 9311902b install -r app/build/outputs/apk/debug/app-debug.apk
# 1. 清理 P1/T5 遗留测试配置
adb -s 9311902b shell run-as cn.imgozi.a.planetport rm -f files/config/networks.json
# 2. 启动 app → 点「启动」→ 系统授权弹窗允许 → 状态「运行中」、nodeId 生成
adb -s 9311902b shell am start -n cn.imgozi.a.planetport/cn.imgozi.planetport.ui.MainActivity
# 3. 验证页输入网络 ID → 点「加入」→ 私有网络先显示「待授权(6)」，
#    controller 授权后变为「正常(1)」；公开网络直接「正常(1)」
# 4. TUN 地址确认
adb -s 9311902b shell ip addr show tun0
# 5. 连通性（对端 ZT IP）
adb -s 9311902b shell ping -c 4 <对端ZT-IP>
# 6. 退出：验证页点「停止」→ 通知消失
adb -s 9311902b shell ip addr show tun0 || echo "tun0 已移除 ✓"
```

Expected: tun0 持有 ZT 管理地址；`ping` 4 包基本通（首包可能因 ARP 学习超时一次，`ping -c 4` 有回包即达标——首访不丢包是 P3 优化项）；停止后 tun0 移除、再 ping 不通。全程截图存档（启动页/tun0/ping 结果），控制方用 mcp__zai-mcp-server__analyze_image 独立读图复核。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(ui): P2 验证页——启停/join/状态观察与端到端闭环

临时验证脚手架（P4 换正式 UI）：VPN 系统授权流程（prepare 空则直启）、
API 33+ 通知权限请求、join 按钮经 store 写 networks.json 触发
configChanges → applyConfig 正式数据通路；状态页展示隧道状态/nodeId/
网络状态原始码/applyTrace/事件日志。P1SelfCheck 数据层自检页退役删除。
P2 完成标准（加入→ping 通→退出）真机验证达成。"
```

---

## 附：执行注意汇总（控制方派发时随简报附给实现者）

1. **SDK 平台类型防御**：vendor Java 接口的 Kotlin 实现允许 `?.` 安全调用；编译器对「永远非空」处报错时删运算符不改逻辑。
2. **VirtualNetworkStatus.ordinal 即原始码**：vendor 冻结版本枚举顺序 0..6；T2 落地时用任一状态事件抽查一次自证（logcat 打印即可，不留代码）。
3. **锁纪律**：TunnelManager 的 `applyConfig`/`onNetworkConfig` 均为 public suspend（各自 withLock），互调必须走 `upsertSnapshotLocked`；FrameConverter 的 synchronized 块内只调 JNI 与内存操作，禁挂起点。
4. **执行顺序**：T1→T6 串行（每任务依赖前序接口）；T4 内七文件一次性写完再编译。
5. **真机**：Mi Note 3 serial `9311902b`；截图验证用 mcp__zai-mcp-server__analyze_image 读图（P0/P1 已验证流程）。
6. **跨任务字段名锚点**：`NetConf.assignedAddrs/routes/dns/mtu/mac/status`（T2 定义，T4/T5 消费）；`TunnelStateBus.tunnelState/nodeStatus/networkStatuses/applyTrace/eventLog`（T1 定义，T5/T6 消费）；`NodeRuntime.join/leave/orbit/networkConf/multicastSubscribe/processVirtualNetworkFrame/processWirePacket`（T2 定义，T3/T5 消费）。实现者发现签名不符时以已落地代码为准适配并在报告注明，禁止臆改已审接口。
7. **activatePlanet 重启联动**：P1 的 activatePlanet 只写指针不发 configChanges——本计划范围内 join 场景不触发；切 planet 重启服务的完整联动留 P5（切换时隧道在跑则先 stop 再由用户手动 start）。T5 Service 组装不处理该场景。

---

## 附：实施差异记录（2026-10-24 终审闭合后回写，实际落地以代码为准）

计划原文与实际代码的偏差，全部经任务审查/终审裁决留档（详见 .superpowers/sdd/progress.md P2 各条）：

### 计划缺陷修正（实现期报出）

1. `BROADCAST_MAC = -0x1000000000000L` 语义错误——`0xffffffffffffL`（2^48-1）为合法字面量并无溢出；负补码低 48 位全 0，经核心 MAC 掩码后广播帧全丢。已回正字面量（87451a6）。
2. NDP 构造偏移错位——target 应 copyInto(p,8)、源 MAC 选项头在 24/25（RFC 4861 布局：ICMPv6 头 4 + flags 4 + target 16 + 选项 8）。已按 RFC 重排（87451a6）。
3. `VpnService.Builder()` 写法错误——Builder 为非静态内部类（android-34 源码无 static 修饰），必须 `vpnService.Builder()` 实例语法。TunnelManager 构造因此为六参（计划 Interfaces 段为五参，增加 `vpnService: VpnService`）。
4. `AppConfigStore.configChanges` 流本体提升至 companion——UI 与隧道各持 store 实例，实例级 SharedFlow 事件不互通，join 通路断裂；实例属性改 getter 转发，签名不变（93f6ebb）。

### 审查驱动修复波（计划未含的增量）

1. **四项停机修复**（T2/T3 审查合并，随 T5 落地 38cc9dc）：udp.start() SocketException 兜底→Failed；启动期 STOP 竞态 isActive 守卫；UdpCom.shutdown() 收包线程 join(500)；NodeRuntime.shutdown() supervisor cancel+runBlocking join 后再 node.close()。
2. **数据面两 Important**（60f551f）：Dataplane 写流每代持有（swap 建立持有、换代/停机关闭——按 task 新建 FileOutputStream 会被 GC finalizer 关共享 fd 杀死在用 TUN）；RoutePlanner 物理接口 `networkPrefixLength <= 0` 跳过（点对点接口 -1 的 IPPrefix contains 恒真会挖空全部 ZT 路由）。
3. **组装两 Important**（096619c）：startTunnel 启动体 try/finally + started 标志（未完成启动用局部引用幂等兜底清理）；onNetworkConfig 收到无地址 UP/CONFIG_UPDATE 清快照重聚合（控制器吊销授权的剔除路径）。
4. **NodeRuntime @Synchronized**（c0dea6e，复审发现）：start/shutdown 互斥防 stopTunnel 与启动 finally 并发双入导致 native double free。
5. **establish 幂等闸门**（40da709，T6 真机发现）：核心每 60s 重推 CONFIG_UPDATE 会重复重建 TUN（接口序号 26→39）；签名（地址/路由/MTU/DNS 内容拼接，RouteEntry 须按 addr/prefix 拼接——普通类 toString 为 identityHash）一致即跳过。
6. **组播订阅移出闸门 + onRevoke + 签名时序**（176f88b，T6 审查发现）：leave 连带丢弃核心订阅记录，rejoin 后签名命中永不重订；订阅改为每次 apply 执行（核心按组去重幂等）。onRevoke 补完整 stopTunnel。appliedSignature 在 swap 成功后赋值。
7. **终审停机收口**（638bb38）：Dataplane 读线程保存引用并在 shutdown join(500)；帧/配置消费协程 Job 存字段，stopTunnel 与 finally 两路径在 runtime.shutdown 前 runBlocking joinAll(500)；writePackets execute 包 RejectedExecutionException 护随。

### 真机验证口径变更

官方公开网络 Earth 已于 2023 被官方下线（join 返回 status=3），计划 Step 5 的公开网络预案物理不可达——改用 Mac docker 自建 controller 公开网络 66defc674a000001（172.28.100.0/24）等价验证。判定线达成：运行中 + nodeId + join 正常(1) + tun0 持 172.28.100.174/24 + applyTrace 含路由表 + 停止清理全过；ping 未通（split-tunnel 平台语义下 adb 流量不进 VPN routing domain + 对端方向组播未达，分析见账本，P3 数据面起点）。

### 移交后续阶段的输入

- **P3**：软件 NDP ICMPv6 校验和恒 0（iOS 同构共享问题，与真实 Linux 节点互通会被对端内核丢弃；IPv4 ARP 主路径安全）；被踢单网不拆 TUN 路由黑洞；run-as 应用 uid ping + statArpReq/Reply 计数判层为 P3 第一动作；split-tunnel 默认路由策略决策；若干停机窄窗顺手项（socket @Volatile、双 START 单实例守卫、swap 前 join 旧代读线程）。
- **P4**：Builder 组装 try-catch（customDns 用户输入）；appliedEntries Triple 纳入 disableIpv6；leave 显式拆 TUN；store 进程单例收敛；startForegroundService 接线；Earth 下线致 P4 前需向用户索取长期私有网络做回归基线。

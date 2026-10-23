# Planet Port Android 设计文档

> 日期：2026-10-23
> 功能基准：iOS 端 planet-port-i（功能完全一致）
> 性质：全新工程，从零搭建

## 1. 项目定位

Planet Port Android 是一个 ZeroTier 网络管理客户端：多份根服务器（Planet）
随身切换、多网络同时连接、Moon 节点、按需连接、完整主题定制。不发明网络，
只把 ZeroTier 的连接能力包装成好用、可控、可定制的 Android App。

功能与 iOS 端 planet-port-i 完全一致。

### 协议红线（硬约束）

本工程的发布协议必须可自由指定（含闭源），因此：

1. **GPL 血统代码零引入**：不引入 kaaass/ZerotierFix（GPLv2 血统）的任何
   代码 —— 不拷贝、不逐行翻译。服务层/数据面行为规格来源为**作者自有资产**：
   iOS 端实现（ZTBridge/FrameConverter 等，见 planet-port-i）与
   `multi-network-dataplane.md` 算法文档。
2. **自由资产仅限作者自建文件**：作者自有 Compose UI 文件、iOS 对齐期的
   自有数据层文件可复用；任何来源不明的代码一律按规格重写。
3. **文案/字符串取自 iOS 工程**（作者自有），不抄第三方翻译。
4. **MPL-2.0 组件以 vendor 方式入仓**（源码拷贝进 `externals/`，非依赖仓库）：
   ZeroTier C++ 核心、JNI 绑定（含 JNIEnvGuard 补丁）、Java SDK。不修改任何
   MPL 文件，随源码携带 `LICENSE-MPL.txt` 与 `LICENSE.txt`。协议义务：这些
   文件保持 MPL-2.0；分发 APK 时 MPL 文件源码可获得 —— 仓库开源期间由本仓库
   直接履行，若日后闭源，零修改场景下指向官方 1.16.2 tag 即满足。

## 2. 总体决策

| 决策点 | 结论 |
|---|---|
| applicationId | `cn.imgozi.a.planetport`（`a` = android 平台段，与 iOS 区分） |
| 代码包 / namespace | `cn.imgozi.planetport`（与 applicationId 解耦，业内惯例） |
| ZeroTier 核心 | 官方 1.16.2 (fe29cd8) **vendor 入仓**（对齐 iOS 端 Core/ 做法，锁定版本、免依赖仓库） |
| 进程模型 | 单进程 VpnService + Kotlin 服务层 + StateFlow 通信 |
| UI | 复用作者自有 Compose 资产（iOS 1:1 复刻成果），视觉不变 |
| 数据迁移 | 无历史包袱，直接以 JSON 文件为唯一事实源 |
| 测试 | 仅数据面 NAT/校验和算法附真实包级单元测试（算法正确性保险） |

## 3. 工程结构

```
planet-port-android/
├─ settings.gradle.kts          # dependencyResolutionManagement + version catalog
├─ gradle/libs.versions.toml     # AGP 8.13.2 / Gradle 8.14 / Kotlin 2.2.21 / JDK 21
│                               # compileSdk 36 / minSdk 23 / targetSdk 33 / NDK 25.2.9519653
├─ app/                         # applicationId: cn.imgozi.a.planetport
│  └─ src/main/java/cn/imgozi/planetport/
│     ├─ ui/                    # Compose UI + 按页 ViewModel
│     ├─ tunnel/                # 服务层（Kotlin 实现）
│     ├─ store/                 # 数据层
│     └─ util/
├─ core/                        # JNI 绑定模块（MPL-2.0）
│  ├─ build.gradle              # namespace com.zerotier.sdk，srcDirs 重定向至 externals/core/java/src
│  └─ jni/                      # 官方 JNI + JNIEnvGuard 补丁（随 MPL-2.0 保留）
└─ externals/
   ├─ core/                     # ZeroTier 1.16.2 vendor 源码（MPL-2.0）
   └─ CMakeLists.txt            # NDK 构建脚本（含 16KB 页对齐）
```

- Kotlin DSL + version catalog；core 模块构建脚本为 Groovy（Gradle 支持
  KTS/Groovy 混用）。
- 工具链：AGP 8.13.2 / Gradle 8.14 / Kotlin 2.2.21 / JDK 21 /
  compileSdk 36 / minSdk 23 / targetSdk 33 / NDK 25.2.9519653。
  targetSdk 不升 34+（前台服务类型强制声明等行为变化不引入）。
- ProGuard 首版关闭。

vendor 源码集合（按构建脚本的实际编译依赖核定，约 2.5MB）：
node/（全部 .cpp/.hpp）、include/、version.h（仓库根）、osdep/、
java/src（Java SDK）、ext/prometheus-cpp-lite-1.0/、
ext/x64-salsa2012-asm/、ext/arm32-neon-salsa2012-asm/、
LICENSE-MPL.txt、LICENSE.txt。不带 controller/service 桌面端/
artwork/windows/nonfree 等无关目录。落地时以编译通过为准核对
（GLOB 覆盖 node/*.cpp，CONFIGURE_DEPENDS）。

## 4. 服务层架构（tunnel/）

VpnService 壳只做生命周期组装（≤200 行），具体逻辑在五个职责单一的组件：

| 组件 | 职责 |
|---|---|
| `ZeroTierVpnService` | VpnService 壳：onStartCommand/onRevoke/onDestroy，组装下列组件 |
| `NodeRuntime` | ZT Node 创建/销毁、dataStore 读写 hook、planet 文件重定向、moon orbit/deorbit、后台任务 deadline 驱动（supervisorLoop） |
| `TunnelManager` | 聚合 enabled 网络单次 Builder.establish；300ms 去抖 + 启动聚齐等待（≤8s）+ 停机丢弃在途重建 |
| `Dataplane` | TUN fd 读写线程、ARP/NDP 按网隔离表、IPv4 源 NAT、LPM 选网、PendingPacketQueue、组播订阅扫描（Android < 29） |
| `UdpCom` | UDP 9994 收发；`isInterrupted()` + `isClosed()` 双条件退出、异常不静默 |
| `VpnNotification` | 前台通知、渠道幂等创建、状态文案 |

### 线程模型

- **编排层协程化**：supervisorLoop、去抖调度、生命周期状态机跑在 SupervisorJob
  scope，服务销毁整体取消（线程自 join 死锁、重启竞态在结构上不可能）。
- **数据面专职线程**：TUN 读循环、UDP recv 循环是阻塞系统调用，用专职线程 +
  interrupt 模型；封装在组件内（`start()` / `shutdownAndWait()`，自 join 防护
  下沉到基类），生命周期由 NodeRuntime 统一管理。
- **回调边界**：JNI 回调（帧/事件/config）在 ZT 原生线程进入 → 投递无锁
  Channel → 协程侧消费。JNIEnvGuard（MPL）保证 attach。

### Planet 隔离

NodeRuntime 的 dataStorePut/Get hook 按激活 planet 分目录（`files/zt/<planetId>/`），
planet 切换 = 换目录 + 重建 Node。对齐 iOS `ZT_STATE_OBJECT_PLANET` 语义。

## 5. 数据层与状态通信（store/）

```
store/
├─ AppConfigStore.kt ── planets.json / networks.json / moons.json（对齐 iOS 结构）
│                      Mutex 全局锁、tmp+rename 原子写、损坏隔离 .corrupt 不自动重建
├─ PlanetImporter.kt ── planet 解析/校验（含非官方 Planet ID）、本地+URL 导入、改名、图标
├─ MoonImporter.kt ─── .moon 文件解析
└─ Models.kt ───────── PlanetProfile / NetworkEntry / MoonEntry / DnsConfig
```

设置项为单文件 `prefs.json`（与三份配置 JSON 同一套读写设施，一个入口，
写后按需触发隧道重配）。

### 状态通信

```
UI ──命令──> TunnelController（façade：start/stop/join/leave/setDnsMode/orbitMoon/...，
             挂起函数，请求-应答类查询也走这里）
Service ──状态──> TunnelStateBus（单例，4 个热流）
             ├─ StateFlow<TunnelState>        连接中/在线/停止/错误（含 recoverable）
             ├─ StateFlow<NodeStatusInfo>
             ├─ StateFlow<List<PeerInfo>>
             └─ StateFlow<List<NetworkState>>
```

UI 不持有服务内部对象（无 Binder、无事件总线）。ApplyTrace 埋点走
`SharedFlow<TraceEvent>`（UI 订阅落环形缓冲）。

`activatePlanet()` = storeMutex 内更新指针 → 隧道在跑则优雅停机 → 切缓存目录 →
重启；UI 全程只看 TunnelState 流。

## 6. UI 层（ui/）

- 复用作者自有 Compose 资产：HomeScreen、PlanetCardView、NetworkCardView 等
  全套页面（iOS 1:1 复刻成果，视觉与交互零变化）；MainActivity/Theme/
  ThemeManager 原样复用。
- ViewModel 按页拆分：HomeViewModel、NetworkDetailVM、MoonViewModel、
  PlanetSettingsVM、PeersViewModel、PrefsViewModel，共享 StoreRepo +
  TunnelController。
- 数据供给统一：状态从 `TunnelStateBus.collect` 读取；命令走对应 VM 方法。
- 导航：单 Activity + 手写页面切换（6 个页面、层级浅，不引入 Navigation 库）。
- zxing-android-embedded 扫码、PhotoPicker 背景图按既有用法接入。

## 7. 错误处理

- 服务层异常收敛 `TunnelState.Error(reason, recoverable)`，UI 单一错误展示路径。
- 数据面异常不静默：可恢复自愈（UDP socket 重建），不可恢复触发优雅停机 + 上报。
- store 原子写、损坏隔离不重建。
- TunnelController 抛类型化异常，VM 层转用户可读文案，禁止裸 `e.message` 直出。

## 8. 构建与验证

- `assembleDebug` / `assembleRelease`（release 暂用 debug 签名装机）。
- CI：GitHub Actions，JDK 21 + NDK 25.2（vendor 入仓，无依赖仓库步骤）；
  首版只做构建验证。
- 验证 = 编译 + 真机冒烟，各阶段完成标准：

| 阶段 | 内容 | 真机完成标准 |
|---|---|---|
| P0 | 工程骨架 + vendor 源码入库 | APK 启动、.so 加载正常 |
| P1 | 数据层 | 导入 planet/moon、切换、持久化正确 |
| P2 | 隧道核心 | **单网络连通**（加入→ping 通→退出） |
| P3 | 多网络数据面 | 双网络不串扰、切换不风暴、首访不丢包 |
| P4 | UI 全量 | 与 iOS 端视觉对照一致 |
| P5 | 收尾 | 锁屏/切网重连、开机自启、DNS 三模式热重配 |

关键路径 P2→P3；P4 自 P1 起可并行。隧道排障锚点：日志关键字表见
`multi-network-dataplane.md` 第六节。

## 9. 不做清单

- EventBus 事件总线、AIDL 跨进程、Binder
- GreenDAO / SQLite ORM
- Navigation-Compose
- targetSdk 升级、ProGuard 混淆
- 常规业务测试用例（仅 NAT 算法包级测试除外）

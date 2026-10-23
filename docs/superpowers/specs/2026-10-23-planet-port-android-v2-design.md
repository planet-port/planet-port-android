# Planet Port Android v2 重写设计

> 日期：2026-10-23
> 源项目：/Users/zivyuan/GoziLib/Zerotier/aZerotierFix （v1，applicationId `cn.imgozi.PlanetPortA`）
> 功能基准：/Users/zivyuan/GoziLib/Zerotier/planet-port-i （iOS 端，功能须完全一致）

## 1. 背景与目标

v1（aZerotierFix → Planet Port）fork 自 kaaass/ZerotierFix（逆向官方客户端，
GPLv2 血统），服务层是 1358 行的 Java 上帝类 + EventBus 20 余个事件类 + GreenDAO
遗留依赖。用户自 2026-10-19（提交 `515cb15`）起的 40 个提交完成了 iOS 1:1 UI 复刻、
多网络数据面、隧道重建去抖等核心资产，但架构债在旧血统上无法根除。

v2 目标：**功能与 planet-port-i 完全一致的全新 Android 工程**，服务层/数据层按
行为规格用 Kotlin 全新实现，UI 移植用户自有 Compose 代码，ZeroTier 核心
（官方 MPL-2.0）与 JNI 绑定层复用。

### 硬性约束（协议红线）

1. **GPL 血统代码一行不进 v2**：v1 中凡 515cb15 之前已存在、后在其上修改的文件
   （`ZeroTierOneService.java`、`TunTapAdapter.java`、`UdpCom.java`、
   `ARPTable.java`、老 `strings.xml` 等）不搬代码、不逐行翻译，只按行为规格重写。
   规格来源 = 用户自有的 iOS 实现（ZTBridge/FrameConverter 等）与
   `multi-network-dataplane.md` 算法文档。
2. **可移植的只有用户自建文件**：`ui/*.kt`（iOS 复刻提交新建）、
   `store/AppConfigStore.java` 等 iOS 对齐期新建文件。实施时逐文件用
   `git log --diff-filter=A` 核对创建者，拿不准的一律按规格重写。
3. **文案/字符串优先取自 iOS 工程**（用户自有），不从 v1 老资源文件抄。
4. MPL-2.0 组件（ZeroTier C++ 核心、JNI 绑定含 JNIEnvGuard、Java SDK）不修改
   任何文件；core 模块构建配置（build.gradle、树外 CMakeLists）为自有文件。
   源码义务以"指向官方仓库 @ 1.16.2 (fe29cd8)"履行。

满足上述约束后，v2 整体（除 MPL 文件本身）归用户所有，发布协议可任意指定（含闭源）。

## 2. 总体决策

| 决策点 | 结论 |
|---|---|
| applicationId | `cn.imgozi.a.planetport`（`a` = android 平台段，与 iOS 区分） |
| 代码包 / namespace | `cn.imgozi.planetport`（与 applicationId 解耦，业内惯例） |
| ZeroTier 核心 | 官方 1.16.2 submodule（MPL-2.0），core 模块零改动搬迁 |
| 进程模型 | 单进程 VpnService + Kotlin 服务层 + StateFlow 通信（方案 A） |
| UI | 移植 v1 用户自有 Compose 文件，视觉零变化，仅改数据供给 |
| 数据迁移 | 不做（新 applicationId 读不到老数据，用户重导 planet/moon 文件） |
| 测试 | 不新增；v1 的 `IPv4SourceNatTest` 随 NAT 算法移植（已有用例，算法保险） |

## 3. 工程结构

```
planet-port-android/
├─ settings.gradle.kts          # dependencyResolutionManagement + version catalog
├─ gradle/libs.versions.toml     # AGP 8.13.2 / Gradle 8.14 / Kotlin 2.2.21 / JDK 21
│                               # compileSdk 36 / minSdk 23 / targetSdk 33 / NDK 25.2.9519653
├─ app/                         # applicationId: cn.imgozi.a.planetport
│  └─ src/main/java/cn/imgozi/planetport/
│     ├─ ui/                    # 移植 v1 Compose + 按页 ViewModel
│     ├─ tunnel/                # 服务层（Kotlin 全新实现）
│     ├─ store/                 # 数据层（移植改写）
│     └─ util/
├─ core/                        # JNI 绑定模块（v1 原样搬迁，MPL-2.0）
│  ├─ build.gradle              # namespace com.zerotier.sdk，srcDirs 重定向至子模块 java/src
│  └─ jni/                      # 官方 JNI + kaaass JNIEnvGuard 补丁（随 MPL-2.0 保留）
└─ externals/
   ├─ core → ZeroTierOne @ 1.16.2 (fe29cd8)，git submodule
   └─ CMakeLists.txt            # 树外构建 + 16KB 页对齐（v1 搬迁）
```

- Kotlin DSL + version catalog，抛弃 v1 旧式根 build.gradle；core 模块的 Groovy
  脚本保持原样搬迁（Gradle 支持 KTS/Groovy 混用，避免动已验证的构建配置）。
- 工具链版本沿用 v1 已验证组合；targetSdk 不升级（34+ 引入前台服务类型
  强制声明等行为变化，首版不引入新变量）。
- ProGuard 首版关闭。

## 4. 服务层架构（tunnel/）

v1 上帝类拆为五个组件，VpnService 壳只做生命周期组装（≤200 行）：

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
  scope，服务销毁整体取消。v1 的自 join 死锁、线程重启竞态在结构上消失。
- **数据面专职线程**：TUN 读循环、UDP recv 循环是阻塞系统调用，保持 v1 验证过的
  专职线程 + interrupt 模型；封装在组件内（`start()` / `shutdownAndWait()`，
  自 join 防护下沉到基类），生命周期由 NodeRuntime 统一管理。
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

与 v1 差异：无 GreenDAO、无迁移逻辑；SharedPreferences 30+ key 收敛为单文件
`prefs.json`（与三份配置 JSON 同一套读写设施，一个入口，写后按需触发隧道重配）。

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

v1 的 20 个事件类 → 4 个 StateFlow + 挂起函数；ApplyTrace 埋点走
`SharedFlow<TraceEvent>`（UI 订阅落环形缓冲）。UI 不持有服务内部对象，
ZeroTierBinder 取消。

`activatePlanet()` = storeMutex 内更新指针 → 隧道在跑则优雅停机 → 切缓存目录 →
重启；UI 全程只看 TunnelState 流。

## 6. UI 层（ui/）

- 视觉与交互零变化：`HomeScreen` 等 ~15 个 Compose 文件原样移植；
  MainActivity/Theme/ThemeManager 原样移植。
- `AppViewModel.kt`（830 行）**不移植**，拆为按页 VM：HomeViewModel、
  NetworkDetailVM、MoonViewModel、PlanetSettingsVM、PeersViewModel、PrefsViewModel，
  共享 StoreRepo + TunnelController。
- 改动模式机械统一：`vm.xxx` 调用点改为对应 VM；EventBus 订阅改
  `TunnelStateBus.collect`；`ZeroTierOneService.isRunning()` 改读状态流。
- 导航沿用单 Activity + 手写页面切换（6 个页面、层级浅，不引入 Navigation 库）。
- zxing-android-embedded 扫码、PhotoPicker 背景图用法原样移植。

## 7. 错误处理

- 服务层异常收敛 `TunnelState.Error(reason, recoverable)`，UI 单一错误展示路径。
- 数据面异常不静默：可恢复自愈（UDP socket 重建），不可恢复触发优雅停机 + 上报。
- store 原子写、损坏隔离不重建（v1"迁移复活已删数据"教训）。
- TunnelController 抛类型化异常，VM 层转用户可读文案，禁止裸 `e.message` 直出。

## 8. 构建与验证

- `assembleDebug` / `assembleRelease`（release 暂用 debug 签名装机，对齐 v1）。
- CI：GitHub Actions，JDK 21 + NDK 25.2 + submodule；首版只做构建验证。
- 验证 = 编译 + 真机冒烟，各阶段完成标准：

| 阶段 | 内容 | 真机完成标准 |
|---|---|---|
| P0 | 工程骨架 + core 搬迁 | APK 启动、.so 加载正常 |
| P1 | 数据层 | 导入 planet/moon、切换、持久化正确 |
| P2 | 隧道核心 | **单网络连通**（加入→ping 通→退出） |
| P3 | 多网络数据面 | 双网络不串扰、切换不风暴、首访不丢包 |
| P4 | UI 全量 | 与 v1 并排视觉对照一致 |
| P5 | 收尾 | 锁屏/切网重连、开机自启、DNS 三模式热重配 |

关键路径 P2→P3；P4 自 P1 起可并行。P2 排障手段：同 planet 同网络与 v1 对照，
按 `multi-network-dataplane.md` 日志锚点逐段对拍。

## 9. 不做清单

- 跨 app 数据迁移（新 id 读不到老数据）
- GreenDAO 及一切迁移逻辑
- EventBus、ZeroTierBinder、AIDL 跨进程
- Navigation-Compose
- targetSdk 升级、ProGuard 混淆、新增测试用例

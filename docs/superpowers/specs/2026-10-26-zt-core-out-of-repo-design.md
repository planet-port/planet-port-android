# ZeroTier 核心源码移出仓库 + README 同步 设计文档

> 日期：2026-10-26
> 基准：iOS 端 planet-port-ios dev 分支 commit ecf1cb3（Core/ 移出仓库，bootstrap.sh 按官方 tag 拉取）

## 1. 背景与目标

Android 仓库目前将 ZeroTier 1.16.2 核心源码子集（`externals/core`，208 文件，3.6MB）直接入库。
iOS 端已完成「核心源码移出仓库、构建前按官方 tag 拉取」的改造，本设计将其同步到 Android 端，
实现自研代码与第三方代码在仓库层面分离；同时把 iOS 的双语 README 同步过来并按 Android 端特性改写。

前置事实（已核实）：

- `externals/core` 与 iOS 已拉取的 `Core/` 共有文件**逐字节一致**，即 Android 侧为零本地补丁的
  上游 1.16.2 纯净子集，迁移无补丁负担。
- `externals/CMakeLists.txt` 与 `core/jni/` 是自维护构建胶水/JNI 层，**继续入库**。
- 消费方两处：`core/build.gradle` 的 `externalNativeBuild.cmake.path = ../externals/CMakeLists.txt`，
  以及 `sourceSets.main.java.srcDirs` 含 `../externals/core/java/src`。

## 2. 仓库层面

- `git rm -r --cached externals/core`（仅移出索引，工作区文件保留，首次无需重新下载）。
- `.gitignore` 追加 `/externals/core/`。
- `externals/CMakeLists.txt` 保留入库；头部注释中「externals/core 子模块」的过时措辞修正为
  「bootstrap 拉取目录」。

## 3. scripts/bootstrap.sh

骨架照搬 iOS 版（固定 tag + SHA256、tarball 缓存与校验、损坏重下、镜像 URL 覆盖、
手动放置 tarball 离线补救、`.fetched-tag` stamp 幂等跳过、升级只改两个版本变量）。

与 iOS 的差异：

- 缓存目录与 iOS 共用 `~/Library/Caches/PlanetPort`（同一 tarball 同一 SHA256，两仓库缓存互通）。
- 无 xcodegen 步骤（Android 无需生成工程）。
- 拉取子集不同（见下）。

### 拉取子集（精简到实际所需）

依据：`externals/CMakeLists.txt` 的编译输入（GLOB `node/*.cpp` + `osdep/OSUtils.cpp`）与
头文件依赖链（`node/Bond.hpp`、`PacketMultiplexer.hpp` → osdep 的 Binder/BlockingQueue/Phy；
`Phy.hpp` → `ExtOsdep.hpp`；`node/*.cpp` 以 `../version.h` 相对包含）。

| 上游路径 | 说明 |
|---|---|
| `node/` 整目录 | CMake GLOB 编译对象及其头文件 |
| `include/ZeroTierOne.h`、`include/ZeroTierDebug.h` | 公共 API 头 |
| `osdep/OSUtils.cpp`、`osdep/OSUtils.hpp` | 唯一参与编译的 osdep 源 |
| `osdep/Binder.hpp`、`BlockingQueue.hpp`、`Phy.hpp`、`ExtOsdep.hpp` | node 头文件引用 |
| `version.h`（上游仓库根） | node 源相对包含 |
| `ext/nlohmann/json.hpp` | OSUtils.hpp 硬依赖 |
| `ext/prometheus-cpp-lite-1.0/core/include/prometheus/` 整目录 | Metrics.cpp 依赖（ZT_NO_PEER_METRICS 下仍需头） |
| `ext/prometheus-cpp-lite-1.0/simpleapi/include/prometheus/` 整目录 | 同上 |
| `java/src` 整目录 | Java SDK，core 模块 srcDirs 编译 |
| `ext/arm32-neon-salsa2012-asm/`、`ext/x64-salsa2012-asm/` | 保险项：`node/Packet.cpp` 条件引用，当前宏未启用，几 KB 成本防未来开宏 |

上游 tag/SHA256 与 iOS 相同：`1.16.2` /
`2c607f573c6e38815433af289d364a689a203b18b51125f06c4472014d0657f0`。

## 4. gradle 友好报错

`core/build.gradle` 配置阶段检测 `../externals/core`（以 `.fetched-tag` 为准）缺失时抛
`GradleException`，中文提示「先执行 ./scripts/bootstrap.sh」，避免用户面对 CMake/GLOB 报错。

## 5. 许可证

- `docs/licenses/` 收录三份：`ZeroTierOne-MPL-2.0.txt`（自 `externals/core/LICENSE-MPL.txt` 迁移）、
  `prometheus-cpp-lite-MIT.txt`、`nlohmann-json-MIT.txt`。
- 仓库根新建 MIT `LICENSE` 文件（与 iOS 仓库一致，作者自有代码 MIT 发布）。

## 6. README 中英双份同步

以 iOS `README.md` / `README.en.md` 为骨架，iOS 特性内容按 Android 端实际改写：

| iOS 原文 | Android 改写 |
|---|---|
| 安装：bootstrap → xcodegen → Xcode 选开发团队 → Cmd+R | bootstrap → Android Studio 打开或 `./gradlew assembleDebug` → 装机；无签名门槛 |
| 命令行构建：`brew install xcodegen` | JDK 21 + NDK 25.2.9519653 环境，`./gradlew assembleDebug`；镜像/离线补救段保留（缓存路径相同） |
| 架构图：App + NEPacketTunnelProvider 双进程、ZTBridge/FrameConverter、App Group + Darwin notify | 单进程 VpnService + Supervisor 协程编排：NodeRuntime / TunnelManager / Dataplane（ARP/NDP 表、IPv4 源 NAT、LPM 选网）/ UdpCom / VpnNotification；TunnelStateBus 状态流通信，UI 不持有服务对象 |
| 目录：Core/、ZTBridge/、PlanetPortTunnel/、Shared/、PlanetPort/ | externals/core（bootstrap 拉取）、core/jni、app 内 tunnel/、store/、ui/ |
| 已知限制：App Store 未上架等 | Google Play 未上架；其余条目按 Android 端实际对称改写 |
| 特色 / 核心用法 / 捐赠 / 致谢 / 协议段 | 功能与 iOS 完全一致，基本原样；捐赠 TODO 占位同步；协议段 MIT + ZeroTier MPL-2.0 双协议说明 |

## 7. 验证

1. bootstrap 拉取后，子集内每个文件与移出前 git HEAD 版本逐字节一致（超集中未拉取的文件本就不参与编译）。
2. `./gradlew assembleDebug` 通过。
3. 真机（9311902b）安装，走核心流程：加入网络 → 在线互通 → 退出（对齐 spec P2 冒烟标准）。

## 8. 不做清单

- 不动 `externals/CMakeLists.txt` 的构建逻辑（仅注释措辞修正）。
- 不引入 submodule / Gradle 下载任务等其它管理方式。
- 不做 CHANGELOG / DESIGN.md 同步（仅 README 双份）。

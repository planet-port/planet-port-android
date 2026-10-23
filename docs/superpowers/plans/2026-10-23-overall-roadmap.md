# Planet Port Android 总体路线图

> 项目：ZeroTier 网络管理 Android 客户端，功能与 iOS 姊妹项目 planet-port-ios 完全一致。
> applicationId `cn.imgozi.a.planetport`，代码包 `cn.imgozi.planetport`。
> 本文档是六阶段总览与跨阶段移交的单一入口：每阶段闭合时更新「状态」与「结论」，
> 各阶段的详细实施计划（含代码）另见同目录 `2026-10-23-pN-*.md`。

**协议红线**：GPL 血统代码零引入（不引入 kaaass/ZerotierFix 任何代码）；唯一参考源 = planet-port-ios + ZeroTier 官方 MPL-2.0 组件（externals/ vendor）。

**既定约定**：不写测试用例；git 一律 `--no-pager`；提交 = 中文 Conventional Commits、Body 必写、禁止任何署名尾注。

## 阶段总览

| 阶段 | 范围 | 真机完成标准 | 状态 |
|---|---|---|---|
| P0 工程骨架 | Gradle 工程/vendor 组件接入/JNI 桥/CI/骨架页 | APK 启动 + libZeroTierOneJNI 加载成功 | ✅ 闭合（2026-10-23） |
| P1 数据层 | 模型/JSON 存储/planet·moon 导入/自检页 | 导入 planet/moon、切换、持久化正确 | ✅ 闭合（2026-10-23） |
| P2 隧道核心 | 单网络隧道全链路（服务壳/核心/UDP/数据面/聚合/验证页） | 加入 → 连通 → 退出 | ✅ 闭合（2026-10-24，判定线版） |
| P3 多网络数据面 | ping 闭环 + 多网络隔离验证 + 数据面调优 | 双网络不串扰、切换不风暴、首访不丢包 | ⬜ 未开始 |
| P4 UI 全量 | 正式 UI，替换验证脚手架 | 与 iOS 端视觉对照一致 | ⬜ 未开始（自 P1 起可并行） |
| P5 收尾 | 稳定性生命周期功能 | 锁屏/切网重连、开机自启、DNS 三模式热重配 | ⬜ 未开始 |

关键路径 P2→P3；P4 自 P1 起可并行（当前按串行推进）。

---

## P0 工程骨架 ✅

**做了**：Gradle/KTS 工程与模块骨架；ZeroTier 官方 MPL-2.0 组件接入 externals/（core/java 子模块钉住官方 fe29cd8，五文件 blob-hash 与上游字节一致）；自维护 JNI 守卫（官方 ScopedLocalReference 模式替代 kaaass 补丁）；CI workflow；骨架验证页。

**结论**：真机（Mi Note 3 9311902b）安装启动、`.so` 加载显示达成。终审 C1（kaaass 血统补丁）已彻底清除并经逐字节验证。

**遗留（用户侧）**：CI 首跑待建 remote push 后观察（用户已指示暂不测）。

## P1 数据层 ✅

**做了**：Models/JsonFileStore（原子写/损坏隔离）；AppConfigStore（planets/networks/moons 三配置域，域级互斥）；PlanetImporter/MoonImporter（文件+URL）；数据层自检页。

**结论**：真机自检 8 项全过（导入回读/moon 落盘/指针切换/分组盖戳/激活复位/级联回退）。终审两 Important（writePlanets 覆盖丢字段、自检页 networks 域零覆盖）修复并复审通过。

**移交输出**：configChanges 有损合并契约（订阅方 level-triggered 全量重读）；activatePlanet 只写指针不发通知（重启联动归隧道侧 P5）。

## P2 隧道核心 ✅

**做了**：ZeroTierVpnService 壳/TunnelStateBus/VpnNotification；NodeRuntime（JNI 核心生命周期/dataStore hook/planet 重定向）；UdpCom（UDP 9994 + protect）；数据面七文件（Ethernet/ArpTable/NdpTable/FrameConverter/Dataplane/RoutePlanner/NetworkContext，多网络隔离结构就绪）；TunnelManager（netconf 聚合/冲突检测/Builder establish/幂等闸门）；P2 验证页。

**结论**（14 commits，1d7ba3e..018204a）：真机判定线全过——运行中 + nodeId + join 正常(1) + tun0 持 172.28.100.174/24 + applyTrace 路由表落位 + 停止清理。**ping 未通**，定性：①Android split-tunnel 下 adb 流量走主表不进 VPN routing domain（验证方法学限制）；②对端→手机组播未达（RX=0）。终审经核心源码交叉验证排除代码结构性缺陷。质量：Critical 0，终审 Ready to merge，Minor 26 条全部 triage。

**关键实现事实**（后续阶段依赖）：Earth 公开网络已被官方 2023 下线，验证改用 Mac docker 自建 controller 网络（容器 zt1106，对端 172.28.100.196，环境保留中）；VpnService.Builder 为非静态内部类必须实例语法；核心每 60s 重推 CONFIG_UPDATE（已设幂等闸门）；leave 连带丢弃核心组播订阅记录（订阅已改为每次 apply 重发）。

**遗留（用户侧）**：真机人工点一次「停止」按钮确认（adb 模拟点击无效归为 MIUI 注入，代码链终审核实无缺陷）；P4 前提供一个可长期使用的私有网络做回归基线。

## P3 多网络数据面 ⬜（下一阶段）

**目标**：把 P2 的「隧道建立」推进到「数据可用」——ping/连通闭环，并验证多网络隔离设计。

**范围**（输入 = 设计文档定义 + P2 移交）：
1. ping 闭环：run-as 应用 uid 发流 + ARP 计数判层（statArpReq/Reply）定位 P2 ping 未通的对端方向问题；必要时组播订阅时机/帧路径调优。
2. NDP ICMPv6 校验和计算（P2 发现恒 0——iOS 同构共享问题，与真实 Linux 节点 v6 互通会被对端内核丢弃；IPv4 ARP 主路径不受影响；iOS 侧同步修复跨平台互通另记）。
3. 被踢单网拆 TUN 语义（valid.isEmpty() 显式拆除路径，消除路由黑洞滞留）。
4. split-tunnel 默认路由策略决策（具体路由不引入 app 流量的平台语义 vs 官方 0.0.0.0/0 全接管）。
5. 停机窄窗顺手项：UdpCom socket @Volatile、双 START 单实例守卫、swap 前 join 旧代读线程、runtime 比对 TOCTOU、establish 滞留 fd。
6. 多网络真机验证：双网络并存不串扰（冲突检测让路）、启停切换不风暴、首访不丢包（ARP 预热/PendingPacket 重放策略）。

**不做**：正式 UI（P4）；planet 切换重启联动（P5）。

## P4 UI 全量 ⬜

**目标**：正式 UI 替换验证脚手架，与 iOS 端视觉对照一致。

**输入（P1/P2 移交项）**：Builder 组装 try-catch（customDns 用户输入面）；appliedEntries 纳入 disableIpv6 比较；leave 显式拆 TUN（P3 若未做）；store 收敛进程单例（companion 流与实例锁不跨实例互斥）；startForegroundService 接线（后台启动限制）；planet 下载 Content-Length 上限+有界读取；MoonView 按 worldId 查重；验证脚手架页面退役。

**前置（用户侧）**：提供长期私有网络回归基线。

## P5 收尾 ⬜

**范围**：锁屏/切网重连稳定性；开机自启；DNS 三模式热重配端到端；activatePlanet 切换的隧道优雅重启联动（store 指针 → 停机 → 切目录 → 重启，UI 全程看状态流）；iOS NDP 构造侧错位跨平台互通复验。

---

## 跨阶段移交项汇总

| 移交项 | 来源 | 去向 | 状态 |
|---|---|---|---|
| CI 首跑 | P0 | 用户侧 | 待 push |
| configChanges level-triggered 契约 | P1 | P2（已消费） | 闭合 |
| activatePlanet 重启联动 | P1 | P5 | 挂起 |
| ping 判层复验 + 组播/帧路径调优 | P2 | P3 | 待接 |
| NDP 校验和恒 0 | P2 | P3（Android）/P5（iOS 同步） | 待接 |
| 被踢单网不拆 TUN 黑洞 | P2 | P3 | 待接 |
| split-tunnel 默认路由策略 | P2 | P3 | 待决策 |
| 停机窄窗五项 | P2 | P3 顺手 | 待接 |
| Builder try-catch / appliedEntries+disableIpv6 / leave 拆 TUN / store 单例 / startForegroundService | P2 | P4 | 待接 |
| planet 下载上限 / moon worldId 查重 | P1 终审 | P4 | 待接 |
| 停止按钮人工确认 / 长期回归网络 | P2 | 用户侧 | 待确认 |
| iOS NDP 构造错位（跨平台 v6 互通） | P2 | P5 备忘 | 待接 |

## 进度记录约定

- 每阶段闭合：更新本文档对应阶段「状态/结论」与移交表。
- 阶段内执行细节：`.superpowers/sdd/progress.md`（账本）+ 阶段实施计划（含代码与差异回写）。

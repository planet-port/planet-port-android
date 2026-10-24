# P3 多网络数据面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 P2 的「隧道建立」推进到「数据可用」——ping/连通闭环、NDP 出站合规、被踢网络拆 TUN、多网络真机验证（双网络不串扰、切换不风暴、首访不丢包）。

**Architecture:** 全部改动落在既有 tunnel/ 组件内：NDP 合规三件套（校验和/L3 组播 dst/真实 src）在 NdpTable+FrameConverter；被踢拆 TUN 与停机窄窗在 TunnelManager/Dataplane/ZeroTierVpnService/UdpCom；ping 判层为真机调查任务；多网络验证用 Mac docker 自建第二网络。

**Tech Stack:** Kotlin + ZeroTier 官方 SDK（vendor MPL-2.0）；真机 Mi Note 3（serial 9311902b）；测试环境 Mac docker 容器 zt1106（controller+对端 172.28.100.196，API http://127.0.0.1:9993 容器内可达，token 2h2e1n8zclou4ky4il9dimsz）。

## Global Constraints

- GPL 血统代码零引入：唯一参考源 = planet-port-ios（/Users/zivyuan/GoziLib/Zerotier/planet-port-ios）+ ZeroTier 官方 MPL-2.0 组件；禁止引入 kaaass/ZerotierFix 任何代码。
- 不写测试用例；验证 = 编译（./gradlew compileDebugKotlin，里程碑任务 assembleDebug+assembleRelease）+ 真机。
- git 一律 `--no-pager`；提交 = Conventional Commits 中文、type/scope 英文、Body 必写、结尾不加句号、禁止任何 Co-authored-by/AI 署名尾注。
- 锁纪律：configMutex → converter.lock → arp/ndp 表锁严格单向；FrameConverter synchronized 块内只调 JNI 与内存操作，禁挂起点。
- MIUI 真机操作路径：startForegroundService 需 `am start` MainActivity 后 `run-as cn.imgozi.a.planetport am start-foreground-service --user 0`；配置预置走 `/data/local/tmp` push + run-as cp。
- 并行实现期间控制方提交必须限定 git add 路径（P2 教训）。
- VpnService.Builder 为非静态内部类，必须 `vpnService.Builder()` 实例语法（P2 裁决）。

## 侦查事实（计划依据，实现者必读）

1. **NDP 现状**（FrameConverter.kt/NdpTable.kt）：出站 NS/NA 的 ICMPv6 校验和恒 0（NdpTable.kt p[2..3] 从未写）；外层 ipv6Packet（FrameConverter.kt:283-293）src 恒 `::` 全零（字节 8..23 未写）；NS 的 L3 dst 是 target 单播（:278 传 target），组播只体现在 L2 MAC——与 RFC 4861「NS 发往请求节点组播地址」不符。入站 parse 不读 offset 2-3（天然兼容对端带校验和的报文），本计划只修出站。
2. **被踢不拆 TUN**：TunnelManager.applyAggregated 的 valid.isEmpty() 分支（约 :201）只 setApplyTrace 返回。converter.retainOnly 在该分支之前已执行（软清理完成），缺硬拆：`dataplane.shutdown()`（关 fd = tun0 消失 = 系统路由自动撤销）。**必须同分支置 appliedSignature = null**，否则同配置网络恢复时被幂等闸门挡住不重建。iOS 同构行为是保留旧设置成黑洞——Android 主动拆是对参考的合理超越（iOS 侧同步修复记 P5 备忘）。
3. **split-tunnel 语义与 iOS 完全同构**（routeVia 单网络才接管默认/地址网段+托管路由/物理网段挖洞）——不是缺陷；P2 ping 未通优先按验证方法学排查（T1）。
4. **NDP 构包仅两处**：收 NS 回 NA（FrameConverter.kt:230-236，srcIPv6=NS 发送方地址 :227）；出站无表项发 NS（:273-280）。NA 的合规 src = p.target 本身（请求方问「谁持有 p.target」，本机持有它，应答源地址即它）。

---

### Task 1: ping 判层定位与连通闭环

**Files:**
- 可能修改： `app/src/main/java/cn/imgozi/planetport/tunnel/` 下按定位结论而定（调查型任务，先诊断后修复）
- 产出： 诊断结论追加至 `.superpowers/sdd/task-1-report.md`

**Interfaces:**
- Consumes: P2 全链路（已运行）；docker 测试环境（容器 zt1106 对端 172.28.100.196）；诊断计数 statArpReq/statArpReply/statInV4/statFwdV4（applyTrace 的 converter.debugSummary() 透出）
- Produces: ping 连通闭环 或 明确的非代码阻塞结论 + （若定位到代码缺陷）修复提交

**背景**：P2 真机判定线全过但 ping 172.28.100.196 未通。两层候选原因：①adb/run-as 流量走主表不进 VPN routing domain（split-tunnel 平台语义）；②对端→手机方向组播帧未达（tun0 RX=0）。终审已排除结构性缺陷（组播订阅语义经核心源码证实、FrameConverter 入站路径完整）。

- [ ] **Step 1: 环境就位**

容器在跑（Mac 上 `docker ps | grep zt1106` 确认；未跑则 docker start zt1106）。手机 USB 连接（adb devices 见 9311902b），networks.json 已含 66defc674a000001（P2 留存）。

```bash
adb devices
docker ps --filter name=zt1106 --format '{{.Names}} {{.Status}}'
docker exec zt1106 zerotier-cli listnetworks   # 对端在线且网络 OK
```

- [ ] **Step 2: 启动隧道并确认判定线仍达成**

```bash
adb -s 9311902b shell am start -n cn.imgozi.a.planetport/cn.imgozi.planetport.ui.MainActivity
sleep 2
adb -s 9311902b shell run-as cn.imgozi.a.planetport am start-foreground-service --user 0 -n cn.imgozi.a.planetport/cn.imgozi.planetport.tunnel.ZeroTierVpnService -a cn.imgozi.planetport.action.START
sleep 15   # 等 join + netconf + establish
adb -s 9311902b shell ip addr show tun0        # 期望 172.28.100.174/24
```

- [ ] **Step 3: 判层——应用 uid 发流 + ARP 计数读数**

```bash
# VPN routing domain 内的应用 uid 发流（绕开 adb shell uid 走主表的疑点）
adb -s 9311902b shell run-as cn.imgozi.a.planetport ping -c 4 -W 2 172.28.100.196
# 读诊断计数（applyTrace 经 UI 可见；adb 侧用 logcat 抓）
adb -s 9311902b logcat -d | grep -E "apply|cfg|establish" | tail -20
```

判层决策树（依次判定，记录每层证据）：

| 观察 | 结论 | 下一步 |
|---|---|---|
| ping 通 | 闭环达成 | 直接 Step 5 |
| statArpReq>0 且 statArpReply=0 | 出站 ARP 已发、对端无应答——组播/对端方向问题 | Step 4-A |
| statArpReq=0 且 tun0 TX>0 | 包进 TUN 但没触发 ARP——FrameConverter 选路问题 | Step 4-B |
| tun0 TX=0（ip -s link） | 流量根本没进 TUN——VPN domain/路由问题 | Step 4-C |

- [ ] **Step 4: 按层定位（授权就地修复，修复后重建重验）**

- **4-A 组播/对端方向**：对端 `docker exec zt1106 tcpdump -i any -n arp` 看手机 ARP request 是否到达；手机侧核对 multicastSubscribe 调用日志与核心 peers 状态（`TUN_ESTABLISHED` 相关 eventLog）。若对端收到 request 但无 reply——查对端 ARP 应答路径（容器内 arp 表）；若对端没收到——手机组播订阅帧未出核心，查 FrameConverter.sendARPRequestLocked 与 keepalive 触发链。
- **4-B 选路**：核对 applyTrace 的 aggV4 与目的地址匹配（172.28.100.174/24 应命中直连路由）；NetworkContextMath 路由排序。
- **4-C VPN domain**：`adb shell dumpsys connectivity` 看 VPN network 与 uid 范围；确认 run-as uid（应用自身）在 VPN 覆盖内；尝试 `dumpsys network_management` 路由表。若确认平台限制（应用自身 uid 流量也不进 tun），记录证据并将「app 内发流验证」列 P4（正式 UI 内嵌 ping 诊断）。

- [ ] **Step 5: 记录与提交**

判定线：ping 4 包至少 1 回包 = 闭环达成（满分）；或定位到明确非代码阻塞（平台限制/对端环境）并记录证据链 = 达成（代码缺陷则必须已修复并重验）。报告写入 `.superpowers/sdd/task-1-report.md`（每层证据+结论+提交哈希）。有代码改动则编译+提交（限定路径）。

```bash
git add <改动文件路径> && git commit -m "fix(tunnel): <定位结论的一句话>

<背景：判层证据链 → 根因 → 修法>"
```

---

### Task 2: NDP 出站合规三件套

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/NdpTable.kt`（companion 加校验和函数）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/FrameConverter.kt`（ipv6Packet 加 src 参数；两处 NDP 构包点接校验和与合规地址）

**Interfaces:**
- Consumes: `NdpTable.neighborSolicitation/neighborAdvertisement(target, mac)`（现有签名不变）；`ipv6Packet(payload, dst)`（现有两参签名将变三参，调用点仅 NDP 两处）
- Produces: `NdpTable.icmpv6Checksum(src: Ipv6Address, dst: Ipv6Address, payload: ByteArray): void`（回写 payload[2..3]，FrameConverter 调用）；`ipv6Packet(payload: ByteArray, src: Ipv6Address, dst: Ipv6Address): ByteArray`

- [ ] **Step 1: NdpTable companion 增加校验和函数**

在 `NdpTable.kt` companion object 内（`neighborAdvertisement` 之后）加：

```kotlin
/**
 * ICMPv6 伪头校验和（RFC 4443 §8.1）：src(16B)+dst(16B)+payload 长度(32bit)+
 * 3 零字节+nextHeader(58)，与 ICMPv6 载荷一起按 RFC 1071 互联网校验和求和，
 * 结果取反回写 payload[2..3]。计算前先清零校验和字段。真实节点内核会校验，
 * 恒 0 的报文被丢弃（Android↔Android 双端软件路径不校验故此前未暴露）。
 */
fun icmpv6Checksum(src: Ipv6Address, dst: Ipv6Address, payload: ByteArray) {
    payload[2] = 0; payload[3] = 0
    var sum = 0L
    fun add16(word: Int) { sum += word }
    for (i in 0 until 16 step 2) {
        add16(((src.bytes[i].toInt() and 0xff) shl 8) or (src.bytes[i + 1].toInt() and 0xff))
    }
    for (i in 0 until 16 step 2) {
        add16(((dst.bytes[i].toInt() and 0xff) shl 8) or (dst.bytes[i + 1].toInt() and 0xff))
    }
    add16(payload.size) // 伪头 32bit 长度的高 16 位恒 0，单字相加等价
    add16(58)
    var i = 0
    while (i < payload.size) {
        val hi = payload[i].toInt() and 0xff
        val lo = if (i + 1 < payload.size) payload[i + 1].toInt() and 0xff else 0
        add16((hi shl 8) or lo)
        i += 2
    }
    while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
    val ck = sum.toInt().inv() and 0xffff
    payload[2] = (ck shr 8).toByte()
    payload[3] = (ck and 0xff).toByte()
}
```

- [ ] **Step 2: ipv6Packet 加 src 参数**

`FrameConverter.kt` 现函数（:283-293）替换为：

```kotlin
/** ICMPv6 载荷包成最小 IPv6 包（next header 58，hop limit 255） */
private fun ipv6Packet(payload: ByteArray, src: Ipv6Address, dst: Ipv6Address): ByteArray {
    val p = ByteArray(40 + payload.size)
    p[0] = 0x60
    p[4] = ((payload.size shr 8) and 0xff).toByte()
    p[5] = (payload.size and 0xff).toByte()
    p[6] = 58
    p[7] = 255.toByte()
    src.bytes.copyInto(p, 8)
    dst.bytes.copyInto(p, 24)
    payload.copyInto(p, 40)
    return p
}
```

- [ ] **Step 3: NA 构包点（收 NS 回 NA）**

`handleNDPLocked` 的 `135 ->` 分支（:230-236）替换为：

```kotlin
135 -> if (ctx.ipv6Addresses.contains(p.target)) {
    val na = NdpTable.neighborAdvertisement(p.target, ctx.mac)
    // NA 源地址 = 被解析的本机地址（请求方问"谁持有 target"，我方持有）；
    // 校验和伪头用真实 src/dst，恒 0 会被真实节点内核丢弃
    NdpTable.icmpv6Checksum(p.target, srcIPv6, na)
    runtime.processVirtualNetworkFrame(
        ctx.nwid, ctx.mac, senderMac, Ethernet.TYPE_IPV6, 0,
        ipv6Packet(na, p.target, srcIPv6),
    )
}
```

- [ ] **Step 4: NS 构包点（出站无表项发 NS）**

`sendNeighborSolicitationLocked`（:273-280）替换为：

```kotlin
private fun sendNeighborSolicitationLocked(ctx: NetworkContext, target: Ipv6Address) {
    val src = ctx.ipv6Addresses.firstOrNull() ?: return
    val ns = NdpTable.neighborSolicitation(target, ctx.mac)
    // RFC 4861：NS 的 L3 目的 = 请求节点组播地址（此前误用 target 单播，组播只在 L2），
    // 源 = 本机接口地址（:: 仅 DAD 合法）
    val dst = NdpTable.solicitedNodeMulticast(target)
    NdpTable.icmpv6Checksum(src, dst, ns)
    runtime.processVirtualNetworkFrame(
        ctx.nwid, ctx.mac,
        NdpTable.multicastMAC(dst),
        Ethernet.TYPE_IPV6, 0, ipv6Packet(ns, src, dst),
    )
}
```

- [ ] **Step 5: 编译 + 提交**

```bash
./gradlew compileDebugKotlin -q
git add app/src/main/java/cn/imgozi/planetport/tunnel/NdpTable.kt app/src/main/java/cn/imgozi/planetport/tunnel/FrameConverter.kt
git commit -m "fix(tunnel): NDP 出站合规——ICMPv6 校验和、组播目的与本机源地址

出站 NS/NA 此前三处不合规：校验和恒 0（真实节点内核会校验丢弃，
Android↔Android 双端软件路径不校验故未暴露）、NS 的 L3 目的误用
target 单播（RFC 4861 应为请求节点组播地址，组播此前只在 L2）、
外层 IPv6 源地址恒 ::（仅 DAD 合法）。NA 源取被解析的本机地址，
NS 源取本网络首个 IPv6，伪头校验和按 RFC 4443/1071 计算回写。
入站 parse 不读校验和字段，与带校验和的对端报文天然兼容。"
```

真机验证并入 Task 4（与 Linux 容器对端 v6 互通需容器配 v6 网段，单独验证成本高；v4 主路径不受影响）。

---

### Task 3: 被踢网络拆 TUN 与停机窄窗收口

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/TunnelManager.kt`（valid.isEmpty() 拆 TUN + 签名联动）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/Dataplane.kt`（swap 前 join 旧代读线程；shutdown 后拒绝迟到 swap）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`（双 START 单实例守卫）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/UdpCom.kt`（socket @Volatile）

**Interfaces:**
- Consumes: `Dataplane.shutdown()`（既有，关 fd = 拆 TUN）；`appliedSignature`（T6 引入的幂等闸门字段）
- Produces: 无新对外接口（行为修正）

- [ ] **Step 1: TunnelManager——valid.isEmpty() 拆 TUN**

`applyAggregated` 的 isEmpty 分支（现约 :201-206）替换为：

```kotlin
if (valid.isEmpty()) {
    // 全部网络失效（被踢/无地址）：拆 TUN——fd 关闭即接口消失、系统路由自动撤销。
    // converter 侧软清理（retainOnly）已在上面完成。iOS 同构行为是保留旧设置成
    // 黑洞，Android 主动拆优于此（iOS 侧同步修复记 P5）。签名必须同置 null：
    // 同配置网络恢复时若签名命中会被幂等闸门挡住不重建
    dataplane.shutdown()
    appliedSignature = null
    TunnelStateBus.setApplyTrace(
        "items=${items.size} valid=0（无可用网络，已拆 TUN）\n" + converter.debugSummary()
    )
    return@withLock
}
```

注意：`dataplane.shutdown()` 持 configMutex 阻塞 IO 线程 ≤500ms（内部 join 读线程），与停机路径同量级可接受。

- [ ] **Step 2: Dataplane——swap 前 join 旧代读线程 + 拒绝迟到 swap**

字段区加 `@Volatile private var stopped = false`。`swap()` 开头（generation 自增后）与 `shutdown()` 改为：

```kotlin
@Synchronized
fun swap(fd: ParcelFileDescriptor) {
    if (stopped) {
        // 停机后迟到的 establish（withContext(IO) 内不可取消）：拒绝并关闭新 fd 防泄漏
        fd.close()
        return
    }
    generation += 1
    val gen = generation
    // 旧代读循环看到 generation 变化即退出，但可能仍在 onTunPacket 的 JNI 内——
    // 等它落地再关旧资源（终审 Minor：换代窗口残窗）
    readThread?.join(500)
    writeExecutor?.shutdownNow()
    outputStream?.close()
    currentFd?.close()
    currentFd = fd
    // ...（其余不变：outputStream 赋值、写线程、读线程创建）
}

@Synchronized
fun shutdown() {
    stopped = true
    generation += 1
    // ...（其余不变）
}
```

- [ ] **Step 3: ZeroTierVpnService——双 START 单实例守卫**

`startTunnel()` 首行（setTunnelState(Starting) 之前）加：

```kotlin
// 双 START 守卫：二轮启动会 bind 9994 失败错置 Failed 状态；一轮存活则忽略重复指令
if (scope?.isActive == true) return
```

- [ ] **Step 4: UdpCom——socket @Volatile**

socket 字段加 `@Volatile`（失效方向丢包无崩溃，但一行成本消除可见性风险）。

- [ ] **Step 5: 编译 + 提交**

```bash
./gradlew compileDebugKotlin -q
git add app/src/main/java/cn/imgozi/planetport/tunnel/TunnelManager.kt \
        app/src/main/java/cn/imgozi/planetport/tunnel/Dataplane.kt \
        app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt \
        app/src/main/java/cn/imgozi/planetport/tunnel/UdpCom.kt
git commit -m "fix(tunnel): 被踢网络拆 TUN 与停机窄窗收口四项

全部网络失效（控制器吊销/无地址）时此前保留旧 TUN 成路由黑洞
（iOS 同构保留旧设置，Android 主动拆优于此）：valid.isEmpty 分支
关 fd 拆接口，签名同置 null 防幂等闸门挡住恢复重建。停机窄窗：
Dataplane 换代前 join 旧代读线程（JNI 残窗）、stopped 标志拒绝
停机后迟到的 establish（滞留 fd）；startTunnel 双 START 守卫；
UdpCom socket @Volatile。"
```

---

### Task 4: 多网络真机验证与全量回归

**Files:**
- 产出： 验证记录 + 截图至 `.superpowers/sdd/task-4-report.md` 与 `.superpowers/sdd/shots/`
- 可能修改： 定位到的缺陷（授权就地修复）

**Interfaces:**
- Consumes: Task 1-3 全部改动（本任务在三者合入后执行）；docker controller API
- Produces: P3 完成标准的真机证据

**前置**：基于 T1-T3 合入后的 HEAD `assembleDebug` 装机（9311902b）。

- [ ] **Step 1: docker 建第二网络**

controller API（Mac，容器内执行）建网络 66defc674a000002，网段 172.28.101.0/24，对端容器 join 并取 172.28.101.x：

```bash
docker exec zt1106 zerotier-cli join 66defc674a000002   # 若网络未建: 用 controller API 建网+开放加入+设网段
docker exec zt1106 zerotier-cli listnetworks
```

（controller 建网流程：P2 环境搭建时的同等命令，见容器内 /var/lib/zerotier-one/ 与 API token；网段 172.28.101.0/24，172.28.100.196 对端将同时在线两网。）

- [ ] **Step 2: 单网络回归（T1-T3 后全量）**

T1 判定线复验（ping 172.28.100.196 或 T1 结论的替代判定）；60s CONFIG_UPDATE 周期观察日志无重复 establish（幂等闸门有效）；被踢场景：controller 侧 deauthorize 手机 → ≤90s 内 tun0 消失（60s 重推 + 拆 TUN生效）→ reauthorize → tun0 恢复（签名置 null 后重建）。**被踢全链必须 logcat 盯 onRevoke/stopTunnel 链（终审 Important）**：若平台在 tun 接口消失时触发 onRevoke → stopTunnel 完整停机，闩锁解耦保住的重建能力落空（表现为 tun0 不恢复、需手动重启）——此时就地修复：拆 TUN 路径加来源标志，onRevoke 区分「系统撤销」与「自拆」（自拆不触发停机），修复后重验全链；onRevoke 未触发且 tun0 自动恢复则该 Important 关闭。

- [ ] **Step 3: 双网络不串扰**

手机 join 第二网络 → applyTrace items=2 valid=2、两网段路由各自落位（`ip route show table 1026`）；ping 对端两个网段地址均通；事件日志无冲突告警。

- [ ] **Step 4: 切换不风暴**

验证页将网络一静默（join 按钮对其他网络置静默的既有逻辑）→ 观察单次 apply/establish（日志计数）、无 ARP 刷屏（eventLog 无连续重复）；恢复双网同理。

- [ ] **Step 5: 首访不丢包**

冷启动（force-stop 后重走启动流程）+ 立即 `ping -c 4 172.28.100.196`：待决队列应保住首包（首包丢失 ≤1 记达标——ARP 学习一 RTT 的业界常态；全丢则查 pendingV4 重放链）。

- [ ] **Step 6: 冲突让路一例**

docker 临时建第三网络（网段与网络一重叠，如 172.28.100.0/25）→ 手机 join → applyTrace 应显示 conflict=address/subnet 且该网不进数据面（另一网 ping 仍通）。

- [ ] **Step 7: 记录与提交**

四项完成标准逐条记录证据（截图/logcat 摘录）；有修复则编译提交。报告写 `.superpowers/sdd/task-4-report.md`。

---

## 附：执行编排与注意

1. **顺序**：T1 先行（真机调查，结论可能调整后续优先级）→ T2+T3 并行（worktree 隔离，互不碰文件：T2=NdpTable/FrameConverter，T3=TunnelManager/Dataplane/ZeroTierVpnService/UdpCom）→ T4 全量回归（合并后）。
2. **T1 授权**：判层到代码缺陷就地修复重建重验（DONE_WITH_CONCERNS 允许——非代码阻塞如实记录）。
3. **跨任务锚点**：`appliedSignature`（T3 Step 1 依赖 T6 已合入语义）；`statArpReq/statArpReply/statInV4`（T1 判层读数，FrameConverter debugSummary 透出）；`ipv6Packet` 三参签名仅 NDP 两调用点（T2 自洽，无外部消费者）。
4. **真机**：Mi Note 3 serial 9311902b；截图验证用 mcp__zai-mcp-server__analyze_image 独立读图复核（P0-P2 已验证流程）。
5. **T4 docker controller**：建网命令以容器内实际 API 为准（P2 环境搭建同源），对端 1.10.6 内置 controller。

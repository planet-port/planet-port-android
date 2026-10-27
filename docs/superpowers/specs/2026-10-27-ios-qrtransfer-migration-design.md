# iOS dev 新增功能全量迁移设计（QRTransfer 等）

日期：2026-10-27
基线：planet-port-ios dev `60d49f7`（Android dev 已同步至 iOS `02a901b`）
落点：Android `dev` 分支

## 背景与范围

iOS dev 在 Android 上次同步点（`02a901b`，10-26 18:08）之后新增 15 个提交，全量迁移分三块：

| 块 | 内容 | iOS 提交 |
|---|---|---|
| 1 | 动态二维码分享与导入自定义 Planet（QRTransfer 全套，~2400 行） | a194640…60d49f7（10 条） |
| 2 | 新增页离开超 5 分钟自动清空表单 | 5dc8cff |
| 3 | 漏同步三项：关于页构建信息、托管路由行切换网段/网关、删除按钮锚点核对 | 73983da / eadcda7 / e4139a9 |

iOS 侧留档： `Modules/QRTransfer/` 与 `docs/plans/2026-10-27-planet-qr-share.md`（1201 行实现计划）。

## 块 1：QRTransfer 动态二维码分享与导入

### 协议（ZTQR1）要点

- 帧字符串：`ZTQR1|total|index|crc32|len|chunk`，定长头 27 字符；total/index 两位十进制（1..99，index 从 1 起）；crc32 为原始数据 CRC-32（IEEE，反射 0xEDB88320）八位十六进制；len 为 Base45 全长五位十进制
- 数据流水线：原始数据 → 压缩容器（4 字节小端原始长度 + raw deflate 流）→ Base45（RFC 9285 字母表，恰为 QR alphanumeric 全集且不含 `|`）→ 均匀分片（等宽 chunk，末帧补 `0` 对齐，全序列每帧字符数严格相等）→ 帧字符串数组
- 容量探测：二分查找最大分片字符数，使真实帧头（`ZTQR1|99|99|ffffffff|00000|`）+ 小写 `z` 填充串（强制 byte mode 最坏情形）生成的二维码模块数 ≤ maxModules
- 模块上限：`max(53, min(117, side/2.5 - 8))`（模块 ≥2.5pt、quiet zone 4 模块、v9~v25）
- 帧数上限 32；解码端齐全后先按 len 剥补位，再 Base45 → 解压 → CRC 三重校验

### Android 架构决策

**协议层：纯 Kotlin 自包含包 `cn.imgozi.planetport.qrtransfer`**（单 :app 模块内，不建独立 Gradle 模块——iOS 独立模块为将来 pod 化，Android 无此需求）：

- `Base45.kt` / `CRC32.kt`：按 iOS 逐行 1:1 翻译（各 ~50 行）
- `Deflate.kt`：改用 `java.util.zip.Deflater/Inflater`（raw 模式），容器格式不变，与 iOS `COMPRESSION_ZLIB` 字节兼容——跨端互扫互通的根基
- `QRFrame.kt` / `QRTransfer.kt` / `ChunkPlanner.kt`：帧协议、均匀分片、二分容量探测，1:1 移植
- `QRRender.kt`：复用已有 zxing——`QRCodeWriter`（M 纠错）出位图；容量探测用 `Encoder.encode(text, M).version` → 模块数 = 17+4v，等价 iOS CIQRCodeGenerator extent 探测

**UI 层：`ui/qrtransfer` 三个组件**，呈现形式沿用 App 惯例 ModalBottomSheet（与 QrScannerSheet 同族）：

- `QRShareSheet`：后台编码分片 → 逐张渲染逐张回填（首张就绪即显示）；单帧退化为静态码；多帧提供四档速度（0.3/0.5/0.8/1.2s）+ 手动模式（点分片方块跳帧）
- `QRReceiveSheet`：CameraX 连续扫码（复用 QrScannerSheet 预览+逐帧解码模式）+ 800ms 同值去重 + 会话切换（total/checksum 变化）自动清空 + 齐全后三重校验；相机权限未决弹系统框、拒绝展示引导页
- `ShardGrid`：分片方块阵列（30×22dp 圆角方块+分片号），FlowRow 流式换行每行居中；发送端 current 高亮主题色、接收端已收格主题色 35% 透明填充、未收格描边

### 接入点

- **行星设置页**：文件大小行内分享图标移除；信息区新增「分享」行（非官方行星且 planet 文件存在时显示），「文件 / 二维码」双选项；二维码选项读 planet 文件进 QRShareSheet，文件选项走现有 FileProvider ACTION_SEND
- **新增页**：文件选择与 URL 区之间新增第 4 个按钮「扫描二维码导入行星」（同款 OutlinedButton 视觉），收齐回填 pendingData，复用现有命名+添加+查重流程

## 块 2：新增页 5 分钟自动清空表单

`isCurrentPage` 机制已有（重掷身份用），补离开时间戳：离开记墙钟、回来时超 5 分钟清空 URL 与已就绪文件（pendingData），时间戳判墙钟、App 挂起期间同样计时。

## 块 3：漏同步三项

1. **关于页构建信息**（73983da）：Gradle `generateBuildInfo` 任务（挂 preBuild）生成 `BuildInfo.kt`（构建时间 `%Y-%m-%d %H:%M:%S` + `git describe --tags --always --dirty=+dirty`），设置-关于区新增构建信息行（上行时间、下行版本，等宽字体）；生成文件入 .gitignore
2. **托管路由行切换**（eadcda7）：NetworkDetailView 路由行默认只显示目标网段，点击值区域切换 `via 网关`，末尾双箭头图标提示；直连路由（无 via）不可切换不显示图标；展开状态按下标追踪，列表 id 改下标防重复路由串崩溃
3. **删除按钮锚点**（e4139a9）：iOS 26 平台特有按钮渲染 bug，Android 无此问题；按 1:1 精神核对行星设置页/网络详情页删除按钮对齐与确认弹窗锚点，一致则跳过并留档

## i18n

iOS `QRTransfer.xcstrings` + `Localizable.xcstrings` 新增键全部并入 app 三语 strings.xml（values / values-en / values-zh-rTW）。

## 测试

新建 `app/src/test`（项目首个测试源集），移植 iOS 11 例单测：Base45 编解码往返、CRC32、Deflate 容器往返、帧协议编解码（含补位剥离/混会话拒绝/CRC 校验）、ChunkPlanner 容量探测（含探测前缀与 headerLen 等长断言）。重点：与 iOS 实现字节兼容（互扫互通的回归防线）。

## 不做的事

- 不建独立 Gradle 模块（iOS pod 化诉求不适用）
- 不引 CameraX/MLKit 之外的扫码新依赖（复用现有 CameraX + zxing）
- e4139a9 的 iOS 26 渲染 fix 不照搬代码，只核对等效交互

## 验收标准

1. 单测全绿（协议字节兼容）
2. 真机互扫：Android 分享 ↔ iOS 接收、iOS 分享 ↔ Android 接收，双向通过
3. 三语文案、深色模式下 UI 正常

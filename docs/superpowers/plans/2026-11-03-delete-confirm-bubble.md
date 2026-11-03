# 删除确认自绘锚定气泡 + 使用中删除顺序重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 iOS 提交 2488ced（删除行星改用自绘锚定气泡并重构使用中的删除顺序）迁移到 Android：两处删除确认（行星设置页、网络详情页）从系统 AlertDialog 改为自绘锚定气泡，行星删除顺序改为三段式，文案对齐。

**Architecture:** 新增共享组件 `ui/components/ConfirmBubble.kt`（全屏点击 catcher + 锚定在触发行上方的气泡，尾尖指向该行）；两个页面把删除行 `onGloballyPositioned` 上报的根坐标传给气泡，替换 AlertDialog。`PlanetSettingsViewModel.delete` 改三段式：写回 lastActivated=false → stop+awaitSettled → 级联删除。

**Tech Stack:** Jetpack Compose (Material 3)，Kotlin 协程。无新增依赖。

## Global Constraints

- 本仓库： `/Users/zivyuan/GoziLib/Zerotier/planet-port-android` ，分支 `dev`（所有命令在此目录执行）
- 不新增测试用例（用户规则 7.1）；验证手段 = 编译通过（`./gradlew :app:compileDebugKotlin`），真机验证由用户执行
- 用户 Git 规则：提交信息 Conventional Commits、中文 Body、禁止 Co-authored-by；整个迁移是一次逻辑单元，**全部任务完成且编译通过后一次提交，不推送**
- 若 gradle 因沙箱无法联网拉依赖，用 `dangerouslyDisableSandbox` 重试（依赖通常已缓存）
- 删除行现状（两个页面）已是纯视图 `Box + clickable` 居中红字，与 iOS 2488ced 的「纯视图写法」天然对齐，**不改行的写法**，只加锚点上报
- iOS 视觉参数（对齐用，见 ios planet-port-ios 仓库 `PlanetPort/Views/ConfirmBubble.swift`）：气泡宽 260、内边距 16、圆角 20、标题/正文间距 4、正文/按钮间距 14、按钮垂直内边距 9（Capsule 胶囊）、尾尖 18×9 向下探 9、气泡底边与卡片顶边呼吸间距 10、动画 easeOut 0.15s + scale 0.95（锚点底部中心）、顶部对齐盒子下限高 60

## 背景知识（执行者必读）

- iOS 为什么要气泡：系统弹窗（confirmationDialog/action sheet/popover）锚点随系统版本三次漂移，放弃系统呈现权，自绘全版本一致。Android 的 `AlertDialog` 是居中模态，没有锚定能力，为保持双端交互一致（气泡贴在删除行上方）同样自绘。
- 锚点换算：`FormSection` 的卡片 `SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp))`（见 `app/src/main/java/cn/imgozi/planetport/ui/form/FormSections.kt:48`）。删除行 Box 上报 `positionInRoot().y` 是行内容顶边；卡片可视顶边 = 行顶 − 4（卡片垂直内边距）；气泡底边 = 卡片顶边 − 10（呼吸间距）。
- 三段式删除顺序（iOS 语义）：使用中的行星直接 stop 会把网络硬断；先写回 `lastActivated=false` 让隧道 applyConfig diff 逐网 leave（优雅退出），再停隧道等终态（给 leave 留处理时间窗），最后级联删除配置并回退官方行星。未使用中的行星直接删。
- Android 现成等价件：`StoreRepo.store.writeNetworks(values, planetId)` 写回并发 `notifyConfigChanged()`（`store/AppConfigStore.kt:171`）；`TunnelController.stop(context)` 幂等（终态 no-op，`tunnel/TunnelController.kt:87`）；`TunnelController.awaitSettled(timeoutMs)` internal suspend，等隧道离开存活态，已是终态立即返回（`tunnel/TunnelController.kt:107`）。

---

### Task 1: ConfirmBubble 共享组件

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/ui/components/ConfirmBubble.kt`

**Interfaces:**
- Produces: `@Composable fun ConfirmBubble(visible: Boolean, anchorTopPx: Float, title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, message: String? = null)`（Task 3/4 按此签名调用）

- [ ] **Step 1: 写组件文件**

完整文件内容：

```kotlin
package cn.imgozi.planetport.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.Capsule
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 锚定在触发行上方的确认气泡（自绘，对齐 iOS ConfirmBubble）。
 *
 * 双端交互一致：不做居中模态，气泡底边贴触发行卡片可视顶边（含呼吸间距）、
 * 尾尖向下探入行内指向该行；点气泡外任意处关闭。视觉参数与 iOS 版一致：
 * 宽 260 / 内边距 16 / 圆角 20 / 标题正文间距 4 / 正文按钮间距 14 / 按钮垂直
 * 内边距 9（胶囊）/ 尾尖 18×9 下探 9 / 呼吸间距 10 / 动画 0.15s scale0.95。
 *
 * [anchorTopPx] 是触发行内容顶边在组合根坐标系的 y（页面删除行用
 * `onGloballyPositioned { it.positionInRoot().y }` 持续上报）。本组件铺满
 * 全屏且自身无平移，根坐标可直接使用。滚动时 onGloballyPositioned 重新
 * 上报，气泡跟随。
 */
@Composable
fun ConfirmBubble(
    visible: Boolean,
    anchorTopPx: Float,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
) {
    val density = LocalDensity.current
    // SectionCard 内容垂直内边距 4：锚点量到行内容顶边，上提后贴卡片可视顶边
    val cardInsetPx = with(density) { 4.dp.toPx() }
    // 气泡底边与卡片顶边之间的呼吸间距
    val gapPx = with(density) { 10.dp.toPx() }
    // 顶部对齐盒子高度 = 容器顶到卡片可视顶边；下限 60 防触发行贴屏顶时塌缩
    val boxHeight = with(density) {
        max(60.dp.toPx(), anchorTopPx - cardInsetPx - gapPx).toDp()
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) +
            scaleIn(
                animationSpec = tween(150),
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(0.5f, 1f),
            ),
        exit = fadeOut(tween(150)),
    ) {
        // 全屏点击 catcher：气泡外任意点关闭
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            // 高度 = 容器顶到触发行卡片可视顶边的盒子，气泡底对齐盒底：
            // 气泡底边（尾尖根部）贴按钮卡片顶边
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(boxHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                BubbleContent(title, message, confirmLabel, onConfirm)
            }
        }
    }
}

@Composable
private fun BubbleContent(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .width(260.dp)
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                .background(surface, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // 确认胶囊：红字 + 次级底色（iOS quaternary 填充的等价）
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(Capsule)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onConfirm)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
        // 尾尖：从气泡底边向下探入触发行内，指向该行（iOS ArrowDown 18×9）
        Canvas(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 9.dp)
                .width(18.dp)
                .height(9.dp),
        ) {
            val path = Path().apply {
                moveTo(size.width / 2f, size.height)
                lineTo(0f, 0f)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path, surface)
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

### Task 2: 文案资源对齐

**Files:**
- Modify: `app/src/main/res/values/strings.xml:243-244`
- Modify: `app/src/main/res/values-en/strings.xml:243-244`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml:243-244`

**Interfaces:**
- Produces: 新增 key `planet_delete_confirmMessage`（简中/繁中/英三语，Task 3 引用）；`planet_delete`、`planet_delete_confirmAction` 值改短文案

- [ ] **Step 1: 改三个 values 文件**

`values/strings.xml`（简中，243-244 行改值，confirmTitle 后新增一行）：

```xml
    <string name="planet_delete">删除行星</string>
    <string name="planet_delete_confirmAction">删除行星</string>
    <string name="planet_delete_confirmMessage">删除行星将同步删除此行星下的网络。操作不可恢复！</string>
```

`values-en/strings.xml`：

```xml
    <string name="planet_delete">Delete Planet</string>
    <string name="planet_delete_confirmAction">Delete Planet</string>
    <string name="planet_delete_confirmMessage">Deleting the planet also deletes its networks. This cannot be undone!</string>
```

`values-zh-rTW/strings.xml`：

```xml
    <string name="planet_delete">刪除行星</string>
    <string name="planet_delete_confirmAction">刪除行星</string>
    <string name="planet_delete_confirmMessage">刪除行星將同步刪除此行星下的網路。操作無法復原！</string>
```

注意：`planet_delete_confirmTitle`（删除这颗行星？）三语保持不变；`common_irreversible` 保留（其他弹窗仍在用，不删）。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

### Task 3: 行星设置页接入气泡

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/planet/PlanetSettingsView.kt`（删除行 251-264、删除 AlertDialog 302-324、Scaffold 包裹 118-269、状态变量 105）

**Interfaces:**
- Consumes: Task 1 的 `ConfirmBubble(...)`，Task 2 的 `R.string.planet_delete_confirmMessage`

- [ ] **Step 1: 状态变量替换**

第 105 行 `var showDelete by remember { mutableStateOf(false) }` 替换为：

```kotlin
    var showDeleteBubble by remember { mutableStateOf(false) }
    // 删除行内容顶边（组合根坐标），作为确认气泡锚点
    var deleteAnchorTop by remember { mutableStateOf(0f) }
```

- [ ] **Step 2: Scaffold 外包 Box，Scaffold 后挂气泡**

在 `Scaffold(` 外层包 `Box(Modifier.fillMaxSize()) { ... }`，并在 Scaffold 闭合后、Box 闭合前加：

```kotlin
        ConfirmBubble(
            visible = showDeleteBubble,
            anchorTopPx = deleteAnchorTop,
            title = stringResource(R.string.planet_delete_confirmTitle),
            message = stringResource(R.string.planet_delete_confirmMessage),
            confirmLabel = stringResource(R.string.planet_delete_confirmAction),
            onConfirm = {
                showDeleteBubble = false
                vm.delete(onBack)
            },
            onDismiss = { showDeleteBubble = false },
        )
```

- [ ] **Step 3: 删除行加锚点上报、开气泡**

第 251-255 行删除行 Box 的 modifier 加 `.onGloballyPositioned { deleteAnchorTop = it.positionInRoot().y }`（放在 clickable 之前），clickable 改为 `{ showDeleteBubble = true }`：

```kotlin
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .onGloballyPositioned { deleteAnchorTop = it.positionInRoot().y }
                            .clickable { showDeleteBubble = true },
                        contentAlignment = Alignment.Center,
                    ) {
```

（行内 Text 不变。）

- [ ] **Step 4: 删除旧 AlertDialog**

删除第 302-324 行 `if (showDelete) { AlertDialog(...) }` 整块。`alertText` 的 AlertDialog 保留。

- [ ] **Step 5: 清理 import**

新增：`androidx.compose.ui.layout.onGloballyPositioned`、`cn.imgozi.planetport.ui.components.ConfirmBubble`。若 `AlertDialog` 在本文件已无引用则删 import（`alertText` 弹窗仍在用，保留）。

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

### Task 4: 网络详情页接入气泡

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/detail/NetworkDetailView.kt`（删除行约 318-332、删除 AlertDialog 约 334-360、Scaffold、showDelete 状态）

**Interfaces:**
- Consumes: Task 1 的 `ConfirmBubble(...)`

- [ ] **Step 1: 找到 showDelete 状态声明**

`grep -n "showDelete" app/src/main/java/cn/imgozi/planetport/ui/detail/NetworkDetailView.kt` 定位（约第 100 行附近 `var showDelete by remember { mutableStateOf(false) }`），替换为：

```kotlin
    var showDeleteBubble by remember { mutableStateOf(false) }
    // 删除行内容顶边（组合根坐标），作为确认气泡锚点
    var deleteAnchorTop by remember { mutableStateOf(0f) }
```

- [ ] **Step 2: Scaffold 外包 Box，Scaffold 后挂气泡**

同 Task 3 Step 2 的包裹方式，气泡参数用网络删除文案：

```kotlin
        ConfirmBubble(
            visible = showDeleteBubble,
            anchorTopPx = deleteAnchorTop,
            title = stringResource(R.string.network_delete_confirmTitle),
            message = stringResource(R.string.network_delete_confirmMessage),
            confirmLabel = stringResource(R.string.network_delete),
            onConfirm = {
                showDeleteBubble = false
                onDelete()
            },
            onDismiss = { showDeleteBubble = false },
        )
```

（`onDelete()` 即原 AlertDialog confirm 里的删除回调，变量名以现场为准。）

- [ ] **Step 3: 删除行加锚点上报、开气泡**

删除行 Box（FormSection 内 `Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { showDelete = true } ...)`）改为：

```kotlin
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .onGloballyPositioned { deleteAnchorTop = it.positionInRoot().y }
                    .clickable { showDeleteBubble = true },
                contentAlignment = Alignment.Center,
            ) {
```

（行内 Text 不变。）

- [ ] **Step 4: 删除旧 AlertDialog、清理 import**

删除 `if (showDelete) { AlertDialog(...) }` 整块。删不再使用的 import（`AlertDialog`、`TextButton`——先确认本文件无其他引用再删）；新增 `androidx.compose.ui.layout.onGloballyPositioned`、`cn.imgozi.planetport.ui.components.ConfirmBubble`。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

### Task 5: 行星删除顺序三段式

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/planet/PlanetSettingsViewModel.kt:112-140`

**Interfaces:**
- Consumes: `StoreRepo.store.readNetworks(planetId)` / `writeNetworks(values, planetId)` / `deletePlanet(planet)`（`store/AppConfigStore.kt`），`TunnelController.stop(context)` / `TunnelController.awaitSettled()`（同模块 internal，可直接调）

- [ ] **Step 1: 重写 delete() 与其 KDoc**

把现有 `delete(onDeleted: () -> Unit)`（127-140 行）及其上方 KDoc（112-126 行）整体替换为：

```kotlin
    /**
     * 删除顺序（对齐 iOS 2488ced 三段式）：行星使用中时先写回 lastActivated=false
     * 断开该行星全部网络（writeNetworks → notifyConfigChanged → 隧道 applyConfig
     * diff 逐网 leave），再 stop + awaitSettled 停用行星等停机终态（给隧道留出
     * 处理 leave 的时间窗），最后级联删除配置（deletePlanet：网络/卫星/文件/
     * 状态目录 + 指针回退官方 + notifyConfigChanged）。未使用中直接删除。
     *
     * 门控核对（沿用旧结论）：
     * - 删非激活行星且隧道在跑：不停——核心只在启动时装载激活行星的 planet 文件，
     *   applyConfig 只重读激活行星的网络/月壳（本次删除未触碰），停机反而误伤
     *   在跑连接。
     * - 删激活行星：三段式后不自动重启——隧道停在已停止，回退官方由下次启动
     *   装载（planetDir 在服务启动期固化）。
     * - awaitSettled 已是终态时立即返回（隧道本就未跑的删除路径不挂起）；
     *   超时返回 false 不阻断删除（进程级异常场景，交由用户手动处理）。
     * [onDeleted] 供视图退栈。
     */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val planet = _ui.value.planet ?: return@launch
            if (planet.id == _ui.value.activePlanetId) {
                // 1. 断开所有网络：写回触发隧道 diff 逐网 leave
                val networks = StoreRepo.store.readNetworks(planetId = planet.id)
                if (networks.any { it.lastActivated }) {
                    StoreRepo.store.writeNetworks(
                        networks.map { it.copy(lastActivated = false) },
                        planetId = planet.id,
                    )
                }
                // 2. 停用行星，等停机终态（stop 幂等，终态 no-op）
                TunnelController.stop(getApplication())
                TunnelController.awaitSettled()
            }
            // 3. 级联删除。失败只记日志不崩进程（对齐 HomeViewModel storeWrite
            // 兜底范式）：行星留档，列表下次重读自然还原；退栈照旧，不因低概率
            // 磁盘故障阻断导航
            runCatching { StoreRepo.store.deletePlanet(planet) }
                .onFailure { Log.w(TAG, "deletePlanet 写盘失败", it) }
            onDeleted()
        }
    }
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

### Task 6: 全量验证与提交

- [ ] **Step 1: 全量编译 + lint 冒烟**

Run: `./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin`
Expected: BUILD SUCCESSFUL（两个 variant 都过）

- [ ] **Step 2: 检查完整 diff**

Run: `git --no-pager diff && git --no-pager status`
确认只含 5 个文件（ConfirmBubble.kt 新增、两个 View、VM、三个 strings.xml），无调试代码、无意外变更。

- [ ] **Step 3: 提交（不推送）**

```bash
git add app/src/main/java/cn/imgozi/planetport/ui/components/ConfirmBubble.kt \
  app/src/main/java/cn/imgozi/planetport/ui/planet/PlanetSettingsView.kt \
  app/src/main/java/cn/imgozi/planetport/ui/planet/PlanetSettingsViewModel.kt \
  app/src/main/java/cn/imgozi/planetport/ui/detail/NetworkDetailView.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-en/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "$(cat <<'EOF'
fix(ui): 删除确认改用自绘锚定气泡并重构使用中的删除顺序

对齐 iOS 2488ced：系统 AlertDialog 改为自绘 ConfirmBubble，锚点为删除
行实时上报的全局位置，气泡底边贴按钮卡片顶边（含呼吸间距）、尾尖指向
该行，点气泡外任意处关闭；行星设置页与网络详情页两处删除交互一致。
文案调整：「删除此行星」→「删除行星」，弹窗提示改用专用 key「删除行
星将同步删除此行星下的网络。操作不可恢复！」。使用中删除行星改为三
段式：先写回 lastActivated=false 断开该行星全部网络（触发隧道 diff
逐网 leave），再 stop + awaitSettled 等停机终态，最后级联删除配置并
回退官方行星。
EOF
)"
```

- [ ] **Step 4: 向用户汇报**

汇报编译结果与改动清单，提醒真机验证点：两处删除气泡的锚定/外点关闭/确认路径，及使用中行星删除后网络逐网 leave、回退官方行星的行为。

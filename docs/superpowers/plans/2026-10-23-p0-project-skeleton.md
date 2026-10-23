# P0 工程骨架实施计划（Planet Port Android）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **废止注记（2026-10-23，f0b5cec 后）**：Task 3 Step 1 的「拷贝 JNI 绑定层（含 JNIEnvGuard 补丁）」已被取代——core/jni 现为官方 1.16.2 java/jni 原样 + 自维护 `core/jni/ZT_jniguard.h`（GETENV 守卫，ZT_jniutils.h 唯一 MPL 修改）。勿按原步骤从 v1 拷贝 ZT_jniutils.h。

**Goal:** 从零建立可构建、可装机的 Gradle 工程骨架：vendor 引入 ZeroTier 1.16.2 源码，打通 JNI 的 NDK 构建，真机启动 APK 并验证 `libZeroTierOneJNI.so` 加载成功，配好 CI。

**Architecture:** 双模块（`:app` application + `:core` JNI 绑定 library）。ZeroTier 官方 1.16.2 源码以 vendor 子集入仓（`externals/core/`，MPL-2.0，零修改），树外 CMake（`externals/CMakeLists.txt`）编译为四 ABI 的 `libZeroTierOneJNI.so`；Java SDK 源码经 `srcDirs` 重定向直接编译进 core 模块。本计划只覆盖 P0，P1–P5 各自成计划。

**Tech Stack:** AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.2.21 / JDK 21 / compileSdk 36 / minSdk 23 / targetSdk 33 / NDK 25.2.9519653 / CMake 3.22.1 / Compose BOM 2025.12.01。

**规格文档:** `docs/superpowers/specs/2026-10-23-planet-port-android-design.md`

## Global Constraints

- applicationId `cn.imgozi.a.planetport`；namespace/代码包 `cn.imgozi.planetport`；rootProject.name `PlanetPort`
- 工具链版本锁定（见 Tech Stack，与本节一致，不得偏离）
- ZeroTier 源码：官方仓库 github.com/zerotier/ZeroTierOne @ 1.16.2 (fe29cd88886e4f58547ba7f740b2d73eb49ab222) 的 vendor 子集；MPL 文件零修改；`LICENSE-MPL.txt` 与 `LICENSE.txt` 随源码入仓
- GPL 血统代码零引入；唯一参考源为 iOS 姊妹项目 planet-port-ios（目录已由 planet-port-i 更名）
- 不写测试用例；验证 = 编译 + 真机冒烟
- 真机设备 serial `10CEBT0B4S000B0`（未连接则说明并跳过装机步骤）
- commit 信息：中文 Conventional Commits，必须写 Body，禁止任何署名尾注
- 每任务独立验证后提交；Task 4 / Task 5 末尾跑全量 `assembleDebug`
- 源拷贝统一来自 `/Users/zivyuan/GoziLib/Zerotier/aZerotierFix`（下文以 `$V1` 指代），仅取 MPL 组件与自有构建配置，不取其 app 层代码

---

### Task 1: Gradle 工具链骨架

**Files:**
- Create: `gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`、`gradlew`、`gradlew.bat`（自 $V1 拷贝）
- Create: `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`.gitignore`、`gradle/libs.versions.toml`、`local.properties`（不入库）
- Create: `externals/`（空目录占位，Task 2 填充）

**Interfaces:**
- Produces: version catalog 别名 `libs.plugins.android.application` / `libs.plugins.android.library` / `libs.plugins.kotlin.android` / `libs.plugins.kotlin.compose`；库名 `libs.compose.bom` / `libs.compose.ui` / `libs.compose.foundation` / `libs.compose.material3` / `libs.activity.compose` / `libs.lifecycle.runtime.ktx`（Task 3/4 按此引用）

- [ ] **Step 1: 拷贝 Gradle wrapper（v1 已验证的 8.14.3）**

```bash
V1=/Users/zivyuan/GoziLib/Zerotier/aZerotierFix
mkdir -p gradle/wrapper
cp $V1/gradlew $V1/gradlew.bat gradlew* . 2>/dev/null || cp $V1/gradlew $V1/gradlew.bat .
cp $V1/gradle/wrapper/gradle-wrapper.jar $V1/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
mkdir -p externals
```

- [ ] **Step 2: 写 settings.gradle.kts（模块 include 随后续任务追加）**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PlanetPort"
```

- [ ] **Step 3: 写根 build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 4: 写 gradle/libs.versions.toml**

```toml
[versions]
agp = "8.13.2"
kotlin = "2.2.21"
composeBom = "2025.12.01"
activityCompose = "1.10.1"
lifecycle = "2.9.1"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 5: 写 gradle.properties 与 .gitignore，生成 local.properties**

`gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
org.gradle.caching=true
```

`.gitignore`：

```
*.iml
.gradle/
/local.properties
/.idea
/.DS_Store
/captures
build/
.externalNativeBuild/
.cxx/
```

`local.properties`（SDK 路径沿用本机既有配置，已被 .gitignore 排除）：

```bash
V1=/Users/zivyuan/GoziLib/Zerotier/aZerotierFix
grep sdk.dir $V1/local.properties > local.properties
```

- [ ] **Step 6: 验证 wrapper 可运行**

Run: `./gradlew help --console=plain -q`
Expected: `BUILD SUCCESSFUL`（无模块时 help 正常退出）

- [ ] **Step 7: Commit**

```bash
git add .gitignore build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat settings.gradle.kts
git commit -m "build: 初始化 Gradle 工具链与工程骨架

Gradle 8.14.3 wrapper、version catalog、Kotlin DSL 根配置。
工具链版本沿用已验证组合（AGP 8.13.2 / Kotlin 2.2.21 / JDK 21），
为双模块工程（app/core）与 vendor 的 ZeroTier 构建做准备。"
```

---

### Task 2: vendor 引入 ZeroTier 1.16.2 源码

**Files:**
- Create: `externals/core/node/`、`externals/core/include/`、`externals/core/version.h`、`externals/core/osdep/`、`externals/core/java/src/`、`externals/core/ext/{prometheus-cpp-lite-1.0,x64-salsa2012-asm,arm32-neon-salsa2012-asm,nlohmann}/`、`externals/core/LICENSE-MPL.txt`、`externals/core/LICENSE.txt`
- Create: `externals/core/README.md`（来源与升级说明）
- Create: `externals/CMakeLists.txt`（自 $V1 树外构建脚本原样拷贝）

**Interfaces:**
- Produces: `externals/core/java/src`（Task 3 的 core 模块 srcDirs 指向）；`externals/CMakeLists.txt`（Task 3 的 externalNativeBuild.path 指向）；目录布局与 $V1 子模块一致（CMake 内相对路径 `${CMAKE_CURRENT_SOURCE_DIR}/core` 与 `../core/jni` 直接成立）

- [ ] **Step 1: 拷贝源码子集**

```bash
V1=/Users/zivyuan/GoziLib/Zerotier/aZerotierFix
SRC=$V1/externals/core
DST=externals/core
mkdir -p $DST $DST/java $DST/ext
cp -R $SRC/node $DST/node
cp -R $SRC/include $DST/include
cp $SRC/version.h $DST/version.h
cp -R $SRC/osdep $DST/osdep
cp -R $SRC/java/src $DST/java/src
cp -R $SRC/ext/prometheus-cpp-lite-1.0 $DST/ext/
cp -R $SRC/ext/x64-salsa2012-asm $DST/ext/
cp -R $SRC/ext/arm32-neon-salsa2012-asm $DST/ext/
cp $SRC/LICENSE-MPL.txt $SRC/LICENSE.txt $DST/
find $DST -name ".DS_Store" -delete
```

- [ ] **Step 2: 拷贝树外 CMake 构建脚本**

```bash
cp /Users/zivyuan/GoziLib/Zerotier/aZerotierFix/externals/CMakeLists.txt externals/CMakeLists.txt
```

（脚本为自有构建配置：GLOB 纳入 `node/*.cpp`，编译 `osdep/OSUtils.cpp` + `core/jni/` 三文件，含 16KB 页对齐；其对源码目录的相对路径假设与本工程的 vendor 布局完全一致，零改动沿用。）

- [ ] **Step 3: 写 externals/core/README.md（来源与协议声明）**

```markdown
# Vendored ZeroTier Core

ZeroTier 1.16.2 官方源码的 vendored 子集，来源：

- 仓库：https://github.com/zerotier/ZeroTierOne
- 版本：1.16.2（commit fe29cd88886e4f58547ba7f740b2d73eb49ab222）

携带目录（Android JNI 构建所需的最小集合）：`node/`、`include/`、
`version.h`、`osdep/`、`java/src/`、`ext/prometheus-cpp-lite-1.0/`、
`ext/x64-salsa2012-asm/`、`ext/arm32-neon-salsa2012-asm/`。

协议：MPL-2.0（见 `LICENSE-MPL.txt` 与 `LICENSE.txt`）。本目录内文件
保持零修改；升级方式 = 从上游 tag 重新拷贝上述目录 + 全量构建验证。

`../core/jni/`（本仓库自有维护位置）为官方 `java/jni` 绑定加
JNIEnvGuard 补丁，随 MPL-2.0 保留署名。
```

- [ ] **Step 4: 核对拷贝完整性（与来源逐目录 diff）**

```bash
V1=/Users/zivyuan/GoziLib/Zerotier/aZerotierFix
SRC=$V1/externals/core
diff -rq $SRC/node externals/core/node
diff -rq $SRC/include externals/core/include
diff -rq $SRC/osdep externals/core/osdep
diff -rq $SRC/java/src externals/core/java/src
diff -rq $SRC/ext/prometheus-cpp-lite-1.0 externals/core/ext/prometheus-cpp-lite-1.0
diff -rq $SRC/ext/x64-salsa2012-asm externals/core/ext/x64-salsa2012-asm
diff -rq $SRC/ext/arm32-neon-salsa2012-asm externals/core/ext/arm32-neon-salsa2012-asm
diff -q $SRC/version.h externals/core/version.h
```

Expected: 全部无输出（完全一致）。

- [ ] **Step 5: Commit**

```bash
git add externals/
git commit -m "build: vendor 引入 ZeroTier 1.16.2 核心源码

自官方仓库 1.16.2 (fe29cd8) 拷贝 Android JNI 构建最小子集
（node/include/osdep/java-src/三个 ext 头库/version.h），
MPL-2.0 零修改，随仓携带双 LICENSE 与来源声明 README；
树外 CMake 构建脚本一并引入（16KB 页对齐）。"
```

---

### Task 3: core 模块（JNI 绑定 + NDK 构建打通）

**Files:**
- Create: `core/build.gradle`（Groovy）
- Create: `core/src/main/AndroidManifest.xml`
- Create: `core/jni/com_zerotierone_sdk_Node.cpp`、`core/jni/com_zerotierone_sdk_Node.h`、`core/jni/ZT_jniutils.cpp`、`core/jni/ZT_jniutils.h`、`core/jni/ZT_jnicache.cpp`、`core/jni/ZT_jnicache.h`（自 $V1 拷贝，MPL-2.0）
- Modify: `settings.gradle.kts`（追加 `include(":core")`）

**Interfaces:**
- Consumes: `externals/CMakeLists.txt`、`externals/core/java/src`（Task 2）
- Produces: AAR `core-debug.aar`，含四 ABI `jni/<abi>/libZeroTierOneJNI.so`；Java 包 `com.zerotier.sdk`（Node 等 SDK 类）供 Task 4 / P2+ 使用

- [ ] **Step 1: 拷贝 JNI 绑定层（MPL-2.0，含 JNIEnvGuard 补丁，零改动）**

```bash
V1=/Users/zivyuan/GoziLib/Zerotier/aZerotierFix
mkdir -p core/jni core/src/main
cp $V1/core/jni/com_zerotierone_sdk_Node.cpp \
   $V1/core/jni/com_zerotierone_sdk_Node.h \
   $V1/core/jni/ZT_jniutils.cpp \
   $V1/core/jni/ZT_jniutils.h \
   $V1/core/jni/ZT_jnicache.cpp \
   $V1/core/jni/ZT_jnicache.h core/jni/
```

- [ ] **Step 2: 写 core/src/main/AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest>
</manifest>
```

- [ ] **Step 3: 写 core/build.gradle**

```groovy
// ZeroTier JNI 绑定模块（MPL-2.0 组件的构建壳，自有文件）。
// Java SDK 源码 vendor 于 ../externals/core/java/src，经 srcDirs 重定向编译；
// native 构建走树外 CMake（../externals/CMakeLists.txt）。
// NDK 钉 25.2：NDK 26+ 移除 minSdk 19 支持的旧工具链行为，此组合已验证。
plugins {
    id 'com.android.library'
}

android {
    namespace 'com.zerotier.sdk'
    compileSdk 36
    ndkVersion "25.2.9519653"

    defaultConfig {
        minSdk 23
    }

    buildTypes {
        release {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    externalNativeBuild {
        cmake {
            path file('../externals/CMakeLists.txt')
            version '3.22.1'
        }
    }

    sourceSets {
        main.java.srcDirs = ['../externals/core/java/src']
    }
}

dependencies {
}
```

- [ ] **Step 4: settings.gradle.kts 追加 include**

在 `rootProject.name = "PlanetPort"` 之后追加一行：

```kotlin
include(":core")
```

- [ ] **Step 5: 构建 core 模块（首次 NDK 编译，数分钟）**

Run: `./gradlew :core:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 核对 AAR 内四 ABI 的 .so**

```bash
unzip -l core/build/outputs/aar/core-debug.aar | grep libZeroTierOneJNI
```

Expected: 输出 4 行 —— `jni/arm64-v8a/`、`jni/armeabi-v7a/`、`jni/x86/`、`jni/x86_64/` 下的 `libZeroTierOneJNI.so`。

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts core/
git commit -m "build: core 模块 JNI 绑定与 NDK 构建打通

com.zerotier.sdk library：srcDirs 重定向编译 vendored Java SDK，
树外 CMake 产出四 ABI libZeroTierOneJNI.so（16KB 页对齐）；
JNI 绑定层为官方 java/jni + JNIEnvGuard 补丁，MPL-2.0 零改动。"
```

---

### Task 4: app 模块骨架 + JNI 库加载验证

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/cn/imgozi/planetport/ui/MainActivity.kt`
- Modify: `settings.gradle.kts`（追加 `include(":app")`）

**Interfaces:**
- Consumes: `project(":core")`（Task 3 的 AAR）；Task 1 catalog 全部别名
- Produces: 可装机 APK（`cn.imgozi.a.planetport`），P0 完成标准 = 启动后显示 `.so` 加载成功

- [ ] **Step 1: 写 app/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.imgozi.planetport"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.imgozi.a.planetport"
        minSdk = 23
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // release 暂用 debug 签名便于直接装机验证
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
}
```

- [ ] **Step 2: 写 app/src/main/AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

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
    </application>

</manifest>
```

（VPN service 声明、权限、图标随 P2/P4 引入；P0 用系统默认图标。）

- [ ] **Step 3: 写 res/values/strings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Planet Port</string>
</resources>
```

- [ ] **Step 4: 写 MainActivity.kt（JNI 加载验证页）**

```kotlin
package cn.imgozi.planetport.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * P0 骨架验证页：确认 libZeroTierOneJNI.so 随 APK 分发且可加载。
 * P4 起替换为正式首页。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jniLoaded = try {
            System.loadLibrary("ZeroTierOneJNI")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = if (jniLoaded) "libZeroTierOneJNI 加载成功" else "JNI 库加载失败")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: settings.gradle.kts 追加 include**

在 `include(":core")` 之后追加：

```kotlin
include(":app")
```

- [ ] **Step 6: 全量构建**

Run: `./gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 核对 APK 内 .so**

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libZeroTierOneJNI
```

Expected: `lib/arm64-v8a/`、`lib/armeabi-v7a/`、`lib/x86/`、`lib/x86_64/` 下各一行 `libZeroTierOneJNI.so`。

- [ ] **Step 8: 真机装机启动（设备未连接则记录并跳过）**

```bash
adb devices | grep 10CEBT0B4S000B0 || echo "设备未连接，跳过装机"
adb -s 10CEBT0B4S000B0 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10CEBT0B4S000B0 shell am start -n cn.imgozi.a.planetport/cn.imgozi.planetport.ui.MainActivity
```

Expected: 安装成功、应用启动，屏幕中央显示「libZeroTierOneJNI 加载成功」。

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts app/
git commit -m "feat: app 模块骨架与 JNI 库加载验证

applicationId cn.imgozi.a.planetport，Compose 最小界面，
启动即加载 libZeroTierOneJNI 并展示结果——P0 完成标准
（APK 启动、四 ABI .so 打包与加载）就位。"
```

---

### Task 5: CI 构建流水线

**Files:**
- Create: `.github/workflows/build.yml`

**Interfaces:**
- Consumes: 本仓库既有 `./gradlew assembleDebug`（Task 4 验证过的命令）

- [ ] **Step 1: 写 .github/workflows/build.yml**

```yaml
name: Build

on:
  push:
    branches: [master]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Accept Android SDK licenses
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true

      - name: Build
        run: ./gradlew assembleDebug --no-daemon --console=plain

      - uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
```

（AGP 在 license 接受后自动安装 platform 36 / NDK 25.2.9519653 / CMake 3.22.1；ZeroTier 源码已 vendor 入仓，无 submodule 步骤。）

- [ ] **Step 2: 本地按 CI 同款命令干跑**

Run: `./gradlew assembleDebug --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add .github/
git commit -m "ci: GitHub Actions 构建流水线

JDK 21 + license 接受 + gradlew assembleDebug，上传 debug APK
产物；无 submodule 步骤（ZeroTier 源码 vendor 入仓）。"
```

---

## Self-Review 结论

- **规格覆盖**：P0 三项交付全部落任务——工程骨架（Task 1/3/4）、vendor 入仓（Task 2）、完成标准「APK 启动 + .so 加载」（Task 4 Step 6–8）；CI（Task 5）对应规格 §8。
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码；所有命令含预期输出。
- **类型一致性**：catalog 别名在 Task 1 定义、Task 4 按同名引用；`include` 逐任务追加与模块创建顺序一致；CMake 相对路径假设（`externals/core`、`core/jni`）与 Task 2/3 产出的目录布局核对无误。
- **风险点**：首次 NDK 编译较慢（Task 3 Step 5，数分钟属正常）；targetSdk 33 下 app 仅默认图标（P4 引入正式图标）；`local.properties` 依赖本机已有 SDK 路径（v1 同机可构建，成立）。

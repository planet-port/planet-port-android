import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.imgozi.planetport"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.imgozi.a.planetport"
        minSdk = 23
        targetSdk = 33
        versionCode = 8
        versionName = "1.6.0"
    }

    signingConfigs {
        // CI 发布签名（GitHub Secrets → 环境变量注入）：debug keystore 每台
        // 机器随机生成，CI 一次性 runner 上每次构建签名都不同，覆盖安装必然
        // 冲突；专用 keystore 保证各版本签名恒定
        if (System.getenv("PP_KEYSTORE_FILE") != null) {
            create("ciRelease") {
                storeFile = file(System.getenv("PP_KEYSTORE_FILE"))
                storePassword = System.getenv("PP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PP_KEY_ALIAS")
                keyPassword = System.getenv("PP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // CI 注入专用发布签名；未注入（本地构建）回落 debug 签名便于直接装机验证
            signingConfig = signingConfigs.findByName("ciRelease")
                ?: signingConfigs.getByName("debug")
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
    // 版本随 compose BOM（扩展图标库：ContentCopy/WifiOff 等 core 集不含的符号）
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    // 语言切换（AppCompatDelegate.setApplicationLocales）需要 AppCompatActivity 承载
    implementation(libs.androidx.appcompat)
    // 扫码加入网络（预览 + 逐帧 zxing 解码）
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

// 构建信息（iOS project.yml preBuildScripts「生成构建信息」等价）：
// 每次构建生成构建时间 + git 短 hash + dirty 标记，关于页展示（默认显时间、
// 点击切换显 hash），用于确认手机上安装的包对应哪次构建。生成文件不入库
//（.gitignore）。
val generateBuildInfo by tasks.registering {
    outputs.upToDateWhen { false }
    val genDir = file("src/main/java/cn/imgozi/planetport/Generated")
    doLast {
        genDir.mkdirs()
        fun git(vararg args: String): String =
            runCatching {
                val p = ProcessBuilder("git", *args).directory(rootDir).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                if (p.exitValue() == 0) out else ""
            }.getOrDefault("")
        // 短 hash 8 位（iOS rev-parse --short=8 同构）；工作区有未提交改动标 dirty
        val hash = git("rev-parse", "--short=8", "HEAD").ifEmpty { "unknown" }
        val dirty = git("status", "--porcelain").isNotEmpty()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
        File(genDir, "BuildInfo.kt").writeText(
            "// 构建脚本自动生成，勿手改（app/build.gradle.kts generateBuildInfo）。\n" +
                "package cn.imgozi.planetport\n" +
                "\n" +
                "object BuildInfo {\n" +
                "    const val time = \"$now\"\n" +
                "    const val hash = \"$hash\"\n" +
                "    const val dirty = $dirty\n" +
                "}\n",
        )
    }
}

tasks.named("preBuild") { dependsOn(generateBuildInfo) }

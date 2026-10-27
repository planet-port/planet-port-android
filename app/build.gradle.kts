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
        versionCode = 3
        versionName = "1.2.0"
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
// 每次构建生成构建时间 + git describe，关于页展示，用于确认手机上安装的
// 包对应哪次构建。生成文件不入库（.gitignore）。
val generateBuildInfo by tasks.registering {
    outputs.upToDateWhen { false }
    val genDir = file("src/main/java/cn/imgozi/planetport/Generated")
    doLast {
        genDir.mkdirs()
        // tag 优先（git describe），无 tag 回退短 hash；dirty 标记未提交改动。
        // git 非零退出不抛异常，必须按退出码判定，否则错误文本会被当成 revision
        val rev = runCatching {
            val p = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty=+dirty")
                .directory(rootDir)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (p.exitValue() == 0 && out.isNotEmpty()) out else "unknown"
        }.getOrNull() ?: "unknown"
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        File(genDir, "BuildInfo.kt").writeText(
            "// 构建脚本自动生成，勿手改（app/build.gradle.kts generateBuildInfo）。\n" +
                "package cn.imgozi.planetport\n" +
                "\n" +
                "object BuildInfo {\n" +
                "    const val time = \"$now\"\n" +
                "    const val revision = \"$rev\"\n" +
                "}\n",
        )
    }
}

tasks.named("preBuild") { dependsOn(generateBuildInfo) }

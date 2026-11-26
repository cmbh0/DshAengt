import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持，不需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release 签名。
 *
 * 口令放在项目根的 `keystore.properties`（**不在版本库、不要外传**）。
 * 文件不存在时回落到 debug 签名 —— 保证 clone 下来的人也能 `assembleRelease`
 * 跑通流程，而不是卡在一个"缺密钥"的报错上。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

// 发行构建（CI）设 DSHA_REQUIRE_RELEASE_SIGNING=1：缺签名配置就直接失败，
// 绝不回落到 debug 签名 —— 免得「构建成功」的产物其实不是发行证书签的。
val requireReleaseSigning = System.getenv("DSHA_REQUIRE_RELEASE_SIGNING") == "1"
if (requireReleaseSigning && !hasReleaseSigning) {
    throw GradleException(
        "DSHA_REQUIRE_RELEASE_SIGNING=1 但根目录没有 keystore.properties，" +
            "拒绝回落到 debug 签名。",
    )
}

android {
    namespace = "com.simplechat.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.simplechat.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 84
        versionName = "2.5.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    /**
     * 本机运行环境以 `jniLibs/<abi>/lib*.so` 的形式随 APK 分发：
     *
     * - `libbusybox.so`：静态链接的 BusyBox（shell 加两百来个命令）；
     * - `libmusl.so`：musl 的动态加载器兼 libc，用来启动按需下载的 CPython；
     * - `libdshaexec.so`：`python3` / `pip` 的入口。
     *
     * 它们是可执行文件，不是动态库，只是借这个名字落到 `nativeLibraryDir`。
     * 这是 Android 10 起 W^X 限制下的唯一可行路径：应用**不能**执行自己私有目录
     * 里的文件（`execve` 直接 EACCES），但 `nativeLibraryDir` 里的东西是可以的。
     * 代价是必须 `extractNativeLibs=true` 且对 jniLibs 用 legacy 打包 ——
     * 默认的"不落地、直接从 APK 映射"模式下，这些文件根本不会出现在磁盘上。
     *
     * 源码版本、补丁与构建脚本都在 `tools/musl/`，`tools/musl/SHA256SUMS` 记着
     * 仓库里这几份产物的校验值。
     *
     * `keepDebugSymbols` 是给打包工具看的：它们已经 strip 过，而且是 musl 的静态
     * 可执行文件，让 AGP 再拿 NDK 的 strip 过一遍没有收益，只有弄坏的风险。
     */
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 「关于」页直接读 BuildConfig.VERSION_NAME，避免手写版本号跟 build.gradle 漂移
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
        jniLibs {
            // 见上面那段说明：这几个文件必须落盘到 nativeLibraryDir 才可执行
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libbusybox.so",
                "**/libmusl.so",
                "**/libdshaexec.so",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.highlights)
    implementation(libs.latex.renderer)
    implementation(libs.latex.renderer.fonts)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.jsch)
    // 解开 Python 发行包（tar.gz）。自己写 tar 解析要处理 GNU/PAX 长名、符号链接、
    // 稀疏文件，出错就是"装到一半的解释器"，不值得省这 600 KB。
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}





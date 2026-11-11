import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.nymph.auto"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "dev.nymph.auto"
        minSdk = 26          // Android 8.0
        targetSdk = 36       // Android 16（预留 17 升级位）
        versionCode = 27
        versionName = "2.9.9"
        // 只构建/打包 arm64-v8a 原生库（QuickJS so ~1MB），与其他 ABI 无关
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // QuickJS（quickjs-ng，MIT）JNI 桥：构建时从 cpp/ 源码现场编译，
    // 不投放任何预编译二进制——与"不允许加密、不允许混淆"一致：
    // 产物与仓库源码一一对应，任何人可逐行审计。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // 16 KB 对齐靠 CMakeLists.txt 的 linker flags
            // (-Wl,-z,max-page-size=16384 / common-page-size)。这里**不设**
            // -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON：该变量只影响 NDK 自带
            // libc++_shared.so 的选择，本工程是纯 C（仅链 m / log），不链接
            // C++ 运行库，设了也不起作用。对齐结果由 tools/check_elf_alignment.py
            // 实测 ELF LOAD 段验证，不靠构建参数自我声明。
        }
    }

    // 纯 arm64-v8a：只构建/打包一个 ABI 的原生库（QuickJS so ~1MB）。
    // 不用 splits.abi——AGP 禁止 abiFilters 与 splits 同时设置；
    // 单 ABI 的 universal 包与 arm64 分包内容完全一致。
    splits {
        abi {
            isEnable = false
        }
    }

    buildTypes {
        release {
            // 硬性要求：不允许混淆、不允许加密。
            // R8 代码混淆/优化与资源压缩全部关闭——release 产物与源码结构
            // 一一对应（类名/方法名/资源名均保持可读），脚本为明文 JSON（.zjs），
            // 无壳、无加密、无可疑打包，任何人都能审计 APK 里到底有什么。
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }

    // 不压缩 so（AGP 8.5.1+ 配合 zipalign -P 16 做 ZIP 条目 16KB 对齐）。
    // 注意：v2.8.1 及以前把这里注释成「16KB 页大小对齐」是**不准确的**——
    // 这一项只保证 APK 内 so 未压缩/条目对齐，与 ELF 段的 LOAD Align 无关。
    // 真正的 16 KB 页兼容需要 linker 段对齐（见 externalNativeBuild 与
    // cpp/CMakeLists.txt 的 max-page-size），并由 tools/check_elf_alignment.py
    // 每次发版实测确认。预编译第三方 so 不受本工程 CMake 控制，如实单列。
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":core"))

    // 版本于 2026-11-07 核对各仓库 maven-metadata.xml 与 AAR metadata 后固定，
    // 与 UPSTREAM_LICENSES.md 清单一致（固定版本 = 可复现构建）。
    // v2.1 工具链：AGP 8.13.2 + Kotlin 2.4.21 + compileSdk 36。
    //  - androidx.core 1.17.0（1.19 要求 compileSdk 37 + AGP 9.1，超出本环境）
    //  - coroutines 1.11.0 / serialization 1.12.0（随 Kotlin 2.4 解封）
    //  - appcompat 1.8.0 / material 1.14.0 仍为最新 release
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")

    // ---- UI：Jetpack Compose + Material 3 ----
    // Compose 1.11.4 / Material3 1.4.0（BOM 2026.06.01，compileSdk 35+ / AGP 8.6+）。
    // 更新的 BOM（2026.08+）要求 compileSdk 37，本环境 SDK 平台只到 36，封顶于此。
    // 不使用任何预览/实验 API。
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // QuickJS 脚本引擎：vendored quickjs-ng（MIT）+ JNI 桥，源码在
    // app/src/main/cpp/，由 externalNativeBuild 现场编译（见上）。
    // 不引入第三方绑定库——少一个二进制黑盒，审计面只剩本仓库源码。

    // ---- 离线 OCR：ONNX Runtime Android 1.31.1（2026-10 release，MIT）----
    // PP-OCRv5 ONNX 模型可插拔（filesDir/ocr/ 或 assets/ocr/），推理纯本地 CPU、
    // 不联网、不依赖 GMS。ORT AAR 声明的 ACCESS_NETWORK_STATE 已被本工程
    // Manifest 的 tools:node="remove" 移除（aapt2 实测），符合零网络权限约束。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    // ML Kit 中文识别：GMS 已缓存模型时的兼容兜底（2024 后停更于 16.0.1）。
    // 入口 TextRecognition.getClient(ChineseTextRecognizerOptions)：拉丁件提供
    // 入口类、中文件切换模型。无 GMS 时自动回退节点文本，不阻塞其他能力。
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // AI 找目标（TFLite Task Vision 目标检测）：efficientdet-lite0 int8
    // （assets/models/，4.6MB，COCO 80 类，明文模型文件不加密）。
    // 推理全程本地 CPU；detect 前先在 VisionPipeline 里判 available。
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")

    testImplementation("junit:junit:4.13.2")
}

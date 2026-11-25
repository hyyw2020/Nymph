# Vendored 原生依赖清单（离线视觉栈）

本目录与 `app/src/main/jniLibs/arm64-v8a/`、`app/src/main/cpp/vendor/`、`app/src/main/java/org/opencv/`
构成**离线视觉栈的 vendored 依赖**。全部随 APK 走、构建期不联网解析运行时、运行期零网络调用。

原则：**固定版本 + SHA256 回源校验 + 许可证随附**，任何人可逐项复核产物来源。

## 1. 二进制（jniLibs/arm64-v8a）

| 文件 | 组件 | 版本 | 来源 | SHA256 |
|---|---|---|---|---|
| `libncnn.so` | ncnn（Vulkan + CPU） | `20261020` | `Tencent/ncnn` release `ncnn-20261020-android-vulkan-shared.zip` | `6361033c58466c8db3211126cbe2a4164783745f2aff231e359b4a747bef203a` |
| `libopencv_java5.so` | OpenCV Android | `5.1.0` | `opencv/opencv` release `opencv-5.1.0-android-sdk.zip` | `90b57a97640565f77cff39d1683740f2bf09c2513a27f51777ee7a01cb97796e` |
| `libc++_shared.so` | LLVM libc++ | NDK `27.0.12077973` | Android NDK `sysroot/usr/lib/aarch64-linux-android/` | `46b51d661454b9cfaf42c1dc90893b5ad601b7a7ffc2e09d44bb3cc9d20e7ae2` |

来源 zip 的整包 SHA256（下载完整性校验）：

- `ncnn-20261020-android-vulkan-shared.zip` → `1b01048590564bae1c3d2d2a2b37ff101d50ad86c2cd2dc862bce7087905444d`
- `opencv-5.1.0-android-sdk.zip` → `24db86c14db3246bff8a8adc2ed97d59c8227a11c7a32bed189f2c2c04e552a4`（与 GitHub release assets 公示摘要一致）

### 页对齐（16 KB）

三个 so 的 ELF `LOAD` 段 `Align` 均为 `0x4000`（16 KB），满足 Android 15+ / Google Play 对 `targetSdk 35+` 的硬性要求。
使用 `llvm-readelf -l <so> | grep LOAD` 复核，发版时 `tools/check_elf_alignment.py` 对整包再核验一次。

### 为何用 shared 而非 static

- ncnn 官方 `-vulkan` 包是静态库（`.a` + 整套 glslang/SPIRV 静态库），链接链长且易与 NDK 版本耦合；
  `-vulkan-shared` 的 `libncnn.so` 只依赖系统库（dl/android/jnigraphics/log/m/c），不动 libc++_shared，
  接入面最小、可复现性最好。
- `libncnn.so` 未把 Vulkan 列为 `DT_NEEDED`：Vulkan 加载器由设备提供，运行时按能力探测启用，
  无 Vulkan 的设备自动走 CPU 路径（见降级链）。

## 2. 头文件（app/src/main/cpp/vendor/ncnn/include/ncnn）

ncnn `20261020` 的 `arm64-v8a/include/ncnn/*.h`（29 个），来自同一 zip。JNI 桥 `ncnn_jni.cpp` 直接 include。

## 3. Java 源码（app/src/main/java/org/opencv）

OpenCV `5.1.0` 的 `sdk/java/src/org/opencv/*.java`，共 172 个类（core 35 / imgproc 9 / dnn 16 / utils 1 / android/Utils 1 /
其余为 features2d、calib3d、objdetect、video、photo、ptcloud 等纯 API 类）。

**裁剪说明**：删除了上游 `org/opencv/android/` 中的相机预览子系统（CameraBridgeViewBase / JavaCameraView /
CameraGLSurfaceView 等 12 个类）——它们依赖 `org.opencv.R` 与 `CameraBridgeViewBase`，本项目取帧走无障碍截图 /
MediaProjection，不使用相机预览，故不引入 Android 资源。仅保留 `android/Utils.java`（bitmapToMat，无 R 依赖）。
so 加载由本工程 `NcnnNative.loadLibraries()` 按序调用 `System.loadLibrary` 完成，不依赖 `OpenCVLoader.initAsync()`。

## 4. 许可证

| 组件 | 许可证 | 文件 |
|---|---|---|
| ncnn | BSD-3-Clause | `third_party/ncnn/LICENSE` |
| OpenCV 5.1.0 | Apache-2.0 | `third_party/opencv/LICENSE` |
| LLVM libc++ | Apache-2.0 WITH LLVM-exception | NDK 自带，随 so 分发；见 `third_party/ncnn/LICENSE` 同目录说明 |
| NymphAuto 本体 / JNI 桥 | MIT | 仓库根 `LICENSE` |

## 5. 更新流程

1. 从官方 release 下载 zip，先用上表 SHA256 校验整包。
2. 只提取 `arm64-v8a` 的 so / 头文件、`sdk/java/src/org/opencv` 的 Java 类。
3. 用 `llvm-readelf -l` 复核每个 so 的 LOAD 段 `Align >= 0x4000`。
4. 更新本清单的版本、来源、SHA256。
5. `--rerun-tasks` 全量构建 + `tools/verify_release.py --mode=full` 通过后才可发布。

国内网络可用 `gh.monlor.com` 等 GitHub 加速镜像，但**必须回源核对官方 release assets 的 SHA256**，
镜像内容不被信任。

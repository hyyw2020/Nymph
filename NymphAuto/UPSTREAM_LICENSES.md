# NymphAuto 上游组件与许可声明

> 本文体例参考了一份 fcitx5-android 系输入法项目（MBM）的上游组件清单，
> **仅借鉴其「列全组件、固定版本、保留许可、写明合规红线」的组织方式**。
> 两者是不同的工程：MBM 是以 AGPL-3.0 发布的输入法，NymphAuto 是以
> **MIT License** 发布的离线自动点击/脚本回放工具，**组件清单无重叠**，
> 下表所列均为 NymphAuto 实际引入或分发的组件，未照搬对方任何条目。

NymphAuto 对上游代码以 **vendored 源码 / Maven 固定版本依赖** 两种方式引入，
并完整保留各自的 License 与版权声明。本文件列出全部组件、引入方式、固定版本
与许可条款，供审计与再分发使用。

## 1. 主仓库

| 项目 | 地址 | 许可 |
|---|---|---|
| NymphAuto | https://gitee.com/nlxxai/Nymph | MIT License（见 `LICENSE`） |

发布物必须包含：`LICENSE`（MIT 全文）、`NOTICE` 与 **本文件**。

## 2. vendored 源码（固定快照，随仓库保留 LICENSE）

| 路径 | 上游 | 版本/快照 | 许可 |
|---|---|---|---|
| `app/src/main/cpp/quickjs/` | [quickjs-ng/quickjs](https://github.com/quickjs-ng/quickjs) | master @ 2026-10 | MIT |

- 引入方式：**vendored 源码**，构建时由 `externalNativeBuild`/CMake 现场编译，
  不投放任何预编译二进制。完整版权与许可全文见 `app/src/main/cpp/quickjs/LICENSE`。
- 上游 `quickjs.c` 按其官方 `amalgam.js` 规则合成为单文件（未做功能改动），
  重新生成方式记录在同目录 `README.md`。
- JNI 桥 `app/src/main/cpp/quickjs_jni.c` 为本工程新增代码（MIT），
  文件头保留 SPDX 声明与改动说明。

## 3. Maven 依赖（2026-11-05 核对固定版本）

版本于 2026-11-05 逐一核对各仓库 `maven-metadata.xml` 的 `<release>`，
并交叉验证 AAR `aar-metadata.properties` 的工具链要求后固定，
以保证**可复现构建**。

| 组件 | 固定版本 | 用途 | 许可 |
|---|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android` | **1.31.1** | 离线 OCR 推理引擎 | MIT |
| `com.google.mlkit:text-recognition` | 16.0.1 | OCR 兼容兜底入口 | Google ML Kit 条款 |
| `com.google.mlkit:text-recognition-chinese` | 16.0.1 | OCR 中文模型切换 | Google ML Kit 条款 |
| `org.tensorflow:tensorflow-lite-task-vision` | 0.4.4 | 端侧目标检测 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | 协程 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | 明文脚本编解码 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.16.0 | AndroidX 基础 | Apache-2.0 |
| `androidx.appcompat:appcompat` | 1.8.0 | 兼容 Activity | Apache-2.0 |
| `com.google.android.material:material` | 1.14.0 | UI 组件 | Apache-2.0 |
| `junit:junit`（仅测试期，不随 APK 分发） | 4.13.2 | 单元测试 | EPL-1.0 |

### 3.1 版本选择说明（工具链上限，如实记录）

「使用最新版本」受构建工具链约束，以下为**当前工具链支持的最新 release**，
未盲目取最高版本号：

- 工具链：AGP 8.7.2、Kotlin 2.0.21、compileSdk 36、NDK 27.0.12077973。
- `androidx.core:core-ktx` 取 **1.16.0**（minCompileSdk 35 / AGP 8.6.0）。
  1.17.0+ 要求 AGP 8.9.1，1.19.1 更要求 compileSdk 37 与 AGP 9.1.0 —— 均不可用。
- `kotlinx-coroutines` 取 **1.9.0**。1.11.0 依赖 Kotlin stdlib 2.2.20，
  与 Kotlin 2.0.21 的 metadata 不兼容，强行升级会破坏编译。
- `appcompat 1.8.0`、`material 1.14.0` 已核对 AAR metadata 与当前工具链兼容。

### 3.2 最新 release 开源技术的落地点

本次功能强化真正引入的 2026-11 最新 release 技术：

- **ONNX Runtime Android 1.31.1**（Microsoft，MIT，2026-10 构建）：
  作为**完全离线**的端侧推理引擎，用于录制锚点的 OCR 文字增强。
- 已评估并明确记录其版本坐标、但**未迁移**的上游：
  **LiteRT 2.3.2**（Google，2026-10-07 release，Apache-2.0）。
  未迁移原因：`ObjectDetector` 属 TFLite Task Vision 高层 API，
  LiteRT 2.x 的 `CompiledModel` 无对应封装，迁移需重写检测后处理与 NMS；
  现有 `tensorflow-lite-task-vision:0.4.4` 仍可用且模型 `.tflite` 格式兼容，
  故保留并在本表如实固定版本，避免无收益的破坏性升级。

## 4. 模型与二进制资产

| 资产 | 来源 | 版本/规格 | 许可 | 引入方式 |
|---|---|---|---|---|
| `assets/models/efficientdet-lite0.tflite` | Google MediaPipe Object Detector | int8 / 1 / COCO 80 类，4.6MB | Apache-2.0（模型） | 随 APK 打包，明文不加密 |

- **PP-OCRv5 / RapidOCR ONNX 模型（Apache-2.0）**：**不内置**。
  采用**可插拔**口径，用户把 `det.onnx` / `rec.onnx` / `keys.txt` 放到
  `filesDir/ocr/`（或自行随包到 `assets/ocr/`）即自动启用离线 OCR；
  缺失时 `available=false` 优雅降级到 ML Kit / 无障碍节点文本。
  理由：中文 rec 模型 7.5–81MB 会使 APK 显著膨胀；显式携带模型与许可
  更利于审计；与既有 RF `.dot` 机制口径一致（机制对等、模型自备）。
  ONNX Runtime **引擎本身随 APK 分发**，许可与版权见 `NOTICE`。

- 所有模型均为**明文**文件（`.tflite` / `.onnx`），不加密、不混淆。

## 5. 许可条款摘录

- **MIT**：NymphAuto 本体、quickjs-ng、ONNX Runtime。允许商用、修改、再分发，
  仅需保留版权与许可声明。
- **Apache-2.0**：AndroidX、Material、Kotlin/kotlinx、TFLite Task Vision、
  efficientdet-lite0 模型、PP-OCRv5 模型。含专利授权；再分发需保留
  NOTICE 与许可全文，修改文件需注明变更。
- **EPL-1.0**：JUnit 4，仅测试期使用，不进入发行 APK。
- **Google ML Kit 条款**：ML Kit SDK 依其服务条款使用；本工程不调用其云端 API，
  不上传任何图像数据。

上述许可均可与 MIT 主工程组合分发；各组件按各自条款独立保留，
不因本工程的 MIT 许可而被重新授权。

## 6. 仅作功能/格式对照，未包含代码

- **自动精灵（com.zdanjian.zdanjian）**：本工程兼容其公开的 `.zjs`
  行分隔 JSON 脚本格式，属**公开数据格式解析**。
  **不包含**其任何代码、资源、模型或资产，**不反编译**其 APK。
  「自动精灵」商标归其权利人所有，本工程与之无关联。
- **Rime / fcitx5 / MBM 等输入法项目**：仅借鉴离线能力与合规文档的组织方式，
  不包含其代码。
- **HeliBoard / AOSP Keyboard / FUTO Keyboard / FlorisBoard 等**：
  仅参考「无网络权限、本地明文存储」的隐私做法，不包含其代码。

## 7. 合规红线

1. **不反编译、不并入任何闭源 APK 的代码、资源与资产**。
2. **不允许加密、不允许混淆**：release 构建关闭 R8
   （`isMinifyEnabled=false` / `isShrinkResources=false`），
   类名、方法名、资源名与源码一一对应，可逐行审计；
   脚本与录制事件流均为明文 JSON（`.zjs` / `.events.json`）。
3. **不和系统录屏抢权限**：全工程零 MediaProjection，
   截图仅用 `AccessibilityService.takeScreenshot()`（API 30+）。
4. **录制不打断其他 APP**：只订阅被动语义事件，不消费/拦截触摸，
   不启用 `flagRequestTouchExplorationMode`（该模式会把单击重解释为双击，
   改变其他应用的交互语义）；无障碍回调立即返回，耗时操作全在后台协程。
5. 任何上游代码的改动，须在对应文件头保留原 SPDX 声明并追加本工程修改说明。
6. 发布物必须包含 `LICENSE`、`NOTICE` 与本文件。

## 8. 权限实测（发行版 APK）

| 核验项 | 方法 | 结果 |
|---|---|---|
| INTERNET / ACCESS_NETWORK_STATE | `aapt2 dump permissions` | 0（Manifest `tools:node="remove"`） |
| MediaProjection 引用 | `dexdump` 全 dex 扫描 | 0 |
| 代码混淆 | `dexdump` 类名检查 | 全部保留原始可读名 |
| 签名 | `apksigner verify` | v2 / v3 通过 |

> 注：ONNX Runtime 与部分传递依赖在其自身 Manifest 中声明了
> `ACCESS_NETWORK_STATE`，本工程通过 `tools:node="remove"` 在合并阶段移除，
> 最终 APK 实测不含该权限（见上表）。

#!/usr/bin/env python3.11
# -*- coding: utf-8 -*-
"""
NymphAuto 发版数字自动核对（v2.9.9，只读、不联网、不改产物）。

回应「README 数字全靠手工核对」：从真实 APK、测试报告、源码包读取并打印
核对表。发布说明 / README 中的数字必须引用本脚本的输出，不允许手填估算。

v2.9.0：接入 16 KB 页对齐实测（tools/check_elf_alignment.py）。此前
build.gradle.kts 把 `useLegacyPackaging=false` 注释成「16KB 页对齐」，但产物
里自编译的 libquickjs-jni.so 的 LOAD Align 仍是 0x1000，属于文档与产物不符。
现在每次发版都实测每个 so 的 ELF 段对齐，自编译 so 未对齐直接判 FAIL。

v2.9.16：新增表述规范化门禁（tools/check_wording.py），扫描交付文本中的用户
反馈原话 / 口语化俚语 / 拟人与情绪化措辞，命中即判 FAIL，纳入发版硬门槛。
expected_symbols 同步加入 ScenePreset / SceneVerdict / sceneDebugEnabled /
setSceneIntent 等本次新增能力，确认「写了且接了线」而非只改源码。

v2.9.17：门禁按类别组织并输出分类统计；expected_symbols 加入意图后果展开、
执行范式同构呈现与位移自诊断三类新增能力，确认旁路埋点确实编进 release dex。

v2.9.18：拖动静态门禁新增「布局坐标空间标志已接线」断言，确认运行球与编辑
面板窗口确实带 FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS。

v2.9.19：拖动静态门禁新增「边界尺寸单一真源已接线」「应用窗口可用区尺寸未
用于边界收敛」「配置变化重定位已接线」三项断言；expected_symbols 加入
OverlayBounds / OverlayWindowMetrics，确认坐标空间契约确实编进 release dex。

v2.9.20：静态接线门禁新增「落点偏差护栏已接线」「执行范式作用域单一真源」
「判定结论与门控挂载呈现对齐」三项断言；expected_symbols 加入 MismatchGuard
与场景范式作用域符号，确认策略契约与界面呈现均确实编进 release dex。

v2.9.21：静态接线门禁改为断言「坐标拾取主流程 + 拾取后自动派生辅助门控」
这一交互层级重构。五项分别为：场景名可编辑且窗口可位级聚焦（IME 修正）、
场景列表取代逐条翻页、颜色素材与有效区域由拾取自动派生、点击前门控
fail-closed 与异步代际隔离、辅助载荷写入既有落盘字段（schemaVersion 不变）。
expected_symbols 同步替换为 SceneAssist / TapGate / SceneList 契约与界面接线
符号，确认本版结构改动确实编进 release dex。

v2.9.32：静态接线门禁新增「方向无关执行」与「判据参数配置」两组断言，逐层
锁定 OrientationTransform 帧归一化 / 落点反变换 / 方向元数据落盘 / 预检非阻断，
以及 SceneTuning 留空沿用 / 表单回显 / 面板收集 / 保存写回 / schema 不变；
expected_symbols 加入本版新增契约符号，确认两处能力确实编进 release dex。

v2.9.34：静态接线门禁新增「OCR 类型隔离」断言：OcrTextAssertion 无坐标字段、
OcrRegion 无文字字段、APPEAR_TEXT 条件改走无坐标断言接口、ocr_triggered 脱敏日志
不落原文/哈希、ML Kit 文案纠正为 bundled 静态打包。expected_symbols 加入本版新增
契约符号。版本判定升级为 2.9.34 / versionCode 52。
说明：manifest/golden/dry-run 契约与 flavor 产物拆分需真实 PP-OCRv5 权重与真机矩阵，
本版不做端到端验证，不作虚假承诺。

v2.9.36：真实用户 debug 包（录屏+日志+失败截图）定位的两类问题修复版。
① 界面卡死：takeScreenshot 在 16ms 帧同步下每帧提交、超时不取消底层请求导致
系统截图队列自我堆积（帧间隔 280ms→674ms→4061ms→27109ms 渐进劣化）。修复为
截图单飞（shotInFlight CAS，上一请求未落地前丢帧而非排队）+ 单次 1.5s 超时 +
BUSY 错误码不计入失败熔断。② ROI 判别度不足仍放行：装配 verdict=LOW_DISTINCTNESS
（命中 0.51 < 干扰 0.60、跨帧下界 0.51 < 阈值 0.55，数学上恒不触发）时，界面不再
显示「可触发」，保存时与「未装配」同等强告警。expected_symbols 加入 shotInFlight /
SHOT_ERROR_BUSY 等本版契约符号。版本判定升级为 2.9.36 / versionCode 54。

v2.9.37：对一份「2.9.36 仍存 bug」源码审查的逐条源码复核版。报告的 S1 三源不对账 /
#1 严格几何闸 / #3 #4 死代码未接线 / #5 搜索窗边界 / #6 pickedRegion!! / 单飞饿死
等指控经源码实读判定为「设计如此或误报」——几何主链是 v2.9.31/32 真机 RCA 的稳定
设计，为对账改动它属确定性回归，不采纳。但复核过程中发现两个报告未列的真 bug：
① P0 截图单飞锁泄漏：awaitShot 的 withTimeoutOrNull{done.await()} 在 try/catch 外，
协程取消时 shotInFlight 不复位 → 永久 BUSY、无帧、不熔断（复现用户卡死）。修复为
submit+await 全纳入 try/finally，release() 用 token CAS 做代次隔离 + 幂等解锁（同时
堵住旧回调误释放新请求锁的竞态），CancellationException 重抛保留取消语义。
② P1 OCR 置信度断链：v2.9.34 的 ctcGreedyScored.confidence 算对却无人消费——
TextBox 无字段、from 硬编码 -1.0、OnnxOcrEngine 走 ctcGreedy 只取 text，导致
ocr_triggered 永远记 -1.0。修复为 TextBox 加 confidence 默认字段、from 透传、
recognizeLine 走 scored、日志与断言取真实值（ML Kit 无 API 仍诚实记 -1.0）。
版本判定升级为 2.9.37 / versionCode 55。

v2.9.38：崩溃可见诊断版。用户报告已授权且已开无障碍但打开即闪退，静态
排查启动链（Application/Activity/ViewModel/Compose 首帧）、产物完整性、dex 类与
so 均无必然崩溃点，冷启动闪退唯一可靠证据是真机崩溃堆栈，而原崩溃钩子只写
filesDir/logs/last_crash.txt，冷启即崩时用户进不去日志页导出。本版新增独立进程
:crash 的 CrashReportActivity（纯代码 UI，零新增资源），全局崩溃钩子在写盘后立即
拉起错误页直接呈现完整堆栈 + 复制/分享/重启，错误页自身崩溃时不再拉起（防循环）。
既有崩溃链语义不变（仍链式调用系统默认处理器）。expected_symbols 加入
CrashReportActivity 与本版符号。版本判定升级为 2.9.38 / versionCode 56。

v2.9.39：真机崩溃修复版。堆栈确证 `Key "未命名脚本" was already used`——脚本列表
LazyColumn 以 name 作 key，老版本遗留 script.json（内部 name=未命名脚本）与升级后
新建的同名文件共存时 name 重复即崩，且按 name 删除/编辑会串台。本版两道修复：
① ScriptStore 增加幂等 migrateDuplicateNames() 启动迁移，给重复 name 的旧文件追加
唯一后缀写回，让 name 恢复唯一主键；② ScriptUi 携带文件名 id、LazyColumn key 改用
it.id，即便迁移遗漏也绝不崩。expected_symbols 加入 migrateDuplicateNames；静态门禁
新增 duplicate_name_key_wired 断言（迁移接线 + id 字段 + key={it.id}）。版本判定升级
为 2.9.39 / versionCode 57。

v2.9.40：数据完整性修复版。对三份审查文档逐条源码甄别（属实≠该修），只修 3 条
确证真 bug：① P0-01 ActionEditGuard 编辑 PX/DP 动作强改 PCT 且坐标钳到 100 导致
落点报废——改为保留原单位、上限随单位走；② P0-09 LineImporter 坐标解析失败补 0
导致 (0,45) 贴边乱点——改为解析失败丢弃整条并在摘要可见；③ P0-15 AtomicTextFile
用 contains(".tmp") 子串匹配误删用户自有 .tmp 中间名文件——改为严格匹配自家临时
命名格式。其余 P0/P1 属实但需真机/涉大重构/语义变更/文档定性错误的条目不改。新增
静态门禁 v2940_data_integrity_wired。版本判定升级为 2.9.40 / versionCode 58。

v2.9.41：2.9.40 审查回执确证真 bug 修复版。① LineImporter 滑动终点此前仍 `?: 0.0`
且 endPosData 缺失时无条件补 0，生成「滑到 (0,0)」——改为与起点同款解析失败/缺失
即丢整条；② AtomicTextFile 老格式分支 `.+\.tmp$` 收得过宽仍会删用户 `备份.tmp`——
两分支一并锚定 `.json.tmp`；③ 重名迁移 item.file.delete() 失败被吞且无告警、下次再生
(3)——改告警 + .orphan 改名隔离；⑤a 崩溃页 isCrashProcess 加 /proc/self/cmdline 回退
防 ROM API 失效导致防循环失效（⑤b 后台启动限制需真机、记 ADR 不改）。文档与版本脱节
经核查为旧快照误报，本版改为「文档全部落定后再打源码包」封死时序隐患。新增静态门禁
v2941_data_integrity_hardening。版本判定升级为 2.9.41 / versionCode 59。

v2.9.42：2.9.41 审查回执确证真 bug 修复版。逐条源码核查（属实≠该修）后修 3 处 + 补测：
① STALE_TMP 事实前提错误——历史快照写 .naz（ScriptRepository.SUFFIX）而非 .json，旧正则结构上
匹配不到 .naz.tmp，且 .history 目录从未接 cleanupStale——正则扩为 (json|naz) 两类 + ScriptRepository.init
接 cleanupStale(history)；③ LineImporter 起点 `x<=0&&y<=0` 双零丢弃与「终点合法零保留」口径矛盾——
去掉起点双零丢弃，起终统一为 parsePercent 成功即保留；② ④⑤a 零测试——补 V2942FixRegressionTest
（core）+ CrashReportActivityTest（app）回归。④ orphan 无回收路径属设计副作用、审查稿本版未列必修，
记为已知边界。新增静态门禁 v2942_snapshot_and_coord_wired。版本判定升级为 2.9.42 / versionCode 60。

v2.9.43：「不抢录屏权限」永久门禁加固版。审查稿逐条确认 2.9.42 干净无新 bug，核心逻辑不动。
本版落实硬约束「不管何时都不和录屏抢权限」：此前 README 声称 8 类录屏符号引用为 0，
但门禁只在 dex 层锁 7 项、缺 createScreenCaptureIntent 等授权入口，且无源码级拦截——
与 v2.9.41「错误事实焊进正则」同类（断言写在文档、门禁没钉死）。本版 dex 层 BANNED
补齐 MediaProjectionManager / createScreenCaptureIntent / getMediaProjection /
ACTION_MEDIA_PROJECTION / android/media/projection / createScreenCapture；新增源码级门禁
v2943_no_screen_capture_api 扫描全量 main 源码非注释代码行，精确 API 标识符零引用，
并正向锁定无障碍截图通道（canTakeScreenshot=true / 不启用触摸浏览）。裸词 MediaProjection
因中文说明文案合法出现、由 dex 层字节锁定，双层互补。版本判定升级为 2.9.43 / versionCode 61。

v2.9.44：场景驱动无障碍节点 fallback 版。真机证据：系统录屏占用无障碍截图通道后
场景驱动每帧 findTemplates 失败 → 767 帧命中 0 → 一次不点。节点树 rootInActiveWindow
不受录屏影响、全程零截图。本版给场景驱动加一条「截图失败 / NCC 未命中时按落盘语义节点
判定场景出现」的触发源：SceneRule 新增四个带默认值字段（schemaVersion 保持 1、老脚本
零迁移），装配期拾取瞬间 findSmallestNodeAt 捕获锚点节点 text/viewId/class（有判别度才
启用），运行期 nodeFinder.find 命中即构造 SceneObservation 喂入同一套 SceneTrigger 状态机
与落点护栏；节点命中落点直接用当前节点中心屏幕百分比（方向无关、免 NCC 相对校验）。
纯图像 / 游戏 / 桌面色块无语义节点 → 不启用、行为逐字不变（技术边界，写入文档）。
新增静态门禁 v2944_node_fallback_wired；录屏双层门禁 v2943 与 BANNED 13 项保持不变。
版本判定升级为 2.9.44 / versionCode 62。

v2.9.45：稳定性修复版。两处确证并发缺陷：① QuickJsAndroidBinding 的原生 runtime
指针用裸 Long 做 check-then-act，脚本线程 finally 与主线程停止链路可同时读到同一个
非 0 指针 → native double-free / UAF；且 stopAll/handover 在主线程直接 dispose 正在
nymph-script 线程 eval 的 runtime，构成 use-after-free。修复：新增纯 JVM 一次性句柄
守卫 NativeHandle（AtomicLong.getAndSet 保证 destroy 恰好一次），QuickJsAndroidBinding
改用它；主线程停止链路只做 requestInterrupt + cancel，真正销毁收敛到脚本协程 finally
并由 invokeOnCompletion 兜底（用局部捕获引用避免误伤下一轮）。② LogStore.append 被
RunLog 监听器在任意线程并发回调，无锁的 length 检查 + rename 轮转 + appendText 会交错，
加 @Synchronized 把检查—轮转—追加串行化。新增静态门禁 v2945_stability_wired 与
7 例纯 JVM 并发回归；前序门禁（v2944 节点 fallback / v2943 录屏双层 / BANNED 13）不变。
版本判定升级为 2.9.45 / versionCode 63。

v2.9.46：系统性稳定性修复版（审计第一轮 P0 四项）。① 无效帧冻结：截图失败 /
几何错配此前构造 present=false 观测推进 missStreak，ON_DISAPPEAR 把「没拍到」当
「场景消失」误派发下降沿；SceneObservation 增 valid 三态，SceneTrigger 对无效帧
冻结状态机。② RunSession 唯一所有权：toggle 的 finally/postUi/invokeOnCompletion
此前全写共享字段，停止后立即重启会被旧收尾破坏（关新浮层、取消新看门狗、改写状态）；
新增 core RunToken 单调会话号，所有延迟提交以 isCurrent(myRunId) 守卫，停止/交接先
invalidate，运行中拒绝重复 ACTION_START，看门狗与浮层用局部引用释放。③ 识别热路径：
模板此前每帧每规则重复 decodeBase64 + 整帧 copyOf；改实例级模板解码缓存 + 仅掩码规则
复制整帧。④ QuickJS 同步桥：NativeHandle 加读写锁（useIfAlive 读锁 / disposeOnce 写锁）
消除在途 UAF；按 API 拆分超时，sleep 用实际时长+余量且停止标志 50ms 轮询可打断，
桥接协程退出即取消。新增静态门禁 v2946_stability_wired 与 14 例纯 JVM 回归；
前序 v2945/v2944/v2943 门禁与 BANNED 13 项不变。版本判定升级为 2.9.46 / versionCode 64。

v2.9.47：视频→规则流水线的「四通道降级」P0 落地（纯 core/JVM、零新依赖、零模型）。
B 通道四证据投票 TapEvidence（关键帧稀疏化 / 帧差块匹配判滑动 / 涟漪瞬态二阶导 /
手指遮挡弱证据，±200ms·±12% 屏宽时空对齐，≥2 票进候选、单票待确认、冲突强制确认），
增强替换 TapCandidateDetector（detect 对外 API 零回归，新增 detectWithEvidence）；
离线回放证伪器 ReplayFalsifier（前置从未满足判不可执行、后置窗口、可执行概率 0~1、
<0.75 待确认，observeFrames/falsifyTaps 纯函数）；app 层 KeyframeTimeline 用 MediaExtractor
读 SAMPLE_FLAG_SYNC 零解码建关键帧时间表 + sampleFramesAdaptive 稀疏采样，生成期对每条
勾选规则离线回放一次。新增静态门禁 v2947_four_channel_wired 与 19 例纯 JVM 回归；
前序 v2946/v2945/v2944/v2943 门禁与 BANNED 13 项不变。版本判定升级为 2.9.47 / versionCode 65。

v2.9.48：视频导入工作台的"减量 + 可裁决"体验升级。根因：needsReview 谓词的 n<2 是
独立硬门槛，单票 confidence 公式上限 0.475 < 0.75 → 53/53 恒待确认，标记退化为常量。
修复：core fuse 改强度感知谓词（冲突 OR 单票且弱才待确认）+ ReviewLevel 单一真源
（CONFLICT/WEAK_SINGLE/NEEDS_REVIEW/OK）；app 层证伪前置到列表、证据命中诊断 +
重录引导、默认折叠只显示高价值子集、批量裁决、缩略图 + 放大图拖拽修正落点（瞬态
LRU 缓存不落盘、零二次解码抽取）。新增静态门禁 v2948_ui_triage_wired 与纯 JVM 回归；
前序 v2947/v2946/v2945/v2944/v2943 门禁与 BANNED 13 项不变。版本判定 2.9.48 / 66。

v2.9.49：把「框选搜索区域 ROI / 模板范围」重构为多特征框拾取容器。core 新增
FeatureBox（每框独立相似度）+ FeatureBoxAnalysis（重叠冗余检测、纹理强度参考）+
RoiApplier.applyMulti（唯一真源：N 框按遍历序写到脚本内 N 个真实目标，每框相似度写入
imageSimilarity，短边轮转，单框兼容旧 apply）；app 浮层 CoordinatePicker 新增多框会话
（＋加框/−删框替换框选态方向键、点拾取方向键零回归、已落定框淡色编号显示、确认一次提交
全部、取消无副作用）；EditorScreen 多框配置卡片（每框相似度滑杆、纹理参考、重叠警告、
删除单框、落盘走 applyMulti）。零 schema 变更、零新依赖、零联网。新增静态门禁
v2949_multifeature_box_wired 与纯 JVM 回归；前序门禁与 BANNED 13 项不变。版本判定 2.9.49 / 67。

v2.9.35：三份审查报告交叉核查后的确证真 bug 集中修复版。静态接线门禁新增
「脚本执行引擎与 JS 架构隔离」断言：脚本运行在专用单线程（scriptThread）、UI 回调
经 postUi 回主线程、zdjl.check 用隔离 ConditionEvaluator 防跨线程重入、原生上调
30s 超时闭锁；expected_symbols 加入 scriptThread / postUi / checkEvaluator /
looksCorrupt / versionRegexOf / normalizeTextPattern / loadMutex / sessionOptions 等
本版契约符号，确认修复确实编进 release dex。版本判定升级为 2.9.35 / versionCode 53。

v2.9.33：静态接线门禁新增「回切失败降级」与「OCR 边界可见告警」两组断言：
RestorePolicy 拉起失败后按当前包名裁决放行 / 中止、一次性点名文案、无 shell 兜底；
dependsOnText 判定脚本依赖文字锚点时才提示 OCR 未就绪，RecordCompiler 滚动告警
补充列表视图复用说明。expected_symbols 加入本版新增契约符号。
版本判定升级为 2.9.33 / versionCode 51。

用法：
    python3.11 tools/verify_release.py
    python3.11 tools/verify_release.py --json     # 机器可读输出
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKSPACE = os.path.dirname(ROOT)
BT = "/opt/android-sdk/build-tools/36.0.0"
APK = os.path.join(WORKSPACE, "NymphAuto-release.apk")
SRC_ZIP = os.path.join(WORKSPACE, "NymphAuto-source-v3.0.4.zip")
DEX_DIR = "/tmp/build/v300dex"

# 16 KB 页对齐核验（v2.9.0）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_elf_alignment as elf  # noqa: E402
import check_wording  # noqa: E402

BANNED = (
    "MediaProjection", "VirtualDisplay", "createVirtualDisplay",
    "MediaCodec", "FLAG_SECURE", "RECORD_AUDIO", "WATCH_OUTSIDE_TOUCH",
    # v2.9.43 / ADR-061：补齐 README 已声称、门禁此前未锁的录屏授权入口符号。
    "MediaProjectionManager", "createScreenCaptureIntent", "getMediaProjection",
    "ACTION_MEDIA_PROJECTION", "android/media/projection", "createScreenCapture",
)


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(args: list[str]) -> str:
    p = subprocess.run(args, capture_output=True)
    out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    return out


def apk_info() -> dict:
    badging = run([f"{BT}/aapt2", "dump", "badging", APK])
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    perms = re.findall(r"uses-permission: name='([^']+)'", badging)
    abi = re.search(r"native-code: '([^']+)'", badging)
    return {
        "package": pkg.group(1) if pkg else "?",
        "versionCode": int(pkg.group(2)) if pkg else 0,
        "versionName": pkg.group(3) if pkg else "?",
        "bytes": os.path.getsize(APK) if os.path.isfile(APK) else 0,
        "sha256": sha256(APK) if os.path.isfile(APK) else "-",
        "permissions": len(perms),
        "permission_list": perms,
        "abi": abi.group(1) if abi else "?",
    }


def signature_info() -> dict:
    out = run([f"{BT}/apksigner", "verify", "--print-certs", "--verbose", APK])
    m = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", out)
    return {
        "verifies": out.lstrip().startswith("Verifies"),
        "v2": "v2 scheme (APK Signature Scheme v2): true" in out,
        "v3": "v3 scheme (APK Signature Scheme v3): true" in out,
        "cert_sha256": m.group(1) if m else "?",
    }


def dex_info() -> dict:
    dexes = sorted(glob.glob(os.path.join(DEX_DIR, "classes*.dex")))
    if not dexes:
        return {"classes": 0, "banned_refs": {}, "note": "未找到解压的 dex"}
    out = run([f"{BT}/dexdump", "-f", *dexes])
    own = 0
    banned_hits: dict[str, list[str]] = {k: [] for k in BANNED}
    banned_hits["MediaCodec"] = []
    # ADR-072 / v3.0.2：双取帧模式。MediaProjection 家族符号在源码层已做白名单隔离
    # （仅 MediaProjectionSource / HudCaptureService 可用，见 v2943）。dex 层同步：
    # 来自这两个白名单类的引用不计违规；其余类仍红。BANNED 元组保持 13 项不变，
    # 以满足前序门禁的「BANNED 13 项不回退」断言——只是命中判定加白名单过滤。
    mp_family = {
        "MediaProjection", "VirtualDisplay", "createVirtualDisplay",
        "MediaProjectionManager", "createScreenCaptureIntent", "getMediaProjection",
        "ACTION_MEDIA_PROJECTION", "android/media/projection", "createScreenCapture",
    }
    mp_class_whitelist = ("Ldev/nymph/auto/engine/MediaProjectionSource", "Ldev/nymph/auto/service/HudCaptureService")
    cur = ""
    for line in io.StringIO(out):
        if "Class descriptor" in line:
            cur = line.strip()
            if "Ldev/nymph/auto" in line:
                own += 1
        for k in BANNED:
            if k in line and ("type" in line or "reg=" in line or "Class descriptor" in line):
                # 白名单类里的 MediaProjection 家族引用是合法的双模式实现，不计违规。
                if k in mp_family and any(w in cur for w in mp_class_whitelist):
                    continue
                banned_hits[k].append(cur[:110])
    # v2.9.2 / ADR-008：新增核心能力必须真正进 release dex，不能「写了没接线」。
    # v2.9.3：浮窗统一视觉投影 OverlayTheme 是那次核心改动，必须确实编进 release dex。
    # v2.9.4：拖动手势状态机 DragGesture 必须确实编进 release dex。
    # v2.9.6：等待画面出现门控 WaitGate 必须确实编进 release dex。
    # v2.9.7：收起球「点击 / 拖动」判定收敛到 core 纯函数，isDragMove / isTap
    #         必须真的编进 release dex，不能只改源码没进包。
    # v2.9.8：同步提交决策 commitDrag、4dp 拖动阈值 dragStartPx 同样必须进包。
    # v2.9.9：拖动改由根视图 onInterceptTouchEvent/onTouchEvent 统一接管（根除
    #   ScrollView 吞手势），但真机仍不派发 MOVE；v2.9.10 改回本工程真机验证可用的
    #   setOnTouchListener 机制（与 RunnerService.attachBallTouch 同款），FloatingEditorPanel
    #   必须确实编进 release dex。
    # v2.9.11：真因定位——拖动手柄是 MaxHeightScrollView 的后代，无论用
    #   onInterceptTouchEvent 还是 setOnTouchListener，ScrollView 都在
    #   onInterceptTouchEvent 抢走竖直 MOVE，手柄收不到 MOVE → 窗口完全不动。
    #   修法：把顶部条整体移出 ScrollView（与 collapsedBall 平级），并对祖先调用
    #   requestDisallowInterceptTouchEvent(true)。两者都必须确实编进 release dex。
    expected_symbols = (
        "dev/nymph/auto/core/recorder/RecordabilityGuard",
        "dev/nymph/auto/core/model/LoopPolicy",
        "dev/nymph/auto/ui/OverlayTheme",
        "dev/nymph/auto/core/gesture/DragGesture",
        "dev/nymph/auto/core/video/WaitGate",
        "isDragMove",
        "isTap",
        "commitDrag",
        "dragStartPx",
        "requestDisallowInterceptTouchEvent",
        # v2.9.14：场景驱动（Scene → Trigger）必须真正编进 release dex：
        # 自动 ROI + 场景规则装配 + 边沿触发调度 + 执行引擎分流，缺一不可。
        "dev/nymph/auto/core/editor/SceneTrigger",
        "autoRoi",
        "attachScene",
        "planSceneFiring",
        "sceneGateOf",
        "sceneDriven",
        "runSceneDriven",
        # v2.9.15 / ADR-031：三处落盘锁定项（模板结构/坐标语义/触发语义）、
        # A1 稳定锚点、A2 相对匹配坐标、A3 带状态触发、A5 拾取即自测与调试覆盖层、
        # A6 一轮一帧，全部必须真的编进 release dex，不能「写了没接线」。
        "dev/nymph/auto/core/model/SceneRule",
        "dev/nymph/auto/core/vision/StableAnchor",
        "dev/nymph/auto/core/editor/SceneSelfTest",
        "dev/nymph/auto/engine/SceneDebugBox",
        "attachSceneRule",
        "planSceneFiringStateful",
        "resolveSceneClick",
        "calibrateSimilarity",
        "computeClickOffset",
        "findTemplates",
        "findDetailed",
        "pickAnchor",
        "planMask",
        "captureSceneAnchor",
        "buildSceneRule",
        "onSceneDebug",
        # v2.9.16 / ADR-032：意图层 ScenePreset、判定层 SceneVerdict、
        # 可观测性独立开关 sceneDebugEnabled、面板意图摘要 setSceneIntent，
        # 全部必须确实编进 release dex，不能「写了没接线」。
        "dev/nymph/auto/core/editor/ScenePreset",
        "dev/nymph/auto/core/editor/SceneVerdict",
        "sceneDebugEnabled",
        "setSceneIntent",
        "sceneIntentSummary",
        "roundTrip",
        "ofUnmeasured",
        # v2.9.17 / ADR-033：意图后果展开、执行范式同构呈现、位移事件链路自诊断。
        # 三项都必须确实编进 release dex，确认旁路埋点与界面呈现均已接线。
        "dev/nymph/auto/core/editor/ExecutionParadigm",
        "dev/nymph/auto/core/gesture/DragProbe",
        "dev/nymph/auto/ui/DragDiagnostics",
        "optionCatalog",
        "sceneExplanation",
        "setParadigm",
        "setIntentDetail",
        "onDragCommit",
        "writeLog",
        "MOVE_INTERCEPTED",
        "COMMIT_BLOCKED",
        # v2.9.18：悬浮窗可见但位移交互未生效根因修复——运行球 / 编辑面板窗口补齐
        # FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS（与坐标拾取器、录制面板一致）。
        # OverlayWindowSpec 是编辑面板 flags 单一真源，必须确实编进 release dex，
        # 且常量 FLAG_LAYOUT_IN_SCREEN 出现在 dex 中，确认修复落包而非只改源码。
        "dev/nymph/auto/core/editor/OverlayWindowSpec",
        "FLAG_LAYOUT_IN_SCREEN",
        "FLAG_LAYOUT_NO_LIMITS",
        # v2.9.19：全屏绝对坐标空间的边界契约与 Android 侧单一真源。
        # 断言二者确实编进 release dex，确认边界收敛与配置变化重定位不是
        # 只改源码而未接线的空实现。
        "dev/nymph/auto/core/editor/OverlayBounds",
        "dev/nymph/auto/ui/OverlayWindowMetrics",
        "reposition",
        "screenSize",
        # v2.9.20：连续落点偏差护栏、执行范式作用域与场景范式收尾口径。
        # 断言策略契约与界面呈现确实编进 release dex，而非仅存在于源码与文档。
        "dev/nymph/auto/core/editor/MismatchGuard",
        "onMismatch",
        "onFired",
        "BACKOFF_AFTER",
        "sceneDrivenNotice",
        "labelFor",
        "sceneSummary",
        # v2.9.20 重新发版：按规则独立计数容器与离场保持态必须确实编进 dex，
        # 确认「全局单例计数」已被替换，而非仅新增未接线的类。
        "MismatchGuard$Tracker",
        "onIdle",
        "countFor",
        # v2.9.21：场景交互层级重构的三份纯 JVM 契约与界面接线符号。断言自动素材
        # 采集 / 有效区域收敛 / 点击前门控 / 代际隔离 / 场景列表 / 位级焦点切换
        # 确实编进 release dex，而非仅存在于源码与文档。
        "dev/nymph/auto/core/editor/SceneAssist",
        "SceneAssist$ColorSample",
        "sampleNeighborhood",
        "convergeRoi",
        "resolveRoi",
        "colorSummary",
        "dev/nymph/auto/core/editor/TapGate",
        "TapGate$Generation",
        "isCurrent",
        "COLOR_MISMATCH",
        "dev/nymph/auto/core/editor/SceneList",
        "SceneList$Row",
        "nameEditable",
        "coordinateLabel",
        "focusableFlags",
        "isFocusToggleOnly",
        "bindSceneList",
        "onSelectScene",
        "refreshSceneList",
        "showAssist",
        # v2.9.22：拾取纠偏反馈契约与结果卡接线。断言「未命中原因 → 主修复动作」
        # 的映射、置信度分级、诊断摘要与结果卡渲染确实编进 release dex。
        "dev/nymph/auto/core/editor/PickFeedback",
        "PickFeedback$Guidance",
        "PickFeedback$Counters",
        "guidanceFor",
        "confidenceOf",
        "leaksInternalTerm",
        "diagnose",
        "showResult",
        "onRunPrimaryFix",
        "onCopyDiag",
        "renderResult",
        "copyDiagnostic",
        "resampleColor",
        # v2.9.23：装配失败阶段化。断言失败阶段枚举、阶段到结论的单一映射与
        # app 层显式装配结果类型确实编进 release dex。
        "TapGate$Failure",
        "reasonFor",
        "captureFailure",
        "BuildResult",
        "BuildResult$Fail",
        # v2.9.25：门控锚点维度与运行前置计数必须真的进包，否则界面会退回
        # 「假 PASS」与「点了运行什么都不发生」。
        "pendingAnchorReady",
        "triggerableCount",
        # v2.9.26：未命中归因维度必须真的进包，否则「元素出现却判未出现」仍无从取证。
        "bestCxPx",
        "bestCyPx",
        # v2.9.27：预检告警通道、包可见性标记与实时坐标系字段必须真的进包，
        # 否则录制脚本仍会被误拦、转屏坐标仍会错乱、日志仍会自相矛盾。
        "warningsText",
        "packageVisibilityRestricted",
        "currentFrameProvider",
        # v2.9.28：逐帧判定策略、每轮强制新帧、固定步长节拍与日志心跳必须真的进包，
        # 否则场景驱动仍会被固定轮询节拍与旧帧缓存锁在低频判定。
        "dev/nymph/auto/core/runner/SceneFramePolicy",
        "frameIntervalMs",
        "delayFor",
        "heartbeatLine",
        "grabFreshFrame",
        "paceFrame",
        # v2.9.29：步进器与「复用落点」必须真的进包，否则数值仍要弹键盘、
        # 新场景仍要重新拾取一次。
        "dev/nymph/auto/core/editor/StepPolicy",
        "bump",
        "hintOf",
        "summaryOf",
        "stepperRow",
        "stepperBtn",
        "useCurrentPoint",
        "lastAnyPickPct",
        # v2.9.29：整屏捕获、抓手几何与落点边缘安全必须真的进包，
        # 否则边缘仍无法拖动、落点仍会被系统手势静默消费。
        "PointerCaptureView",
        "onTap",
        "scaledTouchSlop",
        "dev/nymph/auto/core/vision/PointerTouchTarget",
        "windowFor",
        "markerIn",
        "exclusionFor",
        "crossInWindowX",
        "dev/nymph/auto/core/vision/EdgeSafety",
        "isRisky",
        "gestureInsets",
        "setEdgeInsets",
        "safePoint",
        "edgeAdjusted",
        # v2.9.30 / ADR-047：连接所有权、预告分级、终止报告、降级就绪
        # 四项契约与它们的界面接线必须真的编进 release dex，
        # 不能「写了没接线」——上一版「暂停仍持有连接」正是接线缺口。
        "dev/nymph/auto/core/runner/ConnectionState",
        "dev/nymph/auto/core/runner/AnnouncePolicy",
        "dev/nymph/auto/core/runner/HaltReport",
        "dev/nymph/auto/core/runner/DegradeChannel",
        "allowsInjection",
        "allowsPerception",
        "emitHalt",
        "movePhase",
        "handover",
        "resumeFromPc",
        "undoRequested",
        "showUndo",
        "setNext",
        # v2.9.31 / ADR-049：装配-运行同源证据与其运行期接线
        "dev/nymph/auto/core/editor/SceneEvidence",
        "geometryMatches",
        "recoverPickPoint",
        "maskCovers",
        "Tier",
        "LAYERED",
        "NoValidLocation",
        # strictNoFallback 是函数参数名，不进 dexdump 类/方法/字段表；
        # 其接线由 evidence_same_source 静态门禁断言，符号表改用确实进包的数据契约。
        "SceneEvidence$Verdict",
        "crossFrameFloor",
        "planMaskTemporal",
        "meanAbsDiff",
        "MIN_SAFE_SIMILARITY",
        "colorArgb",
        "colorTolerance",
        "convergedRoi",
        "evidenceStreak",
        "observedArgb",
        "geometryOk",
        "maskCovered",
        "templateSrcW",
        "onSuspectedPresent",
        "setSuspect",
        # v2.9.32 / ADR-050：方向无关执行与拾取后判据参数配置的契约符号。
        # 选符号原则：类描述符 + 唯一性方法 / 字段（dexdump 中出现 1~2 次）。
        # `fields` / `norm` / `applyTo` / `bump` 等短名在多类复用，作为「本版符号
        # 进包」的证据不成立，故不收录；其接线由 Y / Z 两组静态门禁逐层断言。
        "dev/nymph/auto/core/runner/OrientationTransform",
        "fromDimensions",
        "geometryCompatible",
        "pointToCurrent",
        "regionToCurrent",
        "maskToCurrent",
        "rotateImage",
        "driftPct",
        "GEOMETRY_TOL",
        "dev/nymph/auto/core/editor/SceneTuning",
        "SceneTuning$Config",
        "SceneTuning$Field",
        "hasOverride",
        "pickRotation",
        "orientationTurns",
        "locMaxDeviationPct",
        "tuningInputs",
        "tuningBaseline",
        "setTuningBaseline",
        "bumpTuning",
        # v2.9.33 / ADR-051：回切失败降级与 OCR 边界可见告警的契约符号。
        # 均为本版新增的类 / 方法 / 字段，短名唯一，静态接线另由 AA / AB 门禁断言。
        "dev/nymph/auto/core/runner/RestorePolicy",
        "RestorePolicy$Outcome",
        "explicitMainIntent",
        "dependsOnText",
        "ocrUnavailableWarned",
        "markOcrUnavailableWarned",
        # v2.9.34 / ADR-052：OCR 类型隔离的契约符号。选进包即能证明本版类型拆分
        # 确实编进 release dex；断言文字无坐标 / 区域无文字的接线另由 AC 门禁锁定。
        # 注意：OcrAssertion.kt 是文件名，其中并无 OcrAssertion 类，故用两个真实类
        # 描述符；hasConfidence 是计算属性， dex 中会编译为 getHasConfidence。
        "dev/nymph/auto/core/vision/OcrTextAssertion",
        "dev/nymph/auto/core/vision/OcrRegion",
        "OcrTextAssertion",
        "OcrRegion",
        "UNAVAILABLE_CONFIDENCE",
        "DEFAULT_MATCHER",
        "SOURCE_VISION",
        "centerX",
        "getHasConfidence",
        "ctcGreedyScored",
        "OcrPostprocess$Decoded",
        "assertText",
        "emitOcrTriggered",
        # v2.9.35 / 三份审查报告确证 bug 修复：脚本执行引擎与 JS 架构隔离、
        # 存储数据丢失防护、原生资源生命周期。均为本版新增的方法/字段名，
        # 确定编进 release dex，确认修复确实进包而非仅改源码。
        "scriptThread",          # 脚本专用单线程字段（dexdump 字段表可见）
        "postUi",                # UI 回调切主线程
        "checkEvaluator",        # zdjl.check 隔离求值器，防跨线程重入
        "versionRegexOf",        # 历史版本完整锚定，防前缀串档
        "looksCorrupt",          # 损坏录制不覆盖历史
        "normalizeTextPattern",  # /正则/ 首尾斜杠剥离
        "loadMutex",             # TFLite 并发加载互斥
        "sessionOptions",        # ORT 会话选项生命周期
        # v2.9.36 / 真实 debug 包定位修复：截图单飞标志（AtomicBoolean 字段，
        # dexdump 字段表可见），确认「截图请求不堆积」修复确实编进 release dex。
        "shotInFlight",          # 截图单飞 CAS 标志，防 takeScreenshot 请求堆积假死
        # v2.9.37 / 复核发现的真 bug：TextBox 承载 OCR 置信度字段，getter 确定进 dex。
        "getConfidence",         # P1：CTC 置信度从 TextBox 贯通到日志与断言
        # v2.9.38 / 崩溃可见诊断版：独立进程错误页的伴生对象方法确定进 dex。
        "isCrashProcess",        # 防止错误页自身崩溃循环拉起（本版唯一方法符号）
        # v2.9.39 / 重名崩溃修复：启动幂等迁移方法确定进 dex，确认修复确实编进包。
        "migrateDuplicateNames", # name 恢复唯一主键，杜绝 LazyColumn 重名 key 崩溃
        # v2.9.47 / 四通道降级 P0：四证据投票 + 离线证伪 + 关键帧稀疏采样必须真编进 dex。
        "dev/nymph/auto/core/video/TapEvidence",
        "dev/nymph/auto/core/video/ReplayFalsifier",
        "dev/nymph/auto/ui/KeyframeTimeline",
        "detectWithEvidence",
        "falsifyTaps",
        "observeFrames",
        "isDenseRegion",
        # v2.9.48 / 减量+可裁决：强度感知谓词 + ReviewLevel 单一真源 + 瞬态缩略缓存。
        "ReviewLevel",
        "levelOf",
        "strongSingleStrength",
        "FramePreviewCache",
        "frameFullAt",
        # v2.9.49 / 多特征框：拾取容器 + 单一真源 + 分析纯函数 + 浮层多框会话。
        "FeatureBox",
        "applyMulti",
        "overlapRatio",
        "textureStrength",
        "deliverRegions",
        "ADD_BOX",
        # v3.0.1 / ADR-069：图片特征截取交互闭环的核心符号必须编进 release dex。
        # 类描述符证明 core 几何确实进包；短方法名（dexdump 可见）证明裁剪/相似度/
        # region 换算三处纯逻辑未被摘除。UI 接线另由 v3001_feature_capture_wired 锁定。
        "dev/nymph/auto/core/capture/FeatureCapture",
        "cropRect",
        "clampSimilarity",
        "toRegion",
    )
    return {
        "classes_own": own,
        "banned_refs": {k: len(v) for k, v in banned_hits.items()},
        "banned_detail": {k: v[:3] for k, v in banned_hits.items() if v},
        "expected_symbols": {s: (s in out) for s in expected_symbols},
    }


def drag_gate() -> dict:
    """v2.9.8 / v2.9.9 / v2.9.10 静态门禁：拖动必须是「setOnTouchListener 直吃手势
    + 同步直提交」，不得回归三类坏链路。

    1. v2.9.7 的 `Choreographer.postFrameCallback` + `frameScheduled` 自锁会让
       `frameScheduled` 永久为 true，之后每个 MOVE 都在入口 return，真机表现
       就是「折叠态悬浮球位移能力未生效」。
    2. v2.9.8 及以前把拖动监听器挂在**嵌在 ScrollView 内的顶部条**上，竖向 /
       斜向 MOVE 刚过阈值就被 `MaxHeightScrollView.onInterceptTouchEvent` 截走，
       列表项只收到 DOWN + CANCEL，拖动监听器拿不到 MOVE —— 装机观测结论为
       「展开态面板位移幅度显著低于手势位移，且位移过程不连续」。
    3. **v2.9.9 的「根视图 onInterceptTouchEvent / onTouchEvent 统一接管」在真机上
       仍不派发 MOVE**（含 ScrollView 的 ViewGroup 上该写法被 ScrollView 截走），
       第四轮装机回归结论「展开态面板与悬浮球均无位移响应」。v2.9.10 改用本工程在真机上
       **唯一被验证可拖动**的写法——`RunnerService.attachBallTouch` 同款的
       `setOnTouchListener` 机制，直接挂在折叠小球与顶部拖动手柄上，绕过整个
       onInterceptTouchEvent 分发链。
    4. **v2.9.11 真因定位**：v2.9.10 位移能力仍未生效，是因为拖动手柄（`dragHandle`）仍
       **位于 `MaxHeightScrollView` 内部**（标题条是 `body` 的子 View，`body` 又
       在 `scroll` 内）。无论用哪种监听器，ScrollView 一旦判定是竖直/斜向手势，就在
       `onInterceptTouchEvent` 把后续 MOVE 抢走，手柄收不到 MOVE → 窗口原地不动。
       这与 RunnerService 运行球具备位移能力形成对照：运行球是**孤零零的 `FloatBall` 视图，
       下面没有 ScrollView**。v2.9.11 把顶部条**整体移出 ScrollView**（与 collapsedBall
       平级，成为 `expanded` 的直接子 View），并按下时对祖先调用
       `requestDisallowInterceptTouchEvent(true)` 双保险。结构性根因至此对齐运行球。

    这里只检查**非注释代码行**，把「不得复活」变成每次发版自动跑的断言。
    """
    svc = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/service/FloatingEditorService.kt")
    panel = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/FloatingEditorPanel.kt")
    runner = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/service/RunnerService.kt")
    spec = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/OverlayWindowSpec.kt")
    picker = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/CoordinatePicker.kt")
    picker_widgets = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/PickerWidgets.kt")
    pointer_capture = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/PointerCaptureView.kt")
    pointer_touch = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/vision/PointerTouchTarget.kt")
    edge_safety = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/vision/EdgeSafety.kt")
    gesture_engine = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/GestureEngine.kt")
    recording = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/service/RecordingService.kt")
    metrics = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/OverlayWindowMetrics.kt")
    editor = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/EditorScreen.kt")
    scene_runner = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/runner/ScriptRunner.kt")
    ball_panel = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/BallPanel.kt")
    scene_verdict = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/SceneVerdict.kt")
    mismatch_guard = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/MismatchGuard.kt")
    loop_policy = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/model/LoopPolicy.kt")
    scene_assist = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/SceneAssist.kt")
    tap_gate = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/TapGate.kt")
    scene_list = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/SceneList.kt")
    pick_feedback = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/PickFeedback.kt")
    script = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/model/Script.kt")
    auto_click = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/AutoClickService.kt")
    screen_source = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/ScreenSource.kt")
    scene_frame = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/SceneFrame.kt")
    image_finder = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/vision/ImageFinder.kt")
    node_finder = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/NodeFinder.kt")
    compose_screens = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/Screens.kt")
    compose_editor = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/EditorScreen.kt")
    preflight = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/Preflight.kt")
    recorder_bus = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/recorder/RecorderBus.kt")
    scene_trigger = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/SceneTrigger.kt")
    scene_frame_policy = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/SceneFramePolicy.kt")
    step_policy = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/StepPolicy.kt")
    conn_state = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/ConnectionState.kt")
    announce_policy = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/AnnouncePolicy.kt")
    halt_report = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/HaltReport.kt")
    degrade_channel = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/DegradeChannel.kt")
    # v2.9.31 / ADR-049：装配-运行同源证据的五个真源
    scene_rule = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/model/SceneRule.kt")
    # v2.9.44 / ADR-062：无障碍节点 fallback（截图通道被录屏占用时的零截图触发源）
    node_fallback = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/NodeFallback.kt")
    v2944_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2944NodeFallbackTest.kt")
    # v2.9.45 / ADR-063：稳定性修复（原生句柄一次性守卫 + 日志并发写串行化）
    native_handle = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/NativeHandle.kt")
    quickjs_binding = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/runner/QuickJsAndroidBinding.kt")
    log_store = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/log/LogStore.kt")
    v2945_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2945StabilityFixTest.kt")
    # v2.9.46 / ADR-064：四类稳定性根因修复（无效帧冻结 / RunSession 唯一所有权 /
    # 识别热路径降本 / QuickJS 同步桥停止感知 + 在途句柄保护）
    run_token = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/RunToken.kt")
    v2946_frozen_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2946FrozenFrameTest.kt")
    v2946_token_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2946RunTokenTest.kt")
    v2946_inflight_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2946NativeHandleInFlightTest.kt")
    # v2.9.47 / 四通道降级 P0：四证据投票 + 离线证伪 + 关键帧稀疏采样的真源
    tap_evidence = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/video/TapEvidence.kt")
    replay_falsifier = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/video/ReplayFalsifier.kt")
    tap_detector = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/video/TapCandidateDetector.kt")
    keyframe_timeline = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/KeyframeTimeline.kt")
    video_extractor = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/VideoFrameExtractor.kt")
    video_import_dialog = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/VideoImportDialog.kt")
    v2947_evidence_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2947EvidenceVoteTest.kt")
    v2947_falsifier_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2947ReplayFalsifierTest.kt")
    # v2.9.48 / 减量+可裁决：ReviewLevel 单一真源 + 瞬态缩略缓存 + 新回归。
    frame_preview_cache = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/FramePreviewCache.kt")
    v2948_review_level_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2948ReviewLevelTest.kt")
    # v2.9.49 / 多特征框：拾取容器 + 单一真源 + 分析纯函数 + 浮层 + 对话框 + 新回归。
    feature_box = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/FeatureBox.kt")
    feature_box_analysis = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/FeatureBoxAnalysis.kt")
    roi_applier = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/RoiApplier.kt")
    coordinate_picker = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/CoordinatePicker.kt")
    picker_widgets = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/PickerWidgets.kt")
    editor_screen = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/EditorScreen.kt")
    # ADR-075：FeatureCapture 能力迁移后的单一工作台载体（框特征态）。
    scene_canvas_editor = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/SceneCanvasEditorDialog.kt")
    v2949_feature_box_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2949FeatureBoxTest.kt")
    v2949_analysis_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2949FeatureBoxAnalysisTest.kt")
    # v3.0.0 / SimpleTap 快路径 + 注入通道分级
    simple_tap = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/simpletap/SimpleTap.kt")
    injection_channel = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/simpletap/InjectionChannel.kt")
    shizuku_bridge = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/ShizukuBridge.kt")
    simple_tap_runner = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/runner/SimpleTapRunner.kt")
    v300_simple_tap_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V300SimpleTapTest.kt")
    v300_injection_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V300InjectionPolicyTest.kt")
    # v3.0.0 / ADR-068：截图取证链物理隔离目录（唯一合法取屏出口）
    legacy_screenshot_dir = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/legacy/screenshot")
    compat_fallback = os.path.join(legacy_screenshot_dir, "CompatFallback.kt")
    fallback_path_annotation = os.path.join(legacy_screenshot_dir, "FallbackPath.kt")
    # v3.0.1 / ADR-069：图片特征截取交互闭环（显式高级模式）
    feature_capture_core = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/capture/FeatureCapture.kt")
    feature_capture_dialog = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/FeatureCaptureDialog.kt")
    v3001_feature_capture_test = os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V300FeatureCaptureTest.kt")
    scene_evidence = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/SceneEvidence.kt")
    scene_self_test = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/SceneSelfTest.kt")
    stable_anchor = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/vision/StableAnchor.kt")
    floating_editor = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/service/FloatingEditorService.kt")
    # v2.9.32 / ADR-050：方向无关执行与判据参数精调的新增真源
    orientation_transform = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/OrientationTransform.kt")
    scene_tuning = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/SceneTuning.kt")
    # v2.9.33 / ADR-051：回切失败降级与 OCR 边界可见告警的新增真源
    restore_policy = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/runner/RestorePolicy.kt")
    script_model = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/model/Script.kt")
    record_compiler = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/recorder/RecordCompiler.kt")
    floating_layout = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/FloatingEditorLayout.kt")
    # v2.9.34 / ADR-052：OCR 类型隔离的新增真源（无坐标文字断言 / 无文字区域 /
    # 带置信度 CTC 解码 / 脱敏触发日志 / 条件判定去坐标依赖）。
    ocr_assertion = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/vision/OcrAssertion.kt")
    ocr_postprocess = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/ocr/OcrPostprocess.kt")
    onnx_ocr = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/OnnxOcrEngine.kt")
    # v2.9.37 / P1：TextBox.confidence 承载置信度的真源文件
    text_search = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/vision/TextSearch.kt")
    # v2.9.38 / 崩溃可见诊断版：全局钩子 + 独立进程错误页
    nymph_app = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/NymphApp.kt")
    crash_report = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/CrashReportActivity.kt")
    # v2.9.39 / 重名崩溃修复：启动迁移 + 列表 key 改用唯一文件名 id
    script_store = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/store/ScriptStore.kt")
    app_viewmodel = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/compose/AppViewModel.kt")
    # v2.9.40 / 数据完整性修复：编辑单位保真 + 导入坐标解析 + 临时文件精确清理
    action_edit_guard = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/ActionEditGuard.kt")
    atomic_text_file = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/store/AtomicTextFile.kt")
    line_importer = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/importer/LineImporter.kt")
    # v2.9.42 / ADR-060：历史快照 .naz 也走 AtomicTextFile，init 需接 cleanupStale(history)
    script_repository = os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/store/ScriptRepository.kt")

    def code_only(path: str) -> str:
        keep = []
        with open(path, encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s.startswith("*") or s.startswith("/*") or s.startswith("//"):
                    continue
                keep.append(line)
        return "".join(keep)

    svc_code = code_only(svc)
    panel_code = code_only(panel)
    runner_code = code_only(runner)
    spec_code = code_only(spec)
    picker_code = code_only(picker)
    widgets_code = code_only(picker_widgets)
    capture_code = code_only(pointer_capture)
    touch_target_code = code_only(pointer_touch)
    edge_safety_code = code_only(edge_safety)
    gesture_code = code_only(gesture_engine)
    recording_code = code_only(recording)
    metrics_code = code_only(metrics)
    editor_code = code_only(editor)
    scene_canvas_editor_code = code_only(scene_canvas_editor)
    scene_runner_code = code_only(scene_runner)
    scene_verdict_code = code_only(scene_verdict)
    mismatch_guard_code = code_only(mismatch_guard)
    loop_policy_code = code_only(loop_policy)
    scene_assist_code = code_only(scene_assist)
    tap_gate_code = code_only(tap_gate)
    scene_list_code = code_only(scene_list)
    pick_feedback_code = code_only(pick_feedback)
    script_code = code_only(script)
    auto_click_code = code_only(auto_click)
    node_finder_code = code_only(node_finder)
    screen_source_code = code_only(screen_source)
    scene_frame_code = code_only(scene_frame)
    ball_panel_code = code_only(ball_panel)
    conn_state_code = code_only(conn_state)
    announce_policy_code = code_only(announce_policy)
    halt_report_code = code_only(halt_report)
    degrade_channel_code = code_only(degrade_channel)
    scene_rule_code = code_only(scene_rule)
    node_fallback_code = code_only(node_fallback)
    native_handle_code = code_only(native_handle)
    quickjs_binding_code = code_only(quickjs_binding)
    log_store_code = code_only(log_store)
    run_token_code = code_only(run_token)
    scene_evidence_code = code_only(scene_evidence)
    scene_self_test_code = code_only(scene_self_test)
    stable_anchor_code = code_only(stable_anchor)
    floating_editor_code = code_only(floating_editor)
    orientation_transform_code = code_only(orientation_transform)
    scene_tuning_code = code_only(scene_tuning)
    floating_layout_code = code_only(floating_layout)
    restore_policy_code = code_only(restore_policy)
    script_model_code = code_only(script_model)
    ocr_assertion_code = code_only(ocr_assertion)
    ocr_postprocess_code = code_only(ocr_postprocess)
    onnx_ocr_code = code_only(onnx_ocr)
    text_search_code = code_only(text_search)
    nymph_app_code = code_only(nymph_app)
    crash_report_code = code_only(crash_report)
    script_store_code = code_only(script_store)
    app_viewmodel_code = code_only(app_viewmodel)
    compose_screens_code = code_only(compose_screens)
    action_edit_guard_code = code_only(action_edit_guard)
    atomic_text_file_code = code_only(atomic_text_file)
    line_importer_code = code_only(line_importer)
    script_repository_code = code_only(script_repository)
    record_compiler_code = code_only(record_compiler)
    image_finder_code = code_only(image_finder)
    preflight_code = code_only(preflight)
    recorder_bus_code = code_only(recorder_bus)
    scene_trigger_code = code_only(scene_trigger)
    scene_frame_policy_code = code_only(scene_frame_policy)
    step_policy_code = code_only(step_policy)
    tap_evidence_code = code_only(tap_evidence)
    replay_falsifier_code = code_only(replay_falsifier)
    tap_detector_code = code_only(tap_detector)
    keyframe_timeline_code = code_only(keyframe_timeline)
    video_extractor_code = code_only(video_extractor)
    video_import_dialog_code = code_only(video_import_dialog)
    frame_preview_cache_code = code_only(frame_preview_cache)
    # v2.9.49 / 多特征框
    feature_box_code = code_only(feature_box)
    feature_box_analysis_code = code_only(feature_box_analysis)
    roi_applier_code = code_only(roi_applier)
    # v3.0.0 / SimpleTap 快路径
    simple_tap_code = code_only(simple_tap)
    injection_channel_code = code_only(injection_channel)
    shizuku_bridge_code = code_only(shizuku_bridge)
    simple_tap_runner_code = code_only(simple_tap_runner)
    script_runner_code = code_only(runner)
    # v3.0.0 / ADR-068：截图取证链隔离（代码正文 + 目录外真实调用扫描）
    compat_fallback_code = code_only(compat_fallback)
    fallback_path_code = code_only(fallback_path_annotation)
    coordinate_picker_code = code_only(coordinate_picker)
    picker_widgets_code = code_only(picker_widgets)
    editor_screen_code = code_only(editor_screen)
    # v3.0.1 / ADR-069：图片特征截取交互闭环
    feature_capture_core_code = code_only(feature_capture_core)
    feature_capture_dialog_code = code_only(feature_capture_dialog)
    compose_screens_text = open(compose_screens, encoding="utf-8").read()
    compose_editor_text = open(compose_editor, encoding="utf-8").read()
    banned = ("Choreographer", "postFrameCallback", "frameScheduled", "pendingX", "pendingY")
    found = {k: (k in svc_code) for k in banned}
    # 旧的「监听器挂在滚动容器内子 View」方案必须消失
    no_old_listener = "attachDrag" not in panel_code
    # v2.9.10：改用 setOnTouchListener + dragListener（与 RunnerService 同款真机验证）
    uses_set_on_touch = "setOnTouchListener" in panel_code and "dragListener" in panel_code
    # 禁止回归 v2.9.9 的 broken idiom：根视图 onInterceptTouchEvent/onTouchEvent
    no_broken_intercept = ("onInterceptTouchEvent" not in panel_code) and ("onTouchEvent" not in panel_code)
    # v2.9.11：标题条必须整体移出 ScrollView——即「expanded.addView(bar」存在，
    # 且旧的「body.addView(bar」已删除（bar 不再嵌在 ScrollView 内部）。这是 v2.9.11
    # 真正修掉「位移能力未生效」的结构性修复点。
    bar_out_of_scroll = ("expanded.addView(bar" in panel_code) and ("body.addView(bar" not in panel_code)
    # v2.9.11：按下时禁止祖先（尤其 ScrollView）拦截 MOVE 的保险必须存在
    disallow_intercept = "requestDisallowInterceptTouchEvent" in panel_code
    sync = "commitDrag" in svc_code and "wm.updateViewLayout" in svc_code
    small = "dragStartPx" in panel_code
    # v2.9.18：运行球 / 编辑面板窗口必须带 FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS，
    # 与坐标拾取器、录制面板一致——这是悬浮窗可见但位移交互未生效的根因修复。
    # OverlayWindowSpec 是编辑面板 flags 单一真源，运行球在 RunnerService 内联定义，
    # 两处都必须确实含该标志（源字符串断言，防止仅改源码漏接进窗口）。
    runner_has_in_screen = "FLAG_LAYOUT_IN_SCREEN" in runner_code and "FLAG_LAYOUT_NO_LIMITS" in runner_code
    spec_has_in_screen = "FLAG_LAYOUT_IN_SCREEN" in spec_code and "FLAG_LAYOUT_NO_LIMITS" in spec_code
    layout_flags_wired = runner_has_in_screen and spec_has_in_screen
    # v2.9.19：窗口坐标空间为全屏绝对坐标，边界尺寸必须统一取自窗口指标单一真源。
    # 五处消费方（运行球、编辑面板、录制面板、坐标拾取器、编辑界面预览）都必须
    # 调用 OverlayWindowMetrics.screenSize，且不得再用应用窗口可用区尺寸做边界收敛。
    bound_consumers = (runner_code, svc_code, recording_code, picker_code, editor_code)
    unified_bounds = all("OverlayWindowMetrics.screenSize" in c for c in bound_consumers)
    app_area_bounds = (
        "displayMetrics.widthPixels" in runner_code or "displayMetrics.heightPixels" in runner_code
        or "displayMetrics.widthPixels" in svc_code or "displayMetrics.heightPixels" in svc_code
        or "displayMetrics.widthPixels" in recording_code or "displayMetrics.heightPixels" in recording_code
    )
    no_app_area_bounds = not app_area_bounds
    # 配置变化（横竖屏切换 / 折叠屏展开）后必须按相对锚点重定位，避免窗口越界不可见。
    rotation_reposition = (
        "onConfigurationChanged" in runner_code
        and "OverlayBounds.reposition" in runner_code
        and "OverlayBounds.reposition" in svc_code
    )
    bounds_source_single_truth = (
        "currentWindowMetrics" in metrics_code and "getRealMetrics" in metrics_code
    )
    # v2.9.20 重新发版：连续落点偏差护栏必须真实接线（累计 / 降频 / 确定性中止 /
    # 派发后复位），且 core 契约本身带终止上界。计数改为按规则独立的 Tracker，
    # 并显式登记场景离场保持态（onIdle），防止「离场即复位」令周期性出现的
    # 系统性偏移永远达不到中止上界，同时防止多规则脚本互相掩盖失配。
    # PHASE 3 S3：规则索引由 idx 重命名为槽位 slot（rule→spec 映射），功能语义不变；
    # 门禁同时接受新命名 slot 与旧命名 idx，避免命名漂移误判为护栏退化。
    mismatch_guard_wired = (
        "MismatchGuard.Tracker()" in scene_runner_code
        and ("mismatchTracker.onMismatch(slot)" in scene_runner_code
             or "mismatchTracker.onMismatch(idx)" in scene_runner_code)
        and ("mismatchTracker.onFired(slot)" in scene_runner_code
             or "mismatchTracker.onFired(idx)" in scene_runner_code)
        and "mismatchTracker.onIdle()" in scene_runner_code
        and "Action.ABORT" in scene_runner_code
        and "ABORT_AFTER" in mismatch_guard_code
        and "class Tracker" in mismatch_guard_code
        and "onIdle" in mismatch_guard_code
        and "countFor" in mismatch_guard_code
    )
    # 全局单例计数必须已消除：不得再出现裸 onMismatch(局部 Int) 接线。
    mismatch_per_rule_isolation = (
        "consecutiveMismatch" not in scene_runner_code
        and "onMismatch(ruleIndex" in mismatch_guard_code
        and "onFired(ruleIndex" in mismatch_guard_code
    )
    # 场景范式须显式告知：不提供次数维度上限，按次数收敛请配置中止条件。
    scene_stop_guidance = (
        "不提供次数维度上限" in loop_policy_code
        and "配置中止条件" in loop_policy_code
    )
    # v2.9.20：循环次数作用域必须由 core 单一真源声明；场景范式收尾不得再把
    # 脚本级 repeatCount 呈现为轮次总量（RunStats.summary 的 total 参数）。
    loop_scope_wired = (
        "LoopPolicy.sceneDrivenNotice" in scene_runner_code
        and "LoopPolicy.labelFor" in editor_code
        and "LoopPolicy.scopeOf" in editor_code
        and "LoopPolicy.appliesTo" in editor_code
        and "LoopScope" in loop_policy_code
        and "sceneSummary(firedRounds)" in scene_runner_code
        and "roundSummary(firedRounds" not in scene_runner_code
    )
    # v2.9.20：判定结论与画面门控挂载是两条独立依据，状态栏须显式呈现挂载事实；
    # PASS 结论不得在装配阶段提前声称门控已启用。
    # v2.9.25：对齐口径升级 —— 不再只检查「结论与挂载状态同屏呈现」，
    # 而是要求门控判定本身消费锚点可用性，并保留挂载状态的可读文案。
    # v2.9.36：真实 debug 包暴露「LOW_DISTINCTNESS 锚点数学上恒不触发，界面却显示
    # 可触发」的误导。对齐口径再升级 —— 要求 verdict.usable=false 时状态栏不得
    # 给出「可触发」暗示，而是明确「运行期不会触发/可能不触发」，同时保留挂载
    # 状态的可读文案（门控挂载与否仍须可见）。
    verdict_gate_alignment = (
        "anchorReady" in svc_code
        and "画面门控已挂载" in svc_code
        and "可启用画面门控" in scene_verdict_code
        and "已启用画面门控" not in scene_verdict_code
        and "!v.usable" in svc_code
        and "运行期不会触发" in svc_code
    )
    # v2.9.37 / P0 截图单飞锁泄漏：awaitShot 的 submit+await 必须全纳入 try/finally，
    # 解锁统一走 token CAS 的 release()（代次隔离 + 幂等），CancellationException 重抛。
    # 杜绝「协程取消时 shotInFlight 不复位 → 永久 BUSY 无帧不熔断」与「旧回调误释放
    # 新请求锁」两个竞态。
    shot_release_finally_wired = (
        "fun release()" in auto_click_code
        and "token.compareAndSet(true, false)" in auto_click_code
        and "shotInFlight.set(false)" in auto_click_code
        and "catch (e: kotlinx.coroutines.CancellationException)" in auto_click_code
        and "throw e" in auto_click_code
        and "} finally {" in auto_click_code
        # 解锁必须收敛到 release()，不得再有裸 token.set(false) + shotInFlight.set(false) 配对
        and "token.set(false)\n                        shotInFlight.set(false)" not in auto_click_code
    )
    # v2.9.37 / P1 OCR 置信度断链：ctcGreedyScored.confidence 必须从 TextBox.confidence
    # 贯通到 OcrTextAssertion.from 与 ocr_triggered 日志，不再硬编码 -1.0 抹掉真实值。
    ocr_confidence_wired = (
        "val confidence: Double = UNAVAILABLE_CONFIDENCE" in text_search_code
        and "OcrTextAssertion(box.text, box.confidence, matcherId)" in ocr_assertion_code
        and "ctcGreedyScored(logits, steps, classes, keys)" in ocr_postprocess_code
        and 'Decoded("", 0.0)' in ocr_postprocess_code
        and "val decoded = recognizeLine" in onnx_ocr_code
        and "decoded.confidence" in onnx_ocr_code
        and "val confidence = best?.confidence" in screen_source_code
        and "emitOcrTriggered(matcher, hit = best != null, confidence = confidence)" in screen_source_code
    )
    # v2.9.38 / 崩溃可见诊断：全局钩子在写盘后必须拉起独立进程错误页，
    # 且错误页自身崩溃时不再拉起（防 :crash 进程循环）；Manifest 声明独立进程。
    crash_report_wired = (
        "LogStore.writeCrash(thread.name, ex)" in nymph_app_code
        and "CrashReportActivity.isCrashProcess(this)" in nymph_app_code
        and "CrashReportActivity.launch(this, text)" in nymph_app_code
        and "previous?.uncaughtException(thread, ex)" in nymph_app_code
        and "fun isCrashProcess" in crash_report_code
        and "endsWith(\":crash\")" in crash_report_code
        and "android:process=\":crash\"" in open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    )
    # v2.9.39 / 重名崩溃修复：两道防线都必须接线——
    # ① 存储层启动幂等迁移，给重复 name 的旧文件追加后缀写回，name 恢复唯一主键；
    # ② UI 携带文件名 id 且 LazyColumn key 用 id 而非 name，迁移遗漏也绝不崩。
    # code_only 已剔除注释行，故断言只针对真实代码，避免「注释里写了」蒙混过关。
    duplicate_name_key_wired = (
        "fun migrateDuplicateNames()" in script_store_code
        and "migrateDuplicateNames()" in script_store_code  # init 中实际调用
        and "used.add(item.name)" in script_store_code       # 保留最新、改名其余
        and "val id: String" in app_viewmodel_code           # ScriptUi 携带唯一 id
        and "id = item.file.name" in app_viewmodel_code      # id 取文件名（天然唯一）
        and "items(filtered, key = { it.id })" in compose_screens_code  # key 用 id
        and "key = { it.name }" not in compose_screens_code  # 不得回退到 name
    )
    # v2.9.40 / 数据完整性修复：三份审查文档逐条甄别后只修 3 条确证真 bug。
    # P0-01 编辑单位保真（保留 original.unit、上限随单位走、不得写死 PCT）；
    # P0-09 导入坐标解析失败丢弃（parsePercent 返回 null、isFinite 校验、不得补 0）；
    # P0-15 临时文件精确匹配（STALE_TMP 正则、不得再 contains(".tmp") 子串）。
    v2940_data_integrity_wired = (
        # P0-01：单位保真
        "val unit = original.unit" in action_edit_guard_code
        and "CoordUnit.PX, CoordUnit.DP -> MAX_PIXEL_COORD" in action_edit_guard_code
        and "unit = unit," in action_edit_guard_code
        and "unit = CoordUnit.PCT," not in action_edit_guard_code  # 不得回退到写死 PCT
        # P0-09：解析失败丢弃
        and "private fun parsePercent(raw: String?): Double?" in line_importer_code
        and "v.isFinite() && v >= 0.0" in line_importer_code
        and "parsePercent(pos[\"x\"]?.jsonPrimitive?.contentOrNull) ?: return null" in line_importer_code
        # P0-15：临时文件精确清理
        and "private val STALE_TMP = Regex" in atomic_text_file_code
        and "STALE_TMP.matches(it.name)" in atomic_text_file_code
        and "it.name.contains(TMP_SUFFIX)" not in atomic_text_file_code  # 不得回退到子串匹配
    )
    # v2.9.41 / 2.9.40 审查回执确证真 bug：① 滑动终点解析失败/缺失丢整条；
    # ③ STALE_TMP 两分支锚定 .json.tmp（不得回退到宽泛 .+\.tmp$）；
    # ④ 重名迁移 delete 失败告警 + .orphan 改名隔离；
    # ⑤a 崩溃页 /proc/self/cmdline 回退 + 纯判定函数（防 ROM API 失效致防循环失效）。
    v2941_data_integrity_hardening = (
        # ①：滑动终点与起点同款语义（终点 x/y 解析失败即 return null）
        'parsePercent(end["x"]?.jsonPrimitive?.contentOrNull) ?: return null' in line_importer_code
        and 'parsePercent(end["y"]?.jsonPrimitive?.contentOrNull) ?: return null' in line_importer_code
        and '?: obj["posData2"]?.jsonObject' in line_importer_code
        and '?: return null' in line_importer_code
        # ③：两分支锚定 <target>.tmp（v2.9.42 起 target 后缀为 json|naz），不得回退到宽泛老格式分支
        and r'\.(json|naz)\.tmp-[0-9a-z-]+-[0-9a-z]+|.+\.(json|naz)\.tmp)$' in atomic_text_file_code
        and r'.+\.tmp)$' not in atomic_text_file_code
        # ④：delete 失败告警 + .orphan 改名隔离
        and "删除失败，尝试改名隔离" in script_store_code
        and ".orphan-" in script_store_code
        and "dev.nymph.auto.core.log.RunLog.w" in script_store_code
        # ⑤a：/proc 回退 + 纯判定函数（endsWith 已在 crash_report_wired 校验）
        and "/proc/self/cmdline" in crash_report_code
        and "fun isCrashProcessName" in crash_report_code
    )
    # v2.9.42 / 2.9.41 审查回执确证真 bug + 补测：
    # ③-a STALE_TMP 正则覆盖 .naz 快照临时文件，错误事实（"都以 .json 结尾"）已从注释移除，
    #     ScriptRepository.init 接 cleanupStale(history)，SUFFIX 仍为 .naz；
    # ③-b 起点不再 `x<=0&&y<=0` 丢弃（终点侧保留逻辑仍由 v2941 门禁锁定）；
    # ② ④⑤a 补测文件存在（core 的 V2942 + app 的 CrashReportActivityTest）。
    v2942_snapshot_and_coord_wired = (
        # ③-a：正则覆盖 (json|naz) 两类写入目标，注释错误事实已清
        r'\.(json|naz)\.tmp-' in atomic_text_file_code
        and r'.+\.(json|naz)\.tmp)$' in atomic_text_file_code
        and "都以 `.json` 结尾" not in open(atomic_text_file, encoding="utf-8").read()
        and "写入 `.history/<name>.r<N>.naz`" in open(atomic_text_file, encoding="utf-8").read()
        # ③-a：history 目录接清理 + 快照后缀仍为 .naz
        and "AtomicTextFile.cleanupStale(history)" in script_repository_code
        and 'SUFFIX = ".naz"' in script_repository_code
        # ③-b：起点双零丢弃已移除（与终点合法零保留统一）
        and "if (x <= 0.0 && y <= 0.0) return null" not in line_importer_code
        # ②：④⑤a 补测文件存在
        and os.path.isfile(os.path.join(ROOT, "core/src/test/java/dev/nymph/auto/core/regression/V2942FixRegressionTest.kt"))
        and os.path.isfile(os.path.join(ROOT, "app/src/test/java/dev/nymph/auto/ui/CrashReportActivityTest.kt"))
    )

    # v2.9.43 / ADR-061：「不管何时都不和录屏抢权限」源码级永久门禁。
    # 核心事实：工程截图只走 AccessibilityService.takeScreenshot()（无障碍），
    # 全链路零 MediaProjection。README 声称 8 类录屏符号引用为 0，但此前门禁
    # 只在 dex 层锁 7 项、缺 createScreenCaptureIntent 等授权入口，且无源码级
    # 拦截——与 v2.9.41「错误事实焊进正则」同类：断言写在文档、门禁没钉死。
    # 本门禁扫描 core/app 全量 main 源码的「非注释代码行」：
    #  - 精确 API 标识符（MediaProjectionManager / createScreenCaptureIntent /
    #    getMediaProjection / ACTION_MEDIA_PROJECTION / android.media.projection /
    #    createScreenCapture）必须零引用。这些是真实调用/意图动作形态。
    #  - 刻意不扫裸词 MediaProjection / VirtualDisplay：工程大量中文说明文案
    #    （「零 MediaProjection」「不创建 VirtualDisplay」）合法出现，裸词扫描
    #    会误伤；这两类由 dex 层 BANNED 按类型描述符字节锁定，互补覆盖。
    #  - 正向锁定无障碍截图通道仍在：canTakeScreenshot=true 且未开触摸浏览模式。
    screen_capture_api_identifiers = (
        "MediaProjectionManager", "createScreenCaptureIntent", "getMediaProjection",
        "ACTION_MEDIA_PROJECTION", "android.media.projection", "android/media/projection",
        "createScreenCapture",
    )
    # ADR-072 / v3.0.2：双取帧模式（争抢录屏权限模式为可选档之一）。
    # MediaProjection API 从「全局禁用」改为「隔离式白名单」：仅允许存在于专用取帧模块，
    # 其余源码仍为零。白名单外任何引用一律红——比全局禁用更严格地约束了使用范围。
    # 默认模式仍是无障碍截图（FrameSourceSelector.mode=ACCESSIBILITY），不争抢。
    mp_whitelist_names = {"MediaProjectionSource.kt", "HudCaptureService.kt", "FrameSourceSelector.kt"}
    screen_capture_hits: list[str] = []
    mp_whitelist_seen: set[str] = set()
    for _kt in (
        glob.glob(os.path.join(ROOT, "core/src/main/**/*.kt"), recursive=True)
        + glob.glob(os.path.join(ROOT, "app/src/main/**/*.kt"), recursive=True)
    ):
        _body = code_only(_kt)
        _base = os.path.basename(_kt)
        for _api in screen_capture_api_identifiers:
            if _api in _body:
                if _base in mp_whitelist_names:
                    mp_whitelist_seen.add(_base)
                else:
                    screen_capture_hits.append(_base + ":" + _api)
    a11y_cfg = open(
        os.path.join(ROOT, "app/src/main/res/xml/accessibility_service_config.xml"),
        encoding="utf-8",
    ).read()
    # ADR-072：隔离式白名单——MP API 只允许在专用取帧模块；白名单外零引用；
    # 白名单模块必须真实承载这些 API（防止把功能摘掉还让门禁变绿）；
    # 默认模式必须仍是无障碍（不争抢录屏权限）。三条同时成立才算过。
    # 取帧模式选择器与 MediaProjectionSource 同文件（FrameMode / FrameSourceSelector 定义在
    # MediaProjectionSource.kt 内），默认模式必须为无障碍（不争抢录屏权限）。
    selector_path = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/MediaProjectionSource.kt")
    selector_code = open(selector_path, encoding="utf-8").read() if os.path.isfile(selector_path) else ""
    v2943_no_screen_capture_api = (
        not screen_capture_hits
        # MP API 实际使用点在取帧源模块（白名单命中）
        and "MediaProjectionSource.kt" in mp_whitelist_seen
        # 宿主前台服务与模式选择器真实存在（双模式骨架未被摘掉）
        and os.path.isfile(os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/service/HudCaptureService.kt"))
        and "FrameMode.ACCESSIBILITY" in selector_code
        and "android:canTakeScreenshot=\"true\"" in a11y_cfg
        and "android:canRequestTouchExplorationMode=\"false\"" in a11y_cfg
    )

    # v3.0.0 / ADR-068：截图取证链物理隔离门禁（迁移期三层隔离的编译期验证）。
    # 与 v2943 不冲突：v2943 锁「零 MediaProjection 抢录屏 + 无障碍截图能力仍在」，
    # 本门禁锁「新链零截图调用 + 唯一出口在 legacy + 降级路径显式标记」。
    #
    # 层1（源码隔离）：legacy/screenshot/ 目录外，任何 main 源码真实调用取屏 API → 红。
    #   只扫真实调用形态（takeScreenshot( / PixelCopy. / ImageReader.），不扫注释文案。
    # 层2（单一出口）：CompatFallback 存在且含唯一 takeScreenshot 调用 + @FallbackPath + @Deprecated。
    # 层3（标记可见）：降级调用点标 @FallbackPath（AutoClickService.screenshotBitmap）。
    _take_capture_call = ("takeScreenshot(", "PixelCopy.", "ImageReader.", "screencap ")
    # 剥离双引号字符串字面量，避免把 UI 文案（如「只用无障碍 takeScreenshot()」）误判为调用。
    import re as _re
    _string_literal = _re.compile(r'"(?:[^"\\]|\\.)*"')
    legacy_outside_hits: list[str] = []
    for _kt in (
        glob.glob(os.path.join(ROOT, "core/src/main/**/*.kt"), recursive=True)
        + glob.glob(os.path.join(ROOT, "app/src/main/**/*.kt"), recursive=True)
    ):
        _norm = os.path.normpath(_kt)
        if os.path.normpath(legacy_screenshot_dir) in _norm:
            continue  # legacy 目录内允许（唯一出口）
        # ADR-072：MediaProjection 取帧源用 ImageReader.newInstance 接收虚拟显示帧，
        # 属第二种合法取帧模式（用户显式选择），与无障碍截图出口并列。白名单放行。
        if os.path.basename(_kt) == "MediaProjectionSource.kt":
            continue
        _body = _string_literal.sub('""', code_only(_kt))
        for _api in _take_capture_call:
            if _api in _body:
                legacy_outside_hits.append(os.path.basename(_kt) + ":" + _api)
    v3000_screenshot_quarantined = (
        os.path.isdir(legacy_screenshot_dir)
        and os.path.isfile(compat_fallback)
        and os.path.isfile(fallback_path_annotation)
        and "takeScreenshot(displayId, executor, callback)" in compat_fallback_code
        and "@FallbackPath" in compat_fallback_code
        and "@Deprecated" in compat_fallback_code
        and "forRemoval = true" in compat_fallback_code
        and "@FallbackPath(" in auto_click_code
        and "CompatFallback.submitShot" in auto_click_code
        # 层1：legacy 目录外零真实取屏调用
        and not legacy_outside_hits
    )
    # v3.0.1 / ADR-069：图片特征截取交互闭环（显式高级模式，补竞品 73-83s 那条可操作链）。
    # 锁四层接线：core 纯几何 / UI 四步闭环 / 编辑器入口+挂载 / 落盘 Action 对接既有判定链。
    # 与隔离门禁不冲突：抓帧仍走 screenshotBitmap() → CompatFallback 唯一出口，
    # 本门禁只保证「用户能截取→存特征→缩略图可查看→相似度/限制区域判定」不被悄悄摘掉。
    v3001_feature_capture_wired = (
        # 层1：core 纯 JVM 几何（裁剪映射 / 越界钳制 / 相似度收敛 / region 单一真源）
        os.path.isfile(feature_capture_core)
        and "fun cropRect(" in feature_capture_core_code
        and "fun clampSimilarity(" in feature_capture_core_code
        and "fun toRegion(" in feature_capture_core_code
        and "fun isUsable(" in feature_capture_core_code
        # 层2：UI 四步闭环（框选 → 抓帧 → 原画质裁剪编码 → 缩略图）
        and os.path.isfile(feature_capture_dialog)
        and "CoordinatePicker.show(" in feature_capture_dialog_code
        and "PickerMode.REGION" in feature_capture_dialog_code
        and "screenshotBitmap()" in feature_capture_dialog_code
        and "Bitmap.createBitmap(bmp" in feature_capture_dialog_code
        and "Bitmap.CompressFormat.PNG" in feature_capture_dialog_code
        and "Base64.encodeToString" in feature_capture_dialog_code
        and "asImageBitmap()" in feature_capture_dialog_code
        and "Slider(" in feature_capture_dialog_code
        # 层3：编辑器入口 + 挂载。
        # ADR-075 D6：FeatureCaptureDialog 弹窗调用停用，能力整体并入单一工作台
        # SceneCanvasEditorDialog 的「框特征」态。门禁锁定迁移后的等价入口，
        # 不再要求已停用的旧弹窗（WorkbenchRefactorGateTest 反向锁旧调用数=0）。
        and "showSceneCanvas" in editor_screen_code
        and "SceneCanvasEditorDialog(" in editor_screen_code
        and "场景工作台" in editor_screen_code
        and "框特征" in scene_canvas_editor_code
        # 层4：落盘 Action(type=CLICK_IMAGE) 对接既有判定链 + fail-loud + 回归测试
        and "ActionType.CLICK_IMAGE" in feature_capture_dialog_code
        and "imageBase64" in feature_capture_dialog_code
        and "无障碍服务未连接" in feature_capture_dialog_code
        and "无悬浮窗权限" in feature_capture_dialog_code
        and os.path.isfile(v3001_feature_capture_test)
    )
    # v2.9.44 / ADR-062：场景驱动无障碍节点 fallback —— 截图通道被系统录屏占用时
    # 改走零截图节点树判定「场景出现」。四层接线 + 不回退断言。
    v2944_node_fallback_wired = (
        # 1) schema：四字段带默认值，schemaVersion 不升（零回归）
        "val nodeText: String = \"\"" in scene_rule_code
        and "val nodeViewId: String = \"\"" in scene_rule_code
        and "val nodeClass: String = \"\"" in scene_rule_code
        and "val nodeFallbackEnabled: Boolean = false" in scene_rule_code
        and "SCHEMA_VERSION = 1" in script_code
        # 2) 纯函数：判定/匹配/观测构造 + NODE 层级
        and "fun applicable(rule: SceneRule): Boolean" in node_fallback_code
        and "fun matches(signal: NodeSignal, rule: SceneRule): Boolean" in node_fallback_code
        and "fun toObservation(signal: NodeSignal, frameW: Int, frameH: Int): SceneObservation" in node_fallback_code
        and "NODE_HIT_SCORE" in node_fallback_code
        and "NODE, NONE" in scene_evidence_code
        # 3) 装配期：拾取瞬间捕获节点语义（两处 SceneRule 构造都接线）
        and "captureNodeSignal(pickXPx, pickYPx)" in floating_editor_code
        and "findSmallestNodeAt(px, py)" in floating_editor_code
        and floating_editor_code.count("nodeFallbackEnabled = nodeSignal != null") >= 2
        # 4) 运行期：截图失败 / NCC 未命中走节点 fallback，命中落点用节点中心
        and "fun tryNodeFallback(i: Int)" in scene_runner_code
        and "NodeFallback.applicable(rule)" in scene_runner_code
        and "nodeFinder.find(rule.nodeViewId, rule.nodeClass, rule.nodeText, 0, 0)" in scene_runner_code
        and ("nodeFiredThisRound[slot]" in scene_runner_code
             or "nodeFiredThisRound[idx]" in scene_runner_code)
        and "Tier.NODE" in scene_runner_code
        # 5) 回归测试存在
        and os.path.isfile(v2944_test)
        # 6) 不回退：录屏门禁仍在、BANNED 仍 13 项
        and len(BANNED) == 13
    ) and v2943_no_screen_capture_api
    # v2.9.45 / ADR-063：稳定性修复双层接线门禁。
    # ① 原生句柄一次性守卫：NativeHandle 用 AtomicLong.getAndSet 保证 destroy 恰好一次；
    #    QuickJsAndroidBinding 用它，dispose 幂等；RunnerService 主线程停止链路只中断
    #    + 取消，真正销毁收敛到脚本 finally + invokeOnCompletion 兜底（局部引用捕获）。
    # ② 日志并发写串行化：LogStore.append/tail/clear/init 全部 @Synchronized。
    v2945_stability_wired = (
        # 1) 纯 JVM 守卫本体
        "class NativeHandle" in native_handle_code
        and "AtomicLong" in native_handle_code
        and "ref.getAndSet(0L)" in native_handle_code
        and "if (old != 0L) destroyer(old)" in native_handle_code
        # 2) QuickJs 绑定改用守卫，裸 Long ptr 消失，dispose 幂等
        and "NativeHandle(created)" in quickjs_binding_code
        and "handle.disposeOnce" in quickjs_binding_code
        # v2.9.46 把裸 handle.get() 加固为 handle.useIfAlive（读锁在途保护），
        # 二者都表明 QuickJs 经守卫访问 native 指针，均算通过（加固不回退）。
        and ("handle.get()" in quickjs_binding_code or "handle.useIfAlive" in quickjs_binding_code)
        # 3) RunnerService：主线程不再直接 dispose，completion 兜底存在
        and "job.invokeOnCompletion" in runner_code
        and "this@RunnerService.quickJs === quickJs" in runner_code
        # stopAll / handover 两处停止链路不再有 quickJs?.dispose()（保留停止调用：
        # v2.9.45 为 requestInterrupt，v2.9.46 升级为 requestStop，二者都算）
        and (runner_code.count("quickJs?.requestInterrupt()") + runner_code.count("quickJs?.requestStop()")) >= 2
        # 4) 日志串行化：append / tail / clear 都带 @Synchronized
        and "@Synchronized\n    private fun append" in log_store_code.replace("\r", "")
        and "@Synchronized\n    fun tail" in log_store_code.replace("\r", "")
        and "@Synchronized\n    fun clear" in log_store_code.replace("\r", "")
        # 5) 并发回归测试存在
        and os.path.isfile(v2945_test)
        # 6) 不回退：v2944 / v2943 门禁仍在，BANNED 仍 13 项
        and v2944_node_fallback_wired
        and v2943_no_screen_capture_api
        and len(BANNED) == 13
    )
    # v2.9.46 / ADR-064：四类根因修复接线门禁。
    # ① 无效帧冻结：SceneObservation.valid 三态 + SceneTrigger 冻结分支 +
    #    ScriptRunner 截图失败/几何错配标 invalid，杜绝 ON_DISAPPEAR 把「没拍到」当离场。
    # ② RunSession 唯一所有权：core RunToken 单调会话号 + RunnerService 所有延迟收尾
    #    以 isCurrent(myRunId) 守卫，停止/交接先 invalidate；运行中拒绝重复 ACTION_START。
    # ③ 识别热路径降本：模板解码缓存 + 仅掩码规则复制整帧。
    # ④ QuickJS 同步桥：NativeHandle 读写锁在途保护 + 按 API 拆分超时 + 停止感知轮询。
    v2946_stability_wired = (
        # 1) 无效帧三态 + 冻结状态机
        "val valid: Boolean = true" in scene_rule_code
        and "if (!o.valid)" in scene_trigger_code
        and "冻结状态机" in scene_trigger_code
        and "SceneObservation(present = false, valid = false)" in scene_runner_code
        and "valid = m.score >= 0.0" in scene_runner_code
        # 2) RunSession 唯一所有权（RunToken 纯 JVM 本体 + RunnerService 接线）
        and "class RunToken" in run_token_code
        and "fun next()" in run_token_code
        and "fun isCurrent" in run_token_code
        and "fun invalidate()" in run_token_code
        and "runToken.next()" in runner_code
        and runner_code.count("runToken.invalidate()") >= 2
        and runner_code.count("runToken.isCurrent(myRunId)") >= 4
        and runner_code.count("quickJs?.requestStop()") >= 2
        # 3) 识别热路径：模板缓存 + 按掩码条件复制
        and "templateCache" in screen_source_code
        and "decodeTemplate" in screen_source_code
        and "spec.maskRects.isEmpty()" in screen_source_code
        # 4) QuickJS 在途保护 + 停止感知 + 拆分超时
        and "fun <T> useIfAlive" in native_handle_code
        and "rw.readLock()" in native_handle_code
        and "rw.writeLock()" in native_handle_code
        and quickjs_binding_code.count("handle.useIfAlive") >= 5
        and "fun requestStop()" in quickjs_binding_code
        and "fun <T> runBlockingSync(timeoutMs: Long" in quickjs_binding_code
        and "STOP_POLL_MS" in quickjs_binding_code
        # 5) 三例纯 JVM 回归存在
        and os.path.isfile(v2946_frozen_test)
        and os.path.isfile(v2946_token_test)
        and os.path.isfile(v2946_inflight_test)
        # 6) 不回退：v2945 / v2944 / v2943 门禁仍在，BANNED 仍 13 项
        and v2945_stability_wired
        and v2944_node_fallback_wired
        and v2943_no_screen_capture_api
        and len(BANNED) == 13
    )
    # v2.9.47 / 四通道降级 P0 静态接线门禁。确认「任意通道缺席不影响最终产物」的
    # 不变式确实落在源码而非设计文档：四证据投票可缺席增强、产物格式不变、
    # 每条规则生成期离线回放一次。
    v2947_four_channel_wired = (
        # 1) 四证据投票本体：四证据种类 + 时空对齐 + 投票融合 + 单票待确认/冲突。
        "enum class Kind" in tap_evidence_code
        and "SWIPE" in tap_evidence_code
        and "TAP_FINGER" in tap_evidence_code
        and "RIPPLE" in tap_evidence_code
        and "OCCLUSION" in tap_evidence_code
        and "alignTimeMs" in tap_evidence_code
        and "alignWidthPct" in tap_evidence_code
        and "fun fuse" in tap_evidence_code
        and "needsReview = conflict" in tap_evidence_code
        and "fun isDenseRegion" in tap_evidence_code
        # 2) 离线回放证伪器：前置从未满足判不可执行 + 后置窗口 + 概率标定 + 两入口。
        and "REVIEW_THRESHOLD" in replay_falsifier_code
        and "fun falsify" in replay_falsifier_code
        and "fun falsifyTaps" in replay_falsifier_code
        and "fun observeFrames" in replay_falsifier_code
        and "executable = false" in replay_falsifier_code
        and "前置条件在动作帧" in replay_falsifier_code
        and "postScore * 0.35" in replay_falsifier_code
        # 3) 增强替换：detect 对外 API 保留，新增 detectWithEvidence（产物格式超集）。
        and "fun detect(frames: List<FrameSample>" in tap_detector_code
        and "fun detectWithEvidence" in tap_detector_code
        and "TapEvidence.fuse" in tap_detector_code
        and "EvidenceCandidate" in tap_detector_code
        # 4) app 接线：MediaExtractor 读关键帧（零解码）+ 稀疏采样 + 生成期离线回放。
        and "MediaExtractor" in keyframe_timeline_code
        and "SAMPLE_FLAG_SYNC" in keyframe_timeline_code
        and "fun read" in keyframe_timeline_code
        and "fun sampleFramesAdaptive" in video_extractor_code
        and "isDenseRegion" in video_extractor_code
        and "KeyframeTimeline.read" in video_import_dialog_code
        and "detectWithEvidence" in video_import_dialog_code
        and "ReplayFalsifier.falsifyTaps" in video_import_dialog_code
        # 5) 两例纯 JVM 回归存在。
        and os.path.isfile(v2947_evidence_test)
        and os.path.isfile(v2947_falsifier_test)
        # 6) 不回退：v2946/v2945/v2944/v2943 门禁仍在，BANNED 仍 13 项。
        and v2946_stability_wired
        and v2945_stability_wired
        and v2944_node_fallback_wired
        and v2943_no_screen_capture_api
        and len(BANNED) == 13
    )
    # v2.9.48 / 减量+可裁决静态接线门禁。确认"待确认不再恒真、列表可减量、
    # 每条可裁决"落在源码而非设计文档。断言取自 code_only 后的代码行。
    v2948_ui_triage_wired = (
        # 1) 强度感知谓词：n<2 恒真被替换为"单票且弱"，阈值可调。
        "strongSingleStrength" in tap_evidence_code
        and "strengthAvg < opts.strongSingleStrength" in tap_evidence_code
        and "needsReview = conflict" in tap_evidence_code
        and "val strengthAvg: Double" in tap_evidence_code
        # 2) ReviewLevel 单一真源：四档枚举 + 纯函数，UI 与放大图共用。
        and "enum class ReviewLevel" in tap_evidence_code
        and "WEAK_SINGLE" in tap_evidence_code
        and "NEEDS_REVIEW" in tap_evidence_code
        and "fun levelOf" in tap_evidence_code
        and "TapEvidence.levelOf" in video_import_dialog_code
        and "strengthAvg = fu.strengthAvg" in tap_detector_code
        # 3) 瞬态缩略缓存：JPEG byte[]、LRU、不进 Result/dismiss 清空、按需解码。
        and "LinkedHashMap" in frame_preview_cache_code
        and "removeEldestEntry" in frame_preview_cache_code
        and "fun clear" in frame_preview_cache_code
        and "fun putThumb" in frame_preview_cache_code
        and "fun frameFullAt" in video_extractor_code
        and "FramePreviewCache.getThumb" in video_import_dialog_code
        # 4) app UI：证伪前置 + 诊断条 + 重录引导 + 折叠 + 批量 + 拖拽修正。
        and "ReplayFalsifier.falsifyTaps" in video_import_dialog_code
        and "verdictsFound" in video_import_dialog_code
        and "证据命中" in video_import_dialog_code
        and "显示点按操作" in video_import_dialog_code
        and "expandAll" in video_import_dialog_code
        and "展开全部" in video_import_dialog_code
        and "选正常" in video_import_dialog_code
        and "忽略待确认" in video_import_dialog_code
        and "ZoomCandidateDialog" in video_import_dialog_code
        and "detectDragGestures" in video_import_dialog_code
        and "corrected[i]" in video_import_dialog_code
        # 5) 新回归存在。
        and os.path.isfile(v2948_review_level_test)
        # 6) 不回退：v2947/v2946/v2945/v2944/v2943 门禁仍在，BANNED 仍 13 项。
        and v2947_four_channel_wired
        and v2946_stability_wired
        and v2945_stability_wired
        and v2944_node_fallback_wired
        and v2943_no_screen_capture_api
        and len(BANNED) == 13
    )
    # v2.9.49 / 多特征框：框选 ROI 重构为多特征框容器（ADR-010 唯一真源扩展）。
    v2949_multifeature_box_wired = (
        # 1) core 数据模型 + 分析纯函数。
        "data class FeatureBox" in feature_box_code
        and "DEFAULT_SIMILARITY = 85" in feature_box_code
        and "fun overlapRatio" in feature_box_analysis_code
        and "fun textureStrength" in feature_box_analysis_code
        # 2) 唯一真源：applyMulti 写 region + imageSimilarity 全集。
        # v3.0.0：取消短边轮转，框数!=目标数且多框时明确拒绝（fail-loud）。
        and "fun applyMulti" in roi_applier_code
        and "imageSimilarity = newSim" in roi_applier_code
        and "fun applyMultiChecked" in roi_applier_code
        and "data class MultiApplyResult" in roi_applier_code
        and "boxes.size != targets" in roi_applier_code
        # 3) 浮层多框会话：多框开关 + 加框/删框 + 一次提交 + 已落定框显示。
        and "multiBox: Boolean = false" in coordinate_picker_code
        and "onRegionsPicked" in coordinate_picker_code
        and "fun deliverRegions" in coordinate_picker_code
        and "HandleAction.ADD_BOX -> addBox()" in coordinate_picker_code
        and "HandleAction.DEL_BOX -> delBox()" in coordinate_picker_code
        and "overlay.confirmedRects" in coordinate_picker_code
        # 4) 点拾取方向键零回归：UP/DOWN/LEFT/RIGHT 仍在（未被多框替换）。
        and "HandleAction.UP -> nudge(0, -1)" in coordinate_picker_code
        and "HandleAction.RIGHT -> nudge(1, 0)" in coordinate_picker_code
        and "var multiBox = false" in picker_widgets_code
        and "HandleAction.ADD_BOX, \"＋加框\"" in picker_widgets_code
        # 5) 对话框配置卡片：相似度滑杆 + 纹理参考 + 重叠警告 + 删除 + applyMulti 落盘。
        and "onApplyRoiBoxes" in editor_screen_code
        and "fun applyRoiBoxes" in editor_screen_code
        and "FeatureBoxAnalysis.textureStrength" in editor_screen_code
        and "FeatureBoxAnalysis.overlapRatio" in editor_screen_code
        and "相似度 ${box.similarity}%" in editor_screen_code
        and "纹理参考" in editor_screen_code
        and "重叠偏高" in editor_screen_code
        # 5b) 真机截图缺陷修复（PTP-AN70）：顶栏标题有最小宽度保证，不再被
        # 5 个按钮挤成单字「编」；按钮字号收紧。静态门禁锁住代码约束，
        # 像素级渲染验证仍需真机/golden image（本环境无真机，见 ADR-067）。
        and "Modifier.widthIn(min = 96.dp)" in editor_screen_code
        and "\"编辑场景\", maxLines = 1, softWrap = false" in editor_screen_code
        and "fontSize = 13.sp, maxLines = 1, softWrap = false" in editor_screen_code
        # 6) 新回归存在。
        and os.path.isfile(v2949_feature_box_test)
        and os.path.isfile(v2949_analysis_test)
        # 7) 不回退：前序门禁 + BANNED 13 项。
        and v2948_ui_triage_wired
        and v2947_four_channel_wired
        and v2946_stability_wired
        and v2945_stability_wired
        and v2944_node_fallback_wired
        and v2943_no_screen_capture_api
        and len(BANNED) == 13
    )
    # v3.0.0 / SimpleTap 快路径 + 注入通道分级 + fail-loud 静态门禁。
    # 三条红线：① 坐标单一换算（快路径不引入第二套百分比→像素口径）；
    # ② 真值模式不静默回退截图猜；③ 无人值守不静默空转（skip 接可见出口）。
    # v300 是 fast 模式子集的核心，full 模式同样纳入。
    v300_fast_path_wired = (
        # ① SimpleTap 模型 + 时间轴 + 单一坐标换算。
        "data class SimpleTapPlan" in simple_tap_code
        and "fun resolvePixels" in simple_tap_code
        and "fun expand" in simple_tap_code
        and "holdMs in MIN_HOLD_MS..MAX_HOLD_MS" in simple_tap_code
        and os.path.isfile(v300_simple_tap_test)
        # ② 注入通道分级：真值模式不可用必须拒绝，不回退截图猜。
        and "enum class InjectionChannel" in injection_channel_code
        and "REASON_SHIZUKU_UNAVAILABLE" in injection_channel_code
        and "decide(requested" in injection_channel_code
        and os.path.isfile(v300_injection_test)
        # ③ Shizuku 零依赖：ContentProvider 取 binder + 反射 transact，无 Maven 依赖。
        and "content://rikka.shizuku.provider" in shizuku_bridge_code
        and "getBinder" in shizuku_bridge_code
        and "transact(TRANSACTION_NEW_PROCESS" in shizuku_bridge_code
        # ④ 快路径注入器：moveTo+lineTo 到同点（修复只 moveTo 无效点击）、时间轴独立。
        and "lineTo(x.toFloat(), y.toFloat())" in simple_tap_runner_code
        and "SimpleTapSchedule.expand" in simple_tap_runner_code
        and "dispatchGesture" in simple_tap_runner_code
        # ⑤ fail-loud：静默 skip 接 onAbnormal 可见出口 + 节流有界。
        # ADR-068 修正：`runner` 指向 RunnerService.kt，fail-loud 实现实际在
        # ScriptRunner.kt（= scene_runner_code）；此前用错文件导致本门禁恒 False。
        and "fun failLoudSkip" in scene_runner_code
        and "onAbnormal?.invoke" in scene_runner_code
        and "LOUD_SKIP_THROTTLE_MS" in scene_runner_code
        # ⑥ 不回退：取消轮转门禁 + 前序全部门禁。
        and "fun applyMultiChecked" in roi_applier_code
        and v2949_multifeature_box_wired
        and v2948_ui_triage_wired
        and v2947_four_channel_wired
        and len(BANNED) == 13
    )
    # 自动派生的辅助门控」这一交互层级重构确实落在源码，而非仅存在于设计文档。
    # 六项分别为：场景名可编辑且可唤起输入法、窗口位级焦点切换、场景列表取代
    # 逐条翻页、颜色素材与有效区域自动派生、点击前门控 fail-closed 与异步代际
    # 隔离、辅助载荷写入既有落盘字段且用户可见术语归一。
    #
    # 1) 场景名：init 即实例化的可获焦 EditText，点击后请求焦点并显式显示输入法。
    #    明确禁止「只读文本 / isFocusable=false / 详情态懒实例化」三种规避写法。
    scene_name_editable = (
        "etSceneName = EditText(context)" in panel_code
        and "isFocusable = true" in panel_code
        and "isFocusableInTouchMode = true" in panel_code
        and "isFocusable = false" not in panel_code
        and "requestInputFocus(this)" in panel_code
        and "imm?.showSoftInput" in panel_code
        and "fun currentName()" in panel_code
        and "ensureDescField" not in panel_code
        and "fun nameEditable(): Boolean = true" in scene_list_code
    )
    # 2) 窗口位级焦点切换：只增删 FLAG_NOT_FOCUSABLE 一位，保留系统 / OEM 在
    #    addView 之后追加的标志位；不再用 flagsFor 整体覆盖或严格相等闸。
    focus_toggle_bitwise = (
        "fun focusableFlags(" in spec_code
        and "FLAG_NOT_FOCUSABLE.inv()" in spec_code
        and "fun isFocusToggleOnly(" in spec_code
        and "OverlayWindowSpec.focusableFlags(lp.flags, focusable)" in svc_code
        and "isLegalTransition" not in svc_code
    )
    # 3) 场景列表：只读坐标摘要行 + 点行进详情，取代「上一 / 下一」逐条翻页。
    scene_list_wired = (
        "onPrev" not in panel_code and "onNext" not in panel_code
        and "onPrev" not in svc_code and "onNext" not in svc_code
        and "private fun move(" not in svc_code
        and "fun bindSceneList(" in panel_code
        and "onSelectScene?.invoke(row.position)" in panel_code
        and "sceneListBox" in panel_code
        and "fun refreshSceneList()" in svc_code
        and "SceneList.rows(" in svc_code
        and "SceneList.selectAt(" in svc_code
        and "onSelectScene = " in svc_code
        and "fun rows(" in scene_list_code
        and "fun selectAt(" in scene_list_code
    )
    # 4) 拾取后自动派生：颜色邻域采样、有效区域收敛、手动覆盖 / 清除回退。
    #    点击前门控为 fail-closed：仅 PASS 允许派发，异步在途显示「判定中」。
    assist_auto_derived = (
        "fun sampleNeighborhood(" in scene_assist_code
        and "fun convergeRoi(" in scene_assist_code
        and "fun resolveRoi(" in scene_assist_code
        and "class Generation" in tap_gate_code
        and "fun isCurrent(" in tap_gate_code
        and "Reason.COLOR_MISMATCH" in tap_gate_code
        and "SceneAssist.sampleNeighborhood(" in svc_code
        and "SceneAssist.convergeRoi(" in svc_code
        and "SceneAssist.resolveRoi(" in svc_code
        and "TapGate.evaluate(" in svc_code
        and "gateGeneration.advance()" in svc_code
        and "gateGeneration.cancel()" in svc_code
        and "gateGeneration.isCurrent(" in svc_code
        and "fun clearManualRoi()" in svc_code
        and "fun resetAutoRoi()" in svc_code
    )
    # 5) 落盘契约不变：辅助载荷写入既有 Action.color / colorSimilarity / region，
    #    场景名仍落在既有 desc 字段，schemaVersion 保持 1，历史脚本零迁移。
    fields_unchanged = (
        "color = sample.argb.toLong()" in svc_code
        and "colorSimilarity = sample.tolerance" in svc_code
        and "region = effectiveRoi" in svc_code
        and "desc = etSceneName.text.toString()" in panel_code
        and "SCHEMA_VERSION = 1" in script_code
    )
    # 6) 配置 / 编辑交互层术语归一：用户可见层统一以「场景」表述，不出现
    #    动作类叫法、步骤话术、逐条翻页与场景编号。运行时执行诊断层与导入器
    #    外部格式关键字属既有兼容契约，不在本项扫描范围内。
    banned_ui_terms = (
        "动作名称", "动作描述", "添加动作", "动作可视化", "复杂动作",
        "个动作", "组动作", "子动作", "场景 1/", "上一 / 下一", " 步", "步骤",
    )
    ui_code = (
        panel_code,
        code_only(compose_screens),
        code_only(compose_editor),
    )
    ui_term_clean = not any(t in code for code in ui_code for t in banned_ui_terms)
    term_source_single_truth = (
        'const val UNIT = "场景"' in scene_list_code
        and "SceneList.LABEL_NAME" in panel_code
        and "SceneList.TITLE_CURRENT" in svc_code
    )
    # v2.9.23 静态接线门禁：装配失败必须按阶段归因，不得再统一呈现为「取色失败」。
    # 六项分别为：
    #  1) core 暴露失败阶段枚举，覆盖截图空 / 安全窗口 / 超时 / 装配 / 像素 / 采样；
    #  2) 失败阶段到结论存在单一映射函数，判定仍 fail-closed；
    #  3) evaluate 接收可空阶段参数，旧调用不传时回落兜底原因，保持兼容；
    #  4) app 层把截图错误码映射为阶段，并以显式结果类型返回而非 null；
    #  5) 截图返回空时补采一次，避免瞬时失败被判死；
    #  6) 诊断摘要携带采集状态、失败阶段与采样点数。
    stage_failure_graded = (
        "enum class Failure" in tap_gate_code
        and "CAPTURE_SECURE" in tap_gate_code
        and "CAPTURE_TIMEOUT" in tap_gate_code
        and "SAMPLE_EMPTY" in tap_gate_code
        and "fun reasonFor(" in tap_gate_code
        and "failure: Failure? = null" in tap_gate_code
        and "sealed interface BuildResult" in svc_code
        and "BuildResult.Fail(" in svc_code
        and "fun captureFailure(" in svc_code
        and "lastScreenshotError" in svc_code
        and '"采集" to' in svc_code
        and '"阶段" to' in svc_code
        and '"采样" to' in svc_code
    )
    # v2.9.22 静态接线门禁：确认「拾取结果卡是纠偏中枢」与「未命中原因可自助修复」
    # 确实落在源码，而非仅存在于体验建议清单。七项分别为：
    #  1) 结果卡同卡承载结论、素材、四个纠偏动作与主修复按钮，不与折叠区分裂；
    #  2) 每个未命中原因都有唯一主修复动作，由 core 单一真源决定，界面不自行推断；
    #  3) 诊断摘要可一键复制，模板与检测函数同时存在，便于核验不泄露内部术语；
    #  4) 统计只进程内累加，不写入 SharedPreferences / 文件 / 网络；
    #  5) 状态不只靠颜色：文字前缀、contentDescription、列表选中标记三者齐备；
    #  6) 判定中禁用重复拾取与修复，避免连点产生过期任务竞态；
    #  7) 固化否决项：不得引入执行前确认弹窗，自动化的连续性优先于逐次确认。
    # v2.9.25：结果卡从「四动作同卡」收敛为「两动作 + 主修复」，清除手动 / 恢复自动
    # 移到辅助门控区（仍由 showResult 控制可用态）。门禁随之改为：结论、主修复、
    # 两个直接动作必须同卡，重复动作不得回到卡片上。
    pick_card_centralized = (
        "private val pickCard" in panel_code
        and "fun showResult(" in panel_code
        and "btnFix" in panel_code
        and '"重新拾取"' in panel_code
        and '"编辑区域"' in panel_code
        and '"清除手动"' not in panel_code.split("pickCard = LinearLayout")[1].split("body.addView(pickCard")[0]
        and "btnClearManual = outlinedBtn(" in panel_code
        and "onRunPrimaryFix" in panel_code
        and "onCopyDiag" in panel_code
        and "renderResult()" in svc_code
    )
    fail_reason_actionable = (
        "fun guidanceFor(" in pick_feedback_code
        and "val fixable: Boolean" in pick_feedback_code
        and "TapGate.Reason.OUT_OF_BOUNDS ->" in pick_feedback_code
        and "TapGate.Reason.EMPTY_ROI ->" in pick_feedback_code
        and "TapGate.Reason.COLOR_MISMATCH ->" in pick_feedback_code
        and "TapGate.Reason.CAPTURE_UNAVAILABLE ->" in pick_feedback_code
        and "TapGate.Reason.ASSEMBLY_FAILED ->" in pick_feedback_code
        and "TapGate.Reason.SAMPLE_EMPTY ->" in pick_feedback_code
        and "TapGate.Reason.ASYNC_FAILURE ->" in pick_feedback_code
        and "PickFeedback.guidanceFor(" in svc_code
    )
    diag_copy_safe = (
        "fun diagnose(" in pick_feedback_code
        and "fun leaksInternalTerm(" in pick_feedback_code
        and "val INTERNAL_TERMS" in pick_feedback_code
        and "ClipboardManager" in svc_code
        and "fun copyDiagnostic()" in svc_code
        and "PickFeedback.diagnose(" in svc_code
    )
    telemetry_in_memory_only = (
        "class Counters" in pick_feedback_code
        and "SharedPreferences" not in pick_feedback_code
        and "FileOutputStream" not in pick_feedback_code
        and "fbCounters" in svc_code
        and all(
            "fbCounters" not in line
            for line in svc_code.splitlines()
            if "getSharedPreferences" in line or "putBoolean" in line or "repo().save" in line
        )
    )
    a11y_state_not_color_only = (
        panel_code.count("contentDescription") >= 6
        and '"状态：$text"' in panel_code
        and '"● ${row.coordinateLabel}"' in panel_code
        and '"○ ${row.coordinateLabel}"' in panel_code
    )
    pick_busy_guard = (
        "fun setPickBusy(" in panel_code
        and "btnPick.isEnabled = !busy" in panel_code
        and "fun isPickBusy(" in panel_code
        and "if (panel?.isPickBusy() == true) return" in svc_code
    )
    no_blocking_confirm = (
        "AlertDialog" not in runner_code
        and "AlertDialog" not in svc_code
        and "确认执行" not in runner_code
    )
    # v2.9.24 静态接线门禁 A：重启动作与「结束进程」解耦。
    # 本应用按权限零新增约束不声明 KILL_BACKGROUND_PROCESSES，旧实现把
    # killBackgroundProcesses 与 startActivity 放在同一个 runCatching 里，
    # SecurityException 会先抛出并跳过启动 —— 重启动作恒不生效。
    run_body = scene_runner_code.split(
        "fun run(pkg: String, restart: Boolean): Boolean {", 1
    )[1].split("private fun killIfPermitted(", 1)[0]
    restart_launch_decoupled = (
        "PERMISSION_KILL_BACKGROUND" in scene_runner_code
        and "fun killIfPermitted(" in scene_runner_code
        and "checkSelfPermission(PERMISSION_KILL_BACKGROUND)" in scene_runner_code
        and "if (restart) killIfPermitted(ctx, pkg)" in run_body
        and "ctx.startActivity(intent)" in run_body
        and "killBackgroundProcesses" not in run_body
    )
    # v2.9.24 静态接线门禁 B：截图错误码必须是「本次调用」的事实。
    # 采集入口清零 + 超时 / 低版本各自合成独立错误码 + 上层按码归因，
    # 避免超时读到上一次残留的安全窗口码，把归因带偏。
    shot_error_scoped = (
        "SHOT_ERROR_TIMEOUT" in auto_click_code
        and "SHOT_ERROR_UNSUPPORTED" in auto_click_code
        and "SHOT_ERROR_NONE" in auto_click_code
        and auto_click_code.count("lastScreenshotError = SHOT_ERROR_NONE") >= 2
        and "AutoClickService.SHOT_ERROR_TIMEOUT" in svc_code
        and "ERR_NO_SERVICE" in svc_code
        and "lastCaptureError == -1" in svc_code
    )
    # v2.9.24 静态接线门禁 C：门控复算必须用装配期实测色，不得自证。
    # 旧实现 `val observed = sample?.argb` 与代表色自己比距离恒为 0，
    # 清除手动覆盖 / 编辑区域后必然「命中」，是一次假校验。
    gate_observed_real = (
        "pendingObservedArgb" in svc_code
        and "val observed = pendingObservedArgb" in svc_code
        and "val observed = sample?.argb" not in svc_code
        and "failure = TapGate.Failure.SAMPLE_EMPTY" in svc_code
    )
    # v2.9.24 静态接线门禁 D：系统 API 级别门限必须与真实 API 级别一致。
    android_api_guards = (
        "Build.VERSION.SDK_INT < 34" in node_finder_code
        and "Build.VERSION.SDK_INT < 28" in scene_runner_code
        and "@androidx.annotation.RequiresApi" in auto_click_code
        # v3.0.0 / ADR-068：API 30 门限从内联判断收进 CompatFallback.screenshotSupported()，
        # 两种形态都算通过（隔离改造不回退 API 门限）。
        and (
            "if (Build.VERSION.SDK_INT < 30)" in auto_click_code
            or "CompatFallback.screenshotSupported()" in auto_click_code
        )
    )
    # v2.9.24 静态接线门禁 E：对外文案的数值格式化不得走设备默认 Locale。
    # de/fr 等地区默认输出逗号小数点，与解析口径冲突（v2.7.0 只修过拾取面板）。
    locale_offsenders = []
    for kt in (
        glob.glob(os.path.join(ROOT, "core/src/main/**/*.kt"), recursive=True)
        + glob.glob(os.path.join(ROOT, "app/src/main/**/*.kt"), recursive=True)
    ):
        body = code_only(kt)
        for m in re.finditer(r'"%[^"\n]*"\s*\.format\(', body):
            locale_offsenders.append(os.path.basename(kt) + ":" + m.group(0))
    locale_safe_format = not locale_offsenders

    # v2.9.32 门禁 K：方向差异作用域判定已演进为「方向无关、全脚本非阻断」。
    # v2.9.27 曾把方向拦截限定在「依赖图片模板」的脚本，但真机链路中
    # 「竖屏拾取、横屏运行」的场景驱动脚本仍被该分支阻断。v2.9.32 起统一
    # 降级为告警：运行期通过帧归一化 + 落点反变换适配，方向差异不再进入
    # problems。断言同时锁定「不得回归为阻断」与「不得残留过时结论」。
    preflight_orientation_scoped = (
        "val orientationIssue: String? = if (refPortrait != curPortrait)" in preflight_code
        and "if (orientationIssue != null) {" in preflight_code
        and 'warnings += "$orientationIssue' in preflight_code
        and "problems += orientationIssue" not in preflight_code
        and "帧旋回拾取几何" in preflight_code
        and "不阻断" in preflight_code
        and "不含图片模板" not in preflight_code
        and "图片匹配大概率失败" not in preflight_code
    )
    # v2.9.27 门禁 L：包可见性受限时「查不到」不等于「未安装」。
    # com.hihonor.gameassistant 录制阶段刚采集 55 事件，回放却被判未安装。
    # 旧 launchablePackages 用 getLaunchIntentForPackage 冒充已安装，必须消失。
    preflight_package_visibility = (
        "val packageVisibilityRestricted: Boolean = false" in preflight_code
        and "if (env.packageVisibilityRestricted)" in preflight_code
        and "目标应用存在性无法确认" in preflight_code
        and "运行时按包守卫校验前台应用" in preflight_code
        and "fun warningsText()" in preflight_code
        and "pm.getPackageInfo(pkg, 0)" in runner_code
        and "private fun installedPackages(s: Script)" in runner_code
        and "packageVisibilityRestricted = Build.VERSION.SDK_INT >= 30" in runner_code
        and "launchablePackages" not in runner_code
        and "report.warningsText()" in runner_code
    )
    # v2.9.27 门禁 M：录制事件坐标系必须按「当前」屏幕实时读取。
    # 启动 1280×2800、录制中转 2800×1280 时，旧静态 frame 让像素按旧分母
    # 换算百分比并被 coerceIn 夹到边缘，坐标静默错乱。
    record_frame_live = (
        "var currentFrameProvider: (() -> Frame?)? = null" in recorder_bus_code
        and "currentFrameProvider: (() -> Frame?)? = null" in recorder_bus_code
        and "runCatching { currentFrameProvider?.invoke() }.getOrNull() ?: frame" in recorder_bus_code
        and "this.currentFrameProvider = currentFrameProvider" in recorder_bus_code
        and "AutoClickService.live()?.frame() ?: frame" in recording_code
    )
    # v2.9.27 门禁 N：场景日志里「本轮观测」与「状态机在场（含迟滞）」
    # 不再共用一个 present= 标签，避免同一行出现相反取值。
    scene_log_unambiguous = (
        '状态=$presentNow score=$scoreTxt' in scene_trigger_code
        and '观测=${o.present} score=$scoreTxt' in scene_runner_code
        and 'present=$presentNow' not in scene_trigger_code
        and ' present=${o.present}' not in scene_runner_code
    )
    # v2.9.28 门禁 O：场景驱动监视必须逐帧判定，不再按固定轮询节拍休眠。
    # 旧实现本轮无触发意图时固定 delay(300)，且帧缓存 TTL 400ms 会让连续多轮
    # 复用同一张位图；两者叠加后短暂出现的场景可能整段落在两次判定之间。
    scene_frame_synced = (
        "SceneFramePolicy.frameIntervalMs(" in scene_runner_code
        and "SceneFramePolicy.delayFor(" in scene_runner_code
        and "paceFrame(roundStartMs, frameIntervalMs)" in scene_runner_code
        and "delay(WaitGate.DEFAULT_POLL_MS)" not in scene_runner_code
        # v2.9.32：findTemplates 改为多行调用并透传当前方向；逐帧判定语义不变，
        # 断言改为逐项检查参数，避免与调用排版耦合。
        and "findTemplates(" in scene_runner_code
        and "freshFrame = true" in scene_runner_code
        and "currentRotation = nowRotation.coerceAtLeast(0)" in scene_runner_code
        and "fun frameIntervalMs(pollMode: PollMode" in scene_frame_policy_code
        and "PollMode.LEGACY_100, PollMode.LEGACY_300 -> DEFAULT_FRAME_MS" in scene_frame_policy_code
    )
    # v2.9.28 门禁 P：每轮必须强制抓新帧，且抓帧失败时不得拿旧帧冒充新帧判定。
    scene_fresh_frame = (
        "freshFrame: Boolean = false" in screen_source_code
        and "private suspend fun grabFreshFrame()" in screen_source_code
        and "if (freshFrame) grabFreshFrame() else frame()" in screen_source_code
        and "cachedFrame = b" in screen_source_code
        and "if (old !== b) runCatching { old?.recycle() }" in screen_source_code
    )
    # v2.9.28 门禁 Q：提频后完整明细按状态变化 + 周期心跳写出，心跳行携带
    # 本周期实际判定帧数，避免撑爆 RunLog 500 条环形缓冲、挤掉其它模块记录。
    scene_frame_log_bounded = (
        "SceneFramePolicy.due(" in scene_runner_code
        and "SceneFramePolicy.heartbeatLine(" in scene_runner_code
        and "sceneFrameCount" in scene_runner_code
        and 'if (verbose) RunLog.i("Scene", line)' in scene_runner_code
        and "每帧全判" in scene_runner_code
    )
    # v2.9.29 门禁 R：四个数值字段必须走步进器，不能再是裸输入框。
    # 悬浮窗里裸 EditText 每次取值要走「清 NOT_FOCUSABLE → 请求焦点 → 唤起输入法
    # → 收键盘 → 归还焦点」五步，任何一步在 OEM ROM 上不配合就表现为「点了没反应」。
    stepper_fields = (
        "stepperRow(StepPolicy.HOLD, etHold)" in panel_code
        and "stepperRow(StepPolicy.TIMES, etTimes)" in panel_code
        and "stepperRow(StepPolicy.WAIT, etWait)" in panel_code
        and "stepperRow(StepPolicy.DURATION, etDuration)" in panel_code
        and "moreBox.addView(etHold," not in panel_code
        and "moreBox.addView(etTimes," not in panel_code
        and "moreBox.addView(etWait," not in panel_code
        and "swipeRow.addView(etDuration," not in panel_code
    )
    # v2.9.29 门禁 S：步进规则必须是纯函数并带边界与空值语义。
    # 空值点减号必须落到下限而不是「默认值减一档」；非法输入必须回默认值而不是 0。
    step_policy_pure = (
        "fun bump(f: Field, raw: String, dir: Int): Double" in step_policy_code
        and "raw.coerceIn(f.min, f.max)" in step_policy_code
        and "return if (dir > 0) coerce(f, f.def + f.step) else f.min" in step_policy_code
        and "fun hintOf(f: Field): String" in step_policy_code
        and '"选填"' in step_policy_code
        and "fun fields(): List<Field>" in step_policy_code
    )
    # v2.9.29 门禁 T：「用当前位置」必须由面板入口 + 服务侧跨场景保留点共同接线，
    # 且不能复用随场景清空的 lastPickPct（那样切到新场景就失效）。
    use_current_point = (
        'outlinedBtn("用当前位置") { useCurrentPoint() }' in panel_code
        and "onUseCurrentPoint = { lastAnyPickPct }" in svc_code
        and "lastAnyPickPct = r.xPct to r.yPct" in svc_code
        and "private var lastAnyPickPct: Pair<Double, Double>? = null" in svc_code
    )
    # v2.9.29 门禁 U：整屏可拖 + 轻点绝对定位。
    # 旧实现只有 30dp 抓手可拖，命中区小于无障碍最小触摸目标 48dp，贴边时窗口
    # 还被夹到屏内、与准星真值分离，用户的实际体验是「摸不到、拖不回」。
    pointer_capture_anywhere = (
        "class PointerCaptureView" in capture_code
        and "scaledTouchSlop" in capture_code
        and "onTap?.invoke(downX, downY)" in capture_code
        and "if (dragging) onMove?.invoke(e.rawX, e.rawY)" in capture_code
        and "wm.addView(capture, captureLp)" in picker_code
        and "capture.onTap = { rawX, rawY ->" in picker_code
        and "capture.onMove = { rawX, rawY -> moveCrossTo(rawX, rawY) }" in picker_code
    )
    # v2.9.29 门禁 V：边缘手势不被系统抢走 + 命中区达标 + 抓手画在真值上。
    edge_gesture_exclusion = (
        "systemGestureExclusionRects" in picker_code
        and "PointerTouchTarget.exclusionFor" in picker_code
        and "PointerTouchTarget.MIN_TOUCH_DP" in widgets_code
        and "handle.crossInWindowX = crossX - handleLp.x" in picker_code
        and "fun exclusionFor(" in touch_target_code
        and "fun markerIn(" in touch_target_code
    )
    # v2.9.29 门禁 W：落点边缘安全在「拾取」与「执行」两侧共用同一真源。
    # 只做一侧会让「看到的坐标」与「实际点到的坐标」不一致，用户无从解释。
    edge_safety_apply = (
        "EdgeSafety.adjust(px, py, screenW, screenH, insets)" in picker_code
        and "val (sx, sy) = safePoint(x, y)" in gesture_code
        and "EdgeSafety.adjust(x, y, screenW, screenH, edgeInsets)" in gesture_code
        and "fun gestureInsets()" in auto_click_code
        and "gesture.setEdgeInsets(" in runner_code
        and "fun adjust(" in edge_safety_code
        and "fun isRisky(" in edge_safety_code
    )

    # v2.9.30 门禁 X：控制权必须在全局可达，终止必须真的释放采集与注入。
    #
    # 三条历史缺口各自对应一项断言，缺一不可：
    # 1. 归还入口只在悬浮面板里 → 目标应用全屏时被遮、被系统限制，用户够不着；
    # 2. 暂停与归还没有区分 → 「已暂停」时读屏仍被占着，用户以为已经收回；
    # 3. 终止只留一行日志 → 停在哪一步没有任何结构化的落点，「继续」无从说起。
    control_global_reachable = (
        # 通知栏必须有三个 action：主操作 / 归还连接 / 停止
        runner_code.count(".addAction(") >= 3
        and '"归还连接"' in runner_code
        and "ACTION_HANDOVER" in runner_code
        and "ACTION_RESUME" in runner_code
        # 归还必须走 RELEASE → Settled，而不是只改一个布尔
        and "private fun handover()" in runner_code
        and "ConnectionState.Event.Release" in runner_code
        and "ConnectionState.Event.Settled" in runner_code
        # 只有 OWNED 允许注入：这是「暂停不是归还」的结构性保证
        and "fun allowsInjection(phase: Phase): Boolean = phase == Phase.OWNED" in conn_state_code
        # 预告必须分级，且已确认循环零阻塞
        and "Level.FOLDED" in announce_policy_code
        and "COALESCE_MS" in announce_policy_code
        and "if (!announce(action, ruleIndex))" in scene_runner_code
        # 终止必须带可恢复位置
        and "private fun emitHalt(" in scene_runner_code
        and "resumeStep = if (ruleIndex < 0) currentStep else 0" in scene_runner_code
        # HALTED 是独立运行态，面板与通知都要认识它
        and "HALTED" in runner_code
        and "State.HALTED" in ball_panel_code
        # 降级通道必须能提前判定，不能等断线才发现
        and "fun evaluate(" in degrade_channel_code
        and "SERVICE_DISABLED" in degrade_channel_code
    )

    # v2.9.31 门禁：装配-运行同源（真机 RCA 七项条款的静态接线）。
    #
    # 上一版的确证缺陷：装配期取色 / 收敛 ROI / 同帧自测 PASS 完全不进运行期
    # 判定链，运行期只用一张同帧模板要求跨帧 0.87；目标场景完全呈现仍 0 触发。
    # 本门禁逐层锁定「证据落盘 → core 判据 → 装配采集 → 运行消费 → 落点/告警」，
    # 任何一层写了没接线都判 FAIL，避免再次出现「界面一套、判据另一套」。
    evidence_same_source = (
        # 1 证据落盘（字段带默认值 → schemaVersion=1、历史脚本零迁移）
        "val colorArgb: Int = 0" in scene_rule_code
        and "val convergedRoi: Region? = null" in scene_rule_code
        and "val crossFrameFloor: Double = -1.0" in scene_rule_code
        and "val evidenceStreak: Int = 3" in scene_rule_code
        # 2 core 分层判据 + 帧几何闭环
        and "fun geometryMatches(" in scene_evidence_code
        and "fun assess(" in scene_evidence_code
        and "fun maskCovers(" in scene_evidence_code
        and "fun recoverPickPoint(" in scene_evidence_code
        and "Tier.LAYERED" in scene_evidence_code
        # 3 装配期多帧采集 + 跨帧阈值 + 时域掩码
        and "fun crossFrameFloor(" in scene_self_test_code
        and "delay(120L)" in floating_editor_code
        and "crossFrameFloor = floor" in floating_editor_code
        and "planMaskTemporal(" in floating_editor_code
        and "fun planMaskTemporal(" in stable_anchor_code
        # 4 运行期消费：逐帧几何校验 / 逐帧取色 / 两阶段掩码
        and "geometryMatches(" in screen_source_code
        and "val observedArgb = if (" in screen_source_code
        and "sampleNeighborhood(" in screen_source_code
        and "covered > 0" in screen_source_code
        # 5 分层判定接进观测、严格落点、可见告警
        and "SceneEvidence.assess(" in scene_runner_code
        and ("strictNoFallback = rules[slot].triggerMode != TriggerMode.ON_DISAPPEAR" in scene_runner_code
             or "strictNoFallback = rules[idx].triggerMode != TriggerMode.ON_DISAPPEAR" in scene_runner_code)
        and "is SceneClickResolution.NoValidLocation" in scene_runner_code
        and "onSuspectedPresent?.invoke(msg)" in scene_runner_code
        and "fun setSuspect(" in ball_panel_code
        and "重新拾取" in runner_code
        # 6 core 侧阈值双约束与落点保持
        and "crossFrameFloor: Double = -1.0" in scene_trigger_code
        and "MIN_SAFE_SIMILARITY" in scene_trigger_code
        and "strictNoFallback: Boolean = false" in scene_trigger_code
        and "NoValidLocation(" in scene_trigger_code
    )

    # v2.9.32 / ADR-050 门禁 Y：方向无关执行（帧归一化 + 落点反变换 + 非阻断预检）。
    #
    # 真机链路：竖屏拾取、横屏运行（或运行中旋转）时，三道方向硬闸把脚本判成
    # 致命错误并中止。本门禁逐层锁定「core 真源 → 方向元数据落盘 → 装配记录 →
    # 当前帧旋回拾取几何 → 运行期方向检测 / 瞬态证据清理 / 落点旋回当前屏幕 →
    # 预检降级为非阻断告警」，任一层写了没接线即判 FAIL；并断言旧方向硬中止
    # 代码不得复活。
    orientation_agnostic_wired = (
        # 1 core 真源：四分之一转归一化、帧旋转、几何兼容、点/区域/掩码反变换
        "object OrientationTransform {" in orientation_transform_code
        and "fun norm(rotation: Int): Int" in orientation_transform_code
        and "fun turns(refRotation: Int, curRotation: Int): Int" in orientation_transform_code
        and "fun fromDimensions(refW: Int, refH: Int, curW: Int, curH: Int): Int" in orientation_transform_code
        and "fun geometryCompatible(refW: Int, refH: Int, curW: Int, curH: Int): Boolean" in orientation_transform_code
        and "fun rotateImage(img: ImageFinder.Image, turns: Int)" in orientation_transform_code
        and "fun pointToCurrent(xPct: Double, yPct: Double, turns: Int)" in orientation_transform_code
        and "fun regionToCurrent(r: Region, turns: Int)" in orientation_transform_code
        and "fun maskToCurrent(m: MaskRect, turns: Int)" in orientation_transform_code
        # 2 方向元数据落盘：SceneRule 记录拾取方向，TemplateSpec/Match 传递旋转格数
        and "val pickRotation: Int = 0" in scene_rule_code
        and "val pickRotation: Int = 0" in scene_frame_code
        and "val orientationTurns: Int = 0" in scene_frame_code
        # 3 装配期记录拾取方向
        and "OrientationTransform.norm(" in floating_editor_code
        and "pickRotation = pickRot" in floating_editor_code
        and "pickRotation = rule.pickRotation" in scene_runner_code
        # 4 帧归一化：当前帧按角度差旋回拾取几何，NCC / 取色 / ROI / 掩码全部复用
        and "currentRotation: Int = 0" in screen_source_code
        and "val orient = dev.nymph.auto.core.runner.OrientationTransform" in screen_source_code
        and "orient.rotateImage(base, key)" in screen_source_code
        and "val turns = orient.turns(effectivePickRotation(spec), currentRotation)" in screen_source_code
        and "val pickFrame = normalizedFrame(turns)" in screen_source_code
        and "orientationTurns = turns" in screen_source_code
        # 5 运行期：方向变化不中止、清理跨帧瞬态证据、透传当前方向、落点反变换
        and "OrientationTransform.norm(it)" in scene_runner_code
        and "suspectStreak.fill(0)" in scene_runner_code
        and "suspectedAlerted.fill(false)" in scene_runner_code
        and "lastValid[i] = null" in scene_runner_code
        and "currentRotation = nowRotation.coerceAtLeast(0)" in scene_runner_code
        and "OrientationTransform.pointToCurrent(" in scene_runner_code
        and "orientationTurns" in scene_runner_code
        and "refRotation" not in scene_runner_code
        # 6 预检：方向差异降级为告警，不进入 problems，不残留过时结论
        and 'warnings += "$orientationIssue' in preflight_code
        and "problems += orientationIssue" not in preflight_code
        and "帧旋回拾取几何" in preflight_code
        and "不阻断" in preflight_code
        and "图片匹配大概率失败" not in preflight_code
    )

    # v2.9.32 / ADR-050 门禁 Z：拾取后场景判据参数配置（核心需求 3）。
    #
    # 此前「拾取后灵活配置参数」只在界面呈现为只读意图说明：collect() 只收集
    # 动作层字段，SceneRule 的连续判据字段无任何写回入口；且 pendingSceneRoi
    # 保存后置空使「仅调整参数再保存」路径的 sceneRule 完全不更新。本门禁逐层
    # 锁定「core 纯函数真源 → Form 契约 → 面板回显 / 收集 → 服务写盘前应用 →
    # 门控阈值同步 → schema 不变」，并断言空配置恒等（老脚本零回归）。
    scene_tuning_wired = (
        # 1 core 真源：留空沿用、填值覆盖、可往返、可单测
        "object SceneTuning {" in scene_tuning_code
        and "fun fields(): List<Field>" in scene_tuning_code
        and 'val similarity: String = ""' in scene_tuning_code
        and "fun from(rule: SceneRule): Config" in scene_tuning_code
        and "fun applyTo(base: SceneRule, config: Config): SceneRule" in scene_tuning_code
        and "fun hasOverride(config: Config): Boolean" in scene_tuning_code
        and "留空沿用" in scene_tuning_code
        and "return@fold rule" in scene_tuning_code
        # 2 Form 契约：带默认空配置、载入既有场景时回显
        and "val tuning: SceneTuning.Config = SceneTuning.Config()" in floating_layout_code
        and "tuning = a.sceneRule?.let { SceneTuning.from(it) } ?: SceneTuning.Config()" in floating_layout_code
        # 3 面板：可见入口、字段键对齐、基线摘要、收集为 Config
        and 'sectionLabel("场景判据参数（留空沿用装配值）")' in panel_code
        and "SceneTuning.fields()" in panel_code
        and "SceneTuning.hintOf(" in panel_code
        and "fun setTuningBaseline(rule: SceneRule?)" in panel_code
        and "SceneTuning.summary(rule)" in panel_code
        and "tuning = SceneTuning.fields().fold(SceneTuning.Config())" in panel_code
        # 4 服务：写盘前统一应用显式覆盖、仅在变化时写回、同步门控阈值
        and "SceneTuning.hasOverride(form.tuning)" in floating_editor_code
        and "SceneTuning.applyTo(base, form.tuning)" in floating_editor_code
        and "imageSimilarity = tunedRule.similarity.coerceIn(1, 100)" in floating_editor_code
        and "if (tunedRule != base)" in floating_editor_code
        and "p.setTuningBaseline(a.sceneRule)" in floating_editor_code
        and "panel?.setTuningBaseline(outcome.rule)" in floating_editor_code
        # 5 落盘契约不变：不新增 schema 字段，方向元数据仍带默认值
        and "SCHEMA_VERSION = 1" in script_code
        and "val pickRotation: Int = 0" in scene_rule_code
    )

    # v2.9.33 / ADR-051 门禁 AA：应用回切失败降级（真机厂商应用中止循环修复）。
    # 旧实现把 getLaunchIntentForPackage 失败等同中止，com.hihonor.gameassistant
    # 等无标准入口 / 包可见性受限的厂商应用导致同因中止二十余次。本门禁锁定
    # 「core 策略真源 → 拉起失败重读包名 → 已在目标应用放行 / 否则一次性点名中止
    # → 显式 MAIN 兜底 → 不引入 shell」完整链路，并断言旧循环话术已移除。
    restore_failure_downgraded = (
        # 1 core 纯 JVM 策略真源
        "object RestorePolicy {" in restore_policy_code
        and "enum class Outcome { PROCEED, ABORT }" in restore_policy_code
        and "fun decide(" in restore_policy_code
        and "hasPositionalActions: Boolean" in restore_policy_code
        and "recorded.equals(current, ignoreCase = true)" in restore_policy_code
        and "请手动打开" in restore_policy_code
        and "请手动打开后重试" not in restore_policy_code
        # 2 执行层：拉起失败后按策略裁决，而非直接中止
        and "RestorePolicy.decide(" in scene_runner_code
        and "currentPackage = currentFrontPackage()" in scene_runner_code
        and "RestorePolicy.Outcome.PROCEED ->" in scene_runner_code
        and "RestorePolicy.Outcome.ABORT ->" in scene_runner_code
        and "emitSceneNotice(restore.message)" in scene_runner_code
        # 3 显式 MAIN 兜底（尽力而为），不引入 shell / monkey
        and "explicitMainIntent(ctx, pkg)" in scene_runner_code
        and "resolveActivity(probe, 0)" in scene_runner_code
        and "Runtime.getRuntime()" not in scene_runner_code
        and "ProcessBuilder" not in scene_runner_code
    )

    # v2.9.33 / ADR-051 门禁 AB：OCR 边界可见告警与列表视图复用说明。
    # 离线 OCR 可插拔是既有设计（不内置大模型），但静默回退让文字锚点脚本在
    # 非 GMS 设备失效而用户不知情；列表视图复用是 ADR-002 已声明边界。本门禁
    # 锁定「脚本文字依赖判定 → 仅依赖且不可用时一次性可见告警 → 不内置模型 →
    # 滚动告警补充视图复用说明」，避免静默降级与对无关脚本的打扰。
    ocr_boundary_notified = (
        # 1 core 文字依赖判定
        "fun dependsOnText(): Boolean" in script_model_code
        and "a.type == ActionType.CLICK_TEXT" in script_model_code
        and "ConditionKind.APPEAR_TEXT" in script_model_code
        and "walk(a.listeners.all())" in script_model_code
        # 2 运行前一次性可见告警（仅文字依赖 + 不可用）
        and "!ocr.available && s.dependsOnText()" in runner_code
        and "markOcrUnavailableWarned()" in runner_code
        and "ocrUnavailableWarned: Boolean" in runner_code
        and "toast(msg)" in runner_code
        # 3 不内置模型：仍走可插拔契约，仅提示补齐路径
        and "det.onnx / rec.onnx / keys.txt" in runner_code
        and "assets/ocr/" in code_only(os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/OnnxOcrEngine.kt"))
        # 4 列表视图复用说明进入录制告警
        and "列表滚动会复用视图节点" in record_compiler_code
    )

    # v2.9.34 / ADR-052 门禁 AC：OCR 类型隔离（安全根基）。
    # 旧 TextBox 把「文字内容 + 中心点坐标」绑定为同一个值，任何文字断言的调用方
    # 都顺带拿到了一个可注入的点击锚点。本版把「断言文字存在」与「在哪里」在类型
    # 系统上切开：OcrTextAssertion 只有文字/置信度/匹配器，无任何坐标字段，故只能
    # 进条件判定、不可能产出一个点；OcrRegion 只有矩形+来源、无文字内容，供定位
    # 消费取中心点却无法反推文字。文字与位置只在消费侧显式组合。本门禁锁定这组
    # 类型契约 + APPEAR_TEXT 去坐标接线 + 置信度解码 + ocr_triggered 脱敏日志 +
    # ML Kit bundled 口径（含纠正 RunnerService 旧 GMS 措辞）。
    ocr_type_isolated = (
        # 1 文字断言真源：有 text / confidence / matcherId，无任何坐标字段
        "data class OcrTextAssertion(" in ocr_assertion_code
        and "val text: String" in ocr_assertion_code
        and "val confidence: Double" in ocr_assertion_code
        and "val matcherId: String" in ocr_assertion_code
        and "UNAVAILABLE_CONFIDENCE" in ocr_assertion_code
        and "hasConfidence" in ocr_assertion_code
        and "fun from(box: TextBox" in ocr_assertion_code
        # OcrTextAssertion 数据类主体内不得出现任何坐标字段：截取到相邻的 OcrRegion
        # 之前，断言这段里没有 left/top/right/bottom/centerX/centerY/x/y。
        and not any(
            k in ocr_assertion_code.split("data class OcrTextAssertion(", 1)[1].split("data class OcrRegion(", 1)[0]
            for k in ("val left", "val top", "val right", "val bottom", "val centerX", "val centerY")
        )
        # 2 区域真源：有矩形坐标 + source，但无文字内容字段
        and "data class OcrRegion(" in ocr_assertion_code
        and "val left: Int" in ocr_assertion_code
        and "val top: Int" in ocr_assertion_code
        and "val right: Int" in ocr_assertion_code
        and "val bottom: Int" in ocr_assertion_code
        and "val source: String" in ocr_assertion_code
        and not any(
            k in ocr_assertion_code.split("data class OcrRegion(", 1)[1]
            for k in ("val text", "val confidence", "val label")
        )
        # 3 APPEAR_TEXT 条件判定改走无坐标断言接口
        and "ConditionKind.APPEAR_TEXT ->" in scene_runner_code
        and "vision.assertText(cond.text, cond.textRegex) != null" in scene_runner_code
        and "suspend fun assertText(" in screen_source_code
        and "OcrTextAssertion" in screen_source_code
        # 4 置信度解码：ctcGreedy 委托 scored 版，旧签名零回归
        and "fun ctcGreedy(" in ocr_postprocess_code
        and "fun ctcGreedyScored(" in ocr_postprocess_code
        and "ctcGreedyScored(logits, steps, classes, keys).text" in ocr_postprocess_code
        and "data class Decoded(val text: String, val confidence: Double)" in ocr_postprocess_code
        and "softmaxAt(" in ocr_postprocess_code
        # 5 ocr_triggered 脱敏日志：只记 matcher/hit/confidence，不记原文与哈希
        and 'RunLog.i("ocr_triggered"' in screen_source_code
        and "matcher=$matcher hit=$hit confidence=" in screen_source_code
        and "text=$text" not in screen_source_code
        and "hashCode" not in screen_source_code
        # 6 ML Kit bundled 口径：告警文案与注释均不得再说「GMS 兜底 / Play 服务按需下载」
        and "bundled" in runner_code
        and "GMS 兜底" not in open(runner, encoding="utf-8").read()
        and "ML Kit 中文兜底（bundled 静态打包，无 GMS 依赖）" in open(
            os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/engine/OcrEngineFactory.kt"), encoding="utf-8"
        ).read()
    )

    # v2.9.26 静态接线门禁 J：场景 miss 必须有现场存证与归因维度。
    # 现场日志出现过「元素出现时 score 仅 0.61 < 阈值 0.82，全程未触发」且拿不到
    # 当轮画面 —— 场景驱动此前没接失败存证（dumpMissFrame 只挂在顺序找图路径），
    # 也拿不到「最佳窗口在哪 / 是否被掩码盖住」。三条接线缺一不可。
    scene_miss_evidence = (
        "sceneMissDumped" in scene_runner_code
        and ('dumpMissFrame("场景${ruleActions[r] + 1}")' in scene_runner_code
             or 'dumpMissFrame("场景${i + 1}")' in scene_runner_code)
        and "元素疑似在场但外观/尺度/掩码与拾取快照不一致" in scene_runner_code
        and "掩码盖住=" in scene_runner_code
        and "bestCxPx = scored.bestCx" in screen_source_code
        and "bestCxPx: Int = -1" in scene_frame_code
        and "ScaleOutcome" in image_finder_code
        and "toOrigCx" in image_finder_code
    )

    # v2.9.25 静态接线门禁 F：门控结论必须纳入锚点可用性。
    # 现场日志出现过「锚点 0×0px / 掩码 0 块 / NO_FRAME」与「点击前门控 PASS 允许点击」
    # 同屏的链路，保存后运行时又 fail-closed 不触发 —— 界面在骗用户。
    with open(os.path.join(ROOT, "core/src/main/java/dev/nymph/auto/core/editor/TapGate.kt"),
              encoding="utf-8") as fh:
        tap_gate_src = fh.read()
    gate_anchor_ready = (
        "anchorReady: Boolean = true" in tap_gate_src
        and "if (!anchorReady) return verdict(Reason.ASSEMBLY_FAILED)" in tap_gate_src
        and "不会触发" in tap_gate_src
        and "anchorReady = anchorReady" in svc_code
        and "anchorReady = pendingAnchorReady" in svc_code
        and "pendingAnchorReady = outcome.anchorB64.isNotBlank()" in svc_code
    )
    # v2.9.25 静态接线门禁 G：场景驱动零模板必须在起服务之前拦截。
    run_precheck_wired = (
        "SceneTrigger.triggerableCount(s.actions)" in runner_code
        and "if (s.sceneDriven)" in runner_code
        and "运行不会产生任何动作" in runner_code
    )
    # v2.9.25 静态接线门禁 H：首屏减法与「保存不再是死路」。
    panel_lean = (
        "coordBox" in panel_code
        and "手动输入坐标" in panel_code
        and "actRow2" not in panel_code
        and "重新拾取\" else SceneList.LABEL_SAVE" in panel_code
        and "if (r != null && !r.hit && !r.pending) onRepick?.invoke()" in panel_code
    )
    # v2.9.25 静态接线门禁 I：不可用场景的保存结论不得被引导语覆盖。
    # v2.9.36：死场景口径从「仅未装配」扩展到「未装配或 verdict.usable=false」
    # （判别度不足同样数学上恒不触发），断言随之锁定新条件与两类强告警文案。
    save_dead_scene_visible = (
        "if (sceneAttached && (!sceneGated || verdict != null && !verdict.usable)) {" in svc_code
        and "场景已保存，但$cause，运行不会触发" in svc_code
        and "锚点判别度不足" in svc_code
        and svc_code.index("if (sceneAttached && (!sceneGated || verdict != null && !verdict.usable)) {")
        < svc_code.index("else if (sceneAttached && !sceneGuideShown())")
    )

    return {
        "banned_async_state": found,
        "no_old_scrollview_drag_listener": no_old_listener,
        "uses_set_on_touch_listener": uses_set_on_touch,
        "no_broken_intercept_touch": no_broken_intercept,
        "bar_moved_out_of_scroll": bar_out_of_scroll,
        "disallow_intercept_touch": disallow_intercept,
        "sync_commit_wired": sync,
        "small_drag_threshold": small,
        "layout_flags_in_screen_wired": layout_flags_wired,
        "unified_screen_bounds": unified_bounds,
        "no_app_area_bounds": no_app_area_bounds,
        "rotation_reposition_wired": rotation_reposition,
        "bounds_source_single_truth": bounds_source_single_truth,
        "mismatch_guard_wired": mismatch_guard_wired,
        "mismatch_per_rule_isolation": mismatch_per_rule_isolation,
        "scene_stop_guidance": scene_stop_guidance,
        "loop_scope_single_truth": loop_scope_wired,
        "verdict_gate_alignment": verdict_gate_alignment,
        "shot_release_finally_wired": shot_release_finally_wired,
        "ocr_confidence_wired": ocr_confidence_wired,
        "crash_report_wired": crash_report_wired,
        "duplicate_name_key_wired": duplicate_name_key_wired,
        "v2940_data_integrity_wired": v2940_data_integrity_wired,
        "v2941_data_integrity_hardening": v2941_data_integrity_hardening,
        "v2942_snapshot_and_coord_wired": v2942_snapshot_and_coord_wired,
        "v2943_no_screen_capture_api": v2943_no_screen_capture_api,
        "v2944_node_fallback_wired": v2944_node_fallback_wired,
        "v2945_stability_wired": v2945_stability_wired,
        "v2946_stability_wired": v2946_stability_wired,
        "v2947_four_channel_wired": v2947_four_channel_wired,
        "v2948_ui_triage_wired": v2948_ui_triage_wired,
        "v2949_multifeature_box_wired": v2949_multifeature_box_wired,
        "v300_fast_path_wired": v300_fast_path_wired,
        "v3000_screenshot_quarantined": v3000_screenshot_quarantined,
        "v3001_feature_capture_wired": v3001_feature_capture_wired,
        "scene_name_editable": scene_name_editable,
        "focus_toggle_bitwise": focus_toggle_bitwise,
        "scene_list_wired": scene_list_wired,
        "assist_auto_derived": assist_auto_derived,
        "fields_unchanged": fields_unchanged,
        "ui_term_clean": ui_term_clean,
        "term_source_single_truth": term_source_single_truth,
        "stage_failure_graded": stage_failure_graded,
        "pick_card_centralized": pick_card_centralized,
        "fail_reason_actionable": fail_reason_actionable,
        "diag_copy_safe": diag_copy_safe,
        "telemetry_in_memory_only": telemetry_in_memory_only,
        "a11y_state_not_color_only": a11y_state_not_color_only,
        "pick_busy_guard": pick_busy_guard,
        "no_blocking_confirm": no_blocking_confirm,
        "restart_launch_decoupled": restart_launch_decoupled,
        "shot_error_scoped": shot_error_scoped,
        "gate_observed_real": gate_observed_real,
        "android_api_guards": android_api_guards,
        "locale_safe_format": locale_safe_format,
        "scene_miss_evidence": scene_miss_evidence,
        "gate_anchor_ready": gate_anchor_ready,
        "run_precheck_wired": run_precheck_wired,
        "panel_lean": panel_lean,
        "save_dead_scene_visible": save_dead_scene_visible,
        "locale_offsenders": locale_offsenders,
        "preflight_orientation_scoped": preflight_orientation_scoped,
        "preflight_package_visibility": preflight_package_visibility,
        "record_frame_live": record_frame_live,
        "scene_log_unambiguous": scene_log_unambiguous,
        "scene_frame_synced": scene_frame_synced,
        "scene_fresh_frame": scene_fresh_frame,
        "scene_frame_log_bounded": scene_frame_log_bounded,
        "stepper_fields": stepper_fields,
        "step_policy_pure": step_policy_pure,
        "use_current_point": use_current_point,
        "pointer_capture_anywhere": pointer_capture_anywhere,
        "edge_gesture_exclusion": edge_gesture_exclusion,
        "edge_safety_apply": edge_safety_apply,
        "control_global_reachable": control_global_reachable,
        "evidence_same_source": evidence_same_source,
        "orientation_agnostic_wired": orientation_agnostic_wired,
        "scene_tuning_wired": scene_tuning_wired,
        "restore_failure_downgraded": restore_failure_downgraded,
        "ocr_boundary_notified": ocr_boundary_notified,
        "ocr_type_isolated": ocr_type_isolated,
        "ok": (
            (not any(found.values()))
            and no_old_listener
            and uses_set_on_touch
            and no_broken_intercept
            and bar_out_of_scroll
            and disallow_intercept
            and sync
            and small
            and layout_flags_wired
            and unified_bounds
            and no_app_area_bounds
            and rotation_reposition
            and bounds_source_single_truth
            and mismatch_guard_wired
            and mismatch_per_rule_isolation
            and scene_stop_guidance
            and loop_scope_wired
            and verdict_gate_alignment
            and shot_release_finally_wired
            and ocr_confidence_wired
            and crash_report_wired
            and duplicate_name_key_wired
            and v2940_data_integrity_wired
            and v2941_data_integrity_hardening
            and v2942_snapshot_and_coord_wired
            and v2943_no_screen_capture_api
            and v2944_node_fallback_wired
            and v2945_stability_wired
            and v2946_stability_wired
            and v2947_four_channel_wired
            and v2948_ui_triage_wired
            and v2949_multifeature_box_wired
            and v300_fast_path_wired
            and v3000_screenshot_quarantined
            and v3001_feature_capture_wired
            and scene_name_editable
            and focus_toggle_bitwise
            and scene_list_wired
            and assist_auto_derived
            and fields_unchanged
            and ui_term_clean
            and term_source_single_truth
            and stage_failure_graded
            and pick_card_centralized
            and fail_reason_actionable
            and diag_copy_safe
            and telemetry_in_memory_only
            and a11y_state_not_color_only
            and pick_busy_guard
            and no_blocking_confirm
            and restart_launch_decoupled
            and shot_error_scoped
            and gate_observed_real
            and android_api_guards
            and locale_safe_format
            and scene_miss_evidence
            and gate_anchor_ready
            and run_precheck_wired
            and panel_lean
            and save_dead_scene_visible
            and preflight_orientation_scoped
            and preflight_package_visibility
            and record_frame_live
            and scene_log_unambiguous
            and scene_frame_synced
            and scene_fresh_frame
            and scene_frame_log_bounded
            and stepper_fields
            and step_policy_pure
            and use_current_point
            and pointer_capture_anywhere
            and edge_gesture_exclusion
            and edge_safety_apply
            and control_global_reachable
            and evidence_same_source
            and orientation_agnostic_wired
            and scene_tuning_wired
            and restore_failure_downgraded
            and ocr_boundary_notified
            and ocr_type_isolated
        ),
    }


def test_info() -> dict:
    total = failed = skipped = 0
    # core 用 test-results/test/，app 用 test-results/testDebugUnitTest/，
    # 两个都要统计（v2.7.1 修：此前只匹配 test/ 会漏掉全部 app 单测）
    files = glob.glob(os.path.join(ROOT, "**", "build", "test-results", "**", "TEST-*.xml"), recursive=True)
    for f in files:
        try:
            r = ET.parse(f).getroot()
            total += int(r.get("tests", 0))
            failed += int(r.get("failures", 0)) + int(r.get("errors", 0))
            skipped += int(r.get("skipped", 0))
        except Exception:
            pass
    return {"cases": total, "failed": failed, "skipped": skipped, "suites": len(files)}


def source_info() -> dict:
    if not os.path.isfile(SRC_ZIP):
        return {"files": 0, "bytes": 0, "sha256": "-"}
    with zipfile.ZipFile(SRC_ZIP) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
    return {
        "files": len(names),
        "bytes": os.path.getsize(SRC_ZIP),
        "sha256": sha256(SRC_ZIP),
    }


def wording_info() -> dict:
    """v2.9.16：表述规范化静态门禁，纳入发版硬门槛（tools/check_wording.py）。"""
    return check_wording.check()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    # v3.0.0 门禁分级：full=发布硬门槛（全部 61+ 项，CI 默认）；
    # fast=快路径轻量子集（本地开发提速），只保三条红线。
    ap.add_argument("--mode", choices=("full", "fast"), default="full")
    a = ap.parse_args()
    data = {
        "apk": apk_info(),
        "signature": signature_info(),
        "dex": dex_info(),
        "tests": test_info(),
        "source": source_info(),
        "elf_alignment": elf.check(APK),
        "drag": drag_gate(),
        "wording": wording_info(),
    }
    if a.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    apk, sig, dex, t, src, elfd = (
        data[k] for k in ("apk", "signature", "dex", "tests", "source", "elf_alignment")
    )
    print(f"| 核对项 | 实测值 |\n|---|---|")
    print(f"| 版本 | {apk['versionName']} (versionCode {apk['versionCode']})，包名 {apk['package']} |")
    print(f"| Release APK | {apk['bytes']:,} 字节（{apk['bytes']/1024/1024:.2f} MB） |")
    print(f"| APK SHA-256 | `{apk['sha256']}` |")
    print(f"| 权限数 | {apk['permissions']} 项（{', '.join(p.split('.')[-1] for p in apk['permission_list'])}） |")
    print(f"| ABI | {apk['abi']} |")
    print(f"| 签名 | verify={sig['verifies']}，v2={sig['v2']}，v3={sig['v3']}，证书 `{sig['cert_sha256']}` |")
    print(f"| 本工程可读类 | {dex.get('classes_own', 0)} 个 |")
    print(f"| 禁用 API 类型/方法引用 | {dex.get('banned_refs', {})}（全部应为 0） |")
    syms = dex.get("expected_symbols", {})
    print(f"| 新增 core 符号进 release dex | {syms}（全部应为 True，见 ADR-008） |")
    print(f"| 单测 | {t['cases']} 项 / 失败 {t['failed']} / 跳过 {t['skipped']} / {t['suites']} 个 suite |")
    g = data["drag"]
    print(f"| 拖动门禁 | setOnTouchListener 机制={g['uses_set_on_touch_listener']} · 旧 ScrollView 监听器已移除={g['no_old_scrollview_drag_listener']} · "
          f"禁用 broken onIntercept/onTouchEvent={g['no_broken_intercept_touch']} · 标题条移出 ScrollView={g['bar_moved_out_of_scroll']} · "
          f"祖先禁拦截={g['disallow_intercept_touch']} · 同步直提交={g['sync_commit_wired']} · "
          f"4dp 阈值={g['small_drag_threshold']} · 异步自锁残留={g['banned_async_state']} · "
          f"布局坐标空间标志={g['layout_flags_in_screen_wired']} · 边界单一真源={g['unified_screen_bounds']} · "
          f"可用区尺寸未用于收敛={g['no_app_area_bounds']} · 配置变化重定位={g['rotation_reposition_wired']} · "
          f"真源口径完整={g['bounds_source_single_truth']} · 偏差护栏={g['mismatch_guard_wired']} · "
          f"计数按规则隔离={g['mismatch_per_rule_isolation']} · 按次数停止指引={g['scene_stop_guidance']} · "
          f"范式作用域={g['loop_scope_single_truth']} · 判定与门控对齐={g['verdict_gate_alignment']} · "
          f"场景名可编辑={g['scene_name_editable']} · 位级焦点切换={g['focus_toggle_bitwise']} · "
          f"场景列表接线={g['scene_list_wired']} · 辅助门控自动派生={g['assist_auto_derived']} · "
          f"落盘字段不变={g['fields_unchanged']} · 术语归一={g['ui_term_clean']} · "
          f"术语单一真源={g['term_source_single_truth']} · "
          f"失败阶段化={g['stage_failure_graded']} · "
          f"重启解耦={g['restart_launch_decoupled']} · 采集码作用域={g['shot_error_scoped']} · "
          f"实测色门控={g['gate_observed_real']} · API 门限={g['android_api_guards']} · "
          f"Locale 安全={g['locale_safe_format']} · miss 存证={g['scene_miss_evidence']} · "
          f"锚点门控={g['gate_anchor_ready']} · 运行前置={g['run_precheck_wired']} · "
          f"首屏减法={g['panel_lean']} · 死场景可见={g['save_dead_scene_visible']} · "
          f"方向作用域={g['preflight_orientation_scoped']} · 包可见性={g['preflight_package_visibility']} · "
          f"录制坐标系={g['record_frame_live']} · 日志消歧={g['scene_log_unambiguous']} · "
          f"逐帧节拍={g['scene_frame_synced']} · 每轮新帧={g['scene_fresh_frame']} · "
          f"日志有界={g['scene_frame_log_bounded']} · "
          f"步进器={g['stepper_fields']} · 步进语义={g['step_policy_pure']} · "
          f"复用落点={g['use_current_point']} · "
          f"整屏可拖={g['pointer_capture_anywhere']} · 手势排除={g['edge_gesture_exclusion']} · "
          f"落点护栏={g['edge_safety_apply']} · "
          f"全局可收回={g['control_global_reachable']} · "
          f"装配运行同源={g['evidence_same_source']} · "
          f"方向无关接线={g['orientation_agnostic_wired']} · "
          f"判据参数接线={g['scene_tuning_wired']} · "
          f"回切降级={g['restore_failure_downgraded']} · "
          f"OCR边界告警={g['ocr_boundary_notified']} · "
          f"OCR类型隔离={g['ocr_type_isolated']} · "
          f"结论={'PASS' if g['ok'] else 'FAIL'} |")
    if not g["locale_safe_format"]:
        print(f"| ⚠ Locale 未收敛 | {', '.join(g['locale_offsenders'][:6])} |")
    print(f"| 源码包 | {src['files']} 文件 / {src['bytes']:,} 字节 |")
    print(f"| 源码包 SHA-256 | `{src['sha256']}` |")
    w = data["wording"]
    cats = " / ".join("%s %d 条" % (k, v) for k, v in w.get("categories", {}).items())
    print(f"| 表述规范化门禁 | {w['patterns']} 条禁用模式（{cats}）· 命中 {w['count']} 处 · "
          f"豁免 {len(w.get('waived', []))} 处 · {'PASS' if w['ok'] else 'FAIL'} |")
    if not w["ok"]:
        for h in w["hits"][:8]:
            print(f"| 命中 | {h['file']}:{h['line']} [{h.get('category', '?')}/{h['pattern']}] |")
    libs = elfd.get("libs", [])
    aligned = sum(1 for e in libs if e["aligned_16k"])
    print(f"| 16KB 页对齐(ELF LOAD) | {aligned}/{len(libs)} 个 so 对齐；"
          f"自编译产物全部对齐={elfd.get('own_aligned')}；"
          f"ZIP 条目 16KB(zipalign -P 16)={elfd.get('zipalign_16k')} |")
    if elfd.get("third_party_unaligned"):
        print(f"| ⚠ 第三方预编译 so 未对齐（本工程 CMake 不可控，明确列出） | "
              f"{', '.join(elfd['third_party_unaligned'])} |")
    # 签名 / 单测 / 禁用 API 是两种模式共同的基础红线。
    base_ok = (
        sig["verifies"] and sig["v2"] and sig["v3"]
        and t["cases"] > 0 and t["failed"] == 0
        and all(v == 0 for v in dex.get("banned_refs", {"x": -1}).values())
    )
    if a.mode == "fast":
        # fast 快路径子集：三条红线（坐标单一换算 / 不回退截图猜 / 不静默空转）
        # 由 v300_fast_path_wired 统一锁定；再加签名、单测、禁用 API 基础红线。
        # ADR-068：截图取证链隔离门禁（新链零取屏 + 单一出口）属架构红线，两种模式都查。
        # 不检查：61 项取证链静态门禁、表述门禁、elf 对齐、全部 dex 符号。
        ok = (
            apk["versionName"] == "3.0.4" and apk["versionCode"] == 72
            and base_ok
            and bool(g.get("v300_fast_path_wired"))
            and bool(g.get("v3000_screenshot_quarantined"))
            and bool(g.get("v3001_feature_capture_wired"))
        )
    else:
        # full：发布硬门槛，逐字保留原有全部条件（仅版本号随 3.0.0 更新）。
        ok = (
            apk["versionName"] == "3.0.4" and apk["versionCode"] == 72
            and base_ok
            and data["drag"]["ok"]
            # v2.9.2 / ADR-008：新增 core 能力必须真的编进 release dex
            and bool(syms) and all(syms.values())
            # 自编译 so 必须 16KB 对齐（这是 v2.9.0 的核心修复，纳入发版硬门槛）
            and elfd.get("own_aligned") is True
            # v2.9.16：表述规范化门禁纳入硬门槛，任何命中即判 FAIL
            and data["wording"]["ok"]
        )
    print("\n结论:", "PASS ✅" if ok else "FAIL ❌（请检查上表）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

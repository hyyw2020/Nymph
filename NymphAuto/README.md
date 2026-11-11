# NymphAuto

对标 **自动精灵 v26.5.0** 的离线自动化脚本工具：无障碍被动事件录制 + `.zjs` 脚本回放 + QuickJS 脚本引擎 + 端侧 AI/OCR。

当前版本 **v2.9.8（versionCode 26）**，minSdk 26 / targetSdk 36 / compileSdk 36，纯 `arm64-v8a`。

**v2.9.8 是「折叠球真能拖 + 悬浮窗结构性瘦身」版**：输入是两张真机截图与**第二次**反馈「悬浮窗还是这么大，收起后悬浮球仍然不能拖动」。上一版只调了 8%~13% 的常量、拖动修复也没落到真机行为上，本版把整条链路重查一遍。

- **P0 收起球仍拖不动（真因）**：`FloatingEditorService.onDragAbsolute` 把窗口位移**唯一地**交给 `Choreographer.postFrameCallback`，并用 `frameScheduled` 自锁；该标志只在 VSYNC 回调开头复位，一旦回调未触发（后台省电 / overlay 窗口静止）或回调内被判丢后再无 MOVE 续投，就永久为 `true`，此后每个 MOVE 都在入口 `return`，窗口再也不动。反证：主悬浮球 `RunnerService.attachBallTouch` 一直是同步 `updateViewLayout`，从未被投诉拖不动。修法：core 新增**无状态纯函数** `FloatingEditorLayout.commitDrag`（绝对坐标 + 1px 节流），Service 同步直提交，删除 `Choreographer` / `frameScheduled` / `pendingX/Y`；v2.8.1 抗抽搐成果（绝对坐标、不累加、不漂移）不回退——抽搐真因是「局部增量累加」，不是 update 频率。另把拖动启动阈值从系统 `scaledTouchSlop`（约 24px）降到 **4dp**（约 12px），点击仍用 slop 兜底，轻拖不再被误判成点击展开；折叠球按住放大给出可拖反馈。
- **P1 悬浮窗还是这么大**：v2.9.7 的 220dp / 0.54 / 85% 不透明仍是近半屏大黑块。本版 `EXPANDED_W_DP` 220→**168**（1080px 屏上占屏宽 61%→47%）、`MAX_H_FRACTION` 0.54→**0.40**、`GLASS_BG` alpha `0xD9`→**`0xB0`**（69%，明显透出底层）；4 个动作 chips 由 2×2 改**单行**、导航文案与内边距收紧（真机截图里按钮已被挤成竖排多行）、ROI / 滑动终点 / 高级数值字段收进默认隐藏的**「高级」区**（控件与功能一个不删，选「滑动」自动展开）、状态行两行→一行、间距 token 同步收紧。**展开态最大面积相对 v2.9.7 缩小约 43%**。
- **不回退的防线**：`verify_release.py` 新增静态门禁，禁止 `Choreographer` / `postFrameCallback` / `frameScheduled` / `pendingX` / `pendingY` 重新出现在 Service 的**代码行**（KDoc 里如实描述旧缺陷不受影响）；`commitDrag` / `dragStartPx` 纳入 release dex 硬门槛。

新增 3 例 core 单测，全量 **426/426**（48 suite）。**仍无真机复测**：折叠球拖动手感、69% alpha 的文字可读性、168dp 下按钮单行是否仍够宽、收紧高度后是否需滚动，待装机回归；不冒充已跑通。详见 `../NymphAuto-v2.9.8-发布说明.md` 与 `ENGINEERING.md` ADR-024。

### 历史版本

**v2.9.7 是「收起球可拖动 + 悬浮面板瘦身」版（历史）**：输入是三张真机对照截图（自动精灵小面板 vs NymphAuto 悬浮编辑面板）与反馈「交互界面傻大粗、收起后的悬浮球无法拖动」。

- **P0 收起球拖不动**：`FloatingEditorPanel.attachDrag` 的 MOVE 用 `4dp` 启动拖动、UP 却用 `8dp` 判定点击，中间 4~8dp 的位移既不进入 `dragging`、又满足「点击」条件 —— 抬手直接把 40dp 小球**展开成大面板**，体感就是「拖不动、一点就变大」。同时 `downRawX/Y/dragging` 是折叠球与顶部条**共享**的面板级字段，展开态拖动后收起会带着残留状态；点击分支 `return false` 还让 UP 未被消费。修法：判定收敛到 core 纯函数 `FloatingEditorLayout.isDragMove / isTap`（共用系统 `scaledTouchSlop`，严格互补、无中间窗口），折叠切换时 `resetDragState()`，折叠球消费整个手势。
- **P1 傻大粗**：真机像素量化，悬浮面板约占 **76.5% 屏宽 × 58.4% 屏高 ≈ 44.7% 屏幕面积**、背景 93% 不透明；竞品同场景约 49% × 22% 且半透明透出桌面。修法：`EXPANDED_W_DP` 240→220、`MAX_H_FRACTION` 0.62→0.54、`GLASS_BG` alpha `0xED`→`0xD9`；控件与功能一个不删。另修 `EditorScreen` 顶栏 5 个 TextButton 把标题挤成「编 / 辑 / 动 / 作」竖排四行（收紧内边距 + 标题强制单行）。

新增 3 例 core 单测，全量 **423/423**（48 suite）。**仍无真机复测**：收起球拖动手感、半透明观感、收紧后是否需滚动，待装机回归。详见 `../NymphAuto-v2.9.7-发布说明.md` 与 `ENGINEERING.md` ADR-023。

**v2.9.6 是「复杂操作可达」版**：输入是 48.87 秒横屏手游录屏（720×328 / 60fps）与差评「不支持类似复杂操作」。逐帧取证：视频是一个「战斗 → 约 12 秒全屏开箱动画 → 深色『获得奖励』结算 +『确认』→ 回主界面再战」的循环，**全程没有 NymphAuto 界面**——用户演示的是想被自动化的目标操作，不是回放失败实录。

但源码审计确认：**按产品内提示的推荐做法，这类操作必然失败**。

1. **承诺与实现不符（P0）**：编辑器循环弹窗写着「配合找图动作（会等待按钮出现）」，视频导入提示写着「会轮询等待按钮出现，不依赖固定延时」。实际 `CLICK_IMAGE` 的 `withRetry` 默认只等 **2000ms**（`retryWindowMs`，上限 60s 但设置页无入口），超时即判失败推进；视频导入更只生成 `waitMs = gap`（≤3 秒）的固定延时动作。面对 10 秒级转场动画，脚本必然在动画期间超时空转。
2. **能力存在但不可达（P0）**：引擎早已有 `Condition(APPEAR_IMAGE)` + `repeatCheckUntilOk` 轮询，且轮询里带中止条件检查点、等待浮层与看门狗心跳；缺的只是「把这条条件写进脚本」——`VideoScriptBuilder` 不生成，编辑器也没有配置入口。
3. **修复**：core 新增 `WaitGate`（等待画面出现的单一真源，纯 JVM 可测）；`VideoScriptBuilder` 默认开启**门控模式**，每个有模板的动作自带「等到该画面出现 → 再点」的条件，固定延时归零，脚本从定时回放变成**事件驱动状态机**，`waitForAppearance = false` 可完全回到 v2.9.5 行为；`Action.waitAppear` 让手动建的找图动作也能由引擎注入同一门控；编辑器与视频导入的提示改为如实描述。
4. **顺带修两个陷阱**：`repeatCheck` 无条件取 `minOf(脚本间隔, 调参间隔)`，帧同步档会把十几秒的长等待压成 33ms 全屏找图，无限等待现改按脚本节奏（core `WaitGate.pollIntervalMs` 可单测）；候选触点原本**默认全选 60 个**，含全屏特效的游戏视频会检出大量动画噪声，全选 + 无限等待会让脚本卡在永不再现的动画帧上，现改为按时间分桶默认勾选最多 6 个覆盖各阶段的候选。

**可达性实测**：用这段真视频复现候选检测，结算阶段（43–48s）检出 18 个候选、战斗阶段 11 个，用户能勾到关键按钮；门控随后会自己跳过中间的随机动画。新增 15 例单测，全量 **420/420**（48 suite）。**仍无真机复测**；门控是无限等待，请设脚本中止条件或用通知栏停止；找图模板仍来自用户勾选的候选点，不承诺自动识别自绘界面按钮。详见 `../NymphAuto-v2.9.6-发布说明.md` 与 `ENGINEERING.md` ADR-022。

**v2.9.5 是「拾取浮窗抽搐」修复版（历史）**：输入是用户真机录屏（578×1280 / 44fps / 41 秒）反馈「抖动抽搐」。逐帧分析 + 源码定位确认根因并修掉：

1. **手柄拖动用「相对窗口增量」，被窗口自身移动抵消（P0，抽搐根因）**：`PickerHandleView.onTouchEvent` 用 `e.x/e.y`（相对 View 坐标）算拖动增量，而**手柄窗口本身就在跟随手指移动**——窗口每动一次，下一帧的相对增量就被抵消掉，手柄于是「一顿一顿」地滞后跳动。视频证据：绿色手柄明显不与粉红准星重合（停在准星上方约 70px），逐帧位移方向反转率高达 **72%**。这与悬浮编辑器 v2.8.1 的 `dragAbsolute` 治理是同一个病，但**只治了悬浮编辑器、漏了拾取链路**（ADR-012 盲区）。
   修复：改用**屏幕绝对坐标 + 抓取偏移**（core `PickerGeometry.placeFromGrab`），目标位置与窗口位置完全无关——零丢增量、零累积漂移，手柄与准星恒定重合。
2. **同一帧内反复改窗口位置（P0）**：触控采样 120Hz 高于刷新 60Hz，每个 MOVE 都 `updateViewLayout` + `invalidate`，同帧内多次改位置本身就是抖动来源。加 **1px 提交阈值**（`PickerGeometry.shouldCommit`）+ **`Choreographer` 按帧合并**，抬手时立即落地最后一帧。
3. **拾取浮层残留窗口（P1）**：`show()` 只持有单个 session 引用，一旦 `start()` 中途抛异常，已 `addView` 成功的窗口成为「无主窗口」永远无法移除，与新会话叠加。改为**活跃会话登记 + `show()` 前全量拆净 + 异常路径当场 detach**。

新增 3 例 core 单测（绝对定位不累加 / 越界夹逼 / 提交阈值），全量 **405/405**（47 suite）。**修复后仍无真机复测**——本次只拿到问题录屏作为证据，手感最终确认要等下一份真机录屏。

**v2.9.4 是「不改功能的缺陷修复」版（历史）**：按「不要改变功能，检查 bug、检查代码陷阱」的要求，通读全部 Kotlin 源码做了一轮静态排查，只修正确性缺陷 —— 不新增任何能力、不动 UI 与交互语义、不动五条硬契约。共修 12 处：

1. **录制面板拖动实际不可用（v2.9.3 引入的真 bug）**：`onTouchEvent(ACTION_DOWN)` 返回 false，落点在状态/提示文字这类非 clickable 区域时 DOWN 未被消费，后续 MOVE 不再派发给面板，面板根本拖不动。把拖动手势抽成 core 纯 JVM 状态机 `DragGesture`（可单测），面板只转发事件；同时修掉增量基准被反复覆盖导致的首帧跳变。
2. **拖动方向反了 + 悬浮球可丢失**：`gravity=TOP|END` 下 `p.x` 是距右边缘的偏移，旧代码 `p.x + dx` 让水平拖动与手指相反，改为 `p.x - dx`；运行悬浮球补屏幕内 clamp（旧版可拖出屏幕后永久失联）。
3. **空脚本 + 无限循环空转**：`ScriptRunner` 主循环在动作列表为空且 `repeatCount=0` 时永不结束、`afterScript` 不执行、通知常驻；负 `repeatCount` 也被当成无限循环。收敛到 core `LoopPolicy.isFinished()`。
4. **原子写失败静默报成功**：`ScriptStore.save()` 忽略 `AtomicTextFile.write()` 的返回值，磁盘满/目录不可写时上层误报「已保存」，脚本实际丢失；现在显式抛 `IOException`，走既有错误提示路径。
5. **数值与边界陷阱**：`RecorderBus.signal()` 计数 `toInt()` 溢出成负数会让录制可行性误判；`JitterSampler` 的 `exp` 溢出成 `Long.MAX_VALUE` 会把脚本卡死在 `delay(Long.MAX_VALUE)`；自定义 `minGapMs > maxGapMs` 时 `coerceIn` 抛空区间异常；`VideoFrameExtractor` / `ScreenSource` 遇 0 尺寸帧会整数除零或越界；`randomWaitMs + 1` 溢出致 `Random.nextLong(负数)` 崩溃；`LogScreen` 读盘一次异常即永久停更。

新增 11 例 core 单测，全量 **402/402**（47 suite）。`verify_release.py` 把 `DragGesture` 纳入 release dex 硬门槛（ADR-008）。详见 `../NymphAuto-v2.9.4-发布说明.md` 与 `ENGINEERING.md` ADR-020。**真机验证仍为零**，拖动手感与配色的实际观感待回归。

**v2.9.3 是「浮窗视觉统一 + 录制面板可拖动」版（历史）**：输入是 2026-11-10 用户转发的一整条录制链路通读分析 —— 从事件采集（RecorderBus/RecordGate）、锚点选择（AnchorSelector）、编译去噪（RecordCompiler）、能力边界判定（RecordabilityGuard）、回放界面守卫（SceneGuard）到 6 个交互界面，专家给每个界面打了分（整体 8.9/10），并指出两个最该改进点：**(1) 三套浮窗视觉语言分裂**（录制面板红 `#EF4444`、拾取手柄绿 `#059669`、运行悬浮球灰，各写各的硬编码字面量，无统一 token）；**(2) 录制控制面板钉死在右上角不可拖动**，若目标 APP 关键按钮恰好在右上角会被遮挡，而「不干扰其他 APP」的契约本该覆盖所有浮窗。

本版**不推倒地基、不动五条硬契约**，只补这两个确定性缺口：

1. **浮窗视觉单一真源（core `NymphDesignTokens` 新增 12 个浮窗色槽 + app `OverlayTheme` 投影）**：录制控制面板、运行悬浮球、坐标拾取手柄三套浮窗统一引用 `OverlayTheme`，删除全部硬编码字面量；改 `NymphDesignTokens` 一处，所有浮窗同时变。`OverlayThemeTest` 3 例 + `NymphDesignTokensTest` 扩 1 例锁死「浮窗色板不重复定义、且全部来自真源」。
2. **录制面板可拖动（复用拾取手柄拖动手感）**：`RecordPanel` 用 `onInterceptTouchEvent`/`onTouchEvent` 实现拖动，触摸斜率超过阈值才认定为拖动，**绝不误伤按钮点击**；`RecordingService` 面板 params 加 `FLAG_LAYOUT_IN_SCREEN`/`FLAG_LAYOUT_NO_LIMITS` 并接线 `onDrag` 做屏幕内 clamp，只改 `x/y`、**不改 flags、不拦截外部触摸**——「不干扰其他 APP」对录制面板同样成立。

新增 4 例单测，全量 **391/391**（46 suite）。权限 8 项零新增、禁用 API 全 0、**961 个**本工程类全保留可读名、v2/v3 同钥可原地升级；`verify_release.py` 把 `OverlayTheme` 也纳入 release dex 硬门槛（ADR-008）。详见 `../NymphAuto-v2.9.3-发布说明.md` 与 `ENGINEERING.md` ADR-019。**真机验证仍为零**：8 秒告警/拖动手感/统一配色在真机上的实际观感待下一份真机回归包确认；被动录制能力边界未变（不承诺自动识别自绘界面按钮），找图模板仍来自用户截图/视频。

**v2.9.2 是「自绘界面可自动化」版（历史）**：输入是 2026-11-10 的 40.3 秒横屏手游录屏（1280×578）+ 差评「不满意，不能实现类似操作」。逐帧核对后，视频是**连续「扫荡 → 开箱结算 → 领取 → 再扫荡」循环**：界面结果随机、动画时长不定、循环次数不固定，且全程**没有 NymphAuto 的控制面板**——用户是在演示「想要被自动化的操作」，不是展示回放失败。以源码为准定位后确认，这是**能力边界 + 产品可发现性**问题，不是「录制不够准」：

1. **自绘界面被动无障碍录制物理上收不到点击（根因，不是 bug）**：Unity / Cocos 游戏、视频播放器、地图、全绘页面里的「按钮」不是 Android View，系统不会派发 `VIEW_CLICKED` / `VIEW_SCROLLED`。而第 5 条硬契约要求「录制 / 回放不打断其他 APP：只订阅被动语义事件，不拦截、不重排触摸」——**不能**靠加 `FLAG_WATCH_OUTSIDE_TOUCH`、全屏透明覆盖层或触摸浏览来「修复」，那会打断其他 APP 的触摸分发。用户照常操作几十秒，停止后只得到「未录到有效动作，已取消」，既不知原因也不知替代方式，于是认为「不能实现类似操作」。
2. **引擎本来就能做，但三个入口全缺**：`Script.repeatCount`（`0` = 无限循环）、`GOTO`、中止条件、`CLICK_IMAGE` 找图点击（带轮询重试、会等待按钮出现而不依赖固定延时）**早已实现**，正好覆盖这类「循环次数不固定、结算动画时长随机」的重复操作。但（a）录制失败时零诊断零指引；（b）`repeatCount` 在界面上**完全没有入口**，只能手写 JSON；（c）「视频导入」生成的脚本 `repeatCount` 恒为 1、只跑一遍，且这条路径没被指出来。

本版**不推倒地基、不动五条硬契约**，只把已有能力接通到用户手上，三项落地：

1. **录制可行性判定（core `RecordabilityGuard` 纯 JVM 单一真源）**：把录制期信号（`armed` / 交互事件 / 窗口事件 / 总事件 / 时长）判定为 `WAITING` / `NO_EVENTS` / `SELF_DRAWN` / `LOW_SIGNAL` / `RECORDABLE` 五态，输出**必达且可操作**的文案——明确说明「这是能力边界而非脚本错误」，并指向工程里真实存在的「视频导入 → 生成找图脚本 → 设置循环」路径。判定与文案由 14 例单测锁死。
2. **录制链路接线（必达但不打扰）**：`RecorderBus` 新增交互/窗口分类计数与 `signal()`（不改变回调立即返回的轻量契约）；`RecordPanel.setHint()` 承载告警；`RecordingService` 在进入目标 APP 8 秒后若持续收不到交互事件，**只提示一次**（面板 hint + 通知栏 + toast + 日志页四通道），**绝不阻断录制、绝不改变窗口 flags 或触摸分发**；空脚本收尾不再只说「已取消」，而是给出根因 + 替代路径 + 系统诊断。诊断日志页也同步带上指引。
3. **循环一等入口**：新增 core `LoopPolicy`（`0`=无限 / 1~9999 归一化 / 中文「无限」解析，6 例单测）；`EditorScreen` 脚本名下方新增可点击「循环」状态条 + 对话框（1 次 / 10 次 / 无限，纳入撤销栈）；「视频导入」对话框新增循环选择 chips；`VideoScriptBuilder.build()` 加 `repeatCount` 参数（默认 1，老调用零回归）。找图 + 循环组合即可自动反复执行「扫荡 → 结算 → 领取」。

新增 24 例单测（`RecordabilityGuardTest` 14 / `LoopPolicyTest` 6 / `VideoScriptBuilderTest` 补 4），全量 **387/387**（45 suite）。权限 8 项零新增、禁用 API 全 0、**959 个**本工程类全保留可读名、v2/v3 同钥可原地升级；`verify_release.py` 新增硬门槛：两个新 core 符号必须真的编进 release dex（ADR-008）。详见 `../NymphAuto-v2.9.2-发布说明.md` 与 `ENGINEERING.md` ADR-018。**真机验证仍为零，且不承诺「自动识别自绘界面按钮」**：被动录制的能力边界未突破，本版交付的是「如实告知边界 + 把可实现的替代路径变成一键可达」；找图模板仍来自用户截图/视频，无限循环需用户自设中止条件或手动停止。

**v2.9.1 是真机回放保真度 bugfix 版（历史）**：输入是 2026-11-10 的真机调试包（99 秒 120fps 录屏 + 运行日志 + 5 张失败截图）。逐帧核对录屏与日志后锁定一条完整证据链：用户在**应用抽屉**里滚动找应用（97 个 `VIEW_SCROLLED` → 编译成 **38 个 SWIPE 翻页**），回放时却停在**桌面主屏 / 日历小组件 / 搜索历史页 / 输入法键盘**——38 个翻页手势全部打在错误界面，还误触搜索框弹出键盘，两次回放都被用户手动停止。录屏 t≈16~26s（抽屉按 A–Z 滚动）与 t≈50~82s（回放乱翻页）逐帧可辨；日志两次总结都是「命中 0、跳过 0、失败 0」。以源码为准定位并修复三个确定性缺陷：

1. **回放界面守卫在「同包名不同页面」下结构性失效（主因）**：`SceneGuard` 只比对 `packageName`，而应用抽屉、桌面主屏、搜索页**同属一个 launcher 包名** → `recorded == current` 判为界面一致直接 `PROCEED`；`rootInActiveWindow()` 取不到包名、或回放瞬间前台仍是 NymphAuto 自己 → `SKIP`。两条路径都**静默放行**，日志里连一行界面校验都没有。讽刺的是该文件头注释写的正是「用户在应用抽屉里连续翻页录制，回放却停在桌面/日历」这一场景。修复：新增 core 纯 JVM `hasPositionalActions` 判定与 `PROCEED_WITH_WARNING` 决策，把「同包名≠同界面、被动录制无法还原页面与滚动位置」升级为**执行前必达告警**（日志页 + 运行日志 + toast 三通道）。刻意不阻断执行——同包名在原理上无法证明界面一致，拦死会误伤已手动回到正确界面的正常回放；误报一条警告代价远小于拦死脚本。**守卫关闭 / 老脚本无起始包名**时同样送达，不再让编译期告警只躺在 `Script.description` 里。
2. **执行总结全是 0（可观测性 bug）**：旧统计类放在 app 层 `ScriptRunner` 内部，`hit/miss` 只在 `withRetry()`（找图/找色/找文字命中）里自增；SWIPE / TAP 这类**直接派发、无目标校验**的手势走 `dispatch()`，不计入任何口径；`executed` 字段定义了却**从未自增**。于是纯翻页脚本必然报「命中 0、跳过 0、失败 0」，用户以为脚本没跑。修复：把 `RunStats` 抽到 core 纯 JVM（新增 `core/runner/RunStats.kt`），主循环无条件自增 `executed`，总结改为「已执行 N 步，命中 X、跳过 Y、失败 Z」；纯手势脚本额外如实标注「无目标校验，命中/失败口径不适用」，**不把坐标手势伪装成命中**。
3. **诚实边界**：被动无障碍录制拿不到手指真实轨迹与页面/滚动位置，本版**不承诺**「自动把界面还原到录制时的抽屉滚动位置」——那是 ADR-002 已确认的能力边界，不做虚假修复。真正落地的是「不再静默放行、不再报全 0、把风险明确送达用户」。5 张 `miss_目标图片` 截图经时间戳核算为 **2026-11-07 17:13 的历史文件**（距本次 2.5 天，是 APP 未清理的旧失败现场），画面为短视频信息流的**动态视频封面** + 分享面板/控制面板遮挡，属找图能力边界，**不作为本次会话证据**。

新增 17 例单测（`SceneGuardPositionalTest` 11 / `RunStatsTest` 6），全量 **363/363**（43 suite）。新增 core `RunStats` 单一统计真源；`ScriptRunner` / `RunnerService` 接线 `onSceneNotice`。权限 8 项零新增、禁用 API 全 0、**930 个**本工程类全保留可读名、v2/v3 同钥可原地升级。详见 `../NymphAuto-v2.9.1-发布说明.md` 与 `ENGINEERING.md` ADR-017。**真机验证仍为零**：本版修复由真机调试包的录屏+日志驱动，但修复效果本身待下一份真机回归验证，不冒充已在真机跑通。

**v2.9.0 是「平台合规 + 可验证内核」版**：本轮输入是若干外部 agents 的改进建议，逐条核实后**不推倒地基**（core 纯 JVM、零 Android 依赖、100% 单测，以及零 INTERNET / 不加密明文 `.zjs` / 不混淆 / 不抢录屏 / 不打断其他 APP 这五条契约，是可审计、已验证的资产，推倒等于把已验证丢回未验证）。实际落地四件收益确定、可在本环境被真证明的事：

1. **16 KB 页对齐（P0 真缺口，实测驱动）**：解包 v2.8.1 产物实测发现，自编译的 `libquickjs-jni.so` 的 ELF LOAD 段 Align 仍是 `0x1000`(4KB)，而 `build.gradle.kts` / README 却把 `useLegacyPackaging=false` 注释成「16KB 页大小对齐」——**文档与产物不符，且无人发现**（那一项只管 ZIP 条目对齐，与 ELF 段对齐是两回事）。Google Play 自 2025-11-01 对 targetSdk 15+ 强制要求 16 KB 支持，本工程 targetSdk 36 已命中。修复：`cpp/CMakeLists.txt` 加 NDK r27 官方 linker flags（`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`）重编，实测该 so 已变为 `0x4000`(16KB)；新增 `tools/check_elf_alignment.py` 并接入 `tools/verify_release.py`，**每次发版实测每个 so 的 LOAD 段对齐，自编译 so 未对齐直接判 FAIL**，把合规从注释变成机制。6 个 so 中 5 个对齐；唯一遗留是 TFLite Task Vision 0.4.4 预编译的 `libtask_vision_jni.so`（仍 `0x1000`），本工程 CMake 不可控，**如实列出不冒充修复**——16 KB 页设备上该 so 加载会失败，`AiDetector` 已用 `runCatching` 优雅降级为 AI 找目标不可用、其余能力照常，并补了针对性归因日志。彻底解决需等 LiteRT/上游提供对齐的预编译库或改用单一 ONNX 运行时（需真机验证检测精度）。
2. **设计 token 单一真源**：新增 core 纯 JVM `NymphDesignTokens`，Compose 主界面 `Theme.kt` 与悬浮层 `FloatingEditorTheme` 的色板/尺寸/字号全部引自同一对象；后者降级为「投影层」（保留 v2.8.1 全部公开名，视觉值一字节不变）。消除「同一个警告色两处各写一遍、靠约定维持」，并由单测锁死两处同源、对比度、字号层级、语义色可区分。
3. **窗口规格约束函数化**：新增 core `OverlayWindowSpec`，把 v2.8.1 靠注释保证的「flags 恒定」变成可执行约束——只允许 `NOT_FOCUSABLE|NOT_TOUCH_MODAL` 与 `NOT_TOUCH_MODAL`（输入焦点临时态）两种取值，`FloatingEditorService` 的 `baseParams()` / `setInputFocusable()` / 按帧拖动提交全部接线；拖动提交前用 `isPositionOnlyUpdate` 校验「只改 x/y、flags/type/format/width/height 逐字不变」，脏规格直接丢弃该帧。**同时如实修正文档表述**：此前 README/ADR 写「flags 全程恒定」，但 `setInputFocusable()` 本就会受控切换，准确说法是「只允许两种受控状态、只由输入焦点触发」。
4. **原子落盘 + schema 迁移链**：新增 core `AtomicTextFile`（临时文件→fsync→rename）接入 `ScriptStore` / `ScriptRepository` / `ScriptGrants`，修掉「编辑中进程被杀留下半截文件、脚本凭空消失」这个真实数据风险；新增 `ScriptMigrations` 接入 `ScriptCodec.decode`，让早已有字段但一直空设的 `schemaVersion` 真正起作用——未来版本脚本被明确挡下而非被 `ignoreUnknownKeys` 静默当 v1 打开、字段按默认值补齐却行为全错。

新增 27 例单测（`NymphDesignTokensTest` 6 / `OverlayWindowSpecTest` 6 / `AtomicTextFileTest` 7 / `ScriptMigrationsTest` 8），全量 **346/346**（41 suite）。权限 8 项零新增、禁用 API 全 0、v2/v3 同钥可原地升级、新符号已确认进 dex。**明确否决（判断为收益小于风险或不可验证）**：端侧 VLM 替换识别链（按需下载违背零 INTERNET、秒级延迟拖垮回放）、全 Compose 化悬浮层（命中 AndroidX issue 399131921「无障碍服务 + 非 Activity 窗口 + LazyColumn fling」，且会让 v2.8.1 刚修好的抗抽搐回到未验证）、预编译手势队列 <1ms / 零拷贝 HardwareBuffer 直通 ONNX（无对应 API，属幻觉）、全量升 compileSdk 37 / AGP 9 / KMP / PrimJS（本环境 SDK 封顶 36、离线依赖固定，升了构建即断且无法验证）。详见 `../NymphAuto-v2.9.0-发布说明.md` 与 `ENGINEERING.md` ADR-013～016。**真机验证仍为零**，本环境无 Android 设备，不冒充已验证。


**v2.8.1 是差评修复版**，专修 v2.8.0 发布后反馈的「一直在抽搐，界面也不美观」。本次对比视频**成功取回**（19.6MB 真 MP4，720×328 / 60fps / 142s），逐帧像素差分析与连续帧放大拼接给出可见证据：连续帧里悬浮面板的**左/上边缘位置在跳动**、**可见内容范围忽多忽少**（有的帧只到「屏幕拾取起点」、有的多出「框选 ROI」一行），即窗口在反复 relayout；画面同时坐实深灰实心方块按钮、10.5sp 小字、拥挤排版与 `⠿`/`▲` 缺字字符。以源码为准锁定 **5 个确定性根因**：① 拖动用局部坐标增量 `e.x-lastX` 累加、且每个 `ACTION_MOVE` 都 `updateViewLayout`，窗口一重布局局部坐标就与屏幕位移脱钩 → 跟手抖动 + 累加漂移；② `height=WRAP_CONTENT` 叠加 60~120Hz 全窗口 relayout，尺寸反复重算；③ `MaxHeightScrollView.onMeasure` 对 `UNSPECIFIED` 取 `getSize()=0` 再构造 `AT_MOST(0)`，把内容高度压成 0、与父布局反复博弈（正是「内容忽多忽少」的直接原因）；④ `setCollapsed()` 每次切 `FLAG_NOT_FOCUSABLE` 并重复 `startForeground()`，flags 变更触发窗口重建/焦点变化 → 软键盘弹出收起 → 沉浸式可用高度变化 → **循环抽搐**；⑤ 每次 MOVE 重读 `displayMetrics`，全屏游戏状态栏切换时 `heightPixels` 跳变让 clamp 边界跳。修复：拖动改用**屏幕绝对坐标 `rawX/rawY` + 按下时窗口坐标**（`FloatingEditorLayout.dragAbsolute`，不累加、不依赖任何 View 局部坐标系），`Choreographer` **按帧合并**窗口更新、`shouldCommit` 过滤 <1px 无效变化；`FLAG_NOT_FOCUSABLE` **全程恒定**，仅在 `EditText` 真正获焦时按需临时申请焦点（`setInputFocusable`）；`measureHeight` 按 `MeasureMode` 正确处理 `UNSPECIFIED`；状态行固定最小高度、屏幕尺寸缓存（`refreshMetrics`）、前台通知去重不再随状态切换重复 `startForeground`。视觉按新增纯 JVM `FloatingEditorTheme` **设计 token 单一真源**重做：深墨蓝玻璃卡片 + 单一翡翠绿强调、次级按钮改描边/低饱和底、胶囊 chips、统一圆角/间距/字号层级（13/12.5/11/10.5sp 严格递减）、去掉全部缺字字符，token 的层级与对比度由 JVM 单测约束而非肉眼猜。新增 `FloatingEditorThemeTest` 5 例 + `FloatingEditorLayoutTest` 补 5 例（绝对坐标不漂移/越界 clamp/提交阈值/UNSPECIFIED 不再压成 0/三种测量模式），全量单测 **319/319**（core 287 + app 32，37 suite）。权限 8 项零新增、禁用 API 全 0、**921 个**本工程类全保留可读名、v2/v3 同钥可原地升级，包体较 v2.8.0 净增 16,384 字节，`FloatingEditorTheme` / `MeasureMode` 等新符号已确认进 dex。**真机验证仍为零**：按帧节流的实际跟手度、软键盘与焦点在各 ROM 的表现、前台服务保活、折叠/展开/拖动手感，仍待真机回归，不冒充已验证；视频只用于定位「窗口反复重算」这一现象，未据其推断任何未在源码中确认的行为。详见 `../NymphAuto-v2.8.1-发布说明.md` 与 `ENGINEERING.md` ADR-012。

**v2.8.0 是差评修复版**，专修「编辑动作的整个界面不支持脱离 NymphAuto 在其他 APK 界面运行」与「界面太大」两条反馈。用户附了一份「系统级悬浮窗（System Overlay / 后台可显示）vs 前台绑定悬浮窗（Activity 生命周期绑定）」的资料并要求**以源码为准**，核实后本工程其实是**混合架构**，比资料的二分法更细：① 被差评的 `ActionEditDialog` 是 Compose `AlertDialog`，画在 `MainActivity.setContent{}` 的视图树里，**连独立 Dialog 窗口都不是**，切后台随 `onStop` 消失、无法在其他 APP 之上呼出——差评属实；② 而 `CoordinatePicker` 非模态拾取、`FloatBall`+`BallPanel` 运行控制、`RecordingService` 录制浮窗早就走 `TYPE_APPLICATION_OVERLAY` + `specialUse` 前台服务，是真正的系统 Overlay。所以问题不是「缺悬浮窗权限」（`SYSTEM_ALERT_WINDOW` 早有、8 项权限零新增），而是**编辑动作这套界面没走系统 Overlay 通道**。修复：新增 `FloatingEditorService`（`specialUse` 前台服务）用 `WindowManager.addView` 以 `TYPE_APPLICATION_OVERLAY` 承载纯 View 面板 `FloatingEditorPanel`，切后台 / 切任意第三方 APP 持续存在、通知栏可「呼出 / 收起 / 关闭」；脚本卡片菜单「悬浮编辑」、编辑器顶栏「悬浮」、设置页「关闭浮窗」三处入口，点入口主动 `moveTaskToBack(true)`。面板 `FLAG_NOT_TOUCH_MODAL`，面板之外照常操作底层 APP。**紧凑化**：折叠态 40dp 小球（< 屏宽 15%），展开态固定 240dp 宽、高度上限屏高 62%（`MaxHeightScrollView.onMeasure` 真正限高，而非只在 clamp 计算里写个数字），高级字段默认折叠进「更多参数」，原 Activity 内 `AlertDialog` 同步限宽 340dp。**复用而非重写**：脚本读写走 `ScriptStore` / `ScriptRepository`、动作保存走 `ActionEditGuard.apply`（`copy` 保真，不丢 `children` / `listeners` / `condition` / `imageData`）、ROI 走 `RoiApplier.apply`（唯一真源 + 命中数如实反馈）、坐标拾取复用 `CoordinatePicker`（拾取期间面板收成小球）。确定性逻辑（尺寸档位 / 折叠 / 位置 clamp / 拖动 / 表单解析 / Locale）抽到纯 JVM `FloatingEditorLayout`，新增 **12 例单测**。**过程中修掉两个真坑**：① 展开态不能加 `FLAG_NOT_FOCUSABLE`，否则悬浮窗里 `EditText` 抢不到焦点、弹不出软键盘（改为折叠态加、展开态去掉）；② 拖动不能写在根 View 的 `onTouchEvent`（`LinearLayout` 会先把 DOWN 分发给子控件，根 View 收不到 MOVE），改为给折叠小球与顶部拖动条各装独立 `OnTouchListener`。全量单测 **309/309**（core 277 + app 32，36 suite），权限 8 项零新增、禁用 API 全 0、v2/v3 同钥可原地升级，`FloatingEditorService` / `FloatingEditorPanel` / `FloatingEditorLayout` / `MaxHeightScrollView` 等新符号已确认进 dex（906 个本工程类全保留可读名）。**真机验证仍为零**：本环境无 Android 设备，Overlay 在各 ROM 的触摸分发、软键盘弹出、`specialUse` 保活、通知栏呼出、折叠 / 展开 / 拖动手感、面板与拾取器同时显示的层级**全部待真机回归**，不冒充已验证；所给对比视频链接缺有效参数、4 种取回方式全部失败，未据其做任何推断。详见 `../NymphAuto-v2.8.0-发布说明.md` 与 `ENGINEERING.md` ADR-011。

**v2.7.1 是 bugfix 版**，专修 v2.7.0 发布后反馈的「屏幕拾取起点（非模态）」与「框选搜索区域 ROI / 模板范围」两个功能都有 bug：① 修 `DisposableEffect(pickStamp)` 用会自增的 key 导致浮层刚 `show()` 就被 `onDispose → dismiss()` 关掉（改恒定 `Unit` + `resultSeq` 去重兜底）；② 准星从「手柄窗口中心反算」改为**独立像素真值**（新增纯 JVM `PickerGeometry`），消除工具条展开/贴边夹逼导致的坐标跳变；③ 框选 ROI 从「只写当前坐标动作的 region、运行时根本不读（白框）」改为用纯 JVM `RoiApplier` **真正应用到脚本内所有读取 region 的位置**（找图/找色/找文字/AI/节点动作、条件树、children、listeners、中止条件），如实显示命中数；④ 修会话内点/框切换丢结果（统一投递回调）、`show()` 幂等吞请求、已有 region 不带入、撤销只恢复 actions 等一批缺陷；⑤ 全部 `"%.4f".format` 统一为 `Locale.US`（`PickerGeometry.fmt`），修德语/法语区小数坐标显示成 `34,5678` 被回填解析清零、ROI 表达式被逗号切碎的隐蔽 bug。新增 3 个测试类 21 例 + 1 例 Locale 回归；同时修 `verify_release.py` 此前只统计 `test-results/test/`（漏 app 单测）的口径 bug，全量单测 **297/297**（core 265 + app 32，35 suite）。权限 8 项零新增、禁用录屏 API 引用 0、v2/v3 同钥可原地升级。**真机验证仍为零**：本版修的是从代码链路读出的确定性逻辑缺陷 + JVM 单测，浮层/窗口/各 ROM 仍待真机回归，详见 `../NymphAuto-v2.7.1-发布说明.md` 与 `ENGINEERING.md` ADR-009/010。

v2.7 是**针对汇总反馈的全面改进**：① 坐标拾取从全屏模态改为**非模态穿透双窗口**——全屏绘制层 `FLAG_NOT_TOUCHABLE` 只画不挡触摸，独立 30dp 可拖动小手柄 `FLAG_NOT_TOUCH_MODAL`，**手柄之外照常操作底层 APP**；同时新增框选区域（`PickerRegion`）、局部像素放大镜、控件/颜色中心吸附、±1px 长按连发、标尺开关。② 识别从硬编码 300ms 提速到**帧同步 33ms + `MotionPredictor` 运动外推 + 窄 ROI**，设置页可退回 `LEGACY_300`；`Region.expr` ROI 表达式在 8 个找图/条件调用点接线。③ 修掉「录制刚启动就在点击器里自己记自己」真 bug（`RecordGate` 自扰过滤 + 待命态）与编辑器**数据丢失级 bug**（非 4 类动作被静默改成点击、`children`/`listeners` 被清空），新增步骤/源码双视图、实时语法校验、撤销重做。④ 脚本压缩容器 + `ScriptRepository` 版本/回滚（UI 入口「历史版本」）、mp4 半自动导入生成特征脚本。⑤ 新增 `ENGINEERING.md`（8 条 ADR + 路线图 + 验收状态）与 `tools/verify_release.py`（数字自动核对），并发版前做**接线扫描**补齐 `RoiExpr`/`ScriptRepository` 的生产链路。单测 **248/248**（新增 9 类 52 例），权限表无新增，包体较 v2.6 净减 24,775 字节。**三条如实边界**：1ms 找坐标受截图帧率物理上限不可行、mp4 无触点标签只能半自动、本环境无真机故所有版本仍未真机验证（instrumentation 测试仍为零），详见 `../NymphAuto-v2.7.0-发布说明.md` 与 `ENGINEERING.md`。（历史：v2.6 坐标拾取 + 单步编辑器；v2.5 录制回放保真度；v2.4 动作可视化；v2.3 修 4 个录制/找图真 bug；v2.2 修 6 个真机回归；v2.1 工具链升级；v2.0 Compose 界面重写。）

v2.6 新增**坐标拾取 + 单步动作编辑器**（对标参考录屏里「录脚本」与「一步步添加动作」两条路径）：此前脚本只能靠被动无障碍录制或导入 `.zjs`，无法精确指定落点。新增两条能力：① **超高精度坐标拾取浮层** `CoordinatePicker`——用户主动点「屏幕拾取」后弹出全屏 `TYPE_APPLICATION_OVERLAY`，顶部/左侧 0–100 百分比标尺、可拖动准星、实时读数（像素整数 + 百分比 4 位小数）、±1px 微调，确认后把 `PickerResult` 回传编辑器自动填入；② **单步动作编辑器** `EditorScreen`（沿用原 Material3 主题）——新建脚本或从脚本列表「编辑动作」进入，可添加/编辑/删除/上移/下移动作，类型覆盖点击/长按/双击/滑动，坐标支持手输或拾取，参数含按下时长、滑动时长、重复次数、执行前等待、描述，保存为明文 JSON。**关键边界**：拾取浮层是**编辑器态、用户主动触发、确认/取消即销毁**的模态层，不进录制/回放链路、不订阅任何无障碍事件、不常驻、不加 `FLAG_WATCH_OUTSIDE_TOUCH`，与「录制不打断其他 APP」的硬约束并不冲突（详见四条硬约束第 4 条）；回放期 `VisualizerLayer` 仍是 `FLAG_NOT_TOUCHABLE`，两者不会同时存在。新增 5 例单测（`EditorFormatTest`），累计 **219/219**。权限表无新增、包体增量 32,768 字节。（历史：v2.5 录制回放保真度；v2.4 动作可视化；v2.3 修 4 个录制/找图真 bug；v2.2 修 6 个真机回归；v2.1 工具链升级；v2.0 Compose 界面重写。）

v2.5 修复**录制回放动作失真**（真机录屏复现）：用户在应用抽屉里 12 秒连续翻页，回放却在日历界面执行 15 次慢速滑动并误触打开内容页。根因是被动无障碍录制只上报「滚动容器中心 + 内容滚动增量」，拿不到真实手指轨迹；多数 ROM 的 `scrollDelta` 直接返回 0，旧版据此生成**起点=终点的零长度 SWIPE**，回放退化成点击/长按。修复四处：① `SCROLL → SWIPE` 改为屏幕中轴、受控幅度（默认 34%）、安全边距内的标准翻页，方向只取 delta 符号，幅度绝不依赖不可靠的绝对值；② 连续同向滚动折叠为「1 个动作 + times 次数」由回放循环快速连续翻页，不再把 39 次滚动压成 15 个 400ms 慢速长滑；③ 翻页等待上限收紧到 500ms，避免 12s 操作被 8 段停顿拖成 19s；④ 脚本写入 `startPackage`，回放前比对当前前台包名，界面不一致就明确中止并告知，**不再把动作打在错误界面上还报成功**。设置页新增「录制回放 → 回放界面校验」开关（默认开，可关）；老脚本 / 手写脚本无起始包名自动跳过，行为与 v2.4 完全一致。方向未知、无法还原页面状态等能力边界如实写入告警与脚本描述，不猜落点。新增 18 例单测（`RecordFidelityTest`），累计 **214/214**。权限表无新增、包体增量 16KB。（历史：v2.4 动作可视化；v2.3 修 4 个录制/找图真 bug；v2.2 修 6 个真机回归；v2.1 工具链升级；v2.0 Compose 界面重写。）

v2.4 新增**动作可视化**（设置页「动作可视化」分组，默认关闭）：脚本回放时把真实落点、长按进度、滑动轨迹、多指编号以简洁矢量特效叠加到屏幕，便于在系统录屏下看清自动化流程。实现要点：**零录屏权限**——只用 `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` 的透明悬浮层，纯硬件加速 Canvas 矢量绘制（圆点/圆环/轨迹带/箭头/数字角标，绝不 `drawText`、绝不模糊后处理、绝不引用 MediaProjection/VirtualDisplay/MediaCodec）；**数据来自动作引擎**——`ScriptRunner` 在 `gesture.dispatch()` 前把最终像素坐标（含 jitter/offset/命中修正）作为 `VisualCue` 上报，不从无障碍事件反推手指，零误差；**默认关闭零回归**——总开关关闭时不 `addView`、不产生回调、不增加任何延迟，与 v2.3 行为完全一致；**不影响找色**——活跃特效的合并包围盒接入 v2.3 `ImageMask` 通路，只在匹配用图像副本上抑制，不改截图/帧缓存/失败存证；**空闲停帧**——`Choreographer` 帧驱动、无活跃 cue 立即停帧，配置表驱动（新增手势只加一行，不动渲染代码）。核心状态机 `VisualCueReducer` 与轨迹采样 `VisualGeometry` 落在纯 JVM `core` 层。新增 24 例单测，累计 **196/196**。四条硬约束未改动、权限表无新增、包体增量 16KB。（历史：v2.3 修 4 个录制/找图真 bug；v2.2 修 6 个真机回归；v2.1 工具链升级；v2.0 Compose 界面重写。）

v2.3 是录制质量与找图精度的第二轮深度修复（基于第二份真机调试包复核 + 全链路代码审计），修 4 个真实缺陷：**① 窗口层级变化事件不再生成冗余 `OPEN_APP`**（真机 31 事件里 14 条是窗口事件，旧版每条可启动包窗口事件都编成一个 `OPEN_APP`，5 步脚本出现 3 个「切换到 xxx」，回放 `startActivity` 反复重置页面 → 第一次命中、第二次全失败）、**② 易变动态文本不再当稳定锚点**（B站顶部 Tab 的 `推荐,7之2,标签`，录制时 7之2、回放时 8之2 必然失配）、**③ 跨分辨率模板尺度自适应接线**（`ImageFinder.scalesOf` 本支持多尺度，但 app 层长期把 `refDpi` 传 `0.0`，脚本 `refWidth` 从未参与缩放，换宽度设备找图全灭）、**④ 自家浮窗区域抑制**（v2.2 回退全屏截图后，悬浮球/控制面板/等待浮层被一起截进去污染 NCC 匹配；新增纯 JVM `ImageMask`，只在匹配用图像副本上抑制，不动截图通道、不动失败现场存证、不隐藏浮窗）。新增 13 例单测，累计 **172/172**。四条硬约束未改动。（历史：v2.2 修 6 个真机回归；v2.1 工具链代际升级 AGP 8.13.2 / Kotlin 2.4.21 / Compose 1.11；v2.0 Compose 界面重写；v1.2.3 修低版本崩溃/录制丢增强/OCR 泄漏。）

v2.2 基于用户上传的**真机调试包**（运行日志 + 5 张失败现场截图）修复 6 个真机可复现的真实缺陷，其中 2 个是 v2.0 引入的回归：**回退单窗口截图**（真机返回 1128×1378 冻结透明帧，导致图片模板匹配全灭）、**手势 Path 坐标非负钳制**（修 `Path bounds must not be negative` 崩溃），另修录制 `(0,0)` 无效坐标兜底、systemui 节点混入脚本、无限循环空转刷盘（连续未命中退避 + 失败截图按目标去重）、调试包中文名乱码（zip UTF-8 标志）。新增 7 例单测，累计 159/159。四条硬约束未改动。（历史：v2.1 工具链代际升级 AGP 8.13.2 / Kotlin 2.4.21 / Compose 1.11；v2.0 Compose 界面重写；v1.2.3 修低版本崩溃/录制丢增强/OCR 泄漏。）

v2.0 是一次全面升级：**界面整体迁移到 Jetpack Compose + Material 3**（脚本 / 录制 / 设置 / 日志四个页面重做，状态集中于 `AppViewModel`）；录制编译器修掉「连点被当重复上报吞掉」的真实丢步问题；`VisionPipeline` 增加 400ms 帧缓存（同一动作只截一次屏）；Android 14+ 曾优先 `takeScreenshotOfWindow` 只截活动窗口（**v2.2 真机回归后已回退为全屏无障碍截图**）；回放增加 120s 进度看门狗、录制增加 10 分钟空闲自动收尾；权限表移除上游依赖隐含带入的电话状态与存储权限。四条硬约束未改动。（历史：v1.2.3 修低版本崩溃 / 录制结尾增强丢失 / OCR 泄漏；v1.2.2 修桌面录制被预检误拦截；v1.2.1 补录制诊断。）

## 四条硬性约束（架构级保证，APK 可审计）

1. **不允许加密** —— 脚本为明文 `.zjs`（JSON），录制事件流为明文 `events.json`；APK 无壳、无加密、无可疑打包。
2. **不允许混淆** —— release 构建关闭 R8（`isMinifyEnabled=false` / `isShrinkResources=false`），类名/方法名/资源名与源码一一对应，`dexdump` 实测 630 个 `dev/nymph/auto` 类全保留原始可读名（v2.5，含 19 个可视化类与 `SceneGuard` / `RecordFidelityTest` 等录制保真度类：`VisualCue` / `VisualCueReducer` / `VisualizerLayer` / `VisualizerView` / `VectorGlyphs` 等）。
3. **不与系统录屏抢权限** —— 全工程不创建 MediaProjection 会话，截图只用 `AccessibilityService.takeScreenshot()`；权限表无任何录屏类权限；录屏会话关键 API（`createScreenCaptureIntent` / `getMediaProjection` / `createVirtualDisplay` / `ACTION_MEDIA_PROJECTION`）在 dex 中扫描结果全部为 0。dex 内确有 `MediaProjectionManager` 类描述符，已反汇编定位为 AndroidX `ContextCompat$LegacyServiceMapHolder` 的服务名映射表，非本工程调用。
4. **录制/回放不打断其他 APP** —— 只订阅被动语义事件，不消费、不拦截、不重排触摸；`canRequestTouchExplorationMode=false`（触摸浏览会把单击重解释为「先聚焦再双击」，改变其他 APP 语义）；不订阅 `TYPE_TOUCH_INTERACTION_START/END`、不使用 `FLAG_WATCH_OUTSIDE_TOUCH`；`onAccessibilityEvent` 立即返回，截图与 OCR 全在后台协程。**录制与回放链路不挂全屏覆盖层**：回放期可视化层是 `FLAG_NOT_TOUCHABLE`、录制期控制面板是 `WRAP_CONTENT + NOT_TOUCH_MODAL`。v2.6 的坐标拾取浮层是**例外但受严格约束**——它属于**编辑器态、用户主动点「屏幕拾取」才创建、确认或取消立即销毁**的模态全屏层，不属于录制/回放链路，不订阅任何无障碍事件，且 `RunnerService` 启动回放与 `RecordingService.begin()` 都会先 `dismiss()` 兜底，确保它绝不残留到截图帧、绝不干扰其他 APP。其窗口 flag 仅 `NOT_FOCUSABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS`，无 `FLAG_WATCH_OUTSIDE_TOUCH` / `FLAG_SECURE`。**v2.8.0 新增的系统悬浮动作编辑器同属编辑器态、由用户主动从三处入口呼出**：它用 `TYPE_APPLICATION_OVERLAY` + `specialUse` 前台服务跨 APP 常驻，但只消费落在自身面板的触摸（`FLAG_NOT_TOUCH_MODAL`，面板外透传底层 APP）、不订阅任何无障碍事件、不进录制 / 回放链路、不常驻截图帧；拾取期间面板收成 40dp 小球，`RunnerService` / `RecordingService` 启动时的 `CoordinatePicker.dismiss()` 兜底不变。**v2.8.1 补充**：悬浮编辑器窗口 flags 不再随折叠/展开来回切换；拖动由 `Choreographer` 按帧合并，避免高频 relayout 干扰前台窗口。**v2.9.0 措辞修正**：此前写「flags 全程恒定」并不准确——`setInputFocusable()` 在输入框获焦时本就会临时切到 `FLAG_NOT_TOUCH_MODAL`（否则 `EditText` 无法弹键盘）。准确约束是「只允许两种受控状态、切换只由输入焦点触发」，现由 core `OverlayWindowSpec` 定义并单测锁死，`FloatingEditorService` 的 `baseParams()` / `setInputFocusable()` / 按帧提交全部接线；拖动提交另由 `isPositionOnlyUpdate` 校验只改 x/y。

**其他设计约束**：完全离线（Manifest 无 `INTERNET` / `ACCESS_NETWORK_STATE`，并 `tools:node="remove"` 剔除传递依赖）；兼容 Android 8–17；录制可与系统录屏同时进行。

详见 `LICENSE` / `NOTICE` / `UPSTREAM_LICENSES.md`。

## 工程结构

```
NymphAuto/
├── core/                          纯 JVM，零 Android 依赖，可 100% 单测
│   ├── model/Script.kt            领域模型（动作/条件/变量/滤镜/后置断言/中止条件）
│   ├── importer/                  多方言导入器
│   │   ├── Importer.kt            路由 + 版本协商 + 明确报错
│   │   ├── ZdjlImporter.kt        自动精灵方言（中文动作名/嵌套条件/图片 base64/ABORT/AI）
│   │   ├── ScriptBeanImporter.kt  scriptBean 方言
│   │   ├── LineImporter.kt        行分隔方言 + 截断 JSON 修复
│   │   ├── ScriptCodec.kt         原生 JSON 编解码（v2.9.0 接 ScriptMigrations 版本闸门）
│   │   └── ScriptMigrations.kt   ★ v2.9.0 schema 迁移链，未来版本不再被静默加载
│   ├── vision/                    ImageFinder(NCC) / ImageFilters(8 滤镜) /
│   │                              ImageMask(自家浮窗区域抑制，v2.3) /
│   │                              TextSearch(OCR 文本匹配) / CocoLabels(COCO 中文别名)
│   ├── visual/                    ★ 动作可视化数据层（v2.4，纯 JVM）
│   │   ├── VisualCue.kt          VisualCue / VisualKind / VisualSpec / VisualConfig 配置表
│   │   ├── VisualCueReducer.kt   活跃 cue 状态机 + 进度/裁剪/合并包围盒（可单测）
│   │   └── VisualGeometry.kt     轨迹线性/贝塞尔采样 + 弧长重采样（可单测）
│   ├── recorder/                  ★ 录制脚本编译器（v1.2.0 新增）
│   │   ├── RecordModels.kt        RecordEvent / Anchor / RecordOptions / RecordReport
│   │   ├── AnchorSelector.kt      多模态锚点稳定性排序
│   │   ├── RecordCompiler.kt      事件流 → Script（去噪/合并/包守卫/等待）
│   │   └── SceneGuard.kt          ★ v2.9.1 回放界面守卫（同包名≠同界面，位置手势必达告警）
│   │   └── RecordCodec.kt         事件流明文 JSON 持久化
│   ├── ocr/OcrPostprocess.kt      ★ CTC greedy / 概率图→文本框 / ORT 输出展平
│   ├── rf/                        ★ 随机森林（.dot 解析 + 多数投票 + 屏幕特征）
│   ├── runner/                    CapabilityMatrix / Preflight / WaitStatus
│   │   └── RunStats.kt            ★ v2.9.1 执行统计单一真源（已执行步数/命中/跳过/失败）
│   ├── design/                    ★ v2.9.0 全工程唯一设计 token 真源（纯 JVM）
│   │   └── NymphDesignTokens.kt    色板/尺寸/字号 + 对比度/层级约束；
│   │                              Compose Theme 与悬浮层 FloatingEditorTheme 同源
│   ├── editor/                    ★ 动作编辑纯 JVM 逻辑（v2.7.1 / v2.8.0 / v2.8.1 / v2.9.0）
│   │   ├── ActionEditGuard.kt     动作 copy 保真，防复杂动作被静默改成点击
│   │   ├── RoiApplier.kt          ROI 唯一真源（写入真正读取 region 的全集）
│   │   ├── FloatingEditorLayout.kt ★ 尺寸/折叠/clamp/拖动/表单/Locale + v2.8.1 抗抽搐
│   │   │                            （绝对坐标拖动 / 按帧提交阈值 / MeasureMode 测量修正）
│   │   ├── FloatingEditorTheme.kt  v2.8.1 视觉 token；v2.9.0 起降级为 NymphDesignTokens 投影层
│   │   └── OverlayWindowSpec.kt   ★ v2.9.0 悬浮窗规格单一真源（flags 两态 + 仅位移提交校验）
│   ├── expr/ExprEngine.kt         表达式引擎契约（QuickJS 在 app 层实现）
│   ├── store/                     ScriptStore / ScriptGrants（三档权限）
│   │   └── AtomicTextFile.kt      ★ v2.9.0 原子落盘（tmp→fsync→rename），防写一半留半截
│   └── log/RunLog.kt              日志总线（环形缓冲 500 条）
└── app/
    ├── engine/
    │   ├── ScreenSource.kt        截图源抽象（无障碍 takeScreenshot）+ VisionPipeline
    │   ├── AutoClickService.kt    无障碍桥：截图/节点树/全局动作/被动事件转发
    │   ├── OcrEngine.kt           OCR 契约 + ML Kit 中文兜底
    │   ├── OnnxOcrEngine.kt       ★ ONNX Runtime 1.31.1 完全离线 OCR
    │   ├── OcrEngineFactory.kt    ★ 三通道优先级选择
    │   ├── AiDetector.kt          ★ TFLite Task Vision 目标检测
    │   ├── GestureEngine.kt       手势规划与串行派发 + 形态随机
    │   ├── NodeFinder.kt          节点/文字查找 + 最小命中节点吸附
    │   └── ImeService.kt          脚本输入法
    ├── recorder/                  ★ 录制运行时（v1.2.0 新增）
    │   ├── RecorderBus.kt         AccessibilityEvent → RecordEvent
    │   ├── RecordingSession.kt    后台截图/OCR 增强 + 撤销 + 编译结束
    │   └── RecordPanel.kt         悬浮录制面板
    ├── runner/                    ScriptRunner / JsEngine / QuickJsNative /
    │                              QuickJsAndroidBinding / RunnerZdjlApi / VariableStore
    ├── visual/                   ★ 动作可视化（v2.4，只依赖 Context+WindowManager）
    │   ├── VisualSettings.kt      SharedPreferences 开关（复用 nymphauto prefs）
    │   ├── VisualizerLayer.kt     TYPE_APPLICATION_OVERLAY 窗口 + Choreographer 帧 + 抑制矩形
    │   ├── VisualizerView.kt      纯 Canvas 矢量绘制（5 原子 + 双层描边辉光）
    │   └── VectorGlyphs.kt        数字/斜杠/乘号矢量字形（绝不 drawText）
    ├── service/
    │   ├── RunnerService.kt       回放前台服务 + 悬浮球 + 状态机 + 可视化装配
    │   ├── RecordingService.kt    ★ 录制前台服务（specialUse）+ 会话保存
    │   └── FloatingEditorService.kt ★ 系统悬浮动作编辑器（TYPE_APPLICATION_OVERLAY + specialUse；v2.8.1 恒定 flags + 按帧节流）
    ├── ui/                        MainActivity（Compose 宿主）
    │   └── compose/               Theme / AppViewModel / App / Screens / LogScreen /
    │                              Components（M3 视觉体系与全部界面）
    │                              + FloatBall / BallPanel（悬浮球，View 实现）
    │                              + FloatingEditorPanel（v2.8.1 按设计 token 重做视觉，纯 View）
    └── src/main/cpp/quickjs/      vendored quickjs-ng（MIT），CMake 现场编译
```

`core/src/test/resources/golden_reashenghuo.zjs` 为 891KB 真实 `.zjs` 黄金样本，一个字节不改，作导入回归铁证。

## 录制脚本（v1.2.0 重点）

**数据来源只能是三种**：`AccessibilityNodeInfo`、无障碍 `takeScreenshot()`、被动语义事件。全链路零 MediaProjection、零触摸拦截。

| 环节 | 实现 |
| --- | --- |
| 事件订阅 | `TYPE_VIEW_CLICKED` / `LONG_CLICKED` / `SCROLLED` / `FOCUSED` / `SELECTED` / `TEXT_CHANGED` / `WINDOW_STATE_CHANGED` / `WINDOWS_CHANGED`。刻意不订阅 `typeWindowContentChanged`（高频噪声会拖慢系统窗口分发） |
| 回调约束 | `onAccessibilityEvent` 只做判空与转发，立即返回；`RecorderBus` 用 `SystemClock.elapsedRealtime()` 记相对时间；截图/OCR/落盘由 `RecordingSession` 的后台协程处理 |
| 锚点择优 | `NODE_ID`(95) > `NODE_TEXT`(80) > `OCR_TEXT`(65) > `TEMPLATE`(45) > `COORD`(10)，降序取最强；`allowCoordFallback=false` 可禁用坐标兜底；像素统一转百分比。**v2.3 起 `isVolatileLabel()` 剔除位置指示器 / 页码 / 时钟 / 纯计数等易变文本，避免「录制命中、回放失配」** |
| 事件去噪 | 重复点击合并、连续滚动合并为单个 `SWIPE`、FOCUS 噪声剔除、自身 UI 事件过滤、`maxEvents` 上限保护。**v2.3 起 `WINDOW_CHANGED` 全部丢弃（不再生成冗余 `OPEN_APP`），应用切换由交互事件的包守卫统一负责，报告记「忽略窗口事件 N」** |
| 回放稳定性 | 应用包变化插入 `OPEN_APP` 守卫、自动 `WAIT` 等待、写入 `refWidth/refHeight/refRotation` 供坐标映射；**v2.3 把 `refWidth` 接到模板尺度自适应（`ImageFinder.scalesOf`），换宽度设备找图不再全灭** |
| 产出动作 | `CLICK_NODE` / `CLICK_TEXT` / `CLICK_IMAGE` / `TAP` / `LONG_PRESS` / `SWIPE` / `INPUT` / `KEY` / `OPEN_APP` |
| 明文产物 | 审计用 `filesDir/recordings/<时间戳>.events.json` + 脚本库可直接回放的 `.zjs`；均为明文 JSON，崩溃可恢复、可逐行审计 |
| 交互 | 主界面「开始录制脚本（不占用录屏权限）」启动后自动退后台；浮窗仅用 `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL`，支持「撤销上一步」「停止并保存」 |

`AnchorSelector` 与 `RecordCompiler` 是纯 JVM 逻辑，22 个单测覆盖节点 ID 优先、OCR/模板/坐标锚点、去重、滚动合并、包守卫、等待、参考分辨率、事件上限、明文 JSON 往返与描述约束。

## 坐标拾取 v2.7.0：非模态穿透 + 帧同步识别（本次主改）

**用户第一诉求**：「甚至可以一边操作拾取坐标，一边可以操作指针外的任意区域」。v2.6 的全屏可触摸模态层盖上之后底层 APP 完全收不到触摸，且底部大按钮常挡住待拾取位置。v2.7 拆成两个窗口（ADR-001）：

| 窗口 | 参数 | 作用 |
|---|---|---|
| 绘制层 | `MATCH_PARENT` + `FLAG_NOT_TOUCHABLE` + `FLAG_NOT_FOCUSABLE` | 只画标尺/准星/十字/读数/放大镜/框选矩形，**不消费任何触摸**，底层内容原样可见（去掉暗色蒙版） |
| 控制手柄 | `WRAP_CONTENT` + `FLAG_NOT_TOUCH_MODAL`，默认 30dp 圆点 | 可拖动、点开 196×156dp 极简工具条，只消费落在自身区域的触摸 |

由此**手柄之外 = 底层 APP 正常操作区**，可在目标 APP 里翻页点按钮、手柄一直浮着随时拖到新位置。准星改为拖动手柄定位，精度由像素整数 + 4 位小数百分比读数、局部放大镜、±1px **长按连发**共同补偿。新增框选模式（第二角手柄）返回 `PickerRegion` 四元组，可直接填找图 ROI / 找色范围 / 模板截取；`SnapProvider` 默认控件中心 / 颜色中心吸附。零 `FLAG_WATCH_OUTSIDE_TOUCH`、零录屏权限；放大镜取自无障碍 `takeScreenshot()`，会包含绘制层自身准星细线，**仅作辅助**，精确定位以十字/读数为准；运行/录制启动前服务仍强制 `dismiss()`。

**识别提速（ADR-002）**：`RecognitionTuning` 把轮询间隔做成可配置 16/33/50/100/300ms（默认 33ms ≈ 30Hz，替代硬编码 300ms）；`MotionPredictor` 用历史命中点外推速度，`predictedRegion()` 在预测点周围开窄 ROI 并与脚本 region **取交集**，关闭调参/样本不足/预测陈旧时原样返回，行为与 v2.6 一致。`RoiExpr` 支持别名（`topHalf`）、四元组、`0,0,w*0.3,h`、`cx-r,cy-r,cx+r,cy+r` 与 `min/max`；`Region.expr` 新增字段，`RoiExpr.resolveRegion()` 在 `ScriptRunner` 找图/找色/AI/诊断/条件共 **8 个调用点**接线，非法表达式静默退回数值、绝不中断回放。**明确不承诺 1ms**——无障碍全屏截图一帧 16–60ms 是物理下限。

**发版前接线扫描（ADR-008）**：逐个统计新增 core 符号引用点，发现 `RoiExpr` 与 `ScriptRepository` 一度只存在于 core 自身 + 单测，随即补齐生产链路；这条已写成硬性检查，避免「功能写了、测试也过了、用户实际用不到」。

## 坐标拾取与单步动作编辑器（v2.6.0 初版）

此前脚本只有两条来源：被动无障碍录制、导入 `.zjs`，**无法精确指定落点**。v2.6 补齐参考交互里的两条制作路径：

| 路径 | 入口 | 说明 |
| --- | --- | --- |
| 录制式（已有） | 录制页「开始录制」 | 被动事件录制 + `RecordCompiler` 编译，v2.5 起带 `startPackage` 界面校验 |
| 单步添加（新增） | 脚本列表「新建脚本」/ 卡片菜单「编辑动作」 | `EditorScreen` 逐条添加点击/长按/双击/滑动，坐标手输或屏幕拾取，支持排序/删除/编辑 |

**超高精度坐标拾取** `CoordinatePicker`：用户点「屏幕拾取」后弹出全屏 `TYPE_APPLICATION_OVERLAY`，顶部/左侧 0–100 百分比标尺（每 1% 刻度）、可拖动准星 + 全屏十字定位线、实时读数（`X 512px Y 1000px` + `47.4074% 41.6667%`）、±1px 微调按钮。像素坐标用 Int 原样保留，百分比用 `Double` 全精度换算、显示 4 位小数，坐标原点与 `takeScreenshot` 全屏帧同源（`FLAG_LAYOUT_IN_SCREEN + MATCH_PARENT`），直接落进脚本现有的 PCT 坐标系，挖孔屏/折叠屏无需另做换算。滑动可分别拾取起点与终点。

**三条正确性防线**（本版自查修掉的真实缺陷）：① 浮层是同进程窗口，不会让 Activity 走 `pause→resume`，回填改用**确认时即时回调**，`ON_RESUME` 仅作配置重建兜底；② 移除浮层必须用 `show()` 注入的 `WindowManager`（`View.parent` 是 `ViewRootImpl`，强转 `WindowManager` 会永远失败导致浮层盖屏不退）；③ `RunnerService` 启动回放、`RecordingService.begin()` 都会先 `dismiss()`，全屏可触摸层绝不残留到截图链路污染找图。

**边界**：拾取浮层是编辑器态模态层，全屏消费触摸是有意设计（用户主动发起、确认/取消即销毁），不属于录制/回放链路，不订阅无障碍事件、不加 `FLAG_WATCH_OUTSIDE_TOUCH` / `FLAG_SECURE`；无悬浮窗权限时静默失败，编辑器仍可手输坐标。新增 9 例单测（`EditorFormatTest` 5 + `PickerResultTest` 4），累计 **223/223**。**v2.7.0 起该全屏可触摸模态层已被非模态穿透双窗口取代**（见上一节）；编辑器同步升级为数据保真 + 步骤/源码双视图 + 撤销重做，`ActionEditGuard` 修掉非 4 类动作被静默改成点击、保存丢 `children`/`listeners` 的数据丢失级 bug。

## 录制回放保真度（v2.5.0）

真机录屏暴露的问题：用户在应用抽屉里 12 秒连续翻页，录出的脚本回放时却在日历界面执行 15 次慢速滑动，还误触打开了内容详情页。复现链路（调试包日志）：`VIEW_SCROLLED=39 / WINDOWS_CHANGED=7 / WINDOW_STATE_CHANGED=5 / VIEW_CLICKED=1` → 编译成 15 个动作，全是 `SWIPE`。

**根因**：无障碍 `TYPE_VIEW_SCROLLED` 只给「滚动容器中心 + 内容滚动增量」，不给手指落点；多数 ROM 的 `scrollDelta` 直接返回 0。旧版把容器中心当起点、把内容滚动量当位移，于是生成起点=终点的**零长度 SWIPE**（回放退化成点击）、或幅度被 `coerceIn` 拉到屏幕边缘的超长滑动。窗口事件全丢弃又让脚本不记录起始界面，回放停在哪个界面就从哪个界面开始滑。

**修复**（`RecordCompiler` 纯 JVM，18 例单测覆盖）：

| 项 | 旧行为 | v2.5.0 |
| --- | --- | --- |
| 手势幅度 | 用 `scrollDelta` 绝对值，常为 0 或异常大 | 屏幕中轴 ± 17% 的受控 34% 行程，夹在 14% 安全边距内 |
| 方向 | 直接用增量反推终点 | 只取 delta 符号定主轴与正负，全 0 时默认向上翻页并告警 |
| 连续翻页 | 39 次滚动 → 15 个 400ms 慢速长滑 | 折叠为「1 动作 + `times` 次数」，220ms 短滑 + 140ms 间隔，上限 6 次/组 |
| 节奏 | 8 段等待，12s 操作回放成 18.9s | 翻页动作等待上限 500ms |
| 起始界面 | 不记录 | 写入 `startPackage`，回放前比对前台包名 |

`SceneGuard`（纯 JVM）负责界面校验：老脚本无起始包名直接跳过，零回归；新脚本界面一致则放行，目标可启动则自动拉起一次，桌面/系统界面无启动入口则**明确中止并提示手动回到录制时的界面**。设置页「录制回放 → 回放界面校验」可关闭。

**诚实的能力边界**：被动无障碍录制无法还原列表滚动位置、页面层级与真实手指轨迹，这些不猜、不硬凑，方向未知 / 需手动回界面都写进告警与脚本描述。若需要像素级复现手势，应改用带坐标的手动编排脚本，而不是依赖事件录制。

## 动作可视化（v2.4.0，默认关闭）

「在系统录屏下把自动点击流程看清楚」——只加可视化，不加任何录制能力。设置页「动作可视化」分组提供总开关、点击/长按反馈、滑动/多指轨迹、步骤角标、落点预告、克制模式六项，全部默认不改变原有行为。

| 层 | 实现 | 关键约束 |
| --- | --- | --- |
| 数据源 | `ScriptRunner` 在 `gesture.dispatch()` 前用最终像素坐标（含 jitter/offset/命中修正）发 `VisualCue`；`CLICK_*` 命中才发、未命中不画假落点 | 不从无障碍事件反推手指，零误差；`onVisualCue=null` 时 `visualize()` 首行短路 |
| 状态机 | `core/visual/VisualCueReducer`（纯 JVM）：进度 0..1、到期移除、同屏上限 16、多指裁前 4 指、轨迹裁 24 点、非法坐标清洗、合并包围盒 | 时间由调用方传入（nanoTime 毫秒），掉帧按真实时间走、动效速度不变 |
| 渲染 | `VisualizerView`：5 原子（圆点/圆环/轨迹带/箭头/数字角标）纯 Canvas 矢量，`ANTI_ALIAS`；辉光=宽淡外描边+窄亮内描边 | 绝不 `drawText`（角标用 `VectorGlyphs` 矢量折线）、绝不 `BlurMaskFilter`、Paint/Path 预分配、onDraw 不 new |
| 窗口 | `VisualizerLayer`：单个 `TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_FOCUSABLE\|NOT_TOUCHABLE\|LAYOUT_IN_SCREEN\|LAYOUT_NO_LIMITS\|HARDWARE_ACCELERATED`，`TRANSLUCENT` | 绝不 `FLAG_SECURE`、不收 MotionEvent、不拦截点击；无动画 `GONE` + 注销 `Choreographer`（停帧） |
| 找色保护 | 活跃元素合并包围盒外扩 44dp → `ImageMask.RectPct`，接 `VisionPipeline.selfOverlayRects` | 只在匹配副本抑制；不改截图/帧缓存/失败存证、不隐藏浮窗 |
| 配置 | `VisualConfig` 纯数据表驱动（`specFor(kind)` 加一行即新增手势）；SharedPreferences 复用 `nymphauto` | 无动画 CPU≈0；不新增权限、不引入依赖 |

**所有界面统一**：不做页面/安全窗口/白黑名单识别，不设任何按界面类型暂停或隐藏的分支；系统录屏授权弹窗与录制通知由 overlay 的 z-order 天然避让（系统 UI 层在上），不写检测逻辑。**已知限制**：部分 ROM 的「录屏时隐藏悬浮窗」开关会让特效录不到，需真机逐机型记录；系统级三/四指手势拿不到完整事件，静默降级，不猜位置硬画。

`core` 层 24 个新增单测（Reducer 13 / Geometry 6 / Glyphs 5）覆盖生命周期、上限、进度边界、裁剪、非法坐标、合并包围盒、空状态、配置表、轨迹采样与字形越界安全。

## 离线 OCR 与端侧 AI

| 通道 | 说明 |
| --- | --- |
| **ONNX Runtime Android 1.31.1**（MIT，首选） | 2026-11-05 当日最新 release。读 `filesDir/ocr/`（或 `assets/ocr/`）下 `det.onnx` / `rec.onnx` / `keys.txt`，纯 CPU、不联网、不依赖 GMS；`OcrPostprocess` 完成 CTC greedy 解码与概率图→文本框。**模型可插拔、不内置大模型**，缺任一文件即 `available=false` 自动降级 |
| ML Kit 中文 16.0.1（兼容兜底） | 仅当设备有 GMS 且模型已缓存时可用；入口 `TextRecognition.getClient(ChineseTextRecognizerOptions)` |
| 无障碍节点文本（最终回退） | 两者皆不可用时 `UnavailableOcr` 返回空列表，`VisionPipeline` 回退节点文本，录制与回放不中断 |

`OcrEngineFactory` 按上述优先级选择，所有通道输入位图均来自 `takeScreenshot()`。AI 侧另有 TFLite Task Vision `ObjectDetector` + `efficientdet-lite0 int8`（4.6MB 明文模型，COCO 80 类中文别名），支撑 `CLICK_AI` / `APPEAR_AI`；随机森林为纯 JVM `.dot` 决策树解析 + 多数投票，对方专有模型不内置。

## 构建

```bash
export ANDROID_HOME=/opt/android-sdk       # local.properties 亦可配 sdk.dir
gradle :core:test :app:testDebugUnitTest   # 纯 JVM 单测，无需设备
gradle :app:assembleRelease :app:assembleDebug
```

- 工具链：JDK 17 目标 / Gradle 9.3.0 / AGP 8.13.2 / Kotlin 2.4.21 / Compose BOM 2026.06.01 / SDK 36 / NDK 27.0.12077973 / build-tools 36.0.0。仓库不含 `gradlew`，使用系统 Gradle。
- QuickJS 无需额外接入：vendored 源码由 `externalNativeBuild` + CMake 现场编译，不投放预编译二进制。
- release 产物为未签名包，签名采用外部 `zipalign -p -f 4` + `apksigner sign`（v2/v3），私钥不随工程分发。
- **工具链上限（v2.1 代际升级后固定，2026-11-07 复核）**：AGP 8.13.2 / Kotlin 2.4.21 / Compose BOM 2026.06.01（Compose 1.11.4 / Material3 1.4.0）/ core-ktx 1.17.0 / coroutines 1.11.0 / serialization 1.12.0 / activity-compose 1.13.0 / lifecycle 2.10.0。封顶原因：core-ktx 1.19 与 BOM 2026.08+ 要求 `compileSdk 37` 与 AGP 9.1，本环境 SDK 平台只到 36。LiteRT 2.3.2 已评估但未迁移，原因见 `UPSTREAM_LICENSES.md`。

## 验证状态（本机实测，v2.9.2）

- **单测 387/387**，failures=0 / errors=0 / skipped=0（45 个 suite）。v2.9.2 新增 24 例：`RecordabilityGuardTest` 14（五态判定、零事件超时、纯函数、必达文案指向视频导入与无限循环、只提示不阻断）+ `LoopPolicyTest` 6（`0`=无限不被当成执行零次、钳制、中文解析、非法输入用 null）+ `VideoScriptBuilderTest` 补 4（默认 1 次零回归、无限循环产物、归一化、循环不改动找图锚定）。v2.9.1 新增 17 例：`SceneGuardPositionalTest` 11（位置敏感识别、同包名/取不到包名/自家界面三条静默放行升级为必达告警、既有四种决策逐字不变、告警只提示不阻断）+ `RunStatsTest` 6（纯手势脚本显示已执行步数、坐标手势不伪装成命中、查找类口径不变、多轮/无限循环文案、零步不误报）。v2.9.0 新增 27 例：`NymphDesignTokensTest` 6（悬浮层色板/尺寸/约束逐槽等于真源、警告色同源、语义色可区分、对比度构造性达标、asArgb 高低位）、`OverlayWindowSpecTest` 6（位掩码与 AOSP 一致、两态 flags、非法 flag 被拒、合法切换、拖动只改 x/y、非位移改动被丢弃）、`AtomicTextFileTest` 7（首次写/覆盖写/20 轮连续覆盖无半截、遗留 tmp 清理不误删、UTF-8 往返、ScriptStore 接线、目录自动创建）、`ScriptMigrationsTest` 8（CURRENT 同源、历史脚本、v1 行为不变、未来版本被挡、非法版本、isSupported、apply 幂等、steps 非负）。v2.8.1 的 319 例全部保留。
- **aapt2**：`versionCode=20` / `versionName=2.9.2` / `targetSdk 36`；原生库仅 `lib/arm64-v8a/`；权限表与 v2.3～v2.8.1 **完全一致**（8 项），无 `INTERNET` / 网络状态 / 存储 / 电话状态 / 任何录屏或音频类权限。
- **dex 扫描**（`dexdump` 精确类型/方法引用）：`MediaProjection` / `VirtualDisplay` / `createVirtualDisplay` / `MediaCodec` / `FLAG_SECURE` / `RECORD_AUDIO` / `WATCH_OUTSIDE_TOUCH` / `createScreenCaptureIntent` **全部为 0**（唯一 `Landroid/media/AudioRecord;` 来自 TFLite 传递依赖 `TensorAudio`，非本工程调用）；**959 个** `dev/nymph/auto` 类全部保留原始可读名（无混淆），新增 `NymphDesignTokens` / `OverlayWindowSpec` / `AtomicTextFile` / `ScriptMigrations` / `RunStats` / `RecordabilityGuard` / `LoopPolicy` 等符号已确认进 dex；`verify_release.py` 新增硬门槛：`RecordabilityGuard` 与 `LoopPolicy` 必须真的编进 release dex，否则判 FAIL（ADR-008）。
- **16 KB 页对齐（v2.9.0 核心修复，实测）**：`tools/check_elf_alignment.py` 逐个解包核验 6 个 so 的 ELF LOAD 段 Align —— 自编译 `libquickjs-jni.so` 由 v2.8.1 的 `0x1000` **实测变为 `0x4000`**；`libandroidx.graphics.path.so` / `libmlkit_google_ocr_pipeline.so` / `libonnxruntime.so` / `libonnxruntime4j_jni.so` 均 `0x4000`；`zipalign -c -P 16` ZIP 条目对齐通过。**如实列出唯一遗留**：TFLite Task Vision 0.4.4 预编译 `libtask_vision_jni.so` 仍 `0x1000`，本工程 CMake 不可控，不冒充修复；16 KB 页设备上其加载失败由 `AiDetector.ensure()` 的 `runCatching` 优雅降级。该核验已接入 `verify_release.py`，自编译 so 未对齐直接判 FAIL。
- **界面守卫同包名回归（v2.9.1）**：`SceneGuard.hasPositionalActions` 识别直接派发的坐标手势；含翻页/点击手势时，`PROCEED`（同包名）、`SKIP`（取不到前台包名 / 仍在自家界面）三条静默放行路径全部升级为 `PROCEED_WITH_WARNING`，文案来自 core 单一真源 `positionalWarning`，经 `emitSceneNotice`（日志页 + `RunLog.w` + `onSceneNotice` toast）必达；`RESTORE_APP` 拉起应用后同样送达。守卫关闭 / 老脚本无起始包名亦提示。由 `SceneGuardPositionalTest` 11 例锁死，既有四种决策（默认参数）逐字不变。
- **执行统计口径回归（v2.9.1）**：core `RunStats` 为单一真源，主循环无条件 `executed++`；`ScriptRunner` 移除内部私有 `RunStats`，`roundSummary` 与轮次日志委托 core 格式化。纯坐标手势脚本总结「已执行 N 步」并标注「无目标校验」，不再全是 0。由 `RunStatsTest` 6 例锁死。
- **录制可行性判定回归（v2.9.2）**：core `RecordabilityGuard` 纯 JVM 五态判定，自绘界面（有窗口事件、零交互事件）必然得到 `SELF_DRAWN` 与「能力边界 + 视频导入 + 循环」替代路径；零事件超时即使仍在待命也提示检查无障碍；正常录制不打扰；8 秒门槛避免刚进入目标 APP 就误报。由 `RecordabilityGuardTest` 14 例锁死。
- **录制链路必达但不打扰（v2.9.2）**：`RecorderBus.signal()` 分类计数不改回调轻量契约；`RecordingService` 8 秒后只提示一次（面板 `setHint` / 通知栏 / toast / 日志页四通道），**不阻断录制、不改窗口 flags、不改触摸分发**；空脚本收尾由「未录到有效动作，已取消」升级为根因 + 替代路径 + 诊断；`RecorderBus.diagnostics()` 同步把指引写进日志页。
- **循环一等入口回归（v2.9.2）**：core `LoopPolicy` 为 `0`=无限的唯一真源；`EditorScreen` 循环状态条 + 对话框（1/10/无限、纳入撤销栈）、`VideoImportDialog` 循环 chips、`VideoScriptBuilder.build(repeatCount=…)` 默认 1 零回归；`ScriptRunner` 既有 `repeatCount==0` 无限循环能力不变。由 `LoopPolicyTest` 6 + `VideoScriptBuilderTest` 4 例锁死。
- **源码级抗抽搐核验**：`FloatingEditorService` 拖动走 `rawX/rawY` + `Choreographer.postFrameCallback` 一帧一次 `updateViewLayout` + `shouldCommit` 1px 阈值；**v2.9.0 起 flags 取值来自 `OverlayWindowSpec` 单一真源**（准确表述：只允许 `STANDARD_FLAGS` 与输入焦点临时态 `INPUT_FLAGS` 两种受控状态、只由输入焦点触发，不再宣称「全程恒定」——`setInputFocusable()` 本就会切换）；拖动提交前 `isPositionOnlyUpdate` 校验只改 x/y，脏规格丢弃该帧；`refreshMetrics()` 缓存屏幕尺寸、`refreshForeground()` 去重；`MaxHeightScrollView.onMeasure` 用 core `measureHeight` 处理 `UNSPECIFIED`；状态行固定 `STATUS_MIN_DP`。
- **视觉 token 核验**：颜色/圆角/间距/字号全部取自 core `NymphDesignTokens`，Compose `Theme.kt` 与 `FloatingEditorTheme`（投影层）同源，`FloatingEditorPanel` 只负责绘制；两处同源、对比度、字号层级、语义色可区分由 JVM 单测约束。
- **原子落盘 / 版本闸门核验**：`ScriptStore` / `ScriptRepository` / `ScriptGrants` 写入均经 `AtomicTextFile`（tmp→fsync→rename），启动时清理遗留 `.tmp`；`ScriptCodec.decode` 经 `ScriptMigrations` 迁移链与未来版本闸门，v1/历史脚本/压缩容器行为不变。
- **找色保护**：`RunnerService` 启动回放、`RecordingService.begin()` 均先 `CoordinatePicker.dismiss()`；悬浮编辑面板在拾取期间自动收成 40dp 小球。
- **接线扫描**：新增 core 符号 `NymphDesignTokens`（Theme.kt + FloatingEditorTheme）、`OverlayWindowSpec`（FloatingEditorService 三处）、`AtomicTextFile`（三个 store）、`ScriptMigrations`（ScriptCodec）均由生产链路消费，无「写了没接」残留。
- **自动核对**：`python3.11 tools/verify_release.py` 从真实产物只读核对版本/字节数/SHA-256/权限/ABI/v2v3 签名/类数/禁用 API/单测数/源码包/**16KB ELF 对齐**，结论 **PASS**；README 与发布说明数字均引用其输出。
- **包体**：73,882,407 字节；APK SHA-256 `5eac52d9ab0ce504da213935a0e1388eff05e2d71b9b68b6e9e22550bbec4ae9`。v2/v3 同钥签名，证书 SHA-256 `361722d6…6271`，与 v1.x～v2.9.1 全部历史版本同钥可原地升级。
- **无障碍配置**：`canTakeScreenshot=true`（唯一截图能力）、`canRequestTouchExplorationMode=false`，仅订阅被动语义事件，不含 `typeWindowContentChanged`。
- **apksigner**：release 包 v2/v3 通过，证书 SHA-256 `361722d6…6271` 与 v1.x 至 v2.8.1 全部历史版本同钥，支持原地升级。
- **对比视频**：本次链接带签名参数，**成功取回** 19.6MB 真 MP4（720×328 / 60fps / 142s）。逐帧像素差 + 连续帧放大拼接确认面板边缘跳位、可见内容行数变化，作为「窗口反复 relayout」的证据；未据视频推断任何未在源码中确认的行为。
- **待真机验收**：按帧节流的实际跟手度与各 ROM 触摸分发、悬浮窗内 `EditText` 焦点/软键盘、`specialUse` 前台服务保活、通知栏「呼出/收起/关闭」、折叠/展开与拖动手感、面板与 `CoordinatePicker` 同时显示的 z-order，以及沿用 v2.4～v2.8.0 的非模态穿透、找色回归、GPU 时间、各 ROM 录屏可见性、OEM 隐藏悬浮窗开关、折叠屏/分屏坐标、33ms 采样帧率与内存、ROI 表达式收益、mp4 抽帧编码兼容。本机无设备**不宣称通过**；instrumentation 测试（浮层/窗口/Choreographer/权限降级）仍为零，见 `ENGINEERING.md` 验收状态表与发布说明。

## 交互能力

| 能力 | 实现 |
| --- | --- |
| 脚本库持久化 | 导入/录制即入库（`filesDir/scripts/*.json`），主界面列表展示名称/步数/时间，行内运行/删除；同名覆盖、坏文件跳过 |
| 运行日志 | 导入/执行/异常/状态变更全流水入 `RunLog`（内存 500 条环形）并落盘 `filesDir/logs/nymphauto.log`（512KB 滚动）；支持实时刷新、分享导出、清空 |
| 浮窗控制 | 悬浮球三态（灰=就绪/绿=执行中/黄=暂停）；开始/暂停/停止/收起，通知栏同步 |
| 导入报错可读 | 失败原因细化到具体类型（空内容 / 无法识别的格式 / 版本过高需升级），Toast + 日志双通道 |

版本体系：zdjl 方言用独立兼容基线 `COMPAT_VERSION_CODE=26000010`（被复刻版本），与原生 `SCHEMA_VERSION` 严格分离。

## .zjs 脚本兼容（v2.21+ 行分隔方言）

自动精灵 v2.21 起导出格式为**行分隔 NDJSON 式 `.zjs`**（首行元数据 + 每行一个顶层动作树）。导入器按真实样本（891KB / 57 动作）完整适配：

| 方言特性 | 处理方式 |
| --- | --- |
| 首行元数据（`description` / `minVerCode` / `lastSaveScreenWidth/Height`） | 作脚本头解析；任一行非合法 JSON 即判非本方言 |
| 顶层 `location` / `点击`+`posData{type:location,x,y}` | 三级坐标回退（`posData` → `location` → 顶层），`%`/`dp`/`px` 自动识别 |
| `控制执行` + `controlRunType` | `waitDelay` → WAIT（按 `delayUnit` 换算），`stop` → STOP |
| `scriptCallbacks` 回调字典 | afterExecFinish / afterConditionSuc / afterExecSuc / beforeCondition / afterConditionFail 映射 |
| `conditionSet` + `matchMode`（or/and） | COMBINE 条件（ANY/ALL），子条件递归 |
| `textFount` / `colorFound`+`colorData` | APPEAR_TEXT / APPEAR_COLOR，含坐标与区域 |
| `timeAfter` 绝对时间 | AFTER_TIME 时间门条件 → epoch 毫秒 |
| `posData.imageData` / 子条件图 | convertVisual 回退链，23/23 模板零丢失 |
| 单数 `imageFilter`/`filter` | readFilterList 兼容对象与数组 |
| `break` / 中止条件 | `ConditionKind.ABORT`，载荷命中抛 `ScriptAbort`，脚本级每步检查 |

**宽容导入**：单动作级 `runCatching` 异常隔离 + 安全类型转换（`as? JsonObject` / `asPrimitive`），单个畸形步骤只计数不拖垮整包。实测 891KB 真实脚本导入 103ms，57/57 动作与元数据 `count` 精确吻合，导出→再导入往返一致。另支持 `APPEAR_AI` / `CLICK_AI` 导入。

## 精度链路与回放侧闭环

对「点击没反应 / 点歪了 / 界面变了」三类高频失败，不做「更准的点击」，而是做**可确认、可归因、可定点修复**：

| 机制 | 实现 | 位置 |
| --- | --- | --- |
| 能力矩阵四档 | FULL / DEGRADED / RESTRICTED / UNAVAILABLE，运行前浮窗显示，UNAVAILABLE 明确拦截 | `core/runner/CapabilityMatrix.kt` |
| AAPM 检测 | Android 17 高级防护模式（`QUERY_ADVANCED_PROTECTION_MODE`）检测到即诚实告知，**不绕过** | `app/engine/ScreenSource.kt` |
| 敏感页检测 | `isAccessibilityDataSensitive()` → 降档 RESTRICTED 并明示 | `app/engine/NodeFinder.kt` |
| 预检 | 导入即推导依赖清单（目标包/截图/参考分辨率/方向/图片步数/AI 可用性），环境不符事前拦截 | `core/runner/Preflight.kt` |
| 后置断言 | 断言失败与坐标未命中分开归因（ASSERT_FAIL / COORD_MISS） | `ScriptRunner.kt` |
| 检查点制闭环 | 单次修正 >8px 或累计 >40px 判「布局已变」直接失败，**不静默挪点** | `ScriptRunner.kt` + `Preflight.kt` |
| 五类失败归因 | COORD_MISS / ASSERT_FAIL / WAIT_TIMEOUT / CHANNEL_LOST / ENV_MISMATCH / ABORT_HIT，各带修复建议与步骤号 | `Preflight.kt`（FailReason） |
| 时间抖动 | 对数正态 σ=0.06（≈±6%，Box-Muller + 固定种子可复现） | `Preflight.kt`（JitterSampler） |
| 形态随机 | 点击注入 ±0.5px 亚像素 MOVE；滑动二次贝塞尔微弯 | `app/engine/GestureEngine.kt` |
| D1 回归铁证 | 顶层 `repeatCount:0` 是无限循环哨兵，旧导入器 `coerceAtLeast(1)` 会吞掉；黄金样本测试一字节不改看守 | `ZdjlImporter.kt` + `GoldenScriptImportTest.kt` |

## 录制排障（v1.2.1 / v1.2.2 / v1.2.3）

v1.2.0 实机日志停在「开始录制…」后一片空白。根因不是服务失效，而是**整条事件链路零日志**（`RecorderBus.onEvent` / `RecordingSession.accept` 不写 RunLog）、**无悬浮窗权限时通知栏步数永不刷新**（`onEventCount` 只刷新 `panel`，而 `panel == null`）、以及 **`TYPE_WINDOWS_CHANGED` 订阅了却没映射**（`convert()` 返回 null 丢弃）。字节码核验确认旧版 `onServiceConnected` 走的是 `getServiceInfo() → 加 flags → setServiceInfo()`，并未把配置写空、XML 订阅保留。v1.2.1 修复：

| 修复 | 内容 |
| --- | --- |
| 节流诊断日志 | `RecorderBus` 前 5 条逐条打印事件类型、之后每 20 条汇总；`RecordingSession` 首条及每 10 条打印进度；计数用原子量，不在无障碍回调里做阻塞操作 |
| 通知栏步数 | `onEventCount` 同时刷新通知栏，400ms 节流 + 尾刷保证最终一致；0 步时显示「请照常操作其他 APP」 |
| 事件映射 | 补 `TYPE_WINDOWS_CHANGED` → `WINDOW_CHANGED`（生成 `OPEN_APP` 包守卫） |
| 结束诊断摘要 | `RecorderBus.diagnostics()` 打印原始事件数 / 转换数 / 未映射丢弃数 / 按类型分布，脚本为空也能定位原因 |
| 配置可核验 | 删除 `onServiceConnected` 冗余的动态 `setServiceInfo()` 与 companion 同名死字段，改为打印系统实际生效的 `eventTypes`/flags |

**v1.2.3 追加修复：一轮真 bug 审查（3 个，均不改变原有功能）**

| # | 真 bug | 根因 → 修复 |
| --- | --- | --- |
| ① | **回放首次点击必崩（API 26–32）** | `JitterSampler.uniformDeltaMs` 用了 Java 17 / Android API 33 才有的 `java.util.Random.nextLong(origin, bound)`；本工程 minSdk 26 且未启用 core library desugaring，`jitterEnabled` 默认 true、每次点击 DOWN-UP 都调用 → 低版本 `NoSuchMethodError`。v1.2.2 dex 反汇编已坐实该调用 → 改用全 API 可用的 `nextDouble` 均匀抖动；修复后 dex 中 `Ljava/util/Random;.nextLong:(JJ)J` 引用由 1 降为 0 |
| ② | 录制结尾增强丢失 | `RecordingSession.finish()` 停止时立即 `cancel()` + 取快照，队列里最依赖 OCR/模板的点击增强结果未回填就被丢弃 → 关闭队列后以 `CountDownLatch` 有限等待（1200ms 上限）后台排空再编译；保持同步签名，不引入重入竞态 |
| ③ | OCR 引擎资源泄漏 | `RecordingService` 每次录制 `OcrEngineFactory.create()`（契约要求调用方 `close()`）却从不释放 `OrtSession`/`TextRecognizer` 原生资源（回放侧已正确关闭）→ 提升为成员变量，在增强排空之后与 `onDestroy` 中关闭置空 |

新增 2 例抖动回归单测（同种子可复现且覆盖区间两端、极小基准不崩溃），全量 147→149/149。四条硬约束、脚本格式、动作语义、锚点优先级零改动。

**v1.2.2 追加修复：录制脚本被预检误拦截「目标应用未安装」**

v1.2.1 实机日志确认录制链路完全跑通（65 事件 → 17 步并成功保存），但点运行时反复被 `预检拦截：目标应用未安装：com.huawei.android.launcher、com.android.systemui` 拦死。根因是桌面 / 通知栏 / 最近任务的窗口事件把无启动入口的系统组件包名写成 `OPEN_APP`，`dependencies()` 收集后预检按「已安装应用」比对（`getLaunchIntentForPackage` 对 systemui / launcher 返回 null）而误判。修复分四层，全部 core 纯 JVM 可单测、权限模型零改动：

| 层 | 改动 |
| --- | --- |
| 判定 | 新增 `core/model/TargetPackages.kt`，集中识别 framework `android`、`com.android.systemui[.*]`、keyguard / server / internal / wallpaper、各类 launcher / home 包 |
| 录制编译 | `RecordCompiler` 过滤系统组件窗口事件、包守卫只为可启动目标生成、连续同包窗口事件去重、`toAction` 加防御 |
| 依赖 / 预检 | `dependencies()` 排除系统组件包 → **已保存的旧脚本无需数据迁移**，加载后预检自动放行 |
| 回放执行 | `ScriptRunner` 对残留系统组件 `OPEN_APP` 优雅跳过（记日志、不失败） |

真实第三方应用与有启动入口的系统应用（如 `com.android.settings`）不受影响，包守卫与「目标应用未安装」拦截能力完整保留。新增 10 例回归单测，含「系统组件与真实未安装应用混合时只拦截真实应用」。

**看日志定位**：

1. `无障碍服务已连接：eventTypes=0x…[clicked|…|windows]` —— 显示 `[无]` 或缺 `windowState`/`windows` 说明订阅未生效，重新开关无障碍服务。
2. `收到无障碍事件 #1 type=VIEW_CLICKED` —— 完全没有这一行说明系统未派发事件：检查无障碍开关；游戏 / Flutter / 部分 Compose 自绘界面可能根本不发被动语义事件。
3. `已记录 N 个事件（最新 CLICK）` 与通知栏步数应同步增长。
4. `录制诊断：原始事件 X / 转换 Y / 未映射丢弃 Z` —— `X=0` 系统没发；`X>0 且 Y>0` 但脚本为空则是编译期过滤（FOCUS 噪声 / 自身 UI / 无锚点）；`Z>0` 有未映射类型可据此补映射。

> 以上修复**只加只读日志与被动事件映射**，仍为零 MediaProjection、不消费触摸、不启用触摸浏览、不订阅高频 `typeWindowContentChanged`。先用「系统设置」等标准控件 APP 试录可快速验证链路。

## 测试策略

- `core` 全部逻辑（导入器/匹配器/录制编译/OCR 后处理/RF/闭环）JVM 单测，无需设备；`app` 的 QuickJS 绑定另有 13 例单测。
- 黄金样本：断言 `导入→导出→再导入` 往返一致（`热爱生活.zjs`，891585 字节 / 57 步，SHA256 `20e901fa…`）。
- 设备冒烟建议：Android 8 / 11 / 13 / 14 / 16 各跑「无障碍开启 → 录制 → 运行 → 暂停 → 停止」，并**全程开启系统录屏验证共存**。

## 开源协议与合规

### 本工程协议

**MIT License**，完整文本见 `LICENSE`。Copyright (c) 2026 NymphAuto contributors。

### 第三方依赖许可证

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Kotlin 标准库 / 插件 | 2.4.21 | Apache-2.0（JetBrains） | 语言运行时 |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | 协程 |
| kotlinx.serialization-json | 1.12.0 | Apache-2.0 | 脚本 JSON 编解码 |
| AndroidX core-ktx / appcompat | 1.17.0 / 1.8.0 | Apache-2.0（AOSP） | Android 兼容层 |
| Jetpack Compose / Material3 | BOM 2026.06.01 / 1.4.0 | Apache-2.0（AOSP） | Compose 界面 |
| Google Material Components | 1.14.0 | Apache-2.0（Google） | 界面组件 |
| quickjs-ng（vendored 源码） | 源码引入 | MIT | QuickJS 引擎，JNI 现场编译 |
| **ONNX Runtime Android** | **1.31.1** | **MIT** | 完全离线 OCR 推理 |
| ML Kit text-recognition / chinese | 16.0.1 | Google ML Kit 条款 | 中文 OCR 兼容兜底 |
| TensorFlow Lite Task Vision | 0.4.4 | Apache-2.0 | 目标检测 |
| efficientdet-lite0 int8 | 模型资产 | Apache-2.0 | COCO 80 类明文模型 |
| JUnit 4 / Hamcrest | 4.13.2 | EPL-1.0 / BSD-3 | **仅测试期**，不进入 APK |
| Android SDK | API 26–36 | Apache-2.0（AOSP） | 编译与平台 API |

上游版权声明与条款摘录见 `NOTICE`，组件清单 / 固定版本 / 合规红线 / 权限实测表见 `UPSTREAM_LICENSES.md`。发行版 APK 内不含任何 GPL/AGPL 组件。

### 互操作与商标声明

- **无代码复用**：未反编译、未打包、未引用自动精灵的任何代码、资源、素材与图标；`.zjs` 兼容仅通过解析其**公开导出的脚本文件格式**实现（数据格式互操作）。
- **商标归属**：「自动精灵」名称、图标及相关商标归其权利人所有，本工程与之无关联、未经其授权。
- **隐私与网络**：APK 不含 `INTERNET` 权限，不收集、不上传任何数据；日志与录制事件流仅本机明文存储。Manifest 另有 `READ/WRITE_EXTERNAL_STORAGE`、`READ_PHONE_STATE` 三条由上游依赖合并带入的声明，本工程未主动申请、代码不调用相关 API。
- **分发签名**：APK 使用本地测试密钥签名，仅用于功能验证；再分发者应使用自有密钥重签。

### 使用限制

按 MIT 协议授予使用、复制、修改、合并、发布、分发、再授权权利，但**必须保留版权声明与许可声明**；使用者需自行确保用途符合当地法律法规与目标 App 的服务条款。请勿用于游戏外挂、抢单、刷量等违规用途。

## 本机构建记录（v2.9.0 实测出包）

| 项 | 值 |
| --- | --- |
| 工具链 | JDK 17 target + Gradle 9.3.0 + AGP 8.13.2 + Kotlin 2.4.21 + SDK 36 + NDK 27.0.12077973 + build-tools 36.0.0 |
| 构建命令 | `gradle --max-workers=2 :core:test :app:testDebugUnitTest :app:assembleRelease` → **BUILD SUCCESSFUL**（退出码 0） |
| Release APK | **70.44 MB（73,866,023 字节）**，**未混淆 / 未加密**，zipalign + apksigner **v2/v3** 签名 |
| APK SHA-256 | `31daa8cee1281229739d820f9c96b282f0f78dc42769ffd7a95423099adb245c` |
| 源码包 | `NymphAuto-source-v2.9.0.zip`（完整 Gradle 工程）。**文件数、字节数与 SHA-256 见发布说明 / 下载页**——源码包内含本 README，自引用数字不写在此处以免循环 |
| 单测 | **346/346**（41 suite = core 314 + app 32，本版新增 27 例：token 6 + 窗口规格 6 + 原子落盘 7 + 迁移链 8），failures=0 / errors=0 / skipped=0 |
| 包信息 | `dev.nymph.auto` v2.9.0(18)，minSdk 26 / targetSdk 36 / compileSdk 36，仅 `arm64-v8a` |
| 权限核验 | 与 v2.3～v2.8.1 完全一致（8 项）：悬浮窗 / 前台服务（含 specialUse）/ 通知 / 震动 / 电池优化白名单 / AAPM 查询 / 动态接收器；无 INTERNET / 网络状态 / 存储 / 电话状态 / 任何录屏或音频类权限 |
| 无混淆核验 | dexdump：**972 个**本工程类全部保留原始可读名（含 `NymphDesignTokens` / `OverlayWindowSpec` / `AtomicTextFile` / `ScriptMigrations` / `RecordabilityGuard` / `LoopPolicy` / `OverlayTheme` / `DragGesture` / `WaitGate`），新符号已确认进 dex |
| 录屏 API | `MediaProjection` / `VirtualDisplay` / `createVirtualDisplay` / `MediaCodec` / `FLAG_SECURE` / `RECORD_AUDIO` / `WATCH_OUTSIDE_TOUCH` / `createScreenCaptureIntent` 类型与方法引用全为 0 |
| **16KB 页对齐（v2.9.0 核心）** | `tools/check_elf_alignment.py` 实测：自编译 `libquickjs-jni.so` LOAD Align `0x1000 → 0x4000`；6 个 so 中 5 个 `0x4000`，`zipalign -c -P 16` 通过；**如实列出 TFLite 预编译 `libtask_vision_jni.so` 仍 `0x1000`（本工程不可控）**；接入发版硬门槛 PASS |
| 包体增量 | 与 v2.8.1 同为 73,866,023 字节（改动全为纯 JVM 逻辑 + linker flags，zipalign 后凑巧同尺寸，SHA 已变） |
| 签名 | 测试密钥 `nymphauto.jks`（CN=NymphAuto，证书 SHA-256 `361722d6…6271`），与 v1.x 至 v2.8.1 全部历史版本同钥，支持原地升级 |
| 自动核对 | `python3.11 tools/verify_release.py` → **PASS**（版本/字节数/SHA/权限/ABI/签名/类数/禁用 API/单测/源码包/**16KB ELF 对齐**全部取自真实产物） |

**体积说明**：v1.1.0 为 30.6 MB，本版增量几乎全部来自 ONNX Runtime 1.31.1 完整 operator 库 `libonnxruntime.so`（解压后 34.2 MB）。ABI 仍只有 arm64-v8a。如不需要离线 ONNX 通道，移除 `com.microsoft.onnxruntime:onnxruntime-android` 依赖重新打包即可回到约 30MB，OCR 自动降级到 ML Kit / 节点文本，录制与回放不受影响。

## 已知边界（如实说明）

- 离线 ONNX OCR 需自备 PP-OCR 兼容的 `det.onnx` / `rec.onnx` / `keys.txt`；未放置时自动回退。ML Kit 兜底依赖 GMS 已缓存模型，而 APK 无 `INTERNET` 权限，该通道在无 GMS 设备上不可用。
- 被动语义事件由目标 APP 的 View 实现决定：自绘界面（游戏 / Flutter / 部分 Compose）可能不产生 `TYPE_VIEW_CLICKED`，此时由 OCR / 模板 / 坐标锚点补足，**无法保证 100% 还原多点触控、压力等手势细节**。
- 在桌面 / 通知栏等纯系统界面录制的脚本主要是滑动与窗口切换，回放无实际业务价值；系统组件窗口事件已被过滤（v1.2.2），不会因「目标应用未安装」阻塞回放。
- Shizuku 通道未接入，`pressure/size` 形态随机与安全窗口截图暂不可用；命中预览仅语义路径成立，像素路径依赖后置断言与实跑。
- `dispatchGesture` 已注入的手势无法运行中取消（平台限制）；中止条件对「等待/条件」类步骤生效，对进行中的手势不回溯。

## 合规声明

本工程仅做**功能与脚本格式层面的互操作**，不包含也不使用自动精灵的任何代码、资源与资产。工具定位为辅助个人合法的重复操作。详细方案见 `../NymphAuto-复刻方案.md`。

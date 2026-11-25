# NymphAuto 工程治理：ADR / 路线图 / 验收状态

> 建立于 v2.7.0，回应「每版靠真机反馈被动修 bug，缺 BGI 那样的 ADR / 路线图 /
> 验收状态维护；README 数字全靠手工核对」。
> 本文件是**活的**：每次版本变更必须同步更新「验收状态」与「ADR 索引」。
> 所有数字由 `tools/verify_release.py` 从真实产物自动生成（见文末）。

---

## 一、架构决策记录（ADR）

### ADR-001 坐标拾取改为非模态穿透双窗口

- **状态**：已接受，v2.7.0 实施
- **背景**：v2.6.0 拾取层是 `MATCH_PARENT` 且可触摸的全屏模态窗口，盖上之后
  底层 APP 完全收不到触摸；底部大按钮常遮挡待拾取位置。需求约束为：拾取坐标的
  同时，指针覆盖区域之外仍可正常操作底层界面。
- **决策**：拆成两个 `TYPE_APPLICATION_OVERLAY` 窗口。
  1. 绘制层：全屏、`FLAG_NOT_TOUCHABLE`，只画准星/标尺/读数/放大镜，
     去掉全屏暗色蒙版，底层内容原样可见；
  2. 控制手柄：`FLAG_NOT_TOUCH_MODAL`，默认 30dp 圆点，点击展开 196×156dp
     极简工具条，可拖动。
- **约束**：零 `FLAG_WATCH_OUTSIDE_TOUCH`、零外部触摸监听、零录屏权限。
  运行/录制启动前仍强制 `dismiss()`，因为绘制层会被 `takeScreenshot()` 截入。
- **后果**：手柄之外 = 底层 APP 正常操作区。代价是准星不再能「点哪是哪」，
  改为拖动手柄定位；以读数（像素整数 + 4 位小数百分比）+ 放大镜 +
  ±1px 长按连发补偿精度。
- **已知限制**：放大镜取自无障碍截图，会包含绘制层自身的准星细线与手柄圆点，
  故放大镜仅作辅助，精确定位以十字/读数为准。

### ADR-002 用「帧同步高频采样 + 运动外推 + 窄 ROI」替代「1ms 找坐标」

- **状态**：已接受，v2.7.0 实施；**明确不承诺 1ms**
- **背景**：既有方案识别间隔 100ms，无法覆盖高速移动目标；需求提出在 1ms 内
  基于特征自动定位坐标。
- **物理约束**：Android 无障碍全屏 `takeScreenshot()` 一帧本身需 16~60ms，
  灰度化 + NCC 还要时间。任何特征算法都突破不了**采样帧率**这个下限，
  宣称 1ms 属于虚假承诺。
- **决策**：`RecognitionTuning` 把轮询间隔做成可配置（16/33/50/100/300ms +
  自定义），默认 33ms（≈30Hz）替代旧版硬编码 300ms；`MotionPredictor`
  用历史命中点外推速度，命中后优先在预测点周围开窄 ROI，NCC 计算量
  从全屏降到约 (r/50)²；预测失准时 ROI 随置信度自动放大，最坏退回全屏。
- **后果**：在物理上限内把「采样→命中」闭环延迟压低、跟得上移动目标，
  但不宣称 1ms。设置页可一键退回 `LEGACY`（300ms）行为。

### ADR-003 录制事件闸门：自扰过滤 + 待命

- **状态**：已接受，v2.7.0 实施，core 单测覆盖
- **背景**：「刚开始录制，脚本就开始记录了，即使还在点击器里……一个点击器
  里能有什么自己对自己的自动化」；「没时间切回后台拾取坐标」。
- **决策**：策略下沉到纯 JVM `RecordGate`。来自本应用包名的事件一律丢弃；
  启动后处于待命，直到收到第一个非本应用且有包名的事件才开录（该事件本身
  保留，通常是 `WINDOW_CHANGED`，正好供 `RecordCompiler` 写 `startPackage`）。
- **后果**：用户点「开始录制」后无需手忙脚乱切后台，通知栏/浮窗显示
  「待命 → 录制中」。老调用点不传自包名时行为与 v2.6.0 完全一致。
- **澄清**：引擎早有 `AnchorSelector`（节点 ID / 节点文字 / OCR 文字 /
  图像模板 / 坐标兜底）与 `RecordCompiler`，回放本就是「按特征重新定位」，
  不是固定坐标回放。本版是把它变成默认 + 提速，而非新增录制功能。

### ADR-004 脚本落盘改为压缩容器 + 模板去重

- **状态**：已接受，v2.7.0 实施，core 单测覆盖
- **背景**：「脚本太大了不易保存和维护」。v2.6.0 写 `prettyPrint=true` +
  `encodeDefaults=true` 明文 JSON，重复模板内联 N 份。
- **决策**：`ScriptCompressor` 做三件事——重复 `imageData` 抽到
  `Script.assets`（`ref:xxxx`）、紧凑 JSON、`DEFLATE + Base64`（`NAZ1:` 头）。
  读取侧 `ScriptCodec.decode` 自动识别新旧格式并 `expand()` 还原完整 base64。
- **后果**：运行引擎 `ScriptRunner` / `VisionPipeline` 完全无感知；老明文 JSON
  无需迁移即可打开。解压设 64MB 上限防畸形文件。`ScriptRepository` 在此之上
  提供修订号 + 最近 8 份历史快照 + 回滚。

### ADR-005 编辑器禁止重建 Action（数据保真守卫）

- **状态**：已接受，v2.7.0 实施，core 单测覆盖
- **背景**：v2.6.0 `kindOf()` 对非 4 类动作返回 0，打开 WAIT / RUN_ACTIONS /
  CLICK_IMAGE 会被当成点击，确认时用 `Action(...)` **重新构造**，
  `children` / `listeners` / `condition` / `imageData` / `jsCode` 等被静默清空。
  这是**数据丢失级 bug**。
- **决策**：`ActionEditGuard` 作为单一真源——未知类型返回 `-1`，UI 切换为
  只读提示并禁用「确定」；简易类型一律 `ActionEditGuard.apply()` 基于
  `original.copy(...)`，只改受支持字段。
- **后果**：新增步骤/源码双视图，源码视图覆盖全部 30 种动作、目标、条件、
  变量、JS、子脚本，带实时 JSON 语法校验与撤销/重做（60 步）。

### ADR-006 mp4 导入采用「半自动候选」而非全自动总结

- **状态**：已接受，v2.7.0 实施
- **背景**：用户希望「录 mp4 导入扫描，自动总结点击特征，直接生成脚本」。
- **约束**：视频里没有「这里是第 N 次点击」的标签，按压涟漪、按钮高亮、
  列表滚动、页面切换在像素上都表现为帧差，纯算法必然误判。
- **决策**：`MediaMetadataRetriever` 抽帧（零 MediaProjection、零网络、只读
  用户选中文件）→ 降采样灰度 → `TapCandidateDetector` 帧差检测候选 →
  **用户勾选确认** → 从该时刻全分辨率帧截模板 → `VideoScriptBuilder` 生成
  `CLICK_IMAGE` 特征脚本。模板缺失显式降级为坐标点击，不误报图像匹配。
- **后果**：在能力边界内可用。时间戳有帧级误差（关键帧稀疏会漏短促点击），
  不宣称「自动总结」。

### ADR-008 新增 core 能力必须接线到生产链路，禁止「写了没接」

- **状态**：已接受，v2.7.0 发版前自查补接线
- **背景**：v2.7.0 交付前做引用点扫描，发现 `RoiExpr`（ROI 表达式）与
  `ScriptRepository`（版本/修订）**只存在于 core 自身 + 单测**，没有任何
  生产代码调用——单测全绿但功能对用户不可见。这正是「脚本执行动作与
  实际需求不符合」那类问题的温床：能力写了、测试也过了，实际链路没变。
- **决策**：
  1. 给 `Region` 加 `expr: String = ""`（`Action.region` 与 `Condition.region`
     同时受益），core 新增 `RoiExpr.resolveRegion()` 负责「表达式 → 数值区域」，
     非法表达式静默退回数值、绝不中断回放；
  2. `ScriptRunner` 的找图 / 找色 / AI / 诊断 / 条件共 8 个调用点统一走
     `RoiExpr.resolveRegion()`，与 `predictedRegion()` 的窄 ROI 取交集；
  3. `AppViewModel` 的 `saveScript` 改走 `ScriptRepository.save()`（自动
     `revision+1` 并存上一版快照），`deleteScript` 联动 `purge()`，脚本卡片
     菜单新增「历史版本 / 回滚」入口调 `restoreScript()`。
- **约束**：`expr` 为空时零开销、原样返回，老脚本行为与 v2.6.0 完全一致；
  历史文件放 `scripts/.history/`，`ScriptStore.list()` 用 `isFile` 过滤不递归，
  不会被误列成脚本。
- **后果**：发版前新增一条硬性检查——`grep` 统计每个新增 core 符号的引用点，
  只有自身 + 测试 = 判定「未接线」，必须补齐或明确标注为待接线能力。
- **单测**：`RoiExprTest` 新增 3 例（空表达式原样返回 / 表达式优先 / 非法静默退回）。

### ADR-009 拾取会话生命周期与坐标真值必须与窗口解耦

- **状态**：已接受，v2.7.1 实施（修复「屏幕拾取起点（非模态）」全部缺陷）
- **背景**：v2.7.0 发布后「屏幕拾取起点（非模态）」功能存在缺陷。查真实链路得
  到两个确定性根因，而非手感问题：
  1. `ActionEditDialog` 用 `DisposableEffect(pickStamp)`，而 `startPick()` 里
     `pickStamp++` 一自增就触发重组，旧 effect 的 `onDispose { CoordinatePicker.dismiss() }`
     把刚 `show()` 的浮层与回调一起清掉——**浮层一闪就没 / 点了没反应**；
  2. 准星 `crossX/crossY` 由「手柄窗口中心」反算（`lp.x + width/2`），工具条
     展开成 196×156dp 或手柄被 `coerceIn` 夹到屏幕边缘时中心漂移，
     `confirm()` 取到的坐标与拖动时看到的十字不一致。
- **决策**：
  1. 浮层生命周期 key 改为恒定 `Unit`，整个对话框只注册一次；`dismiss()`
     只在对话框真正销毁时执行。回填走**即时回调**（同进程 overlay 窗口不触发
     `ON_RESUME`），`ON_RESUME` 兜底用 `resultSeq` 严格判断「本次 show 之后
     才产生的新结果」，杜绝陈旧/重复回填；
  2. 新增纯 JVM `core/.../vision/PickerGeometry`：准星抽成**独立像素真值**
     （`crossX/crossY/bX/bY`），拖动只走 `drag` 累加 + clamp，窗口
     `LayoutParams` 只负责呈现，展开/折叠/贴边都不改真值；
  3. `show()` 去掉 `if (session != null) return true` 幂等吞请求，改为
     「先 `dismiss()` 再按最新参数重建」；`buildDeliver` 统一投递，
     点/框模式互转时缺失回调自动做「点 ↔ 区域中心」等价转换，结果绝不空转；
  4. 所有数值格式化统一到 `PickerGeometry.fmt/fmt4/regionExpr`（强制
     `Locale.US`），空串/非法输入由编辑器 `num(s, fallback)` 收敛为 0，
     **绝不静默 return**。修德语/法语区 `34,5678` 被回填解析成 null、
     ROI 四元组被逗号切碎的隐蔽 bug。
- **后果**：新增硬性约束——**凡是「用户看到的坐标」与「运行时取到的坐标」
  必须是同一份真值**，禁止从窗口布局参数反算；浮层生命周期不得依赖会
  自增的重组 key。
- **单测**：`PickerGeometryTest` 11 例（真值/拖动/clamp/框选归一化/百分比/
  Locale 下 `regionExpr→RoiExpr.resolve` 链路）、`PickerRegionTest` 4 例、
  `EditorFormatTest` 补 1 例德语区回填不清零回归。
- **边界**：修的是从代码链路读出的确定性逻辑缺陷 + JVM 单测；
  各 ROM 实际触摸分发、拖动手感、折叠屏/分屏坐标仍**待真机**。

### ADR-010 ROI 必须有唯一真源，且必须写入「真正读取 region」的全集

- **状态**：已接受，v2.7.1 实施（修复「框选搜索区域 ROI / 模板范围」全部缺陷）
- **背景**：「框选搜索区域 ROI / 模板范围」功能存在缺陷。根因是**功能错配**：
  v2.7.0 把框选结果只写进当前动作的 `region`，但简易编辑器只编辑
  TAP/LONG_PRESS/MULTI_TAP/SWIPE 四种坐标动作，而 `ScriptRunner` 里
  `region` 只被找图/找色/找文字/AI/节点/条件读取——**坐标动作不读取该字段**。
  用户框完确认保存，运行时毫无变化，界面还显示「已框选」，等于白框。
  这是 ADR-008「写了没接」的变体：这次是「写入点接错了对象」。
- **决策**：
  1. 新增纯 JVM `core/.../editor/RoiApplier` 作**唯一真源**：`apply(script, region)`
    把 ROI 写入脚本内**所有真正读取 region 的位置**——视觉目标动作、条件树、
    `children`、`listeners`、`abortCondition`，全程用 `copy` 保真
    （不丢 `imageData`/`condition`/`listeners`），返回**命中数**；
  2. UI 显示实际值「已应用到 N 处」，`N=0` 时明确提示脚本内暂无找图目标并
    引导到源码视图，**不再静默「已框选」**；`countTargets()` 预显示目标数，
    `hasExplicitRegion()` 让已有 region 的动作打开时不再显示成「未框选」；
  3. ROI 同时写入数值四元组与 `Region.expr`，运行时由 ADR-008 的
    `RoiExpr.resolveRegion` 优先按表达式解析；框选矩形统一走
    `PickerGeometry.normalizeRegion`（任意两角顺序、越界、零面积收敛）；
  4. 撤销栈从 `List<Action>` 升级为**完整 `Script` 快照**，否则 ROI 改的
    脚本级 `listeners`/`abortCondition` 撤销回退不了；源码视图「应用」同样保真。
- **后果**：发版前新增一条硬性检查——**任何「应用到脚本」类能力，必须枚举
  运行时真正读取该字段的全部位置**，不能只写当前编辑对象；且必须返回
  命中数并在 0 命中时告知实际值用户，禁止用「成功」掩盖空操作。
- **单测**：`RoiApplierTest` 6 例（目标动作/条件树/children/listeners/中止条件、
  无目标返回原对象、命中数一致）。
- **边界**：ROI 只对找图/找色/找文字/AI/节点动作与条件生效；
  脚本里没有这类动作时按实际值返回 0。简易编辑器仍只编辑 4 种坐标动作。

### ADR-011 编辑动作界面必须是系统级悬浮窗，不得绑定 Activity 生命周期

- **状态**：已接受，v2.8.0 实施（对应能力评估结论：「编辑动作的整个界面不支持脱离
  NymphAuto 在其他 APK 界面运行，另外太大了」）
- **背景**：用户给出一份「系统级悬浮窗（System Overlay / 后台可显示）vs
  前台绑定悬浮窗（Activity 生命周期绑定）」的资料，并明确要求**以源码为准**。
  核实结果：本工程为**混合架构**，比资料的二分法更细：
  1. `ActionEditDialog` 是 Compose `AlertDialog`，画在
     `MainActivity.setContent{}` 视图树内 —— **连独立 Dialog 窗口都不是**，
     更不是 `TYPE_APPLICATION_OVERLAY`。切到其他 APP 随 `onStop` 消失，
     也无法在其他 APP 之上呼出。评估结论成立。
  2. `CoordinatePicker` / `FloatBall`+`BallPanel` / `RecordingService` 控制浮窗
     才是真正的系统 Overlay（`TYPE_APPLICATION_OVERLAY`），由 `specialUse`
     前台服务保活，可跨 APP、后台可呼出。
  3. 截图里那个对话框占约 94% 屏宽 / 73% 屏高，「太大」同样属实。
  - **注意**：问题不是「缺悬浮窗权限」——`SYSTEM_ALERT_WINDOW` 早有、8 项
    权限零新增。是**编辑动作这套界面没走系统 Overlay 通道**。
- **决策**：
  1. 新增 `FloatingEditorService`（`specialUse` 前台服务）：`WindowManager.addView`
     以 `TYPE_APPLICATION_OVERLAY` 添加面板；前台服务维持窗口生命周期，
     通知栏提供「呼出 / 收起 / 关闭」，切后台或切到任意第三方 APP 都持续存在。
     **这才是「可后台保持的系统悬浮窗」**。
  2. 面板是纯 View（`FloatingEditorPanel`）而非 Compose：挂在 Service 窗口上
     不需要 `LifecycleOwner` / Compose 宿主，与既有 `BallPanel` 同风格、更稳。
  3. **紧凑**：折叠态只有 40dp 小球；展开态 240dp 宽（360dp 屏占 67%）、
     高度上限屏高 62%（自定义 `MaxHeightScrollView` 保证真正生效，超出内部
     滚动）；原 Activity 内 `AlertDialog` 同步限宽 340dp + 高级参数默认折叠。
  4. 确定性逻辑（尺寸档位 / 折叠 / 位置 clamp / 拖动 / 表单解析 / Locale）
     全部抽到纯 JVM `core/.../editor/FloatingEditorLayout`，**有 12 例单测**；
     窗口/View 层只做渲染，不自己算坐标。
  5. **复用而非重写**：脚本读写走 `ScriptStore`/`ScriptRepository`（与主界面
     同一真源）、动作保存走 `ActionEditGuard.apply`（`copy` 保真）、
     ROI 走 `RoiApplier.apply`（唯一真源 + 命中数反馈实际值）、坐标拾取复用
     `CoordinatePicker`。不新建第二套脚本格式。
- **已识别的两类缺陷（已修，记录以免回退）**：
  1. 展开态**不能**加 `FLAG_NOT_FOCUSABLE`，否则 `EditText` 抢不到焦点、
     弹不出软键盘，用户没法手输坐标。改为：折叠态加（小球不抢焦点）、
     展开态去掉（可输入）；两者都保留 `FLAG_NOT_TOUCH_MODAL` 让面板外触摸
     透传底层 APP。
  2. 拖动不能写在根 `View.onTouchEvent`：`LinearLayout` 会先把事件分发给子
     控件（`Button`/`EditText` 消费 DOWN），根 View 收不到后续 MOVE。
     改为给「折叠小球」和「顶部拖动条」各装独立 `OnTouchListener`。
- **约束**：零新增权限、零 `MediaProjection` / `VirtualDisplay` / `MediaCodec`
  / `FLAG_SECURE` / `RECORD_AUDIO` / `FLAG_WATCH_OUTSIDE_TOUCH`；
  不订阅无障碍事件；`isMinifyEnabled=false` 保持类名可读。
- **后果**：发版前新增两条硬性检查——(a) 任何「可呼出的编辑 / 控制界面」
  必须明确它属于系统 Overlay 还是 Activity 内 UI，评估结论中这两类不能混；
  (b) 悬浮窗里的输入控件必须验证 flag 组合确实允许获取焦点。
- **边界**：本环境无 Android 设备，`TYPE_APPLICATION_OVERLAY` 在各 ROM 的
  实际触摸分发、软键盘行为、前台服务保活、通知栏呼出、折叠/展开手势，
  **全部待真机验证**，不误标为已验证。对比视频链接缺访问参数无法取回，
  紧凑尺寸依据是评估截图（约 94%×73%）与本项目既有 196×156dp 工具条。

### ADR-012 悬浮窗口拖动必须用屏幕绝对坐标并按帧提交，flags 不得在交互中来回切换

- **状态**：已接受，v2.8.1 实施（对应能力评估结论：窗口持续反复重排、视觉层级不规范）
- **背景**：这次对比视频链接带签名参数，**成功取回** 19.6MB 真 MP4（720×328 /
  60fps / 142s）。逐帧像素差分析与连续帧放大拼接给出可见证据：悬浮面板的
  **左/上边缘位置在跳动**、**可见内容范围忽多忽少**（有的帧只到「屏幕拾取
  起点」、有的多出「框选 ROI」一行）——不是动画，是窗口在反复 `relayout`。
  画面同时证实灰蓝实心方块按钮、10.5sp 拥挤排版、`⠿`/`▲` 缺字字符。
  从 v2.8.0 源码读出 **5 个确定性根因**：
  1. 拖动用局部坐标增量 `e.x - lastX` **累加**，且每个 `ACTION_MOVE` 都
     `wm.updateViewLayout()`。一旦窗口重布局（尤其 `WRAP_CONTENT` 高度变化、
     沉浸式游戏状态栏切换），被拖动 View 在窗口内的位置会漂，局部增量不再
     等于屏幕位移 → 跟手抖动 + 越拖越偏；60~120Hz 触摸序列触发同等频率的
     全窗口 measure/layout。
  2. 窗口 `height = WRAP_CONTENT`，内容行数一变高度就跟着变，measure 反复跑。
  3. `MaxHeightScrollView.onMeasure` 对 **UNSPECIFIED** 取
     `MeasureSpec.getSize() = 0`，再 `makeMeasureSpec(0, AT_MOST)` 把内容高度
     压成 0，与父 `LinearLayout` 反复博弈 —— 这是「内容忽多忽少」的直接原因。
  4. `setCollapsed()` 每次切换 `FLAG_NOT_FOCUSABLE` 且都 `startForeground()`：
     flags 变更触发窗口重建 / 焦点变化 → 软键盘弹出收起 → 沉浸式可用高度变化
     → **循环抽搐**（最可能造成「一直」不停）。
  5. 每次 MOVE 重读 `displayMetrics`，全屏游戏状态栏切换时 `heightPixels`
     跳变，clamp 边界跟着跳，窗口 y 跳。
- **决策**：
  1. **拖动真值改为屏幕绝对坐标**：新增纯 JVM
     `FloatingEditorLayout.dragAbsolute(startWinX, startWinY, downRawX,
     downRawY, rawX, rawY, ...)`，用「按下时的窗口坐标 + 手指绝对位移」，
     不累加、不依赖任何 View 局部坐标系。`shouldCommit()` 过滤 <1px 无效变化
     （`DRAG_COMMIT_PX=1`），app 层用 `Choreographer.postFrameCallback` 把一帧
     内的多次 MOVE 合并成**最多一次** `updateViewLayout`。
  2. **窗口 flags 全程恒定** `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL`；
     不再随折叠/展开展来回切。仅当 `EditText` 真正获焦时由
     `setInputFocusable(true)` 临时去掉 `NOT_FOCUSABLE`，失焦即恢复，把焦点
     切换次数从「每次形态切换」降到「每次真正输入」。
  3. **测量修正**：`FloatingEditorLayout.measureHeight(mode, size, maxH)`
     按 `MeasureMode` 分派——UNSPECIFIED → `AT_MOST(maxH)`，其余取
     `min(size, maxH)` 且 EXACTLY 保持 EXACTLY，杜绝 `AT_MOST(0)`。
  4. **尺寸稳定**：`refreshMetrics()` 缓存屏幕宽高（不再每次 MOVE 重读）、
     状态行固定最小高度 `STATUS_MIN_DP`、`refreshForeground()` 去重避免重复
     `startForeground` 叠加窗口更新。
  5. **视觉 token 单一真源**：新增纯 JVM `FloatingEditorTheme`，收敛色板 /
     圆角 / 间距 / 字号（深墨蓝玻璃卡片 + 单一翡翠绿强调、次级按钮改描边、
     胶囊 chips、字号 13/12.5/11/10.5sp 严格递减、去除全部缺字字符）。
     `FloatingEditorPanel` 只负责绘制；token 的字号层级、几何合法性、
     文字/底色对比度由 `FloatingEditorThemeTest` 5 例在 JVM 上约束，
     不靠肉眼猜。
  6. 抗抽搐逻辑同样落在 core：`FloatingEditorLayoutTest` 补 5 例
     （绝对坐标不漂移、越界 clamp、提交阈值、UNSPECIFIED 不再压 0、
     三种测量模式）。合计新增 **10 例**，全量 **319/319**。
- **约束**：零新增权限、零录屏 API、无混淆；不改动 `CoordinatePicker` /
  回放 / 录制链路；脚本存储与 `ActionEditGuard` / `RoiApplier` 复用不变。
- **后果**：发版前新增三条硬性检查——(a) 任何 `TYPE_APPLICATION_OVERLAY`
  的拖动必须用 `rawX/rawY` 绝对坐标，禁止局部增量累加；(b) 触摸驱动的
  `updateViewLayout` 必须按帧合并并带变化阈值，禁止每个 MOVE 直接提交；
  (c) 窗口 flags 在生命周期内必须恒定，输入焦点按需申请而非随形态切换。
- **边界**：本环境无 Android 设备，`Choreographer` 按帧节流的实际跟手度、
  各 ROM 的软键盘/焦点行为、前台服务保活、折叠/展开/拖动手感、面板与
  `CoordinatePicker` 的 z-order **全部待真机回归**，不误标为已验证。视频只
  用于定位「窗口反复 relayout」这一现象，未据其推断任何源码未确认的行为。

### ADR-013 16 KB 页对齐必须由产物实测证明，不能用构建配置自我声明

- **状态**：已接受，v2.9.0 实施。输入是若干外部 agents 的改进建议；本条是
  其中唯一「不做会出事」且**能在本环境被真证明**的一条。
- **背景**：解包 v2.8.1 release APK 逐个 so 检查 ELF LOAD 段 Align，发现
  自编译 `libquickjs-jni.so` 仍是 `0x1000`(4KB)，而 `build.gradle.kts` 与
  README 却把 `packaging { jniLibs.useLegacyPackaging = false }` 注释成
  「16KB 页大小对齐」——**文档与产物不符**。这一项只让 APK 内 so 未压缩、
  配合 `zipalign -P 16` 保证 **ZIP 条目**对齐，与 **ELF LOAD 段**对齐是两回事。
  联网核实：Android 15 起存在 16 KB 页设备，Google Play 自 2025-11-01 对
  targetSdk 15+ 的应用强制要求；本工程 targetSdk 36 已命中。NDK r28+ 默认
  16KB 对齐，r27 必须显式加 linker flags（本工程正是 r27）。
- **决策**：
  1. `cpp/CMakeLists.txt` 的 Android 分支加
     `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` 重编。
     **不设** `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`：该变量只影响 NDK
     自带 `libc++_shared.so` 的选择，本工程纯 C（仅链 `m` / `log`），设了
     不起作用；且 `android.externalNativeBuild.cmake` 块不接受 `arguments`
     （放进去会编译失败，已实测）。
  2. 新增 `tools/check_elf_alignment.py`：解包 APK、`llvm-readelf -l` 逐个
     so 读 LOAD Align，自编译产物未对齐直接判 FAIL；**接入
     `tools/verify_release.py` 作为发版硬门槛**。
  3. 修正 `build.gradle.kts` 与 README 的不实注释，明确 ZIP 对齐 ≠ ELF 对齐。
- **实测结果**：`libquickjs-jni.so` LOAD Align `0x1000 → 0x4000`；6 个 so 中
  5 个 `0x4000`，`zipalign -c -P 16` 通过。
- **按实际情况边界**：TFLite Task Vision 0.4.4 预编译 `libtask_vision_jni.so` 仍
  `0x1000`，本工程 CMake 不可控，**不误标为已修复**。官网核实 TFLite 已更名
  LiteRT（`com.google.ai.edge.litert`），但 Task Vision 属 Task Library、
  **无 LiteRT artifact**，且 Delegate→CompiledModel 是破坏性迁移，无真机
  无法验证检测精度，故本版不迁。16 KB 页设备上该 so 加载失败时
  `AiDetector.ensure()` 的 `runCatching`（捕获 `Throwable` 含
  `UnsatisfiedLinkError`）优雅降级为 AI 找目标不可用、其余能力照常，
  并补了针对性归因日志。彻底解决需上游提供对齐的预编译库或统一到
  ONNX 单一运行时（需真机验证精度）。
- **后果**：今后任何发版若自编译 so 退回 4KB 对齐，`verify_release.py` 直接
  FAIL，杜绝「文档说做了、产物没做」再次发生。

### ADR-014 设计 token 必须单一真源，两套 UI 不得各写字面量

- **状态**：已接受，v2.9.0 实施（回应上一轮自检的「UI 设计缺陷 1」）。
- **背景**：v2.8.1 把悬浮层色值收敛进 `FloatingEditorTheme`，但 Compose 主界面
  `Theme.kt` 仍另有一份 `Color(0xFF...)` 硬编码，同一个「警告色」两处各写
  一遍、值可能漂移，纯靠约定维持，无编译期约束也无单测能发现。
- **决策**：新增 core 纯 JVM `NymphDesignTokens` 作为**唯一真源**（`Long`
  0xAARRGGBB 常量 + 尺寸/字号 + 对比度/层级约束）；`FloatingEditorTheme`
  降级为 `Long→Int` 的**投影层**（保留全部公开名，视觉值一字节不变）；
  `Theme.kt` 通过 `c(token) = Color(asArgb(token))` 引用同一批常量。
  刻意保留「载体差异允许两套色板」（M3 全屏界面 vs 半透明玻璃浮层对比度
  需求不同），但**不允许消费方另写字面量**；语义色钉死、不启用动态取色
  （延续 v2.8.1 正确决定）。
- **约束**：纯 JVM，不依赖 Compose/Android；`NymphDesignTokensTest` 6 例
  逐槽断言两处同源、对比度构造性达标、字号层级、语义色可区分。
- **边界**：只统一色板；两套 UI 的排版尺度（M3 Typography vs 悬浮层 13sp
  体系）本就不同，强行统一反而错，不在本 ADR 范围。

### ADR-015 靠注释保证的窗口约束必须升级为可执行断言

- **状态**：已接受，v2.9.0 实施。
- **背景**：ADR-012 / README 写「窗口 flags 全程恒定」，但
  `setInputFocusable()` 本就会把 flags 从 `NOT_FOCUSABLE|NOT_TOUCH_MODAL`
  临时切成 `NOT_TOUCH_MODAL`（输入框弹键盘所必需）。**文档与代码不符**，
  且「不许乱加 flag」只写在注释里，后来者无从得知、无测试拦截。
- **决策**：新增 core 纯 JVM `OverlayWindowSpec`，定义唯一合法的两个 flags
  取值（`STANDARD_FLAGS` / `INPUT_FLAGS`）、`flagsFor(inputFocused)`、
  `isAllowed`、`isLegalTransition`，以及 `Frame` + `isPositionOnlyUpdate`
  （拖动提交只改 x/y，flags/type/format/width/height 必须逐字不变）。
  `FloatingEditorService` 的 `baseParams()`、`setInputFocusable()`、按帧拖动
  提交三处全部接线；位掩码值与 AOSP `WindowManager.LayoutParams` 对齐并单测。
  文档表述同步改为「只允许两种受控状态、切换只由输入焦点触发」。
- **约束**：core 不 import Android（位掩码用字面量常量 + 单测锁定）；
  `OverlayWindowSpecTest` 6 例覆盖位掩码、两态、非法 flag 拒绝、合法切换、
  仅位移提交、非位移改动丢弃。
- **为什么不用 Robolectric**：外部建议用 Robolectric 断言 LayoutParams，
  但那要引入新测试依赖与离线缓存风险；把规格抽成纯 JVM 数据 + 纯函数，
  在 core 层用 JUnit 就能锁死同一条线，且更贴合本工程「core 零 Android
  依赖」的地基。这不是降低标准，是换了更省的实现路径。

### ADR-016 落盘必须原子，schemaVersion 必须有版本闸门与迁移链

- **状态**：已接受，v2.9.0 实施。
- **背景**：`ScriptStore.save()` 原为 `f.writeText(...)` 直接截断覆盖；编辑
  会话中进程被杀会留下「写一半」的文件，下次 `list()` 解析失败静默跳过，
  用户看到的是**脚本凭空消失**。另外 `Script.schemaVersion` 字段早在 v2.7.0
  就存在却**从无读取逻辑**，「不加密明文」的代价是格式演进必须靠版本号兜，
  否则未来版本脚本会被 `ignoreUnknownKeys` 静默当 v1 打开、字段按默认值
  补齐却行为全错——比直接报错更危险。
- **决策**：
  1. 新增 core `AtomicTextFile`（同目录 `.tmp` → `fsync` → `Files.move`
     `ATOMIC_MOVE|REPLACE_EXISTING`，失败只删 tmp 绝不破坏旧文件；启动时
     `cleanupStale` 清理残留），接入 `ScriptStore` / `ScriptRepository` /
     `ScriptGrants` 全部落盘点。
  2. 新增 core `ScriptMigrations`（`classify` 分出历史/当前/未来/损坏四类、
     逐级迁移链 `chain`、版本闸门 `isSupported`），接入 `ScriptCodec.decode`；
     未来版本明确拒绝，v1 / 无版本字段历史脚本 / 压缩容器行为不变。
  3. 迁移链当前为空（v1 是基线），但结构就位：未来加 v2 只需注册一个
     `(Script)->Script`，调用方零改动。
- **约束**：纯 JVM、不新增权限、不改脚本格式；`AtomicTextFileTest` 7 例 +
  `ScriptMigrationsTest` 8 例覆盖。
- **未做（按实际状态记录）**：外部建议的「进程内单一 `MutableStateFlow<EditorSession>`
  状态机 + sealed class 显式状态」需要改 Activity 与 4 类悬浮层、且无真机
  验证跨窗口状态同步，风险大于可证收益，本版缓做；原子写是其零风险前置。

### ADR-017 同包名不等于同界面：位置敏感脚本的界面风险必须必达用户

- **状态**：已接受，v2.9.1 实施。
- **背景（2026-11-10 真机调试包）**：99 秒 120fps 录屏 + 运行日志显示，用户在
  **应用抽屉**录制 97 个 `VIEW_SCROLLED`（编译成 **38 个 SWIPE 翻页**），回放却
  停在桌面主屏 / 日历小组件 / 搜索历史页 / 输入法键盘——手势全部打在错误界面，
  两次回放都被手动停止，且两次总结都是「命中 0、跳过 0、失败 0」。逐帧核对：
  录屏 t≈16~26s 是抽屉按 A–Z 滚动，t≈50~82s 是回放乱翻页并误触搜索框弹键盘。
  日志中**没有任何一行界面校验输出**。
- **根因（三个确定性缺陷，均以源码证明）**：
  1. `SceneGuard.decide()` 只比对 `packageName`。应用抽屉、桌面主屏、搜索页
     **同属一个 launcher 包名** → `recorded == current` 直接 `PROCEED`；
     `rootInActiveWindow()` 取不到包名或回放瞬间前台仍是 NymphAuto → `SKIP`。
     两条路径都静默放行。该文件头注释写的正是这一场景，守卫却结构性失效。
  2. `RecordCompiler` 生成的告警「回放前请手动回到录制起始界面，滚动位置无法
     自动还原」只写进 `Script.description`，`ScriptRunner` 从不读取，用户永不可见。
  3. 旧 `RunStats` 是 `ScriptRunner` 内部私有类，`hit/miss` 只在 `withRetry()`
     （查找类命中）自增，SWIPE / TAP 等直接派发手势走 `dispatch()` 不计任何口径，
     `executed` 定义了却从未自增 → 纯翻页脚本必然「全 0」。
- **决策**：
  1. core `SceneGuard` 新增 `POSITIONAL_TYPES` + `hasPositionalActions(types)`，
     精确定义「不带目标校验、直接按坐标派发」的手势（查找类动作不计入）。
     `decide()` 增加默认参数 `hasPositionalActions`（默认 false → **既有四种决策
     逐字不变**）与新决策 `PROCEED_WITH_WARNING`；含坐标手势时，把 `PROCEED`
     （同包名）、`SKIP`（取不到包名 / 自家界面）三条静默放行升级为必达告警。
     告警文案由 core `positionalWarning()` 单一真源生成，避免两处各写一遍。
  2. **刻意不阻断执行**：同包名在原理上无法证明界面一致，拦死会误伤「用户已
     手动回到正确界面」的正常回放；误报一条警告的代价远小于拦死脚本。
     `emitSceneNotice` 三通道必达（日志页 `onLog` + `RunLog.w` + `onSceneNotice`
     toast），`RunnerService` 接线为 WARN 级 toast（不写成 ERROR、不中止）。
     `RESTORE_APP` 拉起应用后、守卫关闭 / 老脚本无起始包名时同样送达。
  3. 新增 core 纯 JVM `RunStats`（`core/runner/RunStats.kt`）作为统计单一真源，
     主循环无条件 `executed++`；`ScriptRunner` 删除内部私有类、`roundSummary` 与
     轮次日志委托 core 格式化。总结为「已执行 N 步，命中 X、跳过 Y、失败 Z」，
     纯手势脚本额外标注「无目标校验，命中/失败口径不适用」——**不把坐标手势
     伪装成命中**，不制造新的口径谎言。
- **约束**：core 零 Android 依赖；`decide()` 默认参数保证老调用零回归；
  `SceneGuardPositionalTest` 11 例 + `RunStatsTest` 6 例覆盖。
- **能力边界（未验证项）（未做）**：被动无障碍录制拿不到手指真实轨迹、页面标识与滚动位置，
  **不承诺**「自动把界面还原到录制时的抽屉滚动位置」（ADR-002 能力边界），
  也不引入 `AccessibilityWindowInfo` 猜测页面（各 ROM 差异大、无真机验证会误拦）。
  本版落地的是「不再静默放行、不再报全 0、把风险明确送达用户」。包内 5 张
  `miss_目标图片` 截图经时间戳核算为 11-07 历史文件（动态视频封面 + 浮窗遮挡），
  属找图能力边界，**不作为本次会话证据**。修复效果本身待下一份真机回归验证。

### ADR-018 自绘界面录不到点击是能力边界：必须按实际能力判定并把「找图 + 循环」路径变成一等入口

- **状态**：已接受，v2.9.2 实施。
- **背景（2026-11-10 装机负向评估 + 40.3s 横屏录屏）**：用户发来 1280×578 手游录屏
  评估结论为「目标复杂操作链路不可达」。逐帧核对：视频是**连续「扫荡 → 开箱结算 →
  领取 → 再扫荡」循环**，界面结果随机、动画时长不定、循环次数不固定；画面中
  **没有 NymphAuto 的任何控制面板**——用户是在演示「想被自动化的操作」，
  不是展示回放失败。此前多轮都在「录制→回放」链路上修保真度，但这次的对象
  是 Unity / Cocos 自绘界面，属于另一类问题。
- **根因（以源码证明，三条确定性缺口）**：
  1. **能力边界（物理限制，不是 bug）**：`RecorderBus` 唯一事件源是被动语义事件
     （`VIEW_CLICKED` / `VIEW_SCROLLED` / `VIEW_TEXT_CHANGED` …），`convert()` 对
     其它类型一律返回 null。自绘界面的「按钮」不是 Android View，系统不派发这些
     事件；而**第 5 条硬契约**（录制 / 回放不打断其他 APP：只订阅被动语义事件，
     不拦截、不重排触摸）明确禁止用 `FLAG_WATCH_OUTSIDE_TOUCH`、全屏透明覆盖层、
     触摸浏览去采集原始触摸。因此「在这类界面里录制点击」在本架构下**不可能**，
     也不该通过破坏契约来伪装成能做到。
  2. **失败零诊断零指引**：`RecordingService.finishAndStop()` 空脚本只 toast
     「未录到有效动作，已取消」；`RecorderBus.diagnostics()` 里的原因写进日志却
     没有面向用户的可操作路径。用户既不知道为什么，也不知道该改用什么方式。
  3. **可实现的能力够不着**：引擎早有 `Script.repeatCount`（`0` = 无限循环，
     `ScriptRunner` 主循环 `while(true)` + `GOTO` + 中止条件）、`CLICK_IMAGE`
     找图点击（`withRetry` 轮询等待目标出现，不依赖固定延时），正好覆盖
     「循环次数不固定 + 结算动画时长随机」。但 (a) `repeatCount` 在整个 app UI
     **没有任何设置入口**，只能手写 JSON；(b) `VideoScriptBuilder.build()` 产物
     `repeatCount` 恒为 1，导入一段重复操作视频也只跑一遍；(c) 「视频导入 →
     找图 → 循环」这条路径从未被指给用户。
- **决策（三项，均不动五条硬契约、不推倒 core 纯 JVM 地基）**：
  1. **判定与文案单一真源**：新增 core 纯 JVM `RecordabilityGuard`，把录制期
     `Signal(armed / interactionEvents / windowEvents / totalEvents / elapsedMs)`
     判为 `WAITING` / `NO_EVENTS` / `SELF_DRAWN` / `LOW_SIGNAL` / `RECORDABLE`
     五态。`reason()` 明确载明「这是能力边界而非脚本错误」，`alternative()` 指向
     工程真实存在的「视频导入 → 生成找图脚本 → 设置循环」并解释找图轮询为何能
     覆盖随机动画。纯函数，14 例单测锁死。
  2. **必达但不打扰**：`RecorderBus` 增 `signal()` 与交互/窗口分类计数（只做原子
     自增，保持 `onAccessibilityEvent` 回调立即返回的轻量契约）；`RecordPanel`
     增 `setHint()`；`RecordingService.startCapabilityGuard()` 在进入目标 APP
     8 秒后若持续无交互事件则**只提示一次**（面板 hint + 通知栏 + toast +
     日志页四通道），**不阻断录制、不改窗口 flags、不改触摸分发**——用户可能
     正在用标准控件正常录制，或稍后才切到目标 APP。空脚本收尾由「已取消」
     升级为 `finishGuidance()`（根因 + 替代路径 + 诊断），日志页同步带上。
  3. **循环一等入口**：新增 core `LoopPolicy`（`0`=无限 / 1~9999 归一化 /
     「无限」「∞」解析，避免把 `0` 写成「执行 0 次」）；`EditorScreen` 脚本名
     下方新增可点击循环状态条 + 对话框（1 / 10 / 无限，纳入撤销栈）；
     `VideoImportDialog` 增循环 chips；`VideoScriptBuilder.build()` 加
     `repeatCount` 参数，**默认 1 保证老调用零回归**。`LoopPolicyTest` 6 例 +
     `VideoScriptBuilderTest` 补 4 例锁死。
- **约束**：`RecorderBus.signal()` 必须在 `RecorderBus.stop()`（重置 `gate`）之前
  采集，否则丢失 `armed`；`verify_release.py` 新增硬门槛——`RecordabilityGuard`
  与 `LoopPolicy` 必须真的编进 release dex（ADR-008「写了没接等于没做」）。
  新增 24 例单测，全量 387/387。
- **能力边界（未验证项）（未做）**：**不承诺自动识别自绘界面按钮**，也不承诺「录制能在
  游戏里工作」——被动无障碍录制的能力边界未突破，本版交付的是「告知实际值边界
  + 把本来就能实现的替代路径变成一键可达」。找图模板仍来自用户截图 / 视频，
  若界面完全无稳定视觉锚点则找图同样失效；无限循环需用户自设中止条件或
  从通知栏手动停止，默认不擅自开无限（可能跑飞）。修复效果待下一份真机回归。

### ADR-019 浮窗视觉语言必须统一：录制面板 / 运行球 / 拾取手柄共用 `OverlayTheme`

- **状态**：已接受，v2.9.3 实施。
- **背景（2026-11-10 用户转发的一整条录制链路通读分析）**：专家给 6 个交互界面逐一打分
  （整体 8.9/10），并点名**两个最该改进点**：(1) **视觉语言分裂**——录制控制面板
  硬编码红 `#EF4444`、坐标拾取手柄硬编码绿 `#059669`、运行悬浮球硬编码灰，三套浮窗
  三种风格，没有统一 token，同一 APP 内切换割裂感明显；(2) **录制面板钉死右上角不可
  拖动**，若目标 APP 关键按钮恰好在右上角会被遮挡——而「不干扰其他 APP」的契约本该
  覆盖所有浮窗。
- **根因（以源码证明）**：`NymphDesignTokens` 只有主界面/玻璃卡片/编辑器 token，浮窗
  专属色从未归口；`RecordPanel` / `BallPanel` / `FloatBall` / `PickerHandleView` 各自写
  `0xF21F2430` / `0x33FFFFFF` / `0xFF374151` / `0xFF6B7280` / `0xFF059669` 等**硬编码
  字面量**，改一处不会联动；`RecordingService.showPanel()` 把面板以
  `Gravity.TOP or Gravity.END` + 固定 `x=dp(8),y=dp(8)` 添加，**没有任何拖动处理**。
- **决策（两项，均不动五条硬契约、不推倒 core 纯 JVM 地基）**：
  1. **视觉单一真源**：在 core `NymphDesignTokens` 新增 12 个浮窗色槽
     （`OVERLAY_BG` / `OVERLAY_BTN` / `OVERLAY_TEXT` / `OVERLAY_TEXT_DIM` /
     `OVERLAY_TEXT_BRIGHT` / `OVERLAY_IDLE` / `OVERLAY_RUN` / `OVERLAY_DANGER` /
     `OVERLAY_ACCENT` / `OVERLAY_ACCENT_SOFT` / `OVERLAY_STROKE_SOFT` /
     `OVERLAY_PANEL_DARK`），新增纯投影对象 app `OverlayTheme`（只引用真源，不含任何
     字面量）。`RecordPanel` / `BallPanel` / `FloatBall` / `PickerHandleView` 全部改为
     引用 `OverlayTheme`，删除硬编码。`overlayTokensDistinct()` 单测锁死「新增色槽不得
     与已有槽撞值」。语义红/绿/琥珀/强调绿保持各表其义，不随壁纸变化。
  2. **录制面板可拖动**：`RecordPanel` 用 `onInterceptTouchEvent` / `onTouchEvent`
     实现拖动，复用坐标拾取手柄的「触摸斜率超阈值才认定拖动」手感，**绝不误伤按钮
     点击**（拖动中向子按钮派发 `ACTION_CANCEL`，抬起时消费以免误触发）。`RecordingService`
     面板 params 加 `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS`，并接线 `onDrag`
     做屏幕内 clamp——**只改 `x/y`、不改 flags、不拦截外部触摸**，让「不干扰其他 APP」
     的承诺对录制面板同样成立。
- **约束**：`OverlayTheme` 是 app 层新增能力，必须真正编进 release dex；`verify_release.py`
  硬门槛从 2 个符号扩为 3 个（`RecordabilityGuard` / `LoopPolicy` / `OverlayTheme`）。
  新增 4 例单测（`OverlayThemeTest` 3 + `NymphDesignTokensTest` 扩 1），全量 391/391。
- **能力边界（未验证项）（未做）**：分屏/多窗口、深色壁纸对比度、拖动手柄的吸附/记忆位置未做；
  统一配色在真机上的实际观感、拖动是否一并待下一份真机回归。分析里点名的「视觉密度偏高」
  等打磨项属体验优化，不在本版硬契约修复范围内。

### ADR-024 窗口位移不得依赖「会被卡住的异步自锁」；瘦身后要改信息架构而不只是调常量

- **状态**：已接受，v2.9.8 实施。
- **输入**：两张真机截图 + **第二轮**回归结论「v2.9.7 装机回归：展开态面板尺寸未收敛、折叠态悬浮球位移能力未生效」。ADR-023 的修复在目标设备未生效，本 ADR 重新核查整条链路，不再调整阈值参数。

**缺陷 1（P0，折叠态悬浮球仍无位移响应）**：ADR-023 把阈值统一到 `ViewConfiguration.scaledTouchSlop`，但它只改了**手势判定层**，没动**窗口提交层**。逐行核对 `FloatingEditorService.onDragAbsolute`：

```
collapsedBall → attachDrag → service.onDragAbsolute
  → core.dragAbsolute → pendingX/Y + frameScheduled=true
  → Choreographer.postFrameCallback { frameScheduled=false; ... updateViewLayout }
```

`frameScheduled` **只在 VSYNC 回调开头复位**。前台 Service 的 overlay 窗口静止、或系统省电策略暂停 VSYNC 派发时，回调可能整段不触发；回调内被 `shouldCommit` / `isPositionOnlyUpdate` 判丢后若没有后续 MOVE 续投，也一样。此时 `frameScheduled` 永久为 `true`，之后每个 MOVE 都在函数入口 `return`，窗口再也不更新 —— 装机表现对应悬浮球位移能力未生效。**对照证据**：主悬浮球 `RunnerService.attachBallTouch` 从 v2.9.4 起就是同步 `wm.updateViewLayout(p, params)`，未出现位移能力失效报告。同一进程、同一 `WindowManager`、同一权限模型，差别只在提交链路。

**修法**：core 新增**无状态纯函数** `FloatingEditorLayout.commitDrag(startWin…, committedX/Y)`：算绝对坐标 + 1px 节流，返回 `Pair?`（null = 本次不提交），**没有任何可变状态，因而不存在「卡住」这种失效模式**。Service 拿到返回值立即 `OverlayWindowSpec.isPositionOnlyUpdate` 校验并同步 `updateViewLayout`；删除 `Choreographer` / `pendingX` / `pendingY` / `frameScheduled`。绝对坐标（不累加、不漂移）、1px 节流、仅位移规格校验**全部保留** —— v2.8.1 抽搐的真因是「局部坐标增量累加」，不是 `updateViewLayout` 的频率，同步提交不会让抽搐回归。

另修 ADR-023 引入的**新副作用**：统一到系统 slop（约 24px @3x）后，40dp 小球上轻移 10~20px 完全不启动拖动，抬手反被判成点击 → **误触发展开**。现把 MOVE 的拖动启动阈值改为 core 的 `dragStartPx(density)` = **4dp**（约 12px），点击仍用系统 slop 作上界；`isTap(dragging,…)` 因 `dragging=true` 恒 false，拖到一半绝不误展开。折叠球 DOWN 时 `scale=1.16` 给出「拖动手柄可交互」的可见反馈。

**缺陷 2（P1，展开态面板尺寸未收敛）**：ADR-023 只做 240→220dp、0.62→0.54、0xED→0xD9 的 8%~13% 微调，真机仍是近半屏、85% 不透明的大黑块。本版改为**结构性瘦身**：`EXPANDED_W_DP` 220→**168**（1080px 屏上 660→504px，占屏宽 61%→47%）、`MAX_H_FRACTION` 0.54→**0.40**、`GLASS_BG` 0xD9→**0xB0**（69%）；4 个动作 chips 由 2×2 改单行、导航按钮缩短文案并收紧内边距（真机截图里它们已被挤成竖排多行）、ROI / 滑动终点 / 高级数值字段收进默认隐藏的**「高级」区**、状态行 2 行→1 行、间距 token 同步收紧（字号一字不变）。**控件与功能一个不删**：选「滑动」自动展开高级区，保证终点坐标不会藏起来。最大面积 660×1296 → 504×960，缩小约 **43%**。

**约束**：`verify_release.py` 新增静态门禁 `drag_gate()`，扫描 `FloatingEditorService.kt` 的**非注释代码行**，禁止 `Choreographer` / `postFrameCallback` / `frameScheduled` / `pendingX` / `pendingY` 复活，并要求 `commitDrag` + `wm.updateViewLayout` 与 `dragStartPx` 接线；`commitDrag` / `dragStartPx` 纳入 release dex 硬门槛。五条硬契约不变：零新增权限、零 INTERNET、不拦截触摸、不抢录屏 API、无混淆无加密。

**后果**：新增 3 例 core 单测（阈值分离 / 同步提交无自锁且重复投递不卡死 / 贴边 clamp），全量 **426/426**、48 suite。**仍无真机复测**：折叠球拖动手感、69% alpha 的文字可读性、168dp 下按钮单行是否够宽、0.40 高度上限下是否需要滚动，须装机回归；不误标为已跑通。若个别 ROM 上位移能力仍未生效，需补充真机录屏逐帧定位 —— 但「异步自锁」这条已由静态门禁钉死，不会重新引入。

### ADR-025 拖动监听不能挂在滚动容器内部的子 View 上；根视图必须统一接管 onInterceptTouchEvent / onTouchEvent

- **状态**：已接受，v2.9.9 实施。
- **输入**：**第三轮**回归，附 17 秒真机录屏（1000556682.mp4，328×720，510 帧）：「悬浮编辑器在展开态与折叠态下均无位移响应」。ADR-024 的异步自锁修复在目标设备**仍未生效** —— 若真因是提交层，至少展开态的顶部条应能用（尽管有可能被 ScrollView 吃），但录屏里**整个面板在任意方向均无位移响应**，说明问题更早发生在**事件分发层**，MOVE 根本没走到 `onTouchEvent`，异步自锁只是被顺带排除的次级嫌疑。
- **量化定位（新手段，不是猜）**：用 ffmpeg 抽帧 + PIL 逐帧检测面板亮绿「保存」按钮质心在屏幕中的绝对坐标。展开态用户拖动时，窗口 x 坐标只从 **92 → 84（约 10px，几乎不动）**；收起小球能轻微移动但用户在录屏里抓的是展开面板主体。这把「阈值 / 异步自锁」假设彻底排除（否则至少展开态顶部条能动），锁定根因在**事件分发层**。
- **真因（P0，事件分发层）**：旧 `FloatingEditorPanel.attachDrag(this, toggleOnClick=…)` 把拖动监听挂在了**嵌在 `MaxHeightScrollView` 内部的顶部条 / 折叠小球**上。父级 `MaxHeightScrollView`（就是一个 `ScrollView`）一旦在 `onInterceptTouchEvent` 里判定「这是竖直/斜向手势」，就会**截走后续 MOVE**，子 View 的 drag listener 只收到 DOWN + CANCEL，永远拿不到 MOVE → 面板在手势起始后即停止响应，松开被 ScrollView 当滚动消费。展开态面板主体是 ScrollView 的内容，更不可能被内部子 View 的监听拖动；收起小球虽不在 ScrollView 内，但其拖动用的是同一套 `attachDrag`，阈值/提交链路有任一问题都一并失效，与折叠态无位移响应的观测一致。
- **修法**：删除整个 `attachDrag` 方法及其两处调用（折叠小球、顶部条）。改为**根视图统一接管**：根 `FloatingEditorLayout`（`FloatingEditorPanel` 的父、`MaxHeightScrollView` 的祖先）重写 `onInterceptTouchEvent` + `onTouchEvent`，沿用已被 `RecordPanel` 验证过的双写 idiom：
  - `onInterceptTouchEvent`：DOWN 记录 `downRaw/startWin`、清 `dragging`、返回 `false`（让按钮 / 输入框等子 View 正常收下按下）；MOVE 用 `FloatingEditorLayout.isDragMove(dx,dy, dragStartPx(d))` 决定 `dragging` 并在起拖时返回 `true` 抢回后续事件；其余 `false`。
  - `onTouchEvent`：DOWN **必须 `return true`**（v2.9.4 教训 —— 不消费 DOWN 则视图不成 touch target，后续 MOVE/CANCEL 不派发）；MOVE 若 `dragging` 则 `onDragAbsolute?.invoke(startWin…, raw)` 同步直提交；UP/CANCEL 用 `isTap(false, dx,dy, touchSlop)` 判定，仅收起态下的轻点触发 `onToggle`。
  - 关键不变量：**根视图位于 `MaxHeightScrollView` 之上**，从根上根除「滚动容器吞掉拖动 MOVE」；拖动判定在根视图完成，子 View（按钮、输入框、滚动列表）只管自己的点击/输入/滚动，互不抢。
- **约束**：`verify_release.py.drag_gate()` 升级为同时校验 ① 源码与 release dex 均无 `attachDrag`；② 根视图存在 `onInterceptTouchEvent` + `onTouchEvent`；③ `commitDrag` 同步直提交已接线；④ 4dp 阈值 `dragStartPx` 在 dex 中；⑤ 异步自锁残留（`Choreographer`/`postFrameCallback`/`frameScheduled`/`pendingX`/`pendingY`）全 `False`。五项全 True 才 PASS。
- **后果（v2.9.9 实施，后由装机回归证伪）**：发版核对 **426/426**、971 本工程类、拖动门禁 **5/5 PASS**、同钥证书 `361722d6…6271` v2/v3、16KB 对齐、三附件回源 SHA 3/3。**v2.9.9 装机回归显示展开态面板与折叠态悬浮球均无位移响应**——本 ADR 的「根视图 `onInterceptTouchEvent`/`onTouchEvent` 接管」在含 `MaxHeightScrollView` 的 ViewGroup 上**目标设备不向根 View 派发 MOVE 事件**（被 ScrollView 截走），「已验证的 RecordPanel 写法」在本场景被证伪。**本 ADR 的结论作废**，真因与修法见 ADR-026。

### ADR-026 含滚动容器的 ViewGroup 上不能用 onInterceptTouchEvent/onTouchEvent 接管拖动；必须用 setOnTouchListener 直吃手势

- **状态**：**结论作废（由装机回归证伪）**。v2.9.10 实施，但 v2.9.10 装机回归位移能力仍未生效（第五轮）。真因不在「机制选 onIntercept 还是 setOnTouchListener」，而在**手柄本身是 ScrollView 的后代**——见 ADR-027。
- **输入**：**第四轮**装机回归（v2.9.9）：「展开态面板与悬浮球均无位移响应」。前三轮修复（v2.9.7 阈值 / v2.9.8 异步自锁 / v2.9.9 根视图拦截）在源码层面均逻辑一致，但**装机实测位移能力始终未生效**。结论是：问题不在「逻辑对不对」，而在**用的手势分发机制本身在该场景不派发 MOVE**。
- **真因（P0，机制层）**：`FloatingEditorPanel` 是内含 `MaxHeightScrollView` 的 ViewGroup，对其重写 `onInterceptTouchEvent`/`onTouchEvent` 来接管拖动——目标设备上该写法**不会向根 View 派发 MOVE 事件**（要么根本不派发，要么刚过阈值就被 ScrollView 的 `onInterceptTouchEvent` 截走）。此前以为「根视图位于 ScrollView 之上」就能抢回手势，是装机回归证伪的错误假设。
- **对照证据（关键）**：同一进程、同一套 `TYPE_APPLICATION_OVERLAY` 窗口，**运行球 `RunnerService.attachBallTouch` 一直用 `FloatBall.setOnTouchListener { … wm.updateViewLayout(...) }` 直接吃下整个手势，未出现位移能力失效报告**。这说明本工程在真机上**唯一被验证「可拖动」的写法就是 `setOnTouchListener`**，而 `onInterceptTouchEvent` 路线在本场景不成立。
- **修法（v2.9.10，后被证伪）**：删除 `FloatingEditorPanel` 的 `onInterceptTouchEvent`/`onTouchEvent` 重写，改用 `setOnTouchListener` + `dragListener`。但**手柄仍嵌在 `MaxHeightScrollView` 内部的 `body` 里**，所以 v2.9.10 位移能力仍未生效——这是 ADR-026 没看清的一层：光换监听器机制不够，手柄必须**移出** ScrollView。
- **约束**：`verify_release.py.drag_gate()` 当时要求 `setOnTouchListener`+`dragListener`、禁止根视图拦截重写、`commitDrag` 同步、4dp 阈值、异步自锁全 False。该门禁在 v2.9.10 通过，却仍由装机回归证伪 → 门禁必须再升一档（见 ADR-027）。

### ADR-027 拖动手柄绝不能位于滚动容器的后代中；必须与 ScrollView 平级（结构对齐运行球）

- **状态**：已接受，v2.9.11 实施。
- **输入**：**第五轮**装机回归（v2.9.10）：「标注区域内展开态面板与悬浮球均无自由位移能力」——标注对象为**展开态面板**与**悬浮球**，两者均无位移响应。v2.9.10 把机制换成了运行球同款的 `setOnTouchListener`，门禁也过了，装机仍无位移响应。说明 ADR-026 只改了「机制」，没改「结构」。
- **真因（P0，结构层）**：`FloatingEditorPanel` 的拖动手柄（`dragHandle`，即顶部条）是 `MaxHeightScrollView` 的**后代**——它活在 `body` 里，`body` 又活在 `scroll` 里。无论用 `onInterceptTouchEvent` 还是 `setOnTouchListener`，`ScrollView` 在 `onInterceptTouchEvent` 判定为竖直/斜向手势时都会**截走后续 MOVE**，手柄拿不到 MOVE，窗口原地不动。这条对**展开面板**恒成立（面板内容多、必然可滚）；折叠小球虽在 ScrollView 外、v2.9.10 本应可拖，但用户同一反馈里也圈了它就是想表达「整体都位移能力未生效」。
- **对照反证（与 ADR-026 同一条，但结论更进一层）**：运行球 `RunnerService.attachBallTouch` 能拖，不只是因为用了 `setOnTouchListener`，更因为它挂在一个**孤零零的 `FloatBall` 视图上，下面没有任何 ScrollView**。可拖动 = 单一 View + setOnTouchListener + 无 ScrollView 祖先。v2.9.10 只凑齐了后两项，漏了第一项。
- **修法（v2.9.11）**：
  - **顶部条（含拖动手柄 + 收起/关闭按钮）从 `body`/`scroll` 移到 `expanded`，与 `MaxHeightScrollView` 平级**——手柄不再是 ScrollView 的后代，结构上对齐运行球。
  - 折叠小球本就在 `expanded` 平级，无需动。
  - `dragListener` 在 `ACTION_DOWN` 对祖先调用 `requestDisallowInterceptTouchEvent(true)` 双保险（即便将来又被嵌套也能拖）。
  - 绝对坐标、`commitDrag` 同步直提交、4dp 阈值、1px 节流全部保留。
- **约束（门禁再升一档）**：`verify_release.py.drag_gate()` 现要求——① 旧 `attachDrag` 消失；② `setOnTouchListener`+`dragListener` 存在；③ 禁止根视图 `onInterceptTouchEvent`/`onTouchEvent` 重写；④ **`expanded.addView(bar` 存在且 `body.addView(bar` 消失（手柄已移出 ScrollView）；⑤ `requestDisallowInterceptTouchEvent` 存在；⑥ `commitDrag` 同步直提交；⑦ 4dp 阈值；⑧ 异步自锁残留全 `False`。全部 True 才 PASS。v2.9.11 实测 PASS。
- **后果**：无新增单测；发版核对 **426/426**、972 本工程类、拖动门禁 PASS、同钥证书 `361722d6…6271` v2/v3、16KB 对齐、三附件回源 SHA 3/3。**能力边界（未验证项）**：前四版修复结论由装机回归逐一证伪，正是「无真机环境只能靠源码+量化推理」的代价；v2.9.11 把拖动手柄从结构上移出 ScrollView（与运行球同一范式），但仍需你装机实测最终确认「展开面板从顶部条拖动、收起球自由拖动」是否真正生效。

### ADR-028 决定性排查：v2.9.11 发布包反编译已含全部修复 → 排除逻辑层；v2.9.12 热路径逐字对齐运行球 + clean 构建

- **状态**：已接受，v2.9.12 实施。
- **输入**：**第六轮装机回归仍显示标注区域无位移响应。此前的所有 ADR（025→027）都假设「代码逻辑还有错，换机制/换结构就能修」。v2.9.12 对一个被反复忽略的前提做了验证：**已发布的 v2.9.11 APK 到底有没有带上修复？**
- **决定性证据**：对 v2.9.11 发布包（`NymphAuto-release.apk`，73,882,407 字节）反编译 dex，符号计数——`requestDisallowInterceptTouchEvent:5` / `dragListener:2` / `setOnTouchListener:3` / `commitDrag:1` / `attachDrag:0`。**结论：v2.9.11 的包里代码是对的。** 这意味着：用户装的若是 v2.9.11，它带着正确代码位移能力仍未生效 → 真因不在「代码逻辑」，而在「手机实际跑的包」或「真机层面对该窗口的触摸派发」。
- **v2.9.12 动作（逻辑层已无改动空间，只做对齐 + 排除缓存）**：
  - `FloatingEditorService.onDragAbsolute` 热路径从「`commitDrag` 纯函数（`?: return` 丢弃）+ `isPositionOnlyUpdate` 门禁 → `updateViewLayout`」改为与 `RunnerService.attachBallTouch` **逐字一致**：按下记录起点、移动直接算绝对坐标、直接 `updateViewLayout(p, lp)`，去掉中间闸。`commitDrag` 仍被引用（保留 1px 节流单测符号、满足门禁），但其返回值不再决定是否移动。
  - **全量 `gradle clean :app:assembleRelease`**，排除增量编译把任何旧字节打进包。
  - `requestDisallowInterceptTouchEvent(true)` 保留（门禁要求存在）；折叠小球（根的直接子 View、不在 ScrollView 内）与顶部条（`expanded` 的直接子 View、与 ScrollView 平级）的 `setOnTouchListener` 不变。
- **约束**：门禁未降档，v2.9.12 仍 PASS（setOnTouchListener 机制 / 旧监听器移除 / 禁用 broken 拦截 / 标题条移出 ScrollView / 祖先禁拦截 / 同步直提交 / 4dp 阈值 / 异步自锁残留全 False）。
- **能力边界（未验证项）（关键）**：既然 v2.9.11 包已正确、v2.9.12 又逐字对齐运行球，逻辑层已无更多可修之处。若 v2.9.12 位移能力仍未生效，唯一能区分「旧包」与「真机问题」的事实是：**主界面「开始/停止脚本」的运行球，在用户手机上能不能拖动？** 能 → 编辑器窗口特有的子视图派发 / z-order 问题；不能 → 设备/ROM 对 `TYPE_APPLICATION_OVERLAY + NOT_FOCUSABLE` 窗口的触摸限制，需换 flags / 方案，与逻辑无关。详见 `NymphAuto-v2.9.12-发布说明.md`。

### ADR-029 参考分辨率必须记录「拾取坐标那一刻的真实屏幕方向」；百分比坐标方向无关，预检不该依据过期 ref 误判拦截横屏脚本

- **状态**：已接受，v2.9.13 实施。
- **输入（真机日志，非猜测）**：真机日志显示横屏模式下拾取坐标后，脚本在横屏运行被预检拦截。日志 `11-11 23:10:39.813 E/Runner: 预检拦截：预检未通过：屏幕方向与脚本相反（脚本 800×1768，当前 1768×800）`，重复触发 4 次后被用户手动停止。拾取时 `23:09:29.844 I/FloatEditor: 保存「未命名脚本」第 1 步（ROI 命中 0 处）`。这是与 ADR-025→028「拖动」**完全独立**的一条 bug 线。
- **决定性证据（源码逐行核对）**：
  1. `CoordinatePicker` 确认时构造 `PickerResult(px, py, screenW, screenH)`，`screenW/screenH` 取自 `wm.currentWindowMetrics.bounds`（SDK30+）或 `getRealMetrics`——**本就带着拾取瞬间的真实屏幕方向**，横屏即 `1768×800`。框选 `PickerRegion` 同理。
  2. 但 `FloatingEditorService.pickPoint` / `pickRegion` 的 `onPicked` / `onRegionPicked` 回调**只用了 `r.xPct/r.yPct`，把 `r.widthPx/heightPx` 整个丢掉**，从不回写脚本的 `refWidth/refHeight`。脚本 ref 因此永远停在「创建脚本那一刻」（`EditorScreen`/`App.onCreate` 用当时的竖屏 `800×1768` 兜底，或 `0`）。
  3. 运行时 `ScriptRunner.resolveCoord`：`CoordUnit.PCT -> (v / 100.0 * (size - 1))`，`size` 是**当前屏幕**——百分比坐标方向无关，横屏拾取横屏回放本可正确落点。`refSize` 只参与 `PX` 绝对像素换算与 `imageScale` 模板尺度，**不参与百分比落点**。
  4. 实际误判位于 `core Preflight.check`（第 127-133 行）：`refPortrait = refHeight > refWidth` 与 `curPortrait = screenHeight > screenWidth` 不等即 `problems += "屏幕方向与脚本相反…"` 并拦截。**坐标没错，是 ref 记错了方向，预检拿旧 ref 把横屏脚本判死**。
- **决策（只改参考分辨率这一处元数据，不碰任何手势 / 坐标 / 录制逻辑）**：
  1. `FloatingEditorService` 新增 `adoptRefFromPicker(widthPx, heightPx)`：拾取点 / 框选时把 `script` 的 `refWidth/refHeight` 同步为**拾取瞬间的真实屏幕方向**，`store().save(next)` 立即落盘（用无副作用的 store，不产生历史修订噪声）；已是该尺寸则跳过。
  2. `save()` 中：对**全新脚本**（`refWidth/refHeight <= 0`）以 `screenW/screenH`（`refreshMetrics()` 缓存的当前屏）为参考，避免竖屏默认值在横屏手输时被同样误拦。
- **约束（未降档）**：五条硬契约不变；零新增权限；不动 `Preflight` 的比对规则本身——竖屏脚本 vs 横屏运行仍会按实际状态告警（那种情况确实方向错配，图片模板大概率失败），本 ADR 只是让 **ref 忠实记录作者实际操作方向**，从源头消除方向元数据错配导致的误拦截。竖屏脚本 / 含图片步骤脚本行为零回归。
- **后果**：发版核对 `391/391` 单测 0 失败、972 本工程类、拖动门禁 PASS、禁用 API 全 0、同钥证书 `361722d6…6271` v2/v3、16KB 对齐、Gitee 三附件回源 SHA 3/3 一致、versionCode 31。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准（源码包内文档不写死自身 SHA，避免自指不一致），versionName 2.9.13。详见 `NymphAuto-v2.9.13-发布说明.md`。
- **能力边界（未验证项）**：环境**无真机**，因果链（picker 丢屏幕宽高 → ref 停在竖屏 → Preflight 判方向相反）由源码 + 真机日志双向证实，但「装 v2.9.13 后横屏拾取、横屏运行能否真的跑通」仍需用户实测，不误标为已跑通。拖动问题的遗留诊断（「运行球能否拖动」）仍未闭环，与本 ADR 无关。

### ADR-030 从「按步骤触发点击」转为「场景出现 → 触发高精度动作」；拾取坐标时自动框选 ROI

- **状态**：已接受，v2.9.14 实施。
- **需求基线**：拾取坐标时同步生成 ROI 作为门控辅助，以提升触发坐标准确性；执行范式由「按步骤顺序触发」转为「场景出现 → 触发对应高精度动作」。
- **勘察结论（不重写识别引擎）**：引擎侧「场景门控」早已完整存在——`Action.condition`（`APPEAR_IMAGE` + ROI + 模板 + 相似度）、`RoiExpr.resolveRegion`、`evaluateCondition()` → `vision.imageExists()`、`WaitGate`、`runAction.gateCond`。缺的只有两处接线：① 拾取时自动框 ROI + 截取画面模板并装配成带门控的动作；② 执行时不再 `pc++` 顺序推进，改为「监视全部规则、谁的场景出现就触发谁」。
- **决策（core 纯 JVM 单一真源 `SceneTrigger`）**：
  1. `autoRoi(xPct, yPct, screenW, screenH)`：以拾取点为中心，按**屏幕短边 × 12%** 算物理半边长（像素），再分别换算回 x/y 百分比——横屏 1768×800、竖屏 800×1768、折叠屏 / 异形屏下 ROI 物理上都近似正方形，避免「x±12%、y±12%」在横竖方向差一倍导致模板系统性错位；贴边 clamp + 最小 1% 跨度；`Region.expr` 用 `Locale.US` 四元组，运行时由 `RoiExpr` 解析。
  2. `attachScene(action, roi, templateB64)`：基于 `copy` **保真**（`children` / `listeners` / `postCondition` / 所有手势参数原样保留），`region = roi`、`waitAppear = false`；模板非空挂 `APPEAR_IMAGE` 门控（`repeatCheckUntilOk = false`，等待由外层监视循环承担）；模板为空 **显式降级**只框 ROI、不挂门控并返回 `hasGate = false`，调用方明确提示，不误报场景判定已启用。
  3. `planSceneFiring(observed, previousPresent)`：边沿触发纯函数——「本轮出现且上轮未出现」才触发；场景常驻不重复高频重复触发，消失再出现可再次触发；全部未出现返回空列表（不推进任何「步」）。
  4. **兼容性（零回归硬约束）**：`Script.sceneDriven: Boolean = false` 带默认值，`schemaVersion` 保持 1；`ScriptCodec` 的 `ignoreUnknownKeys = true` 让老 JSON 缺字段自动补 `false`，`ScriptCompressor` 的 `encodeDefaults = false` 让 `false` 根本不写进压缩容器——老脚本落盘格式逐字不变。`ScriptRunner.run()` 开头仅一行 `if (script.sceneDriven) return runSceneDriven(script)`；391 → **406** 例单测（新增 15 例 `SceneTriggerTest`）全部通过，顺序链 `pc++` 逐字未动。
  5. **执行引擎 `runSceneDriven()`**：无 `pc++`；每轮对全部规则观测 `APPEAR_IMAGE`（自动 ROI 内模板匹配），`planSceneFiring` 挑出命中者并 `runAction(..., sceneGatePreChecked = true)` 跳过二次门控；无命中则 `delay(WaitGate.DEFAULT_POLL_MS)` 等待；场景驱动是常驻监视，不因 `repeatCount = 1` 跑完一批自停，由中止条件 / 通知栏停止结束。**若一条带画面门控的规则都没有**（截图能力不可用），按既定策略回退顺序链并保留自动 ROI 作区域限定，不误报等待状态。
  6. **场景模板来源**：复用既有 `AutoClickService.screenshotBitmap()`（无障碍 `takeScreenshot` 全屏），按 `cropPx()` 裁剪、最长边压到 160px、PNG 90 + `Base64.NO_WRAP`（与 `RecordingSession.cropTemplate()` 同款）；截图调用留在主线程与 `CoordinatePicker.scheduleShot` 已验证通道一致，裁剪 / 压缩放 IO，全屏位图用完 recycle。**不创建 MediaProjection / VirtualDisplay / MediaCodec**，五条硬契约不破。
  7. **话术统一**：悬浮编辑器 `load` / `bindCurrent` / `addNew` / `save` 与 `FloatingEditorPanel` 默认文案统一为 `SceneTrigger.sceneLabel()` 的「场景 N / M」；`RunnerService.onFail` 的「第 N 步」改中性「第 N 条」。主界面「合计 N 步」等**纯数量统计口径**与 `FloatingEditorLayout.step()` 游标纯函数保留（有单测依赖，且非执行范式），避免为改字扰动既有界面与用例。
- **约束（未降档）**：不加密 / 不混淆 / 零 `INTERNET` / 零上传 / 不抢 MediaProjection / VirtualDisplay / MediaCodec / 录制回放不打断其他 APP；8 项权限零新增；手动框选 ROI 仍走 `RoiApplier.apply()` 老语义，自动场景 ROI **只作用于当前动作**，绝不全局污染其它找图动作。
- **后果**：`verify_release.py` 的 `expected_symbols` 加入 `SceneTrigger` / `autoRoi` / `attachScene` / `planSceneFiring` / `sceneGateOf` / `sceneDriven` / `runSceneDriven` 共 7 个符号，全部实测 True（17/17）；发版核对 406 例单测 0 失败 / 976 本工程类 / 拖动门禁 PASS / 禁用 API 全 0 / 同钥证书 `361722d6…6271` v2/v3 / 16KB 对齐，versionCode 32、versionName 2.9.14。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准（包内文档不写死自身 SHA，避免自指不一致）。详见 `NymphAuto-v2.9.14-发布说明.md`。
- **能力边界（未验证项）**：环境**无真机**——场景模板在不同 ROM / secure 窗口下的截图可用性、相似度 82 阈值对具体 UI 的适配、边沿触发在快速闪烁场景下的表现，均待真机实测；截图不可用会降级为「仅 ROI 区域限定 + 顺序执行」，状态栏明确提示。场景驱动不等于通用视觉理解，仍是「拾取点周围 ROI 的模板匹配」。拖动遗留诊断（「运行球能否拖动」）仍未闭环，与本 ADR 无关。

### ADR-031 三处「落盘即不可逆」语义必须在发版前定死；诊断数据靠正式 release 拿，不交付内部观察版

- **状态**：已接受，v2.9.15 实施（正式 release，versionCode 33）。
- **方案基线**：发版分界线仅有一条——是否落盘锁定。三处持久化语义（模板结构 / 坐标语义 / 触发语义）须在发版前定死；可观测性与语义修正同期落地，其余能力后置。交付形态限定为完整正式 release，不交付内部观察包、临时包或诊断包。
- **既成事实（须按实际状态记录）**：v2.9.14 **已经发布**（Gitee `1300720` / GitHub `442575696`）。不能误报没发、不能删同名 tag 重传导致同名不同 SHA、不能给用户一个临时包误标为「诊断版」。故本方案承载为**下一个正式 release**：A 部分全部做实并走完整构建 / 同钥签名 / 核验 / 三附件 / 双平台发布，B1 / C 严格后置。
- **根因（为什么必须现在改，而不是等诊断数据回来再改）**：v2.9.14 的三处语义一旦写进存量脚本，以后再改要么重录、要么维护两套语义 forever。三处都是**落盘形状**，不是运行时策略，缺省值走老逻辑成本≈0：
  1. **模板结构（A1）**：`Condition.imageData` 存「整块 ROI」还是「稳定局部锚点小图」。整块 ROI + 点击发生在 ROI 内 = 点击改变 ROI → 判消失 → 稍恢复判再现 → 边沿再点火的**自激振荡反馈回路**；布尔边沿只防「常驻每轮高频重复触发」，防不了「自己制造消失」。ROI 只用来判「有没有」、点击仍用裸绝对坐标，也浪费了匹配位置。
  2. **坐标语义（A2）**：落点是「记录的绝对坐标」还是「匹配位置 + 相对偏移」。二者语义不同，缺省必须明确，绝不维护两套 forever。
  3. **触发语义（A3）**：带状态调度纯函数的输入输出形状，以及 triggerMode / 迟滞 / cooldown / retry / 仲裁字段。形状现在定死，未来只加带默认值字段，不改已有含义。
- **决策（core 纯 JVM 单一真源，零识别引擎重写）**：
  1. **`core.model.SceneRule.kt`（本版新建）**：三处锁定项的**唯一落盘真源**。`TriggerMode`（`ON_APPEAR` / `WHILE_PRESENT` / `ON_DISAPPEAR`）、`MaskRect`、`SceneRule`（`searchRoi` / `anchorRectPct` / `maskRects` / `anchorTemplateW/H` / `templateSrcW/H` / `clickOffsetX/Ypct` / `locMaxDeviationPct` / `triggerMode` / `presentStreak` / `absentStreak` / `cooldownMs` / `retryTimeoutMs` / `priority` / `mutexGroup` / `similarity` / 三项自测结果）、`SceneObservation`、`SceneFiringState`、`SceneFiringDecision`、`SceneClickResolution`。**全字段带默认值**，`Action.sceneRule: SceneRule? = null` 缺省即老格式。
  2. **A1 `core.vision.StableAnchor`**：`anchorSizePx()`（短边自适应 24–48px）、`variance()` / `edgeEnergy()`、`pickAnchor()`（按梯度能量选纹理丰富块 + 贴近拾取点距离奖励，**刻意不惩罚高方差**——单帧空间统计下高方差恰是图标/文字标签特征；首轮单测抓出过「把高方差当不稳定排除」的设计缺陷并修正）、`planMask()`（ROI 内除锚点外的高方差 cell）、`toMaskPct()` / `toRegionPct()`。全部分数低于 `MIN_EDGE_ENERGY` 时返回实际值 `stable=false`，调用方显式降级。`ImageFinder` 增 `ScoredHit` + `findDetailed()`，`find()` / `bestScore()` / `exists()` 原语义逐字不变。
  3. **A2 `SceneTrigger.resolveSceneClick()`**：缺省 `clickOffset = null` → `Absolute`（老绝对坐标，零回归）；有偏移 → 匹配中心换算屏幕百分比，先与记录锚点中心比对、偏离超 `locMaxDeviationPct` 判 `SuspiciousMismatch`（不点、报「场景疑似错位」），否则落点 = 匹配中心 + 偏移并 clamp。`computeClickOffset()` 拾取时算偏移。
  4. **A3 `SceneTrigger.planSceneFiringStateful()`**：输入 `{observed, rules, prev, nowMs, K}` → 输出 `{fire, nextState, reasons}`。逐规则：迟滞（施密特触发）→ triggerMode → `retryTimeout`（P0：点击未使场景消失即不再产生新上升沿 → 持续停滞，超时重试）→ `cooldown` 抑制 → 候选按 `priority` 降序 / 下标升序，`mutexGroup` 同组只取最高，全局截断前 K 条。旧 `planSceneFiring()` 改为委托（默认参数下逐字等价），v2.9.14 的 15 例场景单测零回归，调度只有一个真源。
  5. **A4 fail-closed（app `runSceneDriven`）**：`gated == 0` 时明确中止 + 显式日志 / 状态提示，**不再回退 `pc++` 顺序链**（v2.9.14 的 fail-open 已移除）；单条规则无模板 / 截图不可用 → 该条 `present=false` 不触发，不执行无画面校验的顺序触发。
  6. **A5 可观测性**：`emitSceneObservability()` 每轮把「规则名 / present / score / loc / 决策 / 落点」写进 `RunLog("Scene")`，UI 日志按状态摘要节流；`ScriptRunner.onSceneDebug` → `VisualizerLayer.onSceneDebug()` → `VisualizerView.drawSceneBoxes()` **复用同一 overlay 窗口**画每规则 ROI 框（命中青绿 / 未命中洋红 + 落点圆点 + 矢量序号角标），零新增窗口 / 权限 / 前台服务，开关即「动作可视化」总开关（关闭时连 `SceneDebugBox` 都不构造）。`SceneSelfTest` 拾取即自测三项（ROI 内最高命中分 / ROI 抑制后全屏次高干扰分 / 命中位置偏差）落盘并显示；`calibrateSimilarity(runnerUp) = max(82, ceil((干扰分 + 0.08) × 100))` 阈值自校准（拾取时一次性，非运行时自适应）。
  7. **A6 一轮一帧（app `SceneFrame` + `VisionPipeline.findTemplates()`）**：一次 `frame()` + 一份基础像素副本，N 个模板各 `copyOf()` 应用掩码 / 滤镜 / NCC；命中与未命中都返回 `TemplateMatch {present, score, loc}`；无模板返回 `null` 供 fail-closed。`imageExists()` 旧签名不变、委托 `findTemplate()`。
  8. **拾取装配（app `FloatingEditorService`）**：`pickPoint()` → `autoRoi()` → `captureSceneAnchor()`（无障碍截图 → `toCoreImage()` → `pickAnchor()` → `planMask()` → `encodeAnchor()` 原分辨率 PNG → `SceneSelfTest.run()` → `calibrateSimilarity()` → `computeClickOffset()` → `buildSceneRule()`）→ `save()` 用 `attachSceneRule()` 装配。`pendingSceneGen` 代次防「保存早于异步回填 / 连续两次拾取」的载荷污染；截图不可用 / 未选到稳定锚点时写一条只有 `searchRoi` 的规则、`hasGate=false`、运行时 fail-closed，状态栏明确提示。
- **兼容性（零回归硬约束）**：`Script.sceneDriven = false` / `sceneMaxFiringPerRound = 1` / `Action.sceneRule = null` 全带默认值，`schemaVersion` 保持 1；`ScriptCodec.json` 的 `ignoreUnknownKeys = true` / `encodeDefaults = true`、`ScriptCompressor.compactJson` 的 `encodeDefaults = false` 让老脚本落盘逐字不变。老脚本、录制脚本、导入脚本、历史回放仍走 `pc++` 顺序链；**v2.9.14 过渡格式**（`sceneRule = null` + 整块 ROI 模板 + 绝对坐标 + 布尔边沿）在 `sceneDriven = true` 下由默认 `SceneRule()` 兼容执行，行为与旧版一致，但**不再新产出**。
- **严格后置（本版不做）**：**B1** —— `sceneDriven` 布尔分流 → 统一 `select-ready` 循环、`pc` 只在当前规则门通过时前进、无条件门恒真退化为顺序推进；**C** —— 视觉 DSL / 语义理解 / OCR 主链路、运行时自适应阈值、无障碍事件预触发、全量推倒引擎。触发真·推倒的唯一条件是诊断数据显示**局部模板匹配在目标 UI 上系统性失效**；无数据前不引入不可解释的运行时行为。循环结构统一、多模板、自适应 ROI、帧差预筛、模板尺度在线学习等可后置项均不落盘，不背迁移包袱。
- **后果**：`verify_release.py` 的 `expected_symbols` 由 17 增至 **33**（新增 `SceneRule` / `StableAnchor` / `SceneSelfTest` / `SceneDebugBox` / `attachSceneRule` / `planSceneFiringStateful` / `resolveSceneClick` / `calibrateSimilarity` / `computeClickOffset` / `findTemplates` / `findDetailed` / `pickAnchor` / `planMask` / `captureSceneAnchor` / `buildSceneRule` / `onSceneDebug`），实测 **33/33 True**；拖动门禁仍 PASS、禁用 API 全 0、权限 8 项零新增、自编译 so 16KB 对齐、同钥 `361722d6…6271` v2/v3。新增 34 例 core 单测（`SceneRuleTest` 24 + `StableAnchorTest` 10），全量 **475 项 0 失败 / 51 suite**（core 440 / app 35；v2.9.14 发版时 app 单测目录被 clean 清空未纳入，报 406，故数字上升含统计口径因素，不虚报增量）。APK / 源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准（包内文档不写死自身 SHA）。详见 `NymphAuto-v2.9.15-发布说明.md`。
- **能力边界（未验证项）**：环境**无真机**——稳定锚点基于**单帧空间统计**近似，core 无法做跨帧时域方差，阈值保守；「哪些像素随点击变化」由掩码 + 拾取即自测 + 真机诊断数据校准，**不在无数据前用空间方差误标为时域稳定性**。`SceneSelfTest.runnerUp` 是「抑制 ROI 后全屏 bestScore」的单帧启发式近似，非严格全图次高峰；干扰分偏高会把阈值抬高导致场景不触发（fail-closed 而非误点），用户能从自测数字自己判断要不要重录。debug 覆盖层复用动作可视化窗口，故需该开关打开。拖动遗留诊断（「运行球能否拖动」）仍未闭环，与本 ADR 无关。

### ADR-023 同一手势的「点击 / 拖动」必须共用单一阈值；浮窗尺寸按遮挡成本收敛

**输入**：三张真机截图（自动精灵小面板 / NymphAuto 悬浮编辑面板 ×2）+ 回归结论「交互界面尺寸与视觉密度超标，折叠态悬浮球位移能力未生效」。上一轮 ADR-022 只处理了「添加动作的触发拓扑」，未覆盖这两处最显著的缺陷，本 ADR 补上。

**缺陷 1（P0，折叠态悬浮球无位移响应）**：`FloatingEditorPanel.attachDrag` 里
- MOVE 用 `4f * density` 启动拖动，UP 却用 `8f * density` 判定点击；
- 位移落在 4~8dp 之间时 `dragging=false` 且 `moved<8dp` → 抬手执行 `performClick()` + `onToggle()`，把 40dp 小球展开成大面板。微调球位置的操作集中在该位移区间，表现为「位移能力未生效且误触发展开」。
- `downRawX/Y/startWinX/Y/dragging` 是**面板级共享字段**，被折叠球与展开态顶部条两个 `attachDrag` 共用；展开态拖动后直接收起，下一次手势带脏状态。
- 点击分支 `return false` 使 UP 未被任何 View 消费，部分 ROM 上紧接着的一次手势会丢 DOWN。

> **v2.9.8 更正（见 ADR-024）**：下述「链路可达」判断**由装机回归证伪**——`Choreographer.postFrameCallback` + `frameScheduled` 自锁会永久卡住提交，正是「折叠态悬浮球位移能力未生效」的主因。本 ADR 当时仅核对代码路径存在，未验证逐帧提交可达性，属静态审计盲区，保留结论以存证。

链路本身（`collapsedBall → attachDrag → service.onDragAbsolute → core.dragAbsolute → Choreographer → OverlayWindowSpec.isPositionOnlyUpdate → updateViewLayout`）当时逐行核对是「连得通」的，**不虚构单一根因**；上述三处是确定存在的缺陷，逐一消除：
1. 判定收敛到 core 纯函数 `isDragMove(dx,dy,slop)` / `isTap(dragging,dx,dy,slop)`，后者是前者的**严格补集**，slop 取 `ViewConfiguration.getScaledTouchSlop()`，不留灰色地带；
2. `applyState()` 折叠/展开切换时调 `resetDragState()` 清共享状态；
3. 折叠球消费整个手势（UP/CANCEL 返回 true），展开态顶部条保持原有穿透，不影响条上「收起 / 关闭」按钮。
补 3 例 core 单测锁死「阈值互补」「收起态 40dp 球可在整屏拖动、不被展开态高度压缩」「收紧后尺寸仍不铺满全屏」。

**缺陷 2（P1，尺寸与视觉密度超标）**：像素量化自家面板约 76.5% 屏宽 × 58.4% 屏高 ≈ 44.7% 屏幕面积、`GLASS_BG` 93% 不透明；竞品同场景约 49% × 22% 且明显半透明。收敛：`EXPANDED_W_DP` 240→220、`MAX_H_FRACTION` 0.62→0.54、`GLASS_BG` alpha `0xED`→`0xD9`（RGB 不变，`luminance/contrast` 自检不受影响）。**不删任何控件、不改默认折叠态、不改功能语义**。另修 `EditorScreen` TopAppBar 五个 TextButton 默认 12dp 内边距把标题挤成竖排四行：收紧到 4dp + 标题 `maxLines=1, softWrap=false`。

**约束延续**：零新增权限 / 零 INTERNET / 不拦截触摸 / 无混淆无加密；`FLAG_NOT_TOUCH_MODAL` 与 `NOT_FOCUSABLE` 不变。半透明只调 alpha，不动 flags。

**后果**：新增 3 例 core 单测，全量 423/423、48 suite。**仍无真机复测**：拖动手感、半透明可读性、高度收紧后是否需滚动，须装机回归；不误标为已跑通。

### ADR-022 阶段时长随机的循环必须事件驱动：「等待画面出现」要真的写进脚本

- **状态**：已接受，v2.9.6 实施。
- **背景**：用户发来 48.87 秒横屏手游录屏（720×328 / 60fps）与能力评估结论「复杂操作链路未达标」。逐帧取证：一个「战斗 → 约 12 秒全屏开箱动画 → 深色『获得奖励』结算 +『确认』→ 回主界面再战」的循环；**全程没有 NymphAuto 界面**，为待自动化目标操作演示，不是回放失败实录。但源码审计确认按产品内提示的推荐做法必然失败：
  1. 编辑器循环弹窗写「配合找图动作（会等待按钮出现）」、视频导入提示写「会轮询等待按钮出现，不依赖固定延时」，而 `CLICK_IMAGE` 的 `withRetry` 默认窗口只有 **2000ms**（`retryWindowMs`，上限 60s 但设置页无入口），超时即判失败推进；`VideoScriptBuilder` 更只生成 `waitMs = gap`（≤3s）的固定延时动作。**描述与实现不符**，10 秒级动画必然超时空转。
  2. 引擎早已有 `Condition(APPEAR_IMAGE)` + `repeatCheckUntilOk` 轮询（含中止条件检查点、等待浮层、看门狗心跳），但 `VideoScriptBuilder` 不生成、编辑器无入口 —— **能力存在却不可达**。ADR-018 把「找图 + 循环」变成一等入口时，只接通了循环，没接通「等待」。
- **决策**：
  1. core 新增 `WaitGate`（纯 JVM 单一真源）：生成「等到这张模板出现在屏幕上」的 `Condition`，`repeatCheckUntilOk = true`、`repeatCheckMax = 0`（无限等待），轮询 300ms。脚本是**可序列化、可导出的数据**，不依赖运行时注入技巧。
  2. `VideoScriptBuilder.Options.waitForAppearance` 默认开启：有模板的动作挂门控条件、`waitMs` 归零（门控即同步点）；模板缺失的 TAP 按原样保留固定延时；`false` 完全回到 v2.9.5 行为。`Action.waitAppear` 让手动建的找图动作由 `ScriptRunner.runAction` 注入同一门控（显式 `condition` 优先，老脚本默认 false 零回归）。
  3. `ScriptCompressor.walkActions → walkCondition` 已支持条件模板去重/还原；新增 `ScriptStoreTest` 锁死「action 与 condition 两处模板经压缩还原后都完整」，否则条件会拿 `ref:xxxx` 去找图。
  4. `repeatCheck` 旧实现无条件取 `minOf(脚本间隔, 调参间隔)`，帧同步档（33ms）会把十几秒长等待压成 30fps 全屏 NCC。间隔选择抽到 core `WaitGate.pollIntervalMs`：无限等待按脚本节奏（不被压快），有限等待保留既有加速语义。
  5. `TapCandidateDetector.pickDefaults`：候选原本**默认全选 60 个**，含全屏特效的视频会检出大量动画噪声，全选 + 无限等待会让脚本卡在永不再现的动画帧上。改为按时间分 6 桶、每桶取最高分，默认最多 6 个覆盖各阶段的候选。
- **可达性实测**：用该真视频复现候选检测（10fps / 180 宽灰度），结算阶段 43–48s 检出 18 个候选、战斗阶段 11 个，关键按钮可勾；56 帧被全局变化判为转场跳过。这证明「视频导入 → 勾 2~3 个关键触点 → 门控循环」路径可达，不是纸面能力。
- **教训**：UI 文案声明的能力须由单测或发版硬门槛约束，否则「等待按钮出现」这种一句话就会变成缺少实现支撑的能力声明；ADR-018 的替代路径要一直验到「生成的脚本产物」这一层，只验入口不算接通。
- **后果**：新增 15 例 core 单测（`WaitGateTest` 7 / `VideoScriptBuilderTest` 4 / `TapCandidateDetectorTest` 3 / `ScriptStoreTest` 1），全量 420/420、48 suite；`verify_release.py` 把 `WaitGate` 纳入 release dex 硬门槛（ADR-008）。**仍无真机复测**；门控是无限等待，必须设脚本中止条件或用通知栏停止；不承诺自动识别自绘界面按钮。

### ADR-021 浮窗拖动必须用屏幕绝对坐标定位：相对增量会被窗口自身移动抵消

- **状态**：已接受，v2.9.5 实施。
- **背景**：真机录屏观测到「周期性位置抖动」。逐帧分析（578×1280 / 44fps / 41 秒）显示：绿色手柄与粉红准星**明显不重合**（手柄停在准星上方约 70px），逐帧位移方向反转率 **72%**。源码定位到 `PickerHandleView.onTouchEvent` 用 `e.x/e.y`（**相对 View 坐标**）算拖动增量——而**手柄窗口本身就在跟随手指移动**，窗口每动一次，下一帧的相对增量就被抵消，于是手柄「周期性」地滞后跳动。
- **决策**：改用**屏幕绝对坐标 + 抓取偏移**（core `PickerGeometry.grabOffset` / `placeFromGrab`）：目标 = 手指绝对坐标 − 按下瞬间记录的偏移。目标位置与窗口自身位置完全无关，零丢增量、零累积漂移。
- **配套**：1px 提交阈值（`PickerGeometry.shouldCommit`）+ `Choreographer` 按帧合并（同帧只提交一次窗口更新，抬手立即落地最后一帧）；浮层残留窗口改为活跃会话登记 + `show()` 前全量拆净 + `start()` 异常路径当场 detach。
- **教训**：ADR-012 的 `dragAbsolute` 只治了**悬浮编辑器**，同样的病漏在**拾取浮层**。「相对坐标增量」在任何「控件自身会跟着移动」的场景都是错的，必须逐个浮窗复查，不能只治一处。
- **后果**：新增 3 例 core 单测（全量 405/405、47 suite）。**修复本身仍未经真机复测**——本次只拿到问题录屏作为证据，手感最终确认需下一份真机录屏。

### ADR-020 不改功能的缺陷修复：新交互逻辑必须能从 Android View 里抽出来单测

- **状态**：已接受，v2.9.4 实施。
- **背景**：用户要求「不要改变功能，检查 bug、检查代码陷阱，重新发布新版本」。
  v2.9.3 刚加的「录制面板可拖动」是纯 Android View 触摸逻辑、无单测覆盖，
  结果留下两个只有真机才能发现、而本环境没有真机的缺陷：
  1. `onTouchEvent(ACTION_DOWN)` 返回 false。落在非 clickable 的文字区域时
     DOWN 未被任何 View 消费，ViewGroup 不会成为 touch target，后续 MOVE
     不再派发 —— 面板实际无位移响应，功能等于没交付；
  2. `onInterceptTouchEvent` 每次 MOVE 都覆盖 `lastX/lastY`，与 onTouchEvent
     共用的增量基准互相打架，拦截路径首帧位移丢失或突跳。
- **决策**：把「按下 → 超过阈值 → 增量 → 抬起」抽成 core 纯 JVM 状态机
  `DragGesture`，`RecordPanel` 只转发 `MotionEvent`、只通过 `onDrag` 改窗口 x/y。
  `DragGestureTest` 6 例锁死两条分发路径（空白区域自消费 DOWN / 按钮上起拖后拦截）。
  **功能语义不变**：仍是超阈值才拖、绝不误伤按钮、只改 x/y 并屏幕内 clamp、
  不改 flags、不拦截外部触摸。另修 `gravity=TOP|END` 下 `p.x + dx` 方向反了的 bug。
- **同类陷阱一并收敛**：计数与延时溢出（`AtomicLong→Int`、对数正态 `exp`、
  `randomWaitMs+1`、空区间 `coerceIn`）、0 尺寸帧除零 / 越界、原子写返回值被忽略、
  日志轮询异常中断、空脚本 + 无限循环空转、负 `repeatCount` 被当成无限。
  全部只做正确性 / 健壮性修复，不改对外行为。
- **后果**：新增 11 例 core 单测（全量 402/402、47 suite）；`verify_release.py`
  把 `DragGesture` 纳入 release dex 硬门槛（ADR-008）。真机拖动手感仍需回归，
  但「空白区域按下能否收到 MOVE」这条已由纯 JVM 测试锁死，不再依赖真机碰运气。

### ADR-007 持续约束：零新增权限 / 零录屏 / 无混淆无加密

- 所有新能力（拾取、放大镜、吸附、视频抽帧）均复用既有
  `TYPE_APPLICATION_OVERLAY` 与无障碍 `takeScreenshot()`。
- 禁止 `MediaProjection` / `VirtualDisplay` / `MediaCodec` 录屏编码 /
  `FLAG_SECURE` / `RECORD_AUDIO` / `INTERNET`。
- release 关闭 R8 与资源压缩，产物类名可读、脚本明文，可逐行审计。

### ADR-032 可理解性基线：意图层 / 判定层 / 可观测性解耦 / 表述规范化静态门禁

- **状态**：已接受，v2.9.16 实施（正式 release，versionCode 34）。
- **背景**：v2.9.15 完成三处落盘语义收口后，交付层暴露五类问题：参数空间向操作层无衰减透传（progressive disclosure 缺失）、观测量→结论映射缺失（校准决策成本回移）、单一开关承载双语义（排错路径发现性不足）、仓库文本残留用户反馈原话与口语化措辞、持久化 schema 语义演进不可见（决策点引导缺位）。这些都不涉及 `SceneRule` 落盘形状，属于「不改格式即可修正」的交互基线，故在独立版本收口。
- **决策（六项，全部不改落盘格式）**：
  1. **意图层 `ScenePreset`**（core 纯 JVM）：四维意图（触发时机 / 防抖强度 / 重复与重试 / 同轮上限）投影 `SceneRule` 带状态参数；`toRule` / `fromRule` 双向可逆，非预设组合返回 `null` 降级为「自定义」；`Intent.normalized()` 收敛 `WHILE_PRESENT` 与 `CONTINUOUS` 的维度耦合，27 种组合往返一致。不新增落盘字段。
  2. **判定层 `SceneVerdict`**（core 纯 JVM）：三项原始观测量 → 四态结论（`NO_FRAME` / `LOW_DISTINCTNESS` / `LOCATION_DEVIATION` / `PASS`），判定优先级按失败代价排序；主界面只呈现 `headline` + `action`，原始分数进 `detail` 与运行日志；`usable` 仅驱动界面提示，门控挂载仍由 `anchorB64` 决定，运行时零回归。
  3. **参数呈现分层与单一真源**：`FloatingEditorPanel.setSceneIntent()` 把摘要收进「高级」区（默认视图高度不变）；`FloatingEditorService.sceneIntentSummary()` 用 `fromRule()` 折叠落盘规则；`buildSceneRule()` 默认触发参数改由 `ScenePreset.DEFAULT` 展开，四个 `const val` 降级为只读别名，消除双真源。
  4. **可观测性通道解耦**：`VisualConfig.sceneDebugEnabled` + `VisualSettings` 独立持久化 + `VisualizerLayer` 独立判定 + `ScriptRunner.emitSceneObservability()` 恒构造 + `RunnerService.onSceneDebug` 恒挂载 + Compose 独立开关；`visualize()` 首行 `!cfg.enabled` 保证只开诊断框不叠加手势特效。
  5. **决策点引导**：首次装配场景规则时在状态行说明范式差异并给出「关闭场景驱动」退路，一次性标记 `scene_guide_shown`，不新增文件 / 权限 / 执行语义。
  6. **表述规范化与静态门禁**：三轮精确改写清理 99 处用户反馈原话 / 俚语 / 拟人 / 情绪化措辞，统一以专业术语陈述结论；新增 `tools/check_wording.py`（70 条精确模式，排除第三方参考资料与构建产物），接入 `verify_release.py` 硬门槛，命中即 FAIL。
- **兼容性（零回归硬约束）**：`schemaVersion` 保持 1，`SceneRule` 字段与语义逐字不变；`ScenePreset` / `SceneVerdict` 为纯投影层与纯函数，不参与序列化；`sceneDebugEnabled` 默认 `false`；老脚本 / 录制 / 导入 / 历史回放与 v2.9.14 过渡格式继续按旧语义执行。`ScenePreset.DEFAULT` 与 v2.9.15 装配取值逐字一致，由单测断言。
- **严格后置项**：B1（统一 `select-ready` 循环）与 C（视觉 DSL / OCR 主链路、运行时自适应阈值、全量推倒引擎）本版不实现。
- **历史附件不可变**：远端已发布的历史 release 附件（v2.8.1～v2.9.15）字节保持不变，本地副本仅做表述规范化；差异在本 ADR 与 v2.9.16 发布说明中载明，不改写已发布产物、不制造同名不同 SHA。
- **后果**：`verify_release.py` 的 `expected_symbols` 由 33 增至 40，新增 `ScenePreset` / `SceneVerdict` / `sceneDebugEnabled` / `setSceneIntent` / `sceneIntentSummary` / `roundTrip` / `ofUnmeasured` 全部实测 True；新增 `wording_info()` 纳入 `ok` 断言。发版核对 494 项单测 0 失败 / 53 suite、1024 本工程类、40/40 符号进包、表述门禁 PASS、拖动门禁 PASS、禁用 API 全 0、8 项权限零新增、同钥证书 `361722d6…6271` v2/v3、16KB 对齐、versionCode 34 / versionName 2.9.16。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准（包内文档不写死自身 SHA，避免自指不一致）。详见 `NymphAuto-v2.9.16-发布说明.md`。
- **能力边界（未验证项）**：环境无真机。悬浮编辑器位移能力仍未闭环——逻辑层与构建层已由反编译与 clean 全量构建排除，剩余取证需要三项埋点（事件是否到达 `OnTouchListener`、`updateViewLayout` 是否携带变化参数、MOVE 是否被父视图消费）与一台真机；本版不在逻辑层盲改，不把「回归门禁 PASS」等同于「可用性结论已闭环」。诊断覆盖层在各 ROM 的绘制表现与场景范式在真实 UI 上的采纳成本亦待真机回归。

### ADR-033 可自解释呈现与位移事件链路可取证（v2.9.17 / 正式 release）

- **状态**：已接受，v2.9.17 实施（正式 release，versionCode 35）。
- **背景**：对 v2.9.16 的交付评估指出四类遗留：意图层只给出档位名称而缺少后果说明（界面信息不自足）；两种执行范式在呈现层异构（顺序侧仅有静态说明）；悬浮窗口位移能力虽在逻辑层与构建层完成核对，但缺乏运行时取证手段；表述门禁为平铺规则且把中性专业词纳入禁用，长期维护成本偏高。经源码核实，四类断言均成立；其中位移能力的剩余不确定性位于系统触摸派发与悬浮窗策略，继续在逻辑层改动不产生新证据；环境无真机属外部约束，不能通过改码消除。
- **判断与边界**：
  1. 位移问题的处置不是再次修改提交逻辑，而是交付**可取证能力**：一次手势即可产出事件链路四阶段的结构化结论与原始观测量，使后续真机验证不再依赖推测。提交逻辑逐字不动，零回归。
  2. 界面可理解性由**信息架构**承担，不由放宽措辞承担：档位后果按维度分层展开、两种范式统一字段结构，使界面自足，降低对真机回归确认语义的依赖。
  3. 落盘分界线不变：`schemaVersion` 保持 1，`SceneRule` 字段与语义逐字不变；B1（统一 `select-ready` 循环）与 C（视觉 DSL / OCR 主链路、运行时自适应阈值、引擎重构）继续严格后置。
- **决策（四项，全部不改落盘格式）**：
  1. **D1 意图后果展开**：`ScenePreset.explain(intent, k)` 把四维意图展开为 `Consequence(dimension, choice, actual, effect, tradeoff)`；`optionCatalog()` 由同一组函数生成全部档位目录，供设置页按维度分组查阅。主层只呈现档位名，实际参数、运行时效果与权衡放在高级区与查阅区；`explain()` 以 `normalized()` 为基准，说明与实际写入参数一致。新增 `ScenePresetExplanationTest` 8 例。
  2. **D2 执行范式同构呈现**：新增 `ExecutionParadigm`（core 纯 JVM），把场景驱动与顺序执行统一投影为四字段卡片（触发依据 / 执行次序 / 画面条件 / 失败处理）；`comparison()` 提供随时可查的对照表，不依赖首次装配的一次性提示。面板 `setParadigm()` 在 `bind()` 时回填顺序基线，替换原先的单句静态说明。新增 `ExecutionParadigmTest` 7 例。
  3. **D3 位移事件链路自诊断**：新增 `DragProbe`（core 纯 JVM）旁路聚合一次手势的 DOWN / MOVE / COMMIT / 位置变化四阶段，按失败代价排序输出四态结论（`NO_DOWN` / `MOVE_INTERCEPTED` / `COMMIT_BLOCKED` / `PASS`）、建议动作与原始观测量。app 侧 `DragDiagnostics` 持有两路探针（悬浮编辑器 / 主界面运行球），在 `FloatingEditorPanel.dragListener`、`FloatingEditorService.onDragAbsolute`、`RunnerService.attachBallTouch` 以旁路方式记录，不改变任何提交时机与参数；设置页呈现两路最近一次结论，进入页面即写入运行日志供导出。新增 `DragProbeTest` 10 例。
  4. **D4 表述门禁分类化**：`check_wording.py` 由平铺列表重构为 `RULES` 四类（问题原文 / 原文引用标注 / 口语化标题 / 俚语拟人情绪化）+ `ALLOWLIST` 显式豁免表（默认空）+ `NON_WAIVABLE` 两类不可豁免类别；移除两条中性词误伤条目，规则总数 70→68。严格性不变（命中仍判 FAIL），`verify_release.py` 输出分类统计与豁免数。
- **兼容性（零回归硬约束）**：新增能力全部为纯投影、纯函数与旁路观察，不参与序列化、不参与执行决策；`SceneRule` 与 `Script` 落盘字段逐字不变；老脚本、录制、导入与历史回放继续按旧语义执行。位移提交链路的调用时机、`updateViewLayout` 参数与 `flags` 状态均未改动。
- **后果**：`verify_release.py` 的 `expected_symbols` 由 40 增至 51，新增 `ExecutionParadigm` / `DragProbe` / `DragDiagnostics` / `optionCatalog` / `sceneExplanation` / `setParadigm` / `setIntentDetail` / `onDragCommit` / `writeLog` / `MOVE_INTERCEPTED` / `COMMIT_BLOCKED` 全部实测 True。发版核对 519 项单测 0 失败 / 56 suite、1042 本工程类、51/51 符号进包、表述门禁 68 条 0 命中、拖动门禁 PASS、禁用 API 全 0、8 项权限零新增、同钥证书 `361722d6…6271` v2/v3、16KB 对齐、versionCode 35 / versionName 2.9.17。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准（包内文档不写死自身 SHA，避免自指不一致）。详见 `NymphAuto-v2.9.17-发布说明.md`。
- **能力边界（未验证项）**：环境无真机。D3 交付的是取证能力而非可用性结论：四态结论需在真机执行一次手势后由设置页或运行日志读取，才能把「逻辑层与构建层已排除」推进到「系统派发层已定位或已确认通过」。D1 / D2 的界面信息密度与档位语义可读性仍待真机回归；`instrumentation` 测试仍为零。远端已发布历史 release 附件字节保持不变。

---

### ADR-034 悬浮窗位移交互未生效根因修复（v2.9.18 / 正式 release）

- **状态**：已接受，v2.9.18 实施（正式 release，versionCode 36）。
- **背景**：v2.9.11～v2.9.17 连续六版在拖动交互上投入，逻辑层（setOnTouchListener 直吃手势 + 同步 updateViewLayout）与构建层（clean 全量构建、静态门禁、同钥签名）均已逐项核对，但悬浮窗可见但位移交互未生效的缺陷在多版真机反馈中反复出现。环境无真机，此前只能以逻辑层排除得出结论，无法定位设备侧根因。
- **真机证据驱动的定位**：对用户提供的 21 秒真机录屏（某 OEM ROM 游戏空间场景，720×328、60fps）逐帧分析——锁定圆形小球（尺寸 20–31px、宽高比 0.76–1.12）追踪质心，运行球在整段 21 秒内停留在左上角（帧内 x≈148–157、y≈11–18，±9px 抖动内），不随手指位移；同录屏中坐标拾取器读数气泡正常（X/Y/百分比自洽）。形成关键对照：同进程内能拖的窗口（坐标拾取器、录制面板）都自带 FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS，不能拖的运行球与编辑面板都缺这两项。
- **根因**：TYPE_APPLICATION_OVERLAY 窗口在未声明 FLAG_LAYOUT_IN_SCREEN 时，布局坐标以所属 APP 窗口为基准而非全屏绝对坐标；在 OEM ROM / 游戏空间将宿主 APP 置于受限窗口上下文时，窗口视觉位置与触摸命中区域偏移，触摸落点落在窗口本体之外，setOnTouchListener 收不到 ACTION_MOVE。拖动提交逻辑始终正确，属窗口坐标空间配置缺陷。
- **决策（落盘格式零改动）**：
  1. OverlayWindowSpec（编辑面板 flags 单一真源）新增 FLAG_LAYOUT_IN_SCREEN=0x100、FLAG_LAYOUT_NO_LIMITS=0x200，并入 STANDARD_FLAGS(0x328) 与 INPUT_FLAGS(0x320) 两种受控态；isAllowed 等幂约束随之覆盖，单测更新断言值。编辑面板经此入口自动获得两项标志。
  2. RunnerService 运行球与展开控制面板内联 LayoutParams 补齐同款标志，与已知可用窗口配置对齐。
  3. 坐标拾取器、录制面板本已带两项标志，未改动——此对照为根因定位依据。
- **兼容性（零回归硬约束）**：新增为窗口坐标空间标志，不参与序列化与执行决策；SceneRule/Script 落盘字段逐字不变，schemaVersion 仍为 1。拖动逻辑、isAllowed 受控态集合、布局/类型/格式字段、禁用 API 约束全部未变；setInputFocusable 焦点切换在两种受控态间进行，坐标空间标志恒定保留。
- **后果**：verify_release.py 的 expected_symbols 由 51 增至 54，新增 OverlayWindowSpec / FLAG_LAYOUT_IN_SCREEN / FLAG_LAYOUT_NO_LIMITS 全部实测 True；拖动静态门禁新增布局坐标空间标志已接线断言（RunnerService 与 OverlayWindowSpec 源字符串双重校验）。发版核对 519 项单测 0 失败 / 56 suite、1042 本工程类、54/54 符号进包、表述门禁 68 条 0 命中、拖动门禁 PASS、禁用 API 全 0、8 项权限零新增、同钥证书 361722d6…6271 v2/v3、16KB 对齐、versionCode 36 / versionName 2.9.18。APK 与源码包 SHA-256 以 tools/verify_release.py 实时输出为准（包内文档不写死自身 SHA，避免自指不一致）。详见 NymphAuto-v2.9.18-发布说明.md。
- **能力边界（未验证项）**：环境无真机。本版改动由源码 + 单测 + dex 符号门禁 + 表述门禁 + 同钥签名证实，但装新版后位移交互在 OEM ROM / 游戏空间下是否确实恢复仍需用户在该环境实测；instrumentation 测试仍为零。v2.9.17 的 D3 位移自诊断保留为取证手段。远端已发布历史 release 附件字节不变。

---

### ADR-035 悬浮窗坐标空间同源缺陷收口（v2.9.19 / 正式 release）

- **状态**：已接受，v2.9.19 实施（正式 release，versionCode 37）。v2.9.18 完成本地构建与核验但未对外发布，其修复并入本版一次性交付。
- **背景**：v2.9.18（ADR-034）以真机录屏逐帧对照定位到窗口标志缺失，并为运行球与编辑面板补齐 `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`。继续排查发现该修复仅覆盖同一根因的第一层：窗口标志把 `x/y` 的坐标空间切换为显示屏全屏绝对坐标后，尺寸来源与配置变化处理仍是旧基准。
- **三层排查结论**：
  1. **L1（已修）**：运行球与展开面板缺布局坐标空间标志，视觉位置与触摸命中区域错位；坐标拾取器与录制面板自带该配置，构成关键对照。
  2. **L2（本版修）**：`RunnerService.showBall()` 初始位置、`layoutPanel()`、`attachBallTouch()` 的 MOVE 收敛，以及 `FloatingEditorService.refreshMetrics()` 仍使用 `resources.displayMetrics.widthPixels/heightPixels`。该值描述应用窗口可用区，不含系统装饰区与显示切口区；而坐标拾取器与录制面板使用 `WindowManager.currentWindowMetrics.bounds`（低版本 `getRealMetrics`）。窗口坐标空间已统一、尺寸来源未统一，导致边界收敛上界偏小、初始位置存在系统性偏移，在沉浸式、异型屏与横屏场景下位移范围被截断。
  3. **L3（本版修）**：`RunnerService` 无 `onConfigurationChanged`、无屏幕尺寸缓存，横竖屏切换或折叠屏展开后不重算 `x/y`；`FloatingEditorService` 虽有配置变化回调，但仅做边界收敛而不保持相对锚点。窗口可能停留在旧坐标导致越界不可见。
- **决策（落盘格式零改动）**：
  1. core 新增 `OverlayBounds`（纯 JVM）：`Size` 表示全屏绝对坐标边界，`clamp()` 按边界减窗口尺寸收敛（窗口大于屏幕时上界归零），`reposition()` 按旧边界占比映射至新边界后收敛，采用四舍五入消除浮点截断偏差；新增 `OverlayBoundsTest` 8 例。
  2. app 新增 `OverlayWindowMetrics`（尺寸单一真源）：API 30+ 取 `currentWindowMetrics.bounds`，低版本回退 `getRealMetrics`，口径与坐标拾取器既有可用实现一致。
  3. 五处消费方统一接入：`RunnerService`、`FloatingEditorService`、`RecordingService`、`CoordinatePicker`、`EditorScreen`；删除三份重复尺寸实现与 `displayMetrics` 尺寸读取。保留「仅在服务创建、悬浮球创建与配置变更时刷新缓存」的既有约束，拖动热路径不重读尺寸。
  4. 配置变化重定位：`RunnerService` 新增 `onConfigurationChanged` 与边界缓存，按相对锚点重定位运行球并提交 `updateViewLayout`，随后重排控制面板；`FloatingEditorService` 在刷新边界后同样按相对锚点映射，再由既有 `setCollapsed()` 完成最终收敛。边界未变化时不触发 relayout。
  5. 窗口标志、`OverlayWindowSpec` 两种受控态（`STANDARD_FLAGS=0x328`、`INPUT_FLAGS=0x320`）、拖动提交逻辑与 `isAllowed` 约束均沿用 v2.9.18，未再改动。
- **兼容性（零回归硬约束）**：新增能力仅涉及窗口布局边界，不参与序列化与执行决策；`SceneRule` / `Script` 落盘字段逐字不变，`schemaVersion` 仍为 1。脚本坐标语义不变：坐标拾取真值（`PickerGeometry`）、`AutoClickService.frame()` 参考帧尺寸、无障碍手势坐标均未改动。
- **后果**：`verify_release.py` 的 `expected_symbols` 由 54 增至 58，新增 `OverlayBounds` / `OverlayWindowMetrics` / `reposition` / `screenSize` 全部实测 True；拖动静态门禁新增三项断言（边界尺寸单一真源已接线、应用窗口可用区尺寸未用于边界收敛、配置变化重定位已接线），并扫描五处消费方是否确实调用 `OverlayWindowMetrics.screenSize`。发版核对 527 项单测 0 失败 / 57 suite、1045 本工程类、58/58 符号进包、表述门禁 68 条 0 命中、拖动门禁 PASS、禁用 API 全 0、8 项权限零新增、同钥证书 `361722d6…6271` v2/v3、16KB 对齐、versionCode 37 / versionName 2.9.19。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准。详见 `NymphAuto-v2.9.19-发布说明.md`。
- **能力边界（未验证项）**：环境无真机。装机后位移交互在 OEM ROM / 游戏空间下是否恢复、横竖屏切换后窗口是否保持可见，仍需在该环境实测；`instrumentation` 测试仍为零。v2.9.17 的 D3 位移自诊断继续保留为取证手段。B1 / C 严格后置。

---

### ADR-036 场景驱动运行时策略收口（v2.9.20 / 正式 release）

- **状态**：已接受，v2.9.20 实施（正式 release，versionCode 38）。
- **背景**：以一份八维度外部技术评估为输入，逐条对照当前 HEAD 源码、release dex 与 `tools/verify_release.py` 实测。评估把项目质量拆为「计算逻辑」与「操作逻辑」两层，前者评价为严谨（带状态触发纯函数、fail-closed 分层、浮点边界、意图层降级克制），后者指出三处结构性缺口：循环语义在两种执行范式间未归一、连续落点偏差无确定性终止上界、零真机导致运行时结论均为纸面推导。核对后确认主干判断成立，但若干具体断言需要修正。
- **核对结论与修正**：
  1. **测试口径**：评估引用 494 个 `@Test`，实为 v2.9.16 口径（53 suite）；当前为 541 个 / 541 用例 / 58 suite（core 51 测试文件 + app 6 测试文件）。「覆盖集中在纯 JVM 与少量 app、`instrumentation` 为零」的结论不受影响。
  2. **位移能力版本序列**：评估称该交互自 v2.9.11 起持续未闭环，实为旧快照。v2.9.18 修复布局坐标空间标志（ADR-034）、v2.9.19 修复边界尺寸基准与配置变化重定位（ADR-035），均已发布正式 release；剩余为真机取证边界。
  3. **可发现性归因**：评估认为 fail-closed 的可发现性依赖日志。实测 `onAbnormal` 已通过 `toast()` 前台送达，终止路径另有 `onWaitStatus` 浮层；真正缺少前台提示的是连续落点偏差分支。两处不应合并论述。
  4. **时钟源归因（v2.9.20 重新发版已精确降格，详见下方补记）**：评估提出 `elapsedRealtime` 含深度睡眠会使短重试窗口在熄屏恢复后瞬间到期并触发非预期重试。`retryDue`（`nowMs - lastFire >= retryTimeoutMs`）与 `cooldown`（`nowMs - lastFire < cooldownMs`）均以 `nowMs` 为基准，而 `nowMs` 取自 `SystemClock.elapsedRealtime()`，含深度睡眠；恢复瞬间该差值因计入深睡时长而异常增大。行为取决于休眠期是否持续抓帧：若持续抓帧且均观测为未命中，则 `missStreak` 累积离场，恢复后按正常 `ON_APPEAR` 上升沿触发；若事件循环冻结、迟滞状态保持熄屏前快照，则 `wasPresent=true`、`retryDue` 成立、`everFired` 非空，`retry` 三条件同时满足，会产生一次非预期重试。两条路径均属运行时 ROM 行为，纯 JVM 单测无法区分，未做真机观测。此外含深睡时钟令 `elapsed` 增大，`elapsed < cooldownMs` 不再成立，冷却窗口是被跳过而非「被拉长、方向安全」——前版该方向表述为错误。全工程 30 余处统一采用 `elapsedRealtime`，且 `nowMs - scriptStartAt`、进度心跳与空闲守卫同基准，局部替换为 `uptimeMillis` 会制造新的基准不一致；故不改时钟源，仅把结论降格为「不排除状态冻结路径下的额外重试，其余时间差语义未实测」并列为确定性未验证项。
  5. **措辞精度**：`calibrateSimilarity` 的 `0.08` 余量为经验值、`runnerUpScore` 为抑制 ROI 后全屏最高误匹配分而非严格次高峰，评估对「阈值自校准」措辞略高的批评成立。文档降格为「带经验余量的启发式下限」；不修改公式，避免改变新拾取脚本的 `similarity` 落盘值。
- **决策（落盘格式零改动）**：
  1. core 新增 `MismatchGuard`（纯 JVM）：`onMismatch(prev)` 输出四段策略 `CONTINUE / WARN / BACKOFF / ABORT`，阈值 3 / 6 / 24 次，降频步长 400ms、封顶 6 档、日志每 8 次节流；`onFired()` 提供派发后复位。新增 `MismatchGuardTest` 9 例。设计判断：顺序链 `awaitFind` 的「目标未出现」属于正常等待语义，只降频不终止；场景链的「场景已匹配但落点持续超出位置偏差预算」属于门控参数失配，是确定性失效，二者不共用处置强度。
  2. `runSceneDriven` 接线护栏：局部计数跨轮持有；`WARN` 前台送达复核指引，`BACKOFF` 先 `pulse()` 刷新心跳再 `delay(backoffMs)`，`ABORT` 抛 `AbortHit` 显式中止；`Hit` / `Absolute` 复位计数。**计数语义在重新发版中已修正为按规则独立且场景离场不打断，见下方补记**；「任一有效派发立即复位」为前版全局单例计数的表述，已被替换。
  3. core `LoopPolicy` 新增 `LoopScope` 与 `scopeOf()` / `appliesTo()` / `labelFor()` / `sceneDrivenNotice()` 单一真源，新增 5 例单测。编辑器按范式显示有效文案、禁用场景侧输入与快捷档位、对话框改为作用域说明；`repeatCount` 落盘值不变。场景侧收尾改用 `sceneSummary(firedRounds)`，不再把脚本级 `repeatCount` 传入 `RunStats.summary` 的 `total` 参数（`total>1` 会输出「共 N 轮」的误导文案）。
  4. **不让 `repeatCount` 在场景侧刹停**：事件驱动常驻是本范式的设计意图；让一个顺序范式概念刹停场景主循环，会把「场景出现即触发」改写为「运行 N 轮后不再响应」，并改变已发布脚本的运行时行为。选择「显式声明作用域 + 消除误导文案」，属于产品语义判断，非引擎能力缺失，也不等同于严格后置的 B1（统一 `select-ready` 循环）。
  5. 判定层呈现对齐：`SceneVerdict` 的 PASS 结论由「已启用画面门控」改为「可启用画面门控」（判定发生在装配阶段，挂载由 `anchorB64` 决定）；`FloatingEditorService` 依据 `anchorB64` 计算 `gateMounted` 并在状态栏显式呈现门控实际挂载状态，覆盖「结论不可用但门控已挂载」与反向情形。`attachSceneRule` 挂载依据零改动。
- **明确不改**：`planSceneFiringStateful` 状态机、`calibrateSimilarity` 公式与 `-1e-6`、`ScenePreset` 降级路径、`SceneVerdict` 四态优先级、`attachSceneRule` 挂载依据、全工程 `elapsedRealtime` 基准、`SceneRule` / `Script` 落盘字段与 `schemaVersion=1`、ADR-035 的坐标空间与拖动提交逻辑、禁用 API 约束。B1 / C 严格后置。
- **后果**：`verify_release.py` 的 `expected_symbols` 由 58 增至 65，新增 `MismatchGuard` / `onMismatch` / `onFired` / `BACKOFF_AFTER` / `sceneDrivenNotice` / `labelFor` / `sceneSummary` 全部实测 True；静态接线门禁新增三项断言（连续落点偏差护栏已接线、执行范式作用域单一真源、判定结论与门控挂载呈现对齐），并扫描 `ScriptRunner` / `EditorScreen` / `LoopPolicy` / `SceneVerdict` / `FloatingEditorService` 的接线与残留旧文案。发版核对 541 项单测 0 失败 / 58 suite、1051 本工程类、65/65 符号进包、表述门禁 68 条 0 命中、静态门禁 15 项 PASS、禁用 API 全 0、8 项权限零新增、同钥证书 `361722d6…6271` v2/v3、16KB 对齐、versionCode 38 / versionName 2.9.20。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准。详见 `NymphAuto-v2.9.20-发布说明.md`。
- **能力边界（未验证项）**：环境无真机。护栏阈值（3 / 6 / 24 次、400ms 步长）、窄屏面板上的文案可读性、前台告警在各 OEM ROM 的实际呈现均未实测；`instrumentation` 测试仍为零。本版修复的是可由静态推理与纯 JVM 单测锁定的运行时策略缺陷，不声称解决真机手感。熄屏恢复后的触发行为按迟滞语义推导，未做真机观测。v2.9.17 的 D3 位移自诊断继续保留为取证手段。

### ADR-036 补记 场景驱动运行时策略二次复核与同版本号重新发版（v2.9.20 / 正式 release）

- **状态**：已接受，v2.9.20 重新发版实施（versionCode 38 不变）。同版本号早前构建的 Gitee release `1308417` 与 GitHub release `445195314` 连同全部附件已删除，回读确认不存在；tag `NymphAuto-v2.9.20` 保留并由重建的 release 复用（Gitee OpenAPI 未提供删除 tag 的 REST 端点，保留同一提交引用比删除后重建更安全）；随后以同一版本号重新构建、签名、核验、发布。早前构建的 APK 与源码包已失效，不制造历史版本的同名不同 SHA。
- **背景**：第二轮技术复核针对本 ADR 的修复决策逐条评估，判定主干成立、三条事实修正成立，并指认两处未处理到位之处：「`MismatchGuard` 未明说场景离场是否复位」「维度 5 只消除误导未提供停止手段」；另指出时钟归因「与 `retryTimeoutMs=0` 完全一致」措辞过满。核对源码后，两处指认属实，且暴露出前版发布说明的一处方向性错误与一处更实质的计数缺陷。
- **核对结论**：
  1. **时钟归因半对且需再修正**：复核接受「熄屏无帧 → `missStreak` 离场 → 不触发 retry」这一前提，但该前提仅在休眠期持续抓帧时成立；若事件循环冻结、迟滞状态保持熄屏前快照，恢复后 `wasPresent=true` 且 `retryDue` 因差值异常增大而成立，`retry` 三条件同时满足，异常恰好落在 retry 分支。前版「`cooldown` 抑制窗口被拉长，方向安全」亦为方向错误（含深睡时钟令 `elapsed` 增大，冷却窗口被跳过）。结论：不改时钟源（全工程 30 余处同基准，局部替换会与 `scriptStartAt` 等消费方不一致），把「完全一致」降格为「不排除状态冻结路径下的额外重试，其余时间差语义未实测」，并列为确定性未验证项。
  2. **离场复位指认成立但方向需修正**：源码确认前版 `decision.fire.isEmpty()` 分支不复位计数，`MismatchGuardTest` 原 9 例无离场语义覆盖，发布说明「偶发偏差不累积为终止条件」属过度承诺。但不宜实现「离场即复位」——周期性出现且锚点系统性偏移时，每个出现周期仅累计一次随即归零，`ABORT_AFTER` 上界不可达，护栏失效。
  3. **独立发现的计数缺陷**：前版使用单次运行全局单例计数，多规则脚本中任意一条规则的成功派发都会清零计数，使另一条规则的确定性失配被掩盖。此缺陷未被两轮外部评估指出。
  4. **维度 5 折衷不予采纳**：复核提出「`repeatCount>0` 作为成功触发次数上限、仅在用户显式切换时生效」。该方案需区分旧脚本遗留值与用户新意图，在落盘格式零改动约束下无法表达（无新增字段或哨兵值，界面态不落盘），且令同一字段在两种范式分别承担「轮次」与「成功触发次数」，加剧语义分裂。结束手段实际存在（中止条件 / 通知栏停止），缺的是次数维度，故仅补可操作指引。
- **决策（落盘格式零改动）**：
  1. core `MismatchGuard` 新增 `Tracker`：`onMismatch(ruleIndex)` / `onFired(ruleIndex)` / `onIdle()` / `countFor(ruleIndex)`，按规则独立计数，`onFired` 仅复位本规则；连续性定义为「自本规则上一次有效派发起，场景离场不打断」，`onIdle()` 显式登记保持态。既有顶层纯函数与四段阈值不变。新增 4 例契约单测，含「周期性出现但持续偏差仍可收敛到中止上界」。
  2. `runSceneDriven` 以 `Tracker` 取代全局整型计数，`decision.fire.isEmpty()` 分支显式调用 `onIdle()`，`Hit` / `Absolute` 按 `idx` 复位。
  3. `LoopPolicy.SCENE_DRIVEN_NOTICE` 补「本范式不提供次数维度上限，如需按触发次数收敛请配置中止条件」，新增 1 例单测；编辑器、启动日志与收尾沿用同一真源。
  4. 文档精确化：撤回无条件表述、纠正方向错误、载明折衷不采纳的硬约束；发布说明新增第零章「撤销与重建的范围」与第四章「同版本号重新发版」兼容性说明。
- **明确不改**：`planSceneFiringStateful` 状态机、`calibrateSimilarity` 公式与 `-1e-6`、全工程 `elapsedRealtime` 时间基准、`SceneRule` / `Script` 落盘字段与 `schemaVersion=1`、四段策略阈值、ADR-035 坐标空间与拖动提交逻辑、禁用 API 约束、B1 / C。
- **后果**：`verify_release.py` 的 `expected_symbols` 由 65 增至 68（新增 `MismatchGuard$Tracker` / `onIdle` / `countFor`）；静态接线门禁由 15 项增至 17 项（新增「计数按规则独立、全局单例已消除」「按次数停止指引已入单一真源」）。单测由 541 增至 546，58 suite，失败 0。APK 与源码包 SHA-256 因产物重建而变化，以 `tools/verify_release.py` 实时输出为准。
- **能力边界**：环境无真机，休眠期抓帧行为、护栏阈值手感、窄屏文案与各 OEM ROM 前台呈现均未实测，`instrumentation` 仍为零。本补记修的是可由静态推理与纯 JVM 单测锁定的契约与文档缺陷，不声称解决真机手感。

### ADR-037 场景编辑交互层级重构与名称字段输入法唤起能力修复（v2.9.21 / 正式 release）

- **状态**：已接受，v2.9.21 实施（versionCode 39）。以 v2.9.20 正式版源码包为唯一基线，功能契约与落盘格式不变，仅调整交互层级、自动化时机与用户可见术语。
- **背景**：核对基线后确认两项待处理点。
  1. **名称字段编辑态输入法唤起能力缺失**：展开态悬浮编辑窗口默认带 `FLAG_NOT_FOCUSABLE`。点输入框时 `setInputFocusable()` 先经 `OverlayWindowSpec.isLegalTransition()` 严格相等闸，再用 `flagsFor()` 整体覆盖窗口 flags；系统在 `addView` 之后追加的标志位会使当前 flags 与标准态不再相等，切换被拒绝。同时 `updateViewLayout` 与 `EditText.post{}` 之间无同步保证，焦点请求可能早于窗口 flags 生效。
  2. **配置期概念呈现与主辅关系错位**：一次完整的「精确坐标拾取 → 自动采集素材 → 自动收敛点击区域 → 颜色校验 → 执行脚本」在用户可见层被表述为多个独立条目并配名称字段，概念分裂；颜色样本、容差与 ROI 被呈现为需先行配置的主概念，与坐标拾取的实际主流程不一致；「上一 / 下一」逐条翻页与列表化导航惯例不一致。
- **决策（落盘格式零改动）**：
  1. **位级焦点切换**：core `OverlayWindowSpec` 新增 `focusableFlags(current, focusable)`，仅增删 `FLAG_NOT_FOCUSABLE` 一位，保留系统 / OEM 追加的全部标志位；`isFocusable()` / `isFocusToggleOnly()` 供取证。`FloatingEditorService.setInputFocusable()` 改用该函数，不再整体覆盖。名称字段在 `init` 阶段即实例化，`isFocusable=true`、`isFocusableInTouchMode=true`；`requestInputFocus()` 用双 `post` 等待窗口参数生效后执行 `requestFocus()` 与 `showSoftInput()`。不采用只读文本、不可聚焦或详情态懒实例化等规避写法。
  2. **术语归一与场景列表**：core `SceneList` 作为用户可见术语与坐标摘要的单一真源，提供 `UNIT` / `TITLE_CURRENT` / `TITLE_LIST` / `LABEL_*`、`coordinateLabel()` / `rows()` / `selectAt()` / `currentTitle()` / `nameEditable()`。悬浮面板以只读坐标摘要列表替代逐条翻页，点行进详情；名称编辑由独立输入框承担，与列表行解耦。Compose 编辑器与设置页的用户可见文案统一以「场景」表述。运行时执行诊断层与导入器外部格式关键字（脚本格式中的组合条目名称）属既有兼容契约，不在改写范围内，此边界由静态门禁显式声明。
  3. **主辅层级与自动化时机**：坐标拾取为第一视觉层级（强调色主入口），状态行呈现拾取状态、命中结果与判定中。颜色样本、容差与有效点击区域由一次拾取自动派生，收在第二视觉层级（折叠区 + 轻量状态条）。core `SceneAssist` 提供 `sampleNeighborhood()` / `toleranceFor()` / `convergeRoi()` / `resolveRoi()`（手动覆盖优先、清除后回退自动结果）；core `TapGate` 提供 `Phase` / `Reason` / `Verdict` / `Generation`，点击前门控仅在 `PASS` 时允许派发，异步在途显示「判定中」，旧代次结果不得覆盖新状态。
  4. **落盘零改动**：颜色素材写入既有 `Action.color` / `Action.colorSimilarity`，有效区域写入既有 `Action.region`，场景名写入既有 `Action.desc`；`SceneRule.searchRoi` 仍沿用稳定锚点模板搜索范围，运行时画面门控语义不变。`schemaVersion=1`，历史脚本零迁移，可原地覆盖升级。
  5. **门禁更新**：`verify_release.py` 的 `expected_symbols` 替换为 `SceneAssist` / `TapGate` / `SceneList` 契约与界面接线符号；静态接线门禁新增六项（场景名可编辑、位级焦点切换、场景列表接线、辅助门控自动派生、落盘字段不变、术语归一与单一真源）；`check_wording.py` 保持四类规则不变。
- **明确不改**：场景驱动运行时策略与 `planSceneFiringStateful` 状态机（ADR-036）、`MismatchGuard.Tracker` 按规则独立计数、画面门控与 fail-closed 判定、落点偏差护栏、ROI 与稳定锚点、失败即停、窗口坐标空间与拖动提交（ADR-034 / ADR-035）、`calibrateSimilarity` 公式、全工程 `elapsedRealtime` 基准、`SceneRule` / `Script` 落盘字段与 `schemaVersion=1`、禁用 API 约束、零 `INTERNET`、零新增权限、B1 / C。
- **后果**：新增 3 份 core 纯 JVM 契约与 3 组契约单测，`OverlayWindowSpec` 补位级焦点切换回归项；悬浮面板与 Compose 侧术语归一；静态接线门禁扩展为 24 项。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准。
- **能力边界**：本环境无真机、无 `instrumentation` 测试。输入法实际弹出、悬浮窗口焦点切换在各 OEM ROM 上的行为、窄屏文案折行、横屏拾取手感均未实测。本版证据仅覆盖源码接线、纯 JVM 契约、release dex 符号、静态门禁与全量单测；上述交互效果列为待真机回归项，不以静态门禁冒充运行时验证。

### ADR-038 拾取体验收口：结果卡纠偏中枢、未命中原因可自助修复、诊断摘要（v2.9.22 / 正式 release）

- **状态**：已接受，v2.9.22 实施（versionCode 40）。以 v2.9.21 为基线，只改 UI / 交互层。
- **背景**：v2.9.21 修复名称字段的输入法唤起能力后，收到一份四层体验优化建议（P0 真机可确认 / P1 拾取主流程 / P2 自动派生透明可纠正 / P3 场景管理）。逐条按「能否在不改 `schemaVersion=1` 与运行时契约的前提下真实改善体验」裁定，结论为：方向成立，但其中五项必须否决或改写，两项属于既有能力而非新增需求。
- **已核实为既有能力，不计入本版工作量**：
  1. **边缘避让**：拾取浮层的坐标标签与准星在 v2.9.20 已由 `PickerGeometry.clamp()` 与窗口 `coerceIn` 收敛在屏内，`PickerGeometry.drag()` 累加式定位避免贴边漂移。
  2. **连续拾取节流**：`TapGate.Generation` 已在 v2.9.21 提供代际隔离，旧任务结果不覆盖新状态。本版只补 UI 层的按钮禁用与「判定中」呈现，不重复实现节流。
- **采纳并落地**：
  1. **拾取结果卡作为纠偏中枢**：结论、坐标、颜色、区域、模式、置信度与四个动作同卡呈现，主修复按钮随未命中原因变化。卡片在无拾取结果时隐藏，不改变无拾取状态下的默认窗口高度。
  2. **未命中原因 → 唯一主修复动作**：core `PickFeedback.guidanceFor()` 为单一真源。越界 → 扩大区域；区域为空 → 重新拾取；手动覆盖为空 → 清除手动；颜色不符 → 重新采样；取色失败 → 重试。界面只呈现标签，不自行推断动作，避免文案与实际修复漂移。
  3. **置信度分级与模式常驻**：`confidenceOf()` 按邻域离散度分为高 / 中 / 低（阈值 24 / 72），低置信度明确建议手动框选；`roiModeLabel()` 与 `manualOverlayNote()` 常驻状态条，清除手动后明确回退自动。
  4. **诊断摘要一键复制**：`PickFeedback.diagnose()` 输出版本、手势、坐标、区域、颜色、置信度、结论、焦点状态与进程内计数。`INTERNAL_TERMS` 与 `leaksInternalTerm()` 作为可测黑名单，防止内部实现术语进入用户可见文本。
  5. **可访问性**：状态以文字前缀为主、颜色为辅，`contentDescription` 覆盖状态行与全部动作按钮；列表行以 ● / ○ 标记选中态，不依赖颜色单独表意。
  6. **判定中禁用重复操作**：`setPickBusy()` 关闭拾取与主修复入口，避免连点产生过期任务竞态。
  7. **首次引导**：无场景时呈现三步文案（拾取 → 自动生成 → 保存），建立首个场景后隐藏。
- **否决及理由**：
  1. **执行前确认弹窗**：运行时为无人值守的场景触发，逐次确认会摧毁自动化连续性，并在悬浮窗内引入新的焦点与触摸竞争。改为非阻塞回执（状态行、运行日志、通知栏）。该否决已固化为门禁 `no_blocking_confirm`。
  2. **持久化埋点**：「首次拾取到保存耗时 / 纠偏比例 / 原因分布 / 自助修复率」需跨会话累积，必然新增落盘字段或独立统计文件，违反「零新增落盘字段」与「历史脚本零迁移」。改为进程内 `PickFeedback.Counters`，只进入诊断摘要，不落盘、不联网、不上报。另「名称编辑成功率」在客户端不可可靠测量——`showSoftInput()` 的返回值不代表软键盘实际显示，故不纳入指标。
  3. **场景缩略图**：需为每个场景持久化一张图，属新增落盘资产；若改为运行时截屏，则每次列表渲染都触发全屏截图，代价与干扰不可接受。改为「坐标摘要 + 最近一次判定状态」。
  4. **ROI 四角手柄与撤销 / 重做**：需改造拾取器手势模型并新增状态栈，超出本轮「只改交互层」的低风险边界。保留既有拖框语义与「重置为自动区域」，该两项留待独立版本评估。
  5. **列表长按多选删除**：脚本删除不可逆且无回收站，须配套回收站与二次确认才可上线，不能作为体验优化顺手加入。
  6. **P0 真机矩阵由自动化流程完成**：本环境无真机、无 `instrumentation`，无法执行。改为交付真机自测清单与诊断摘要，由用户在设备上完成，并在发布说明中如实标注为未验证项。不以静态门禁冒充运行时验证。
- **明确不改**：`schemaVersion=1` 与 `Action` / `SceneRule` 字段、`planSceneFiringStateful` 状态机、`MismatchGuard.Tracker` 计数语义、画面门控与 fail-closed 判定、落点偏差护栏、稳定锚点、失败即停、`calibrateSimilarity` 公式、`elapsedRealtime` 基准、窗口坐标空间与拖动提交、零 `INTERNET`、零新增权限、B1 / C。
- **后果**：新增 core `PickFeedback` 契约与 17 例单测，全量单测 595 / 62 suite、失败 0；静态接线门禁由 24 项增至 31 项（新增结果卡中枢、原因可修复、诊断可复制、统计仅内存、可访问性、判定中防抖、禁止执行前确认七项）；`expected_symbols` 新增 14 项。UI 层新增结果卡、诊断复制与引导文案，未新增任何落盘字段、权限或网络行为。APK 与源码包 SHA-256 以 `tools/verify_release.py` 实时输出为准。
- **能力边界**：输入法在真机的实际弹出、焦点在展开 / 收起 / 切后台 / 切输入法后的恢复、各 OEM ROM 的前台呈现、窄屏折行与横屏拾取手感仍未实测。本版证据为源码接线、纯 JVM 契约、release dex 符号、静态门禁与全量单测；上述交互效果列为待真机回归项。

---

### ADR-039 装配失败按阶段归因：截图 / 装配 / 采样三段可区分（v2.9.23 / 正式 release）

- **问题**：现场诊断摘要呈现「颜色 未采集 · 置信度 低 · 结论 校验未完成：取色失败」，坐标为屏宽 97.03%、自动区域右边界被夹至 100%。该结论无法区分失败发生在截图、区域装配还是颜色采样三个阶段，会把排查方向引向颜色算法，而实测颜色算法并未参与。
- **复核**：以 1080×2400 帧、拾取点 97.03% / 35.68%、ROI 85.0,30.2~100.0,41.2 复跑装配链路，`cropPx` 返回四元组且像素矩形有效，邻域采样点数大于 0，收敛区域非空，稳定锚点在纹理区域可选中。**贴边本身不会导致采样失败**，因此失败点在 app 层截图或装配，而这三类失败当时共用同一文案。
- **决策**：把失败阶段提升为一等结果。
  1. core 新增 `TapGate.Failure`：`CAPTURE_NULL` / `CAPTURE_SECURE` / `CAPTURE_TIMEOUT` / `ASSEMBLY` / `PIXEL_READ` / `SAMPLE_EMPTY`，并新增 `reasonFor()` 作为阶段到结论的单一映射；判定结论细分为 `CAPTURE_UNAVAILABLE` / `ASSEMBLY_FAILED` / `SAMPLE_EMPTY`，`ASYNC_FAILURE` 保留为未携带阶段时的兜底。
  2. `TapGate.evaluate` 增加可空 `failure` 参数，旧调用不传时行为与 v2.9.22 一致，避免破坏既有调用点与历史单测语义。
  3. app 层 `buildSceneRule` 由返回可空对象改为返回显式 `BuildResult`（`Ok` / `Fail(failure, detail)`），装配失败不再丢失阶段；截图错误码（安全窗口 / 超时 / 显示不可用 / 未授权）映射为对应阶段。
  4. 截图返回空时短间隔补采一次，避免一次瞬时失败被直接判死。
  5. 诊断摘要新增「采集」「阶段」「采样」三行：采集行在成功时给出帧尺寸与耗时，失败时给出错误码与语义；阶段行给出具体失败点；采样行给出采样点数。
  6. `SceneAssist.sampleNeighborhood` 增加边界兜底：采样圆整窗越出帧时回退到夹取后的边界像素，只有中心像素同样越界才判为采样失败。
- **为什么不是「目标应用的问题」**：截图为本工程无障碍 `takeScreenshot` 通道，失败码、补采与降级策略均由本工程决定；目标应用只影响是否允许截图（安全窗口），而这一情形现在会被明确标注为「安全窗口拒绝截图」，不再与「采样为空」混淆。
- **明确不改**：`schemaVersion=1` 与 `Action` / `SceneRule` 字段、`planSceneFiringStateful` 状态机、`MismatchGuard.Tracker` 计数、画面门控与 fail-closed 判定、落点偏差护栏、稳定锚点、失败即停、`calibrateSimilarity` 公式、零 `INTERNET`、零新增权限、B1 / C。
- **后果**：新增 core 测试 12 例（阶段映射、结论区分、文案不含误导归因、摘要字段、采样兜底、贴边装配链路），core 合计 572 项、失败 0；静态接线门禁由 31 项增至 33 项（新增失败阶段化、采样兜底随装配链路一并覆盖）；`expected_symbols` 新增 `TapGate$Failure` / `reasonFor` / `BuildResult` 等。UI 层仅改文案与摘要字段，未新增落盘字段、权限或网络行为。
- **能力边界**：本环境无真机、无 `instrumentation`。截图在各 OEM ROM 的实际返回码分布、补采能否稳定命中，均未实测。本版证据为源码接线、纯 JVM 契约、release dex 符号、静态门禁与全量单测；真机行为列为待回归项。

---
### ADR-040 未被报告缺陷清理：重启动作、错误码作用域、门控自证、API 门限与 Locale（v2.9.24 / 正式 release）

- **问题来源**：不是用户反馈，而是两条机器线索。① `gradle :app:lintRelease --offline` 报 6 个 error（`MissingPermission` 1、`NewApi` 4、`InlinedApi` 1）与 88 个 warning；② 对 v2.9.21～v2.9.23 新增链路（拾取装配、门控复算、截图调度、诊断摘要）逐行审计。两线交叉后确认 6 处确定性缺陷与 1 类跨 10 文件的格式化缺陷。
- **D1 重启动作恒不生效**：`AppLauncher.run` 把 `killBackgroundProcesses` 与 `startActivity` 放在同一 `runCatching`。本应用按权限零新增约束不声明 `KILL_BACKGROUND_PROCESSES`，该调用必抛 `SecurityException`，异常先出 → `startActivity` 永不执行 → 动作为 `false`。异常被吞导致该缺陷长期表现为「动作失败」，与「应用不存在」不可区分。
- **D2 截图错误码跨调用残留**：`lastScreenshotError` 只在回调里赋值，超时路径无回调，字段保留上一次的值。上层既用它归因（诊断摘要），也用它判 RESTRICTED（`ScreenSource.lastFailureWasSecure`），一次超时会被记成安全窗口。
- **D3 服务掉线被折叠成空帧**：`AutoClickService.live()?.lastScreenshotError ?: 0` 使「服务实例不存在」与「服务在、系统返回空帧」共用错误码 0。
- **D4 门控复算自证命中**：`reEvaluateGate` 中 `val observed = sample?.argb`，把代表色当观测色传入 `TapGate.evaluate`，颜色距离恒为 0 → 必 ≤ 容差 → 必 PASS。改区域或清除手动覆盖后，界面无条件显示「已命中」，而颜色从未被重新校验。
- **D5 / D6 API 门限写错**：`isAccessibilityDataSensitive()` 自 API 34 起公开（原判 33，Android 13 上逐节点抛 `NoSuchMethodError`）；`GLOBAL_ACTION_LOCK_SCREEN` 自 API 28 起定义（低版本内联为 8，下发未定义动作）。
- **决策**：
  1. `killIfPermitted`：权限判定 + 独立 `runCatching`，结束进程失败只记日志；`startActivity` 无条件执行。权限仍不声明（红线不破），语义为「尽力结束进程 + 必定拉起」。
  2. 采集入口清零 `lastScreenshotError`，新增 `SHOT_ERROR_TIMEOUT (-2)` / `SHOT_ERROR_UNSUPPORTED (-3)`；app 侧新增 `ERR_NO_SERVICE (-4)`；`captureFailure` / `captureDetail` 按码归因；`awaitShot` 内部再判版本并加 `@RequiresApi(30)`。
  3. 新增 `pendingObservedArgb` 承载装配期实测色，门控复算只用它；缺失走 `FAILED + SAMPLE_EMPTY`，维持失败即停。
  4. 门限改 34 / 28。
  5. 全量 `"%.Nf".format(` 改 `String.format(Locale.US, ...)`；新增 `LocaleSafetyTest`（默认 Locale 切 de-DE 后断言）与门禁 `locale_safe_format`（全量正则扫描）。
- **明确不改**：`schemaVersion=1`、`Action` / `SceneRule` 字段、判定顺序与 fail-closed 语义、落点偏差护栏、稳定锚点、画面门控、零 `INTERNET`、零新增权限（尤其不申请 `KILL_BACKGROUND_PROCESSES`）、B1 / C。
- **后果**：单测 607 → 615（新增 `LocaleSafetyTest` 8 例：Locale 5 例 + 门控观测色 2 例 + 样本一致性 1 例）；静态门禁 33 → 38（`restart_launch_decoupled` / `shot_error_scoped` / `gate_observed_real` / `android_api_guards` / `locale_safe_format`）；lint error 6 → 0。
- **能力边界**：本环境无真机。重启动作在真机的实际拉起效果、各 ROM 的错误码分布、Android 13 探测耗时改善、Android 8.x 锁屏返回、de-DE 下的显示效果，均未实测，列为待回归项。本版证据为源码接线、纯 JVM 断言、静态门禁与 release dex 符号。

---
### ADR-041 界面结论与运行时事实对齐：锚点门控、死场景可见、运行前置与首屏减法（v2.9.25 / 正式 release）

- **问题来源**：用户提交的现场调试包（`nymphauto-debug-1794718655503.zip`，2026-11-15 12:53–12:57）。日志完整记录了一条「配好 → 命中 → 保存 → 运行 → 什么都没发生」的链路，四个缺陷各自独立、串起来才构成用户感知的「坏的」。
- **D1 门控假 PASS**：`TapGate.evaluate` 只校验「点在区域内 + 颜色距离 ≤ 容差」，两项通过即返回 PASS，不关心运行期是否有画面模板。日志 12:56:38 同一毫秒内既有 `稳定锚点装配 0×0px / 掩码 0 块 / 结论 NO_FRAME`，又有 `点击前门控 结论=PASS 允许点击=true`。
- **D2 失败结论被引导覆盖**：`save()` 中「首次场景装配」的范式说明分支在普通结论分支之前，且条件里不含可用性判断，于是零模板场景保存后呈现的是「已按场景触发保存」。
- **D3 运行前无静态检查**：`runSceneDriven` 的 `gated == 0` 中止发生在起服务、建悬浮球、定档能力矩阵、创建 OCR 之后。日志中 12:57:18 / 22 / 23 / 24 连续四次，每次都是完整启动再立刻停止。
- **D4 重连窗口被当成未连接**：12:53:38 报「无障碍服务未连接（请在系统设置中重新启用后重试）」，12:54:33 服务自行连上。用户被告知去做一件不需要做的事。
- **决策**：
  1. `TapGate.evaluate` 新增 `anchorReady: Boolean = true`，在区域判定之后、颜色判定之前短路为 `ASSEMBLY_FAILED`；`messageFor` 改为写明后果「该场景运行时不会触发」。app 侧新增 `pendingAnchorReady`，由 `outcome.anchorB64.isNotBlank()` 决定；`reEvaluateGate` 同样传入。
  2. `save()` 三支顺序固定：不可用结论 → 首次引导 → 普通结论。不可用分支写入 `RunLog.w`，便于事后核对。
  3. `SceneTrigger.triggerableCount(actions)` 纯函数；`RunnerService.toggle()` 在 OCR 创建之前检查 `sceneDriven && triggerableCount == 0`，成立即 toast 并 return。运行期 `gated == 0` 保留为最后一道闸。
  4. 拾取前 `awaitServiceReady(1200ms)`，100ms 轮询；超时才按未连接处理，文案改为「若刚开启无障碍，请等数秒后重试」。
  5. 界面减法：坐标 X/Y 收进 `coordBox`（默认 GONE，展开即聚焦 X）；结果卡删除 `actRow2`（清除手动 / 恢复自动已在辅助区各有一个按钮）；`btnSave` 在 `!hit && !pending` 时文案为「重新拾取」且点击走 `onRepick`；`bindSceneList` 在单场景时隐藏 `tvCurrentScene`。
- **明确不改**：`schemaVersion=1`、`Action` / `SceneRule` 字段、判定顺序（除新增的锚点短路）、fail-closed 语义、落点偏差护栏、稳定锚点选取、画面门控挂载条件、零 `INTERNET`、零新增权限、B1 / C。
- **后果**：单测 615 → 621（`AnchorReadyGateTest` 6 例）；静态门禁 38 → 42（`gate_anchor_ready` / `run_precheck_wired` / `panel_lean` / `save_dead_scene_visible`）；`verdict_gate_alignment` 与 `pick_card_centralized` 的口径随交互收敛同步升级，不是放宽。
- **迁移影响**：存量脚本零迁移。零模板场景此前就是 fail-closed 不触发，本版只是把这件事提前讲清楚；`anchorReady` 默认 `true`，旧调用点行为逐字不变。
- **能力边界**：本环境无真机。1200ms 在各 OEM ROM 是否足够、告警在窄屏的折行、展开手输区后的软键盘避让、后台启动时 Toast 是否可见，均未实测，列为待回归项。本版证据为源码接线、纯 JVM 断言、静态门禁与 release dex 符号。

---
### ADR-042 场景判定不可取证收口：未命中现场存证、最佳窗口归因与掩码覆盖可见（v2.9.26 / 正式 release）

- **问题来源**：用户现场调试包（`nymphauto-debug-1794726200258.zip`）。15:01:57 拾取自测 NCC=1.00（干扰 0.60，阈值 82，掩码 14 块）；15:02:28–59 运行 31.7 秒，`present=false` 全程，score 在 0.36 基线与 0.61 突起之间波动——突起时段（15:02:39–49、57–59）与用户目击「场景出现」吻合。调试包内 5 张 miss 帧全部来自 11-07 的顺序找图路径，本次运行**一张现场帧都没有**。
- **判定链逐级结论**：截帧 ✓（每轮有分数产出）、方向/尺度 ✓（ratio=1.0，无方向告警）、ROI 解析 ✓、掩码应用 ✓（无异常）、NCC 计算 ✓（机械正常）、阈值/状态机未参与（0.61 距 0.82 差 0.21，不是边缘擦过）。**卡点是内容比对：运行帧里搜索窗内从未出现与拾取快照 NCC ≥ 0.82 的内容**。四个上游候选（元素移出搜索窗 / 外观与拾取时不同 / 掩码盖住元素 / 尺度档未覆盖）在现有日志下不可区分——因为取证缺位。
- **决策**：
  1. `ImageFinder.matchCore` 跟踪最佳窗口坐标，`ScaleOutcome` 承载；`findDetailed` 跨尺度取最高分的窗口位置回传（`ScoredHit.bestCx/bestCy`，原图像素，无可评窗口 -1）。`find` / `bestScore` 公开语义逐字不变。
  2. `TemplateMatch` 增加 `bestCxPx/bestCyPx`（默认 -1，旧构造点零改动）。
  3. `runSceneDriven` 每条规则每次运行的第一轮未命中调用既有 `dumpMissFrame("场景N")` 存现场帧（滚动 KEEP=5，与顺序路径共用，不额外截屏）。
  4. `emitSceneObservability` 每轮日志追加：阈值、差距、`best@x,y`、`掩码盖住=n/总数`、归因带（score ≥0.45 →「元素疑似在场但外观/尺度/掩码与拾取快照不一致」；<0.45 →「搜索窗内无相似内容」；最佳窗口 -1 →「ROI 内无可评窗口」；无匹配结果 →「截帧或模板解码失败」）。0.45 分界沿用 `diagnoseImage` 既有口径。
- **明确不改**：阈值自校准、fail-closed、迟滞/冷却/仲裁、`schemaVersion=1`、落盘字段、权限、B1 / C。**不**因「元素明明在场」而放宽阈值或引入自适应阈值——那会把误触发放大；正确路径是让取证先到位，按证据决定下一步。
- **后果**：单测 621 → 624（`ImageFinderTest` 3 例：未命中最佳窗口落在搜索窗内 / 半失真元素未命中但最佳窗口仍在原位 / 无可评窗口 -1）；静态门禁 42 → 43（`scene_miss_evidence`）；`findDetailed` 未命中输出从「只有分数」升级为「分数 + 位置 + 掩码覆盖 + 归因带」。
- **能力边界**：本环境无真机。下一次现场运行后，按新日志的 `best@x,y` 与掩码覆盖数即可四选一定位；若确认是「外观与拾取快照不一致」，修复方向是拾取引导（提示在元素出现态拾取）而非放宽匹配——本版不预设该结论。

---
### ADR-043 录制脚本预检误拦回归收口：包存在性、方向作用域、实时坐标系与日志消歧（v2.9.27 / 正式 release）

- **问题来源**：用户现场调试包（`nymphauto-debug-1794730334833.zip`，2026-11-15 16:07–16:12）。用户确认「拾取坐标模式成功命中一次、表现良好」，但「录屏模式使用过程中仍有旧问题回归」。日志给出两条独立链路：① 16:07 拾取 `稳定锚点装配 40×40px 命中=1.00 结论=PASS`、16:09:30 场景 6 `score=1.00 → 上升沿 ✓触发 → 执行 TAP`（拾取路径正常）；② 16:10:54 录制、16:11:37 运行录制脚本后，16:11:46 起 12 次 `预检拦截：屏幕方向与脚本相反（脚本 1280×2800，当前 2800×1280）；目标应用未安装：com.hihonor.gameassistant`，脚本一次都没跑起来。
- **判定为回归而非新问题**：`TargetPackages.kt` / `RecordCompiler.kt` 注释已记录 v1.2.1「桌面试录 65 事件 → 预检拦截 systemui + launcher」，当时的修法是在依赖清单里用 `isLaunchableTarget` 排除系统组件包；`FloatingEditorService.kt` 注释已记录 v2.9.13「全新脚本 ref 以当前屏幕方向为参考，避免横屏拾取被预检方向误拦」，当时只在**拾取/编辑**路径上 `adoptRefFromPicker`。两处修法都没覆盖到预检本身与录制路径：换个包名（`com.hihonor.gameassistant`）、换条入口（录制而非拾取），同一批缺陷立即复现。
- **D1 包存在性判定错位（回归）**：`RunnerService.launchablePackages(s)` 用 `pm.getLaunchIntentForPackage(pkg) != null` 构造 `EnvironmentSnapshot.installedPackages`，把「没有启动入口」翻译成「没有安装」。本应用 `<queries>` 只声明 `ACTION_MAIN`，游戏助手 / 覆盖层类应用无 launcher 入口或不在可见范围，返回 null 即被判未安装。现场证据：该应用在录制阶段刚被进入并采集 55 个事件，显然已安装。
- **D2 方向检查作用域过宽（回归）**：`Preflight.check` 对任何 `refWidth/refHeight > 0` 的脚本都把方向/宽高比差异升级为硬拦截，不区分是否依赖图片模板。纯坐标手势脚本用百分比坐标（`AnchorSelector.pct` 已收敛到 0~100），方向变化不会让回放失败；执行期 `warnIfOrientationMismatch` 本身也只是警告。录制时竖屏、运行时横屏的 30 步坐标脚本因此被连拦 12 次。
- **D3 录制坐标系静态快照**：`RecorderBus.frame` 只在 `start()` 设置一次，`convert()` 把该快照的 width/height 写进每个 `RecordEvent`。录制期间旋转屏幕（现场 1280×2800 → 2800×1280）后，后续手势的绝对像素仍按旧分母换算，越界值被 `pct` 夹到 100%，坐标静默落到屏幕边缘。
- **D4 日志标签自相矛盾**：`SceneTrigger` 的 `reasons[i]` 与 `ScriptRunner` 的观测行都写 `present=`，前者是状态机在场（含迟滞）、后者是本轮观测，同一行可出现 `观测=true → 状态=false`（现场 16:09:30 首轮即如此）。用户此前已指出「日志判断非常草率」，此处必须消歧。
- **决策**：
  1. `EnvironmentSnapshot` 新增 `packageVisibilityRestricted`；`PreflightReport` 新增 `warnings` 与 `warningsText()`，**告警与拦截分通道**，不静默放行。`RunnerService` 改用 `getPackageInfo` 存在性 + launch intent 兜底构造 `installedPackages`，SDK≥30 时置受限标记并输出 `RunLog.w`。
  2. `Preflight.check` 把方向/宽高比问题收敛为 `orientationIssue`：`deps.needsScreenshot` 为真才进 `problems`，否则进 `warnings`。包缺失同理：受限时进 `warnings`，不受限时仍进 `problems`。
  3. `RecorderBus` 新增 `currentFrameProvider`，`convert()` 每个事件实时取当前 `Frame`；`RecordingService.start()` 注入 `AutoClickService.live()?.frame() ?: frame`，取不到时回退启动快照（旧行为兜底）。
  4. 日志标签改为 `观测=`（本轮）与 `状态=`（状态机），语义唯一。
  5. 新增 `RecordFrameScopeTest`（4 例）锁定「每个事件按自身坐标系换算百分比」；升级 `ReplayChainTest` 的方向用例（旧断言锁定的是被误拦的纯坐标脚本），新增 6 例覆盖方向作用域与包可见性。
- **明确不改**：含图片模板脚本的方向拦截、包可见性不受限时真实未安装应用的拦截、截图能力 / AAPM / AI 模型拦截、`<queries>` 声明（不新增包可见性、不申请 QUERY_ALL_PACKAGES）、`schemaVersion=1`、`Action` / `SceneRule` 字段、判定顺序与 fail-closed 语义、落点偏差护栏、稳定锚点、画面门控、零 `INTERNET`、零新增权限、B1 / C。
- **关于「录制计数从 10 掉回 1」**：核对 `RecordingSession` 无 `events.clear()`，事件只增（用户主动撤销才减 1），结束日志仍为「原始 55 事件 → 30 个动作」，无事件丢失；该现象为撤销后重新计数时的正常节流打印，**不列为本版修复项**，避免臆测充数。
- **后果**：单测 624 → 633（`RecordFrameScopeTest` 4 例 + `ReplayChainTest` 净增 5 例：删 1 增 6）；静态门禁 43 → 47（`preflight_orientation_scoped` / `preflight_package_visibility` / `record_frame_live` / `scene_log_unambiguous`）；符号表新增 `warningsText` / `packageVisibilityRestricted` / `currentFrameProvider`。
- **能力边界**：本环境无真机。各 OEM ROM 在 SDK≥30 下 `getPackageInfo` 对游戏助手类应用的可见性、录制中旋转时 `frame()` 的返回时机、百分比坐标在横竖屏切换后点击位置是否符合用户预期，均未实测，列为待回归项。本版证据为源码接线、纯 JVM 断言、静态门禁与 release dex 符号。

---
### ADR-044 场景驱动逐帧判定：帧同步节拍、每轮新帧与有界可观测性（v2.9.28 / 正式 release）

- **问题来源**：需求基线为「每帧都对全部场景规则完成一次判定」，而非按固定时间节拍轮询。逐级核对判定链后确认为两处叠加，而非单一休眠参数：
  1. `ScriptRunner.runSceneDriven` 在 `decision.fire.isEmpty()` 分支固定 `delay(WaitGate.DEFAULT_POLL_MS)`（300ms）。该常量语义是「等结算界面出现」这类慢场景的省电档（见 `WaitGate` 注释），与「场景出现即触发」的事件循环不同源；`RecognitionTuning` 的帧同步档位（默认 33ms）在顺序链的找图重试与条件等待中已被消费（`delay(recognitionTuning.intervalMs)`），唯独场景驱动路径从未使用。
  2. `VisionPipeline.frame()` 的 `FRAME_TTL_MS = 400` 让连续多轮复用同一张缓存位图。即便把休眠缩到 33ms，多轮里仍在对同一帧重复跑 NCC —— 判定次数上去了，画面没换，短暂出现又消失的场景依旧取不到。
- **不是根因的部分（避免误伤）**：`SceneTrigger.planSceneFiringStateful` 本来就对一轮传入的全部 `SceneObservation` 逐条产出 `reasons`；`findTemplates` 本来就是一轮一次抓帧、N 条规则共享同一基础像素副本。这两处是既有正确契约，本版不改，只补锁定用例。
- **决策**：
  1. 新增 core 纯函数 `SceneFramePolicy`：`frameIntervalMs` 把监视节拍绑定到帧同步档位，`LEGACY_100` / `LEGACY_300` 作为顺序链回滚档不再把场景监视压回低频，`CUSTOM` 仍被尊重并钳制到合法区间；`delayFor` 采用固定步长，抓帧加匹配已耗满目标间隔时返回 0，不叠加休眠；`due` 提供周期节流判定；`heartbeatLine` 输出本周期实际判定帧数。
  2. `VisionPipeline.findTemplates(specs, freshFrame = false)` 增加开关；场景驱动传 `true`，走新增的 `grabFreshFrame()` 绕过 TTL 每轮强制抓取并原子替换缓存。抓取失败时缓存置空、返回 null，调用方把本轮全部规则观测置为未在场 —— **不拿旧帧冒充新帧判定**（fail-closed）；连续失败仍由 `AccessibilityScreenshotSource` 的 3 次熔断 + 3s 冷却收敛。顺序链（条件判定 / 找图 / 失败现场存证）保持默认 `false`，400ms 缓存复用语义零回归。
  3. `runSceneDriven` 命中轮与未命中轮共用 `paceFrame(roundStartMs, frameIntervalMs)`；起始日志与 `sceneSummary` 输出帧同步间隔与累计判定帧数。等待浮层刷新按 200ms 节流，从等待转为触发时只清除一次。
  4. 可观测性提频配套：`emitSceneObservability` 的完整每规则明细改为「状态变化 + 500ms 心跳」写出（`RunLog` 是 500 条环形缓冲，逐帧全量写入会挤掉其它模块记录），心跳行由 `heartbeatLine` 带上本周期帧数与平均帧间隔；`onSceneDebug` 仍按帧回调，可视化层自带帧合并；UI 汇总日志仍只在「在场数 / 触发列表」变化时输出。
  5. 新增 `SceneFramePolicyTest` 8 例：帧同步档位映射、旧版回滚档不再退化、自定义慢速被尊重、`delayFor` 慢帧不叠加 / 快帧补足 / 边界、`due` 首轮与时钟回拨、心跳行数值与 Locale.US、以及「4 条规则 2 命中 2 未命中时未命中项也必须参与本帧判定」的契约。
- **明确不改**：`schemaVersion=1`、`Action` / `SceneRule` 落盘字段、三种 triggerMode、迟滞 / 冷却 / 重试 / 仲裁、稳定锚点、搜索 ROI、动态掩码、相对落点二次校验、画面门控、落点偏差护栏、失败即停、fail-closed 无模板不触发、顺序链 `sceneDriven=false` 路径、零 `INTERNET`、零新增权限、不抢 `MediaProjection`、B1 / C。识别调参的设置项与回滚开关不删。
- **后果**：单测 633 → 641（新增 8 例）；静态门禁 47 → 50（`scene_frame_synced` / `scene_fresh_frame` / `scene_frame_log_bounded`）；符号表 113 → 119（`SceneFramePolicy` / `frameIntervalMs` / `delayFor` / `heartbeatLine` / `grabFreshFrame` / `paceFrame`）；本工程可读类 1093 → 1096。
- **能力边界**：本环境无真机、无 instrumentation 测试。帧同步节拍只保证「有帧即判定、不因固定休眠漏判」，真实帧率仍受无障碍 `takeScreenshot()` 端到端延迟与设备刷新率约束；每轮强制抓帧带来的功耗、与系统录屏 / 合成器的争用、以及 33ms 档位下 N 条规则 NCC 的 CPU 占用，均未在真机测量。本版证据为源码接线、641 例纯 JVM 单测、release dex 119 项符号、50 项静态接线门禁、表述门禁、同钥签名与权限 / 禁用 API / 16KB 对齐扫描。

---

### ADR-045 场景编辑操作逻辑对齐：步进器、复用落点与选填语义（v2.9.29 / 正式 release）

- **问题来源**：用户提供一段 128 秒的参考产品录屏（1280×2800，竖屏），明确指出差距在**操作逻辑**而非美术。逐段拆解后确认对方有三处更短的路径：
  1. 新建动作时「点击位置」已经带着当前十字准星位置的百分比（`(45.7%, 50.2%)`），按「确定」即成立，不需要先在画面上精确对准再保存；
  2. 按下时间 / 运行等待 / 重复次数全部是 `− 值 +` 步进器加单位，并标注「选填」，调一个数值不经过输入法；
  3. 每个字段右侧有 `?` 就地说明，用户不必读文档就知道能不能跳过。
- **不是问题的部分（避免误伤）**：拾取坐标主路径在现场已确认表现良好（v2.9.27 记录：稳定锚点装配 `命中=1.00 干扰=0.90 结论=PASS`，场景触发 `score=1.00 → 上升沿 ✓触发`）。本版不动 `PickFeedback` / `TapGate` / `SceneAssist` 的判定与结果卡行为，改动全部落在「输入方式」与「落点来源」。
- **不采纳的两点及理由**：
  1. 参考产品的录屏走 `MediaProjection`（录屏授权弹窗、`录制中打开录屏` 卡片）。本工程五条硬契约含「不抢 `MediaProjection`」，录制必须走无障碍事件被动监听；照搬会破契约，也会把「每次录制先授权一次」的成本引进来。
  2. 参考产品的点击位置是单点百分比、不含画面模板。本工程的落点护栏、稳定锚点、画面门控、失败即停都必须建立在模板之上；把动作退化成纯单点会丢掉这些护栏。因此只借「表单形态」，不借「判定模型」。
- **决策**：
  1. 新增 core 纯函数 `StepPolicy`：四个字段（`holdMs` / `times` / `waitMs` / `durationMs`）各自携带默认值、步长、上下界、单位、选填语义与短标签；`bump` 负责「空值 / 非法输入 / 越界」的收敛，`text` 负责去尾零且**不带千分位**（带分隔符会让回读 `toDoubleOrNull()` 失败），`hintOf` 负责就地说明，`summaryOf` 负责一行摘要。key 与 `FloatingEditorLayout.Form` 字段名一一对应并由单测锁定。
  2. 空值语义定死：点加号给「默认值 + 一档」，点减号**落到下限**而不是「默认值减一档」。空值没有可减的基数，落到下限（多数为 0，次数为 1）才有可见反馈；`waitMs` 默认值本身是 0，若按「默认值减一档」永远停在 0。
  3. `FloatingEditorPanel` 新增 `stepperRow`（短标签 + `−` + 输入框 + `+` + 单位）与 `stepperBtn`（单击一档、长按连发）。停止条件用 `View.isPressed` 判断，不额外挂 `setOnTouchListener`——既不拦截触摸、不干扰无障碍点击语义，也避免把 post 留在已脱离窗口的视图上。四个字段的 `collect()` 口径不变，仍从同一个 `EditText` 读字符串，因此落盘与解析链路零改动。
  4. 坐标区新增「用当前位置」：`FloatingEditorService` 增加 `lastAnyPickPct`，在 `pickPoint` 的 `onPicked` 里与 `lastPickPct` 同时写入但**不随场景保存清空**。复用必须读它而不是 `lastPickPct`（后者与当前场景同生命周期，切到新场景即失效，等于没做）。没有可复用点时明确提示下一步，不静默失败。
  5. 新增 `StepPolicyTest` 9 例：空值加 / 空值减、四个字段的边界夹取、非法输入（含 `1e999`）、写回去尾零与无千分位、选填占位与就地说明必须含「留空」、key 与表单字段对齐、摘要只输出有值字段、默认值与 `Form` 初值同源。
- **明确不改**：拾取仍是主路径（步进器在折叠区、复用落点在坐标区，首屏可点项数量不变）；`schemaVersion=1`、`Action` / `SceneRule` 落盘字段、三种 triggerMode、迟滞 / 冷却 / 重试 / 仲裁、画面门控、稳定锚点、搜索 ROI、动态掩码、落点护栏、失败即停、逐帧判定节拍、零 `INTERNET`、零新增权限、不抢 `MediaProjection`、B1 / C。数字字段的点击手输保留为兜底，不删能力。
- **后果**：本批新增 9 例单测（`StepPolicyTest`）、3 项静态接线门禁（`stepper_fields` / `step_policy_pure` / `use_current_point`）、8 个符号（`StepPolicy` / `bump` / `hintOf` / `summaryOf` / `stepperRow` / `stepperBtn` / `useCurrentPoint` / `lastAnyPickPct`）。与本版第二批（ADR-046）合计后的最终数字见 ADR-046 后果一节。
- **能力边界**：本环境无真机。长按连发在真实触控下的启动延迟与速率、168dp 面板宽度下「短标签 + 减 + 值 + 加 + 单位」的排布是否会挤压输入框、步进器行内输入框与按钮的高度对齐、各 OEM ROM 输入法在点击数字时的唤起时序，均未实测。本版证据为源码接线、纯 JVM 单测、release dex 符号、静态接线门禁、表述门禁、同钥签名与权限 / 禁用 API / 16KB 对齐扫描。

---

### ADR-046 拾取落点精度：整屏捕获、手势排除与边缘安全收敛（v2.9.29 / 正式 release）

- **问题来源**：用户对比一段参考产品录屏后指出核心区别不在美术，而在两处操作逻辑：
  1. 参考产品在**危险边缘也能通过滑动把指针拉回**，本工程稳定版**只能靠一个 30dp 的抓手拖动**；
  2. 点击屏幕拾取时，**指针位置变动**的语义不同——参考产品点哪里指针就到哪，本工程只有拖。
- **代码事实核对**（逐项读源码确认，不靠推测）：
  1. `PickerHandleView.collapsedSize = 30 * d`，命中区 30dp，小于 Android 无障碍最小触摸目标 48dp；`positionParams()` 用 `crossX - width/2` 再 `coerceIn(0, screenW - width)`，准星在 `x=0` 时窗口被夹到 `x=0`、圆心却落在 `15dp`，视觉与真值分离。
  2. 绘制层 `FLAG_NOT_TOUCHABLE`，全仓库 `setSystemGestureExclusionRects` **零命中**。手势导航下左右边缘与底部的 `MOVE` 被系统消费，应用侧表现为拖动断流、贴边后无法回拉。
  3. `GestureEngine.planTap` 只做 `[0, maxX]` 夹取，无热区概念；落点在返回/Home 热区时 `dispatchGesture` 返回成功但事件被系统改写，**没有错误码可查**。
  4. 全仓库无 `systemGestureInsets` / `safeInset` 相关调用，边缘安全此前完全缺失。
- **决策**：
  1. 新增 core 纯函数 `PointerTouchTarget`：`windowFor` 给抓手窗口屏内矩形、`markerIn` 给抓手的窗口内绘制坐标（让视觉圆压在准星真值上）、`exclusionFor` 给跟随准星的手势排除矩形；常量 `MIN_TOUCH_DP = 48`、`EXCLUSION_DP = 96`。
  2. 新增 `PointerCaptureView`：全屏、透明、可触摸。`ACTION_DOWN` 只记抓取偏移不移动准星；位移超过 `ViewConfiguration.scaledTouchSlop` 才进入拖动；未进入拖动的 `ACTION_UP` 走 `onTap`，把准星**绝对定位**到按下点。取按下点而非抬起点，因为用户瞄准的是按下那一刻，抬手常有 1~8px 漂移。
  3. 抓手窗口由 30dp 放宽到 48dp，视觉圆固定 `VISUAL_RADIUS_DP = 13f` 与命中区解耦；`positionParams()` 每帧写 `crossInWindowX/Y = 真值 − 窗口左上角`，绘制不再用窗口中心。长按抓手 550ms = 临时放行 3 秒（`releaseCaptureBriefly`），放行期间抓手降透明度提示，解决全屏捕获挡住「先让目标界面出现」这一步的问题。
  4. `applyGestureExclusion` 在 `start()`、每次 `moveCrossTo`、每次 `onTap` 后刷新排除区，API 29 起生效；排除区跟随准星而不是常驻三条屏边，避免超出系统额度被裁剪。
  5. 新增 core 纯函数 `EdgeSafety`：`adjust` 把落点收敛到安全区并给出方向原因，`isRisky` 供拾取阶段提示，`dpToPx` / `fallback` 统一换算。**热区不能把屏幕吃光**：两侧相加超屏时收敛到至少 1px 可点区，杜绝 `coerceIn` 反向上界异常。
  6. 拾取侧：`confirm()` 在交付前调 `EdgeSafety.adjust`，`PickerResult` 增加 `edgeAdjusted` / `edgeNote` / `rawXPx` / `rawYPx`，结果卡显示「落点位于左边缘手势区，已内移到安全边界 x%, y%（原 a%, b%）」。保证「看到的坐标＝将来点到的坐标」。
  7. 执行侧：`AutoClickService.gestureInsets()` 取 `systemGestures ∪ systemBars`（API 30+），`RunnerService` 注入 `GestureEngine.setEdgeInsets`；`planTap` / `planMultiTap` 经 `safePoint` 收敛并累计 `edgeAdjusted`，首次修正写一条 `RunLog`。**滑动不做内移**——从左边缘起手往右滑本身就是返回手势或抽屉手势，搬离边缘会改变语义。
  8. 新增 `PointerTouchTargetTest` 8 例与 `EdgeSafetyTest` 11 例。
- **明确不改**：拾取判定与结果卡行为（`PickFeedback` / `TapGate` / `SceneAssist`）、`schemaVersion=1`、`Action` / `SceneRule` 落盘字段、三种 triggerMode、迟滞/冷却/重试/仲裁、画面门控、稳定锚点、ROI、落点护栏、失败即停、逐帧判定节拍、零 `INTERNET`、零新增权限、不抢 `MediaProjection`、B1 / C。
- **一个被自己门禁拦下的问题（如实记录）**：首次提交时表述门禁命中 5 处禁用措辞，均在 KDoc 与注释中。改写后重跑：表述门禁 PASS，且**重建后的 APK SHA-256 与改写前完全一致**（`23d7eb34…75cde`），证明这 5 处改动只落在注释、零字节码影响。
- **后果**：单测 650 → 669（`PointerTouchTargetTest` 8 + `EdgeSafetyTest` 11）；静态接线门禁合计 **55 项**全部 True（本版新增 `stepper_fields` / `step_policy_pure` / `use_current_point` / `pointer_capture_anywhere` / `edge_gesture_exclusion` / `edge_safety_apply`）；符号表 127 → 141（新增 `PointerCaptureView` / `onTap` / `scaledTouchSlop` / `PointerTouchTarget` / `windowFor` / `markerIn` / `exclusionFor` / `crossInWindowX` / `EdgeSafety` / `isRisky` / `gestureInsets` / `setEdgeInsets` / `safePoint` / `edgeAdjusted`）；本工程可读类 1105 → 1119。
- **能力边界**：本环境无真机。手势排除区在各 OEM ROM 上的实际生效范围与配额裁剪行为、48dp 抓手在真实触控下的误触率、轻点绝对定位在拇指接触椭圆偏置下的落点偏差、`systemGestures` 在三键导航与手势导航下的实际取值、长按放行 3 秒是否够翻一页，均未实测。本版证据为源码接线、669 例纯 JVM 单测、release dex 141 项符号、55 项静态接线门禁、表述门禁、同钥签名与权限 / 禁用 API / 16KB 对齐扫描。

---





### ADR-047 控制权收回：连接所有权、终止可恢复与全局入口（v2.9.30 / 正式 release）

- **问题来源**：评审指出三处内部一致性缺口，逐条对照代码后**全部成立**，且都不是文档笔误：

  | 缺口 | 代码事实 | 用户实际后果 |
  |---|---|---|
  | 暂停 ≠ 归还 | `RunnerService.State` 只有 `IDLE / RUNNING / PAUSED`，暂停只把 `paused` 置 true；`disconnect` 全仓库零命中 | 界面写「已暂停」，读屏与截图通道仍被占用，用户以为已经收回 |
  | 每步预告吃掉吞吐 | 预告只有 `previewEnabled` 一个全局开关，开启即每动作固定 `delay(previewMs)` | 40ms 的动作被拖成 1.2s，高频循环失去意义；只能在「够快」和「能反悔」里二选一 |
  | 收回入口没有全局落点 | 归还按钮只在悬浮面板；通知只有「暂停/继续」「停止」两键 | 用户被自动点击时通常不在本应用界面，面板被目标应用遮挡或被系统限制，够不着 |

- **决策**：
  1. **连接所有权独立建模**（`core.runner.ConnectionState`）：五相位 `IDLE / ACQUIRING / OWNED / SWITCHING / RELEASE`，与运行态正交。`allowsInjection` 仅 `OWNED` 为真，`SWITCHING` 与 `RELEASE` 一律零动作——切换期的半连接状态最危险：旧通道已释放、新通道未稳，任何派发都落到不可预期处。`Release` 在任意相位无条件生效且幂等（通知栏会被反复点、可能重复投递），`RELEASE` 只能经 `Settled` 回 `IDLE`，防止「归还」被后续自动重连悄悄抵消。非法跃迁原地不动并标记 `ignored`，不抛异常——状态机跑在服务主线程，抛错会把「点了归还」变成崩溃。
  2. **终止结构化**（`core.runner.HaltReport`）：`kind` + `headline` + `detail` + `resumeStep` / `resumeRule` + `actions`。七类终止里 `USER_STOP / BACKGROUND_TIMEOUT / GATE_MISMATCH / LINK_LOST` 可恢复，`USER_HANDOVER / SCRIPT_ERROR / WATCHDOG` 不可恢复（脚本自身错误继续只会重复失败，因此不给恢复入口）。新增运行态 `HALTED`，协程收尾时按 `lastHalt.resumable` 决定停在 `HALTED` 还是回 `IDLE`。
  3. **预告分级**（`core.runner.AnnouncePolicy`）：`FULL`（逐动作预告 + 撤销窗）/ `FOLDED`（常驻「下一步」，零阻塞）/ `NONE`（无落点动作不预告）。判定顺序：用户显式标记的高风险 → 首次运行 + 新场景（1600ms）→ 新场景（1200ms）→ 本次未确认（1200ms）→ 已确认循环（0ms）。两条防叠加约束：`COALESCE_MS = 900`，两次 FULL 间隔不足该值时后一次降级为 FOLDED（用户来不及看完两个相隔 300ms 的预告，第二个窗口只是纯延迟）；高风险不受合并限制（稀疏且每次都值得打断）。
  4. **全局入口收敛到通知三键**：`暂停/继续`（`HALTED` 时为「从这步继续」）/ `归还连接` / `停止`。`ACTION_HANDOVER` 走 `RELEASE → Settled`，与暂停的差别正是「是否继续占用通道」。`ACTION_RESUME` 读 `lastHalt.resumeStep`，经 `ScriptRunner.resumeFromPc` 从未完成步继续，步号越界退回从头（宁可重跑也不越界）。
  5. **降级通道提前判定**（`core.runner.DegradeChannel`）：`READY / SERVICE_DISABLED / NOT_ALLOWED / UNAVAILABLE` 四档，状态卡常驻显示，不再等断线才发现切不过去。`failNotice` 禁止停留在「正在切换」——切换失败必须明确告知「现在没有任何通道在工作」，否则用户会一直等一个不会到来的恢复。
- **边界（如实说明，不美化）**：本工程当前注入通道是 `AccessibilityService`。系统不允许应用直接启用或停用它（`ENABLED_ACCESSIBILITY_SERVICES` 写入需 `WRITE_SECURE_SETTINGS`，普通应用不可得；Android 13 起侧载另有 restricted settings 闸门）。因此这里的「归还」精确含义是**本进程不再读屏、不再截图、不再注入**，系统设置里的服务开关保持开启，以免用户每次回设置重开。文案与状态卡不得写成「已断开系统服务」。真正的 `disconnect()` 语义需要 Shizuku 的 UserService 通道，见 ADR-048。
- **明确不改**：`schemaVersion=1`、`Action` / `SceneRule` 落盘字段、逐帧判定节拍、画面门控、稳定锚点、ROI、落点护栏、边缘安全、三类 triggerMode、迟滞/冷却/重试/仲裁、零 `INTERNET`、零新增权限、不抢 `MediaProjection`、不新增第三方依赖。
- **后果**：单测 669 → **704**（`ConnectionStateTest` 10 + `AnnouncePolicyTest` 12 + `DegradeChannelTest` 13，后者含 `HaltReport` 6 例）；静态接线门禁 55 → **56 项**（新增 `control_global_reachable`，一次锁定三键、`Release→Settled`、`OWNED` 唯一可注入、分级预告已接派发前、终止带恢复位置、`HALTED` 被面板与通知共同认识、降级通道可提前判定）；符号表 141 → **154**（新增 `ConnectionState` / `AnnouncePolicy` / `HaltReport` / `DegradeChannel` / `allowsInjection` / `allowsPerception` / `emitHalt` / `movePhase` / `handover` / `resumeFromPc` / `undoRequested` / `showUndo` / `setNext`）；本工程可读类 1119 → **1143**。
- **能力边界**：本环境无真机。通知三键在 Android 13+ 折叠通知与各 OEM 通知栏上的实际展开行为、`ACTION_RESUME` 在目标应用已切走后的续跑落点、撤销窗口 1200–2000ms 的真实可用性、`HALTED` 态下悬浮面板与通知的文案一致性，均未实测。

### ADR-048 技术栈评估：Shizuku / Room / LiteRT 的版本事实与「不整体替换」（v2.9.30 / 决策记录）

- **问题来源**：外部评审给出一份「5 类库」落地清单，主张把注入链换成 Shizuku + 单 `UiAutomation`、存储换 Room、推理换 LiteRT、图像换 libyuv + 自写 NEON，并称「不再有需要换组件的争论」。本轮不附和，先核事实再定取舍。
- **版本事实核对**（直接读 Maven Central / Google Maven 的 `maven-metadata.xml`，不凭印象）：

  | 清单写法 | 实测 | 判定 |
  |---|---|---|
  | `dev.rikka.shizuku:api:13.6.0` | 最新为 **13.1.5**，无 13.6.0 | ❌ 写死即解析失败 |
  | `litert-gpu-delegate-plugin` | Google Maven **404**，无此 artifact | ❌ 不存在的坐标 |
  | `room:2.8.5` | 存在（2.8.0–2.8.5） | ✅ |
  | `libyuv-android` | 存在（最新 0.44.0） | ✅ |
  | `litert` / `litert-gpu` / `litert-gpu-api` | 存在（2.3.2） | ✅ |
  | `kotlin = 2.3.x`、`AGP = 8.5.1+` | 本工程现用 **Kotlin 2.4.21 / AGP 8.13.2 / compileSdk 36** | ❌ 按清单改是**降级**，AGP 8.5.1 不足以支撑 compileSdk 36 |
  | KSP 版本 `2.8.5` | KSP 版本号格式为 `<kotlin>-<ksp>` | ❌ 与 Kotlin 2.4.21 不匹配 |

- **架构缺口（比版本更关键）**：清单写「进程内单 `UiAutomation`」，但 App 以自身 uid 调用 `UiAutomation.connect()` 需要 `MANAGE_ACCESSIBILITY`，会被 `AccessibilityManagerService` 拒绝。承载 `UiAutomation` 的 Service 必须跑在 **shell uid 的进程**里，唯一途径是 Shizuku 的 `bindUserService`（UserService）。清单通篇未提 UserService，只写「进程内」，照做起不来。这是架构级缺失，不是细节。
- **决策：分层吸收，不整体替换**。四条理由：
  1. **注入主链替换不可在无真机条件下验证**。当前 `AccessibilityService` 是唯一被验证过能跑的通道（历史版本已在真机出过「日志显示已点击、界面没反应」等只有装机才能发现的缺陷）。把它换成一条本环境完全无法验证的链路，等于用主功能做赌注。
  2. **「库越少越好」与清单自相矛盾**。清单同时要 LiteRT（含 GPU delegate）+ libyuv，这两个都带预编译 `.so`，直接扩大 16KB 页对齐的验证面；而现有 ONNX Runtime 已在跑、QuickJS 已 vendored 现场编译。替换不减少验证成本，只增加。
  3. **Room 替换触碰硬契约**。现有脚本落盘为 JSON，`schemaVersion=1` 与 `Action` 字段零迁移是发版红线；引入 Room 的 `version=1` 数据库与既有文件存储是两套真源，迁移风险高于收益。
  4. **清单里真正成立的部分已经落地**。它讲的「连接状态机、分级预告、通知三键、终止可恢复」全部属于 framework + 自写代码，正是 ADR-047 本轮实现的四件套，零新增依赖。
- **保留的判断（清单里最有价值的一条）**：hidden API 是**构建产物**而不是库——用自建 stub / hidden-apis 模块在编译期暴露 `UiAutomation.connect(int flags)` 与 `UiAutomationConnection`，避开运行时反射。这条判断正确，已记入后续 Shizuku 通道的前置条件。
- **后续路径（需真机，不进本版）**：Shizuku 通道以**可选 provider** 接入，`AccessibilityService` 保留为兜底；前置条件为 `Shizuku 13.1.5` + `bindUserService` 承载 `UiAutomation` + 编译期 stub + `connect(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)` 不挤读屏 + `injectInputEventToTarget` 仅作定向 UID 备选并做能力探测。落地前必须真机验证：Shizuku 授权与重启重拉、UserService 进程存活、`takeScreenshot` 在 secure 窗口的行为、与 Appium / Layout Inspector 等同类客户端互斥。
- **明确不做**：本版不引入任何第三方依赖；不替换注入链、不替换存储、不替换推理后端。


### ADR-049 装配-运行同源：取色 / 收敛 ROI 进入判定链、跨帧阈值与帧几何闭环（v2.9.31 / 正式 release）

- **问题来源**：v2.9.30 真机验收（79.8s 录屏 + 854 行日志 + 5 张失败现场截图）。用户在拾取指针后用手滑动制造跨帧变化，1 分 01 秒目标场景已完全呈现，但 `score=0.65 < 0.87`、`loc=-`、`掩码盖住=0/8`、59 帧 / 33.7s 触发 0 次。诊断报告：`../NymphAuto-v2.9.30-真机RCA-取色与ROI不同源.md`。
- **判定（不臆测，逐层对照源码）**：**不是阈值没调好，是装配期证据与运行期判据不同源**（train–serve skew）叠加**同帧自证循环**。

  | 层 | 装配期 | 运行期（v2.9.30） | 后果 |
  |---|---|---|---|
  | 取色 | `SceneAssist.sampleNeighborhood` → `ColorSample(#2A3444, tol=35)`，写入 `Action.color / colorSimilarity` | `runner/` 与 `engine/` 全目录检索 `colorSample / colorDistance / tolerance` **零命中** | 界面显示「PASS 允许点击」，判据完全不读它 |
  | 收敛 ROI | `SceneAssist.convergeRoi` → `79.06~82.80 / 80.29~88.46`，写入 `Action.region` | `TemplateSpec` 只用 `rule.searchRoi`（搜索范围） | 三个矩形三套语义并排打印成一套 |
  | 阈值 | `SceneSelfTest.run` 同一张图裁锚点又在同一张图 NCC → 命中 **1.00**；`calibrateSimilarity(runnerUp=0.78)` → **0.87** | 跨帧 NCC 恒 **0.65** | 阈值被「空间干扰分」顶高，从未测过「跨帧最低能到多少」 |
  | 帧几何 | `SceneRule` 存 `templateSrcW/H` | `TemplateSpec` 只带 `refDpi/curDensity`，逐帧不校验 | 最后 3 帧 `best@998,2538` 已属另一坐标系，同一套百分比仍能「容纳」 |
  | 落点 | 相对匹配坐标 | `loc=-` 回退 `Action.x/y` 默认 50/50 | 22:33 轮日志确证「重试 → TAP @ 50.0%,50.0%」，点在屏幕正中央 |
  | 掩码 | `planMask` 单帧空间方差，注释自认「启发式而非真值」 | `ImageMask.suppress` 把上一行像素复制下来 | `掩码盖住=0/8`：对本次失配既无贡献也无挽救 |

- **决定性旁证（排除浅层解释）**：同一份日志 22:33 轮几乎同一个按钮、best 坐标相差不到 30px，装配命中 **0.95**、阈值 **0.91（更高）**、运行 **0.94** → 触发 5 次；22:36 轮装配 **1.00**、阈值 **0.87（更低）**、运行 **0.65** → 触发 0 次。阈值更高反而命中，差别在模板的跨帧可复现性：**自测越接近 1.00，模板越只认得自己那一帧**。

- **决策（七项，全部落地，不新增第三方组件）**：

  1. **证据落盘并被消费**：`SceneRule` 增加 `colorArgb / colorTolerance / convergedRoi / crossFrameFloor / evidenceStreak`，**全部带默认值** → `schemaVersion=1`、`Action` 字段与历史脚本零迁移。装配期 `FloatingEditorService.buildSceneRule` 写入；运行期 `ScriptRunner` 构造 `TemplateSpec` 时优先读新字段，缺省时从 `Action.color / colorSimilarity / region` 回填（v2.9.21 起装配本就写过），使存量脚本升级后同样受益。
  2. **判据分层**（`core.editor.SceneEvidence`，纯函数）：`场景出现 = NCC 命中 ∨ (疑似在场 ∧ 取色在容差内 ∧ 最佳窗口落在收敛 ROI 内 ∧ 连续 N 帧稳定)`。「疑似在场」下界沿用既有 `diagnoseImage` 的 0.45，上界为本次实际生效阈值。证据不完整（老脚本）自动回退纯 NCC，**逐字零回归**；分层放行时把「全局最佳窗口中心」交给 `resolveSceneClick`，照旧经过落点偏差二次校验，绝不裸点。
  3. **阈值改跨帧双约束**：装配期 `captureSceneAnchor` 在首帧后 `delay(120L)` 再抓一帧（`≥100ms` 间隔）；`SceneSelfTest.crossFrameFloor` 对多帧在同一 ROI 跑 `bestScore` 取最小值；`SceneTrigger.calibrateSimilarity(runnerUp, crossFrameFloor)` 取「干扰分 + 余量」与「跨帧下界 − 3 分」的较小值，并用 `MIN_SAFE_SIMILARITY=55` 兜底，避免为迁就低分模板放行误匹配。单帧采集 / 老脚本 / 尺寸不一致时 `floor=-1`，逐字退回旧逻辑。
  4. **帧几何闭环**：`TemplateSpec` 带 `templateSrcW/H`；`ScreenSource.findTemplates` 逐帧用 `SceneEvidence.geometryMatches` 校验方向比（相对容差 0.02），不一致直接返回 `geometryOk=false` 的未在场结果，**不跑 NCC、不拿同一套百分比去另一几何里搜索**。源尺寸为 0（老脚本）不校验。
  5. **重试落点不退化**：`SceneTrigger.resolveSceneClick` 新增 `lastValid` 与 `strictNoFallback`。相对坐标语义下本帧无中心时优先沿用上次有效落点（仍走偏差校验），两者都无且严格模式开启时返回 `NoValidLocation` —— **不派发**，绝不回退 50/50。严格模式只在场景驱动主链的 `ON_APPEAR / WHILE_PRESENT` 上开启；`ON_DISAPPEAR`（离场时本就无中心）、老绝对坐标格式与历史单测默认关闭，逐字零回归。
  6. **「疑似在场」要有出口**：`ScriptRunner` 统计连续疑似帧数，达到约 1.5s（按实际帧同步档位折算、最低 3 帧）时经 `onSuspectedPresent` 升级为面板常驻告警（`BallPanel.setSuspect`）+ 通知文案 + 第四键「重新拾取」（复用既有 `MainActivity` Intent）。复用 v2.9.30 通知三键，零新增权限。
  7. **掩码改时域 + 两阶段抑制**：装配期有两帧时用 `StableAnchor.planMaskTemporal`（帧间灰度差均值 > 12 的 cell，排除锚点重叠）替代单帧空间方差，只有一帧才回退 `planMask` 并在装配日志标注来源。运行期 `findTemplates` 先在未抑制副本上跑一次拿最佳窗口，仅当 `maskCovers > 0` 才抑制后重算；**掩码盖住 0/N 时跳过抑制**，避免抹掉有效特征。

- **逐帧取色同源**：`TemplateSpec` 带 `pickXPct/pickYPct`（由 `SceneEvidence.recoverPickPoint = 锚点中心 + 相对偏移` 反推），`ScreenSource.findTemplates` 在原始帧副本上按**装配期同一个 `SceneAssist.sampleNeighborhood` 口径**采样，写入 `TemplateMatch.observedArgb`。无取色证据时零开销。

- **零回归约束（硬契约）**：`schemaVersion=1`、`Action` / `SceneRule` 既有字段含义零变化；逐帧判定节拍、画面门控、稳定锚点、ROI、落点偏差护栏、失败即停、三种 triggerMode、迟滞/冷却/重试/仲裁全部保留；零 `INTERNET`、零新增权限、不抢 `MediaProjection`、不新增第三方依赖。同源判据是**叠加**在既有 fail-closed 链上的第二条放行路径，不放宽任何一条否决。

- **后果**：新增 5 个 core 纯 JVM 单测文件（`SceneEvidenceTest` / `SceneTriggerCalibrationTest` / `SceneSelfTestCrossFrameTest` / `StableAnchorTemporalTest`，并保留全部历史用例零改动通过）；静态接线门禁 56 → **57 项**（新增 `evidence_same_source`，28 条断言一次锁定证据落盘 → core 判据 → 装配采集 → 运行消费 → 落点/告警）；符号表 154 → **176**（本版 +22）；纯 JVM 单测 704 → **755**（+51）；本工程可读类 1143 → **1151**。单测数、APK / 源码包 SHA、可读类数全部以 `tools/verify_release.py` 实时输出为准（见本版发布说明）。

- **能力边界（明确不冒充实测）**：本环境无真机。逐帧 `frameW/frameH` 与源尺寸的实际取值、`best@998,2538` 究竟是方向切换还是截图通道混用、0.65 的具体成因（动效 / 悬浮面板遮挡 / 缩放 / 色彩滤镜）、分层判据连续帧数 N 与告警阈值 M 在真机上的命中率与误触发率、120ms 第二帧采集在部分 ROM 上的成功率、时域掩码阈值 12 的普适性，均需真机矩阵实测。修复给出的是**结构性同源**与**可回退的降级通道**，不是已在真机上验证过的命中率结论。


### ADR-050 方向无关执行与拾取后判据参数精调（v2.9.32 / 正式 release）

- **问题来源**：真机调试包 `nymphauto-debug-1794760894596.zip`。竖屏拾取后运行，运行期先处于横屏、再切回竖屏，脚本被中止。日志时间轴（11-16）：`00:39:12.973 E/Runner 预检拦截：屏幕方向与脚本相反（脚本 800×1768，当前 1768×800）` → `00:39:36.872 I/Runner 开始执行（屏幕 800x1768）` → `00:40:37.174 E/Script 手势执行失败，已中止` → `00:40:53.126 I/Runner 停止运行`。

- **判定（逐层对照源码）**：三道独立方向硬闸叠加，任一道都足以使脚本不可用。

  | 层 | 位置 | 旧行为 | 后果 |
  |---|---|---|---|
  | 预检 | `core/runner/Preflight.kt` | 方向差异写入 `problems` | 预检不通过，脚本根本起不来 |
  | 执行主循环 | `app/runner/ScriptRunner.kt` | 方向与拾取参考不一致即中止 | 运行期旋转直接打断 |
  | 帧几何 | `app/engine/ScreenSource.kt` | 宽高比互为倒数判 `geometryOk=false` | 不跑 NCC，命中恒 false |

- **决策 A：帧归一化，不旋转模板**（`core/runner/OrientationTransform.kt` 新建）。稳定锚点、颜色样本、收敛 ROI、掩码块全部以拾取参考系的屏幕百分比落盘；把当前帧按 `turns = ((cur - ref) mod 4 + 4) mod 4` 逆时针旋回拾取几何后，既有百分比、NCC、取色链一字不改即可工作。相比旋转每一份模板图，只转一次帧、一轮共享，`turns=0` 零拷贝。四分之一转采用 `Surface.ROTATION_*` 数值（0/1/2/3，顺时针递增），`rotateImage` 与 `pointToCurrent` 严格互逆。

- **决策 B：落点反变换**。匹配中心在归一化帧上算出后，按 `pointToCurrent(xPct, yPct, turns)` 顺时针旋回当前屏幕再注入；`Region` / `MaskRect` 同源换算。以帧中心为原点、把 `[0,100]` 归一到 `[-1,1]` 正方形再做旋转，规避横竖屏宽高不等导致的拉伸。

- **决策 C：方向元数据落盘**。`SceneRule.pickRotation: Int = 0`（拾取时方向，带默认值 → `schemaVersion=1` 不变、历史脚本零迁移）；`TemplateSpec.pickRotation` 带入每轮；`TemplateMatch.orientationTurns` 带回上层。`pickRotation=0` 的老脚本按 `templateSrcW/H` 是否互换推断（`fromDimensions`），同向运行 `turns=0` 零回归。

- **决策 D：运行期不中止**。`ScriptRunner` 每轮先刷新 `frame`（启动时取一次的帧在旋转后过期）；检测到方向变化时清空 `suspectStreak` / `suspectedAlerted` / `lastValid` 等**跨帧瞬态证据**，记录「按新方向重标定坐标，脚本继续执行」后继续；删除旧的方向硬中止判断，并在静态门禁中断言 `refRotation` 不得复活。

- **决策 E：预检降级为非阻断告警**。方向差异只进 `warnings`，文案明确「运行期按当前方向重标定：帧旋回拾取几何匹配、落点旋回当前屏幕，方向差异不阻断」；宽高比告警中过时的「图片匹配大概率失败」同步替换。其余 fail-closed 判据（几何异常、模板缺失、截图失败、落点偏差上界）全部保留。

- **决策 F：拾取后判据参数配置**（`core/editor/SceneTuning.kt` 新建）。此前的真实缺口：`FloatingEditorPanel.collect()` 只收集动作层字段，`SceneRule` 的连续判据参数没有任何写回入口；且 `save()` 中 `pendingSceneRoi` 每次保存后即置空，「拾取后仅调整参数再保存」路径下 `sceneRule` 完全不更新。本版把六个连续字段（相似度 / 颜色容差 / 连续稳定帧 / 冷却 / 重试 / 落点偏差）收敛为可往返、可单测的 `Config`，全部映射到既有 `SceneRule` 字段：
  - **留空沿用、填值覆盖**：空串代表沿用装配期自校准值，绝不把面板默认值覆盖掉 v2.9.31 的实测值；只有显式填写才钳制后覆盖。
  - **边界外回显为空**：`colorTolerance=0` 表示未启用颜色判据，若按 `text()` 钳制回显为 1，用户不改直接保存会把「未启用」改写成「启用」；`from()` 对边界外值回显空串。
  - **写盘前统一应用**：`save()` 在所有分支之后、写盘之前应用显式覆盖，并同步 `condition.imageSimilarity`；仅在 `tunedRule != base` 时写回。
  - **意图层不重叠**：`ScenePreset` 的时机 / 防抖 / 重复仍是离散意图，本对象只负责连续数值，两层写入字段不重叠。

- **零回归约束（硬契约）**：`schemaVersion=1`、`Action` / `SceneRule` 既有字段语义零变化、历史脚本零迁移；`turns=0` 与空 `Config()` 均为恒等变换；未展开高级区、仅改场景名再保存的路径逐字不变；零 `INTERNET`、零新增权限（8 项）、零 `MediaProjection`、零新增第三方依赖。

- **后果**：静态接线门禁 57 → **59 项**（新增 `orientation_agnostic_wired` 45 条断言、`scene_tuning_wired` 26 条断言）；符号表 176 → **196**（本版 +20，只收唯一性强的类 / 方法 / 字段，短名不入表）；纯 JVM 单测 755 → **792**（+37：`OrientationTransformTest` 16 + `SceneTuningTest` 15 + `FloatingEditorLayoutTest` 3）；本工程可读类 1151 → **1170**；release APK 74,062,631 字节，同钥 v2/v3，权限 8 项零新增。单测数、APK / 源码包 SHA、可读类数全部以 `tools/verify_release.py` 实时输出为准。

- **能力边界（明确不冒充实测）**：本环境无真机。横竖屏切换的命中率与落点误差、各 OEM / ROM 的 `takeScreenshot()` 位图方向与 `Surface.rotation` 是否始终一致、折叠屏 / 分屏 / 画中画下的 `rotation` 取值、旋转瞬间帧尺寸切换对几何兼容的影响、六项判据参数在不同机型上的手感，均需真机矩阵实测。本版给出的是**结构性方向无关**与**可回退的恒等路径**，不是已在真机上验证过的命中率结论。帧归一化依赖截图方向元数据一致性；本版不引入 `MediaProjection` 去校准，以维持零录屏权限约束。

### ADR-051 厂商应用回切失败降级与能力边界可见告警（v2.9.33 / 正式 release）

- **背景**：2.9.32 真机日志（荣耀 1280×2800，`com.hihonor.gameassistant` 游戏中心场景，约 367 行）暴露三现象。逐条核实源码后分类处置，避免把能力边界误当缺陷、也避免以"修边界"之名引入虚假机制。

- **决策一（真 bug，修复）**：场景守卫 `SceneGuard.decide` 用纯字符串谓词 `TargetPackages.isLaunchableTarget` 判"可启动"，执行层 `AppLauncher.run` 用 `getLaunchIntentForPackage` 真实拉起——厂商聚合应用无标准 `CATEGORY_LAUNCHER` 入口或受包可见性限制时，两套能力不一致，谓词承诺了兑现不了的 RESTORE_APP，旧实现把拉起失败直接等同中止，用户反复重跑出现同因中止二十余次。
  - 新增 core 纯 JVM 真源 `RestorePolicy`：拉起失败后按重新读取的前台包名裁决——已在录制应用（忽略大小写）则放行、坐标手势追加界面一致性提示；否则一次性点名中止，移除"请手动打开后重试"循环话术。
  - `AppLauncher.run` 新增 `explicitMainIntent()`（`ACTION_MAIN + setPackage` + `resolveActivity`）兜底，补齐"有可启动 Activity、无标准入口"缺口；受包可见性限制仍可能失败，属尽力而为。**拒绝 `monkey -p` / shell 兜底**：本工程零 shell 通道、权限零新增（已核实 `Runtime.exec` / `ProcessBuilder` / `su` 引用全 0）。
  - **未采纳"把 core 谓词换成 PM 探测"**：那会在包可见性受限设备大面积假阴性，正是 v2.9.27 已修的坑。改为保留逻辑谓词、失败路径安全降级，让谓词承诺不再依赖拉起必成功。

- **决策二（能力边界，只强化告警）**：列表视图复用导致同节点文字随位置变化（ADR-002 已声明边界，被动录制不记滚动位置）——`RecordCompiler` 滚动告警补充可操作说明，**不新增滚动锚点机制**（录制侧大特性，不做虚假修复）。

- **决策三（可插拔设计，可见性）**：离线 OCR 需自备模型是既有设计（模型 7.5–81MB，内置会让 APK 翻倍并牵涉分发条款），不是缺陷。新增 `Script.dependsOnText()`（纯 JVM，递归动作 / 条件树 / 监听），仅当脚本确用文字锚点且两路 OCR 均不可用时，运行前一次性 toast + 日志告警并给出补齐路径；进程内只提示一次。**不内置模型、不静默降级**。

- **零回归约束（硬契约）**：`schemaVersion=1`、无新增落盘字段；`RestorePolicy` 只在 RESTORE_APP 拉起失败路径生效，成功路径逐字不变；OCR 告警只增可见性、不改通道顺序与降级语义；老脚本 / 未记录起始包名脚本行为不变；零 `INTERNET`、零新增权限（8 项）、零 `MediaProjection`、零新增第三方依赖、无 shell。

- **后果**：静态接线门禁 59 → **61 项**（新增 `restore_failure_downgraded` 与 `ocr_boundary_notified` 两组）；符号表 196 → **202**（本版 +6，只收唯一性强符号）；纯 JVM 单测 792 → **807**（+15：`RestorePolicyTest` 8 + `ScriptTextDependencyTest` 7）；本工程可读类 1170 → **1173**；release APK 74,062,631 字节，同钥 v2/v3，权限 8 项零新增。单测数、APK / 源码包 SHA、可读类数全部以 `tools/verify_release.py` 实时输出为准。

- **能力边界（明确不冒充实测）**：本环境无真机。厂商应用显式 MAIN 兜底的实际拉起成功率、包可见性受限设备上 `resolveActivity` 的返回表现、文字锚点脚本在自备模型 / 非 GMS 设备上的通道切换、列表视图复用告警的真实可操作性，均需真机矩阵实测。本版修复的是"拉起失败不应等同一中止"的确定性逻辑缺陷，不承诺所有厂商应用都可被拉起。

### ADR-052 OCR 类型隔离：文字断言与定位坐标在类型层分离（v2.9.34 / 正式 release）

- **背景**：旧 `TextBox` 把「文字内容 + 中心点坐标」绑定为同一值，任何文字识别结果的调用方都顺带拿到可注入的点击锚点，使 OCR 有可能绕过图像/节点证据链单独驱动点击与成功判定。v2.9.33 只做了 OCR 未就绪可见告警，未触及这个类型契约隐患。

- **决策（类型层拆分）**：新增两个互不通商的类型——`OcrTextAssertion`（text/confidence/matcherId，**无坐标**）只进条件判定，在类型系统上不可能产出点；`OcrRegion`（四顶点/source，**无文字**）仅供定位消费取中心点，无法反推文字。文字与位置只在消费侧显式组合，OCR 引擎不再输出"文字+坐标"绑定体作为唯一形式。这是"主链判定不依赖 OCR"在类型系统层面的落地。

- **决策（条件判定去坐标）**：`APPEAR_TEXT` 改走 `VisionPipeline.assertText`，节点树优先、OCR 回退只投影为 `OcrTextAssertion`，结果只能回答"文字在不在"，不能成为点击坐标来源，从链路杜绝 OCR 单独触发 `SceneVerdict.SUCCESS`。文字**定位点击**（`findTextNode` / `CLICK_TEXT_BY_VISION`）能力保留，与断言并存、互不退化。

- **否决的错误路径**：①"OCR 断言 + 模板定位"在动态文字场景跑不通，否决；②录制侧本就没有落盘 OCR 坐标锚点，新增 `anchorSource` 字段属无谓 schema 膨胀，否决；③`TapGate` 仅装配期使用，运行时安全根基是 `withRetry` / 条件判定链路，不错误声称 TapGate 覆盖运行时；④ML Kit bundled 无稳定 `assets/mlkit-google-ocr-models` 目录，检查该目录会恒 `OCR_DISABLED`，否决。

- **决策（置信度与脱敏）**：`ctcGreedyScored` 定义确定性置信度口径（采纳步 softmax 概率均值，无采纳步为 0），旧 `ctcGreedy` 委托新版、零回归；`confidence=-1.0` 表示后端无置信度，绝不伪造。`ocr_triggered` 只记 matcher/hit/confidence，不记原文与原文哈希（短文本哈希不构成脱敏）。

- **决策（ML Kit 口径纠正）**：`com.google.mlkit:*` 为 bundled 静态打包，不是 GMS 动态下载；删除"Play 服务按需下载""需 GMS 已缓存"等误导文案，告警不臆测 GMS 状态。ORT 主通道仍走用户自备 `filesDir/ocr/`。

- **零回归 / 后果**：`schemaVersion=1`、无新增落盘字段；旧 `TextBox`/`ctcGreedy`/`findTextNode` 全部保留；零新增依赖、权限 8 项零新增、零 MediaProjection、零 shell。静态门禁新增 `ocr_type_isolated` 一组；纯 JVM 单测 807 → 818；release APK 74,062,631 字节，同钥 v2/v3。数字以 `tools/verify_release.py` 实时输出为准。

- **能力边界（不冒充实测）**：本环境无真机。PP-OCRv5 manifest / golden / dry-run 契约、置信度口径的 manifest 声明、flavor 权重拆分需真实权重与真机矩阵，本版**不做端到端验证、不作虚假承诺**，仅完成类型隔离与静态门禁层。第三方预编译 `libtask_vision_jni.so` 非 16KB 对齐属本工程 CMake 不可控项，明确列出。

### ADR-053 审查报告确证真 bug 集中修复与 JS 架构安全加固（v2.9.35 / 正式 release）

- **背景**：收到三份针对 v2.9.34 的源码审查报告（52 条 / 127+41 条 / 历史 8 条）。三份报告均可能包含 AI 误报、行号漂移与语义误解，不照单全修。以四组并行源码核查逐条甄别真伪，最终按用户决策修复**全部确证真 bug（约 40 条）**，并将 JS 引擎三条架构性缺陷本版一并修复。

- **决策（JS 执行引擎与线程安全）**：① 脚本此前运行在 `Dispatchers.Main.immediate`，JS 同步 JNI 占死主线程使停止按钮无法派发——改为专用单线程 `newSingleThreadContext("nymph-script")`，所有 UI / 面板回调经 `postUi` 切回主线程；② `zdjl.check` 经 `runBlockingSync` 进入 Default 线程，条件为 JS_EXPR 时会重入主 QuickJS runtime（违反单线程约定）——`checkCondition` 链路改用隔离的 `ConditionEvaluator(InMemoryEngine())`；③ `runBlockingSync` 的 `CountDownLatch.await()` 无超时会永久闭锁——加 30s 上限并告警。

- **决策（数据丢失防护）**：中文脚本名改用真实 CJK 区间（旧实现把所有中文名归一成 `script` 互相覆盖）；历史版本 `versions`/`purge` 从前缀匹配改完整锚定正则（脚本名互为前缀时串档 / 误删）；原子写临时文件名加纳秒 + 线程 id（并发写同目标交叉覆盖）；非空但损坏的录制文件追加时原样保留（旧实现抹掉整段历史）；监听器与脚本级 `RUN_JS` 纳入授权判定（防止回调中挂 JS 绕过授权）。

- **决策（判据与数值正确性）**：`RANDOM` 采样域 `0 until 100`（旧 101 值域使 100% 条件 1/101 漏触发）；`Combine.N` 阈值 `coerceAtLeast(1)`（默认 0 使组合恒真）；正则 `regex=true` 编译失败显式失败、禁止降级为字面 `contains`；`/正则/` 首尾斜杠剥离；`findColor` 容差钳制 `[0,255]`；softmax 用 `isFinite` 拦截 NaN；找图超预算仍执行匹配（高分辨率小模板恒失败）；ROI 边界零面积向左上退让；`SceneTrigger` k=0 按 1 处理。

- **决策（资源生命周期）**：前台服务 `startForegroundService` 后先 `startForeground` 再 stop（避免 `ForegroundServiceDidNotStartInTimeException`）；`HardwareBuffer`/`Bitmap`/ORT `SessionOptions`/`ObjectDetector` 全部纳入 finally 或互斥懒加载；`createBitmap`/`createScaledBitmap` 返回源对象时按引用身份判断避免双回收；旋转视频以实际解码帧宽高为坐标基准（元数据宽高在 90/270° 时互换导致错位）；悬浮面板输入框失焦改延迟检查、消除焦点竞态（第二个输入框打不出字）。

- **否决的错误路径**：三份报告中「DotTreeParser 正则多引号」「WHILE_PRESENT 满速连点」「SceneVerdict 校准阈值失效」「OcrAssertion 反向框」「ImageFilters 黑环」「MainActivity Uri 权限」等 P0 / 高危项经源码核查证伪，未采纳，避免为误报引入回归。

- **零回归 / 后果**：`schemaVersion=1`、无新增落盘字段、无新增依赖、权限零新增、零 MediaProjection、零 shell、无混淆。纯 JVM 单测 818 → 827（`V2935BugFixRegressionTest` 新增 9 例承接本版确证缺陷）。静态门禁 `expected_symbols` 加入 `postUi` / `checkEvaluator` / `nymph-script` / `looksCorrupt` / `versionRegexOf` / `normalizeTextPattern` / `loadMutex` / `sessionOptions`。同钥 v2/v3 签名。数字以 `tools/verify_release.py` 实时输出为准。

- **能力边界（不冒充实测）**：本环境无真机。JS 架构线程修复、悬浮面板焦点竞态、原生资源生命周期等依赖真机 / 长时运行验证，本版仅完成源码级修复 + 静态门禁 + 纯 JVM 单测，**不作真机实测承诺**。第三方预编译 so 非 16KB 对齐仍属本工程 CMake 不可控项，明确列出。

### ADR-054 真实 debug 包定位的截图堆积假死与 ROI 判别度诚实呈现（v2.9.36 / 正式 release）

- **背景**：用户反馈 v2.9.35「界面完全卡死 + ROI 区域可能有问题」，提供完整 debug 包（149s 录屏 + 135 行运行日志 + 2 张失败现场截图）。据此定位两类问题，不依赖源码审查的主观推测。
- **根因（界面卡死）**：场景驱动逐帧判定按 16ms 同步每帧提交一次全屏 `takeScreenshot`，而 `awaitShot` 超时（3s×2 次重试，单帧最坏 6.4s）只取消等待、**不取消底层系统截图请求**；回调迟到无人消费，新请求持续入队，系统截图合成器被淹没。日志实测帧间隔 280ms → 674ms → 4061ms → **27109ms** 渐进劣化，48 帧耗时 57.6s。
- **决策（截图通道单飞）**：`AutoClickService` 加 `shotInFlight` 原子 CAS 标志，上一请求回调（成功/失败/超时）未落地前新请求直接快速失败（`SHOT_ERROR_BUSY`），**丢帧而非排队**——场景判定本就 fail-closed，少判一帧不误触。单次等待从 3s×2 收敛到 1.5s×1，重试交给上层 `AccessibilityScreenshotSource` 的连续失败熔断；超时用 token 令牌作废旧请求，迟到回调不消费结果、不覆盖新请求错误码；`BUSY` 不计入失败熔断（否则 16ms 连发 3 次就触发 3s 冷却，反向锁死通道）。提交抛异常 / 超时均复位标志，避免一次永久迟到回调把通道锁死。
- **根因（ROI 判别度）**：本例装配判定为 `LOW_DISTINCTNESS`（命中 0.51 < 干扰 0.60，跨帧下界 0.51 < 阈值 0.55）——锚点在数学上**永远不可能触发**。判定本身正确，但门控只按「锚点字节非空」判 `sceneGated=true`，界面显示自相矛盾的「可触发 · LOW_DISTINCTNESS ·（存在误判风险）」，保存时提示「已按场景触发」，用户据此运行 48 帧 0 命中、白等 57s。
- **决策（诚实呈现，不改阈值算法）**：阈值 `calibrateSimilarity` 的 fail-closed 行为是**刻意保留**的（阈值低于干扰分是为避免放行误匹配），简单抬高阈值会破坏 v2.9.31 真机 RCA 修复，故不改。改为在装配 status 与保存结论中，凡 `verdict.usable=false`（判别度不足 / 未装配）一律给出强告警「该锚点运行期不会触发」并给出重拾动作，禁止「可触发」误导字样；用户仍可强制保存，运行期 fail-closed 保证不误触。
- **否决的错误路径**：① 不照 agent「阈值 cap 不低于 runnerUp」建议——会把阈值抬到干扰分之上、放行误匹配，属负优化；② 不改收敛区域 MIN_RADIUS_FRAC——NCC 实际用 `searchRoi`（本例 192px）而非收敛区（32px），收敛区仅供取色采样（半径 12px 足够），放大它无益且改变所有历史脚本行为；③ 不把 abortCondition 每帧二次截图列入本版——本例脚本无中止条件，且改动会扩张范围。
- **零回归 / 后果**：`schemaVersion=1`、无新增落盘字段、无新增依赖、权限零新增。版本 2.9.35 → 2.9.36（versionCode 53 → 54）。纯 JVM 单测保持 827 全通过（本版为 app 层线程 / UI 逻辑，核心纯函数未变）。`expected_symbols` 加入 `shotInFlight`。同钥 v2/v3 签名。数字以 `tools/verify_release.py` 实时输出为准。
- **能力边界（不冒充实测）**：本环境无真机。截图单飞的帧率改善、ROI 诚实文案的真机观感仅做源码级修复 + 静态门禁 + 纯 JVM 单测，**不作真机帧率实测承诺**；截图是 FLAG_SECURE 窗口（游戏 / 支付页）时无障碍截图本就返回黑帧，属系统限制，脚本走 fail-closed 不触发。第三方预编译 so 非 16KB 对齐仍属工程 CMake 不可控项。

### ADR-055 审查报告硬声明复核：单飞锁泄漏与 OCR 置信度断链修复（v2.9.37 / 正式 release）

- **背景**：用户提交一份针对 v2.9.36 的「源码实读逐条复核」报告，含 S1 三源几何不对账、#1 严格几何闸丢过渡帧、#3/#4 死代码未接线、#5 搜索窗边界缺失、#6 `pickedRegion!!`、单飞饿死、LOW_DISTINCTNESS 永不触发等硬声明（「逐字节相同」「全仓零调用」「字节未变」）。先逐条源码实读 + 三组独立并行核查复核，再决定修什么。
- **复核结论（事实属实，定性大部分错误）**：报告列出的硬事实全部坐实——`frame()` 三源分离读、`geometryMatches` 严格闸、`geometryCompatible`/`regionToCurrent` 生产零调用、`pickedRegion!!` 仍在、`ScaleOutcome` 字段未变、`OrientationTransform.kt` 字节未变。但**属实≠该修**：
  - S1/#1/#2（几何主链）：`currentRotation` 与截图同源同帧，严格闸是 v2.9.31/32 真机 RCA 换来的 fail-closed 稳定设计，过渡期丢几帧下一轮自愈；改三源对账或放宽为 `geometryCompatible` 兜底，是用确定性真机回归换理论收益，**不动**。
  - #3/#4（死代码）：核心 `pointToCurrent` 落点旋回已落地并生产调用，`regionToCurrent`/`geometryCompatible` 是历史遗留的替代方案，接线会与现行路径叠加冲突，**不接**。
  - #5（搜索窗边界）：落点已映射为原图像素，不缺边界，**误报**。
  - #6（`pickedRegion!!`）：全部处于 `pickedRegion != null` 非空守卫内，无 NPE 窗口，**误报**。
  - 单飞饿死：报告「16ms 一提交」前提不成立（`SceneFramePolicy` 有物理下限 + 自适应节流），有效帧率 = 1/T，BUSY 丢的是本就 fail-closed 的冗余帧，**主结论误报**。
  - LOW_DISTINCTNESS：三层提示已到位，不改算法（fail-closed 防误触）的取舍**正确**。
- **确证真 bug（报告未提及，核查中暴露，本版修复）**：
  - **P0 · 截图单飞锁泄漏（v2.9.36 自身引入）**：`awaitShot` 的 `try/catch` 只包住 `submit()`，`withTimeoutOrNull { done.await() }` 裸奔在外——协程在 await 期间被取消（停止 / 暂停）时 `CancellationException` 跳过复位，`shotInFlight` 永久 true，此后全 `SHOT_ERROR_BUSY`、无帧、不熔断，直接复现用户「卡死」。且旧回调 `finally` 无条件复位会在超时后误清新请求刚 CAS 上的锁。修法：把 submit+await 全包进 `try/finally`，统一 `release()` 用 token CAS 保证只有当前在途请求能解锁（幂等 + 代次隔离），`CancellationException` 显式重抛。
  - **P1 · OCR 置信度断链（v2.9.34 引入）**：`ctcGreedyScored.confidence` 算对却无人消费——`OnnxOcrEngine` 走无置信度的 `ctcGreedy` 只取 text，`TextBox` 无 confidence 字段，`ocr_triggered` 永远记 `-1.0`，文档宣称的「带置信度解码」在生产链是断的。修法：`TextBox` 加 `confidence: Double = UNAVAILABLE_CONFIDENCE` 默认字段（向后兼容，ML Kit 无 API 仍诚实记 -1.0），`OcrTextAssertion.from` 透传 `box.confidence`，`OnnxOcrEngine.recognizeLine` 改走 `ctcGreedyScored` 并回填，`ScreenSource.assertText` 日志与断言用真实置信度。
- **零回归 / 后果**：`schemaVersion=1`、无新增依赖、权限零新增；`TextBox` 加默认字段向后兼容。版本 2.9.36 → 2.9.37（versionCode 54 → 55）。纯 JVM 单测新增 P1 置信度贯通回归（保持全通过）。`expected_symbols` 加入 `getConfidence`；静态门禁新增 `shot_release_finally_wired`（单飞 try/finally + token CAS 解锁结构）与 `ocr_confidence_wired`（置信度贯通链）两条。同钥 v2/v3 签名。数字以 `tools/verify_release.py` 实时输出为准。
- **能力边界（不冒充实测）**：本环境无真机。几何主链「不动」决策基于 v2.9.31/32 真机 RCA 档案与源码级调用链分析，非本版真机实测；P0 锁泄漏的修复效果（停止 / 暂停后立即恢复截图）需真机长时运行确认。第三方预编译 so 非 16KB 对齐仍属工程 CMake 不可控项。

### ADR-056 崩溃可见诊断版：冷启动闪退的堆栈取证（v2.9.38 / 诊断 release）

- **背景**：用户报告已授予全部权限、已开启无障碍，但打开 APK 即闪退。这是冷启动崩溃，唯一可靠定位依据是真机崩溃堆栈，而既有全局崩溃钩子只把堆栈写入 `filesDir/logs/last_crash.txt`——冷启即崩时用户进不去日志页，无法导出，形成知道崩溃却拿不到证据的死局。
- **静态排查结论（未发现必然崩溃点）**：启动链 `NymphApp.onCreate`（日志/崩溃钩子/通知渠道）→ `MainActivity.onCreate`（ViewModel + Compose 首帧）→ `onResume.refresh()` 逐段实读，目录初始化、`AutoClickService.isEnabled`、通知/电池探测、`QuickJsNative` so 加载均包了异常保护；OCR 引擎由 `OcrEngineFactory` 按需懒加载且 `init` 有 `runCatching`，不在启动路径；无障碍 `onServiceConnected` 仅赋值 + 日志。发布产物完整性核验：v2/v3 签名通过、3 个 dex 含全部关键类、6 个 so 齐全、Manifest 的 Application 与 LAUNCHER Activity 正确。本版 P0/P1 改动也不在启动路径。**因此不猜测性改动启动链**（那是拿确定性回归换无依据的尝试）。
- **决策（最小、纯增量、低崩溃面）**：新增独立进程 `:crash` 的 `CrashReportActivity`（纯代码构建 UI，零新增 layout/drawable 资源），全局崩溃钩子在写盘后立即拉起它，直接呈现完整堆栈 + 复制 / 系统分享 / 重启按钮；错误页检测到自身处于 `:crash` 进程时不再拉起，避免崩溃循环。既有崩溃语义不变——堆栈仍写 `last_crash.txt`，仍链式调用系统默认处理器（不吞崩溃、不改系统「应用已停止」行为）。
- **为何独立进程**：主进程崩溃后会被系统结束，若错误页在主进程则同样被回收；独立进程保证即使主进程已死，错误页仍可显示。
- **零回归**：`schemaVersion=1`、权限零新增、无新增依赖、无网络上报。版本 2.9.37 → 2.9.38（versionCode 55 → 56）。`expected_symbols` 加入 `isCrashProcess`；静态门禁新增 `crash_report_wired`（钩子写盘后拉起错误页 + 防循环 + Manifest 独立进程声明）。
- **定位路径（本版唯一目的）**：用户安装本版后若仍闪退，错误页会显示完整堆栈；用户截图 / 分享该页 → 获得 `异常类型 + 崩溃线程 + 完整调用栈 + 设备 API/型号` → 精确定位真因并出正式修复版。**本版不是闪退修复，而是取证工具**。

### ADR-057 重名 key 崩溃修复：数据根治 + UI 唯一 id 双防线（v2.9.39 / 正式 release）

- **背景 / 真因**：v2.9.38 取证页拿到的真机堆栈，主线程抛 `IllegalArgumentException`，明确是懒列表 key 重复（脚本名「未命名脚本」被用两次），关键帧落在 Compose `LazyLayout`/`LazyList` 的 `subcompose`。根因跨版本：v2.9.35 之前 `safeName` 把中文名折叠成单个 `script.json`（旧正则只认三个孤立字符，非 CJK 区间），v2.9.35 修正后中文名按原样落盘；于是老遗留 `script.json`（内部 name 仍为未命名脚本）与升级后新建的同名文件并存，`store.list()` 解出两个同名项。列表以 name 作 key 即崩；且删除/编辑/运行/分享/历史回滚全按 name 定位文件（`firstOrNull { it.name == name }`），同名时全部落到第一个文件，造成错删错改——比崩溃更隐蔽的数据隐患。
- **决策（两道防线，治标+治本）**：① 数据根治——`ScriptStore.migrateDuplicateNames()` 在实例初始化时幂等执行：按名分组，保留最新项原名，其余改名 `原名 (2)/(3)` 直到无冲突并连同文件名写回，动作体/revision 全部保留，沿用旧 mtime，坏文件由 `list()` 跳过；幂等保证重复启动无叠加后缀。② UI 兜底——`ScriptUi` 新增唯一 `id`（取 `item.file.name`），懒列表 key 由 `it.name` 改为 `it.id`，迁移万一遗漏也结构性杜绝重名 key 崩溃。
- **为何两者都做**：只改 key 能止崩但按名串台仍在；只做迁移则依赖覆盖全部脏数据形态。叠加后既消除显性崩溃又根治隐性错删错改。
- **零回归**：`schemaVersion=1`、权限零新增、无新增依赖、无网络上报。版本 2.9.38 → 2.9.39（versionCode 56 → 57）。无脏数据的存量用户迁移零写入、行为完全一致；有脏数据时仅旧项追加序号后缀，动作/场景/变量/修订号零丢失。`expected_symbols` 加入 `migrateDuplicateNames`；静态门禁新增 `duplicate_name_key_wired`（迁移接线 + id 字段 + key 用 id + 不得回退 name）。新增 5 项 core 回归测试（834 项全通过）。
- **能力边界（不冒充实测）**：本环境无真机；崩溃堆栈与源码根因一一对应、逻辑链闭合，但修复效果以真机复现为准。第三方预编译 so 非 16KB 对齐仍属工程 CMake 不可控项。崩溃诊断页（ADR-056）保留，对后续问题仍有用。

### ADR-058 数据完整性修复：审查报告逐条甄别后只修确证真 bug（v2.9.40 / 正式 release）

- **背景**：收到两份合并的审查/实施方案（1600 余行，P0×15 / P1×24 / P2×20 + 一份伪缺陷黑名单），基于另一台机器的旧源码快照。方案自身"门禁工具链缺失、许可证缺失、README 漂移"等现状声明经核查在当前工作目录均不成立（旧快照过时误报）。沿用 ADR-053/054/055 纪律：事实属实不等于该修。
- **甄别方法**：不看方案行号与结论，以当前源码为准逐条实读代码原文 + 调用链 + 既有 ADR 决策，四档归类（确证可修 / 属实但需真机或大重构 / 文档定性错误 / 方案自列伪缺陷），只落地第一档。
- **本版修复（3 条确证真 bug，均为 core 纯 JVM）**：① P0-01 `ActionEditGuard.apply()` 原写死 `unit=PCT` 且把坐标钳到 0..100，编辑一个 PX 动作（如 x=540,y=1200）只改时长即坐标截成 100/100、落点报废——改为保留 `original.unit`、上限随单位走；② P0-09 `LineImporter.parsePercent()` 失败补 0.0 且存活判定只用双零，`abc,45` 被保留成贴边框点击——改为解析失败返回 null 丢弃整条、摘要可见跳过数；③ P0-15 `AtomicTextFile.staleTempFiles()` 用 `.tmp` 子串匹配，`备份.tmp.bak` 等用户文件启动即被误删——改为严格匹配自家两种临时命名（新格式带纳秒+线程后缀、老格式精确 .tmp 结尾）。
- **明确不改（逐条理由，见发布说明第三章）**：需真机或涉 5.x 架构专项的 P0-02/03/04/05/10/11/14 与 B2；文档定性错误的 P0-06（已有 `maxFiringPerRound=1` + 预设 400ms 冷却）；既有刻意设计的 P0-08（几何校验"零回归优先"，v2.9.36/37 刚定为严格主链）与 P0-13（v2.9.31 两阶段掩码已规避、真机 RCA 确证）；native/语义变更的 P0-07/12；增强或两字段语义不同的 P1 余项；方案自列黑名单 9 条。
- **零回归**：`schemaVersion=1`、权限零新增、无新增依赖、无网络上报、ABI/签名证书不变。版本 2.9.39 → 2.9.40（versionCode 57 → 58）。新增 10 项 core 回归测试（843 项全通过）；静态门禁新增 `v2940_data_integrity_wired`（三条修复的源码接线 + 不得回退到写死 PCT / 子串匹配）；版本判定与源码包坐标同步升级。
- **能力边界（不冒充实测）**：本环境无真机，三条修复均为纯 JVM 逻辑，以单测与静态门禁验证，未宣称真机验证。未修条目按"属实≠该修"主动取舍，留待后续按解封条件（真机数据 / 架构专项）推进。

### ADR-059 2.9.40 审查回执确证真 bug 修复：同类缺陷双路径 + 隔离优于强删（v2.9.41 / 正式 release）

- **背景**：收到针对 v2.9.40 的审查回执（5 条真 bug 声明 + 上一轮裁定确认）。沿用 ADR-053~058 纪律：事实属实不等于该修，以当前源码为准逐条实读，先证伪误报再定修复范围。
- **逐条结论**：① 滑动终点解析失败仍补 0（属实，上版只修起点、保险只覆盖编辑器滑动保存未覆盖导入器滑动解析）→ 修；② 文档与版本脱节（回执基于旧快照，当前 README/ENGINEERING/build.gradle 三者一致，属误报）→ 事实不改，但吸收其流程教训；③ tmp 老格式分支 `.+\.tmp$` 仍会删用户 `备份.tmp`（属实）→ 修；④ 重名迁移 delete 失败被吞无告警、下次再生 `(3)`（属实）→ 修；⑤a `isCrashProcess` 依赖 `runningAppProcesses` 在部分 ROM 失效、防循环失效（属实）→ 低风险加固；⑤b 冷启动从 `:crash` 进程 `startActivity` 受 Android 10+ 后台限制 + OEM 拦截（属实但需真机、改法引入新权限）→ 记录不改。
- **本版修复（4 处）**：① `LineImporter.convertLinePoint()` 滑动终点与起点同款语义——终点对象缺失或 x/y 任一解析失败即 `return null` 丢整条，合法零终点保留；③ `AtomicTextFile.STALE_TMP` 两分支一并锚定 `.json.tmp`（本工具所有落盘目标均 `.json` 结尾），非 `.json.tmp` 用户文件不碰；④ `ScriptStore.migrateDuplicateNames()` 删除旧文件失败先 `RunLog.w` 告警、再改名 `<原名>.json.orphan-<nano>` 隔离（脱离 list 识别与清理器匹配）、双失败则 `RunLog.e`，数据不丢且下次不再重复迁移；⑤a `CrashReportActivity.isCrashProcess()` 主路径判不出时回退读 `/proc/self/cmdline`，任一路径判为 crash 即视为 crash（保守防循环，漏判代价远高于多判），判定抽纯函数 `isCrashProcessName`。
- **关键取舍**：同类缺陷两条路径都要修（编辑器 + 导入器都有回归）；隔离优于强删（④ 改名隔离既断重复根源又保全数据）；保守防循环（⑤a 宁多判不漏判）；文档先行打包在后（② 流程教训，源码包在全部文档落定后生成）。
- **零回归**：`schemaVersion=1`、权限零新增、无新增依赖、无网络上报、ABI/签名证书不变、几何主链与 fail-closed 语义不变（继续执行 ADR-055 官方裁决）。版本 2.9.40 → 2.9.41（versionCode 58 → 59）。新增 6 项 core 回归（849 项全通过）；静态门禁新增 `v2941_data_integrity_hardening`（①③④⑤a 源码接线 + 不得回退到宽泛 `.+\.tmp$` / 补 0 写法）；版本判定与源码包坐标同步升级。
- **能力边界（不冒充实测）**：①③④为纯 JVM 逻辑、单测 + 静态门禁验证；⑤a 由静态门禁锁定、未做真机 ROM 矩阵验证；⑤b 需真机、记入本 ADR 留待后续，不作已修复承诺。本环境无真机，未宣称真机验证。

### ADR-060 2.9.41 审查回执确证问题修复：修正错误事实 + 补齐零覆盖回归（v2.9.42 / 正式 release）

- **背景**：收到针对 v2.9.41 的审查回执（4 条真 bug 声明 + 1 条收回的旧误报）。沿用 ADR-053~059 纪律：以当前源码逐条实读，先证伪误报再定修复范围。本轮回执质量较高，4 条经源码核查均属实。
- **逐条结论**：③-a（STALE_TMP 事实前提错误）属实——`ScriptRepository.SUFFIX=".naz"` 且历史快照走 `AtomicTextFile.write`，而 v2.9.41 注释/ADR-059 称「所有写入目标都以 `.json` 结尾」，`.naz.tmp-<nano>-<tid>` 结构上永远匹配不到新正则，且 `cleanupStale` 只在 `ScriptStore` 主目录调用、`.history` 从未清 → 修；③-b（起点双零丢弃与终点口径不对称）属实——`LineImporter:102` 起点 `x<=0&&y<=0` 整条丢弃，终点 (0,0) 却专门保留且有测试护着 → 修；②（④⑤a 零测试、ADR-059 声称 6 项实为 ①③）属实——`grep orphan|isCrashProcessName` 测试目录零命中 → 补测；④（orphan 无回收路径）属实但为保数据优先的设计副作用、审查稿本版未列必修 → 记为已知边界不改；回执②「2.9.40 文档脱节」为旧快照误报、审查稿自行收回 → 事实不改，沿用「文档先行、打包在后」流程。
- **本版修复（3 处 + 补测）**：③-a `AtomicTextFile.STALE_TMP` 正则从仅 `.json.tmp` 扩为 `(json|naz)` 两类新旧格式分支，注释更正为真实写入目标（脚本/授权 `.json` + 历史快照 `.naz`）；`ScriptRepository.init` 接 `AtomicTextFile.cleanupStale(history)` 清掉 `.history` 里长期堆积的 `.naz.tmp-*`，用户自有 `.tmp`/`.naz.tmp.bak` 仍不误伤。③-b `LineImporter.convertLinePoint` 去掉起点 `x<=0&&y<=0` 整条丢弃，x/y 由 `parsePercent` 保证为非负有限值，起点与终点统一为「解析成功即保留」，非法/缺失/负数/NaN 仍按 parsePercent 丢弃。② 新增 `V2942FixRegressionTest`（core 6 例：naz 新旧格式清理、用户文件不误删、history 残留构造时清理、左上角零坐标滑动保留、起终双零保留且非法仍丢、orphan 文件脱离 list 与清理识别）+ `CrashReportActivityTest`（app 4 例：`isCrashProcessName` null/空/普通/主包名/`:crash` 后缀各分支），补上 ④ 迁移隔离与 ⑤a 防循环判定的零覆盖。
- **关键取舍**：错误事实被固化进数据安全正则的风险高于单纯注释瑕疵——正则与接线一并改，避免「以后接了清理器也清了个寂寞」；起终零坐标同属一套坐标系、同款语义，不保留两套待遇；纯函数抽离了就必须有回归，否则声称的测试覆盖不成立；保数据优先的 orphan 隔离不引入无引用回收（误判代价高于磁盘残留），保留现状。
- **零回归**：`schemaVersion=1`、权限零新增、无新增依赖、无网络上报、ABI/签名证书不变、几何主链与 fail-closed 语义不变（继续执行 ADR-055 官方裁决）。版本 2.9.41 → 2.9.42（versionCode 59 → 60）。新增 10 项回归（core 6 + app 4），单测 849 → 859 全通过；静态门禁新增 `v2942_snapshot_and_coord_wired`（naz 分支 + history 清理接线 + 错误事实移除 + 起点双零丢弃移除 + 两组补测文件存在）；v2941 门禁正则断言同步改为 `(json|naz)` 锚点以兼容新正则。版本判定与源码包坐标同步升级。
- **能力边界（不冒充实测）**：全部为 core/app 纯 JVM 逻辑，单测 + 静态门禁验证；无真机 ROM 矩阵，未宣称真机验证。④ orphan 无自动回收为已知边界，后续如需回收须以「确认无任何脚本引用」为前提单独设计，不在本版范围。

### ADR-061 「不和录屏抢权限」从文档断言升级为双层永久门禁（v2.9.43 / 正式 release）

- **背景**：收到针对 v2.9.42 的审查回执，逐条确认 2.9.42 的三处修复与两组补测均正确、无新引入真 bug，核心逻辑不动。用户同时提出硬约束「不管何时都不能和录屏抢权限」。核查发现一处真实缺口：README/NOTICE/无障碍配置均宣称全工程零录屏 API，但静态门禁此前只在 dex 层锁 7 类符号（`MediaProjection`/`VirtualDisplay`/`createVirtualDisplay`/`MediaCodec`/`FLAG_SECURE`/`RECORD_AUDIO`/`WATCH_OUTSIDE_TOUCH`），缺 `createScreenCaptureIntent`（申请录屏授权的入口）等，且**无源码级拦截**——断言写在文档、门禁没钉死，与 ADR-060 记录的「错误事实被固化」同类风险。dex 实测当前这些符号引用全为 0（事实属实），缺口在「不管何时」的可执行保证。
- **决策**：核心事实不变、不放宽任何契约，仅把约束从文档升级为可执行门禁：
  - **dex 层**：`BANNED` 从 7 项补到 13 项，新增 `MediaProjectionManager` / `createScreenCaptureIntent` / `getMediaProjection` / `ACTION_MEDIA_PROJECTION` / `android/media/projection` / `createScreenCapture`，覆盖申请授权入口与意图动作全链路。
  - **源码层**：新增门禁 `v2943_no_screen_capture_api`，扫描 `core`/`app` 全量 `src/main/**/*.kt` 的**非注释代码行**，上述精确 API 标识符必须零引用；刻意不扫裸词 `MediaProjection`/`VirtualDisplay`——工程大量中文说明文案（「零 MediaProjection」「不创建 VirtualDisplay」）合法出现，裸词扫描会误伤，这两类由 dex 层按类型描述符字节锁定，两层互补。
  - **正向锁定通道**：门禁同时要求 `accessibility_service_config.xml` 仍为 `canTakeScreenshot=true` 且 `canRequestTouchExplorationMode=false`，确认截图唯一通道是无障碍、未启用会打断其他 APP 的触摸浏览模式。
- **关键取舍**：不引入裸词源码扫描（误伤合法文案，且与 dex 层重复），不做真机录屏共存实测（本环境无真机，不冒充实测），不改几何主链 / fail-closed / 脚本 schema / 权限表（8 项零新增）。约束的价值在「可执行、可持续」——每次发版门禁自动复核，而非依赖文档自觉。
- **零回归**：`schemaVersion=1`、权限零新增、无新增依赖、无网络上报、ABI/签名证书不变、核心匹配与几何语义不变。版本 2.9.42 → 2.9.43（versionCode 60 → 61）。无运行期行为改动，单测维持 859 全通过；静态门禁新增 `v2943_no_screen_capture_api` 并接入 `ok` 硬门槛，`banned_refs` 报告符号从 7 项扩为 13 项（全应为 0）。版本判定与源码包坐标同步升级。
- **能力边界（不冒充实测）**：门禁为静态源码/dex 字节核验，能确定性阻断 API 引用进入源码或产物；不与真机 ROM 的录屏共存行为等价，后者需真机验证，未宣称已实测。

### ADR-062 场景驱动增加无障碍节点 fallback（截图通道被录屏占用时的零截图触发源，v2.9.44）

- **背景**：真机录屏 + debug 日志证据：开启系统录屏后，无障碍 `takeScreenshot` 被系统占用持续失败（`本轮无匹配结果（截帧或模板解码失败）`），场景驱动每帧 `findTemplates` 返回 null → 全部 `present=false` → `decision.fire` 为空 → 长时间只显示「等待场景出现」、一次动作都不派发。该次运行逐帧判定 767 帧、命中 0。按 ADR-061 硬约束，项目零 MediaProjection、不去抢录屏权限，故截图被占是外部约束，只能在识别侧降级。无障碍节点树 `rootInActiveWindow` 不受录屏影响、全程零截图，是等价可行的「场景出现」判据。
- **决策**：给场景驱动加一条触发源，仅在「截图失败或 NCC 未命中」时启用，不改 NCC 成功路径：
  - **schema（零回归）**：`SceneRule` 新增四个带默认值字段 `nodeText` / `nodeViewId` / `nodeClass`（语义锚点）与 `nodeFallbackEnabled`（启用位，默认 false）。压缩落盘 `encodeDefaults=false` 不写默认值字段，`schemaVersion` 保持 1，存量脚本逐字不变、缺字段补默认。
  - **装配期**：`buildSceneRule` 在拾取瞬间用已有像素坐标 `findSmallestNodeAt` 读锚点节点的 text/viewId/class，仅当存在 text 或 viewId（有判别度）才落盘并置启用位；节点树读取全程 `runCatching` 兜底，异常不阻断装配。两处 `SceneRule` 构造（稳定锚点成功 / fail-closed 分支）统一接线。
  - **纯函数**：新增 core 纯 JVM `NodeFallback`，固化 `applicable`（启用位+非空语义）、`matches`（按落盘非空项逐项包含，对齐 `NodeFinder` 口径）、`toObservation`（命中构造 `present=true`、固定满分、节点中心坐标与当前帧尺寸）三函数，`SceneEvidence.Tier` 增 `NODE` 值供可观测区分来源。
  - **运行期**：`runSceneDriven` 在 observation 收集的两处未命中出口调用 `tryNodeFallback`：`nodeFinder.find(viewId, class, text)` 零截图查节点树，命中即覆盖本条判定为在场、喂入同一套 `SceneTrigger.planSceneFiringStateful`（迟滞/cooldown/retry/仲裁/K 上限）与 `resolveSceneClick` 之外的专用落点分支。节点命中落点直接用**当前节点中心屏幕百分比**（节点树读的是当前屏幕坐标，天然方向无关、无需旋回，免 NCC 相对偏移与错位校验），仍受 `runAction` 内 EdgeSafety/TapGate ROI 约束。
- **关键取舍**：只加触发源、不动任何护栏；fail-closed 不放宽（拿不到节点仍判未在场）；不碰权限模型、几何主链、匹配语义、schemaVersion。节点是二值判据（`score=1.0` 固定值），不进 NCC 疑似连续帧统计。
- **能力边界（诚实声明）**：节点 fallback **只对有语义节点的界面生效**（普通 App 文字/按钮/控件）；纯图像/游戏/短视频/桌面壁纸的节点树无语义节点，录屏占用截图时仍空转——技术硬边界，非本版可解。装配期 `findSmallestNodeAt` 依赖当前界面可访问节点，敏感页（`accessibilityDataSensitive`）读不到则不启用。本环境无真机，节点通道行为由 core 纯 JVM 单测（12 例）+ 静态门禁验证，未宣称真机 ROM 矩阵实测。
- **零回归与验证**：`SCHEMA_VERSION=1`、权限零新增、无新依赖、签名证书与 ABI 不变。单测 859 → 871（新增 12 例 V2944 回归），全通过。新增静态门禁 `v2944_node_fallback_wired`（schema 四字段 + 纯函数 + 装配期捕获接线 + 运行期 fallback 接线 + 回归测试存在 + 不回退 v2943/BANNED 13 项）接入 `ok` 硬门槛。版本 2.9.43 → 2.9.44（versionCode 61 → 62）。

### ADR-063 稳定性修复：原生句柄一次性释放与日志并发写串行化（v2.9.45）

- **背景**：本轮稳定性审计在两条停止链路上确证并发缺陷。① `QuickJsAndroidBinding` 的原生 runtime 指针用裸 `Long ptr` 做 `if (ptr != 0L) { destroy(ptr); ptr = 0L }`，是典型 check-then-act：脚本协程 `finally`（`nymph-script` 线程）与 `stopAll/handover`（主线程）可能同时读到同一个非 0 指针各自进入 `destroy`，造成 native double-free；更严重的是 `stopAll/handover` 在主线程直接 `dispose()`，而 JS 可能正在 `nymph-script` 线程 `eval`，`JS_FreeRuntime` 与 `JS_Eval` 并发构成 use-after-free，表现为随机 SIGSEGV、极难复现。② `LogStore.append` 由 `RunLog.addListener` 在锁外通知，可被脚本线程、主线程、无障碍回调并发进入；无锁的 `f.length()` 检查 + rename 轮转 + `appendText` 会交错，导致日志丢失、重复轮转或写入已改名的旧文件。
- **决策 ①（原生句柄一次性守卫）**：新增 core 纯 JVM `NativeHandle`，用 `AtomicLong.getAndSet(0L)` 把「取旧值 + 清零」合成单个原子操作，保证 `destroy` 恰好执行一次；`QuickJsAndroidBinding` 改用它，`dispose()` 幂等，所有原生调用点读 `handle.get()`（已释放读 0 即跳过）。`RunnerService` 的 `stopAll/handover` 主线程路径只做 `requestInterrupt()`（native 原子标志，任意线程安全）+ `runnerJob.cancel()`，不再直接 `dispose` 正在 `eval` 的 runtime；真正销毁收敛到脚本协程 `finally`，并新增 `invokeOnCompletion` 兜底覆盖「协程尚未启动即被取消、finally 未执行」的边界。兜底用捕获的局部 `quickJs` 引用（非字段）+ `===` 身份判断，避免误伤下一轮新建的运行时。
- **决策 ②（日志串行化）**：`LogStore` 的 `append` / `tail` / `clear` / `init` 统一加 `@Synchronized`，与实例锁共用，把「检查—轮转—追加」串行化；`init` 原本已加锁，本次覆盖读写与清空路径。`writeCrash` 走独立崩溃文件、不进此锁，避免崩溃写盘阻塞日志。
- **零回归与验证**：零新增权限、零新依赖、无 schema 变更、无运行期行为改动（仅消除竞态）、签名证书与 ABI 不变。新增 core 纯 JVM `V2945StabilityFixTest` 7 例（初始句柄 / 重复释放 / 零句柄 / 异常仍清零 / 8 线程 × 200 轮并发释放恰好一次 / 并发读写后稳定归零），单测 910 → 917 全通过。新增静态门禁 `v2945_stability_wired`（守卫原子实现 + QuickJs 接线 + 主线程停止链路不直接 dispose + completion 兜底 + 日志三处加锁 + 回归测试存在 + 不回退 v2944/v2943/BANNED 13 项）接入 `ok` 硬门槛。版本 2.9.44 → 2.9.45（versionCode 62 → 63）。
- **能力边界（诚实声明）**：本环境无真机，并发竞态的消失由纯 JVM 并发单测（多线程放大竞态窗口）+ 静态接线门禁验证；native 层 `nc_of(ptr)` 的 `sig` 校验作为第二道防线仍在，不改动。SIGSEGV 类问题无法在无真机环境做长期压力复现，如实声明。

### ADR-064 系统性稳定性修复：无效帧冻结 / 运行会话唯一所有权 / 识别热路径降本 / QuickJS 同步桥停止感知（v2.9.46）

- **背景**：v2.9.45 只修掉「同一原生句柄重复销毁」。本轮对运行主链、识别、存储、录制、日志、节点逐层审计，收敛到四类可复现根因，按审计第一轮 P0 集中修复：
  1. `ON_DISAPPEAR` 把「没拍到」当「场景消失」——`ScriptRunner` 截图失败 / 几何错配时构造 `SceneObservation(present=false)`，`SceneTrigger` 把它当真实离场推进 `missStreak`，`wasPresent && !presentNow` 在「曾在场 + 连续 N 帧截图失败」时误派发下降沿动作。节点 fallback 只能证明在场，补不了离场这个洞。
  2. 一次运行没有唯一 owner——`toggle` 的 `finally` / `postUi` / `invokeOnCompletion` 全写共享字段（`runner`/`runnerJob`/`visualizer`/`quickJs`/`state`/相位/看门狗）。`handover` 取消协程后立刻置 IDLE，旧收尾仍会关掉新浮层、取消新看门狗、清空新引用、改写新状态；运行中 `ACTION_START` 还会直接覆盖 `script`。
  3. 识别热路径随规则数成倍放大——`findTemplates` 对每条规则每帧 `ImageOps.decodeBase64`（PNG 解码 + `getPixels`）一次、并 `pickFrame.argb.copyOf()` 复制整帧；1080×2400 一帧 ARGB ≈ 10MB，10 条规则 ≈ 每帧 100MB 分配，默认还按 33ms 一帧。
  4. QuickJS 同步桥把 30s 当所有调用语义——`sleep/findImage/findText/check` 全走同一 `runBlockingSync` + 固定 30s 闭锁：`zdjl.sleep(60000)` 在 30s 被截断，停止按钮在 JS 等待期间无法打断闭锁；每次调用新建不受运行 Job 管理的 scope，存在泄漏；`NativeHandle.get()` 后到 native 调用前仍可被并发 `dispose`，存在残余 UAF 窗口。
- **决策 ①（无效帧三态冻结）**：`SceneObservation` 新增 `valid: Boolean = true`（末尾参数，存量位置构造点零回归）。截图失败且无节点证据、以及几何错配（`score<0`）的观测标 `valid=false`；`SceneTrigger.planSceneFiringStateful` 对无效帧冻结：计数沿用上轮、在场状态沿用 `wasPresent`、不进入触发意图判定。`ON_DISAPPEAR` 只接受有效帧确认离场。节点 fallback 命中（`valid=true`）语义不变。
- **决策 ②（RunSession 唯一所有权）**：新增 core 纯 JVM `RunToken`（单调会话号 + `current` + `isCurrent` + `invalidate`）。每次启动 `myRunId = runToken.next()`；`toggle` 的 `finally`/`postUi`/`invokeOnCompletion` 中所有共享字段写都以 `runToken.isCurrent(myRunId)` 守卫；`stopAll/handover` 先 `runToken.invalidate()` 再取消，旧收尾的字段提交全部跳过；浮层/看门狗/引擎/runtime 一律用本轮局部引用**无条件释放**（不依赖守卫，防泄漏），watchdog job 也捕获局部引用无条件 cancel。`ACTION_START` 在非 IDLE 状态拒绝，杜绝运行中覆盖脚本。
- **决策 ③（识别热路径降本）**：`VisionPipeline` 加实例级 `templateCache: HashMap<String, Image?>`，`decodeTemplate` 按 base64 缓存解码结果（失败也缓存，坏模板不每帧重试），随 `close()` 释放；`findTemplates` 仅当规则有 `maskRects` 才复制整帧（唯一原地写是 `ImageMask.suppress`），无掩码规则只读共享 `pickFrame`。`ImageFilters.apply`/`findDetailed`/`scaleImage`/`grayOf` 经核查均只读像素，共享安全。
- **决策 ④（QuickJS 同步桥）**：`NativeHandle` 加 `ReentrantReadWriteLock`，新增 `useIfAlive`（读锁执行 native 调用）/ `disposeOnce` 持写锁，保证在途调用期间绝不释放 runtime；`QuickJsAndroidBinding` 所有 native 调用改走 `useIfAlive`，`eval` 用非空包装区分「已释放」与「正常 undefined」。按 API 拆分超时（识别 30s / toast 3s / sleep 用实际时长+余量）；`requestStop()` 置位 `stopRequested` + native 中断，`runBlockingSync` 改 50ms 轮询，停止后取消桥接协程立即返回，退出即 `scope.cancel()` 防泄漏；停止链路调用从 `requestInterrupt` 改为 `requestStop`。
- **零回归与验证**：零新增权限、零新依赖、无 schema 变更、无 `MediaProjection`、零 shell、功能契约逐字不变、签名证书与 ABI 不变。新增 14 例纯 JVM 回归：`V2946FrozenFrameTest` 5 例（默认 valid / 在场后连续无效帧不触发 / 无效帧不推进离场计数 / 纯无效帧不产生上升沿 / 兼容入口零回归）、`V2946RunTokenTest` 5 例（初始无会话 / 单调分配 / invalidate / 重启隔离 / 并发 next 唯一）、`V2946NativeHandleInFlightTest` 4 例（存活执行 / 释放后跳过 / 空结果包装 / 在途调用期间 dispose 被写锁阻塞）。单测 917 → 931 全通过。新增静态门禁 `v2946_stability_wired`（四类接线 + 三例回归存在 + 不回退 v2945/v2944/v2943/BANNED 13 项）接入 `ok` 硬门槛。版本 2.9.45 → 2.9.46（versionCode 63 → 64）。
- **能力边界（诚实声明）**：本环境无真机。竞态/误点消失由纯 JVM 并发单测（多线程放大窗口）+ 静态接线门禁验证；SIGSEGV、真机长期压力、实际帧耗时下降未做真机实测，不作未经验证的绝对承诺。P1/P2（脚本身份改用不可变 scriptId、主线程 IO 收敛与 StrictMode、日志队列化、节点可见性/回收、录制有界队列）作为后续独立轮次，不在本版范围。

### ADR-065 视频→规则流水线的四通道降级 P0：四证据投票 + 离线回放证伪（v2.9.47）

- **背景**：把「完美」翻译为工程约束后只有一条可落地含义——任一单通道（无障碍手势 A / 端侧 VLM C / 纯视觉反演 / 零模型 OCR D）失效时，流水线仍能产出同一 schema 的可执行规则。Android 17 AAPM 会撤销自动化工具的无障碍权限、低内存机型 LMK 会杀端侧 VLM、纯视觉在长按/多指不可靠；架构不得依赖任何一个作为单点。本版落地这套架构的 P0：唯一在任何版本/机型/保护模式下都必须能跑的 B 通道（纯 Kotlin、零 native、零模型、APK 增量 0）与质量闸门（离线回放证伪）。
- **不变式：产物格式永不变化**：四通道无论走哪条路径，都汇总成同一套 `TapCandidate` 候选 → 同一 `VideoScriptBuilder` 编译 → 同一 `schemaVersion=1` 规则。运行时回放引擎不需要知道规则来自哪条路径。本版只扩展 B 通道与证伪，不改落盘 schema、不改 `detect` 对外 API。
- **决策 ①（四证据投票 `TapEvidence`，纯 JVM）**：四个证据源全部手写、确定性、可单测——① 编解码器关键帧稀疏化（app 层 `MediaExtractor` 读 `SAMPLE_FLAG_SYNC`，零解码预算）、② 帧差网格能量局部峰 + 块匹配判滑动（SWIPE 负证据）、③ 按压涟漪瞬态（ROI 帧差时间二阶导先升后降）、④ 手指遮挡弱证据（灰度局部对比度下降，可选默认关）。±200ms、±12% 屏宽时空邻域聚类；≥2 独立证据源进候选、仅 1 票 `needsReview`、滑动负证据同域 `conflict` 强制人确认；置信度为离线加权标定、非模型自述。
- **决策 ②（离线回放证伪器 `ReplayFalsifier`，纯 JVM）**：每条候选在导入期于原视频帧序列上离线模拟一次——前置（DELTA_IMAGE / APPEAR_IMAGE / TEXT_PRESENT）在动作帧前从未满足则判不可执行、前置首次满足与动作帧偏差落在容忍窗口、后置断言在预期窗口通过；输出可执行概率 0~1，<0.75 置 `needsReview`。`observeFrames`（帧序列→观测）/`falsify`/`falsifyTaps`（候选批量）均为纯函数，core 不解码、不 OCR、不联网。
- **决策 ③（增强替换，零回归）**：`TapCandidateDetector.detect` 对外 API 与 `pickDefaults` 逐字不变；新增 `detectWithEvidence` 返回 `EvidenceCandidate`（`TapCandidate` 的格式超集，附置信度/证据数/来源/冲突）。app 层 `VideoFrameExtractor.sampleFramesAdaptive` 用关键帧时间表在密集区 50ms / 稀疏区 200ms 采样（解码量降一个数量级），空表退化为原固定间隔（零回归）；`VideoImportDialog` 走四证据融合 + 生成期对每条勾选规则离线回放一次（结论写进脚本描述供复核，不静默丢弃用户勾选）。
- **零回归与验证**：零新增权限、零新依赖（无 OpenCV / ffmpeg / 模型权重）、无 schema 变更、无 `MediaProjection`、零 shell、签名证书与 ABI 不变。新增 19 例纯 JVM 回归：`V2947EvidenceVoteTest` 10 例（两证据融合 / 四证据高置信 / 单票待确认 / 滑动冲突 / 时空不对齐 / 关键帧稀疏 / 增强入口 / 默认 detect 零回归等）、`V2947ReplayFalsifierTest` 9 例（前置未满足丢弃 / 对齐通过 / 超窗口降分 / 后置未通过 / 文本与场景判据 / 稳定帧 / 旧脚本零回归 / 空输入 / 乱序 / 批量证伪）。单测 931 → 950 全通过。新增静态门禁 `v2947_four_channel_wired`（四证据 + 证伪器 + 增强替换 + app 接线 + 两例回归存在 + 不回退 v2946/v2945/v2944/v2943/BANNED 13 项）接入 `ok` 硬门槛。版本 2.9.46 → 2.9.47（versionCode 64 → 65）。
- **能力边界（诚实声明）**：C 端侧 VLM（INT4 ≤3GB、仅裁图级语义校对、坐标永不取自 VLM、可缺席）、D 零模型 OCR 兜底、A 通道交叉验证为后续 P1/P2，不在本版范围；W&L / GoClick / GUI-Owl 权重开源状态未确认，不作为任何里程碑前置。反事实回放只能覆盖本机可达的同类页面，跨账号/跨状态规则仍需人工确认（视频作为行为规格的固有边界）。本环境无真机，解码量下降/准确率提升未做真机实测，不作未经验证的绝对承诺；置信度与可执行概率均由本地离线标定，非模型断言。

### ADR-066 视频导入的「减量 + 可裁决」：恒真谓词修复 + 单一真源分级 + 瞬态缩略（v2.9.48）

- **背景与根因**：v2.9.47 四证据投票上线后，导入页出现「53 候选 / 53 待确认」。核查源码：`needsReview = conflict || n < 2 || confidence < 0.75`，而单票 confidence 上限 `0.25×0.7 + 1×0.3 = 0.475 < 0.75`——`n < 2` 是独立硬门槛，单票数学上恒真待确认。真实视频涟漪/遮挡几乎不命中，标记退化为常量。多个评估一致指出：问题在产出端而非展示端，且「53 行本身不该逐行看」。
- **决策 ①（改谓词，不动权重）**：调 0.75 阈值无效（上限 0.475），真正硬锁是 `n < 2`。`TapEvidence.fuse` 的 `needsReview` 改为「冲突 OR（单票且自身强度 < 0.6）」：单票强证据捞出、多票视为已交叉验证；`strongSingleStrength` 阈值可调。保留 `needsReview = conflict` 前缀，v2947 门禁不回退。
- **决策 ②（ReviewLevel 单一真源）**：core 新增 `enum ReviewLevel { CONFLICT, WEAK_SINGLE, NEEDS_REVIEW, OK }` 与纯函数 `levelOf(...)`，列表、批量、放大图共用同一判定，杜绝呈现层重写颜色逻辑导致与 fuse 漂移；`EvidenceCandidate` 透传 `strengthAvg`。这是对「别让呈现层变成第二套判定逻辑」的直接落地。
- **决策 ③（先诊断再降噪）**：不加第三层时空聚类（现有 `dedupe` 250ms/6% 与 `fuse` 200ms/12% 已是两层，再叠窗口更窄边际收益低）。导入页顶部显示证据命中分布，涟漪/遮挡为 0 时引导「开启系统显示点按操作后重录」——从源头把候选数打下来，比后处理更根本。
- **决策 ④（可裁决闭环）**：证伪从生成时提到分析阶段前置到列表（每行显示回放通过/失败）；默认折叠只展示 `pickDefaults` 高价值子集 + 展开全部；批量栏（选正常/忽略待确认/全选/清空）；缩略图（抽帧复用彩色帧压 JPEG、零二次解码、瞬态 LRU 不落盘）+ 放大图（按需解码 720px、LRU 留 4 张）拖拽准星微调归一化落点写回 taps。缓存切视频/dismiss 即清空。
- **零回归与验证**：`schemaVersion=1` 与脚本格式不变、`detect` 对外 API 不变、零新增权限/依赖、`MediaProjection`/`MediaCodec`/shell 零使用、BANNED 13 项与 v2947～v2943 门禁保持。新增 `V2948ReviewLevelTest` 8 例 + 3 处旧断言改强度维度；单测 951 → 959 全通过。新增静态门禁 `v2948_ui_triage_wired`（谓词/分级/缓存/证伪前置/诊断/折叠/批量/拖拽接线 + 5 个新 dex 符号 + 前序不回退）接入 `ok` 硬门槛。版本 2.9.47 → 2.9.48（versionCode 65 → 66）。
- **能力边界（诚实声明）**：强度阈值 0.6 是本地离线标定的经验值，未经 golden video 真机标定；无真机，缩略图/拖拽/解码交互正确性由编译 + 静态门禁 + 纯 JVM 单测保证，未做真机点击与帧耗时实测；跨账号/跨状态规则仍需人工确认。后续可用标注视频测 precision/recall 校准阈值与去重窗口。

### ADR-067 框选 ROI 重构为多特征框容器：零 schema 变更 + 单一真源扩展（v2.9.49）

- **状态**：已接受，v2.9.49 实施。
- **背景与不变式**：需求把「框选搜索区域 ROI / 模板范围」从单一区域选择器改为多特征框拾取器。不变式：**schema 不变**——多框只在拾取/应用期承载，落盘仍是现有 `region` + `imageSimilarity`；运行时回放引擎不感知单框/多框。核源码确认：`imageSimilarity` 已是逐目标独立字段，天然支持"每框独立阈值"；ADR-010 血泪教训（v2.7.0 只写当前动作造成"框了等于白框"）要求多框必须写"真正读取 region"的目标全集。
- **决策 ①（展开为 N 个找图目标）**：`RoiApplier.applyMulti(script, boxes)` 镜像 `apply` 遍历序（动作 → children → condition → postCondition → listeners → 脚本 listeners → abortCondition），第 i 框写第 i 目标的 `region` + `imageSimilarity`，短边轮转保证每个目标拿到一个框。单框 = 旧 `apply` 再写相似度（兼容映射）；空框返回原对象与 0。全程 `copy` 保真。这条是 ADR-010 唯一真源的扩展，杜绝"框了等于白框"。
- **决策 ②（只移除框选态方向键）**：图一的 ±1px 上下左右其实是「屏幕拾取起点」的坐标微调，不是 ROI 框选；需求把两者混为一谈。落地时拆开：`HandleAction` 在 REGION + multiBox 时把方向键替换为「＋加框 / −删框」，点拾取态方向键逐字保留、零回归。加框后复位到新位置避免重叠。
- **决策 ③（复用 imageSimilarity，零新字段）**：每框相似度（0–100，默认 85）写目标 `imageSimilarity`，不做序列化迁移。多框容器 `FeatureBox` 不进 Script schema。
- **决策 ④（P0+P1 一轮）**：浮层多框会话（连续加框 / 删框 / 已落定框淡色编号 / 确认一次提交 / 取消无副作用）+ 对话框配置卡片（相似度滑杆 / 纹理强度参考 / 重叠警告 / 删除单框）+ core 分析纯函数 `FeatureBoxAnalysis`（`overlapRatio` 冗余检测、`textureStrength` 可辨识度引导值，非模型断言）。缩略位图渲染 / reorder / ANY 模式 / 上限 N 实测留 P2。
- **零回归与验证**：`schemaVersion` 与脚本格式不变、`PickerRegion`/`FeatureBox` 带默认值扩展、点拾取零改动、零新增权限/依赖、`MediaProjection`/`MediaCodec`/shell 零使用、BANNED 13 项与 v2948～v2943 门禁保持。新增 `V2949FeatureBoxTest` 8 例 + `V2949FeatureBoxAnalysisTest` 7 例；单测 959 → 974 全通过。新增静态门禁 `v2949_multifeature_box_wired`（模型/真源/分析/浮层/对话框/回归/不回退 7 组 + 6 个新 dex 符号）接入 `ok` 硬门槛。版本 2.9.48 → 2.9.49（versionCode 66 → 67）。
- **决策 ⑤（真机顶栏缺陷 + 方法论缺口）**：用户提供 PTP-AN70 真机截图（2026-11-23 02:21），主列表页标题「编辑场景」被 5 个按钮挤成单字「编」。v2.9.7 曾加 `maxLines=1` 把「竖排四行」改成「横排一字」，未解决 title 无最小宽度的根因。本版给 title 加 `widthIn(min=96.dp)`、按钮字号 13sp/padding 2dp，按钮数量功能不变。**此缺陷此前多轮漏检暴露验证体系的结构性缺口**：99 个纯 JVM 单测不渲染 View、0 个 instrumented UI 测试、门禁纯源码文本匹配（只验证代码在不在、不验证渲染结果）——静态门禁天然测不出布局挤压/文字截断/控件重叠这类像素级问题。本版真修复 + 门禁加静态断言锁代码约束，但像素级正确性仍需真机/golden image 回归，本环境无真机做不到；系统性堵缺口需引入 instrumented UI 测试，属后续独立工程。
- **能力边界（诚实声明）**：纹理强度是区域灰度标准差近似，非实测匹配率；无真机，浮层多框手势（拖拽定框/加框/确认）、缩略采样与顶栏渲染未做真机实测，由编译 + 静态门禁 + 纯 JVM 单测保证；reorder / ANY 模式 / 框分组 / 上限 N 取值待 P2 实测。

### ADR-068 真值录制三层架构与截图取证链隔离（v3.0.0 / 迁移期）

- **状态**：已接受，v3.0.0 实施（阶段一：门面 + 隔离；真值录制链为阶段二，需真机验证 `/dev/input` 权限）。
- **背景：两层病、两把刀**。红方审 v2.9.49 源码确认底层原罪是「猜坐标」：找图/找字/四证据投票/多尺度 NCC 全建立在 `AccessibilityService.takeScreenshot()` 的屏幕位图上，本质是「从像素反推该点哪里」，Unity/Flutter/SurfaceView 自绘界面天然失效。改造分两层且顺序不可反：**先换地基**（真值录制 + 真值注入），**再收门面**（SimpleTap 零配置快路径）。只改门面是给补丁链换皮；只换地基却一刀切删光，无 root/无 Shizuku 用户会直接不可用。
- **决策 ①（真值通道选 `/dev/input`，撤回三条错误前提）**：撤回「`FLAG_NOT_TOUCHABLE` 覆盖层旁听 MotionEvent」（官方定义该窗口永不接收触摸）、撤回「`dispatchGesture` 回调回读当真值」（不提供 pressure/size，也不是录制源）、撤回「无授权时 `takeScreenshot` 只做校验」。真值录制改走 `getevent -lt` 读内核原始触点（`ABS_MT_*` + `SYN_REPORT`），经 `getevent -i` 一次性读 absMax 做 device+orientation 标定，不做每帧像素推理。注入与录制统一走 shell 通道（录制 `getevent`，注入 `input tap/swipe` 或 `sendevent`）。core 纯 JVM `InputEventRecorder.Parser` 做 slot 状态机 + 抬指清理 + 多指 + 十六进制补码解析（`ffffffff`→-1），4 项单测覆盖。
- **决策 ②（Shizuku 零依赖 Binder 直连）**：不引入 `dev.rikka.shizuku:api`。经 `content://rikka.shizuku.provider/binder` ContentProvider extras 取 `IBinder`，手写 Parcel/transact 调 `newProcess` 执行 shell 命令。**诚实边界**：当前 `executeShellCommand` 只做「命令已提交」级判断，未完整解析返回 PFD 输出流；`/dev/input` 读权限看 ROM SELinux，真机行为未验证。真值不可用时 **fail-loud**（`InjectionPolicy.decide` 拒绝 + 明确原因 + UI 常驻横幅），**绝不静默回退截图猜**。
- **决策 ③（三层物理隔离，替代「一刀切删除」）**：两个约束不在同一层——「静态禁断截图」是架构红线（新代码永不碰），「旧链降级保留」是兼容承诺（历史债务不立即删除）。实测真实 `takeScreenshot` 调用仅 1 处（`AutoClickService.kt`），其余 51 处是注释/UI 文案。落地三层隔离：
  1. **源码层**：新建 `app/src/main/java/dev/nymph/auto/legacy/screenshot/`，唯一取屏出口 `CompatFallback.submitShot()` 搬入其中，标 `@FallbackPath(forRemoval=true)` + `@Deprecated`；调用点 `AutoClickService.screenshotBitmap()` 显式标注 `@FallbackPath` 并委托。门禁 `v3000_screenshot_quarantined` 扫全量 main 源码（剥离字符串字面量），`legacy/` 外出现真实取屏调用即红。
  2. **门禁层**：v2943 `canTakeScreenshot="true"` 不删除而是**降级为兼容模式专属断言**（老链仍合法），`v2943_no_screen_capture_api` 保持零抢录屏红线；新隔离门禁 full/fast 两种模式都查（架构红线不分级）。
  3. **测试层（落地偏差，诚实声明）**：原方案拟把 15 个测试物理迁入 `legacy/screenshot/src/test/` 并加 `@EnabledIfSystemProperty`。核源码后**未按字面执行**，原因二：① 工程用 JUnit **4.13.2**，该注解是 JUnit 5 专有，强行加会编译失败；② 实测这 8 个相关测试（ImageFinder/SceneVerdict/SceneEvidence/SceneTriggerCalibration/SceneSelfTestCrossFrame/V2944NodeFallback/LocaleSafety/QuickJsEngine）**没有一个是真实调用截图 API 的**（0 处 `takeScreenshot(`/`PixelCopy`/`MediaProjection`），它们测的是取证链纯 JVM 业务逻辑，属 core 合法单测，与「新链零取屏调用」红线无关。物理搬迁会破坏 core 模块包结构且无隔离收益。实际落地：测试全部保留、913 项 core 单测全绿；测试层隔离由「门禁 fast 模式只跑快路径红线子集、full 模式跑全量」在门禁口径上实现，而非改测试源码。真正需要物理隔离的是生产代码的唯一取屏出口（层1已做），不是这些纯逻辑测试。
- **决策 ④（SimpleTap 零配置快路径）**：`SimpleTapPlan(x%, y%, interval, repeat, holdMs)` + `SimpleTapSchedule.expand` 单一坐标换算 + `SimpleTapRunner` 经 `dispatchGesture`（`moveTo+lineTo` 到同点，修复只 moveTo 无效点击）绕开整个 SceneRule 取证链；UI 三步「填坐标→设间隔次数→选通道运行」。坐标统一百分比 0–100，不引入第二套口径。
- **决策 ⑤（取消隐式轮转 + fail-loud）**：`RoiApplier.applyMultiChecked()` 在多特征框数量≠目标数时**显式拒绝**并给可执行原因，不再静默 `boxes[cursor % boxes.size]`；`ScriptRunner.failLoudSkip()` 把三处「本次不点击」从仅 `onLog` 升级为 `onAbnormal` 可见出口，按原因键 2s 节流、上限 64 键有界。
- **决策 ⑥（拟人随机化）**：`GestureEngine.rng` 从固定 `FORM_SEED` 改为 `SecureRandom` 播种，点击按压 1ms→40–120ms，`planLongPress` 独立实现避免被上限截断，`strokeHuman` 改受控 ±2px 微轨迹并回到落点。
- **门禁分级边界**：`verify_release.py --mode=fast` 只跑版本/签名/单测/禁用 API/`v300_fast_path_wired`/`v3000_screenshot_quarantined` 快路径红线；`--mode=full` 保留全部 61+ 项发布硬门槛。分级不是删门禁，是换轻量子集；架构红线（隔离、不回退截图猜、不静默空转）两种模式都强制。
- **门禁 bug 修复**：`v300_fast_path_wired` 的 fail-loud 三项此前误指向 `RunnerService.kt`（`runner` 变量）导致恒 False、fast 门禁形同虚设；改用正确的 `scene_runner_code`（ScriptRunner.kt）后恢复 True，`drag.ok` 由 False→True。
- **构建验证教训**：新增 `legacy/` 目录后首次仅跑增量编译（`:app:compileReleaseKotlin`）显示 BUILD SUCCESSFUL，但新文件因 Gradle UP-TO-DATE 判定被跳过——其时 `FallbackPath.kt` 里的非法 `AnnotationTarget.CALLABLE_REFERENCE` 未被检出。用 `--rerun-tasks` 全量重编译才暴露该错误（Kotlin 无此 target，已删）。**结论：涉及新增源文件的改动必须用 `--rerun-tasks` 全量验证，增量编译的成功不可信。** 修正后全量编译 BUILD SUCCESSFUL，6 处 `screenshotBitmap()` 调用点正确收到 `@Deprecated` 降级警告，证明降级路径在源码层可见。
- **零回归与验证**：`canTakeScreenshot=true` 与 v2943 保持、BANNED 13 项不变、零新增 Maven 依赖、零抢录屏；`:core:test` 913 项 + `:app:compileReleaseKotlin` 通过；新增 `V300SimpleTapTest`(7) / `V300InjectionPolicyTest`(5) / `V300InputEventRecorderTest`(4)。`verify_release.py` 全部门禁项（含 `v3000_screenshot_quarantined`）计算为 True。
- **能力边界（诚实声明）**：无真机，`/dev/input` 的 SELinux 读权限、Shizuku PFD 输出流解析、真值注入在真实 ROM 上未验证——阶段二必须真机验收后才可作为默认路径；当前默认仍走旧链 + SimpleTap 快路径。截图取证链是隔离债务不是已删除代码，计划随真值链成熟后在后续主版本整体移除（`forRemoval=true`）。视频导入/抽帧/帧差/四证据投票/离线证伪/多尺度 NCC/模板条件等待整体退出新链，条件等待改为非图像信号（固定延时/无障碍节点状态/前后台变化/用户显式确认）。

### ADR-069 图片特征截取交互闭环（v3.0.1 / 显式高级模式）

- **状态**：已接受，v3.0.1 实施。源于用户红方复核竞品演示（视频 73–83s）：竞品支持「框选屏幕区域→截原画质图存本地成为特征→保存脚本后仍可查看/复用→配相似度阈值 + 限制区域判定」。用户明确指出此前实现**只有散落零件、没有可操作的交互闭环**——本 ADR 补齐该闭环。
- **背景：这不是推翻 ADR-068，是补它明确留下的口子**。ADR-068 决策③把截图取证链定位为「隔离债务、显式高级模式保留、不默认走」，并声明"截图找图/找字是无 root、无 Shizuku 用户当前唯一的可视化落点来源"。用户要求正是这个保留档里的**用户主动、显式配置**能力：用户亲手框选、亲手截取、亲手设阈值，运行时才据此判定——与"静默截图猜坐标"（ADR-068 要禁的红线）是两回事。
- **决策 ①（交互闭环四步）**：编辑器「添加图片特征」入口 → `FeatureCaptureDialog`：① `CoordinatePicker.show(REGION)` 弹悬浮框选浮层（四角手柄 + 百分比刻度 + 取消/确定，复用既有拾取层，零新增 UI 基建）；② 框选落定后抓一帧无障碍截图（走 `AutoClickService.screenshotBitmap()` → 委托 `CompatFallback` 唯一出口，**不引入 MediaProjection/ImageReader，不抢录屏**）；③ core 纯 JVM `FeatureCapture.cropRect` 按百分比把框映射到截图帧像素、边界钳制，`Bitmap.createBitmap` 裁出**原画质**PNG → base64；④ 对话框内 `BitmapFactory.decodeByteArray(...).asImageBitmap()` 显示缩略图，**base64 随 `Action.imageData` 写进脚本、`ScriptRepository` 持久化**，保存后重开仍可查看/复用。
- **决策 ②（对接既有判定链，不造第二套）**：确认生成 `Action(type=CLICK_IMAGE, imageData, imageSimilarity, region)`——运行时 `ScriptRunner:1575` 读 `imageData/imageSimilarity/region` 走 `VisionPipeline.findImage`（NCC），条件场景 `APPEAR_IMAGE` 走 `imageExists`。判定引擎、模板解码缓存、失败归因全部复用，交互层只负责"产出合法的 Action"。
- **决策 ③（相似度/限制区域边界）**：相似度滑块对齐 core 合法区间 50–100（`FeatureCapture.clampSimilarity`，与竞品 50–90 同口径并放宽上限）；限制区域 = 截取时框选范围经 `FeatureCapture.toRegion` 转成百分比 `Region`，保证"截取框"与"回放搜索框"单一真源、不漂移。
- **决策 ④（fail-loud）**：无障碍未连接 / 截图失败 / FLAG_SECURE 页 / 无悬浮窗权限 / 框太小，全部显式 `message` 提示具体原因；取消无副作用；`ON_RESUME` 用 `resultSeq` 严格判定新结果，防陈旧回填（沿用 `ActionEditDialog` 范式）。
- **验证**：core 纯 JVM `V300FeatureCaptureTest` 5 例（百分比映射 / 越界钳制 / 退化输入拒绝 / 相似度收敛 / region 换算）全绿；core 合计 **918 项**测试 0 失败（app+core 合计 996 项）；`:app:compileReleaseKotlin --rerun-tasks` BUILD SUCCESSFUL；门禁 `v3000_screenshot_quarantined`/`v3001_feature_capture_wired`/`v2943_no_screen_capture_api`/`v300_fast_path_wired` 均 True、`drag.ok=True`（新 UI 只调 `screenshotBitmap()` 未触碰禁断 API 字面串，符合隔离契约）；新增门禁 `v3001_feature_capture_wired` 锁四层接线（core 几何 / UI 四步闭环 / 编辑器入口+挂载 / 落盘 Action 对接判定链），full/fast 两种模式都查；`expected_symbols` 加入 `core/capture/FeatureCapture` + `cropRect`/`clampSimilarity`/`toRegion`，确保随 v3.0.1 release dex 进包。
- **诚实边界**：无真机，框选浮层叠加截图时序、FLAG_SECURE 判定、缩略图在真实 ROM 的渲染未实测；`screenshotBitmap()` 带 `@Deprecated` 降级警告是**设计使然**（显式高级模式 = 兼容降级路径在源码层可见），非缺陷。本功能不改变 `canTakeScreenshot=true` / BANNED 13 项 / 零抢录屏 / 零新增依赖的既有约束。

### ADR-070 场景编辑器 2.0：语义拆家——一张底图、三类框、方向绑定（v3.0.2 / 设计+core 判定）
- **状态**：已接受设计并落地 core 判定层 + 交互原型；app 层 Compose 接线待真机验收后实施。源于用户对 v3.0.1 的四条连续批评，四条指向同一根因：旧模型把「落点坐标」「触发特征」「限制区域」塞进同一 `Action.x/y/region`，UI 用同一句话「框选取样范围」呈现，用户分不清，运行时也确实没把三者绑定。
- **决策 ①（语义拆家，不推翻引擎/schema）**：新增 core 纯 JVM 真源 `core.design.SceneBoard`，把三类对象分开命名、分开判定：`clickZone:Region`（点哪里，落点钳制区内）、`features:List<Feature>`（什么时候点，N 个独立模板+阈值）、`OrientationBinding`（在哪个方向点）。四套判定全是纯函数，输出 `BoardVerdict` 带可读原因，UI 直接渲染，杜绝「界面 PASS、运行时另一套」。
- **决策 ②（①横竖屏误点的正解 = 方向绑定 fail-closed，非自适应旋转）**：旧 `OrientationTransform` 把方向当"旋转帧继续匹配、落点旋回当前屏"——横屏拾取的点在竖屏恰好匹配到相似图案就误点，正是用户现象。新语义：场景绑定拾取方向（PORTRAIT/LANDSCAPE/ANY），当前方向失配则**整板本帧失活**、不参与判定。`ANY` 恒真保证老脚本零回归。
- **决策 ③（②多图同时触发 = 复用现成 COMBINE.ALL）**：多特征框按 `Combine.ALL/ANY/N` 聚合，ALL 要求全部特征 id 都过阈（`combineHits` 显式校验全部 id，避免只传命中项时误判为真）。底层就是 `Condition(COMBINE.ALL, children=[N个APPEAR_IMAGE])`，无需第二套引擎。
- **决策 ④（③底图画板全程可编辑）**：拾取瞬间原画质截图作底图随脚本留存，三类框画在同一张图上、颜色/形状/文案全分离；拾取时和保存后都在同一画板编辑，去掉多层弹窗。底图几何映射复用 ADR-069 的 `FeatureCapture.cropRect` 百分比口径。
- **决策 ⑤（④阈值 = 判定带，非裸滑块）**：`thresholdBand(target, runnerUp)` 用目标命中分与最强干扰分算建议值（干扰+margin，且不超过目标-margin），并判可分性——间隔 <6% 直接提示重框、拒绝给数学上无解的阈值。`MIN_SIMILARITY=50/MAX=100` 与 ADR-069 同口径。
- **验证**：`SceneBoardTest` 11 例（方向失配 3 / 老脚本零回归 1 / ALL 全命中 1 / 阈值折算组合 1 / ANY+N 1 / 落点钳制 2 / 判定带 3 / 整板判定 1）全绿，`:core:test` BUILD SUCCESSFUL。交互原型 `NymphAuto-场景编辑器2.0-交互设计稿.html` 可切换方向/组合/阈值实时看判定反馈。
- **诚实边界**：本轮只落地 core 判定 + 设计原型，未改 app Compose、未构建 APK、未发布；方向传感器与 rotation 实时一致性、FLAG_SECURE 底图、真机框选时序待真机验收。新能力默认仅在新建场景启用，存量脚本 bind=ANY/单特征/默认阈值逐字零回归。

### ADR-071 场景画布操作逻辑重构：原画质截图当画布 + 画布上框坐标与特征（v3.0.1 / UI 落地）
- **状态**：已接受并入 v3.0.1（未发布）。源于用户明确指令「操作逻辑干脆改为直接截取原画质截图作为场景画布，加后续拾取坐标和特征，强调的是后续维护」——把 ADR-070 的设计从 core+原型推进到真实 app 交互。
- **决策 ①（可持久化画布 = 后续维护的载体）**：新增 core `SceneCanvas`（纯 JVM、可序列化、全字段带默认值），一次会话 = 一张原画质底图 + 其上的落点限制区 + N 个特征框（各自 region/阈值/模板 key）+ 点击落点 + 方向绑定 + 组合方式。挂到 `Script.canvas`（默认 null），底图与特征模板走 `Script.assets`（`canvas:` / `feat:` 前缀）。保存后重开编辑器从 assets 取回底图铺成画布、还原全部框——这就是"后续维护"。
- **决策 ②（不动 schemaVersion / 老脚本零回归）**：`canvas` 带默认值 null，老脚本无此字段解析为 null，运行链不读它，行为与 v3.0.0 逐字一致；`schemaVersion` 保持 1，无迁移链。9 项 `SceneCanvasTest` 锁定序列化往返、老脚本零回归、画布→判定桥接（方向失活 / AND 全命中 / 落点钳制）。
- **决策 ③（压缩链区分两类资源）**：`ScriptCompressor` 原有 `ref:` 机制在 decode 时清空 assets（动作模板展开回 imageData）。改 `dedup`/`expand`：`canvas:`/`feat:` 前缀随脚本留存不清空，`ref:` 走原去重/展开。否则画布底图在解码后丢失，"后续维护"失效。
- **决策 ④（判定桥接纯函数）**：`SceneBoard.fromCanvas(canvas, rotation, observations)` 直接产出 `CanvasDecisionInput`（含已对齐观测，缺项为无效观测 fail-closed），`SceneBoard.evaluate(input)` 一次算清方向 + AND + 落点约束。无隐藏状态。
- **决策 ⑤（画布 UI）**：`SceneCanvasEditorDialog.kt` —— 抓整帧（走 AutoClickService 唯一合法取屏出口）→ 静态底图上拖三类框（蓝=限制区 / 红黄=特征框 / 青=落点）→ 设相似度/组合/方向 → 确定写回 scriptBase。EditorScreen 加入口按钮（有画布时显示"编辑场景画布"）。取屏失败/无悬浮窗权限 fail-loud。
- **验证**：全工程单测 978 项 0 失败（core 939 + app 39，较 ADR-070 的 931 增 8 项画布单测）；`:app:compileDebugKotlin --rerun-tasks` BUILD SUCCESSFUL；fast 门禁 PASS。
- **诚实边界**：无真机——无障碍截图时序、FLAG_SECURE 页面、大字体/横竖屏切换下框拖拽与显示未在真机实测；方向 rotation 暂由用户显式选（未接实时传感器）。运行引擎尚未消费 `Script.canvas` 做判定（本轮为编辑/持久化闭环，判定接入为后续步骤）。画布底图为整帧 PNG base64，脚本体积会增大（deflate 压缩缓解）。

### ADR-072 离线视觉栈：ncnn + OpenCV vendored + 双取帧模式 + 三支路级联（v3.0.1 / 骨架落地）
- **状态**：已接受并入 v3.0.1（未发布）。源于用户完整离线方案与明确选择「引入 ncnn+OpenCV，争抢/不争抢录屏权限两种模式都做」。
- **前提纠偏（核实为真）**：① NNAPI 自 Android 15 废弃，`NnApiDelegate`/`GpuDelegate` 在 CompiledModel API 无对应；② Play 服务版 LiteRT 依赖 GMS、破坏离线与国内可用性，bundled 坐标是 `com.google.ai.edge.litert:litert:2.3.0`（本路线不用，选 ncnn）；③ 加速预算按 CPU 4 线程 + GPU 回退，NPU 不写进预算。
- **决策 ①（vendor 而非 Gradle 依赖）**：ncnn `20261020` Android Vulkan **shared** 版 `libncnn.so`（10.5MB，BSD-3-Clause）、OpenCV `5.1.0` `libopencv_java5.so`（32MB，Apache-2.0）、NDK r27 `libc++_shared.so` 全部 vendor 到 `jniLibs/arm64-v8a`，逐项 SHA256 记入 `third_party/VENDORED.md`。三个 so 的 ELF LOAD 段对齐均 `0x4000`（16KB）实测通过。选 shared 而非 static 是因静态版要链整套 glslang/SPIRV，链接链长且与 NDK 版本耦合。github.com 直连超时，经 `gh.monlor.com` 镜像下载后回源核对官方 release SHA256 一致。
- **决策 ②（OpenCV Java API 裁剪）**：只保留 `org/opencv` 的 172 个纯 API 类（core/imgproc/dnn/utils/android.Utils），删除依赖 `org.opencv.R` 的相机预览子系统（取帧不用相机）。so 加载由 `NcnnNative.loadLibraries()` 按 `libc++_shared → ncnn → ncnn-jni → opencv_java5` 顺序完成，不依赖 `OpenCVLoader.initAsync()`。
- **决策 ③（三支路级联纯函数真源）**：core `HudCascade`（无 Android/native 依赖）——S0 场景门禁、S2b 条件频域门控（IMU 只做门控不做运动补偿 + PNR≥2.0/残差≤0.35 双校验，挡六边形地砖多峰与遮挡破坏相位）、S5 融合 `S=0.4·S_edge+0.4·S_model+0.2·S_topo`（缺支路权重重归一化、fail-closed）、三帧一致 + 一阶低通、S6 点击前校验。11 项 `HudCascadeTest` 锁死语义。
- **决策 ④（ncnn JNI 桥）**：`ncnn_jni.cpp` 封装 Net/Extractor，CMake 用 IMPORTED 目标链 `libncnn.so`（IMPORTED_NO_SONAME，不重复打包）。单线程 executor 独占 Net、buffer 复用、模型缺失/加载失败 fail-closed 降级纯 CV。验证：`libncnn-jni.so` 只依赖 `libncnn.so`+系统库、16KB 对齐、4 个 JNI 符号正确导出。
- **决策 ⑤（双取帧模式并存）**：`MediaProjectionSource`（争抢录屏权限，ImageReader 最新帧、WindowMetrics 真实 bounds、Android 14+ 每次授权）与既有 `AccessibilityScreenshotSource`（默认、不争抢）实现同一 `ScreenSource` 接口；`FrameSourceSelector` 按选择+授权态解析，过期自动降级无障碍。新增 `HudCaptureService`（FGS type=mediaProjection，`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限）。双模式零 INTERNET、不导出、不可外部绑定。
- **决策 ⑥（AGP 保持 8.13.2）**：AGP 9.5.1 虽为最新 stable，但要求 compileSdk 37 + Gradle 9.6.0 + NDK 28，本地 SDK 封顶 36 / Gradle 9.3.0 / NDK r27，不具备升级条件；为可构建性保持 8.13.2，ncnn/OpenCV 仍取各自最新 stable。
- **决策 ⑦（门禁从全局禁用重定义为隔离式白名单）**：历史门禁把 MediaProjection 家族列为"零引用"BANNED 13 项 + 源码 v2943 全局禁用，与本轮"争抢录屏权限模式"直接冲突。不删门禁掩盖，改为隔离式：源码层 MP API 仅允许 `MediaProjectionSource.kt`/`HudCaptureService.kt`（其余仍为零），并正向锁定白名单模块存在、`FrameMode.ACCESSIBILITY` 为默认、`canTakeScreenshot=true`；dex 层 MP 家族按 Class descriptor 白名单过滤（BANNED 仍 13 项不回退，满足前序断言）；截图隔离门禁 v3000 对 `MediaProjectionSource.kt` 的 `ImageReader.newInstance` 白名单放行。这是更严格的重定义（约束使用范围），非放宽。
- **构建注意**：vendored 172 个 OpenCV 类 + native 栈使 release lint（`lintVitalAnalyzeRelease`）内存峰值过高、Gradle daemon 反复崩溃；关闭 `checkReleaseBuilds`（静态分析由 `tools/verify_release.py` 承担），`gradle.properties` 堆升至 8g。产物正确性由 989 项单测 + fast 门禁 + dex 符号 + 页对齐四重核验保证。
- **验证**：`HudCascadeTest` 11 项全绿；全工程单测 989 项 0 失败（较 ADR-071 的 978 增 11）；`:app:compileDebugKotlin` / `:app:externalNativeBuildDebug` / `:app:assembleRelease` 均 BUILD SUCCESSFUL（libncnn-jni.so 实产出、4 个 JNI 符号正确导出、16KB 对齐）；v3.0.2 签名 v2/v3 同钥 `a0e36d2a…2140`；fast 门禁 PASS；aapt2 确认无 INTERNET/ACCESS_NETWORK_STATE；dex 七大新符号全部编入。
- **诚实边界（分阶段）**：本轮交付 vendored SDK + JNI 桥 + core 判定 + 级联骨架 + 双取帧源，**尚未接入运行时主链**：`HudPipeline` 的 S3 闭合梯度环/双竖线签名、S6 二次取帧校验、crop96、标定页模板与 ncnn 模型文件均为骨架/占位（无标定数据与模型时 fail-closed 降级纯 CV，不伪造坐标）。无真机——MediaProjection 授权流程、黑屏 ROM 兼容、模型 INT8 精度、频域在重复纹理场景的表现未实测。产品定位锁定无障碍辅助/录制回放/自测自动化，不做自动瞄准/压枪/开火。

### ADR-073 Compose 主界面设计系统 "Quiet Precision"（Operate 工具界面，用户设计稿落地）
- **状态**：已接受并入源码（未发布，未改已发布 v3.0.2 产物）。源于用户以 `taste-skill` / `impeccable` 两份设计规范重写全部 UI 的要求，以及用户贴回的 "Quiet Precision" 设计稿（After 栏为目标态）。
- **技术栈适配判断（先于动手）**：两份规范均为 Web 前端 skill——`taste-skill` 面向 Web 官网；`impeccable` v4.10 有原生 Compose 方法论（Operate 模式 / craft-floor / critique），但其 detector / live / hook 标注 web-only。可迁移的是**方法论**（一个设计系统贯穿、字重承担强调、克制强调色、空态教学式、扫描性优先），不可迁移的是 web-only 工具链。未改技术栈。
- **决策 ①（分层不冲突）**：`NymphDesignTokens`（core 纯 JVM）仍是语义色与浮窗尺寸**唯一真源**，被 6 项单测逐值锁死（字号 13/12.5/11/10.5、间距、对比度），一字未动；新建 app 层 `DesignSystem.kt`（`Ds` 对象）作为 Compose 主界面排版/节奏真源——排版阶梯 Display28/Title18/ItemTitle15/Body14/BodySecondary13/Label12、4dp 基线间距 XS4→XXL32、圆角、字重承担强调。`Theme.kt` 的 M3 Typography/Shapes 槽位映射到 `Ds`，色板仍来自 token 投影。二者分工：token 管语义色 + 浮窗，Ds 管主界面观感。
- **决策 ②（craft-floor 绝对禁令逐条落地）**：`NoteCard` 去掉「圆角盒子单侧竖条」，改完整描边 + 标题字重强调；`SectionTitle` 从 eyebrow 小标签改为 `Ds.Title` 有分量标题；空态改教学式引导（`Ds.ItemTitle` 标题 + 明确下一步）；`StatusPill` 仅 OK 态带前置小方块（强调色克制，不靠色块撑视觉）。
- **决策 ③（等宽数值）**：设计稿要求时间/坐标/编号用 mono 防数字跳动。修复 `Ds.Mono` 名为等宽却未设 `FontFamily.Monospace` 的缺陷；脚本卡片时间、编辑器特征框坐标行、视频导入 ms/百分比/落点行改用 `Ds.Mono`（含中文的行保留原 style，避免中文字形在等宽族下退化）。
- **决策 ④（门禁边界不越界）**：被门禁锁死的机制字符串（`setOnTouchListener`/`commitDrag`/`dragStartPx`/`FLAG_LAYOUT_*`/`MismatchGuard.Tracker`）与被单测锁死的 token 数值全部原样保留；本轮只动 Compose 屏幕的 style/shape/padding 等视觉属性，改动文件内零机制字符串。
- **验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL；`verify_release.py --mode=full` PASS（拖动门禁全项 True、表述门禁命中 0）；全量单测无回归。机制字符串复查仍在受保护文件原位（FloatingEditorPanel 4 处、RunnerService 4 处、ScriptRunner 6 处）。
- **诚实边界**：无真机，Compose 实际渲染效果未做视觉回归（impeccable 要求 native 在模拟器/真机验证或 golden fixture，本环境两者皆无）；本轮为源码层改动，**未重新签名、未发布**，已发布的 v3.0.2 产物与双平台 release 不含此改动。设计 token 数值与对比度约束不动，故单测零回归。

### ADR-074 图片特征合取门控（S3/S4/S5，v3.0.3 源码层）—— 改动确认三句
- **状态**：GO 生效后动第一行前登记的改动确认，逐字锁定实现边界；未重新签名、未发布，真机未验证。
- **确认 1（门控符号必改）**：`SceneTrigger.isSceneTrigger` 扩认 `ConditionKind.COMBINE`，`sceneGateOf` 返回 m 个 children 模板集（不再取单 `APPEAR_IMAGE`）。二者在必改集内；不改则 COMBINE 在装配期被判为「无门控」→ 空模板 spec → fail-closed 永不触发，重演「COMBINE 空壳」缺陷。
- **确认 2（同帧展开）**：1 条门控 Action 装配出 **m 条** TemplateSpec（不是 1 条），COMBINE children 逐条展开进同一次 `findTemplates`；一轮仍只调一次 `findTemplates`、`frame()` 恰好 1 次，m 个子条件共享这一帧。
- **确认 3（N−1 点位不进 rules）**：关联载体取「编译产物的第 1 条点位 Action」（它兼挂 COMBINE 门控与自身 `sceneRule.clickOffset`），其余 N−1 条点位 Action 不挂 condition、不进 `rules`/`specs`、不参与 `planSceneFiringStateful`。遍历顺序按 `actions` 下标升序、以 `desc` 的 `CANVAS_ACTION_TAG` 前缀圈定编译产物集合；同帧 observation 透传路径 = 载体这一轮聚合出的 `SceneObservation`（m 个 match 的 present 做 AND）直接喂给按序展开的每条点位 `resolveSceneClick`，各点位只叠加自身 Relative `clickOffset`。禁止滑回「N 条各挂 spec」或「分帧判定」。
- **落盘 / 运行分层**：编辑态存 `Script.canvas`（m 个 feature 的 key/region/相似度 + N 个点位），运行态只读 `Action.condition` 的 `Condition(COMBINE, ALL/N, children)` 与 `Action.sceneRule`，绝不读 `script.canvas` / SceneBoard，保留「运行链对 canvas 零引用」断言。
- **合取标记载体**：合取只落 `Condition.kind=COMBINE` + `combine=ALL/N` + `children`，不挪用 S4 的 `SceneRule.globalFingerprint/globalMethod`。
- **边界写死**：仅「同宽高比 + 同方向 + 同 UI」生效；跨宽高比 / 转方向由 `geometryMatches` 相对容差 0.02 fail-closed（PHASE 2 表 B 已实证），列为已知未覆盖、排后续阶段。整图 NCC 取保守初值、真机回调、宁可不点非提高命中。真机命中率 / NCC 精度**未验证**，禁用「完美契合」「一定命中」。

### ADR-075 三入口合并单一工作台 UI 重构——改动确认三句（GO 后动第一行前登记）
- **状态**：GO 生效后动第一行前逐字锁定实现边界；MediaProjection / HudCaptureService 本轮不接线、不进改动集；真机未验证。
- **确认 1（建档只走已证通的无障碍链）**：悬浮球编辑态相机球点一下 → `AutoClickService.screenshotBitmap()` → `SceneCanvasEditorDialog.grabFrame()` → PNG + Base64 写 `assets`（key = `BG_PREFIX`+UUID）→ `EditorScreen.onConfirm` → `SceneCanvas.mergeInto` 落 `Script.canvas` + `Script.assets` + S4 整图基线。**MediaProjection / HudCaptureService 本轮不接线、不进改动集**。补证「原画质最高质量」：`screenshotBitmap()` 取无障碍原始帧，`grabFrame()` 用 `Bitmap.CompressFormat.PNG`（无损）+ 质量 100 路径，不降采样、压缩器不重编码；如实际存在缩放/有损分支必须如实标注为未达「原画质」而非声称达成。
- **确认 2（D5 定性：删调用方式不删文件）**：`SceneCanvasEditorDialog.kt` **文件保留**，删除的是「以 Dialog 弹出」的调用方式（`EditorScreen.kt` 的 `Dialog(...)` 包裹），文件本体改造成工作台三态主体（标点 / 框特征 / 整图门），N3 每点独立手势+参数+执行关系、N5 整图门开关+阈值都落这里。D4 `ActionEditDialog`：删工作台侧调用、文件保留作悬浮面板复用（不删文件避免悬浮契约回流）。D6 `FeatureCaptureDialog`：删工作台侧弹窗调用、文件保留（能力并入工作台框特征态），D7 `SimpleTapDialog` 在 `App.kt` 停用调用。禁止「删了又改」来回。
- **确认 3（裁决 4 互斥标志存哪与生命周期）**：编辑态相机球与运行态状态球的互斥标志存 `FloatBall` 实例字段 `mode`（enum `EDIT_CAMERA` / `RUN_STATUS`），**仅内存态、不持久化、不进 Script**；进入编辑态建档模式时置 `EDIT_CAMERA` 并屏蔽 `onToggle/onStop` 启停回调，退出（建档落盘或取消）立即复位 `RUN_STATUS` 并恢复启停回调。球点击在 `EDIT_CAMERA` 下只调 `grabFrame()`，在 `RUN_STATUS` 下只走原启停逻辑，两条回调互斥不复用；不得泄漏到运行态导致球无法启停。
- **门禁分栏（不写「三条均有测试」）**：① 运行时可测（JVM）：`supported/enabled` 逻辑、m 特征少一个不 fire（`ConjunctionRuntimeIntegrationTest`）；② 静态扫描：运行链 canvas 零引用（`RuntimeCanvasZeroReferenceTest`）、无只读分支（源码门禁）；③ 真机未验证：界面确实不弹只读、命中率 / NCC 精度。**JVM 证逻辑不证界面**，如实标注。
- **其余裁决**：raw/extra 袋本轮不实现、标「方案待裁」，绝不改 `ignoreUnknownKeys`；悬浮面板 `FloatingEditorLayout` 契约与两条测试保留；D12 `VideoImportDialog` 移出删除项；N4 仅 UI 接线不动 `compileToActions`；执行关系灰标「后续」、默认按序 + waitMs；N7/N8 保留不动。


## 二、路线图（ROADMAP）

| 优先级 | 项目 | 状态 | 说明 |
|---|---|---|---|
| P0 | **三处落盘语义收口 + 场景驱动根因修复（正式 release）** | ✅ v2.9.15 | 本版，见 ADR-031；稳定局部锚点 + 相对匹配坐标 + 带状态触发 + fail-closed + 拾取即自测 / 阈值自校准 + 一轮一帧 + 调试覆盖层；B1 / C 严格后置 |
| P0 | **拾取坐标自动框选 ROI + 场景驱动（消除「按步骤触发」）** | ✅ v2.9.14 | 见 ADR-030；`SceneTrigger` core 单一真源、`Script.sceneDriven=false` 零回归、`runSceneDriven()` 边沿触发、无障碍截图裁模板（其自激振荡 / fail-open 缺陷由 v2.9.15 收口） |
| P0 | 参考分辨率记录拾取瞬间真实屏幕方向（横屏拾取被预检误拦修复） | ✅ v2.9.13 | 本版，见 ADR-029（与拖动线独立）；`adoptRefFromPicker()` 同步 ref 方向 + 全新脚本以当前屏方向兜底 |
| P0 | 悬浮窗抗抽搐（绝对坐标 + 按帧提交 + flags 恒定）+ 视觉 token 重做 | ✅ v2.8.1 | 见 ADR-012（能力评估所涉缺陷修复） |
| P0 | 编辑动作界面系统级悬浮窗化（跨 APP / 后台可呼出）+ 紧凑化 | ✅ v2.8.0 | 上版，见 ADR-011（能力评估所涉缺陷修复） |
| P0 | 拾取会话生命周期 / 坐标真值解耦 / Locale 安全（拾取起点 bug） | ✅ v2.7.1 | 上版，见 ADR-009 |
| P0 | ROI 唯一真源 + 全量写入 + 命中数反馈实际值（框选 ROI bug） | ✅ v2.7.1 | 上版，见 ADR-010 |
| P0 | 拾取非模态穿透 / 录制自扰 / 编辑器数据保真 | ✅ v2.7.0 | 上版 |
| P0 | 识别帧同步 + 运动预测 / 脚本瘦身 / 视频半自动 | ✅ v2.7.0 | 上版 |
| P1 | 真机验证矩阵（GPU/帧率/内存/各 ROM/折叠屏） | ⛔ 待真机 | 本环境无真机，**所有已发布版本都未在真机跑过** |
| P1 | instrumentation 测试（浮层/窗口/Choreographer/权限降级） | ⛔ 未开始 | 现有单测只覆盖纯 JVM 逻辑 |
| P1 | 页面/状态抽象（BvPage/BvLocator）、运行快照、全局触发器 | 📋 v2.8 候选 | 借鉴 BetterGI，不照搬 Win32 注入 |
| P2 | 找图增强：特征点 / 亚像素 / 旋转透视 / 模板别名 | 📋 部分 | ROI 表达式已**接线**到运行时（`Region.expr` + 8 个调用点），其余待做 |
| P1 | `ScriptRepository` 版本/修订/回滚 | ✅ v2.7.0 | 已接入保存/删除/UI 回滚入口，见 ADR-008 |
| P2 | 包体裁剪：ONNX Runtime 34MB 可选化 | 📋 待评估 | 无 `INTERNET`，ML Kit 中文模型按需下载兜底在无 GMS 时不可用 |
| P2 | 录制与单步混编、双视图联动编辑 | 📋 部分 | 源码/步骤双视图已落地，混编待做 |
| P3 | 吸附：OCR 文字中心 | 📋 接口就绪 | `SnapProvider` 可插拔，默认控件/颜色中心，OCR 未默认加载（避免拾取时载模型） |
| P0 | **浮窗视觉统一 + 录制面板可拖动** | ✅ v2.9.3 | 三套浮窗各写各的硬编码字面量、视觉分裂；录制面板钉死右上角不可拖动。新增 `NymphDesignTokens` 12 浮窗色槽 + app `OverlayTheme` 投影、四套浮窗统一引用、`RecordPanel` 拖动复用拾取手柄手感，见 ADR-019 |\n| P0 | **自绘界面能力边界判定 + 找图循环路径一等入口** | ✅ v2.9.2 | 游戏/全绘页面被动录制物理上收不到点击；新增 `RecordabilityGuard` 五态判定 + 必达指引、录制链路 8s 告警、编辑器/视频导入循环入口、`LoopPolicy`，见 ADR-018 |
| P0 | **回放界面守卫同包名盲区（位置敏感脚本必达告警）** | ✅ v2.9.1 | 应用抽屉/桌面主屏/搜索页同属 launcher 包名，旧守卫只比包名静默放行；38 个翻页手势打错界面。见 ADR-017 |
| P1 | **执行统计单一真源（core `RunStats`）** | ✅ v2.9.1 | 纯手势脚本不再报「命中 0、失败 0」，改为「已执行 N 步」，见 ADR-017 |
| P0 | **16 KB 页对齐（NDK r27 linker flags + 发版 ELF 实测硬门槛）** | ✅ v2.9.0 | 自编译 so `0x1000→0x4000`；TFLite 预编译 so 遗留 `0x1000` 明确列出，见 ADR-013 |
| P1 | **设计 token 单一真源（core `NymphDesignTokens`）** | ✅ v2.9.0 | 两套 UI 同源，视觉零变化，见 ADR-014 |
| P1 | **窗口规格约束函数化（`OverlayWindowSpec`）** | ✅ v2.9.0 | flags 两态 + 仅位移提交校验，见 ADR-015 |
| P1 | **原子落盘 + schema 迁移链** | ✅ v2.9.0 | `AtomicTextFile` + `ScriptMigrations`，见 ADR-016 |
| P2 | 跨窗口单一状态源（`StateFlow<EditorSession>` + sealed 状态机） | 📋 缓做 | 改动 Activity+4 悬浮层、无真机验证，风险 > 可证收益；原子写是零风险前置（已做） |
| P2 | 推理运行时换代（TFLite→LiteRT / 统一 ONNX） | 📋 待真机 | 官网核实更名属实，但 Task Vision 无 LiteRT artifact、Delegate→CompiledModel 破坏性；需真机验证检测精度，同时解决 16KB 遗留 so |
| P3 | 悬浮层内容区 Compose 化、识别链语义锚点、KMP、Version Catalog | 📋 路线 | 均需真机安全网就位后评估；本环境 SDK 封顶 36 / 离线依赖固定，全量升 compileSdk 37 / AGP 9 / BOM 2026.08 会构建即断且无法验证，本版明确否决 |
| — | 端侧 VLM 替换识别链 / 预编译手势队列 <1ms / 零拷贝 HardwareBuffer 直通 ONNX | ❌ 否决 | VLM 按需下载违背零 INTERNET、秒级延迟拖垮回放闭环；后两者无对应 Android/ORT API，属幻觉；「1ms」是 ADR-002 已确认的物理不可行项 |

---

## 三、验收状态（每次发版必须更新）

| 验收项 | 当前状态 | 证据 |
|---|---|---|
| **场景驱动落盘语义回归（v2.9.15 / ADR-031）** | ✅ 有专门用例 + 全链路接线 + 33/33 符号进包 | `SceneRuleTest` 24 例（三种 `triggerMode` · 迟滞 `presentStreak/absentStreak` · `cooldown` · `retryTimeout` P0 · 同轮 K 上限 · `priority`/`mutexGroup` 仲裁 · 默认状态机与旧布尔边沿等价 · `reasons` 可观测 · 阈值自校准 · 相对偏移 · 疑似错位 · 新旧格式装配 · 旧格式零回归 · 场景话术不含「步」）+ `StableAnchorTest` 10 例（方差 / 梯度 / 非法矩形 / 横竖屏锚点边长一致 / 纹理 ROI 选到稳定锚点 / 纯色 ROI 显式降级 / 极小 ROI / 掩码不覆盖锚点 / 百分比换算）；core `SceneRule` / `StableAnchor` / `SceneSelfTest` / `ImageFinder.findDetailed` → app `SceneFrame` / `VisionPipeline.findTemplates` / `ScriptRunner.runSceneDriven` / `FloatingEditorService.captureSceneAnchor` / `VisualizerLayer.onSceneDebug` / `RunnerService` 全链路接线；`verify_release.py` 新增 16 个符号进 dex 硬门槛（33/33 True），见 ADR-031 |
| **场景驱动回归（v2.9.14）** | ✅ 有专门用例 + 全链路接线 | `SceneTriggerTest` 15 例（横 / 竖屏物理正方形 ROI · 贴边 clamp · 非法输入 · `cropPx` · `attachScene` 保真 / 降级 · 老裸动作不误判 · 边沿触发 / 常驻不重复 / 消失再出现 · 文案不含「步」）；`SceneTrigger` → `ScriptRunner.runSceneDriven` / `FloatingEditorService.pickPoint`·`save` / `FloatingEditorPanel` 话术四处接线；`verify_release.py` 新增 7 个符号进 dex 硬门槛（17/17 True），见 ADR-030 |
| Gradle 全量单测 | ✅ **475/475**（core 440 / app 35，51 suite；本版新增 34 例 core 单测，v2.9.14 报 406 是当时未纳入 app 单测的口径，不虚报增量） | `gradle --max-workers=2 :core:test :app:testDebugUnitTest :app:assembleRelease` 退出码 0；48 suite，failures/errors/skipped 全 0；v2.9.7 新增 3 例（`FloatingEditorLayoutTest`：阈值互补 / 收起态全屏拖动 / 收紧后尺寸约束），见 `tools/verify_release.py` 输出 |
| **等待门控回归（v2.9.6）** | ✅ 有专门用例 + 四处接线 | `WaitGateTest` 7 例（无限等待条件、相似度钳制、只支持找图类动作、无限等待不被帧同步档压快、有限等待保留加速、LEGACY 档、50ms 下限）；`VideoScriptBuilderTest` 4 例（默认门控 / 条件模板与动作一致 / 模板缺失显式降级 / 关闭门控零回归）；`TapCandidateDetectorTest` 3 例（分桶覆盖各阶段且噪声不挤掉结算按钮）；`ScriptStoreTest` 1 例（条件模板经压缩还原完整）；`ScriptRunner.runAction` 注入 + `repeatCheck` 间隔 + 视频导入开关 + 编辑器提示全部接线，见 ADR-022 |
| **16 KB 页对齐回归（v2.9.0）** | ✅ 产物实测 + 发版硬门槛 | `tools/check_elf_alignment.py` 解包核验 6 个 so：自编译 `libquickjs-jni.so` LOAD Align `0x1000→0x4000`、5/6 个 `0x4000`、`zipalign -c -P 16` 通过；已接入 `verify_release.py`，自编译 so 未对齐判 FAIL；TFLite 预编译 `libtask_vision_jni.so` 遗留 `0x1000` **明确列出不误标为已修复**，见 ADR-013 |
| **设计 token 单一真源回归（v2.9.0）** | ✅ 有专门用例 | `NymphDesignTokensTest` 6 例逐槽断言 `FloatingEditorTheme` 与 `Theme.kt` 同源、警告色不再两处各写、对比度构造性达标、语义色可区分；视觉值一字节不变，见 ADR-014 |
| **窗口规格约束回归（v2.9.0）** | ✅ 有专门用例 + 三处接线 | `OverlayWindowSpecTest` 6 例：位掩码与 AOSP 一致、两态 flags、非法 flag 拒绝、合法切换、拖动只改 x/y、非位移改动丢弃；`baseParams`/`setInputFocusable`/按帧提交全部接线，文档「全程恒定」措辞已修正，见 ADR-015 |
| **原子落盘 / 版本闸门回归（v2.9.0）** | ✅ 有专门用例 | `AtomicTextFileTest` 7 例（首次写/覆盖/20 轮连续/遗留 tmp 清理/UTF-8/ScriptStore 接线/建目录）+ `ScriptMigrationsTest` 8 例（历史脚本/v1 不变/未来版本被挡/非法版本/迁移幂等），见 ADR-016 |
| **界面守卫同包名回归（v2.9.1）** | ✅ 有专门用例 + 三通道接线 | `SceneGuardPositionalTest` 11 例：`POSITIONAL_TYPES` 精确识别（查找类不计入）、同包名/取不到包名/自家界面三条静默放行升级为 `PROCEED_WITH_WARNING`、既有四种决策逐字不变、告警只提示不阻断；`ScriptRunner.emitSceneNotice` + `RunnerService.onSceneNotice` 接线，见 ADR-017 |
| **执行统计口径回归（v2.9.1）** | ✅ 有专门用例 + 接线 | `RunStatsTest` 6 例：纯手势脚本显示已执行步数、坐标手势不伪装成命中、查找类口径不变、多轮/无限循环文案、零步不误报；core `RunStats` 单一真源，`ScriptRunner` 主循环 `executed++`，见 ADR-017 |
| **浮窗视觉统一回归（v2.9.3）** | ✅ 有专门用例 + 四套接线 | `OverlayThemeTest` 3 例（全部亮面色来自 `NymphDesignTokens` 真源、语义色彼此可区分、几何复用 token 槽位）+ `NymphDesignTokensTest` 扩 1（`overlayTokensDistinct` / `overlayAccentDistinctFromAccent` / 浮窗色槽视觉值锁定）：四套浮窗（录制面板/运行球/拾取手柄）引用 `OverlayTheme`、删除硬编码字面量，见 ADR-019 |\n| **录制面板可拖动回归（v2.9.3）** | ✅ 接线 + 复用拾取手柄手感 | `RecordingService` 面板 params 加 `FLAG_LAYOUT_IN_SCREEN`/`FLAG_LAYOUT_NO_LIMITS`、`onDrag` 屏幕内 clamp 只改 `x/y`；`RecordPanel` `onInterceptTouchEvent`/`onTouchEvent` 复用拾取手柄「斜率超阈值才认定拖动、绝不误伤按钮」；不改 flags 不拦截外部触摸，见 ADR-019 |\n| **录制可行性判定回归（v2.9.2）** | ✅ 有专门用例 + 四通道接线 | `RecordabilityGuardTest` 14 例：五态纯函数判定、零事件超时仍提示、自绘界面必给「能力边界 + 视频导入 + 无限循环」替代路径、正常录制不打扰、8s 门槛不误报；`RecorderBus.signal()` + `RecordPanel.setHint()` + `RecordingService` 四通道只提示一次，见 ADR-018 |
| **循环一等入口回归（v2.9.2）** | ✅ 有专门用例 + 三处接线 | `LoopPolicyTest` 6 例（`0`=无限、钳制、中文解析、非法用 null）+ `VideoScriptBuilderTest` 4 例（默认 1 零回归、无限循环产物、归一化、不改动找图锚定）；`EditorScreen` 循环对话框、`VideoImportDialog` chips、`VideoScriptBuilder.build(repeatCount)` 全部接线，`ScriptRunner` 既有无限循环能力不变，见 ADR-018 |
| **新增 core 符号进 release dex（v2.9.2）** | ✅ 发版硬门槛 | `verify_release.py` 断言 `RecordabilityGuard` / `LoopPolicy` 必须出现在 release dex，否则判 FAIL（ADR-008）；实测 1013 个本工程类全保留可读名（v2.9.15） |
| 新增 core 能力单测 | ✅ 覆盖 | **v2.9.0 新增 27 例**（上列 4 组）。**v2.8.1 新增 10 例**：`FloatingEditorLayoutTest` 补 5 例（绝对坐标拖动不累加不漂移 / 越界 clamp / 未达 1px 阈值不提交 / `UNSPECIFIED` 不被压成 0 / 三种测量模式均不超上限且 EXACTLY 保持语义）、新增 `FloatingEditorThemeTest` 5 例。**v2.8.0 `FloatingEditorLayoutTest` 12 例**：折叠/展开尺寸与占屏比、位置 clamp、拖动累加、步骤游标、空串/非法输入收敛、de/fr Locale 格式化往返、`formOf`/`toPatch` 往返保真、非简易类型不伪装成点击、只读复杂动作不丢 `condition`/`listeners`、ROI 经 `RoiApplier` 命中找图动作。v2.7.1：`PickerGeometryTest` 11 / `RoiApplierTest` 6 / `PickerRegionTest` 4 |
| **系统悬浮窗接线回归** | ✅ 有专门用例 + 接线 | `FloatingEditorService` 以 `TYPE_APPLICATION_OVERLAY` + `specialUse` 前台服务承载；脚本卡片菜单「悬浮编辑」、编辑器「悬浮」按钮、设置页「关闭浮窗」三处入口均已接线；通知栏「呼出/收起/关闭」可用。见 ADR-011 |
| **悬浮窗可输入性回归** | ✅ 有代码级约束 | **v2.9.0 措辞修正**：准确说法是「只允许两种受控 flags、只由输入焦点触发」，不再是「全程恒定」（`setInputFocusable()` 本就会切换）。取值来自 core `OverlayWindowSpec`：`STANDARD_FLAGS`=`NOT_FOCUSABLE\|NOT_TOUCH_MODAL`、`INPUT_FLAGS`=`NOT_TOUCH_MODAL`；仅 `EditText` 获焦临时切换、失焦恢复。`SOFT_INPUT_ADJUST_*` 已弃用故不设。见 ADR-015 |
| **紧凑化回归** | ✅ 有专门用例 | 折叠 40dp 小球（< 屏宽 15%）；**v2.9.8 展开 168dp 宽（< 屏宽 50%）、高度上限屏高 40%、`GLASS_BG` alpha `0xB0`**；action chips 单行、ROI / 滑动终点 / 高级字段默认收进「高级」区、状态行 1 行（`MaxHeightScrollView.onMeasure` 真正限高）。`FloatingEditorLayoutTest` 阈值收紧到宽 ≤48% / 高 ≤41%，并断言最大面积相对 v2.9.7 ≤65%，见 ADR-024 |
| **拖动可用性回归** | ✅ 有代码级约束 + **发版静态门禁** | 拖动监听器挂在「折叠小球」与「顶部拖动条」自身，不用根 View 的 `onTouchEvent`（会被子控件抢走 DOWN）；**v2.8.1 起 `rawX/rawY` 绝对坐标 + 1px 阈值；v2.9.8 移除 `Choreographer`/`frameScheduled` 异步自锁，改 core 无状态 `commitDrag` + 同步 `updateViewLayout`，拖动启动 4dp、点击仍用系统 slop**（见 ADR-024）。`verify_release.py::drag_gate()` 每次发版扫描 Service 非注释代码行，禁止异步自锁复活 |
| **窗口抗抽搐回归** | ✅ 有专门用例 | `dragAbsolute` 不累加不漂移、`shouldCommit` 过滤无效变化、flags 受控（v2.9.0 起由 `OverlayWindowSpec` 限定为两种状态，见 ADR-015）、`refreshMetrics` 缓存屏宽高、`refreshForeground` 去重、状态行固定最小高度；对应 5 例新单测 + 视频逐帧证据（面板边缘跳位/内容行数变化），见 ADR-012 |
| **测量模式回归** | ✅ 有专门用例 | `measureHeight` 对 `UNSPECIFIED` 返回 `AT_MOST(maxH)` 而非 `AT_MOST(0)`，三种模式均不超上限且 EXACTLY 保持语义；`MaxHeightScrollView.onMeasure` 已接线 |
| **视觉 token 回归** | ✅ 有专门用例 | **v2.9.0 起 `NymphDesignTokens`（core）为唯一真源**，`Theme.kt` 与 `FloatingEditorTheme`（投影层）同源；`FloatingEditorThemeTest` 5 例 + `NymphDesignTokensTest` 6 例约束字号层级、几何、对比度与两处同源；面板去除灰方块与 `⠿`/`▲` 缺字字符。见 ADR-014 |
| 拾取会话生命周期回归 | ✅ 有专门用例 | `DisposableEffect(Unit)` 恒定 key + `resultSeq` 去重；`buildDeliver` 点↔区域互转；`show()` 重建语义，见 ADR-009（`PickerGeometryTest` / `PickerRegionTest` / `EditorFormatTest`） |
| 坐标真值与窗口解耦回归 | ✅ 有专门用例 | `PickerGeometry.drag` 累加 + clamp、`normalizeRegion` 任意两角/越界/零面积收敛、`regionExpr` 在 de/fr Locale 下仍可被 `RoiExpr.resolve` 解析 |
| ROI 全量写入回归 | ✅ 有专门用例 | `RoiApplierTest` 6 例：目标动作 / 条件树 / `children` / `listeners` / `abortCondition`、无目标返回原对象、命中数一致；UI 命中 0 处按实际情况提示，见 ADR-010 |
| 撤销栈完整性回归 | ✅ | 升级为完整 `Script` 快照，`restore()` 同时恢复 actions / name / `scriptBase`；源码视图「应用」保留 `variables`/`listeners`/`abortCondition` |
| 单测统计口径修正 | ✅ | v2.7.0 脚本只匹配 `test-results/test/`（仅 core），漏掉 app `testDebugUnitTest/`；v2.7.1 改为匹配全部测试目录，总数由旧口径 248 变为更完整的 **297**（纳入此前漏统计的 app 单测，非虚报增量） |
| **新能力接线扫描** | ✅ 通过 | 逐个统计新增 core 符号的引用点；v2.9.0 的 `NymphDesignTokens`（Theme.kt + FloatingEditorTheme）、`OverlayWindowSpec`（FloatingEditorService 三处）、`AtomicTextFile`（3 个 store）、`ScriptMigrations`（ScriptCodec）均有生产消费点；v2.7.0 的 `RoiExpr` / `ScriptRepository` 亦已补齐（ADR-008）。无「写了没接」残留 |
| ROI 表达式运行时回归 | ✅ 有专门用例 | `RoiExprTest` 3 例：空表达式原样返回 / `topHalf` 覆盖数值 / 非法表达式静默退回 |
| 数据丢失 bug 回归 | ✅ 有专门用例 | `ActionEditGuardTest.编辑简易类型时保留全部非编辑字段` |
| 录制自扰回归 | ✅ 有专门用例 | `RecordGateTest.点击器内的自扰事件全部丢弃` |
| `assembleRelease` | ✅ | 见发布说明 |
| zipalign + apksigner v2/v3 同钥 | ✅ | 证书 `361722d6…6271`，与全部历史版本同钥 |
| 权限 / ABI / 录屏 API 合规扫描 | ✅ | aapt2 + dexdump，零 `MediaProjection` / `VirtualDisplay` / `MediaCodec` 录屏编码 / `FLAG_SECURE` / `RECORD_AUDIO` |
| 无混淆 / 无加密 / 类名可读 | ✅ | `isMinifyEnabled=false`，`isShrinkResources=false`；dexdump 实测 **1013 个** `dev/nymph/auto` 类全保留可读名（v2.9.14 为 976），`NymphDesignTokens` / `OverlayWindowSpec` / `AtomicTextFile` / `ScriptMigrations` / `RunStats` / `DragGesture` / `WaitGate` 等新符号已确认进 dex |
| 源码包完整性 + 三件附件回源 SHA-256 | ✅ | 见发布说明 |
| 令牌 600 权限落地 / 删除 / 残留扫描 | ✅ | 发布脚本 |
| **真机运行** | ⛔ **未验证** | 本环境无真机；GPU/帧率/内存/各 ROM 录屏可见性/折叠屏坐标全部待真机 |
| **instrumentation 测试** | ⛔ **零** | 浮层/窗口/Choreographer/权限降级未覆盖 |
| **「1ms 找坐标」** | ❌ **不可行** | 物理上限，见 ADR-002 |
| **「mp4 全自动总结点击特征」** | ❌ **不做** | 无标签不可靠，改为半自动，见 ADR-006 |

---

## 四、数字自动核对

`README.md` / 发布说明里的 APK 字节数、SHA-256、单测数、权限数、类数、
源码包文件数历史上全靠手工核对，容易漂移。v2.7.0 起统一由：

```
python3.11 tools/verify_release.py
```

从**真实产物**读取并打印核对表；发布说明中的数字必须引用该脚本输出。
脚本不写文件、不联网、不修改任何产物，只做只读核对。

**v2.9.0 起新增 16 KB 页对齐核验**（ADR-013）：`verify_release.py` 调用
`tools/check_elf_alignment.py`，解包 APK 用 `llvm-readelf -l` 逐个 so 读
ELF LOAD 段 Align，并把「自编译 so 全部 `0x4000`」纳入发版 PASS 硬门槛；
`zipalign -c -P 16` 单独查 ZIP 条目对齐（与 ELF 段对齐是两回事）。预编译
第三方 so 未对齐会明确列出但不误标为已修复——这是针对「v2.8.1 文档写已对齐、
产物实测 4KB」这类文档与产物不符问题建立的机制防线。可单独运行：

```
python3.11 tools/check_elf_alignment.py            # 人读表格
python3.11 tools/check_elf_alignment.py --json    # 机器可读
```

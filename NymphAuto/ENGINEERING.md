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
  底层 APP 完全收不到触摸；底部大按钮常遮挡待拾取位置。用户明确要求
  「一边操作拾取坐标，一边可以操作指针外的任意区域」。
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
- **背景**：用户反馈「100 毫秒识别效率低，跟不上高速移动的目标」，并提出
  「1 毫秒内通过特征自动找坐标」。
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
  `CLICK_IMAGE` 特征脚本。模板缺失如实降级为坐标点击，不假装找图。
- **后果**：诚实但可用。时间戳有帧级误差（关键帧稀疏会漏短促点击），
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
- **背景**：v2.7.0 发布后用户反馈「屏幕拾取起点（非模态）」有 bug。查真实链路得
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
- **背景**：用户反馈「框选搜索区域 ROI / 模板范围」有 bug。根因是**功能错配**：
  v2.7.0 把框选结果只写进当前动作的 `region`，但简易编辑器只编辑
  TAP/LONG_PRESS/MULTI_TAP/SWIPE 四种坐标动作，而 `ScriptRunner` 里
  `region` 只被找图/找色/找文字/AI/节点/条件读取——**坐标动作根本不读它**。
  用户框完确认保存，运行时毫无变化，界面还显示「已框选」，等于白框。
  这是 ADR-008「写了没接」的变体：这次是「写入点接错了对象」。
- **决策**：
  1. 新增纯 JVM `core/.../editor/RoiApplier` 作**唯一真源**：`apply(script, region)`
    把 ROI 写入脚本内**所有真正读取 region 的位置**——视觉目标动作、条件树、
    `children`、`listeners`、`abortCondition`，全程用 `copy` 保真
    （不丢 `imageData`/`condition`/`listeners`），返回**命中数**；
  2. UI 如实显示「已应用到 N 处」，`N=0` 时明确提示脚本内暂无找图目标并
    引导到源码视图，**不再静默「已框选」**；`countTargets()` 预显示目标数，
    `hasExplicitRegion()` 让已有 region 的动作打开时不再显示成「未框选」；
  3. ROI 同时写入数值四元组与 `Region.expr`，运行时由 ADR-008 的
    `RoiExpr.resolveRegion` 优先按表达式解析；框选矩形统一走
    `PickerGeometry.normalizeRegion`（任意两角顺序、越界、零面积收敛）；
  4. 撤销栈从 `List<Action>` 升级为**完整 `Script` 快照**，否则 ROI 改的
    脚本级 `listeners`/`abortCondition` 撤销回退不了；源码视图「应用」同样保真。
- **后果**：发版前新增一条硬性检查——**任何「应用到脚本」类能力，必须枚举
  运行时真正读取该字段的全部位置**，不能只写当前编辑对象；且必须返回
  命中数并在 0 命中时如实告知用户，禁止用「成功」掩盖空操作。
- **单测**：`RoiApplierTest` 6 例（目标动作/条件树/children/listeners/中止条件、
  无目标返回原对象、命中数一致）。
- **边界**：ROI 只对找图/找色/找文字/AI/节点动作与条件生效；
  脚本里没有这类动作时如实报 0。简易编辑器仍只编辑 4 种坐标动作。

### ADR-011 编辑动作界面必须是系统级悬浮窗，不得绑定 Activity 生命周期

- **状态**：已接受，v2.8.0 实施（回应用户差评：「编辑动作的整个界面不支持脱离
  NymphAuto 在其他 APK 界面运行，另外太大了」）
- **背景**：用户给出一份「系统级悬浮窗（System Overlay / 后台可显示）vs
  前台绑定悬浮窗（Activity 生命周期绑定）」的资料，并明确要求**以源码为准**。
  核实结果：本工程其实是**混合架构**，比资料的二分法更细：
  1. `ActionEditDialog` 是 Compose `AlertDialog`，画在
     `MainActivity.setContent{}` 视图树内 —— **连独立 Dialog 窗口都不是**，
     更不是 `TYPE_APPLICATION_OVERLAY`。切到其他 APP 随 `onStop` 消失，
     也无法在其他 APP 之上呼出。差评属实。
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
     ROI 走 `RoiApplier.apply`（唯一真源 + 命中数如实反馈）、坐标拾取复用
     `CoordinatePicker`。不新建第二套脚本格式。
- **踩过的两个真坑（已修，记录以免回退）**：
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
  必须明确它属于系统 Overlay 还是 Activity 内 UI，差评里这两类不能混；
  (b) 悬浮窗里的输入控件必须验证 flag 组合确实允许获取焦点。
- **边界**：本环境无 Android 设备，`TYPE_APPLICATION_OVERLAY` 在各 ROM 的
  实际触摸分发、软键盘行为、前台服务保活、通知栏呼出、折叠/展开手势，
  **全部待真机验证**，不冒充已验证。对比视频链接缺访问参数无法取回，
  紧凑尺寸依据是差评截图（约 94%×73%）与本项目既有 196×156dp 工具条。

### ADR-012 悬浮窗口拖动必须用屏幕绝对坐标并按帧提交，flags 不得在交互中来回切换

- **状态**：已接受，v2.8.1 实施（回应用户差评：「一直在抽搐，界面也不美观」）
- **背景**：这次对比视频链接带签名参数，**成功取回** 19.6MB 真 MP4（720×328 /
  60fps / 142s）。逐帧像素差分析与连续帧放大拼接给出可见证据：悬浮面板的
  **左/上边缘位置在跳动**、**可见内容范围忽多忽少**（有的帧只到「屏幕拾取
  起点」、有的多出「框选 ROI」一行）——不是动画，是窗口在反复 `relayout`。
  画面同时坐实灰蓝实心方块按钮、10.5sp 拥挤排版、`⠿`/`▲` 缺字字符。
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
  `CoordinatePicker` 的 z-order **全部待真机回归**，不冒充已验证。视频只
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
- **如实边界**：TFLite Task Vision 0.4.4 预编译 `libtask_vision_jni.so` 仍
  `0x1000`，本工程 CMake 不可控，**不冒充修复**。官网核实 TFLite 已更名
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
- **未做（诚实记录）**：外部建议的「进程内单一 `MutableStateFlow<EditorSession>`
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
- **诚实边界（未做）**：被动无障碍录制拿不到手指真实轨迹、页面标识与滚动位置，
  **不承诺**「自动把界面还原到录制时的抽屉滚动位置」（ADR-002 能力边界），
  也不引入 `AccessibilityWindowInfo` 猜测页面（各 ROM 差异大、无真机验证会误拦）。
  本版落地的是「不再静默放行、不再报全 0、把风险明确送达用户」。包内 5 张
  `miss_目标图片` 截图经时间戳核算为 11-07 历史文件（动态视频封面 + 浮窗遮挡），
  属找图能力边界，**不作为本次会话证据**。修复效果本身待下一份真机回归验证。

### ADR-018 自绘界面录不到点击是能力边界：必须如实判定并把「找图 + 循环」路径变成一等入口

- **状态**：已接受，v2.9.2 实施。
- **背景（2026-11-10 真机差评 + 40.3s 横屏录屏）**：用户发来 1280×578 手游录屏
  并评价「不满意，不能实现类似操作」。逐帧核对：视频是**连续「扫荡 → 开箱结算 →
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
     五态。`reason()` 如实写明「这是能力边界而非脚本错误」，`alternative()` 指向
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
- **诚实边界（未做）**：**不承诺自动识别自绘界面按钮**，也不承诺「录制能在
  游戏里工作」——被动无障碍录制的能力边界未突破，本版交付的是「如实告知边界
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
- **诚实边界（未做）**：分屏/多窗口、深色壁纸对比度、拖动手柄的吸附/记忆位置未做；
  统一配色在真机上的实际观感、拖动是否顺手待下一份真机回归。分析里点名的「视觉密度偏高」
  等打磨项属体验优化，不在本版硬契约修复范围内。

### ADR-024 窗口位移不得依赖「会被卡住的异步自锁」；瘦身后要改信息架构而不只是调常量

- **状态**：已接受，v2.9.8 实施。
- **输入**：两张真机截图 + **第二次**反馈「NymphAuto2.9.7 悬浮窗还是这么大，收起后悬浮球仍然不能拖动」。ADR-023 的修复在真机上没有生效，本 ADR 承认并重查整条链路，而不是继续调数值。

**缺陷 1（P0，收起球仍拖不动）**：ADR-023 把阈值统一到 `ViewConfiguration.scaledTouchSlop`，但它只改了**手势判定层**，没动**窗口提交层**。逐行核对 `FloatingEditorService.onDragAbsolute`：

```
collapsedBall → attachDrag → service.onDragAbsolute
  → core.dragAbsolute → pendingX/Y + frameScheduled=true
  → Choreographer.postFrameCallback { frameScheduled=false; ... updateViewLayout }
```

`frameScheduled` **只在 VSYNC 回调开头复位**。前台 Service 的 overlay 窗口静止、或系统省电策略暂停 VSYNC 派发时，回调可能整段不触发；回调内被 `shouldCommit` / `isPositionOnlyUpdate` 判丢后若没有后续 MOVE 续投，也一样。此时 `frameScheduled` 永久为 `true`，之后每个 MOVE 都在函数入口 `return`，窗口再也不更新 —— 真机表现正是「悬浮球完全拖不动」。**反证**：主悬浮球 `RunnerService.attachBallTouch` 从 v2.9.4 起就是同步 `wm.updateViewLayout(p, params)`，从未被投诉拖不动。同一进程、同一 `WindowManager`、同一权限模型，差别只在提交链路。

**修法**：core 新增**无状态纯函数** `FloatingEditorLayout.commitDrag(startWin…, committedX/Y)`：算绝对坐标 + 1px 节流，返回 `Pair?`（null = 本次不提交），**没有任何可变状态，因而不存在「卡住」这种失效模式**。Service 拿到返回值立即 `OverlayWindowSpec.isPositionOnlyUpdate` 校验并同步 `updateViewLayout`；删除 `Choreographer` / `pendingX` / `pendingY` / `frameScheduled`。绝对坐标（不累加、不漂移）、1px 节流、仅位移规格校验**全部保留** —— v2.8.1 抽搐的真因是「局部坐标增量累加」，不是 `updateViewLayout` 的频率，同步提交不会让抽搐回归。

另修 ADR-023 引入的**新副作用**：统一到系统 slop（约 24px @3x）后，40dp 小球上轻移 10~20px 完全不启动拖动，抬手反被判成点击 → **一点就展开**。现把 MOVE 的拖动启动阈值改为 core 的 `dragStartPx(density)` = **4dp**（约 12px），点击仍用系统 slop 作上界；`isTap(dragging,…)` 因 `dragging=true` 恒 false，拖到一半绝不误展开。折叠球 DOWN 时 `scale=1.16` 给出「这是可拖把手」的可见反馈。

**缺陷 2（P1，悬浮窗还是这么大）**：ADR-023 只做 240→220dp、0.62→0.54、0xED→0xD9 的 8%~13% 微调，真机仍是近半屏、85% 不透明的大黑块。本版改为**结构性瘦身**：`EXPANDED_W_DP` 220→**168**（1080px 屏上 660→504px，占屏宽 61%→47%）、`MAX_H_FRACTION` 0.54→**0.40**、`GLASS_BG` 0xD9→**0xB0**（69%）；4 个动作 chips 由 2×2 改单行、导航按钮缩短文案并收紧内边距（真机截图里它们已被挤成竖排多行）、ROI / 滑动终点 / 高级数值字段收进默认隐藏的**「高级」区**、状态行 2 行→1 行、间距 token 同步收紧（字号一字不变）。**控件与功能一个不删**：选「滑动」自动展开高级区，保证终点坐标不会藏起来。最大面积 660×1296 → 504×960，缩小约 **43%**。

**约束**：`verify_release.py` 新增静态门禁 `drag_gate()`，扫描 `FloatingEditorService.kt` 的**非注释代码行**，禁止 `Choreographer` / `postFrameCallback` / `frameScheduled` / `pendingX` / `pendingY` 复活，并要求 `commitDrag` + `wm.updateViewLayout` 与 `dragStartPx` 接线；`commitDrag` / `dragStartPx` 纳入 release dex 硬门槛。五条硬契约不变：零新增权限、零 INTERNET、不拦截触摸、不抢录屏 API、无混淆无加密。

**后果**：新增 3 例 core 单测（阈值分离 / 同步提交无自锁且重复投递不卡死 / 贴边 clamp），全量 **426/426**、48 suite。**仍无真机复测**：折叠球拖动手感、69% alpha 的文字可读性、168dp 下按钮单行是否够宽、0.40 高度上限下是否需要滚动，须装机回归；不冒充已跑通。若装机后仍有个别 ROM 拖不动，需另取真机录屏逐帧定位 —— 但「异步自锁」这条已由静态门禁钉死，不会悄悄回来。

### ADR-023 同一手势的「点击 / 拖动」必须共用单一阈值；浮窗尺寸按遮挡成本收敛

**输入**：三张真机截图（自动精灵小面板 / NymphAuto 悬浮编辑面板 ×2）+ 反馈「交互界面傻大粗，就算收起后的悬浮球也无法拖动」。上一轮 ADR-022 只处理了「添加动作的触发拓扑」，漏看这两处最扎眼的缺陷，本 ADR 补上。

**缺陷 1（P0，收起球拖不动）**：`FloatingEditorPanel.attachDrag` 里
- MOVE 用 `4f * density` 启动拖动，UP 却用 `8f * density` 判定点击；
- 位移落在 4~8dp 之间时 `dragging=false` 且 `moved<8dp` → 抬手执行 `performClick()` + `onToggle()`，把 40dp 小球展开成大面板。用户想微调球位置最容易落在这段窗口，体感即「拖不动、一点就变大」。
- `downRawX/Y/startWinX/Y/dragging` 是**面板级共享字段**，被折叠球与展开态顶部条两个 `attachDrag` 共用；展开态拖动后直接收起，下一次手势带脏状态。
- 点击分支 `return false` 使 UP 未被任何 View 消费，部分 ROM 上紧接着的一次手势会丢 DOWN。

> **v2.9.8 更正（见 ADR-024）**：下面「链路本身是通的」这一判断**被真机反馈推翻**——`Choreographer.postFrameCallback` + `frameScheduled` 自锁会永久卡住提交，正是「收起球拖不动」的主因。本 ADR 当时只核对了「代码路径存在」，没有验证「每一帧都真的能提交」，属于静态审计的盲区，保留原文以留痕。

链路本身（`collapsedBall → attachDrag → service.onDragAbsolute → core.dragAbsolute → Choreographer → OverlayWindowSpec.isPositionOnlyUpdate → updateViewLayout`）当时逐行核对是「连得通」的，**不虚构单一根因**；上述三处是确定存在的缺陷，逐一消除：
1. 判定收敛到 core 纯函数 `isDragMove(dx,dy,slop)` / `isTap(dragging,dx,dy,slop)`，后者是前者的**严格补集**，slop 取 `ViewConfiguration.getScaledTouchSlop()`，不留灰色地带；
2. `applyState()` 折叠/展开切换时调 `resetDragState()` 清共享状态；
3. 折叠球消费整个手势（UP/CANCEL 返回 true），展开态顶部条保持原有穿透，不影响条上「收起 / 关闭」按钮。
补 3 例 core 单测锁死「阈值互补」「收起态 40dp 球可在整屏拖动、不被展开态高度压缩」「收紧后尺寸仍不铺满全屏」。

**缺陷 2（P1，傻大粗）**：像素量化自家面板约 76.5% 屏宽 × 58.4% 屏高 ≈ 44.7% 屏幕面积、`GLASS_BG` 93% 不透明；竞品同场景约 49% × 22% 且明显半透明。收敛：`EXPANDED_W_DP` 240→220、`MAX_H_FRACTION` 0.62→0.54、`GLASS_BG` alpha `0xED`→`0xD9`（RGB 不变，`luminance/contrast` 自检不受影响）。**不删任何控件、不改默认折叠态、不改功能语义**。另修 `EditorScreen` TopAppBar 五个 TextButton 默认 12dp 内边距把标题挤成竖排四行：收紧到 4dp + 标题 `maxLines=1, softWrap=false`。

**约束延续**：零新增权限 / 零 INTERNET / 不拦截触摸 / 无混淆无加密；`FLAG_NOT_TOUCH_MODAL` 与 `NOT_FOCUSABLE` 不变。半透明只调 alpha，不动 flags。

**后果**：新增 3 例 core 单测，全量 423/423、48 suite。**仍无真机复测**：拖动手感、半透明可读性、高度收紧后是否需滚动，须装机回归；不冒充已跑通。

### ADR-022 阶段时长随机的循环必须事件驱动：「等待画面出现」要真的写进脚本

- **状态**：已接受，v2.9.6 实施。
- **背景**：用户发来 48.87 秒横屏手游录屏（720×328 / 60fps）与差评「不支持类似复杂操作」。逐帧取证：一个「战斗 → 约 12 秒全屏开箱动画 → 深色『获得奖励』结算 +『确认』→ 回主界面再战」的循环；**全程没有 NymphAuto 界面**，是目标操作演示而非回放失败实录。但源码审计确认按产品内提示的推荐做法必然失败：
  1. 编辑器循环弹窗写「配合找图动作（会等待按钮出现）」、视频导入提示写「会轮询等待按钮出现，不依赖固定延时」，而 `CLICK_IMAGE` 的 `withRetry` 默认窗口只有 **2000ms**（`retryWindowMs`，上限 60s 但设置页无入口），超时即判失败推进；`VideoScriptBuilder` 更只生成 `waitMs = gap`（≤3s）的固定延时动作。**描述与实现不符**，10 秒级动画必然超时空转。
  2. 引擎早已有 `Condition(APPEAR_IMAGE)` + `repeatCheckUntilOk` 轮询（含中止条件检查点、等待浮层、看门狗心跳），但 `VideoScriptBuilder` 不生成、编辑器无入口 —— **能力存在却不可达**。ADR-018 把「找图 + 循环」变成一等入口时，只接通了循环，没接通「等待」。
- **决策**：
  1. core 新增 `WaitGate`（纯 JVM 单一真源）：生成「等到这张模板出现在屏幕上」的 `Condition`，`repeatCheckUntilOk = true`、`repeatCheckMax = 0`（无限等待），轮询 300ms。脚本是**可序列化、可导出的数据**，不依赖运行时注入技巧。
  2. `VideoScriptBuilder.Options.waitForAppearance` 默认开启：有模板的动作挂门控条件、`waitMs` 归零（门控即同步点）；模板缺失的 TAP 如实保留固定延时；`false` 完全回到 v2.9.5 行为。`Action.waitAppear` 让手动建的找图动作由 `ScriptRunner.runAction` 注入同一门控（显式 `condition` 优先，老脚本默认 false 零回归）。
  3. `ScriptCompressor.walkActions → walkCondition` 已支持条件模板去重/还原；新增 `ScriptStoreTest` 锁死「action 与 condition 两处模板经压缩还原后都完整」，否则条件会拿 `ref:xxxx` 去找图。
  4. `repeatCheck` 旧实现无条件取 `minOf(脚本间隔, 调参间隔)`，帧同步档（33ms）会把十几秒长等待压成 30fps 全屏 NCC。间隔选择抽到 core `WaitGate.pollIntervalMs`：无限等待按脚本节奏（不被压快），有限等待保留既有加速语义。
  5. `TapCandidateDetector.pickDefaults`：候选原本**默认全选 60 个**，含全屏特效的视频会检出大量动画噪声，全选 + 无限等待会让脚本卡在永不再现的动画帧上。改为按时间分 6 桶、每桶取最高分，默认最多 6 个覆盖各阶段的候选。
- **可达性实测**：用该真视频复现候选检测（10fps / 180 宽灰度），结算阶段 43–48s 检出 18 个候选、战斗阶段 11 个，关键按钮可勾；56 帧被全局变化判为转场跳过。这证明「视频导入 → 勾 2~3 个关键触点 → 门控循环」路径可达，不是纸面能力。
- **教训**：UI 文案承诺的能力必须有单测或发版硬门槛兜住，否则「会等待按钮出现」这种一句话就会变成用户踩坑的假承诺；ADR-018 的替代路径要一直验到「生成的脚本产物」这一层，只验入口不算接通。
- **后果**：新增 15 例 core 单测（`WaitGateTest` 7 / `VideoScriptBuilderTest` 4 / `TapCandidateDetectorTest` 3 / `ScriptStoreTest` 1），全量 420/420、48 suite；`verify_release.py` 把 `WaitGate` 纳入 release dex 硬门槛（ADR-008）。**仍无真机复测**；门控是无限等待，必须设脚本中止条件或用通知栏停止；不承诺自动识别自绘界面按钮。

### ADR-021 浮窗拖动必须用屏幕绝对坐标定位：相对增量会被窗口自身移动抵消

- **状态**：已接受，v2.9.5 实施。
- **背景**：用户真机录屏反馈「抖动抽搐」。逐帧分析（578×1280 / 44fps / 41 秒）显示：绿色手柄与粉红准星**明显不重合**（手柄停在准星上方约 70px），逐帧位移方向反转率 **72%**。源码定位到 `PickerHandleView.onTouchEvent` 用 `e.x/e.y`（**相对 View 坐标**）算拖动增量——而**手柄窗口本身就在跟随手指移动**，窗口每动一次，下一帧的相对增量就被抵消，于是手柄「一顿一顿」地滞后跳动。
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
     根本不再派发 —— 面板实际拖不动，功能等于没交付；
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

---

## 二、路线图（ROADMAP）

| 优先级 | 项目 | 状态 | 说明 |
|---|---|---|---|
| P0 | 悬浮窗抗抽搐（绝对坐标 + 按帧提交 + flags 恒定）+ 视觉 token 重做 | ✅ v2.8.1 | 本版，见 ADR-012（差评修复） |
| P0 | 编辑动作界面系统级悬浮窗化（跨 APP / 后台可呼出）+ 紧凑化 | ✅ v2.8.0 | 上版，见 ADR-011（差评修复） |
| P0 | 拾取会话生命周期 / 坐标真值解耦 / Locale 安全（拾取起点 bug） | ✅ v2.7.1 | 上版，见 ADR-009 |
| P0 | ROI 唯一真源 + 全量写入 + 命中数如实反馈（框选 ROI bug） | ✅ v2.7.1 | 上版，见 ADR-010 |
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
| P0 | **16 KB 页对齐（NDK r27 linker flags + 发版 ELF 实测硬门槛）** | ✅ v2.9.0 | 自编译 so `0x1000→0x4000`；TFLite 预编译 so 遗留 `0x1000` 如实列出，见 ADR-013 |
| P1 | **设计 token 单一真源（core `NymphDesignTokens`）** | ✅ v2.9.0 | 两套 UI 同源，视觉零变化，见 ADR-014 |
| P1 | **窗口规格约束函数化（`OverlayWindowSpec`）** | ✅ v2.9.0 | flags 两态 + 仅位移提交校验，见 ADR-015 |
| P1 | **原子落盘 + schema 迁移链** | ✅ v2.9.0 | `AtomicTextFile` + `ScriptMigrations`，见 ADR-016 |
| P2 | 跨窗口单一状态源（`StateFlow<EditorSession>` + sealed 状态机） | 📋 缓做 | 改动 Activity+4 悬浮层、无真机验证，风险 > 可证收益；原子写是零风险前置（已做） |
| P2 | 推理运行时换代（TFLite→LiteRT / 统一 ONNX） | 📋 待真机 | 官网核实更名属实，但 Task Vision 无 LiteRT artifact、Delegate→CompiledModel 破坏性；需真机验证检测精度，同时解决 16KB 遗留 so |
| P3 | 悬浮层内容区 Compose 化、识别链语义锚点、KMP、Version Catalog | 📋 路线 | 均需真机安全网就位后评估；本环境 SDK 封顶 36 / 离线依赖固定，全量升 compileSdk 37 / AGP 9 / BOM 2026.08 会构建即断且无法验证，本版明确否决 |
| — | 端侧 VLM 替换识别链 / 预编译手势队列 <1ms / 零拷贝 HardwareBuffer 直通 ONNX | ❌ 否决 | VLM 按需下载违背零 INTERNET、秒级延迟拖垮回放闭环；后两者无对应 Android/ORT API，属幻觉；「1ms」是 ADR-002 已确认的物理不可行项 |

---

## 三、验收状态（每次发版必须更新）

| 验收项 | v2.9.7 状态 | 证据 |
|---|---|---|
| Gradle 全量单测 | ✅ **423/423** | `gradle --max-workers=2 :core:test :app:testDebugUnitTest :app:assembleRelease` 退出码 0；48 suite，failures/errors/skipped 全 0；v2.9.7 新增 3 例（`FloatingEditorLayoutTest`：阈值互补 / 收起态全屏拖动 / 收紧后尺寸约束），见 `tools/verify_release.py` 输出 |
| **等待门控回归（v2.9.6）** | ✅ 有专门用例 + 四处接线 | `WaitGateTest` 7 例（无限等待条件、相似度钳制、只支持找图类动作、无限等待不被帧同步档压快、有限等待保留加速、LEGACY 档、50ms 下限）；`VideoScriptBuilderTest` 4 例（默认门控 / 条件模板与动作一致 / 模板缺失如实降级 / 关闭门控零回归）；`TapCandidateDetectorTest` 3 例（分桶覆盖各阶段且噪声不挤掉结算按钮）；`ScriptStoreTest` 1 例（条件模板经压缩还原完整）；`ScriptRunner.runAction` 注入 + `repeatCheck` 间隔 + 视频导入开关 + 编辑器提示全部接线，见 ADR-022 |
| **16 KB 页对齐回归（v2.9.0）** | ✅ 产物实测 + 发版硬门槛 | `tools/check_elf_alignment.py` 解包核验 6 个 so：自编译 `libquickjs-jni.so` LOAD Align `0x1000→0x4000`、5/6 个 `0x4000`、`zipalign -c -P 16` 通过；已接入 `verify_release.py`，自编译 so 未对齐判 FAIL；TFLite 预编译 `libtask_vision_jni.so` 遗留 `0x1000` **如实列出不冒充修复**，见 ADR-013 |
| **设计 token 单一真源回归（v2.9.0）** | ✅ 有专门用例 | `NymphDesignTokensTest` 6 例逐槽断言 `FloatingEditorTheme` 与 `Theme.kt` 同源、警告色不再两处各写、对比度构造性达标、语义色可区分；视觉值一字节不变，见 ADR-014 |
| **窗口规格约束回归（v2.9.0）** | ✅ 有专门用例 + 三处接线 | `OverlayWindowSpecTest` 6 例：位掩码与 AOSP 一致、两态 flags、非法 flag 拒绝、合法切换、拖动只改 x/y、非位移改动丢弃；`baseParams`/`setInputFocusable`/按帧提交全部接线，文档「全程恒定」措辞已修正，见 ADR-015 |
| **原子落盘 / 版本闸门回归（v2.9.0）** | ✅ 有专门用例 | `AtomicTextFileTest` 7 例（首次写/覆盖/20 轮连续/遗留 tmp 清理/UTF-8/ScriptStore 接线/建目录）+ `ScriptMigrationsTest` 8 例（历史脚本/v1 不变/未来版本被挡/非法版本/迁移幂等），见 ADR-016 |
| **界面守卫同包名回归（v2.9.1）** | ✅ 有专门用例 + 三通道接线 | `SceneGuardPositionalTest` 11 例：`POSITIONAL_TYPES` 精确识别（查找类不计入）、同包名/取不到包名/自家界面三条静默放行升级为 `PROCEED_WITH_WARNING`、既有四种决策逐字不变、告警只提示不阻断；`ScriptRunner.emitSceneNotice` + `RunnerService.onSceneNotice` 接线，见 ADR-017 |
| **执行统计口径回归（v2.9.1）** | ✅ 有专门用例 + 接线 | `RunStatsTest` 6 例：纯手势脚本显示已执行步数、坐标手势不伪装成命中、查找类口径不变、多轮/无限循环文案、零步不误报；core `RunStats` 单一真源，`ScriptRunner` 主循环 `executed++`，见 ADR-017 |
| **浮窗视觉统一回归（v2.9.3）** | ✅ 有专门用例 + 四套接线 | `OverlayThemeTest` 3 例（全部亮面色来自 `NymphDesignTokens` 真源、语义色彼此可区分、几何复用 token 槽位）+ `NymphDesignTokensTest` 扩 1（`overlayTokensDistinct` / `overlayAccentDistinctFromAccent` / 浮窗色槽视觉值锁定）：四套浮窗（录制面板/运行球/拾取手柄）引用 `OverlayTheme`、删除硬编码字面量，见 ADR-019 |\n| **录制面板可拖动回归（v2.9.3）** | ✅ 接线 + 复用拾取手柄手感 | `RecordingService` 面板 params 加 `FLAG_LAYOUT_IN_SCREEN`/`FLAG_LAYOUT_NO_LIMITS`、`onDrag` 屏幕内 clamp 只改 `x/y`；`RecordPanel` `onInterceptTouchEvent`/`onTouchEvent` 复用拾取手柄「斜率超阈值才认定拖动、绝不误伤按钮」；不改 flags 不拦截外部触摸，见 ADR-019 |\n| **录制可行性判定回归（v2.9.2）** | ✅ 有专门用例 + 四通道接线 | `RecordabilityGuardTest` 14 例：五态纯函数判定、零事件超时仍提示、自绘界面必给「能力边界 + 视频导入 + 无限循环」替代路径、正常录制不打扰、8s 门槛不误报；`RecorderBus.signal()` + `RecordPanel.setHint()` + `RecordingService` 四通道只提示一次，见 ADR-018 |
| **循环一等入口回归（v2.9.2）** | ✅ 有专门用例 + 三处接线 | `LoopPolicyTest` 6 例（`0`=无限、钳制、中文解析、非法用 null）+ `VideoScriptBuilderTest` 4 例（默认 1 零回归、无限循环产物、归一化、不改动找图锚定）；`EditorScreen` 循环对话框、`VideoImportDialog` chips、`VideoScriptBuilder.build(repeatCount)` 全部接线，`ScriptRunner` 既有无限循环能力不变，见 ADR-018 |
| **新增 core 符号进 release dex（v2.9.2）** | ✅ 发版硬门槛 | `verify_release.py` 断言 `RecordabilityGuard` / `LoopPolicy` 必须出现在 release dex，否则判 FAIL（ADR-008）；实测 959 个本工程类全保留可读名 |
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
| ROI 全量写入回归 | ✅ 有专门用例 | `RoiApplierTest` 6 例：目标动作 / 条件树 / `children` / `listeners` / `abortCondition`、无目标返回原对象、命中数一致；UI 命中 0 处如实提示，见 ADR-010 |
| 撤销栈完整性回归 | ✅ | 升级为完整 `Script` 快照，`restore()` 同时恢复 actions / name / `scriptBase`；源码视图「应用」保留 `variables`/`listeners`/`abortCondition` |
| 单测统计口径修正 | ✅ | v2.7.0 脚本只匹配 `test-results/test/`（仅 core），漏掉 app `testDebugUnitTest/`；v2.7.1 改为匹配全部测试目录，总数由旧口径 248 变为更完整的 **297**（纳入此前漏统计的 app 单测，非虚报增量） |
| **新能力接线扫描** | ✅ 通过 | 逐个统计新增 core 符号的引用点；v2.9.0 的 `NymphDesignTokens`（Theme.kt + FloatingEditorTheme）、`OverlayWindowSpec`（FloatingEditorService 三处）、`AtomicTextFile`（3 个 store）、`ScriptMigrations`（ScriptCodec）均有生产消费点；v2.7.0 的 `RoiExpr` / `ScriptRepository` 亦已补齐（ADR-008）。无「写了没接」残留 |
| ROI 表达式运行时回归 | ✅ 有专门用例 | `RoiExprTest` 3 例：空表达式原样返回 / `topHalf` 覆盖数值 / 非法表达式静默退回 |
| 数据丢失 bug 回归 | ✅ 有专门用例 | `ActionEditGuardTest.编辑简易类型时保留全部非编辑字段` |
| 录制自扰回归 | ✅ 有专门用例 | `RecordGateTest.点击器内的自扰事件全部丢弃` |
| `assembleRelease` | ✅ | 见发布说明 |
| zipalign + apksigner v2/v3 同钥 | ✅ | 证书 `361722d6…6271`，与全部历史版本同钥 |
| 权限 / ABI / 录屏 API 合规扫描 | ✅ | aapt2 + dexdump，零 `MediaProjection` / `VirtualDisplay` / `MediaCodec` 录屏编码 / `FLAG_SECURE` / `RECORD_AUDIO` |
| 无混淆 / 无加密 / 类名可读 | ✅ | `isMinifyEnabled=false`，`isShrinkResources=false`；dexdump 实测 **972 个** `dev/nymph/auto` 类全保留可读名（v2.9.5 为 967），`NymphDesignTokens` / `OverlayWindowSpec` / `AtomicTextFile` / `ScriptMigrations` / `RunStats` / `DragGesture` / `WaitGate` 等新符号已确认进 dex |
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
第三方 so 未对齐会如实列出但不冒充修复——这是针对「v2.8.1 文档写已对齐、
产物实测 4KB」这类文档与产物不符问题建立的机制防线。可单独运行：

```
python3.11 tools/check_elf_alignment.py            # 人读表格
python3.11 tools/check_elf_alignment.py --json    # 机器可读
```

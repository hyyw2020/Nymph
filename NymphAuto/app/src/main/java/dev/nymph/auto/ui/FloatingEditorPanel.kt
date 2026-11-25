// SPDX-License-Identifier: MIT
package dev.nymph.auto.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.nymph.auto.core.editor.ActionEditGuard
import dev.nymph.auto.core.editor.ExecutionParadigm
import dev.nymph.auto.core.editor.FloatingEditorLayout
import dev.nymph.auto.core.editor.FloatingEditorTheme
import dev.nymph.auto.core.editor.SceneList
import dev.nymph.auto.core.editor.SceneTuning
import dev.nymph.auto.core.editor.StepPolicy
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.SceneRule

/**
 * 系统级悬浮场景编辑器的紧凑面板（v2.9.21 交互层级重构）。
 *
 * ## 主辅关系与视觉层级
 *
 * 坐标拾取是主流程与第一视觉焦点：[btnPick] 使用强调色、置于当前场景名之下，
 * 是唯一的「进入画布拾取」入口。颜色样本、容差与有效点击区域（ROI）由一次
 * 拾取自动派生，收在 [moreBox] 的第二视觉层级（折叠区 + [tvAssist] 轻量状态条），
 * 不作为需要用户先配置的概念。
 *
 * 场景导航由 [sceneListBox] 的只读坐标摘要列表承担（点行进详情），取代
 * 「上一 / 下一」逐条翻页；列表行不主动设置 focusable，也不承载名称编辑。
 * 场景名是 [etSceneName]：一个在 init 阶段即实例化、可获焦、可唤起输入法的
 * [EditText]，落在第一视觉层级，点击后由 [requestInputFocus] 完成窗口焦点切换
 * 与软键盘显示。名称编辑不使用只读文本、不使用 isFocusable=false、不使用懒实例化。
 *
 * ## 纯 View 与窗口约束（延续 v2.8.0～v2.9.20 既有成果）
 *
 * 面板由 `FloatingEditorService` 以 `TYPE_APPLICATION_OVERLAY` 添加，跨 APP 显示、
 * 后台可呼出。拖动采用屏幕绝对坐标 [dragListener]（与 RunnerService 运行球同一
 * 已验证机制）；顶部条移出 [MaxHeightScrollView] 以避免 MOVE 被截获；折叠/展开
 * 不改窗口 flags 的抗抽搐约束保持不变；[MaxHeightScrollView.onMeasure] 交由
 * [FloatingEditorLayout.measureHeight] 正确处理 UNSPECIFIED。
 *
 * 术语由 core [SceneList] 单一真源提供：用户可见层统一以「场景」表述，不出现
 * 「动作 / 动作名称 / 动作描述 / 添加动作 / 场景 N / M」等叫法。落盘字段
 * （Action.desc 等）保持不变，场景名读写仍落在既有 desc 字段。
 */
@SuppressLint("ViewConstructor")
class FloatingEditorPanel(context: Context) : LinearLayout(context) {

    /** 面板对外的回调（由 Service 接线到脚本读写 / 拾取器 / 关闭）。 */
    var onSave: ((FloatingEditorLayout.Form) -> Unit)? = null
    var onPickPoint: ((target: Int) -> Unit)? = null   // 0=起点，1=终点
    /**
     * v2.9.29：取「最近一次落在画面上的位置」填进坐标，跳过在画面上精确对准。
     *
     * 与 [onPickPoint] 的区别是它不进入拾取模式、不弹画布遮罩、不改变窗口焦点：
     * 只是把上一次已经确认过的点复用一次。用户刚在第 3 个场景点过一次，
     * 想在第 4 个场景复用同一个横坐标时，这个入口比重新拾取快一步。
     */
    var onUseCurrentPoint: (() -> Pair<Double, Double>?)? = null
    var onPickRegion: (() -> Unit)? = null
    var onApplyRoi: (() -> Int)? = null
    /** v2.9.21：点场景列表行进详情（位置仅用于定位，不构成执行顺序编号）。 */
    var onSelectScene: ((Int) -> Unit)? = null
    var onAdd: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onCollapse: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null
    /** 手动框选覆盖 / 清除回退 / 重置自动区域。 */
    var onClearManualRoi: (() -> Unit)? = null
    var onResetAutoRoi: (() -> Unit)? = null
    /** v2.9.22：结果卡一键纠偏（重新拾取 / 执行主修复）与诊断摘要复制。 */
    var onRepick: (() -> Unit)? = null
    var onRunPrimaryFix: (() -> Unit)? = null
    var onCopyDiag: (() -> Unit)? = null

    var onDragAbsolute: ((startWinX: Int, startWinY: Int, downRawX: Float, downRawY: Float,
                          rawX: Float, rawY: Float) -> Unit)? = null

    /** 用户主动点输入框：Service 位级清除 FLAG_NOT_FOCUSABLE，使窗口可聚焦。 */
    var onRequestInputFocus: (() -> Unit)? = null
    /** 输入框失焦：Service 恢复 FLAG_NOT_FOCUSABLE，避免软键盘反复弹收。 */
    var onReleaseInputFocus: (() -> Unit)? = null

    var state: FloatingEditorLayout.State = FloatingEditorLayout.State.EXPANDED
        private set

    private var d = resources.displayMetrics.density
    private var cachedSh = resources.displayMetrics.heightPixels
    private val collapsedBall: View
    private val expanded: LinearLayout
    private val tvTitle: TextView
    /** 第一视觉层级：当前场景标题（不呈现编号）。 */
    private val tvCurrentScene: TextView
    /** v2.9.21：可编辑场景名，init 即实例化、可获焦、可唤起输入法。 */
    private val etSceneName: EditText
    /** 第一视觉层级：拾取状态 / 命中结果 / 判定中。 */
    private val tvStatus: TextView
    /** v2.9.22：拾取结果卡——纠偏中枢，结论与四个动作同卡可见，不折叠、不跳转。 */
    private val pickCard: LinearLayout
    private val tvCardHead: TextView
    private val tvCardMeta: TextView
    private val tvCardMode: TextView
    private val btnFix: TextView
    private val btnDiag: TextView
    private val btnClearManual: TextView
    /** 首次进入的三步引导；场景建立后自动隐藏。 */
    private val tvGuide: TextView
    private val btnPick: TextView
    private var pickBusy = false
    /** v2.9.25：保存按钮随结论改变语义（不可用时它就是「重新拾取」）。 */
    private val btnSave: TextView
    private var lastResult: Result? = null
    /** v2.9.29：落点边缘警告（非空时追加到结果卡说明行，随每次结论重绘）。 */
    private var edgeWarn: String? = null
    /** 第二视觉层级：颜色样本 / 容差 / 有效区域的一行轻量状态条。 */
    private val tvAssist: TextView
    /** 场景列表容器：只读坐标摘要行，点行进详情。 */
    private val sceneListBox: LinearLayout
    private val tvSceneIntent: TextView
    private val tvParadigm: TextView
    private val tvIntentDetail: TextView
    private val chips = mutableListOf<TextView>()
    /** v2.9.25：坐标手输区（默认收起，减少首屏控件量）。 */
    private val coordBox: LinearLayout
    private val etX: EditText
    private val etY: EditText
    private val etX2: EditText
    private val etY2: EditText
    private val swipeRow: LinearLayout
    private val etHold: EditText
    private val etDuration: EditText
    private val etTimes: EditText
    private val etWait: EditText
    private val moreBox: LinearLayout
    private val btnMore: TextView
    private var moreOpen = false
    private var kind = ActionEditGuard.KIND_TAP
    private var pickedRegion: Region? = null
    /**
     * v2.9.32 拾取后判据参数精调：字段键 → 输入框，与 [SceneTuning.fields] 对齐。
     * 收在第二视觉层级，留空即「沿用装配值」，不改变首屏高度。
     */
    private val tuningInputs = LinkedHashMap<String, EditText>()
    /** 当前装配出的判据基线，供留空步进与「留空沿用」提示取值。 */
    private var tuningBaseline: SceneRule? = null
    private val tvTuningSummary: TextView

    private var winX = 0
    private var winY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var startWinX = 0
    private var startWinY = 0
    private var dragging = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** 屏幕绝对坐标拖动（与 RunnerService.attachBallTouch 同一套已验证机制）。 */
    private val dragListener = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                DragDiagnostics.onDown(DragDiagnostics.Target.EDITOR_PANEL)
                startWinX = winX
                startWinY = winY
                downRawX = e.rawX
                downRawY = e.rawY
                dragging = false
                (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                DragDiagnostics.onMove(DragDiagnostics.Target.EDITOR_PANEL)
                if (!dragging) {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (FloatingEditorLayout.isDragMove(dx, dy, FloatingEditorLayout.dragStartPx(d))) {
                        dragging = true
                    }
                }
                if (dragging) {
                    onDragAbsolute?.invoke(startWinX, startWinY, downRawX, downRawY, e.rawX, e.rawY)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                DragDiagnostics.onEnd(
                    DragDiagnostics.Target.EDITOR_PANEL,
                    e.actionMasked == MotionEvent.ACTION_CANCEL,
                )
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && FloatingEditorLayout.isTap(false, dx, dy, touchSlop)
                    && state == FloatingEditorLayout.State.COLLAPSED) {
                    onToggle?.invoke()
                }
                dragging = false
                true
            }
            else -> false
        }
    }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(FloatingEditorTheme.RADIUS_CARD_DP).toFloat()
            setColor(FloatingEditorTheme.BG)
            setStroke(strokeW(), FloatingEditorTheme.STROKE)
        }

        collapsedBall = View(context).apply {
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FloatingEditorTheme.BALL)
                setStroke((2 * d).toInt().coerceAtLeast(2), FloatingEditorTheme.BALL_RING)
            }
            val inner = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF0FDFA.toInt())
            }
            val layers = LayerDrawable(arrayOf(outer, inner))
            val inset = dp(FloatingEditorLayout.COLLAPSED_DP / 2 - 5)
            layers.setLayerInset(1, inset, inset, inset, inset)
            background = layers
            contentDescription = "展开悬浮场景编辑器（按住可拖动）"
            visibility = View.GONE
            setOnTouchListener(dragListener)
        }
        addView(collapsedBall, LayoutParams(
            dp(FloatingEditorLayout.COLLAPSED_DP), dp(FloatingEditorLayout.COLLAPSED_DP),
        ))

        expanded = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = MaxHeightScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(FloatingEditorTheme.PAD_CARD_DP), dp(6), dp(FloatingEditorTheme.PAD_CARD_DP), dp(10))
        }

        // 顶部条（移出 ScrollView，与 RunnerService 运行球同为单一 View + 监听器）
        val dragHandle = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(FloatingEditorTheme.BAR_H_DP)
            setPadding(dp(3), 0, dp(2), 0)
            isClickable = false
        }
        dragHandle.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FloatingEditorTheme.ACCENT)
            }
        }, LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) })
        tvTitle = TextView(context).apply {
            text = "场景编辑"
            textSize = FloatingEditorTheme.TEXT_TITLE_SP
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(FloatingEditorTheme.TEXT)
        }
        dragHandle.addView(tvTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(glyphBtn(Glyph.MINUS) { onCollapse?.invoke() })
        actions.addView(glyphBtn(Glyph.CLOSE) { onClose?.invoke() })
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(dragHandle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(actions)
        expanded.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        dragHandle.setOnTouchListener(dragListener)

        body.addView(View(context).apply {
            background = GradientDrawable().apply { setColor(FloatingEditorTheme.STROKE) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, strokeW()).apply {
            topMargin = dp(2); bottomMargin = dp(4)
        })

        // ---------------- 第一视觉层级：当前场景 / 场景名 / 拾取主入口 / 命中结果
        tvCurrentScene = TextView(context).apply {
            text = SceneList.TITLE_CURRENT
            textSize = FloatingEditorTheme.TEXT_LABEL_SP
            setTextColor(FloatingEditorTheme.OK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(2))
        }
        body.addView(tvCurrentScene)

        // 场景名：init 即实例化、可获焦、可唤起输入法；不使用只读 / 不可聚焦 / 懒实例化
        etSceneName = EditText(context).apply {
            hint = SceneList.LABEL_NAME_HINT
            textSize = FloatingEditorTheme.TEXT_BODY_SP
            setTextColor(FloatingEditorTheme.TEXT)
            setHintTextColor(FloatingEditorTheme.TEXT_MUTED)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            minHeight = dp(FloatingEditorTheme.FIELD_MIN_H_DP)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = fieldBg(false)
            setOnClickListener { requestInputFocus(this) }
            setOnFocusChangeListener { _, hasFocus ->
                background = fieldBg(hasFocus)
                if (!hasFocus) onReleaseInputFocus?.invoke()
            }
        }
        body.addView(etSceneName, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 坐标拾取主入口：强调色实心按钮，第一视觉焦点
        btnPick = TextView(context).apply {
            text = "⊕ ${SceneList.LABEL_PICK}"
            textSize = FloatingEditorTheme.TEXT_TITLE_SP
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xFF052E24.toInt())
            gravity = Gravity.CENTER
            isClickable = true
            minHeight = dp(FloatingEditorTheme.FIELD_MIN_H_DP + 8)
            contentDescription = "拾取坐标，进入画面取点"
            background = GradientDrawable().apply {
                cornerRadius = dp(FloatingEditorTheme.RADIUS_CONTROL_DP).toFloat()
                setColor(FloatingEditorTheme.ACCENT)
            }
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { onPickPoint?.invoke(0) }
        }
        body.addView(btnPick, wideItem())

        // v2.9.25：坐标手输是备选路径（拾取是主路径），默认收起。首屏因此少掉
        // 一个分区标题与两个输入框，拾取仍是唯一显眼的主按钮。
        coordBox = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        val xy = LinearLayout(context).apply { orientation = HORIZONTAL }
        etX = numberField("X %")
        etY = numberField("Y %")
        xy.addView(etX, fieldItem())
        xy.addView(etY, fieldItem())
        coordBox.addView(sectionLabel("坐标（屏幕百分比）"))
        coordBox.addView(xy, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // v2.9.29：坐标区里补一个「用当前位置」——不重新进入拾取模式，直接把上一次
        // 已确认的落点填进来。参考产品把这一步做成默认值（新建动作即带当前点），
        // 这里保留拾取作为主路径，只把「复用」补成第二入口。
        coordBox.addView(outlinedBtn("用当前位置") { useCurrentPoint() }, wideItem())
        body.addView(outlinedBtn("手动输入坐标") { toggleManualCoord() }, wideItem())
        body.addView(coordBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 拾取状态 / 命中结果 / 判定中（固定一行，窗口高度恒定）
        tvStatus = TextView(context).apply {
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setLines(1)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), 0, dp(2))
        }
        body.addView(tvStatus)

        // ---------------- v2.9.22 拾取结果卡：纠偏中枢
        // 结论、自动派生素材、四个纠偏动作与主修复按钮同卡可见，用户不需要展开
        // 折叠区、不需要理解容差或颜色距离即可完成纠偏。卡片在无拾取结果时隐藏，
        // 不改变窗口默认高度，也不影响既有折叠/展开的抗抽搐约束。
        tvCardHead = TextView(context).apply {
            textSize = FloatingEditorTheme.TEXT_LABEL_SP
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(FloatingEditorTheme.TEXT)
            setPadding(0, 0, 0, dp(3))
        }
        tvCardMeta = TextView(context).apply {
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setPadding(0, 0, 0, dp(2))
        }
        tvCardMode = TextView(context).apply {
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
        }
        btnFix = outlinedBtn("", { onRunPrimaryFix?.invoke() }).apply {
            visibility = View.GONE
            textSize = FloatingEditorTheme.TEXT_LABEL_SP
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xFF052E24.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(FloatingEditorTheme.RADIUS_CONTROL_DP).toFloat()
                setColor(FloatingEditorTheme.ACCENT)
            }
        }
        // v2.9.25：结果卡只保留「重新拾取 / 编辑区域」两个直接作用于拾取的动怍。
        // 「清除手动 / 恢复自动」是区域微调，已在辅助门控区各有一个同名按钮，
        // 同卡重复呈现只会让卡片变成第二堵按钮墙。
        val actRow1 = LinearLayout(context).apply { orientation = HORIZONTAL }
        actRow1.addView(cardAction("重新拾取") { onRepick?.invoke() }, chipItem())
        actRow1.addView(cardAction("编辑区域") { onPickRegion?.invoke() }, chipItem())
        btnDiag = TextView(context).apply {
            text = "复制诊断"
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.ACCENT)
            gravity = Gravity.END
            isClickable = true
            contentDescription = "复制诊断摘要，可发给客服或开发者复现"
            setPadding(0, dp(4), dp(2), dp(2))
            setOnClickListener { onCopyDiag?.invoke() }
        }
        pickCard = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(7), dp(8), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(FloatingEditorTheme.RADIUS_FIELD_DP).toFloat()
                setColor(FloatingEditorTheme.BG_ELEVATED)
                setStroke(strokeW(), FloatingEditorTheme.STROKE_FOCUS)
            }
            addView(tvCardHead, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(tvCardMeta, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(tvCardMode, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(actRow1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
            addView(btnFix, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            addView(btnDiag, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        body.addView(pickCard, wideItem())

        tvGuide = hintTextView().apply {
            text = "三步完成：① 点「⊕ 拾取坐标」在画面上取一个点 ② 颜色与区域自动生成 " +
                "③ 点「保存」写入脚本"
            visibility = View.GONE
        }
        body.addView(tvGuide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---------------- 场景列表：只读坐标摘要，点行进详情
        body.addView(sectionLabel(SceneList.TITLE_LIST))
        sceneListBox = LinearLayout(context).apply { orientation = VERTICAL }
        body.addView(sceneListBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(outlinedBtn(SceneList.LABEL_NEW) { onAdd?.invoke() }, wideItem())

        // ---------------- 第二视觉层级：辅助门控（默认折叠，轻量状态条）
        btnMore = outlinedBtn("${SceneList.LABEL_ASSIST} ⌄") { setAdvancedOpen(!moreOpen) }
        body.addView(btnMore, wideItem())
        moreBox = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        tvAssist = hintTextView().apply { text = "颜色 / 容差 / 有效区域：拾取坐标后自动生成" }
        moreBox.addView(tvAssist, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 手势类型（第二层）：选「滑动」自动展开本区以显示终点与时长
        val gestureRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        listOf("点击", "长按", "双击", "滑动").forEachIndexed { i, label ->
            val b = outlinedBtn(label) { selectKind(i) }.apply {
                textSize = FloatingEditorTheme.TEXT_LABEL_SP
                setPadding(dp(2), dp(FloatingEditorTheme.PAD_CONTROL_V_DP),
                    dp(2), dp(FloatingEditorTheme.PAD_CONTROL_V_DP))
            }
            chips.add(b)
            gestureRow.addView(b, chipItem())
        }
        moreBox.addView(sectionLabel("手势类型"))
        moreBox.addView(gestureRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 有效区域：手动覆盖 / 清除回退 / 重置自动 / 应用到脚本
        moreBox.addView(sectionLabel("有效点击区域"))
        moreBox.addView(outlinedBtn(SceneList.LABEL_MANUAL_ROI) { onPickRegion?.invoke() }, wideItem())
        btnClearManual = outlinedBtn(SceneList.LABEL_CLEAR_MANUAL_ROI) { onClearManualRoi?.invoke() }
        moreBox.addView(btnClearManual, wideItem())
        moreBox.addView(outlinedBtn(SceneList.LABEL_RESET_AUTO_ROI) { onResetAutoRoi?.invoke() }, wideItem())
        moreBox.addView(outlinedBtn("应用区域到脚本") {
            val hits = onApplyRoi?.invoke() ?: 0
            setStatus(if (hits > 0) "✓ 区域已应用到脚本内 $hits 处" else "脚本内暂无可应用区域的目标")
        }, wideItem())

        swipeRow = LinearLayout(context).apply { orientation = VERTICAL }
        swipeRow.addView(sectionLabel("滑动终点 / 时长"))
        val x2y2 = LinearLayout(context).apply { orientation = HORIZONTAL }
        etX2 = numberField("终点 X %"); etY2 = numberField("终点 Y %")
        x2y2.addView(etX2, fieldItem())
        x2y2.addView(etY2, fieldItem())
        swipeRow.addView(x2y2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        swipeRow.addView(outlinedBtn("屏幕拾取终点") { onPickPoint?.invoke(1) }, wideItem())
        etDuration = numberField(StepPolicy.hintOf(StepPolicy.DURATION))
        swipeRow.addView(stepperRow(StepPolicy.DURATION, etDuration), wideItem())
        moreBox.addView(swipeRow)

        etHold = numberField(StepPolicy.hintOf(StepPolicy.HOLD))
        etTimes = numberField(StepPolicy.hintOf(StepPolicy.TIMES))
        etWait = numberField(StepPolicy.hintOf(StepPolicy.WAIT))
        // v2.9.29：四个数值改为「短标签 + − 值 +」步进器。原先每调一次都要
        // 走「清 NOT_FOCUSABLE → 请求焦点 → 唤起输入法 → 收键盘 → 归还焦点」，
        // 在悬浮窗里只要一步不顺，用户就会觉得按钮点了没反应。
        moreBox.addView(sectionLabel("按下时间 / 次数 / 等待"))
        moreBox.addView(stepperRow(StepPolicy.HOLD, etHold), wideItem())
        moreBox.addView(stepperRow(StepPolicy.TIMES, etTimes), wideItem())
        moreBox.addView(stepperRow(StepPolicy.WAIT, etWait), wideItem())

        // v2.9.32 拾取后判据参数精调：留空沿用装配值，填值显式覆盖。
        // 全部收在第二视觉层级（默认折叠），不改变默认窗口高度。
        moreBox.addView(sectionLabel("场景判据参数（留空沿用装配值）"))
        tvTuningSummary = hintTextView()
        tvTuningSummary.contentDescription = "当前装配判据摘要"
        moreBox.addView(tvTuningSummary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        for (f in SceneTuning.fields()) {
            val et = numberField(SceneTuning.hintOf(f, f.read(SceneRule())))
            tuningInputs[f.key] = et
            moreBox.addView(tuningRow(f, et), wideItem())
        }

        // 只读解释卡片（v2.9.16～v2.9.17 成果，收在第二层，不改变默认高度）
        tvParadigm = hintTextView()
        tvSceneIntent = hintTextView()
        tvIntentDetail = hintTextView()
        moreBox.addView(sectionLabel("执行范式"))
        moreBox.addView(tvParadigm, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        moreBox.addView(sectionLabel("触发策略"))
        moreBox.addView(tvSceneIntent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        moreBox.addView(tvIntentDetail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(moreBox)

        val bottomActions = LinearLayout(context).apply { orientation = HORIZONTAL }
        bottomActions.addView(outlinedBtn(SceneList.LABEL_CANCEL) { onClose?.invoke() }, navItem())
        // v2.9.25：保存按钮不再无条件保存。当前结论不可用时，它变成「重新拾取」——
        // 因为此时保存下来的场景在运行期一定不触发，让用户点「保存」拿到一句「已保存」
        // 才是真正难用的地方。
        btnSave = outlinedBtn(SceneList.LABEL_SAVE) {
            val r = lastResult
            if (r != null && !r.hit && !r.pending) onRepick?.invoke() else onSave?.invoke(collect())
        }.apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(FloatingEditorTheme.RADIUS_CONTROL_DP).toFloat()
                setColor(FloatingEditorTheme.ACCENT)
            }
            setTextColor(0xFF052E24.toInt())
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        bottomActions.addView(btnSave, navItem())
        body.addView(bottomActions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        scroll.addView(body)
        expanded.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(expanded, LayoutParams(
            dp(FloatingEditorLayout.EXPANDED_W_DP), LayoutParams.WRAP_CONTENT,
        ))
        selectKind(ActionEditGuard.KIND_TAP)
    }

    // ------------------------------------------------------------ 视觉工具

    private fun dp(v: Int): Int = FloatingEditorLayout.dp(v, d)
    private fun strokeW(): Int = (1 * d).toInt().coerceAtLeast(1)

    private fun navItem(): LayoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(FloatingEditorTheme.GAP_DP)
    }

    private fun fieldItem(): LayoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(FloatingEditorTheme.GAP_DP)
    }

    private fun chipItem(): LayoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(2)
    }

    private fun wideItem(): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(FloatingEditorTheme.GAP_DP)
    }

    private fun fieldBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(FloatingEditorTheme.RADIUS_FIELD_DP).toFloat()
        setColor(FloatingEditorTheme.BG_ELEVATED)
        setStroke(strokeW(), if (focused) FloatingEditorTheme.STROKE_FOCUS else FloatingEditorTheme.STROKE)
    }

    private fun hintTextView(): TextView = TextView(context).apply {
        textSize = FloatingEditorTheme.TEXT_HINT_SP
        setTextColor(FloatingEditorTheme.TEXT_MUTED)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun outlinedBtn(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = FloatingEditorTheme.TEXT_BODY_SP
        setTextColor(FloatingEditorTheme.TEXT_DIM)
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = text
        minHeight = dp(FloatingEditorTheme.FIELD_MIN_H_DP)
        background = GradientDrawable().apply {
            cornerRadius = dp(FloatingEditorTheme.RADIUS_CONTROL_DP).toFloat()
            setColor(FloatingEditorTheme.CONTROL)
            setStroke(strokeW(), FloatingEditorTheme.STROKE)
        }
        setPadding(dp(FloatingEditorTheme.PAD_CONTROL_H_DP), dp(FloatingEditorTheme.PAD_CONTROL_V_DP),
            dp(FloatingEditorTheme.PAD_CONTROL_H_DP), dp(FloatingEditorTheme.PAD_CONTROL_V_DP))
        setOnClickListener { onClick() }
    }

    /** 结果卡内的紧凑动作按钮：两列等宽，字号收小但热区高度不低于控件最小值。 */
    private fun cardAction(text: String, onClick: () -> Unit): TextView =
        outlinedBtn(text, onClick).apply {
            textSize = FloatingEditorTheme.TEXT_LABEL_SP
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

    private fun numberField(hint: String): EditText = EditText(context).apply {        this.hint = hint
        textSize = FloatingEditorTheme.TEXT_BODY_SP
        setTextColor(FloatingEditorTheme.TEXT)
        setHintTextColor(FloatingEditorTheme.TEXT_MUTED)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        isSingleLine = true
        minHeight = dp(FloatingEditorTheme.FIELD_MIN_H_DP)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = fieldBg(false)
        setOnClickListener { requestInputFocus(this) }
        setOnFocusChangeListener { _, hasFocus ->
            background = fieldBg(hasFocus)
            if (!hasFocus) scheduleReleaseInputFocus()
        }
    }

    /**
     * 失焦后是否归还窗口焦点的延迟检查。
     *
     * v2.9.35 竞态修复：从一个输入框直接点到另一个输入框时，A 的
     * `onFocusChange(false)` 与 B 的 `requestInputFocus` 先后顺序不确定。旧实现
     * A 失焦立即 `onReleaseInputFocus`（恢复 FLAG_NOT_FOCUSABLE），若它晚于 B
     * 的 `setInputFocusable(true)` 执行，就把 B 刚拿到的窗口焦点收回，表现为
     * 「第二个输入框打不出字 / 软键盘弹出即收起」。
     *
     * 改为：失焦只 post 一个待执行释放到队尾；真正执行前重新检查是否仍有
     * 输入框持有焦点——焦点已转移到 B 则放弃释放；[requestInputFocus] 也会
     * 主动撤销尚未执行的释放。这样无论回调先后，结果都收敛到「有输入框获焦
     * 则窗口可聚焦，全失焦才归还」。
     */
    private var pendingReleaseFocus: Runnable? = null

    /** 当前是否有任一可编辑输入框持有焦点。 */
    private fun anyInputFocused(): Boolean {
        val fields = buildList {
            add(etSceneName); add(etX); add(etY); add(etX2); add(etY2)
            add(etHold); add(etDuration); add(etTimes); add(etWait)
            addAll(tuningInputs.values)
        }
        return fields.any { it.hasFocus() }
    }

    private fun cancelPendingReleaseFocus() {
        pendingReleaseFocus?.let { removeCallbacks(it) }
        pendingReleaseFocus = null
    }

    private fun scheduleReleaseInputFocus() {
        cancelPendingReleaseFocus()
        val r = Runnable {
            pendingReleaseFocus = null
            // 队尾复核：焦点已转移到其它输入框就不归还窗口焦点。
            if (!anyInputFocused()) onReleaseInputFocus?.invoke()
        }
        pendingReleaseFocus = r
        post(r)
    }

    /**
     * 场景名 / 数值字段的软键盘唤起：先请 Service 位级清除 NOT_FOCUSABLE，
     * 双 post 等待 updateViewLayout 生效后再请求焦点并显式显示输入法。
     * 名称字段在 init 即实例化且可获焦，此处只负责焦点切换时序，不做懒实例化。
     */
    private fun requestInputFocus(et: EditText) {
        // 新一轮获焦：撤销上一次失焦排队的窗口焦点归还，避免误收 B 的焦点。
        cancelPendingReleaseFocus()
        onRequestInputFocus?.invoke()
        et.post {
            et.post {
                et.isFocusable = true
                et.isFocusableInTouchMode = true
                et.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun sectionLabel(text: String): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(FloatingEditorTheme.GAP_DP + 2), 0, dp(4))
        addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = d
                setColor(FloatingEditorTheme.ACCENT)
            }
        }, LayoutParams(dp(3), dp(12)))
        addView(TextView(context).apply {
            this.text = text
            textSize = FloatingEditorTheme.TEXT_LABEL_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setPadding(dp(8), 0, 0, 0)
        })
    }

    private enum class Glyph { MINUS, PLUS, CLOSE }

    private class GlyphView(context: Context, private val glyph: Glyph) : View(context) {
        private val dens = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = FloatingEditorTheme.CONTROL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * dens; color = FloatingEditorTheme.STROKE
        }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f * dens
            strokeCap = Paint.Cap.ROUND; color = FloatingEditorTheme.TEXT_DIM
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) / 2f - 1.5f * dens
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r, ring)
            val s = r * 0.40f
            when (glyph) {
                Glyph.MINUS -> c.drawLine(cx - s, cy, cx + s, cy, ink)
                Glyph.PLUS -> {
                    c.drawLine(cx - s, cy, cx + s, cy, ink)
                    c.drawLine(cx, cy - s, cx, cy + s, ink)
                }
                Glyph.CLOSE -> {
                    c.drawLine(cx - s, cy - s, cx + s, cy + s, ink)
                    c.drawLine(cx + s, cy - s, cx - s, cy + s, ink)
                }
            }
        }
    }

    private fun glyphBtn(g: Glyph, onClick: () -> Unit): GlyphView =
        GlyphView(context, g).apply {
            isClickable = true
            layoutParams = LayoutParams(dp(FloatingEditorTheme.ICON_BTN_DP), dp(FloatingEditorTheme.ICON_BTN_DP))
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ 状态 / 数据

    private fun paintChip(b: TextView, selected: Boolean) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(FloatingEditorTheme.RADIUS_CONTROL_DP).toFloat()
            setColor(if (selected) FloatingEditorTheme.ACCENT else FloatingEditorTheme.CONTROL)
            setStroke(strokeW(), if (selected) FloatingEditorTheme.ACCENT else FloatingEditorTheme.STROKE)
        }
        b.setTextColor(if (selected) 0xFF052E24.toInt() else FloatingEditorTheme.TEXT_DIM)
        b.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    /** v2.9.25：坐标手输区的展开 / 收起；展开后把焦点给 X 字段，省一次点击。 */
    private fun toggleManualCoord() {
        val open = coordBox.visibility != View.VISIBLE
        coordBox.visibility = if (open) View.VISIBLE else View.GONE
        if (open) requestInputFocus(etX)
    }

    /**
     * v2.9.29：把最近一次落在画面上的位置回填到坐标框，不进入拾取模式、不弹键盘。
     *
     * 与「手动输入坐标」的区别是它连输入都省掉：坐标框展开、值填好、光标不进入
     * 编辑态，用户接下来直接点保存即可。没有可复用位置时不静默失败，而是明确
     * 告诉用户下一步该做什么。
     */
    private fun useCurrentPoint() {
        val p = onUseCurrentPoint?.invoke()
        if (p == null) {
            setStatus("还没有可用位置：先在画面上拾取一次，之后即可复用")
            return
        }
        if (coordBox.visibility != View.VISIBLE) coordBox.visibility = View.VISIBLE
        etX.setText(FloatingEditorLayout.fmt(p.first))
        etY.setText(FloatingEditorLayout.fmt(p.second))
        setStatus(
            "已填入最近位置 ${FloatingEditorLayout.fmt(p.first)}% , " +
                "${FloatingEditorLayout.fmt(p.second)}% · 可继续微调或直接保存",
        )
    }

    /**
     * 步进器：`短标签 − 值 +`。
     *
     * 单击走一档，长按连续走（等待上限 600000ms，靠单击到达不现实）。
     * 步进后主动归还窗口焦点，避免面板停在可聚焦态把背后应用的第一下触摸吃掉。
     */
    private fun stepperRow(f: StepPolicy.Field, et: EditText): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = f.short
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setLines(1)
        }, LayoutParams(dp(26), LayoutParams.WRAP_CONTENT))
        row.addView(stepperBtn(Glyph.MINUS) { bump(f, et, -1) }, glyphSlot())
        row.addView(et, LayoutParams(0, dp(FloatingEditorTheme.ICON_BTN_DP), 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
        row.addView(stepperBtn(Glyph.PLUS) { bump(f, et, 1) }, glyphSlot())
        row.addView(TextView(context).apply {
            text = f.unit
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setLines(1)
            setPadding(dp(3), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun glyphSlot(): LayoutParams =
        LayoutParams(dp(FloatingEditorTheme.ICON_BTN_DP), dp(FloatingEditorTheme.ICON_BTN_DP))

    /**
     * 单击一档、长按连发。
     *
     * 停止条件用 [View.isPressed] 而不是额外挂 `setOnTouchListener`：可点击视图在
     * 抬手时自动置 false，连发线程下一拍自行退出，既不需要拦截触摸（避免与
     * 无障碍点击语义冲突，也避免 lint 的 ClickableViewAccessibility 告警），
     * 也不会把 post 留在已脱离窗口的视图上。
     */
    private fun stepperBtn(g: Glyph, onStep: () -> Unit): View {
        val v = GlyphView(context, g)
        v.isClickable = true
        var repeater: Runnable? = null
        fun stop() {
            repeater?.let { v.removeCallbacks(it) }
            repeater = null
        }
        v.setOnClickListener { onStep() }
        v.setOnLongClickListener {
            val r = object : Runnable {
                override fun run() {
                    if (!v.isAttachedToWindow || !v.isPressed) { stop(); return }
                    onStep()
                    v.postDelayed(this, 90L)
                }
            }
            repeater = r
            v.postDelayed(r, 60L)
            true
        }
        return v
    }

    private fun bump(f: StepPolicy.Field, et: EditText, dir: Int) {
        val next = StepPolicy.bump(f, et.text?.toString() ?: "", dir)
        et.setText(StepPolicy.text(f, next))
        et.setSelection(et.text.length)
        // 步进不唤起键盘；若输入框正持有焦点，先还回去，避免窗口保持可聚焦。
        if (et.hasFocus()) et.clearFocus() else onReleaseInputFocus?.invoke()
    }

    /**
     * v2.9.32 判据参数步进器：与 [stepperRow] 同一交互，只是数值规则来自
     * [SceneTuning]；留空时加号以「当前装配值」为基准，而非固定默认值。
     */
    private fun tuningRow(f: SceneTuning.Field, et: EditText): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = f.short
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setLines(1)
        }, LayoutParams(dp(26), LayoutParams.WRAP_CONTENT))
        row.addView(stepperBtn(Glyph.MINUS) { bumpTuning(f, et, -1) }, glyphSlot())
        row.addView(et, LayoutParams(0, dp(FloatingEditorTheme.ICON_BTN_DP), 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
        row.addView(stepperBtn(Glyph.PLUS) { bumpTuning(f, et, 1) }, glyphSlot())
        row.addView(TextView(context).apply {
            text = f.unit
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setLines(1)
            setPadding(dp(3), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun bumpTuning(f: SceneTuning.Field, et: EditText, dir: Int) {
        val assembled = tuningBaseline?.let { f.read(it) } ?: f.min
        val next = SceneTuning.bump(f, et.text?.toString() ?: "", dir, assembled)
        et.setText(SceneTuning.text(f, next))
        et.setSelection(et.text.length)
        if (et.hasFocus()) et.clearFocus() else onReleaseInputFocus?.invoke()
    }

    /**
     * 刷新判据基线：把当前装配规则的生效值呈现在摘要与占位文本里，
     * 使用户明确「留空沿用」的具体数值，避免凭空猜测。
     */
    fun setTuningBaseline(rule: SceneRule?) {
        tuningBaseline = rule
        tvTuningSummary.text = if (rule == null) {
            "尚未装配：拾取坐标后自动派生判据；留空即沿用装配值"
        } else {
            "当前生效：${SceneTuning.summary(rule)}"
        }
        for ((key, et) in tuningInputs) {
            val f = SceneTuning.field(key) ?: continue
            val assembled = rule?.let { f.read(it) } ?: f.read(SceneRule())
            et.hint = SceneTuning.hintOf(f, assembled)
        }
    }

    private fun selectKind(k: Int) {
        kind = k
        chips.forEachIndexed { i, b -> paintChip(b, i == k) }
        swipeRow.visibility = if (k == ActionEditGuard.KIND_SWIPE) View.VISIBLE else View.GONE
        if (k == ActionEditGuard.KIND_SWIPE && !moreOpen) setAdvancedOpen(true)
    }

    private fun setAdvancedOpen(open: Boolean) {
        moreOpen = open
        moreBox.visibility = if (open) View.VISIBLE else View.GONE
        btnMore.text = if (open) "${SceneList.LABEL_ASSIST} ⌃" else "${SceneList.LABEL_ASSIST} ⌄"
    }

    /**
     * 场景列表渲染：每行为只读坐标摘要，点行进详情。行不主动设置 focusable，
     * 名称编辑由 [etSceneName] 独立承担，二者不耦合。
     */
    fun bindSceneList(rows: List<SceneList.Row>) {
        sceneListBox.removeAllViews()
        // v2.9.25：只有一个场景时，顶部「当前场景」标签与场景名输入框表达同一件事，
        // 隐藏它，把首屏留给真正要操作的东西。
        tvCurrentScene.visibility = if (rows.size <= 1) View.GONE else View.VISIBLE
        if (rows.isEmpty()) {
            sceneListBox.addView(hintTextView().apply {
                text = "暂无场景，点「${SceneList.LABEL_NEW}」创建"
            })
            return
        }
        rows.forEach { row ->
            val tv = TextView(context).apply {
                // 选中态以「●」标记为主、颜色为辅，避免仅靠颜色区分（可访问性）。
                text = if (row.selected) "● ${row.coordinateLabel}" else "○ ${row.coordinateLabel}"
                textSize = FloatingEditorTheme.TEXT_BODY_SP
                setTextColor(if (row.selected) FloatingEditorTheme.TEXT else FloatingEditorTheme.TEXT_MUTED)
                setTypeface(Typeface.DEFAULT, if (row.selected) Typeface.BOLD else Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isClickable = true
                contentDescription = "场景，坐标 ${row.coordinateLabel}" +
                    if (row.selected) "，当前选中" else ""
                background = GradientDrawable().apply {
                    cornerRadius = dp(FloatingEditorTheme.RADIUS_FIELD_DP).toFloat()
                    setColor(if (row.selected) FloatingEditorTheme.STROKE_FOCUS else FloatingEditorTheme.CONTROL)
                }
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setOnClickListener { onSelectScene?.invoke(row.position) }
            }
            sceneListBox.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
        }
    }

    /** 第二视觉层级状态条：颜色样本 / 容差 / 有效区域模式，一行轻量呈现。 */
    fun showAssist(colorText: String, roiText: String, modeText: String) {
        tvAssist.text = "颜色 $colorText · 区域 $roiText · $modeText"
    }

    /**
     * 结果卡载荷。结论、素材摘要、区域模式、主修复动作与手动覆盖状态同卡呈现；
     * [fixLabel] 为空表示当前结论无需修复（命中 / 判定中），界面隐藏修复按钮。
     */
    data class Result(
        val headline: String,
        val meta: String,
        val mode: String,
        val fixLabel: String?,
        val manual: Boolean,
        val pending: Boolean,
        val hit: Boolean,
    )

    /** [r] 为 null 时隐藏结果卡，回到「尚未拾取」的初始高度。 */
    /**
     * v2.9.29：设置 / 清除落点边缘警告。
     *
     * 单独存在而不是塞进 [Result]：一次拾取的结论可能被后续门控重算覆盖多次，
     * 但「这个点本来就是从热区里挪出来的」这一事实直到下次拾取前都成立。
     * 由这里持有，任何一次重绘都不会把它丢掉。
     */
    fun setEdgeWarn(text: String?) {
        edgeWarn = text
        val r = lastResult ?: return
        tvCardMeta.text = composeMeta(r)
    }

    private fun composeMeta(r: Result): String =
        if (edgeWarn.isNullOrBlank()) r.meta else "${r.meta}\n⚠ $edgeWarn"

    fun showResult(r: Result?) {
        if (r == null) {
            pickCard.visibility = View.GONE
            lastResult = null
            btnSave.text = SceneList.LABEL_SAVE
            btnSave.contentDescription = SceneList.LABEL_SAVE
            setPickBusy(false)
            return
        }
        pickCard.visibility = View.VISIBLE
        tvCardHead.text = r.headline
        tvCardHead.setTextColor(if (r.hit) FloatingEditorTheme.OK else FloatingEditorTheme.WARN)
        tvCardHead.contentDescription = "判定结论：${r.headline}"
        tvCardMeta.text = composeMeta(r)
        tvCardMode.text = r.mode
        val fix = r.fixLabel
        if (fix.isNullOrBlank()) {
            btnFix.visibility = View.GONE
        } else {
            btnFix.visibility = View.VISIBLE
            btnFix.text = fix
            btnFix.contentDescription = "主修复动作：$fix"
        }
        btnClearManual.isEnabled = r.manual
        btnClearManual.alpha = if (r.manual) 1f else 0.4f
        lastResult = r
        val repick = !r.hit && !r.pending
        btnSave.text = if (repick) "重新拾取" else SceneList.LABEL_SAVE
        btnSave.contentDescription =
            if (repick) "当前场景不可用，点此重新拾取坐标" else SceneList.LABEL_SAVE
        setPickBusy(r.pending)
    }

    /** 首次进入的三步引导：仅无场景时呈现，不占默认高度。 */
    fun showGuide(visible: Boolean) {
        tvGuide.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 判定中禁用重复拾取与修复，避免连点产生过期任务竞态。 */
    fun setPickBusy(busy: Boolean) {
        pickBusy = busy
        btnPick.isEnabled = !busy
        btnPick.alpha = if (busy) 0.5f else 1f
        btnFix.isEnabled = !busy
    }

    fun isPickBusy(): Boolean = pickBusy

    /** 用 core 的表单填入全部字段（保真 + Locale 安全）；场景名写入 desc 承载字段。 */
    fun bind(form: FloatingEditorLayout.Form, titleText: String, supported: Boolean) {
        kind = form.kind
        selectKind(form.kind)
        etX.setText(form.x); etY.setText(form.y)
        etX2.setText(form.x2); etY2.setText(form.y2)
        etHold.setText(form.holdMs); etDuration.setText(form.durationMs)
        etTimes.setText(form.times); etWait.setText(form.waitMs)
        etSceneName.setText(form.desc)
        pickedRegion = form.region
        // v2.9.32：载入既有场景时回显落盘判据参数（空串沿用装配值），
        // 使「拾取之后继续调整参数」在切换场景 / 重新打开面板后仍然可见。
        for ((key, et) in tuningInputs) {
            val f = SceneTuning.field(key) ?: continue
            et.setText(form.tuning.raw(f))
        }
        tvCurrentScene.text = titleText
        setParadigm(ExecutionParadigm.describe(ExecutionParadigm.Kind.SEQUENTIAL))
        setSceneIntent(null)
        setIntentDetail(null)
        setStatus(if (supported) "" else "该类型不在简易编辑范围，保存不会改动它")
        // 切换场景 / 新建场景时清空上一场景的拾取结果卡，避免结论串台。
        showResult(null)
    }

    fun setParadigm(card: ExecutionParadigm.Card) {
        tvParadigm.text = "${card.title}\n${card.detailText}\n· 范式切换：${card.switchHint}"
    }

    fun setSceneIntent(summary: String?) {
        tvSceneIntent.text = summary ?: "当前场景未启用场景触发；后果明细不呈现"
    }

    fun setIntentDetail(detail: String?) {
        tvIntentDetail.text = detail ?: "当前场景未启用预设意图组合，后果明细不呈现"
    }

    fun setPoint(target: Int, xPct: Double, yPct: Double) {
        val sx = FloatingEditorLayout.fmt(xPct)
        val sy = FloatingEditorLayout.fmt(yPct)
        if (target == 1) { etX2.setText(sx); etY2.setText(sy) }
        else { etX.setText(sx); etY.setText(sy) }
        setStatus("✓ 已拾取坐标 $sx , $sy %")
    }

    fun setRegion(region: Region) {
        pickedRegion = region
        setStatus("✓ 区域 ${FloatingEditorLayout.fmt(region.leftPct)},${FloatingEditorLayout.fmt(region.topPct)}" +
            " ~ ${FloatingEditorLayout.fmt(region.rightPct)},${FloatingEditorLayout.fmt(region.bottomPct)} %")
    }

    /**
     * 拾取状态 / 命中结果 / 判定中：文字前缀为主、颜色为辅，不依赖颜色单独表意；
     * 同时写入 contentDescription，使 TalkBack 可读出完整结论。
     */
    fun setStatus(text: String) {
        tvStatus.text = text
        tvStatus.contentDescription = if (text.isBlank()) "" else "状态：$text"
        val emphasize = text.startsWith("✓") || text.startsWith("命中") ||
            text.startsWith("判定中") || text.startsWith("正在校验")
        tvStatus.setTypeface(Typeface.DEFAULT, if (emphasize) Typeface.BOLD else Typeface.NORMAL)
        tvStatus.setTextColor(when {
            text.startsWith("✓") || text.startsWith("命中") -> FloatingEditorTheme.OK
            text.startsWith("判定中") || text.startsWith("正在校验") -> FloatingEditorTheme.ACCENT
            text.startsWith("未命中") || text.contains("暂无") || text.contains("失败") ||
                text.contains("不在") || text.contains("不符") || text.contains("之外") ->
                FloatingEditorTheme.WARN
            else -> FloatingEditorTheme.TEXT_MUTED
        })
    }

    fun collect(): FloatingEditorLayout.Form = FloatingEditorLayout.Form(
        kind = kind,
        x = etX.text.toString(), y = etY.text.toString(),
        x2 = etX2.text.toString(), y2 = etY2.text.toString(),
        holdMs = etHold.text.toString(), durationMs = etDuration.text.toString(),
        times = etTimes.text.toString(), waitMs = etWait.text.toString(),
        desc = etSceneName.text.toString(), region = pickedRegion,
        // v2.9.32：把六个判据输入框汇总为单一 Config，交由 Service 应用到规则。
        tuning = SceneTuning.fields().fold(SceneTuning.Config()) { acc, f ->
            acc.withRaw(f, tuningInputs[f.key]?.text?.toString() ?: "")
        },
    )

    fun currentRegion(): Region? = pickedRegion

    /** 场景名（供 Service 在标题与日志中呈现，仍落在既有 desc 字段）。 */
    fun currentName(): String = etSceneName.text.toString()

    fun applyState(next: FloatingEditorLayout.State) {
        state = next
        val collapsed = next == FloatingEditorLayout.State.COLLAPSED
        collapsedBall.visibility = if (collapsed) View.VISIBLE else View.GONE
        expanded.visibility = if (collapsed) View.GONE else View.VISIBLE
        resetDragState()
    }

    private fun resetDragState() {
        downRawX = 0f; downRawY = 0f
        startWinX = winX; startWinY = winY
        dragging = false
    }

    fun setWindowPos(x: Int, y: Int) { winX = x; winY = y }

    fun onDragCommit(beforeX: Int, beforeY: Int, afterX: Int, afterY: Int) {
        DragDiagnostics.onCommit(
            DragDiagnostics.Target.EDITOR_PANEL, beforeX, beforeY, afterX, afterY,
        )
    }

    fun measuredExpandedHeight(): Int = expanded.height.takeIf { it > 0 }
        ?: FloatingEditorLayout.maxHeightPx(cachedSh)

    fun updateMetrics(screenHPx: Int, density: Float) {
        cachedSh = screenHPx
        d = density
    }

    private inner class MaxHeightScrollView(context: Context) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val mode = when (MeasureSpec.getMode(heightSpec)) {
                MeasureSpec.UNSPECIFIED -> FloatingEditorLayout.MeasureMode.UNSPECIFIED
                MeasureSpec.EXACTLY -> FloatingEditorLayout.MeasureMode.EXACTLY
                else -> FloatingEditorLayout.MeasureMode.AT_MOST
            }
            val (m, size) = FloatingEditorLayout.measureHeight(
                mode, MeasureSpec.getSize(heightSpec), FloatingEditorLayout.maxHeightPx(cachedSh),
            )
            val androidMode = when (m) {
                FloatingEditorLayout.MeasureMode.EXACTLY -> MeasureSpec.EXACTLY
                FloatingEditorLayout.MeasureMode.AT_MOST -> MeasureSpec.AT_MOST
                FloatingEditorLayout.MeasureMode.UNSPECIFIED -> MeasureSpec.UNSPECIFIED
            }
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(size, androidMode))
        }
    }
}

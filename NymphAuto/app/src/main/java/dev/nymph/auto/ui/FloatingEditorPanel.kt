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
import dev.nymph.auto.core.editor.FloatingEditorLayout
import dev.nymph.auto.core.editor.FloatingEditorTheme
import dev.nymph.auto.core.model.Region

/**
 * 系统级悬浮动作编辑器的**紧凑面板**（v2.8.0 引入，v2.8.1 抗抽搐 + 视觉重做）。
 *
 * ## 为什么是纯 View 而不是 Compose
 *
 * 差评指「编辑动作」的整个界面不支持脱离 NymphAuto 在其他 APK 界面运行。
 * 根因是 v2.7.1 的 `ActionEditDialog` 是 Compose `AlertDialog`，画在
 * `MainActivity.setContent{}` 的视图树里 —— **Activity 生命周期绑定**。
 * 本面板由 `FloatingEditorService` 以 `TYPE_APPLICATION_OVERLAY` 添加（真系统
 * 悬浮窗）+ 前台服务保活，因此可跨 APP 显示、后台可呼出。纯 View 不依赖
 * `LifecycleOwner` / Compose 宿主，挂在 Service 窗口上更稳。
 *
 * ## v2.8.1 修「一直在抽搐」
 *
 * 录屏逐帧可见面板边缘跳动、内容忽多忽少。根因是窗口被高频、反复地重新测量：
 *  1. 拖动用「局部坐标增量 `e.x-lastX`」累加，窗口一 relayout 局部坐标就漂；
 *     → 改为 **屏幕绝对坐标** `rawX/rawY` + 按下时窗口坐标，不累加、无漂移。
 *  2. 每个 MOVE 都 `updateViewLayout` → 改为 Service 侧 **Choreographer 按帧合并**
 *     + `shouldCommit` 阈值，一帧最多一次、小于 1px 不提交。
 *  3. `MaxHeightScrollView` 对 `UNSPECIFIED` 取 `getSize()=0` 压成 `AT_MOST(0)`
 *     → 交给 core `measureHeight` 按 mode 正确处理。
 *  4. `setCollapsed` 来回切 `FLAG_NOT_FOCUSABLE` 导致软键盘弹出/收起循环
 *     → flags **恒定** `NOT_FOCUSABLE or NOT_TOUCH_MODAL`，只在用户主动点输入框时
 *     由 Service 临时清 `NOT_FOCUSABLE`，失焦即恢复。
 *  5. 状态文本空/一行/两行会让 `WRAP_CONTENT` 窗口高度变 → 状态行固定行数
 *     （v2.9.8 由两行收为一行，仍恒定）。
 *
 * ## v2.9.8 修「悬浮窗还是这么大 / 收起球拖不动」
 *
 *  - 面板宽 220→168dp、高度上限 0.54→0.40、卡片底 alpha 0xD9→0xB0；
 *  - 4 个动作 chips 由 2×2 改单行、导航按钮收紧文案与内边距（真机截图里
 *    它们已被挤成竖排多行），ROI / 滑动终点 / 高级数值字段收进默认隐藏的
 *    「高级」区 —— **控件与功能一个不删**，选「滑动」自动展开高级区；
 *  - 折叠球按住放大给出可拖反馈；拖动启动阈值改用 core 的 4dp（见
 *    [FloatingEditorLayout.dragStartPx]），Service 侧改同步直提交，详见
 *    `FloatingEditorService.onDragAbsolute`。
 *
 * ## v2.8.1 修「界面不美观」
 *
 * 颜色 / 圆角 / 间距 / 字号全部取自纯 JVM [FloatingEditorTheme]，不再散落硬编码：
 * 深墨蓝玻璃卡片、单一翡翠绿强调、次级按钮改描边而非实心灰块、胶囊 chips、
 * 自绘矢量图标（杜绝 `⠿` / `▲` 缺字方块）、分区标题带竖条、输入框聚焦描边。
 */
@SuppressLint("ViewConstructor")
class FloatingEditorPanel(context: Context) : LinearLayout(context) {

    /** 面板对外的动作（由 Service 接线到脚本读写 / 拾取器 / 关闭）。 */
    var onSave: ((FloatingEditorLayout.Form) -> Unit)? = null
    var onPickPoint: ((target: Int) -> Unit)? = null   // 0=起点，1=终点
    var onPickRegion: (() -> Unit)? = null
    var onApplyRoi: (() -> Int)? = null
    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onAdd: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onCollapse: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null

    /**
     * v2.8.1：屏幕绝对坐标拖动。参数是「按下时窗口位置 + 按下/当前手指屏幕坐标」，
     * Service 用 `FloatingEditorLayout.dragAbsolute` 算新位置并按帧节流提交。
     */
    var onDragAbsolute: ((startWinX: Int, startWinY: Int, downRawX: Float, downRawY: Float,
                          rawX: Float, rawY: Float) -> Unit)? = null

    /** 用户主动点输入框：Service 临时清 NOT_FOCUSABLE 让窗口可聚焦。 */
    var onRequestInputFocus: (() -> Unit)? = null
    /** 输入框失焦：Service 恢复 NOT_FOCUSABLE，杜绝软键盘反复弹收。 */
    var onReleaseInputFocus: (() -> Unit)? = null

    var state: FloatingEditorLayout.State = FloatingEditorLayout.State.EXPANDED
        private set

    private var d = resources.displayMetrics.density
    private var cachedSh = resources.displayMetrics.heightPixels
    private val collapsedBall: View
    private val expanded: LinearLayout
    private val tvTitle: TextView
    private val tvStep: TextView
    private val tvStatus: TextView
    private val chips = mutableListOf<TextView>()
    private val etX: EditText
    private val etY: EditText
    private val etX2: EditText
    private val etY2: EditText
    private val swipeRow: LinearLayout
    private val etHold: EditText
    private val etDuration: EditText
    private val etTimes: EditText
    private val etWait: EditText
    private val etDesc: EditText
    private val moreBox: LinearLayout
    private val btnMore: TextView
    private var moreOpen = false
    private var kind = ActionEditGuard.KIND_TAP
    private var pickedRegion: Region? = null

    /** 当前窗口左上角（由 Service 每次提交后同步），拖动起点用。 */
    private var winX = 0
    private var winY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var startWinX = 0
    private var startWinY = 0
    private var dragging = false

    /**
     * v2.9.7：系统标准 touch slop，仅作**点击**上界。
     * v2.9.8：拖动启动改用更小的 `FloatingEditorLayout.dragStartPx(d)`（4dp），
     * 否则 40dp 小球上轻移 10~20px 完全不启动拖动、抬手反被判成点击展开，
     * 真机体感就是「悬浮球拖不动、一碰就变大」。
     */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(FloatingEditorTheme.RADIUS_CARD_DP).toFloat()
            setColor(FloatingEditorTheme.BG)
            setStroke(strokeW(), FloatingEditorTheme.STROKE)
        }

        // ---------------------------------------------------------- 折叠小球
        // 双层圆：实心翡翠 + 亮环 + 中心白点，比 v2.8.0 的纯色圆更易辨识
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
            contentDescription = "展开悬浮编辑器（按住可拖动）"
            visibility = View.GONE
            // 拖动统一在根视图处理（onInterceptTouchEvent / onTouchEvent），见下方
        }
        addView(collapsedBall, LayoutParams(
            dp(FloatingEditorLayout.COLLAPSED_DP), dp(FloatingEditorLayout.COLLAPSED_DP),
        ))

        // ---------------------------------------------------------- 展开面板
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

        // 顶部拖动条：强调色圆点 + 标题 + 自绘「收起 / 关闭」图标
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(FloatingEditorTheme.BAR_H_DP)
            setPadding(dp(3), 0, dp(2), 0)
            // 拖动统一在根视图处理（顶部条不再单独挂监听器，避免 ScrollView 吞手势）
        }
        bar.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FloatingEditorTheme.ACCENT)
            }
        }, LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) })
        tvTitle = TextView(context).apply {
            text = "悬浮编辑"
            textSize = FloatingEditorTheme.TEXT_TITLE_SP
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(FloatingEditorTheme.TEXT)
        }
        bar.addView(tvTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(glyphBtn(Glyph.MINUS) { onCollapse?.invoke() })
        bar.addView(glyphBtn(Glyph.CLOSE) { onClose?.invoke() })
        body.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 细分隔线
        body.addView(View(context).apply {
            background = GradientDrawable().apply { setColor(FloatingEditorTheme.STROKE) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, strokeW()).apply {
            topMargin = dp(2); bottomMargin = dp(4)
        })

        tvStep = TextView(context).apply {
            text = "第 1 / 1 步"
            textSize = FloatingEditorTheme.TEXT_LABEL_SP
            setTextColor(FloatingEditorTheme.OK)
            // v2.9.8：Service 会传入「第 x / y 步 · 类型」，168dp 下必须单行省略，
            // 否则长文案折成两行又把面板撑高。
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(4))
        }
        body.addView(tvStep)

        // 上一步 / 下一步 / 新增（描边按钮，不再是灰实心块）
        val nav = LinearLayout(context).apply { orientation = HORIZONTAL }
        // v2.9.8：168dp 宽下「上一步 / 下一步 / ＋ 新增」会被挤成竖排多行
        // （真机截图可见），缩短文案 + 收紧内边距与字号，保持单行。
        val navItems = listOf<Pair<String, () -> Unit>>(
            "上一" to { onPrev?.invoke() },
            "下一" to { onNext?.invoke() },
            "＋新增" to { onAdd?.invoke() },
        )
        navItems.forEach { (label, act) ->
            nav.addView(outlinedBtn(label) { act() }.apply {
                textSize = FloatingEditorTheme.TEXT_LABEL_SP
                setPadding(dp(2), dp(FloatingEditorTheme.PAD_CONTROL_V_DP),
                    dp(2), dp(FloatingEditorTheme.PAD_CONTROL_V_DP))
            }, chipItem())
        }
        body.addView(nav, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // v2.9.8：4 个动作 chips 由 2×2 改单行，省掉一整行高度。168dp 面板里
        // 每个 chip 只有约 35dp，故同步收紧水平内边距与字号，保证「点击 / 长按 /
        // 双击 / 滑动」不折行（真机截图里 220dp 时按钮都已被挤成竖排）。
        val kinds = listOf("点击", "长按", "双击", "滑动")
        val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        kinds.forEachIndexed { i, label ->
            val b = outlinedBtn(label) { selectKind(i) }.apply {
                textSize = FloatingEditorTheme.TEXT_LABEL_SP
                setPadding(dp(2), dp(FloatingEditorTheme.PAD_CONTROL_V_DP),
                    dp(2), dp(FloatingEditorTheme.PAD_CONTROL_V_DP))
            }
            chips.add(b)
            chipRow.addView(b, chipItem())
        }
        body.addView(chipRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 坐标 X / Y（核心区：默认可见）
        body.addView(sectionLabel("坐标（屏幕百分比）"))
        val xy = LinearLayout(context).apply { orientation = HORIZONTAL }
        etX = numberField("X %"); etY = numberField("Y %")
        xy.addView(etX, fieldItem())
        xy.addView(etY, fieldItem())
        body.addView(xy, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(outlinedBtn("屏幕拾取起点") { onPickPoint?.invoke(0) }, wideItem())

        // v2.9.8「高级」区：ROI / 滑动终点 / 更多数值字段默认收起，把展开态
        // 从近半屏大卡片压成紧凑工具卡。**按钮一个没删**，点开全在。
        btnMore = outlinedBtn("高级 / ROI ⌄") { setAdvancedOpen(!moreOpen) }
        body.addView(btnMore, wideItem())
        moreBox = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        moreBox.addView(outlinedBtn("框选 ROI 区域") { onPickRegion?.invoke() }, wideItem())
        moreBox.addView(outlinedBtn("应用 ROI 到脚本") {
            val hits = onApplyRoi?.invoke() ?: 0
            setStatus(if (hits > 0) "✓ 已应用到脚本内 $hits 处" else "脚本内暂无找图 / 找色目标")
        }, wideItem())

        // 滑动终点（仅滑动时显示）；选「滑动」会自动展开高级区，避免字段不可见
        swipeRow = LinearLayout(context).apply { orientation = VERTICAL }
        swipeRow.addView(sectionLabel("滑动终点 / 时长"))
        val x2y2 = LinearLayout(context).apply { orientation = HORIZONTAL }
        etX2 = numberField("终点 X %"); etY2 = numberField("终点 Y %")
        x2y2.addView(etX2, fieldItem())
        x2y2.addView(etY2, fieldItem())
        swipeRow.addView(x2y2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        swipeRow.addView(outlinedBtn("屏幕拾取终点") { onPickPoint?.invoke(1) }, wideItem())
        etDuration = numberField("滑动时长 ms")
        swipeRow.addView(etDuration, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        moreBox.addView(swipeRow)

        etHold = numberField("按下时间 ms")
        etTimes = numberField("重复次数")
        etWait = numberField("执行前等待 ms")
        etDesc = EditText(context).apply {
            hint = "动作描述（可选）"
            textSize = FloatingEditorTheme.TEXT_BODY_SP
            setTextColor(FloatingEditorTheme.TEXT)
            setHintTextColor(FloatingEditorTheme.TEXT_MUTED)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            minHeight = dp(FloatingEditorTheme.FIELD_MIN_H_DP)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = fieldBg(false)
            setOnClickListener { requestInputFocus(this) }
            setOnFocusChangeListener { _, hasFocus ->
                background = fieldBg(hasFocus)
                if (!hasFocus) onReleaseInputFocus?.invoke()
            }
        }
        moreBox.addView(sectionLabel("按下时间 / 次数 / 等待"))
        moreBox.addView(etHold, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        moreBox.addView(etTimes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        moreBox.addView(etWait, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        moreBox.addView(etDesc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(moreBox)

        // v2.9.8：状态行由固定两行改固定一行（窗口高度仍恒定，不随文本行数跳变），
        // 再省下约 18dp 的展开态高度。
        tvStatus = TextView(context).apply {
            textSize = FloatingEditorTheme.TEXT_HINT_SP
            setTextColor(FloatingEditorTheme.TEXT_MUTED)
            setLines(1)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), 0, dp(2))
        }
        body.addView(tvStatus)

        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.addView(outlinedBtn("取消") { onClose?.invoke() }, navItem())
        val save = outlinedBtn("保存") { onSave?.invoke(collect()) }.apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(FloatingEditorTheme.RADIUS_CONTROL_DP).toFloat()
                setColor(FloatingEditorTheme.ACCENT)
            }
            setTextColor(0xFF052E24.toInt())
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        actions.addView(save, navItem())
        body.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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

    /** v2.9.8：紧凑等分列（间距仅 2dp），给 168dp 面板里的单行 chips / 导航留足文字宽度。 */
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

    private fun outlinedBtn(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = FloatingEditorTheme.TEXT_BODY_SP
        setTextColor(FloatingEditorTheme.TEXT_DIM)
        gravity = Gravity.CENTER
        isClickable = true
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

    private fun numberField(hint: String): EditText = EditText(context).apply {
        this.hint = hint
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
            if (!hasFocus) onReleaseInputFocus?.invoke()
        }
    }

    /** 先让 Service 清 NOT_FOCUSABLE，下一帧再申请焦点 / 弹软键盘。 */
    private fun requestInputFocus(et: EditText) {
        onRequestInputFocus?.invoke()
        et.post {
            et.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
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

    private enum class Glyph { MINUS, CLOSE }

    /** 自绘矢量图标：圆形底 + 细线，杜绝字符缺字方块。 */
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

    private fun selectKind(k: Int) {
        kind = k
        chips.forEachIndexed { i, b -> paintChip(b, i == k) }
        swipeRow.visibility = if (k == ActionEditGuard.KIND_SWIPE) View.VISIBLE else View.GONE
        // v2.9.8：滑动终点收进高级区后，选「滑动」必须自动展开，否则用户
        // 看不到终点坐标 / 时长输入（功能没删，但不能让它藏起来）。
        if (k == ActionEditGuard.KIND_SWIPE && !moreOpen) setAdvancedOpen(true)
    }

    /** v2.9.8：高级区开合的单一入口（按钮与 [selectKind] 共用，状态不会分叉）。 */
    private fun setAdvancedOpen(open: Boolean) {
        moreOpen = open
        moreBox.visibility = if (open) View.VISIBLE else View.GONE
        btnMore.text = if (open) "收起高级 ⌃" else "高级 / ROI ⌄"
    }

    /** 用 core 的表单填入全部字段（保真 + Locale 安全）。 */
    fun bind(form: FloatingEditorLayout.Form, stepText: String, supported: Boolean) {
        kind = form.kind
        selectKind(form.kind)
        etX.setText(form.x); etY.setText(form.y)
        etX2.setText(form.x2); etY2.setText(form.y2)
        etHold.setText(form.holdMs); etDuration.setText(form.durationMs)
        etTimes.setText(form.times); etWait.setText(form.waitMs)
        etDesc.setText(form.desc)
        pickedRegion = form.region
        tvStep.text = stepText
        setStatus(if (supported) "" else "该动作类型不在简易编辑范围，保存不会改动它")
    }

    /** 回填拾取到的点（Locale.US，4 位小数）。 */
    fun setPoint(target: Int, xPct: Double, yPct: Double) {
        val sx = FloatingEditorLayout.fmt(xPct)
        val sy = FloatingEditorLayout.fmt(yPct)
        if (target == 1) { etX2.setText(sx); etY2.setText(sy) }
        else { etX.setText(sx); etY.setText(sy) }
        setStatus("✓ 已拾取坐标 $sx , $sy %")
    }

    fun setRegion(region: Region) {
        pickedRegion = region
        setStatus("✓ 已框选 ${FloatingEditorLayout.fmt(region.leftPct)},${FloatingEditorLayout.fmt(region.topPct)}" +
            " ~ ${FloatingEditorLayout.fmt(region.rightPct)},${FloatingEditorLayout.fmt(region.bottomPct)} %")
    }

    fun setStatus(text: String) {
        tvStatus.text = text
        tvStatus.setTextColor(when {
            text.startsWith("✓") -> FloatingEditorTheme.OK
            text.contains("暂无") || text.contains("失败") || text.contains("不在") -> FloatingEditorTheme.WARN
            else -> FloatingEditorTheme.TEXT_MUTED
        })
    }

    /** 收集当前表单（字符串原样交给 core 解析）。 */
    fun collect(): FloatingEditorLayout.Form = FloatingEditorLayout.Form(
        kind = kind,
        x = etX.text.toString(), y = etY.text.toString(),
        x2 = etX2.text.toString(), y2 = etY2.text.toString(),
        holdMs = etHold.text.toString(), durationMs = etDuration.text.toString(),
        times = etTimes.text.toString(), waitMs = etWait.text.toString(),
        desc = etDesc.text.toString(), region = pickedRegion,
    )

    fun currentRegion(): Region? = pickedRegion

    /** 折叠/展开切换（由 Service 调，Service 同时改窗口尺寸）。 */
    fun applyState(next: FloatingEditorLayout.State) {
        state = next
        val collapsed = next == FloatingEditorLayout.State.COLLAPSED
        collapsedBall.visibility = if (collapsed) View.VISIBLE else View.GONE
        expanded.visibility = if (collapsed) View.GONE else View.VISIBLE
        // v2.9.7：downRawX/Y、dragging 是面板级共享字段（折叠球与顶部条共用）。
        // 展开态拖动后直接收起，下一次触摸会带着上一次手势的残留状态，
        // 表现为收起球「第一下拖不动」。切换形态时强制复位。
        resetDragState()
    }

    /** v2.9.7：手势状态复位（折叠/展开切换时调用）。 */
    private fun resetDragState() {
        downRawX = 0f; downRawY = 0f
        startWinX = winX; startWinY = winY
        dragging = false
    }

    /** Service 每次提交窗口位置后同步，作为下一次拖动起点。 */
    fun setWindowPos(x: Int, y: Int) { winX = x; winY = y }

    /** 实测展开面板高度，供 Service 做 clamp（避免用「假设 62% 屏高」）。 */
    fun measuredExpandedHeight(): Int = expanded.height.takeIf { it > 0 }
        ?: FloatingEditorLayout.maxHeightPx(cachedSh)

    /** 屏幕配置变化时由 Service 刷新缓存，拖动/测量不再每次重读 displayMetrics。 */
    fun updateMetrics(screenHPx: Int, density: Float) {
        cachedSh = screenHPx
        d = density
    }

    // ------------------------------------------------------------ 拖动（根视图统一处理）

    /**
     * 整块面板（含折叠小球）的拖动**统一在根视图**处理，采用本工程已验证的
     * `RecordPanel` idiom（`onInterceptTouchEvent` 拦截 + `onTouchEvent` 执行）：
     *
     * - DOWN 一律 `return false` 放行给子控件（按钮 / 输入框 / 拾取按钮照常点击）；
     * - MOVE 一旦位移超过 `dragStartPx`(4dp) 即把 `dragging` 置真并 `return dragging`
     *   拦截手势；此后 MOVE/UP 由本视图的 `onTouchEvent` 接管，窗口随手指移动；
     * - 根层级拦截位于 `MaxHeightScrollView` **之上**，滚动容器永远抢不到拖动
     *   手势——这正是 v2.9.8 及以前「拖动条被 ScrollView 吃掉、面板几乎不动」
     *   的真因：旧 `attachDrag` 把监听器挂在嵌在 ScrollView 内的顶部条上，
     *   竖/斜向 MOVE 刚过阈值就被 ScrollView 的 `onInterceptTouchEvent` 截走，
     *   列表项只收到 DOWN + CANCEL，拖动监听器拿不到 MOVE，面板起手抖一下就停。
     *
     * v2.9.4 教训：DOWN 若不被消费，视图不会成为 touch target，后续 MOVE 根本
     * 不派发——所以 `onTouchEvent.ACTION_DOWN` 必须 `return true`。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            downRawX = e.rawX; downRawY = e.rawY
            startWinX = winX; startWinY = winY
            dragging = false
            false // 让按钮 / 输入框等子 View 正常接收按下
        }
        MotionEvent.ACTION_MOVE -> {
            val dx = e.rawX - downRawX
            val dy = e.rawY - downRawY
            // 4dp 阈值启动拖动；一旦进入拖动即拦截，后续 MOVE 交给本视图
            if (FloatingEditorLayout.isDragMove(dx, dy, FloatingEditorLayout.dragStartPx(d))) {
                dragging = true
            }
            dragging
        }
        else -> false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            true // 接住空白区域按下，后续 MOVE 才会派发到本面板（v2.9.4 教训）
        }
        MotionEvent.ACTION_MOVE -> {
            if (dragging) {
                // 绝对坐标纯函数同步直提交（v2.9.8 起，无 Choreographer 自锁）
                onDragAbsolute?.invoke(startWinX, startWinY, downRawX, downRawY, e.rawX, e.rawY)
            }
            true // 已消费 DOWN，MOVE 必须持续消费以保持手势
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            val dx = e.rawX - downRawX
            val dy = e.rawY - downRawY
            // 收起态轻点小球 = 展开；展开态点空白不做事（与旧行为一致）
            val tap = !dragging && FloatingEditorLayout.isTap(false, dx, dy, touchSlop)
            if (tap && state == FloatingEditorLayout.State.COLLAPSED) onToggle?.invoke()
            dragging = false
            true // 拖动过则消费抬起，避免误触发按钮
        }
        else -> false
    }


    /** 高度上限取 core `measureHeight`，正确修掉 UNSPECIFIED 被压成 0 的问题。 */
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

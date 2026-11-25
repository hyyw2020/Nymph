package dev.nymph.auto.core.editor

/**
 * 数值字段的步进规则：把「按下时间 / 重复次数 / 执行前等待 / 滑动时长」从
 * **键盘输入**改为**加减步进**，键盘只作为兜底。
 *
 * 为什么要有这一层：
 * 悬浮窗里的 `EditText` 每次取值都要经历「位级清 NOT_FOCUSABLE → 请求焦点 →
 * 唤起输入法 → 收键盘 → 归还焦点」五步，任何一步在 OEM ROM 上不配合，用户就会
 * 卡在「点不动输入框」。而按下时间、重复次数、等待这几个值，绝大多数场合只是
 * 在少数几个整数之间来回调——用步进器一次点击即可，不需要键盘参与。
 *
 * 本类是纯函数，零 Android 依赖，可被 JVM 单测逐条锁定：
 * - 空值 / 非法输入不产生 `0`、不产生负值，而是回到该字段的默认值再步进；
 * - 步进结果一律夹在 `[min, max]` 内，越界不溢出、不回绕；
 * - 输出字符串去尾零，`100.0` 呈现为 `100`，`1.5` 仍是 `1.5`；
 * - 「选填」语义由 [hintOf] 承担，用户不需要读文档就知道留空可用。
 *
 * 字段 [key] 与 `FloatingEditorLayout.Form` 的字段名一一对应，由
 * `StepPolicyTest` 锁定，避免两侧改名后静默失配。
 */
object StepPolicy {

    /** 一个可步进的数值字段。 */
    data class Field(
        /** 与 `FloatingEditorLayout.Form` 同名，用于跨层对齐校验。 */
        val key: String,
        val label: String,
        /**
         * 单行步进器里用的短标签（≤2 字）。
         *
         * 面板宽 168dp，一行要放下「短标签 + 减 + 值 + 加」，四个控件抢同一行，
         * 用「按下时间」这种四字标签会把值挤到看不见。短标签不是缩写，
         * 是同一概念在窄容器里的呈现形式；完整名称仍由 [label] 承担。
         */
        val short: String,
        /** 留空时使用的默认值。 */
        val def: Double,
        /** 每次步进的变化量。 */
        val step: Double,
        val min: Double,
        val max: Double,
        val unit: String,
        /** 是否「选填」：留空有可解释的默认行为，不等于缺失。 */
        val optional: Boolean,
        /** 选填字段的留空含义；必填字段为空串。 */
        val emptyMeans: String = "",
    )

    val HOLD = Field(
        key = "holdMs", label = "按下时间", short = "按下", def = 100.0, step = 50.0,
        min = 0.0, max = 10_000.0, unit = "ms", optional = true, emptyMeans = "轻点",
    )
    val TIMES = Field(
        key = "times", label = "重复次数", short = "次数", def = 1.0, step = 1.0,
        min = 1.0, max = 999.0, unit = "次", optional = true, emptyMeans = "1 次",
    )
    val WAIT = Field(
        key = "waitMs", label = "执行前等待", short = "等待", def = 0.0, step = 100.0,
        min = 0.0, max = 600_000.0, unit = "ms", optional = true, emptyMeans = "不等待",
    )
    val DURATION = Field(
        key = "durationMs", label = "滑动时长", short = "滑动", def = 400.0, step = 50.0,
        min = 50.0, max = 10_000.0, unit = "ms", optional = false,
    )

    /** 全部可步进字段，顺序即面板呈现顺序。 */
    fun fields(): List<Field> = listOf(HOLD, TIMES, WAIT, DURATION)

    fun field(key: String): Field? = fields().firstOrNull { it.key == key }

    /** 夹取到字段边界；非有限值回落到默认值。 */
    fun coerce(f: Field, raw: Double): Double {
        if (!raw.isFinite()) return f.def
        return raw.coerceIn(f.min, f.max)
    }

    /**
     * 步进一次。
     *
     * @param raw 输入框当前文本（可能是空串、空格、带单位、非法字符）。
     * @param dir `+1` 加一档，`-1` 减一档。
     * @return 夹取后的新值；永远落在 `[min, max]` 内。
     */
    fun bump(f: Field, raw: String, dir: Int): Double {
        val parsed = raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        if (parsed == null) {
            // 留空时两个方向要有各自确定的含义，而不是「默认值走一档」：
            // 加号给「默认值 + 一档」（启用并略增），减号直接落到下限——
            // 空值没有可减的基数，落到下限（多数是 0，次数是 1）才有可见反馈。
            return if (dir > 0) coerce(f, f.def + f.step) else f.min
        }
        return coerce(f, coerce(f, parsed) + f.step * dir)
    }

    /**
     * 把数值写回输入框的文本：整数不带小数点，非整数保留一位小数。
     *
     * 不在这里做本地化：`1000` 在任何 locale 下都不该出现千分位分隔符，
     * 否则回读 `toDoubleOrNull()` 会失败。
     */
    fun text(f: Field, v: Double): String {
        val c = coerce(f, v)
        val rounded = Math.round(c).toDouble()
        return if (Math.abs(c - rounded) < 1e-9) rounded.toLong().toString() else c.toString()
    }

    /** 输入框为空时呈现的占位文本。 */
    fun placeholder(f: Field): String = if (f.optional) "选填" else f.label

    /**
     * 就地说明：一句话讲清「留空会怎样」，取代需要翻文档才知道能不能跳过。
     * 必填字段也要给出可执行的具体含义。
     */
    fun hintOf(f: Field): String = when {
        f.optional && f.emptyMeans.isNotEmpty() -> "选填 · 留空=${f.emptyMeans}"
        f.optional -> "选填"
        else -> "${f.label}（${f.unit}）"
    }

    /** 该字段的值在摘要里的呈现，如 `100ms` / `3次`；空值返回空串。 */
    fun summaryOf(f: Field, raw: String): String {
        val parsed = raw.trim().toDoubleOrNull() ?: return ""
        if (!parsed.isFinite()) return ""
        return text(f, parsed) + f.unit
    }
}

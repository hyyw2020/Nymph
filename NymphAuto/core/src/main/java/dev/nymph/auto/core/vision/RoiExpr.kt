package dev.nymph.auto.core.vision

import kotlin.math.max
import kotlin.math.min

/**
 * ROI 表达式解析（v2.7.0，纯 JVM 可测）。
 *
 * ## 为什么存在
 *
 * v2.6.0 的 [dev.nymph.auto.core.model.Region] 只能是四个写死的百分比数字，
 * 无法表达「右半屏上方 1/3」「以变量 w 为基准」「模板周围 ±10%」这类需求。
 * 借鉴 BetterGI `Recognition.json` 的 ROI 表达式思路，但**不引入脚本引擎**：
 * 只做一个极小的四则运算解析器（+ - * / 括号 + min/max + 变量），
 * 零依赖、零反射、零正则回溯风险，core 单测可直接覆盖。
 *
 * ## 支持语法
 *
 * - 命名别名：`full` `top` `bottom` `left` `right` `center`
 *   `topLeft` `topRight` `bottomLeft` `bottomRight` `topHalf` `bottomHalf`
 * - 四元组：`"10,20,90,80"` / `"10 20 90 80"` / `"l=10,t=20,r=90,b=80"`
 * - 表达式：`"0,0,w*0.3,h"`、`"cx-10,cy-10,cx+10,b"`，变量 `w h cx cy r`
 *   以及调用方传入的任意变量（如脚本变量值）。
 *
 * 所有结果钳制到 0..100 并自动纠正左右/上下倒置；非法输入返回 null
 * （调用方应退回脚本自带 Region，绝不因表达式写错就让整轮回放崩掉）。
 */
object RoiExpr {

    /** 内置别名（百分比 l,t,r,b）。 */
    private val ALIASES: Map<String, ImageFinder.RegionPct> = mapOf(
        "full" to r(0, 0, 100, 100),
        "top" to r(0, 0, 100, 33),
        "bottom" to r(0, 67, 100, 100),
        "left" to r(0, 0, 33, 100),
        "right" to r(67, 0, 100, 100),
        "center" to r(25, 25, 75, 75),
        "topleft" to r(0, 0, 50, 50),
        "topright" to r(50, 0, 100, 50),
        "bottomleft" to r(0, 50, 50, 100),
        "bottomright" to r(50, 50, 100, 100),
        "tophalf" to r(0, 0, 100, 50),
        "bottomhalf" to r(0, 50, 100, 100),
    )

    private fun r(l: Number, t: Number, ri: Number, b: Number) =
        ImageFinder.RegionPct(l.toDouble(), t.toDouble(), ri.toDouble(), b.toDouble())

    /** 别名清单（供 UI 下拉提示）。 */
    fun aliases(): Set<String> = ALIASES.keys

    /**
     * 解析 ROI 表达式。返回 null = 语法错误/段数不对/数值非法。
     * 默认变量：w=h=100（百分比基准）、cx=cy=50、r=10。
     */
    fun resolve(spec: String, vars: Map<String, Double> = emptyMap()): ImageFinder.RegionPct? {
        val s = spec.trim()
        if (s.isEmpty()) return ImageFinder.RegionPct()
        // 别名匹配对大小写/下划线/连字符/空格不敏感：bottomRight / BOTTOM_RIGHT / bottom-right 同义
        val aliasKey = s.lowercase().filter { it.isLetterOrDigit() }
        ALIASES[aliasKey]?.let { return it }

        val parts = splitParts(s)
        if (parts.size != 4) return null
        val ctx = HashMap<String, Double>(vars.size + 5).apply {
            putAll(vars)
            putIfAbsent("w", 100.0)
            putIfAbsent("h", 100.0)
            putIfAbsent("cx", 50.0)
            putIfAbsent("cy", 50.0)
            putIfAbsent("r", 10.0)
        }
        val v = DoubleArray(4)
        for (i in 0..3) {
            val value = Expr(parts[i], ctx).parse() ?: return null
            if (!value.isFinite()) return null
            v[i] = value
        }
        var l = v[0].coerceIn(0.0, 100.0)
        var t = v[1].coerceIn(0.0, 100.0)
        var ri = v[2].coerceIn(0.0, 100.0)
        var b = v[3].coerceIn(0.0, 100.0)
        if (ri < l) { val tmp = l; l = ri; ri = tmp }
        if (b < t) { val tmp = t; t = b; b = tmp }
        // 零面积 ROI 无意义，给一个最小 1% 窗口避免 NCC 直接返回 null
        if (ri - l < 1.0) ri = min(100.0, l + 1.0)
        if (b - t < 1.0) b = min(100.0, t + 1.0)
        return ImageFinder.RegionPct(l, t, ri, b)
    }

    /**
     * v2.7.0 运行时接线：把脚本里的 [dev.nymph.auto.core.model.Region] 解析成
     * 引擎实际使用的数值区域。`expr` 为空（老脚本 / 常规手填）时原样返回，
     * 零开销、行为与 v2.6.0 完全一致；表达式非法时静默退回数值区域，
     * 绝不因用户写错表达式就中断整轮回放。
     *
     * 放在 core 层是为了让「表达式 → Region」这条链路可以被纯 JVM 单测覆盖，
     * 而不是只靠真机反馈（本次接线正是为修掉「写了没接」）。
     */
    fun resolveRegion(spec: dev.nymph.auto.core.model.Region): dev.nymph.auto.core.model.Region {
        if (spec.expr.isBlank()) return spec
        val r = resolve(spec.expr) ?: return spec
        return dev.nymph.auto.core.model.Region(r.left, r.top, r.right, r.bottom, spec.expr)
    }

    /**
     * 拆出 4 段：支持 `l=..` 前缀、逗号分隔、空白分隔。
     * 逗号分割必须**括号感知**——`min(w*0.5,40)` 里的逗号是函数参数分隔符，
     * 不能当成 ROI 四元组的段边界（否则表达式永远解析失败）。
     */
    private fun splitParts(s: String): List<String> {
        val prefixEq = Regex("""\b([lt rb]{1,2})\s*=""", RegexOption.IGNORE_CASE)
        val normalized = prefixEq.replace(s) { "" }.trim()
        val parts = ArrayList<String>(4)
        val sb = StringBuilder()
        var depth = 0
        for (c in normalized) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> {
                    parts += sb.toString().trim()
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) parts += sb.toString().trim()
        val nonEmpty = parts.filter { it.isNotEmpty() }
        if (nonEmpty.size == 4) return nonEmpty
        if (nonEmpty.size == 1) {
            val bySpace = nonEmpty[0].split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (bySpace.size == 4) return bySpace
        }
        return nonEmpty
    }

    /**
     * 极小型递归下降表达式解析：
     * expr   := term (('+' | '-') term)*
     * term   := factor (('*' | '/') factor)*
     * factor := number | ident | ident '(' expr ',' expr ')' | '(' expr ')' | '-' factor
     * 仅支持 min/max 两个双参函数，其余一律视为语法错误。
     */
    private class Expr(src: String, private val vars: Map<String, Double>) {
        private val s = src.replace(" ", "")
        private var i = 0

        fun parse(): Double? {
            val v = expr() ?: return null
            skip()
            return if (i == s.length) v else null
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun expr(): Double? {
            var acc = term() ?: return null
            while (true) {
                skip()
                if (i >= s.length) return acc
                val op = s[i]
                if (op != '+' && op != '-') return acc
                i++
                val rhs = term() ?: return null
                acc = if (op == '+') acc + rhs else acc - rhs
            }
        }

        private fun term(): Double? {
            var acc = factor() ?: return null
            while (true) {
                skip()
                if (i >= s.length) return acc
                val op = s[i]
                if (op != '*' && op != '/') return acc
                i++
                val rhs = factor() ?: return null
                if (op == '/' && rhs == 0.0) return null
                acc = if (op == '*') acc * rhs else acc / rhs
            }
        }

        private fun factor(): Double? {
            skip()
            if (i >= s.length) return null
            if (s[i] == '-') { i++; return factor()?.let { -it } }
            if (s[i] == '+') { i++; return factor() }
            if (s[i] == '(') {
                i++
                val v = expr() ?: return null
                skip()
                if (i >= s.length || s[i] != ')') return null
                i++
                return v
            }
            if (s[i].isDigit() || s[i] == '.') return number()
            if (s[i].isLetter() || s[i] == '_') return ident()
            return null
        }

        private fun number(): Double? {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDoubleOrNull()
        }

        private fun ident(): Double? {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
            val name = s.substring(st, i).removePrefix("$")
            skip()
            if (i < s.length && s[i] == '(') {
                i++
                val a = expr() ?: return null
                skip()
                if (i >= s.length || s[i] != ',') return null
                i++
                val b = expr() ?: return null
                skip()
                if (i >= s.length || s[i] != ')') return null
                i++
                return when (name.lowercase()) {
                    "min" -> min(a, b)
                    "max" -> max(a, b)
                    else -> null
                }
            }
            return vars[name.lowercase()] ?: vars[name]
        }
    }
}

// SPDX-License-Identifier: MIT
package dev.nymph.auto.visual

/**
 * 极小矢量字形集：只有数字 0-9、`/`、`×`、空格。
 *
 * 硬约束：绝不调用 Canvas.drawText。步骤角标「3/12」、多指编号「1」「×2」
 * 全部由这些归一化折线（0..1 网格）缩放绘制，分辨率无关、抗锯齿由
 * View 的 Paint 保证。用 STROKE + ROUND cap 绘制即得圆润线段数字，
 * 不需要字形图集、不需要纹理、包体增量为零。
 *
 * 纯 Kotlin，不引用任何 android.* 类，因此可在 JVM 单测里验证越界安全。
 */
object VectorGlyphs {

    // 七段坐标（0..1）。带切角的端点让斜杠/乘号与数字笔画风格统一。
    private val A = floatArrayOf(0.14f, 0.08f, 0.86f, 0.08f) // 上横
    private val G = floatArrayOf(0.14f, 0.50f, 0.86f, 0.50f) // 中横
    private val D = floatArrayOf(0.14f, 0.92f, 0.86f, 0.92f) // 下横
    private val F = floatArrayOf(0.10f, 0.12f, 0.10f, 0.46f) // 左上
    private val B = floatArrayOf(0.90f, 0.12f, 0.90f, 0.46f) // 右上
    private val E = floatArrayOf(0.10f, 0.54f, 0.10f, 0.88f) // 左下
    private val C = floatArrayOf(0.90f, 0.54f, 0.90f, 0.88f) // 右下

    private val SLASH = floatArrayOf(0.22f, 0.94f, 0.78f, 0.06f)
    private val CROSS1 = floatArrayOf(0.20f, 0.24f, 0.80f, 0.76f)
    private val CROSS2 = floatArrayOf(0.80f, 0.24f, 0.20f, 0.76f)

    private val GLYPHS: Map<Char, Array<FloatArray>> = mapOf(
        '0' to arrayOf(A, B, C, D, E, F),
        '1' to arrayOf(B, C),
        '2' to arrayOf(A, B, G, E, D),
        '3' to arrayOf(A, B, G, C, D),
        '4' to arrayOf(F, G, B, C),
        '5' to arrayOf(A, F, G, C, D),
        '6' to arrayOf(A, F, G, E, C, D),
        '7' to arrayOf(A, B, C),
        '8' to arrayOf(A, B, C, D, E, F, G),
        '9' to arrayOf(A, B, C, D, F, G),
        '/' to arrayOf(SLASH),
        '×' to arrayOf(CROSS1, CROSS2),
        'x' to arrayOf(CROSS1, CROSS2), // 兼容半角 x（×2 的容错）
        'X' to arrayOf(CROSS1, CROSS2),
        ' ' to emptyArray(),
    )

    /** 该字符是否有笔画（未知字符一律 false，静默跳过，绝不抛异常）。 */
    fun has(ch: Char): Boolean = GLYPHS.containsKey(ch)

    /**
     * 取字符的归一化折线集合；未知字符返回空数组。
     * 每条 FloatArray 为一条独立折线的点序列 [x0,y0,x1,y1,...]。
     */
    fun strokes(ch: Char): Array<FloatArray> = GLYPHS[ch] ?: emptyArray()

    /** 字符前进宽度（网格单位），用于逐字排版。未知字符给一个稳定默认值。 */
    fun advance(ch: Char): Float = when (ch) {
        ' ' -> 0.45f
        '/', '×', 'x', 'X' -> 1.0f
        else -> 1.05f
    }

    /** 整串文本的归一化宽度（不含行高），空串返回 0，未知字符按默认宽度计。 */
    fun textWidth(text: String): Float {
        if (text.isEmpty()) return 0f
        var w = 0f
        for (ch in text) w += advance(ch)
        return w
    }
}

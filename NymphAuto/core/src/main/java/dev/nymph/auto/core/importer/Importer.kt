package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Script
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 多方言脚本导入器路由。
 *
 * 支持：
 *  1. 原生 NymphAuto JSON
 *  2. 自动精灵（zdjl）JSON 方言      —— 见 [ZdjlImporter]
 *  3. scriptBean 方言（另一类点击器）
 *  4. 行分隔 JSON 方言（含截断修复）  —— 见 [LineImporter]
 *
 * 设计原则（对齐自动精灵导入行为）：
 *  - 宽容解析：缺字段补默认值，不识别的步骤跳过并计数
 *  - 版本协商：脚本 minVerCode 高于当前版本 → 拒绝并说明
 *  - 明确报错：绝不静默返回 null，err 给出人类可读原因
 */
object Importer {

    data class Result(val script: Script, val note: String)

    sealed class Fail(val reason: String) {
        class Empty : Fail("脚本内容为空")
        class UnknownFormat(detail: String) : Fail("无法识别的脚本格式：$detail")
        class VersionTooOld(minVer: Int, curVer: Int) :
            Fail("脚本需要软件版本 >= $minVer，当前为 $curVer，请升级")
    }

    /** @return (成功结果, 失败原因)；二者必有其一非空。 */
    fun import(raw: String, curVerCode: Int = Script.SCHEMA_VERSION): Pair<Result?, Fail?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null to Fail.Empty()

        // ① 原生
        runCatching { ScriptCodec.decode(trimmed) }.getOrNull()?.let { s ->
            if (s.actions.isNotEmpty()) {
                s.minVerCode.takeIf { it > curVerCode }?.let {
                    return null to Fail.VersionTooOld(it, curVerCode)
                }
                return Result(s, "原生脚本") to null
            }
        }

        // ② 自动精灵方言（版本协商用 zdjl 兼容基线，与原生 schema 版本无关）
        //    覆盖两种封装：单 JSON 整体导出 / 行分隔 .zjs（首行元数据 + 每行一个动作树）
        val looksLikeZdjl = trimmed.contains("\"actionList\"") || trimmed.contains("\"scriptBean\"") ||
            trimmed.contains("\"actions\"") || trimmed.contains("\"action\"") ||
            trimmed.contains("\"scriptSet\"") || trimmed.contains("\"minVerCode\"") ||
            trimmed.contains("\"posData\"")
        if (looksLikeZdjl) {
            probeMinVerCode(trimmed)?.let { minVer ->
                if (minVer > ZdjlImporter.COMPAT_VERSION_CODE) {
                    return null to Fail.VersionTooOld(minVer, ZdjlImporter.COMPAT_VERSION_CODE)
                }
            }
            ZdjlImporter.import(trimmed, ZdjlImporter.COMPAT_VERSION_CODE)?.let { return it to null }
        }

        // ③ scriptBean
        if (trimmed.contains("\"scriptBean\"")) {
            ScriptBeanImporter.import(trimmed, curVerCode)?.let { return it to null }
        }

        // ④ 行分隔方言
        val line = LineImporter.import(trimmed, curVerCode)
        if (line != null) return line to null

        return null to Fail.UnknownFormat("不是有效的 JSON 脚本，也不是行分隔脚本")
    }

    /** 便捷签名的语法糖：成功返回 Result，失败返回 null（reason 见 [import]）。 */
    fun importOrNull(raw: String, curVerCode: Int = Script.SCHEMA_VERSION): Result? =
        import(raw, curVerCode).first

    /** 探测脚本声明的最低软件版本（zdjl 方言）。整体 JSON 失败时按行分隔取首行。解析失败返回 null。 */
    private fun probeMinVerCode(raw: String): Int? = runCatching {
        val s = runCatching { LenientJson.parseToJsonElement(raw).jsonObject }
            .getOrNull()
            ?.let { it["script"]?.jsonObject ?: it["scriptBean"]?.jsonObject ?: it }
            ?: raw.lineSequence().firstOrNull { it.isNotBlank() }
                ?.let { runCatching { LenientJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return@runCatching null
        s["minVerCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }.getOrNull()
}

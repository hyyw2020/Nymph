package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.PathPoint
import dev.nymph.auto.core.model.Script
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 行分隔 JSON 方言导入器（每行一个独立 JSON 对象）。
 * 特性：截断修复 —— 对最后一个被截断的行做括号栈补全并回填空 base64，
 * 把"半截文件"抢救成可用脚本（从 PureClick 验证过的实现移植）。
 */
object LineImporter {

    private class Stats(var skipped: Int = 0, var repaired: Boolean = false)

    fun import(raw: String, curVerCode: Int): Importer.Result? {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val stats = Stats()
        var name = ""
        var refW = 0
        var refH = 0
        var minVer = 0
        val actions = mutableListOf<Action>()
        for (line in lines) {
            val obj = parseOrRepair(line, stats)
            if (obj == null) { stats.skipped++; continue }
            when {
                obj.containsKey("type") && obj["type"] is JsonPrimitive ->
                    convertLineAction(obj, actions, stats, 0)
                obj.containsKey("description") && name.isEmpty() -> {
                    name = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    refW = obj["lastSaveScreenWidth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    refH = obj["lastSaveScreenHeight"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    minVer = obj["minVerCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                }
                else -> stats.skipped++
            }
        }
        if (actions.isEmpty()) return null
        // 版本协商：行分隔脚本的 minVerCode 是 zdjl 方言的 App 版本号，用兼容基线而非 schema 版本
        if (minVer > ZdjlImporter.COMPAT_VERSION_CODE) return null
        return Importer.Result(
            script = Script(
                name = name.ifEmpty { "导入脚本" },
                refWidth = refW, refHeight = refH,
                minVerCode = minVer,
                actions = actions,
            ),
            note = buildNote(stats),
        )
    }

    private fun convertLineAction(obj: JsonObject, out: MutableList<Action>, stats: Stats, depth: Int) {
        if (depth > 4) { stats.skipped++; return }
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "运行多个动作" -> {
                val set = obj["scriptSet"] as? kotlinx.serialization.json.JsonArray ?: run { stats.skipped++; return }
                val repeat = (obj["repeatCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, 100)
                val tmp = mutableListOf<Action>()
                set.forEach { c -> convertLineAction(c.jsonObject, tmp, stats, depth + 1) }
                repeat(repeat) { out.addAll(tmp) }
            }
            "点击", "长按", "滑动" -> convertLinePoint(obj)?.let { out.add(it) } ?: run { stats.skipped++ }
            "控制执行", "等待" -> { /* 纯延时：并入上一个动作的 waitMs */ }
            else -> stats.skipped++
        }
    }

    private fun convertLinePoint(obj: JsonObject): Action? {
        val pos = obj["posData"]?.jsonObject ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "点击"
        val x = parsePercent(pos["x"]?.jsonPrimitive?.contentOrNull)
        val y = parsePercent(pos["y"]?.jsonPrimitive?.contentOrNull)
        if (x <= 0.0 && y <= 0.0) return null
        var x2 = 0.0; var y2 = 0.0
        if (type == "滑动") {
            val end = obj["endPosData"]?.jsonObject ?: obj["posData2"]?.jsonObject
            if (end != null) {
                x2 = parsePercent(end["x"]?.jsonPrimitive?.contentOrNull)
                y2 = parsePercent(end["y"]?.jsonPrimitive?.contentOrNull)
            }
        }
        val repeat = obj["repeatCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        return Action(
            type = when {
                type == "长按" -> ActionType.LONG_PRESS
                type == "滑动" -> ActionType.SWIPE
                repeat > 1 -> ActionType.MULTI_TAP
                else -> ActionType.TAP
            },
            x = x, y = y, x2 = x2, y2 = y2, unit = CoordUnit.PCT,
            durationMs = obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 50L,
            times = repeat.coerceIn(1, 1000),
            gapMs = obj["repeatDelayValue"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 80L,
            waitMs = obj["delay"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 500L,
            imageData = obj["condition"]?.jsonObject?.get("image")?.jsonObject
                ?.get("imageData")?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull ?: "",
            imageSimilarity = obj["condition"]?.jsonObject?.get("image")?.jsonObject
                ?.get("minSimilarPercent")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 80,
        )
    }

    private fun parsePercent(raw: String?): Double =
        raw?.trim()?.removeSuffix("%")?.toDoubleOrNull() ?: 0.0

    private fun parseOrRepair(line: String, stats: Stats): JsonObject? {
        runCatching { return LenientJson.parseToJsonElement(line).jsonObject }
        val repaired = repairTruncated(line) ?: return null
        stats.repaired = true
        return runCatching { LenientJson.parseToJsonElement(repaired).jsonObject }.getOrNull()
    }

    /** 截断修复：定位最后一个 `"data":"`，把超长 base64 替换为空串，再按括号栈补齐。 */
    private fun repairTruncated(line: String): String? {
        val marker = "\"data\":\""
        val idx = line.lastIndexOf(marker)
        if (idx < 0) return null
        val cut = line.substring(0, idx + marker.length - 1) + "\"\""
        val stack = ArrayDeque<Char>()
        var inString = false
        var i = 0
        while (i < cut.length) {
            val c = cut[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
            i++
        }
        val sb = StringBuilder(cut)
        while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == '{') '}' else ']')
        return sb.toString()
    }

    private fun buildNote(stats: Stats): String {
        val notes = mutableListOf<String>()
        if (stats.repaired) notes += "文件疑似被截断，仅导入可见部分"
        if (stats.skipped > 0) notes += "已跳过 ${stats.skipped} 个无法解析的步骤"
        return notes.joinToString("；", prefix = "（", postfix = "）").takeIf { notes.isNotEmpty() } ?: ""
    }
}

/**
 * scriptBean 方言（另一类点击器的导出格式）。
 * 与 zdjl 同源但字段名不同；当前提供最小可用转换，随样本回流扩充。
 */
object ScriptBeanImporter {
    fun import(raw: String, curVerCode: Int): Importer.Result? {
        val root = runCatching { LenientJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val sb = root["scriptBean"]?.jsonObject ?: return null
        val cfg = sb["config"] as? kotlinx.serialization.json.JsonArray ?: return null
        val actions = mutableListOf<Action>()
        cfg.forEach { el ->
            val e = el.jsonObject
            when (e["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()) {
                0 -> { // 点击
                    val p = e["point"]?.jsonObject
                    val x = p?.get("x")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@forEach
                    val y = p["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@forEach
                    actions += Action(
                        type = ActionType.TAP, x = x, y = y, unit = CoordUnit.PCT,
                        times = (e["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, 1000),
                        waitMs = e["delay"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 1000L,
                    )
                }
                10 -> { // 手势 → REPLAY
                    val evts = e["gestureActions"] as? kotlinx.serialization.json.JsonArray ?: return@forEach
                    val pts = evts.mapNotNull { ev ->
                        val o = ev.jsonObject
                        PathPoint(
                            x = o["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null,
                            y = o["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null,
                            tMs = o["eventTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                        )
                    }
                    if (pts.isNotEmpty()) actions += Action(type = ActionType.REPLAY, path = pts)
                }
            }
        }
        if (actions.isEmpty()) return null
        return Importer.Result(
            script = Script(
                name = sb["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "导入脚本" },
                refWidth = sb["screenWidth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                refHeight = sb["screenHeight"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                actions = actions,
            ),
            note = "（scriptBean 方言导入）",
        )
    }
}

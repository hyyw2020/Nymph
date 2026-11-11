package dev.nymph.auto.core.importer

import dev.nymph.auto.core.model.Action
import dev.nymph.auto.core.model.ActionType
import dev.nymph.auto.core.model.Combine
import dev.nymph.auto.core.model.Condition
import dev.nymph.auto.core.model.ConditionKind
import dev.nymph.auto.core.model.CoordUnit
import dev.nymph.auto.core.model.Finger
import dev.nymph.auto.core.model.ImageFilter
import dev.nymph.auto.core.model.FilterKind
import dev.nymph.auto.core.model.ListOrder
import dev.nymph.auto.core.model.Listeners
import dev.nymph.auto.core.model.PathPoint
import dev.nymph.auto.core.model.Region
import dev.nymph.auto.core.model.Script
import dev.nymph.auto.core.model.VarType
import dev.nymph.auto.core.model.VariableDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
/**
 * 自动精灵（zdjl）脚本方言导入器。
 *
 * 目标：把自动精灵导出的 JSON 脚本转换为 NymphAuto 模型，尽量保留：
 *  - 动作语义（含运行条件、监听动作、重复次数、随机等待）
 *  - 视觉参数（图片 base64 / 文字 / 颜色 / 区域 / 滤镜）
 *  - 节点参数（id/class/text/depth）
 *  - 坐标单位与参考分辨率（执行期做分辨率映射）
 *
 * 对齐自动精灵导入器的容错行为：未知动作跳过并计数，绝不整体失败。
 */
object ZdjlImporter {

    /**
     * 兼容基线：本复刻版对齐的自动精灵版本（v26.5.0 内部版本号 26000010）。
     * 脚本声明的 minVerCode 高于此值 → 来自更新版本，拒绝导入并提示升级。
     * 注意：这与 [dev.nymph.auto.core.model.Script.SCHEMA_VERSION] 是两套版本体系，
     * 前者是对方 App 的 versionCode，后者是 NymphAuto 原生脚本结构的 schema 版本，
     * 严禁互相比较（否则会把所有 zdjl 脚本误判为"版本过高"）。
     */
    const val COMPAT_VERSION_CODE: Int = 26000010
    const val COMPAT_VERSION_NAME: String = "26.5.0"

    /** 自动精灵动作类型（中文）→ NymphAuto ActionType。 */
    private val ACTION_MAP: Map<String, ActionType> = mapOf(
        "点击" to ActionType.TAP,
        // v2.21 起坐标点击独立为 location 类型（x/y 百分比在动作顶层）
        "location" to ActionType.TAP,
        "长按" to ActionType.LONG_PRESS,
        "滑动" to ActionType.SWIPE,
        "单指手势" to ActionType.REPLAY,
        "多指手势" to ActionType.MULTI_FINGER,
        "连续手势" to ActionType.REPLAY,
        "点击图片" to ActionType.CLICK_IMAGE,
        "点击文字" to ActionType.CLICK_TEXT,
        "点击颜色" to ActionType.CLICK_COLOR,
        "点击节点" to ActionType.CLICK_NODE,
        // AI 找目标（对标自动精灵"AI识别/智能识别"）：文字描述找目标，
        // 描述字段在 aiLabel / desc / text 三个历史字段名上都可能
        "AI找目标" to ActionType.CLICK_AI,
        "AI识别" to ActionType.CLICK_AI,
        "智能识别" to ActionType.CLICK_AI,
        "点击AI" to ActionType.CLICK_AI,
        "aiClick" to ActionType.CLICK_AI,
        "按键" to ActionType.KEY,
        "输入内容" to ActionType.INPUT,
        "打开应用" to ActionType.OPEN_APP,
        "关闭应用" to ActionType.CLOSE_APP,
        "打开链接" to ActionType.OPEN_LINK,
        "运行多个动作" to ActionType.RUN_ACTIONS,
        "运行脚本" to ActionType.RUN_SCRIPT,
        "运行JS代码" to ActionType.RUN_JS,
        "运行 JS 代码" to ActionType.RUN_JS,
        "JS函数" to ActionType.RUN_JS,
        "等待" to ActionType.WAIT,
        "跳转到" to ActionType.GOTO,
        "停止" to ActionType.STOP,
        "暂停" to ActionType.STOP,
        "继续" to ActionType.WAIT,
        "设置变量" to ActionType.SET_VAR,
        "屏幕识别" to ActionType.CAPTURE_TO_VAR,
        "获取变量" to ActionType.SET_VAR,
        "系统提示" to ActionType.TOAST,
        "提示" to ActionType.TOAST,
        "控制执行" to ActionType.WAIT,
    )

    private class Stats(var skipped: Int = 0, var repaired: Boolean = false)

    /**
     * @return 导入结果；版本过高或内容为空返回 null（调用方据此报错）。
     *
     * 支持两种封装：
     *  1. 单 JSON 对象（整体导出）：{"script":{...}} 或直接是脚本对象
     *  2. 行分隔（.zjs，自动精灵 v2.21+ 的导出格式）：
     *     第 1 行元数据（description=脚本名 / minVerCode / lastSaveScreenWidth...），
     *     其余每行一个顶层动作树对象（通常是一个"运行多个动作"包裹全部步骤）
     */
    fun import(raw: String, curVerCode: Int): Importer.Result? {
        val root = runCatching { LenientJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (root != null) return importWhole(root, curVerCode)
        return importLineDelimited(raw, curVerCode)
    }

    private fun importWhole(root: JsonObject, curVerCode: Int): Importer.Result? {
        // zdjl 脚本可能带外层壳：{"script": {...}} 或直接是动作列表壳
        val s = root["script"]?.jsonObject ?: root["scriptBean"]?.jsonObject ?: root
        val minVer = s["minVerCode"]?.jsonPrimitive?.intOrNull ?: 0
        if (minVer > curVerCode) return null // 版本协商：由调用方转换为 Fail.VersionTooOld

        val stats = Stats()
        val actions = mutableListOf<Action>()
        val rawActions = s["actions"]?.jsonArray ?: s["actionList"]?.jsonArray ?: s["config"]?.jsonArray
        rawActions?.forEach { el ->
            el.jsonObject.takeIf { it.isNotEmpty() }?.let { obj ->
                // 单动作级隔离：单个动作结构异常不拖垮整包（宽容导入）
                runCatching { convertAction(obj, actions, stats, 0) }
                    .onFailure { stats.skipped++ }
            }
        }
        if (actions.isEmpty()) return null

        val name = s["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "导入脚本" }
        val desc = s["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return Importer.Result(
            script = Script(
                name = name,
                schemaVersion = 1,
                minVerCode = minVer,
                description = desc,
                refWidth = s["screenWidth"]?.jsonPrimitive?.intOrNull ?: s["lastSaveScreenWidth"]?.jsonPrimitive?.intOrNull ?: 0,
                refHeight = s["screenHeight"]?.jsonPrimitive?.intOrNull ?: s["lastSaveScreenHeight"]?.jsonPrimitive?.intOrNull ?: 0,
                // D1 根因修复：自动精灵顶层 repeatCount:0 是"无限循环"哨兵值，
                // 引擎契约 0=无限（ScriptRunner 顶层循环），禁止再 coerceAtLeast(1)
                repeatCount = readRepeatCount(s, 1).first.coerceAtLeast(0),
                variables = convertVariables(s["variables"] as? JsonArray),
                actions = actions,
                listeners = readListeners(s),
                abortCondition = readCondition(
                    s["abortCondition"] ?: s["breakCondition"] ?: s["stopCondition"], stats,
                ),
            ),
            note = buildNote(stats),
        )
    }

    /** 行分隔 .zjs：第 1 行元数据，其余行各一个顶层动作树。 */
    private fun importLineDelimited(raw: String, curVerCode: Int): Importer.Result? {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val parsed = lines.mapIndexedNotNull { i, line ->
            runCatching { LenientJson.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return null // 任一行不是合法 JSON → 不是本方言
        }
        // 第 1 行是元数据：无 type 或含 minVerCode/description 特征
        val meta = parsed.first()
        val isMeta = !meta.containsKey("type") ||
            meta.containsKey("minVerCode") || meta.containsKey("description")
        if (!isMeta) return null
        val minVer = meta["minVerCode"]?.jsonPrimitive?.intOrNull ?: 0
        if (minVer > curVerCode) return null // 版本协商

        val stats = Stats()
        val actions = mutableListOf<Action>()
        parsed.drop(1).forEach { obj ->
            // 单动作级隔离：单个动作树结构异常不拖垮整包（宽容导入）
            runCatching { convertAction(obj, actions, stats, 0) }
                .onFailure { stats.skipped++ }
        }
        if (actions.isEmpty()) return null

        val name = meta["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            .ifEmpty { meta["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty() }
            .ifEmpty { "导入脚本" }
        return Importer.Result(
            script = Script(
                name = name,
                schemaVersion = 1,
                minVerCode = minVer,
                description = meta["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                refWidth = meta["lastSaveScreenWidth"]?.jsonPrimitive?.intOrNull ?: 0,
                refHeight = meta["lastSaveScreenHeight"]?.jsonPrimitive?.intOrNull ?: 0,
                // D1 根因修复：同上，行分隔 .zjs 的顶层 repeatCount:0 = 无限循环
                repeatCount = readRepeatCount(meta, 1).first.coerceAtLeast(0),
                variables = convertVariables(meta["variables"] as? JsonArray),
                actions = actions,
                listeners = readListeners(meta),
                abortCondition = readCondition(
                    meta["abortCondition"] ?: meta["breakCondition"] ?: meta["stopCondition"], stats,
                ),
            ),
            note = buildNote(stats, lineDelimited = true),
        )
    }

    // ---------------------------------------------------------------- 动作转换

    private fun convertAction(obj: JsonObject, out: MutableList<Action>, stats: Stats, depth: Int) {
        if (depth > 8) { stats.skipped++; return }   // 防递归过深（对标对方的运行路径深度上限）
        val typeName = obj["type"]?.jsonPrimitive?.contentOrNull ?: run { stats.skipped++; return }
        // 控制执行按 controlRunType 分流：stop=停止脚本，其余=等待
        val type = if (typeName == "控制执行") {
            if (obj["controlRunType"]?.jsonPrimitive?.contentOrNull == "stop") ActionType.STOP else ActionType.WAIT
        } else {
            ACTION_MAP[typeName] ?: run { stats.skipped++; return }
        }
        val base = readCommon(obj)
        val action = when (type) {
            ActionType.TAP, ActionType.LONG_PRESS, ActionType.SWIPE -> convertPoint(obj, type, base)
            ActionType.REPLAY -> convertReplay(obj, base)
            ActionType.MULTI_FINGER -> convertMultiFinger(obj, base)
            ActionType.CLICK_IMAGE, ActionType.CLICK_TEXT, ActionType.CLICK_COLOR -> convertVisual(obj, type, base)
            ActionType.CLICK_NODE -> convertNode(obj, base)
            // AI 找目标：描述字段在 aiLabel / desc / text / aiDesc 上都可能
            ActionType.CLICK_AI -> base.copy(
                type = type,
                aiLabel = obj["aiLabel"]?.jsonPrimitive?.contentOrNull
                    ?: obj["aiDesc"]?.jsonPrimitive?.contentOrNull
                    ?: obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["desc"]?.jsonPrimitive?.contentOrNull ?: "",
                aiThreshold = obj["aiThreshold"]?.jsonPrimitive?.intOrNull
                    ?: obj["minSimilarPercent"]?.jsonPrimitive?.intOrNull ?: 50,
                region = readRegion(obj["limitArea"]?.jsonPrimitive?.contentOrNull),
            )
            ActionType.KEY -> base.copy(type = type, keyName = obj["key"]?.jsonPrimitive?.contentOrNull ?: obj["keyName"]?.jsonPrimitive?.contentOrNull ?: "")
            ActionType.INPUT -> base.copy(
                type = type,
                text = obj["text"]?.jsonPrimitive?.contentOrNull ?: obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                nodeId = obj["inputIndex"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            ActionType.OPEN_APP, ActionType.CLOSE_APP -> base.copy(
                type = type,
                packageName = obj["packageName"]?.jsonPrimitive?.contentOrNull ?: obj["packName"]?.jsonPrimitive?.contentOrNull ?: "",
                restart = obj["restart"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            // 打开链接：对方字段名历史上有 link/url/linkUrl 三种写法，全部认
            ActionType.OPEN_LINK -> base.copy(
                type = type,
                linkUrl = obj["link"]?.jsonPrimitive?.contentOrNull
                    ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["linkUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            ActionType.RUN_ACTIONS -> {
                // 保留包裹层（而非拍平）：自身的 condition/listeners/order/limitCount 不丢失，
                // ScriptRunner 的 RUN_ACTIONS 分支按 times 重复执行 children
                val children = mutableListOf<Action>()
                obj["scriptSet"]?.jsonArray?.forEach { c ->
                    convertAction(c.jsonObject, children, stats, depth + 1)
                }
                val repeat = readRepeatCount(obj, 1).first.coerceIn(1, 1000)
                base.copy(
                    type = type,
                    times = repeat,
                    // 自动精灵 v2.21+ 的 runMode 用英文枚举（multiThread/random/sequential），
                    // 旧版用中文（同时运行/乱序运行/顺序运行），双轨都要认
                    order = when (obj["runMode"]?.jsonPrimitive?.contentOrNull) {
                        "同时运行", "multiThread", "parallel" -> ListOrder.PARALLEL
                        "乱序运行", "随机运行", "random" -> ListOrder.RANDOM
                        else -> ListOrder.SEQUENTIAL
                    },
                    limitCount = obj["limitCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    children = children,
                )
            }
            ActionType.RUN_SCRIPT -> base.copy(
                type = type,
                scriptFile = obj["scriptFile"]?.jsonPrimitive?.contentOrNull
                    ?: obj["fileName"]?.jsonPrimitive?.contentOrNull ?: "",
                returnToCaller = obj["returnToCaller"]?.jsonPrimitive?.booleanOrNull ?: true,
                runArgs = obj["runArgs"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            ActionType.RUN_JS -> base.copy(
                type = type,
                jsCode = obj["jsCode"]?.jsonPrimitive?.contentOrNull ?: obj["code"]?.jsonPrimitive?.contentOrNull ?: "",
                jsFunc = obj["jsFunc"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            ActionType.WAIT -> base.copy(type = type, waitMs = obj["delay"]?.jsonPrimitive?.longOrNull
                ?.let { convertDelay(it, obj["delayUnit"]?.jsonPrimitive?.intOrNull ?: 0) } ?: base.waitMs)
            ActionType.GOTO -> base.copy(type = type, label = obj["label"]?.jsonPrimitive?.contentOrNull
                ?: obj["jumpToPosition"]?.jsonPrimitive?.contentOrNull ?: "")
            ActionType.SET_VAR, ActionType.CAPTURE_TO_VAR -> convertVar(obj, type, base)
            ActionType.TOAST -> base.copy(
                type = type,
                text = obj["text"]?.jsonPrimitive?.contentOrNull ?: obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            else -> base.copy(type = type)
        }
        out.add(
            action.copy(
                condition = readCondition(obj["condition"], stats),
                listeners = readListeners(obj),
                // 执行后删变量：对方字段名历史上写过 deleteVarAfter / delVarAfter /
                // deleteAfterRun 三种，全部认（缺省 false）
                deleteVarAfter = obj["deleteVarAfter"]?.jsonPrimitive?.booleanOrNull
                    ?: obj["delVarAfter"]?.jsonPrimitive?.booleanOrNull
                    ?: obj["deleteAfterRun"]?.jsonPrimitive?.booleanOrNull
                    ?: false,
            ),
        )
    }

    /**
     * repeatCount/times 三形态容错读取：数字 / 数字字符串 / __vars 表达式对象。
     * 自动精灵的 __vars.repeatCount 形如 {"varType":"expression","valueExp":""}，
     * 表示该字段由表达式变量控制。返回 (数值, 表达式)；常量表达式导入期即可解，
     * 含变量引用的保留表达式到 timesExp 供执行期求值（空=用数值）。
     */
    private fun readRepeatCount(obj: JsonObject, default: Int): Pair<Int, String> {
        fun fromExpr(el: JsonObject): Pair<Int, String> {
            if (el["varType"]?.jsonPrimitive?.contentOrNull != "expression") return default to ""
            val exp = el["valueExp"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            exp.toIntOrNull()?.let { return it to "" }
            return default to exp
        }
        return when (val direct = obj["repeatCount"]) {
            is JsonPrimitive -> direct.intOrNull?.let { it to "" }
                ?: direct.contentOrNull?.toIntOrNull()?.let { it to "" }
                ?: (default to "")
            is JsonObject -> fromExpr(direct)
            else -> {
                val varsExp = (obj["__vars"] as? JsonObject)?.get("repeatCount")
                if (varsExp is JsonObject) fromExpr(varsExp) else (default to "")
            }
        }
    }

    /** 读取公共字段：等待/重复/随机/抖动/描述/禁用。 */
    private fun readCommon(obj: JsonObject): Action {
        // repeatCount 三形态（数字/数字串/__vars 表达式）统一走 readRepeatCount；
        // 表达式非纯常量时保留到 timesExp 供执行期求值
        val (rc, rcExp) = readRepeatCount(obj, 1)
        return Action(
            desc = obj["desc"]?.jsonPrimitive?.contentOrNull ?: obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            disabled = obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: obj["enabled"]?.jsonPrimitive?.booleanOrNull?.not() ?: false,
            waitMs = obj["delay"]?.jsonPrimitive?.longOrNull?.let { convertDelay(it, obj["delayUnit"]?.jsonPrimitive?.intOrNull ?: 0) } ?: 500L,
            randomWaitMs = obj["randomDelay"]?.jsonPrimitive?.longOrNull ?: 0L,
            times = rc,
            timesExp = rcExp,
            fastRepeatMode = obj["fastRepeatMode"]?.jsonPrimitive?.booleanOrNull ?: false,
            gapMs = obj["repeatDelayValue"]?.jsonPrimitive?.longOrNull?.let { convertDelay(it, obj["repeatDelayUnit"]?.jsonPrimitive?.intOrNull ?: 0) } ?: 80L,
            jitterPx = obj["randomDistance"]?.jsonPrimitive?.intOrNull ?: 0,
            durationMs = obj["duration"]?.jsonPrimitive?.longOrNull ?: 50L,
            holdMs = obj["touchDuration"]?.jsonPrimitive?.longOrNull ?: obj["pressDuration"]?.jsonPrimitive?.longOrNull ?: 100L,
        )
    }

    private fun convertPoint(obj: JsonObject, type: ActionType, base: Action): Action {
        val (ux, uy, unit) = readPoint(obj)
        val end = obj["endPosData"]?.jsonObject ?: obj["posData2"]?.jsonObject
        val (ux2, uy2, _) = if (end != null) readPoint(end) else Triple(0.0, 0.0, CoordUnit.PCT)
        return base.copy(
            type = type,
            x = ux, y = uy, x2 = ux2, y2 = uy2, unit = unit,
            times = readRepeatCount(obj, 1).first.coerceAtLeast(1),
        )
    }

    /** posData 支持 "90.8%" / "120dp" / "360" 三种写法；v2.21 方言坐标还可能在 location 子对象或动作顶层。 */
    private fun readPoint(obj: JsonObject): Triple<Double, Double, CoordUnit> {
        val pos = obj["posData"]?.jsonObject ?: obj["location"]?.jsonObject ?: obj
        val x = readCoordValue(pos["x"]?.jsonPrimitive?.contentOrNull)
        val y = readCoordValue(pos["y"]?.jsonPrimitive?.contentOrNull)
        val unit = when (pos["type"]?.jsonPrimitive?.contentOrNull) {
            "dp", "逻辑像素" -> CoordUnit.DP
            "px", "像素" -> CoordUnit.PX
            else -> CoordUnit.PCT
        }
        return Triple(x.first, y.first, unit.takeIf { x.second || y.second } ?: CoordUnit.PCT)
    }

    private fun readCoordValue(raw: String?): Pair<Double, Boolean> {
        val s = raw?.trim().orEmpty()
        return when {
            s.endsWith("%") -> (s.removeSuffix("%").toDoubleOrNull() ?: 0.0) to true
            s.endsWith("dp", ignoreCase = true) -> (s.drop(2).toDoubleOrNull() ?: 0.0) to true
            else -> (s.toDoubleOrNull() ?: 0.0) to false
        }
    }

    private fun convertReplay(obj: JsonObject, base: Action): Action {
        val events = obj["gestureActions"]?.jsonArray ?: obj["path"]?.jsonArray
        val pts = mutableListOf<PathPoint>()
        events?.forEach { el ->
            val e = el.jsonObject
            val t = (e["eventTime"]?.jsonPrimitive?.longOrNull ?: e["tMs"]?.jsonPrimitive?.longOrNull ?: 0L)
            pts += PathPoint(
                x = readCoordValue(e["x"]?.jsonPrimitive?.contentOrNull).first,
                y = readCoordValue(e["y"]?.jsonPrimitive?.contentOrNull).first,
                tMs = t,
            )
        }
        return base.copy(type = ActionType.REPLAY, path = pts, durationMs = obj["duration"]?.jsonPrimitive?.longOrNull ?: base.durationMs)
    }

    private fun convertMultiFinger(obj: JsonObject, base: Action): Action {
        val fingers = obj["gestures"]?.jsonArray?.map { g ->
            val pts = g.jsonObject["points"]?.jsonArray?.map { p ->
                PathPoint(
                    x = readCoordValue(p.jsonObject["x"]?.jsonPrimitive?.contentOrNull).first,
                    y = readCoordValue(p.jsonObject["y"]?.jsonPrimitive?.contentOrNull).first,
                    tMs = p.jsonObject["tMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.orEmpty()
            Finger(points = pts, startDelayMs = g.jsonObject["startDelay"]?.jsonPrimitive?.longOrNull ?: 0L)
        }.orEmpty()
        return base.copy(type = ActionType.MULTI_FINGER, fingers = fingers.take(10))
    }

    private fun convertVisual(obj: JsonObject, type: ActionType, base: Action): Action {
        // 视觉参数按优先级：动作顶层 > condition.image / condition.text / condition 本身
        // （v2.21 导出常把模板图/相似度/区域只放在 condition 里）
        val cond = obj["condition"] as? JsonObject
        val cImage = cond?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull in setOf("image", "图片") }
            ?.get("image") as? JsonObject ?: cond
        val cText = cond?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull in setOf("text", "文字", "textFount") }
        // v2.21 还有两处模板图位置：
        //  1. posData.imageData——"点击图片"的坐标位本身是图位（posData.type == "image"）
        //  2. conditionSet 子条件的 image.imageData
        val posData = obj["posData"] as? JsonObject
        val posImage = posData?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull in setOf("image", "图片") }
        val comboImage = (cond?.get("conditions") as? JsonArray)?.firstNotNullOfOrNull { c ->
            c.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull in setOf("image", "图片") }
                ?.get("image") as? JsonObject
        }
        val image = obj["imageData"] as? JsonObject ?: obj["image"] as? JsonObject ?: cImage?.get("imageData") as? JsonObject
            ?: posImage?.get("imageData") as? JsonObject ?: comboImage?.get("imageData") as? JsonObject
        val simSrc = cImage ?: posImage ?: comboImage
        val filters = readFilters(obj["filters"] as? JsonArray)
        return base.copy(
            type = type,
            imageData = image?.get("data")?.jsonPrimitive?.contentOrNull ?: "",
            text = obj["text"]?.jsonPrimitive?.contentOrNull
                ?: (cText?.get("text") as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                ?: (cText?.get("text") as? JsonPrimitive)?.contentOrNull ?: "",
            color = parseColor(obj["color"]?.jsonPrimitive?.contentOrNull
                ?: cond?.get("color")?.jsonPrimitive?.contentOrNull
                ?: cond?.get("colorData")?.jsonPrimitive?.contentOrNull),
            colorSimilarity = obj["minSimilarPercent"]?.jsonPrimitive?.intOrNull
                ?: obj["colorSimilarity"]?.jsonPrimitive?.intOrNull
                ?: cond?.get("minSimilarPercent")?.jsonPrimitive?.intOrNull ?: 90,
            imageSimilarity = obj["minSimilarPercent"]?.jsonPrimitive?.intOrNull
                ?: obj["imageSimilarity"]?.jsonPrimitive?.intOrNull
                ?: simSrc?.get("minSimilarPercent")?.jsonPrimitive?.intOrNull ?: 80,
            region = readRegion(obj["limitArea"]?.jsonPrimitive?.contentOrNull
                ?: simSrc?.get("limitArea")?.jsonPrimitive?.contentOrNull),
            filters = filters,
            maxResults = obj["maxResults"]?.jsonPrimitive?.intOrNull ?: 1,
            offsetX = obj["offsetX"]?.jsonPrimitive?.intOrNull ?: 0,
            offsetY = obj["offsetY"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun convertNode(obj: JsonObject, base: Action): Action = base.copy(
        type = ActionType.CLICK_NODE,
        nodeId = obj["nodeId"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        nodeClass = obj["nodeClass"]?.jsonPrimitive?.contentOrNull ?: obj["className"]?.jsonPrimitive?.contentOrNull ?: "",
        nodeText = obj["nodeText"]?.jsonPrimitive?.contentOrNull ?: obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
        nodeDepthMin = obj["depthMin"]?.jsonPrimitive?.intOrNull ?: 0,
        nodeDepthMax = obj["depthMax"]?.jsonPrimitive?.intOrNull ?: 0,
        nodeDirectClick = obj["directClick"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private fun convertVar(obj: JsonObject, type: ActionType, base: Action): Action = base.copy(
        type = type,
        varName = obj["varName"]?.jsonPrimitive?.contentOrNull ?: obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
        varValue = obj["value"]?.jsonPrimitive?.contentOrNull ?: obj["expression"]?.jsonPrimitive?.contentOrNull ?: "",
        varType = when (obj["varType"]?.jsonPrimitive?.contentOrNull) {
            "数值" -> VarType.NUMBER; "字符" -> VarType.STRING; "布尔" -> VarType.BOOL
            "颜色" -> VarType.COLOR; "图片" -> VarType.IMAGE; "屏幕区域" -> VarType.REGION
            "节点" -> VarType.NODE; "文字" -> VarType.TEXT; "坐标" -> VarType.COORD
            "文件" -> VarType.FILE; "键值对象" -> VarType.DICT
            "数组(数值)" -> VarType.ARRAY_NUM; "数组(字符)" -> VarType.ARRAY_STR
            "数组(脚本动作)" -> VarType.ARRAY_ACTION
            else -> VarType.STRING
        },
    )

    // ---------------------------------------------------------------- 条件 / 监听 / 滤镜 / 变量

    /** 单动作转换（监听动作用）：转换失败返回 null 并计数。 */
    private fun convertOne(obj: JsonObject, stats: Stats): Action? = runCatching {
        val out = mutableListOf<Action>()
        convertAction(obj, out, stats, 0)
        out.firstOrNull()
    }.getOrNull()

    /**
     * 中止条件的顶层字段载荷：按存在的字段推断类型（图片/文字/颜色/节点/JS），
     * 复用 readCondition 的各类型读取逻辑（合成 type 键）。
     */
    private fun directPayload(obj: JsonObject, stats: Stats): Condition? {
        val t = when {
            obj["imageData"] != null || obj["image"] != null -> "image"
            obj["text"] != null -> "text"
            obj["colorData"] != null || obj["color"] != null -> "color"
            obj["nodeId"] != null || obj["nodeClass"] != null -> "node"
            obj["aiLabel"] != null || obj["aiDesc"] != null -> "ai"
            obj["jsExpr"] != null || obj["expression"] != null -> "js"
            else -> return null
        }
        return readCondition(
            buildJsonObject {
                obj.forEach { (k, v) -> put(k, v) }
                put("type", JsonPrimitive(t)) // 显式 JsonElement：put 无 String 重载
            },
            stats,
        )
    }

    private fun readCondition(raw: Any?, stats: Stats): Condition? {
        val obj = raw as? JsonObject ?: return null
        val t = obj["type"].asPrimitive?.contentOrNull ?: return null
        // image 条件的参数可能在 obj.image 子对象里，也可能直接在 obj 上（两种版本导出格式）
        // 注意：JsonElement.jsonObject 对非对象元素是抛异常而非安全转型（旧版 text 写成
        // 纯字符串时曾让整条动作被单动作隔离吞掉跳过），这里一律用 as? 安全转型。
        val image = obj["image"] as? JsonObject ?: obj
        val imageData = image["imageData"] as? JsonObject ?: obj["imageData"] as? JsonObject
        return when (t) {
            "image", "图片" -> Condition(
                kind = ConditionKind.APPEAR_IMAGE,
                invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                imageData = imageData?.get("data")?.jsonPrimitive?.contentOrNull ?: "",
                imageSimilarity = image["minSimilarPercent"]?.jsonPrimitive?.intOrNull ?: 80,
                region = readRegion(image["limitArea"]?.jsonPrimitive?.contentOrNull),
                // v2.21 起滤镜在 image.imageFilter（单数对象）；旧版在顶层 filters（数组）
                filters = readFilterList(obj["filters"] ?: image["imageFilter"]),
                jsExpr = obj["jsExpr"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "text", "文字", "textFount" -> Condition(
                kind = ConditionKind.APPEAR_TEXT,
                invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                // v2.21 起文字在 text 子对象里：{"text":{"type":"text","text":"拾取",...}}；
                // 旧版是纯字符串 {"type":"text","text":"拾取"}——两种都要认
                text = (obj["text"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: (obj["text"] as? JsonPrimitive)?.contentOrNull ?: "",
                textRegex = obj["textRegex"]?.jsonPrimitive?.booleanOrNull ?: false,
                region = readRegion((obj["text"] as? JsonObject)?.get("limitArea")?.jsonPrimitive?.contentOrNull
                    ?: obj["limitArea"]?.jsonPrimitive?.contentOrNull),
                // v2.21 起文字条件可带单数 filter（匹配前的灰度/二值化预处理）
                filters = readFilterList(obj["filters"] ?: (obj["text"] as? JsonObject)?.get("filter")),
            )
            "node", "节点" -> Condition(
                kind = ConditionKind.APPEAR_NODE,
                invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                nodeId = obj["nodeId"]?.jsonPrimitive?.contentOrNull ?: "",
                nodeClass = obj["nodeClass"]?.jsonPrimitive?.contentOrNull ?: "",
                nodeText = obj["nodeText"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            // AI 找目标条件（对标自动精灵"AI识别"条件）：描述对应类别的物体出现即满足
            "ai", "AI找目标", "AI识别", "智能识别", "aiFound" -> Condition(
                kind = ConditionKind.APPEAR_AI,
                invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                aiLabel = obj["aiLabel"]?.jsonPrimitive?.contentOrNull
                    ?: obj["aiDesc"]?.jsonPrimitive?.contentOrNull
                    ?: obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["desc"]?.jsonPrimitive?.contentOrNull ?: "",
                aiThreshold = obj["aiThreshold"]?.jsonPrimitive?.intOrNull
                    ?: obj["minSimilarPercent"]?.jsonPrimitive?.intOrNull ?: 50,
                region = readRegion(obj["limitArea"]?.jsonPrimitive?.contentOrNull),
            )
            "color", "颜色", "colorFound" -> {
                // v2.21：colorData 是对象 {color,limitArea,similarPercent,x,y,limitPosX,limitPosY}
                // 旧版可能直接给 color 字符串——安全转型，别把整条动作炸进跳过计数
                val cd = obj["colorData"] as? JsonObject
                Condition(
                    kind = ConditionKind.APPEAR_COLOR,
                    invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                    color = parseColor(obj["color"]?.jsonPrimitive?.contentOrNull
                        ?: cd?.get("color")?.jsonPrimitive?.contentOrNull),
                    colorSimilarity = obj["colorSimilarity"]?.jsonPrimitive?.intOrNull
                        ?: obj["minSimilarPercent"]?.jsonPrimitive?.intOrNull
                        ?: cd?.get("similarPercent")?.jsonPrimitive?.intOrNull ?: 90,
                    coordX = obj["coordX"]?.jsonPrimitive?.doubleOrNull
                        ?: cd?.get("x")?.jsonPrimitive?.contentOrNull?.removeSuffix("%")?.toDoubleOrNull() ?: -1.0,
                    coordY = obj["coordY"]?.jsonPrimitive?.doubleOrNull
                        ?: cd?.get("y")?.jsonPrimitive?.contentOrNull?.removeSuffix("%")?.toDoubleOrNull() ?: -1.0,
                    region = readRegion(obj["limitArea"]?.jsonPrimitive?.contentOrNull
                        ?: cd?.get("limitArea")?.jsonPrimitive?.contentOrNull),
                )
            }
            "js", "JS表达式" -> Condition(
                kind = ConditionKind.JS_EXPR,
                invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                jsExpr = obj["jsExpr"]?.jsonPrimitive?.contentOrNull ?: obj["expression"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "random", "随机" -> Condition(
                kind = ConditionKind.RANDOM,
                randomPercent = obj["randomPercent"]?.jsonPrimitive?.intOrNull ?: 50,
            )
            // timeAfter：系统时间超过 timeValue（"yyyy-MM-dd HH:mm:ss" 绝对时间或 epoch 毫秒）才满足
            "timeAfter", "定时", "运行时间" -> Condition(
                kind = ConditionKind.AFTER_TIME,
                invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                afterTimeMs = parseTimeValue(obj["timeValue"]?.jsonPrimitive?.contentOrNull),
            )
            // 中止条件（对标自动精灵 break）：载荷命中即中止整个脚本。
            // 载荷优先取嵌套子条件；否则按顶层字段（图片/文字/颜色/节点/JS）构造。
            "break", "中止", "中止条件", "breakCondition", "stop", "停止条件" -> {
                val child = (obj["condition"] ?: obj["child"] ?: obj["breakCondition"]
                    ?: obj["payload"])?.let { readCondition(it, stats) }
                val payload = child ?: directPayload(obj, stats)
                Condition(
                    kind = ConditionKind.ABORT,
                    invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                    children = listOfNotNull(payload),
                )
            }
            "multi", "多个条件", "conditionSet" -> Condition(
                kind = ConditionKind.COMBINE,
                invert = obj["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                combine = when (obj["satisfyMode"]?.jsonPrimitive?.contentOrNull
                    ?: obj["matchMode"]?.jsonPrimitive?.contentOrNull) {
                    "满足一个", "一个满足", "or" -> Combine.ANY
                    "满足指定个数", "指定个数", "count" -> Combine.N
                    "全部满足", "and" -> Combine.ALL
                    else -> Combine.ALL
                },
                // matchCount 优先：自动精灵 conditionSet 的英文 matchMode=count 时
                // 指定个数写在 matchCount；旧版方言才用 satisfyCount/satisfyNum
                runCountLimit = obj["matchCount"]?.jsonPrimitive?.intOrNull
                    ?: obj["satisfyCount"]?.jsonPrimitive?.intOrNull
                    ?: obj["satisfyNum"]?.jsonPrimitive?.intOrNull ?: 1,
                children = (obj["conditions"] as? JsonArray)?.mapNotNull { readCondition(it, stats) }.orEmpty(),
            )
            else -> { stats.skipped++; null }
        }
    }

    private fun readListeners(obj: JsonObject): Listeners {
        fun list(key: String) = (obj[key] as? JsonArray)?.mapNotNull { el ->
            convertOne(el.jsonObject, Stats())
        }.orEmpty()
        // v2.21 起回调是 scriptCallbacks:{"时机名": 单个动作} 的字典；旧版是顶层 listener* 数组；
        // 脚本级（.zjs 第 1 行元数据）的回调字典叫 globalCallbacks
        val callbacks = obj["scriptCallbacks"]?.jsonObject ?: obj["globalCallbacks"]?.jsonObject
        fun cb(key: String): List<Action> {
            val v = callbacks?.get(key) ?: obj[key] ?: return emptyList()
            val one = (v as? JsonObject)?.let { convertOne(it, Stats()) }
            return listOfNotNull(one)
        }
        return Listeners(
            beforeAction = list("listenerBefore"),
            afterAction = list("listenerAfter") + cb("afterExecFinish"),
            onActionFail = list("listenerFail"),
            onActionOk = list("listenerOk") + cb("afterConditionSuc"),
            beforeList = list("listenerListBefore"),
            afterList = list("listenerListAfter"),
            beforeScript = list("listenerScriptBefore") + cb("beforeScript"),
            afterScript = list("listenerScriptAfter") + cb("afterExecSuc"),
            beforeConditionCheck = cb("beforeCondition"),
            onConditionFail = cb("afterConditionFail"),
        )
    }

    private fun readFilters(raw: JsonArray?): List<ImageFilter> = raw?.mapNotNull { el ->
        val o = el.jsonObject
        val kind = when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "RGB滤镜" -> FilterKind.RGB; "亮度滤镜" -> FilterKind.BRIGHTNESS
            "对比度滤镜" -> FilterKind.CONTRAST; "灰度滤镜", "grey", "gray", "灰度" -> FilterKind.GRAY
            "黑白滤镜", "bw", "黑白" -> FilterKind.BLACK_WHITE; "反相滤镜" -> FilterKind.INVERT
            "颜色替换" -> FilterKind.COLOR_REPLACE; "轮廓滤镜" -> FilterKind.CONTOUR
            else -> return@mapNotNull null
        }
        ImageFilter(
            kind = kind,
            r = o["r"]?.jsonPrimitive?.intOrNull ?: 0,
            g = o["g"]?.jsonPrimitive?.intOrNull ?: 0,
            b = o["b"]?.jsonPrimitive?.intOrNull ?: 0,
            brightness = o["brightness"]?.jsonPrimitive?.intOrNull ?: 0,
            contrast = o["contrast"]?.jsonPrimitive?.intOrNull ?: 0,
            threshold = o["threshold"]?.jsonPrimitive?.intOrNull ?: 127,
            fromColor = parseColor(o["fromColor"]?.jsonPrimitive?.contentOrNull),
            toColor = parseColor(o["toColor"]?.jsonPrimitive?.contentOrNull),
        )
    }.orEmpty()

    /** 滤镜读取（宽容）：兼容顶层 filters 数组与 v2.21 的单数 imageFilter/filter 对象。 */
    private fun readFilterList(raw: Any?): List<ImageFilter> = when (raw) {
        is JsonArray -> readFilters(raw)
        is JsonObject -> readFilters(JsonArray(listOf(raw)))
        else -> emptyList()
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 解析 timeAfter 的 timeValue："2026-03-31 04:17:02" 绝对时间或 epoch 毫秒；失败返回 0（条件恒不满足）。 */
    private fun parseTimeValue(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0L
        s.toLongOrNull()?.let { return it }
        return runCatching {
            LocalDateTime.parse(s, TIME_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }
    }

    private fun readRegion(raw: String?): Region {
        val parts = raw?.split(Regex("[\\s,，]+"))?.map { it.trim().removeSuffix("%") }?.filter { it.isNotEmpty() }
        if (parts == null || parts.size < 4) return Region()
        val v = parts.take(4).map { it.toDoubleOrNull() ?: return Region() }
        return Region(v[0], v[1], v[2], v[3])
    }

    private fun convertVariables(raw: JsonArray?): List<VariableDef> = raw?.mapNotNull { el ->
        val o = el.jsonObject
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) null else VariableDef(
            name = name,
            display = o["display"]?.jsonPrimitive?.contentOrNull ?: "",
            value = o["value"]?.jsonPrimitive?.contentOrNull ?: "",
            expression = o["expression"]?.jsonPrimitive?.contentOrNull ?: "",
            requireInput = o["requireInput"]?.jsonPrimitive?.booleanOrNull ?: false,
            description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }.orEmpty()

    private fun parseColor(raw: String?): Long {
        val s = raw?.trim()?.removePrefix("#")?.removePrefix("0x")?.lowercase().orEmpty()
        if (s.isEmpty()) return 0L
        return s.toLongOrNull(16) ?: 0L
    }

    private fun convertDelay(value: Long, unit: Int): Long = when (unit) {
        1 -> value * 1000L
        2 -> value * 60_000L
        else -> value
    }

    private fun buildNote(stats: Stats, lineDelimited: Boolean = false): String {
        val notes = mutableListOf<String>()
        if (stats.repaired) notes += "文件疑似被截断，仅导入可见部分"
        if (lineDelimited) notes += "行分隔脚本（.zjs）"
        if (stats.skipped > 0) notes += "已跳过 ${stats.skipped} 个不支持的步骤"
        return notes.joinToString("；", prefix = "（", postfix = "）").takeIf { notes.isNotEmpty() } ?: ""
    }
}

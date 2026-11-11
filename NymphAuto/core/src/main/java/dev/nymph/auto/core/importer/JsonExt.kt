package dev.nymph.auto.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 导入器共享的宽容 JSON 实例（isLenient + 忽略未知键），避免每次调用重复创建。 */
internal val LenientJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * JSON 原始值的宽容取值扩展。
 *
 * 自动精灵方言里标量常以字符串形式出现（"80"、 "true"、 "1"），
 * 也可能直接是数字/布尔——统一按 contentOrNull 再转换，避免每个调用点重复写样板。
 */

val JsonPrimitive?.intOrNull: Int?
    get() = this?.contentOrNull?.trim()?.toIntOrNull()

val JsonPrimitive?.longOrNull: Long?
    get() = this?.contentOrNull?.trim()?.toLongOrNull()

val JsonPrimitive?.doubleOrNull: Double?
    get() = this?.contentOrNull?.trim()?.toDoubleOrNull()

val JsonPrimitive?.booleanOrNull: Boolean?
    get() = this?.contentOrNull?.trim()?.lowercase()?.let {
        when (it) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

/**
 * 安全取原始值：字段实际是对象/数组时返回 null 而非抛异常
 * （真实样本里 colorData 等字段在不同版本间会在字符串与对象间变化）。
 */
val JsonElement?.asPrimitive: JsonPrimitive?
    get() = this as? JsonPrimitive

/** 安全取对象：字段实际是原始值/数组时返回 null 而非抛异常。 */
val JsonElement?.asObject: JsonObject?
    get() = this as? JsonObject

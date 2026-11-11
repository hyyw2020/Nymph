// SPDX-License-Identifier: MIT
package dev.nymph.auto.core.recorder

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 录制事件的**明文**持久化（JSON 数组，非二进制、不加密、不混淆）。
 *
 * 用途：录制过程按事件增量落盘，App 被杀或崩溃后仍可恢复未编译的事件流；
 * 同时也是审计材料——任何人用文本编辑器即可逐条核对录制器记录了什么。
 *
 * 与 `ScriptCodec` 的区别：后者编解码最终脚本（.zjs），本类编解码中间事件流。
 * 两者都是明文 JSON，符合「不允许加密、不允许混淆」的硬性约束。
 */
object RecordCodec {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(events: List<RecordEvent>): String = json.encodeToString(events)

    /** 宽容解码：坏文件返回空列表，绝不抛异常影响录制会话恢复。 */
    fun decode(raw: String): List<RecordEvent> = runCatching {
        if (raw.isBlank()) emptyList() else json.decodeFromString<List<RecordEvent>>(raw)
    }.getOrDefault(emptyList())

    /** 追加一条事件（读-改-写，录制期低频调用足够）。 */
    fun append(raw: String, event: RecordEvent): String = encode(decode(raw) + event)
}

package dev.nymph.auto.core.recorder

import java.util.concurrent.atomic.AtomicLong

/**
 * 录制事件闸门（v2.7.0，纯 JVM 可测）。
 *
 * ## 回应用户吐槽
 *
 * 「他刚开始录制，脚本就开始记录了，即使还在点击器里，就开始记录，
 * 一个点击器里能有什么自己对自己的自动化？纯纯无聊的功能。用户根本
 * 没时间再切回后台拾取坐标」——
 *
 * 两层闸门：
 *  1. **自扰过滤**：任何来自 [selfPackage] 的事件（点开始、点浮窗、在
 *     编辑器里翻页）直接丢弃，永不进脚本。
 *  2. **待命**：启动后不记录，直到收到第一个来自非本应用且有包名的事件
 *     才解除；解除的那个事件本身保留（通常是 WINDOW_CHANGED，正好供
 *     RecordCompiler 写入 startPackage）。用户不用手忙脚乱切后台。
 *
 * 未传 selfPackage 时 [armed] 初始即 true，行为与 v2.6.0 完全一致。
 */
class RecordGate(val selfPackage: String = "") {

    enum class Decision {
        /** 本应用自扰事件，丢弃。 */
        DROP_SELF,

        /** 待命中且事件无包名，丢弃。 */
        DROP_WAITING,

        /** 已解除待命，本事件应记录（并通知 UI 刷新）。 */
        ARMED,

        /** 正常记录。 */
        ACCEPT,
    }

    @Volatile
    var armed: Boolean = selfPackage.isEmpty()
        private set

    private val droppedSelf = AtomicLong()

    fun droppedSelfCount(): Long = droppedSelf.get()

    /** 判定一条无障碍事件是否应进入录制会话。回调链上只做这一次轻量判断。 */
    fun accept(packageName: String): Decision {
        if (selfPackage.isNotEmpty() && packageName == selfPackage) {
            droppedSelf.incrementAndGet()
            return Decision.DROP_SELF
        }
        if (!armed) {
            if (packageName.isEmpty()) return Decision.DROP_WAITING
            armed = true
            return Decision.ARMED
        }
        return Decision.ACCEPT
    }
}

package dev.nymph.auto.engine

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * 脚本输入法：为脚本提供全按键触发与中文/特殊符号输入能力。
 *
 * 说明（对齐自动精灵的同类设计）：
 *  - 未启用本输入法时，"按键"动作仅支持 7 个全局键（返回/主页/任务/音量/截屏/粘贴/电源）
 *  - 启用并设为默认输入法后，支持全部按键与输入框文本输入
 *  - 本类刻意保持极简：不采集、不上传任何输入内容（无网络权限兜底）
 */
class ImeService : InputMethodService() {

    override fun onCreateInputView(): View? = null // 无可见 UI，纯能力通道

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
    }

    /** 供脚本调用的输入入口（经 InputConnection 提交文本）。 */
    fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    /** 供脚本调用的按键入口（KeyEvent 注入到当前输入框）。 */
    fun sendKey(keyCode: Int) {
        currentInputConnection?.sendKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode),
        )
        currentInputConnection?.sendKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode),
        )
    }

    companion object {
        @Volatile
        var instance: ImeService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

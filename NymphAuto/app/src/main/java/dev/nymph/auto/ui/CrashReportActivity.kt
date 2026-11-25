package dev.nymph.auto.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 崩溃可见诊断页（v2.9.38，独立进程 :crash）。
 *
 * 背景：用户反馈"打开就闪退"，而崩溃堆栈此前只落在 filesDir/logs/last_crash.txt，
 * 冷启动即崩时用户根本进不去日志页导出。本页在全局崩溃处理器捕获异常后立即拉起，
 * 运行在独立进程，即使主进程随即被系统结束也能显示完整堆栈。
 *
 * 设计约束：
 * - 纯代码构建 UI，零新增 layout/drawable 资源，最小的崩溃面；
 * - 只显示本机堆栈 + 复制 / 系统分享 / 重启，不含任何网络上报；
 * - 堆栈来源优先 Intent extra，回退读取 last_crash.txt（同一 package 可读）。
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NymphAuto 崩溃报告"

        val stack = intent.getStringExtra(EXTRA_STACK)
            ?: readCrashFile().orEmpty().ifBlank { "（未能读取堆栈）" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 26, 30))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val hint = TextView(this).apply {
            text = "应用发生未捕获异常并已退出。请把下面堆栈截图或分享给开发者：\n" +
                "Android API ${Build.VERSION.SDK_INT} / ${Build.MODEL}"
            textSize = 14f
            setTextColor(Color.rgb(255, 214, 128))
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(hint)

        val tv = TextView(this).apply {
            text = stack
            textSize = 11f
            setTextColor(Color.rgb(220, 226, 235))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 18, 22))
            addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(12); bottomMargin = dp(12) }
        }
        root.addView(scroll)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(button("复制堆栈") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("NymphAuto 崩溃堆栈", stack))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        actions.addView(button("分享") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "NymphAuto 崩溃堆栈")
                putExtra(Intent.EXTRA_TEXT, stack)
            }
            runCatching {
                startActivity(Intent.createChooser(send, "发送崩溃堆栈").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        actions.addView(button("重启应用") {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
            }
            finish()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        root.addView(actions)

        setContentView(root)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun readCrashFile(): String? = runCatching {
        File(filesDir, "logs/last_crash.txt").takeIf { it.exists() }?.readText()
    }.getOrNull()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_STACK = "stack"

        /** 崩溃时拉起错误页；独立进程保证主进程结束后仍可显示。 */
        fun launch(context: Context, stack: String) {
            runCatching {
                val i = Intent(context, CrashReportActivity::class.java).apply {
                    putExtra(EXTRA_STACK, stack)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(i)
            }
        }

        /** 当前进程是否为崩溃诊断进程（防止错误页自身崩溃再拉起自己形成循环）。 */
        fun isCrashProcess(context: Context): Boolean {
            // 主路径：ActivityManager 列出进程名。
            val fromAm = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            }.getOrNull()
            if (isCrashProcessName(fromAm)) return true
            // v2.9.41（⑤a）：部分 ROM 的 runningAppProcesses 只回主进程或返回 null，
            // 导致 :crash 进程里的错误页自身崩溃时防循环失效、无限拉起。回退读取
            // Linux 标准的 /proc/self/cmdline（首段以 NUL 分隔，即当前完整进程名）。
            // 两路任一判为 crash 即视为 crash：漏判会无限重启，远比保守多判严重。
            val fromProc = runCatching {
                File("/proc/self/cmdline").readBytes()
                    .takeWhile { it != 0.toByte() }.toByteArray()
                    .toString(Charsets.UTF_8).trim().ifBlank { null }
            }.getOrNull()
            return isCrashProcessName(fromProc)
        }

        /** 纯判定逻辑（抽离以便纯 JVM 回归）：完整进程名是否崩溃诊断进程。 */
        fun isCrashProcessName(name: String?): Boolean = name?.endsWith(":crash") == true
    }
}

package dev.nymph.auto.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import dev.nymph.auto.R
import dev.nymph.auto.core.log.LogEntry
import dev.nymph.auto.core.log.RunLog
import dev.nymph.auto.log.LogStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 运行日志页：展示导入/执行/异常的完整流水（本地滚动文件 + 内存缓冲）。
 * 支持分享导出（系统分享面板）与清空。无网络权限，日志不会外传。
 *
 * 崩溃可观测：若存在上次未捕获崩溃的堆栈（filesDir/logs/last_crash.txt），
 * 顶部显示红色横幅，点击可查看完整堆栈并一键清除记录。
 */
class LogActivity : Activity() {

    private lateinit var lv: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCrash: TextView
    private var adapter: ArrayAdapter<String>? = null

    private val onEntry: (LogEntry) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        lv = findViewById(R.id.lvLog)
        tvEmpty = findViewById(R.id.tvLogEmpty)
        tvCrash = findViewById(R.id.tvCrashBanner)
        findViewById<Button>(R.id.btnLogShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnLogBundle).setOnClickListener { exportBundle() }
        findViewById<Button>(R.id.btnLogClear).setOnClickListener {
            LogStore.clear()
            refresh()
        }
        setupCrashBanner()
        refresh()
    }

    private fun setupCrashBanner() {
        val crashText = LogStore.readCrash()
        if (crashText == null) {
            tvCrash.visibility = View.GONE
            return
        }
        tvCrash.visibility = View.VISIBLE
        tvCrash.text = "上次发生崩溃，点击查看完整堆栈"
        tvCrash.setOnClickListener {
            val tv = TextView(this).apply {
                setTextColor(0xFFE5E7EB.toInt())
                textSize = 10f
                typeface = Typeface.MONOSPACE
                text = crashText
            }
            val sv = ScrollView(this).apply { addView(tv) }
            AlertDialog.Builder(this)
                .setTitle("上次崩溃堆栈")
                .setView(sv)
                .setPositiveButton("清除记录") { _, _ ->
                    LogStore.clearCrash()
                    tvCrash.visibility = View.GONE
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        RunLog.addListener(onEntry)
    }

    override fun onStop() {
        RunLog.removeListener(onEntry)
        super.onStop()
    }

    private fun refresh() {
        val lines = LogStore.tail(500)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        lv.adapter = adapter
        tvEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        if (lines.isNotEmpty()) lv.setSelection(lines.size - 1)
    }

    private fun share() {
        val text = buildString {
            appendLine("== NymphAuto 运行日志 ==")
            append(LogStore.exportText().ifEmpty { "（暂无日志）" })
            LogStore.readCrash()?.let {
                appendLine()
                appendLine()
                appendLine(it)
            }
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "NymphAuto 运行日志")
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "分享日志",
            ),
        )
    }

    /**
     * 导出调试包：logs/（运行日志+崩溃堆栈）+ debug/（失败现场截图）打成 zip 分享。
     * 这是定位"为什么模板匹配不上"的关键材料——截图 + 日志 + 最佳 NCC 分数。
     */
    private fun exportBundle() {
        val zip = runCatching { buildBundle() }.getOrNull()
        if (zip == null) {
            android.widget.Toast.makeText(this, "调试包生成失败（无日志或无截图）", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "dev.nymph.auto.fileprovider", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "NymphAuto 调试包")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "发送调试包（含失败截图）"))
    }

    private fun buildBundle(): File? {
        val logsDir = File(filesDir, "logs")
        val debugDir = File(filesDir, "debug")
        val inputs = buildList {
            logsDir.listFiles()?.forEach { add(it) }
            debugDir.listFiles()?.forEach { add(it) }
        }
        if (inputs.isEmpty()) return null
        val outDir = File(cacheDir, "bundles").apply { mkdirs() }
        val out = File(outDir, "nymphauto-debug-${System.currentTimeMillis()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            inputs.forEach { f ->
                runCatching {
                    zos.putNextEntry(ZipEntry(if (f.parentFile?.name == "debug") "debug/${f.name}" else "logs/${f.name}"))
                    f.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        // 只保留最近 3 个包，防止堆积
        outDir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(3)?.forEach { runCatching { it.delete() } }
        return out
    }
}

package com.bgplayer.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bgplayer.core.BgLog
import java.io.File
import java.io.RandomAccessFile

/** Log viewer + log level setting + export. */
class LogsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LogsUI"
        private const val SHOW_BYTES = 200_000L
        private val LEVELS = intArrayOf(BgLog.VERBOSE, BgLog.DEBUG, BgLog.INFO, BgLog.WARN, BgLog.ERROR, BgLog.NONE)
        private val LEVEL_NAMES = arrayOf(
            "VERBOSE — всё (по умолчанию)", "DEBUG", "INFO", "WARN — только предупреждения", "ERROR — только ошибки", "Выключено"
        )
    }

    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Логи"
        BgLog.i(TAG, "open logs screen")

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        root.addView(TextView(this).apply { text = "Уровень логирования:" })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LEVEL_NAMES)
        spinner.setSelection(LEVELS.indexOf(BgLog.getLevel()).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val lvl = LEVELS[pos]
                if (lvl != BgLog.getLevel()) {
                    Prefs.logLevel = lvl
                    BgLog.setLevel(lvl)
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Обновить"; setOnClickListener { load() } })
        buttons.addView(Button(this).apply { text = "Отправить"; setOnClickListener { share() } })
        buttons.addView(Button(this).apply { text = "Очистить"; setOnClickListener { clear() } })
        root.addView(buttons)

        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logText) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root, android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        load()
    }

    private fun load() {
        val f = App.fileSink.currentFile()
        val content = if (!f.exists()) "(лог пуст)" else tail(f, SHOW_BYTES)
        logText.text = content
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun tail(f: File, max: Long): String {
        RandomAccessFile(f, "r").use { raf ->
            val start = maxOf(0L, raf.length() - max)
            val buf = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
    }

    private fun share() {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val out = File(dir, "bgplayer-logs.txt")
            out.outputStream().use { o ->
                for (f in App.fileSink.filesOldestFirst()) f.inputStream().use { it.copyTo(o) }
            }
            BgLog.i(TAG, "share logs ${out.length()} bytes")
            val uri = FileProvider.getUriForFile(this, "$packageName.logs", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BGplayer logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Отправить логи"))
        } catch (t: Throwable) {
            BgLog.e(TAG, "share failed", t)
            Toast.makeText(this, "Не удалось: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clear() {
        App.fileSink.clear()
        BgLog.i(TAG, "logs cleared by user")
        load()
    }
}

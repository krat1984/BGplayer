package com.bgplayer.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.bgplayer.core.BgLog
import com.bgplayer.core.Chapter
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UI"
        private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "mpg", "mpeg", "wmv", "flv")
    }

    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var status: TextView
    private lateinit var btnLoop: Button

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    /** Items chosen before the controller was connected. */
    private var pendingItems: Pair<List<MediaItem>, Int>? = null

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        BgLog.i(TAG, "folder picked: $uri")
        if (uri != null) onFolderPicked(uri)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        BgLog.i(TAG, "file picked: $uri")
        if (uri != null) {
            persist(uri)
            play(listOf(itemFor(uri, displayName(uri))), 0)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            BgLog.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BgLog.i(TAG, "onCreate intent=${intent?.action} data=${intent?.data}")
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        status = findViewById(R.id.status)
        btnLoop = findViewById(R.id.btnLoop)

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            topBar.visibility = v
        })

        findViewById<Button>(R.id.btnFolder).setOnClickListener {
            BgLog.i(TAG, "click: folder")
            pickFolder.launch(Prefs.lastFolder?.let { Uri.parse(it) })
        }
        findViewById<Button>(R.id.btnFile).setOnClickListener {
            BgLog.i(TAG, "click: file")
            pickFile.launch(arrayOf("video/*"))
        }
        findViewById<Button>(R.id.btnPlaylist).setOnClickListener { showPlaylist() }
        findViewById<Button>(R.id.btnPrevChapter).setOnClickListener {
            PlayerHub.service?.previousChapter("ui") ?: BgLog.w(TAG, "service not running")
        }
        findViewById<Button>(R.id.btnNextChapter).setOnClickListener {
            PlayerHub.service?.nextChapter("ui") ?: BgLog.w(TAG, "service not running")
        }
        findViewById<Button>(R.id.btnChapters).setOnClickListener { showChapters() }
        btnLoop.setOnClickListener { toggleLoop() }
        findViewById<Button>(R.id.btnTimer).setOnClickListener { showTimerDialog() }
        findViewById<Button>(R.id.btnLogs).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        updateLoopButton(if (Prefs.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            BgLog.i(TAG, "requesting POST_NOTIFICATIONS")
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        BgLog.i(TAG, "onNewIntent action=${intent.action} data=${intent.data}")
        handleViewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        BgLog.i(TAG, "onStart -> connect controller")
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            try {
                onControllerReady(future.get())
            } catch (t: Throwable) {
                BgLog.e(TAG, "controller connection failed", t)
            }
        }, ContextCompat.getMainExecutor(this))
        ui.post(ticker)
    }

    override fun onStop() {
        BgLog.i(TAG, "onStop (screen off or app in background): audio continues in service")
        ui.removeCallbacks(ticker)
        PlayerHub.service?.setVideoEnabled(false)
        playerView.player = null
        controller?.removeListener(controllerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        BgLog.i(TAG, "onDestroy")
        bg.shutdownNow()
        super.onDestroy()
    }

    /** Headset keys reach the focused window first: send them to the same logic as the service. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val s = PlayerHub.service
                if (s != null && s.handleMediaKey(event, "activity")) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onControllerReady(c: MediaController) {
        BgLog.i(TAG, "controller ready, items=${c.mediaItemCount} playing=${c.isPlaying}")
        controller = c
        c.addListener(controllerListener)
        playerView.player = c
        PlayerHub.service?.setVideoEnabled(true)
        updateLoopButton(c.repeatMode)
        pendingItems?.let { (items, index) ->
            pendingItems = null
            play(items, index)
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onRepeatModeChanged(repeatMode: Int) = updateLoopButton(repeatMode)

        override fun onPlayerError(error: PlaybackException) {
            BgLog.e(TAG, "playback error shown to user: ${error.errorCodeName}", error)
            Toast.makeText(this@PlayerActivity, "Ошибка воспроизведения: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ playback

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!
            BgLog.i(TAG, "open from other app: $uri")
            play(listOf(itemFor(uri, displayName(uri))), 0)
        }
    }

    private fun play(items: List<MediaItem>, startIndex: Int) {
        val c = controller
        if (c == null) {
            BgLog.d(TAG, "controller not ready, queue ${items.size} item(s)")
            pendingItems = items to startIndex
            return
        }
        BgLog.i(TAG, "play ${items.size} item(s) from index $startIndex")
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
    }

    private fun itemFor(uri: Uri, name: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setDisplayTitle(name).build())
            .build()

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
            BgLog.w(TAG, "cannot persist permission for $uri", t)
        }
    }

    private fun onFolderPicked(tree: Uri) {
        persist(tree)
        Prefs.lastFolder = tree.toString()
        bg.execute {
            val t0 = System.currentTimeMillis()
            val dir = DocumentFile.fromTreeUri(this, tree)
            val files = (dir?.listFiles() ?: emptyArray())
                .filter { f -> f.isFile && isVideo(f) }
                .sortedWith(compareBy(NaturalOrder) { it.name ?: "" })
            BgLog.i(TAG, "folder listed in ${System.currentTimeMillis() - t0}ms: ${files.size} video(s)")
            files.forEachIndexed { i, f -> BgLog.v(TAG, "  [$i] ${f.name} ${f.type} ${f.length()}b") }
            val items = files.map { itemFor(it.uri, it.name ?: it.uri.lastPathSegment ?: "?") }
            runOnUiThread {
                if (items.isEmpty()) {
                    Toast.makeText(this, "В папке нет видео", Toast.LENGTH_LONG).show()
                } else {
                    play(items, 0)
                }
            }
        }
    }

    private fun isVideo(f: DocumentFile): Boolean {
        if (f.type?.startsWith("video/") == true) return true
        val ext = f.name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in VIDEO_EXT
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (t: Throwable) {
            BgLog.w(TAG, "no display name for $uri", t)
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    // ------------------------------------------------------------------ dialogs

    private fun toggleLoop() {
        val c = controller ?: return
        val next = if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        BgLog.i(TAG, "click: loop -> ${repeatName(next)}")
        c.repeatMode = next
        updateLoopButton(next)
    }

    private fun updateLoopButton(mode: Int) {
        btnLoop.text = if (mode == Player.REPEAT_MODE_ONE) "Повтор: вкл" else "Повтор: выкл"
    }

    private fun showPlaylist() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            Toast.makeText(this, "Список пуст — выберите папку или файл", Toast.LENGTH_SHORT).show()
            return
        }
        val names = (0 until c.mediaItemCount).map { i ->
            val t = c.getMediaItemAt(i).mediaMetadata.title ?: "?"
            if (i == c.currentMediaItemIndex) "▶ $t" else t.toString()
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Видео в папке")
            .setItems(names) { _, i ->
                BgLog.i(TAG, "playlist: jump to $i")
                c.seekToDefaultPosition(i)
                c.play()
            }
            .show()
    }

    private fun showChapters() {
        val s = PlayerHub.service
        val chapters = s?.currentChapters() ?: emptyList()
        if (chapters.isEmpty()) {
            Toast.makeText(this, "В этом видео нет глав (кнопки «Глава» переключают файлы)", Toast.LENGTH_LONG).show()
            return
        }
        val cur = Chapter.indexAt(chapters, s!!.currentPositionMs())
        val names = chapters.mapIndexed { i, ch ->
            (if (i == cur) "▶ " else "") + Chapter.formatTime(ch.startMs) + "  " + ch.title.ifEmpty { "Глава ${i + 1}" }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Главы")
            .setItems(names) { _, i -> s.seekToChapter(i) }
            .show()
    }

    private fun showTimerDialog() {
        val s = PlayerHub.service
        if (s == null) {
            Toast.makeText(this, "Сначала запустите воспроизведение", Toast.LENGTH_SHORT).show()
            return
        }
        val presets = intArrayOf(15, 30, 45, 60, 90)
        val labels = presets.map { "$it мин" } + listOf("Своё значение…", "Выключить таймер")
        AlertDialog.Builder(this)
            .setTitle("Таймер сна")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < presets.size -> startTimer(s, presets[i])
                    i == presets.size -> askCustomTimer(s)
                    else -> {
                        BgLog.i(TAG, "click: timer off")
                        s.cancelSleepTimer()
                    }
                }
            }
            .show()
    }

    private fun askCustomTimer(s: PlaybackService) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.lastTimerMinutes.toString())
        }
        val box = LinearLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Минут до остановки")
            .setView(box)
            .setPositiveButton("Старт") { _, _ ->
                val m = input.text.toString().toIntOrNull()
                if (m == null || m <= 0 || m > 24 * 60) {
                    BgLog.w(TAG, "bad custom timer value '${input.text}'")
                    Toast.makeText(this, "Введите число от 1 до 1440", Toast.LENGTH_SHORT).show()
                } else {
                    startTimer(s, m)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startTimer(s: PlaybackService, minutes: Int) {
        BgLog.i(TAG, "click: timer $minutes min")
        Prefs.lastTimerMinutes = minutes
        s.startSleepTimer(minutes)
        Toast.makeText(this, "Остановка через $minutes мин", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ status line

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 1000)
        }
    }

    private fun updateStatus() {
        val parts = ArrayList<String>()
        val c = controller
        val s = PlayerHub.service
        c?.currentMediaItem?.mediaMetadata?.title?.let { parts.add(it.toString()) }
        if (s != null) {
            val ch = s.currentChapters()
            if (ch.isNotEmpty()) {
                val i = Chapter.indexAt(ch, s.currentPositionMs())
                if (i >= 0) parts.add("глава ${i + 1}/${ch.size}: ${ch[i].title}")
            }
            val rem = s.sleepRemainingMs
            if (rem >= 0) parts.add("⏾ ${Chapter.formatTime(rem)}")
        }
        status.text = parts.joinToString("  •  ")
    }
}

/** "2.mp4" before "10.mp4", case-insensitive. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val pa = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val pb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (r != 0) return r
        }
        return pa.size - pb.size
    }
}

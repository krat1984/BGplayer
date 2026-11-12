package com.bgplayer.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.bgplayer.core.BgLog
import com.bgplayer.core.RotatingFileSink
import java.io.File

class App : Application() {

    companion object {
        private const val TAG = "App"
        lateinit var fileSink: RotatingFileSink
            private set
    }

    override fun onCreate() {
        super.onCreate()
        val prefsReset = Prefs.init(this)
        fileSink = RotatingFileSink(File(filesDir, "logs"), 1_000_000, 3)
        BgLog.addSink(LogcatSink)
        BgLog.addSink(fileSink)
        BgLog.setLevel(Prefs.logLevel)

        val pi = packageManager.getPackageInfo(packageName, 0)
        BgLog.i(TAG, "==== start ${pi.versionName} (${pi.longVersionCodeCompat()}) " +
                "Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT} " +
                "${Build.MANUFACTURER} ${Build.MODEL}, log level ${BgLog.levelName(BgLog.getLevel())}")
        if (prefsReset) BgLog.w(TAG, "settings schema changed -> old settings discarded (now v${Prefs.SCHEMA})")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            BgLog.e(TAG, "CRASH in thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
}

/** Mirrors every log line to logcat (tag prefix "BG/"). */
object LogcatSink : BgLog.Sink {
    override fun write(timeMs: Long, level: Int, tag: String, message: String, error: Throwable?) {
        val text = if (error != null) message + "\n" + Log.getStackTraceString(error) else message
        Log.println(level.coerceIn(Log.VERBOSE, Log.ASSERT), "BG/$tag", text)
    }
}

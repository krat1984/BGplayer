package com.bgplayer.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bgplayer.core.Scheduler

/** Main-thread implementation of the core Scheduler. */
class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {

    override fun now(): Long = SystemClock.uptimeMillis()

    override fun schedule(r: Runnable, delayMs: Long): Scheduler.Task {
        handler.postDelayed(r, delayMs)
        return Scheduler.Task { handler.removeCallbacks(r) }
    }
}

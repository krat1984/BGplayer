package com.bgplayer.core;

/**
 * Sleep timer: after the given time playback is stopped.
 * During the last fadeMs the volume goes down linearly from 1 to 0;
 * after stopping, the volume is restored to 1 so the next playback is not silent.
 * Cancelling during fade restores the volume immediately.
 */
public final class SleepTimer {

    private static final String TAG = "SleepTimer";

    public interface Listener {
        void onVolume(float volume);

        void onExpired();

        /** remainingMs == -1 means the timer is off. */
        void onTick(long remainingMs);
    }

    public static final long DEFAULT_FADE_MS = 10_000;
    static final long TICK_MS = 1000;
    static final long FADE_TICK_MS = 200;

    private final Scheduler scheduler;
    private final Listener listener;
    private final long fadeMs;

    private long endAt = -1;
    private boolean fading;
    private Scheduler.Task task;

    public SleepTimer(Scheduler scheduler, Listener listener) {
        this(scheduler, listener, DEFAULT_FADE_MS);
    }

    public SleepTimer(Scheduler scheduler, Listener listener, long fadeMs) {
        this.scheduler = scheduler;
        this.listener = listener;
        this.fadeMs = fadeMs;
    }

    public void start(long durationMs) {
        cancelInternal();
        endAt = scheduler.now() + Math.max(0, durationMs);
        BgLog.i(TAG, "started for " + durationMs + "ms (fade " + fadeMs + "ms)");
        tick();
    }

    public void cancel() {
        if (endAt < 0) return;
        BgLog.i(TAG, "cancelled, remaining=" + remainingMs() + "ms");
        cancelInternal();
        listener.onTick(-1);
    }

    public boolean isActive() {
        return endAt >= 0;
    }

    public long remainingMs() {
        return endAt < 0 ? -1 : Math.max(0, endAt - scheduler.now());
    }

    private void cancelInternal() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (fading) {
            fading = false;
            BgLog.d(TAG, "volume restored after cancelled fade");
            listener.onVolume(1f);
        }
        endAt = -1;
    }

    private void tick() {
        task = null;
        if (endAt < 0) return;
        long remaining = endAt - scheduler.now();
        if (remaining <= 0) {
            BgLog.i(TAG, "expired -> stop playback");
            endAt = -1;
            fading = false;
            listener.onExpired();
            listener.onVolume(1f);
            listener.onTick(-1);
            return;
        }
        if (remaining <= fadeMs && fadeMs > 0) {
            if (!fading) BgLog.i(TAG, "fade out started");
            fading = true;
            float v = (float) remaining / fadeMs;
            BgLog.v(TAG, "fade volume=" + v);
            listener.onVolume(v);
        }
        listener.onTick(remaining);
        long step = fading ? FADE_TICK_MS : TICK_MS;
        long untilFade = remaining - fadeMs;
        if (!fading && untilFade > 0 && untilFade < step) step = untilFade;
        task = scheduler.schedule(this::tick, Math.min(step, remaining));
    }
}

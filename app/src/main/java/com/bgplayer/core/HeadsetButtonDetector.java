package com.bgplayer.core;

/**
 * Turns raw key events of the single headset button (HEADSETHOOK / MEDIA_PLAY_PAUSE)
 * into actions:
 *   1 click   -> PLAY_PAUSE
 *   2 clicks  -> NEXT_CHAPTER
 *   3+ clicks -> PREVIOUS_CHAPTER
 *   long press -> NEXT_CHAPTER
 *
 * A long press is recognized by any of:
 *   - a repeated ACTION_DOWN (repeatCount > 0) or FLAG_LONG_PRESS,
 *   - ACTION_UP arriving longPressMs or later after ACTION_DOWN.
 *   - once ACTION_UP has been seen at least once: the button is still held longPressMs after DOWN.
 * Until the system has delivered ACTION_UP at least once, a press without UP is treated
 * as a click after noUpFallbackMs (some devices deliver only ACTION_DOWN).
 */
public final class HeadsetButtonDetector {

    private static final String TAG = "HeadsetButton";

    public enum Action { PLAY_PAUSE, NEXT_CHAPTER, PREVIOUS_CHAPTER }

    public interface Listener {
        void onAction(Action action);
    }

    public static final long DEFAULT_MULTI_CLICK_MS = 450;
    public static final long DEFAULT_LONG_PRESS_MS = 700;
    public static final long DEFAULT_NO_UP_FALLBACK_MS = 1200;

    private final Scheduler scheduler;
    private final Listener listener;
    private final long multiClickMs;
    private final long longPressMs;
    private final long noUpFallbackMs;

    private int clicks;
    private boolean pressed;
    private boolean longHandled;
    private long downAt;
    /** Becomes true once the system delivered an ACTION_UP: then long press can be timed while held. */
    private boolean upSupported;
    private Scheduler.Task pending;

    public HeadsetButtonDetector(Scheduler scheduler, Listener listener) {
        this(scheduler, listener, DEFAULT_MULTI_CLICK_MS, DEFAULT_LONG_PRESS_MS, DEFAULT_NO_UP_FALLBACK_MS);
    }

    public HeadsetButtonDetector(Scheduler scheduler, Listener listener,
                                 long multiClickMs, long longPressMs, long noUpFallbackMs) {
        this.scheduler = scheduler;
        this.listener = listener;
        this.multiClickMs = multiClickMs;
        this.longPressMs = longPressMs;
        this.noUpFallbackMs = noUpFallbackMs;
    }

    public void onKeyDown(int repeatCount, boolean longPressFlag) {
        long now = scheduler.now();
        BgLog.v(TAG, "DOWN repeat=" + repeatCount + " longFlag=" + longPressFlag + " clicks=" + clicks
                + " pressed=" + pressed + " longHandled=" + longHandled);
        if (repeatCount == 0 && !longPressFlag) {
            if (pressed) {
                // Previous press never got its UP — close it as a click.
                BgLog.d(TAG, "DOWN while already pressed: previous press closed as click");
            }
            cancelPending();
            pressed = true;
            longHandled = false;
            downAt = now;
            clicks++;
            if (upSupported) {
                pending = scheduler.schedule(() -> {
                    pending = null;
                    if (pressed && !longHandled) fireLongPress("held " + longPressMs + "ms");
                }, longPressMs);
            } else {
                pending = scheduler.schedule(this::onNoUpTimeout, noUpFallbackMs);
            }
            return;
        }
        // Repeat or long flag: long press.
        if (!longHandled) {
            fireLongPress("repeat/flag");
        }
    }

    public void onKeyUp() {
        long now = scheduler.now();
        long held = now - downAt;
        BgLog.v(TAG, "UP held=" + held + "ms clicks=" + clicks + " longHandled=" + longHandled);
        if (!upSupported) {
            BgLog.d(TAG, "ACTION_UP is delivered by the system: long press timed while held from now on");
            upSupported = true;
        }
        if (!pressed) {
            BgLog.d(TAG, "UP without DOWN ignored");
            return;
        }
        pressed = false;
        cancelPending();
        if (longHandled) {
            longHandled = false;
            return;
        }
        if (held >= longPressMs) {
            fireLongPress("held " + held + "ms");
            longHandled = false;
            return;
        }
        pending = scheduler.schedule(this::dispatchClicks, multiClickMs);
    }

    /** Drops any in-flight sequence (e.g. when the session is released). */
    public void reset() {
        cancelPending();
        clicks = 0;
        pressed = false;
        longHandled = false;
    }

    private void fireLongPress(String why) {
        cancelPending();
        // The press that became long is not a click; earlier clicks in the sequence are dropped too.
        BgLog.i(TAG, "long press (" + why + ") -> NEXT_CHAPTER, dropped clicks=" + (clicks - 1));
        clicks = 0;
        longHandled = true;
        listener.onAction(Action.NEXT_CHAPTER);
    }

    private void onNoUpTimeout() {
        pending = null;
        if (!pressed) return;
        BgLog.w(TAG, "no UP within " + noUpFallbackMs + "ms, treating as click(s)");
        pressed = false;
        dispatchClicks();
    }

    private void dispatchClicks() {
        pending = null;
        int n = clicks;
        clicks = 0;
        if (n <= 0) return;
        Action a = n == 1 ? Action.PLAY_PAUSE : n == 2 ? Action.NEXT_CHAPTER : Action.PREVIOUS_CHAPTER;
        BgLog.i(TAG, n + " click(s) -> " + a);
        listener.onAction(a);
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }
}

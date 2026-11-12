package com.bgplayer.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central logger. All key parts of the app write here.
 * Level is configurable at runtime (from the settings UI); default is VERBOSE (maximum).
 * Sinks (logcat, rotating file) are plugged in by the Android layer.
 */
public final class BgLog {

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    /** Logging switched off completely. */
    public static final int NONE = 100;

    public static final int DEFAULT_LEVEL = VERBOSE;

    public interface Sink {
        void write(long timeMs, int level, String tag, String message, Throwable error);
    }

    private static volatile int level = DEFAULT_LEVEL;
    private static final List<Sink> sinks = new CopyOnWriteArrayList<>();

    private BgLog() {}

    public static void setLevel(int newLevel) {
        int old = level;
        level = newLevel;
        // Logged at WARN-ish visibility so the change itself is visible whenever logging is on.
        if (newLevel != NONE) {
            dispatch(INFO, "BgLog", "log level changed " + levelName(old) + " -> " + levelName(newLevel), null);
        }
    }

    public static int getLevel() {
        return level;
    }

    public static boolean isLoggable(int lvl) {
        return lvl >= level && level != NONE;
    }

    public static void addSink(Sink sink) {
        sinks.add(sink);
    }

    public static void removeSink(Sink sink) {
        sinks.remove(sink);
    }

    public static void clearSinks() {
        sinks.clear();
    }

    public static void v(String tag, String msg) { log(VERBOSE, tag, msg, null); }
    public static void d(String tag, String msg) { log(DEBUG, tag, msg, null); }
    public static void i(String tag, String msg) { log(INFO, tag, msg, null); }
    public static void w(String tag, String msg) { log(WARN, tag, msg, null); }
    public static void w(String tag, String msg, Throwable t) { log(WARN, tag, msg, t); }
    public static void e(String tag, String msg) { log(ERROR, tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { log(ERROR, tag, msg, t); }

    public static void log(int lvl, String tag, String msg, Throwable t) {
        if (!isLoggable(lvl)) return;
        dispatch(lvl, tag, msg, t);
    }

    private static void dispatch(int lvl, String tag, String msg, Throwable t) {
        long now = System.currentTimeMillis();
        for (Sink s : sinks) {
            try {
                s.write(now, lvl, tag, msg, t);
            } catch (Throwable ignored) {
                // A broken sink must never break the app.
            }
        }
    }

    public static String levelName(int lvl) {
        switch (lvl) {
            case VERBOSE: return "VERBOSE";
            case DEBUG: return "DEBUG";
            case INFO: return "INFO";
            case WARN: return "WARN";
            case ERROR: return "ERROR";
            case NONE: return "NONE";
            default: return "L" + lvl;
        }
    }

    public static char levelChar(int lvl) {
        switch (lvl) {
            case VERBOSE: return 'V';
            case DEBUG: return 'D';
            case INFO: return 'I';
            case WARN: return 'W';
            case ERROR: return 'E';
            default: return '?';
        }
    }

    /** One line format used by the file sink: "2026-11-13 00:05:01.123 D/Tag [thread]: message". */
    public static String formatLine(long timeMs, int lvl, String tag, String msg, Throwable t, String threadName) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append(fmt.format(new Date(timeMs))).append(' ')
          .append(levelChar(lvl)).append('/').append(tag)
          .append(" [").append(threadName).append("]: ").append(msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw.toString().trim());
        }
        return sb.toString();
    }
}

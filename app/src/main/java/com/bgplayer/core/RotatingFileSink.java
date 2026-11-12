package com.bgplayer.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Writes log lines to dir/bgplayer.log, rotating to bgplayer.1.log ... when maxBytes is exceeded.
 * Every line is flushed immediately so that the log survives crashes.
 */
public final class RotatingFileSink implements BgLog.Sink {

    public static final String BASE_NAME = "bgplayer";

    private final File dir;
    private final long maxBytes;
    private final int maxFiles;
    private Writer writer;
    private long currentSize;

    public RotatingFileSink(File dir, long maxBytes, int maxFiles) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.maxFiles = Math.max(1, maxFiles);
    }

    public File currentFile() {
        return new File(dir, BASE_NAME + ".log");
    }

    /** All log files, oldest first. */
    public File[] filesOldestFirst() {
        java.util.ArrayList<File> out = new java.util.ArrayList<>();
        for (int i = maxFiles - 1; i >= 1; i--) {
            File f = new File(dir, BASE_NAME + "." + i + ".log");
            if (f.exists()) out.add(f);
        }
        File cur = currentFile();
        if (cur.exists()) out.add(cur);
        return out.toArray(new File[0]);
    }

    @Override
    public synchronized void write(long timeMs, int level, String tag, String message, Throwable error) {
        String line = BgLog.formatLine(timeMs, level, tag, message, error, Thread.currentThread().getName()) + "\n";
        try {
            ensureOpen();
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (currentSize + bytes.length > maxBytes && currentSize > 0) {
                rotate();
                ensureOpen();
            }
            writer.write(line);
            writer.flush();
            currentSize += bytes.length;
        } catch (IOException ignored) {
            closeQuietly();
        }
    }

    public synchronized void clear() {
        closeQuietly();
        for (File f : filesOldestFirst()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        currentSize = 0;
    }

    private void ensureOpen() throws IOException {
        if (writer != null) return;
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File f = currentFile();
        currentSize = f.exists() ? f.length() : 0;
        writer = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
    }

    private void rotate() {
        closeQuietly();
        File oldest = new File(dir, BASE_NAME + "." + (maxFiles - 1) + ".log");
        if (maxFiles == 1) {
            //noinspection ResultOfMethodCallIgnored
            currentFile().delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        oldest.delete();
        for (int i = maxFiles - 2; i >= 1; i--) {
            File from = new File(dir, BASE_NAME + "." + i + ".log");
            if (from.exists()) {
                //noinspection ResultOfMethodCallIgnored
                from.renameTo(new File(dir, BASE_NAME + "." + (i + 1) + ".log"));
            }
        }
        //noinspection ResultOfMethodCallIgnored
        currentFile().renameTo(new File(dir, BASE_NAME + ".1.log"));
        currentSize = 0;
    }

    private void closeQuietly() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }
}

package com.bgplayer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One chapter of a video: start time and title. */
public final class Chapter {
    public final long startMs;
    public final String title;

    public Chapter(long startMs, String title) {
        this.startMs = startMs;
        this.title = title == null ? "" : title;
    }

    @Override
    public String toString() {
        return formatTime(startMs) + " " + title;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Chapter)) return false;
        Chapter c = (Chapter) o;
        return c.startMs == startMs && c.title.equals(title);
    }

    @Override
    public int hashCode() {
        return (int) (startMs * 31 + title.hashCode());
    }

    /** Sorts by start time, drops negative times and duplicates of the same start time. */
    public static List<Chapter> normalize(List<Chapter> in) {
        ArrayList<Chapter> list = new ArrayList<>();
        for (Chapter c : in) if (c.startMs >= 0) list.add(c);
        Collections.sort(list, (a, b) -> Long.compare(a.startMs, b.startMs));
        ArrayList<Chapter> out = new ArrayList<>();
        for (Chapter c : list) {
            if (!out.isEmpty() && out.get(out.size() - 1).startMs == c.startMs) continue;
            out.add(c);
        }
        return Collections.unmodifiableList(out);
    }

    /** Index of the chapter that contains positionMs, or -1 if position is before the first chapter. */
    public static int indexAt(List<Chapter> chapters, long positionMs) {
        int idx = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).startMs <= positionMs) idx = i; else break;
        }
        return idx;
    }

    public static String formatTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
                     : String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }
}

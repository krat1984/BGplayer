package com.bgplayer.core;

import java.util.List;

/**
 * Decides what "next chapter" / "previous chapter" mean at a given position.
 *
 * Next:
 *   - there is a chapter after the current position -> seek to it
 *   - otherwise (last chapter, or no chapters) -> next file if there is one
 *   - otherwise -> back to the start of the current video
 * Previous:
 *   - more than RESTART_THRESHOLD_MS into the current chapter (or video) -> its start
 *   - otherwise previous chapter
 *   - at the first chapter / no chapters -> previous file if there is one, otherwise start of video
 */
public final class ChapterNavigator {

    private static final String TAG = "ChapterNav";

    public static final long RESTART_THRESHOLD_MS = 3000;
    /** A chapter starting within this distance after the position counts as "current" (just seeked there). */
    public static final long TOLERANCE_MS = 500;

    public enum Kind { SEEK, NEXT_ITEM, PREVIOUS_ITEM }

    public static final class Action {
        public final Kind kind;
        public final long positionMs;

        private Action(Kind kind, long positionMs) {
            this.kind = kind;
            this.positionMs = positionMs;
        }

        public static Action seek(long ms) { return new Action(Kind.SEEK, ms); }
        public static Action nextItem() { return new Action(Kind.NEXT_ITEM, 0); }
        public static Action previousItem() { return new Action(Kind.PREVIOUS_ITEM, 0); }

        @Override public String toString() {
            return kind == Kind.SEEK ? "SEEK(" + positionMs + "ms)" : kind.name();
        }

        @Override public boolean equals(Object o) {
            return o instanceof Action && ((Action) o).kind == kind && ((Action) o).positionMs == positionMs;
        }

        @Override public int hashCode() { return kind.hashCode() * 31 + (int) positionMs; }
    }

    private ChapterNavigator() {}

    public static Action next(List<Chapter> chapters, long positionMs, boolean hasNextItem) {
        Action a = null;
        for (Chapter c : chapters) {
            if (c.startMs > positionMs + TOLERANCE_MS) {
                a = Action.seek(c.startMs);
                break;
            }
        }
        if (a == null) a = hasNextItem ? Action.nextItem() : Action.seek(0);
        BgLog.d(TAG, "next: pos=" + positionMs + " chapters=" + chapters.size() + " hasNext=" + hasNextItem + " -> " + a);
        return a;
    }

    public static Action previous(List<Chapter> chapters, long positionMs, boolean hasPreviousItem) {
        Action a;
        int idx = Chapter.indexAt(chapters, positionMs + TOLERANCE_MS);
        long curStart = idx >= 0 ? chapters.get(idx).startMs : 0;
        if (positionMs - curStart > RESTART_THRESHOLD_MS) {
            a = Action.seek(curStart);
        } else if (idx > 0) {
            a = Action.seek(chapters.get(idx - 1).startMs);
        } else if (idx == 0 && curStart > 0 && positionMs > TOLERANCE_MS) {
            // Area before the first chapter counts as a chapter starting at 0.
            a = Action.seek(0);
        } else {
            a = hasPreviousItem ? Action.previousItem() : Action.seek(0);
        }
        BgLog.d(TAG, "previous: pos=" + positionMs + " chapters=" + chapters.size() + " idx=" + idx
                + " hasPrev=" + hasPreviousItem + " -> " + a);
        return a;
    }
}

package com.bgplayer.core;

import java.util.ArrayList;
import java.util.List;

/** Deterministic scheduler for tests: time moves only via advance(). */
final class FakeScheduler implements Scheduler {

    private long now;
    private final List<Entry> queue = new ArrayList<>();

    private static final class Entry implements Task {
        final long at;
        final Runnable r;
        boolean cancelled;

        Entry(long at, Runnable r) { this.at = at; this.r = r; }

        @Override public void cancel() { cancelled = true; }
    }

    @Override public long now() { return now; }

    @Override public Task schedule(Runnable r, long delayMs) {
        Entry e = new Entry(now + delayMs, r);
        queue.add(e);
        return e;
    }

    /** Moves time forward, running everything that becomes due, in order. */
    void advance(long ms) {
        long target = now + ms;
        while (true) {
            Entry next = null;
            for (Entry e : queue) {
                if (!e.cancelled && e.at <= target && (next == null || e.at < next.at)) next = e;
            }
            if (next == null) break;
            queue.remove(next);
            now = next.at;
            next.r.run();
        }
        now = target;
    }
}

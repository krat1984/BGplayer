package com.bgplayer.core;

/** Clock + delayed execution. Android uses a Handler; tests use a fake. */
public interface Scheduler {

    interface Task {
        void cancel();
    }

    long now();

    Task schedule(Runnable r, long delayMs);
}

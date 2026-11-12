package com.bgplayer.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class SleepTimerTest {

    private FakeScheduler clock;
    private List<Float> volumes;
    private int expired;
    private long lastTick;
    private SleepTimer timer;

    @Before public void setUp() {
        clock = new FakeScheduler();
        volumes = new ArrayList<>();
        expired = 0;
        timer = new SleepTimer(clock, new SleepTimer.Listener() {
            @Override public void onVolume(float v) { volumes.add(v); }
            @Override public void onExpired() { expired++; }
            @Override public void onTick(long remainingMs) { lastTick = remainingMs; }
        }, 10_000);
    }

    @Test public void stopsAfterDurationAndRestoresVolume() {
        timer.start(60_000);
        clock.advance(49_000);
        assertEquals(0, expired);
        assertTrue(volumes.isEmpty()); // no fade before the last 10 s
        clock.advance(11_000);
        assertEquals(1, expired);
        assertFalse(timer.isActive());
        assertEquals(1f, volumes.get(volumes.size() - 1), 0.0001);
        assertEquals(-1, lastTick);
    }

    @Test public void fadesDownDuringLastSeconds() {
        timer.start(30_000);
        clock.advance(25_000);
        float v = volumes.get(volumes.size() - 1);
        assertTrue("volume ~0.5 but was " + v, v > 0.45f && v < 0.55f);
        for (int i = 1; i < volumes.size(); i++) assertTrue(volumes.get(i) <= volumes.get(i - 1));
    }

    @Test public void cancelDuringFadeRestoresVolume() {
        timer.start(15_000);
        clock.advance(10_000);
        timer.cancel();
        assertEquals(1f, volumes.get(volumes.size() - 1), 0.0001);
        clock.advance(60_000);
        assertEquals(0, expired);
        assertEquals(-1, lastTick);
    }

    @Test public void restartReplacesPreviousTimer() {
        timer.start(20_000);
        clock.advance(5_000);
        timer.start(60_000);
        clock.advance(20_000);
        assertEquals(0, expired);
        assertEquals(40_000, timer.remainingMs());
        clock.advance(40_000);
        assertEquals(1, expired);
    }

    @Test public void shortTimerShorterThanFade() {
        timer.start(3_000);
        clock.advance(3_000);
        assertEquals(1, expired);
    }
}

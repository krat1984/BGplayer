package com.bgplayer.core;

import static org.junit.Assert.assertEquals;

import com.bgplayer.core.HeadsetButtonDetector.Action;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class HeadsetButtonDetectorTest {

    private FakeScheduler clock;
    private List<Action> actions;
    private HeadsetButtonDetector det;

    @Before public void setUp() {
        clock = new FakeScheduler();
        actions = new ArrayList<>();
        det = new HeadsetButtonDetector(clock, actions::add);
    }

    private void click() {
        det.onKeyDown(0, false);
        clock.advance(80);
        det.onKeyUp();
    }

    @Test public void singleClickIsPlayPause() {
        click();
        clock.advance(300);
        assertEquals(Collections.emptyList(), actions); // still waiting for a possible 2nd click
        clock.advance(500);
        assertEquals(Arrays.asList(Action.PLAY_PAUSE), actions);
    }

    @Test public void doubleClickIsNextChapter() {
        click();
        clock.advance(150);
        click();
        clock.advance(1000);
        assertEquals(Arrays.asList(Action.NEXT_CHAPTER), actions);
    }

    @Test public void tripleClickIsPreviousChapter() {
        click(); clock.advance(150);
        click(); clock.advance(150);
        click(); clock.advance(1000);
        assertEquals(Arrays.asList(Action.PREVIOUS_CHAPTER), actions);
    }

    @Test public void longPressViaRepeatIsNextChapterOnce() {
        det.onKeyDown(0, false);
        clock.advance(500);
        det.onKeyDown(1, true);
        clock.advance(100);
        det.onKeyDown(2, false);
        clock.advance(400);
        det.onKeyUp();
        clock.advance(2000);
        assertEquals(Arrays.asList(Action.NEXT_CHAPTER), actions);
    }

    @Test public void longPressViaHoldTimeWithoutRepeats() {
        det.onKeyDown(0, false);
        clock.advance(900);
        det.onKeyUp();
        clock.advance(2000);
        assertEquals(Arrays.asList(Action.NEXT_CHAPTER), actions);
    }

    @Test public void longPressFiresWhileHeldOnceUpIsKnownToBeDelivered() {
        click();
        clock.advance(1000);
        actions.clear();
        det.onKeyDown(0, false);
        clock.advance(750); // still held, no repeats delivered by the system
        assertEquals(Arrays.asList(Action.NEXT_CHAPTER), actions);
        clock.advance(1000);
        det.onKeyUp();
        clock.advance(2000);
        assertEquals(Arrays.asList(Action.NEXT_CHAPTER), actions);
    }

    @Test public void deviceWithoutUpEventsStillGetsClicks() {
        det.onKeyDown(0, false);
        clock.advance(2000);
        assertEquals(Arrays.asList(Action.PLAY_PAUSE), actions);
    }

    @Test public void clicksSeparatedByPauseAreIndependent() {
        click();
        clock.advance(1000);
        click();
        clock.advance(1000);
        assertEquals(Arrays.asList(Action.PLAY_PAUSE, Action.PLAY_PAUSE), actions);
    }

    @Test public void upWithoutDownIgnored() {
        det.onKeyUp();
        clock.advance(2000);
        assertEquals(Collections.emptyList(), actions);
    }
}

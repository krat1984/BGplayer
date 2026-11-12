package com.bgplayer.core;

import static org.junit.Assert.assertEquals;

import com.bgplayer.core.ChapterNavigator.Action;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ChapterNavigatorTest {

    private static final List<Chapter> CH = Arrays.asList(
            new Chapter(0, "a"), new Chapter(60_000, "b"), new Chapter(120_000, "c"));
    private static final List<Chapter> NONE = Collections.emptyList();

    @Test public void nextGoesToFollowingChapter() {
        assertEquals(Action.seek(60_000), ChapterNavigator.next(CH, 10_000, true));
    }

    @Test public void nextRightAfterSeekToChapterStartSkipsIt() {
        // Position a bit before chapter b (seek imprecision) must not return b again.
        assertEquals(Action.seek(120_000), ChapterNavigator.next(CH, 59_800, false));
    }

    @Test public void nextInLastChapterGoesToNextFile() {
        assertEquals(Action.nextItem(), ChapterNavigator.next(CH, 130_000, true));
    }

    @Test public void nextInLastChapterWithoutNextFileRestartsVideo() {
        assertEquals(Action.seek(0), ChapterNavigator.next(CH, 130_000, false));
    }

    @Test public void nextWithoutChaptersGoesToNextFile() {
        assertEquals(Action.nextItem(), ChapterNavigator.next(NONE, 5_000, true));
    }

    @Test public void nextWithoutChaptersAndFilesRestarts() {
        assertEquals(Action.seek(0), ChapterNavigator.next(NONE, 5_000, false));
    }

    @Test public void previousFarIntoChapterRestartsChapter() {
        assertEquals(Action.seek(60_000), ChapterNavigator.previous(CH, 70_000, true));
    }

    @Test public void previousAtChapterStartGoesToPreviousChapter() {
        assertEquals(Action.seek(0), ChapterNavigator.previous(CH, 61_000, true));
    }

    @Test public void previousAtFirstChapterGoesToPreviousFile() {
        assertEquals(Action.previousItem(), ChapterNavigator.previous(CH, 1_000, true));
    }

    @Test public void previousAtFirstChapterWithoutPreviousFileRestarts() {
        assertEquals(Action.seek(0), ChapterNavigator.previous(CH, 1_000, false));
    }

    @Test public void previousWithoutChapters() {
        assertEquals(Action.seek(0), ChapterNavigator.previous(NONE, 10_000, true));
        assertEquals(Action.previousItem(), ChapterNavigator.previous(NONE, 1_000, true));
    }

    @Test public void previousBeforeFirstChapterThatStartsLate() {
        List<Chapter> late = Arrays.asList(new Chapter(30_000, "x"), new Chapter(60_000, "y"));
        assertEquals(Action.seek(0), ChapterNavigator.previous(late, 10_000, true));
        assertEquals(Action.seek(0), ChapterNavigator.previous(late, 31_000, true));
    }
}

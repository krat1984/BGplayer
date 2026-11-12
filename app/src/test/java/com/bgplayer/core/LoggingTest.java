package com.bgplayer.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class LoggingTest {

    @After public void tearDown() {
        BgLog.clearSinks();
        BgLog.setLevel(BgLog.DEFAULT_LEVEL);
    }

    @Test public void defaultLevelIsMaximum() {
        assertEquals(BgLog.VERBOSE, BgLog.DEFAULT_LEVEL);
    }

    @Test public void levelFilters() {
        List<String> got = new ArrayList<>();
        BgLog.addSink((t, lvl, tag, msg, e) -> got.add(BgLog.levelChar(lvl) + msg));
        BgLog.setLevel(BgLog.WARN);
        got.clear();
        BgLog.v("T", "v");
        BgLog.d("T", "d");
        BgLog.i("T", "i");
        BgLog.w("T", "w");
        BgLog.e("T", "e");
        assertEquals(List.of("Ww", "Ee"), got);
        BgLog.setLevel(BgLog.NONE);
        got.clear();
        BgLog.e("T", "e");
        assertTrue(got.isEmpty());
    }

    @Test public void fileSinkRotates() throws Exception {
        File dir = Files.createTempDirectory("bglog").toFile();
        RotatingFileSink sink = new RotatingFileSink(dir, 2000, 3);
        BgLog.addSink(sink);
        for (int i = 0; i < 200; i++) BgLog.i("Rot", "line number " + i + " with some padding text");
        File[] files = sink.filesOldestFirst();
        assertEquals(3, files.length);
        for (File f : files) assertTrue(f.length() <= 2000);
        String last = new String(Files.readAllBytes(files[files.length - 1].toPath()));
        assertTrue(last.contains("line number 199"));
        sink.clear();
        assertEquals(0, sink.filesOldestFirst().length);
    }
}

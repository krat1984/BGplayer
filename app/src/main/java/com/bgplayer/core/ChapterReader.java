package com.bgplayer.core;

import java.util.ArrayList;
import java.util.List;

/** Picks the right parser by file signature. Never throws: errors are logged and give "no chapters". */
public final class ChapterReader {

    private static final String TAG = "ChapterReader";

    private ChapterReader() {}

    public static List<Chapter> read(RandomAccessSource src, String name) {
        long t0 = System.nanoTime();
        try {
            List<Chapter> res;
            String format;
            if (MkvChapterParser.sniff(src)) {
                format = "matroska";
                res = MkvChapterParser.parse(src);
            } else if (Mp4ChapterParser.sniff(src)) {
                format = "mp4";
                res = Mp4ChapterParser.parse(src);
            } else {
                format = "unknown";
                res = new ArrayList<>();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            BgLog.i(TAG, "'" + name + "' format=" + format + " chapters=" + res.size() + " in " + ms + "ms");
            for (int i = 0; i < res.size(); i++) BgLog.v(TAG, "  #" + i + " " + res.get(i));
            return res;
        } catch (Throwable t) {
            BgLog.e(TAG, "failed to read chapters of '" + name + "'", t);
            return new ArrayList<>();
        }
    }
}

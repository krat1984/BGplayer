package com.bgplayer.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Real files produced by ffmpeg (see README "Тестовые файлы") plus synthetic edge cases. */
public class ChapterParsersTest {

    private static final List<Chapter> EXPECTED = Arrays.asList(
            new Chapter(0, "Вступление"),
            new Chapter(2000, "Chapter Two"),
            new Chapter(5000, "Финал"));

    @Test public void mp4WithNeroAndQuickTimeChapters() throws Exception {
        assertEquals(EXPECTED, ChapterReader.read(TestFiles.resource("chapters_both.mp4"), "both"));
    }

    @Test public void mp4WithQuickTimeChapterTrackOnly() throws Exception {
        assertEquals(EXPECTED, ChapterReader.read(TestFiles.resource("chapters_qt.mp4"), "qt"));
    }

    @Test public void mp4WithMoovAtStart() throws Exception {
        assertEquals(EXPECTED, ChapterReader.read(TestFiles.resource("chapters_faststart.mp4"), "faststart"));
    }

    @Test public void mkvChapters() throws Exception {
        assertEquals(EXPECTED, ChapterReader.read(TestFiles.resource("chapters.mkv"), "mkv"));
    }

    @Test public void webmChapters() throws Exception {
        assertEquals(EXPECTED, ChapterReader.read(TestFiles.resource("chapters.webm"), "webm"));
    }

    @Test public void mp4WithoutChapters() throws Exception {
        assertTrue(ChapterReader.read(TestFiles.resource("nochapters.mp4"), "none").isEmpty());
    }

    @Test public void garbageGivesNoChaptersAndNoException() {
        byte[] junk = new byte[1000];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 37);
        assertTrue(ChapterReader.read(new RandomAccessSource.OfBytes(junk), "junk").isEmpty());
    }

    @Test public void truncatedMp4GivesNoException() throws Exception {
        RandomAccessSource full = TestFiles.resource("chapters_faststart.mp4");
        byte[] cut = full.readBytes(0, 600);
        ChapterReader.read(new RandomAccessSource.OfBytes(cut), "cut"); // must not throw
    }

    // ---------------------------------------------------------------- synthetic MKV: Chapters after Cluster

    @Test public void mkvChaptersAfterClusterFoundViaLinearScan() {
        byte[] chapters = ebml(MkvChapterParser.ID_CHAPTERS,
                ebml(MkvChapterParser.ID_EDITION,
                        atom(0, "A"), atom(90_000_000_000L, "B")));
        byte[] cluster = ebml(MkvChapterParser.ID_CLUSTER, new byte[5000]);
        byte[] file = concat(ebml(MkvChapterParser.ID_EBML, new byte[4]),
                ebml(MkvChapterParser.ID_SEGMENT, cluster, chapters));
        List<Chapter> res = ChapterReader.read(new RandomAccessSource.OfBytes(file), "synthetic");
        assertEquals(Arrays.asList(new Chapter(0, "A"), new Chapter(90_000, "B")), res);
    }

    @Test public void mkvUnknownSizeClusterUsesSeekHead() {
        byte[] chapters = ebml(MkvChapterParser.ID_CHAPTERS,
                ebml(MkvChapterParser.ID_EDITION, atom(1_000_000_000L, "X")));
        // Segment: SeekHead, then an unknown-size cluster with junk, then Chapters.
        // SeekHead must be built knowing its own size: compute with placeholder first.
        byte[] clusterBody = new byte[300];
        byte[] unknownCluster = concat(idBytes(MkvChapterParser.ID_CLUSTER), new byte[]{(byte) 0x01, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, clusterBody);
        byte[] seekHead = seekHead(0);
        int chaptersOffset = seekHead.length + unknownCluster.length;
        seekHead = seekHead(chaptersOffset);
        byte[] segBody = concat(seekHead, unknownCluster, chapters);
        byte[] file = concat(ebml(MkvChapterParser.ID_EBML, new byte[4]),
                idBytes(MkvChapterParser.ID_SEGMENT), new byte[]{(byte) 0x01, (byte) 0xFF, (byte) 0xFF,
                        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, segBody);
        List<Chapter> res = ChapterReader.read(new RandomAccessSource.OfBytes(file), "seekhead");
        assertEquals(Arrays.asList(new Chapter(1000, "X")), res);
    }

    @Test public void mkvHiddenChapterSkippedAndDefaultEditionPreferred() {
        byte[] ed1 = ebml(MkvChapterParser.ID_EDITION, atom(0, "first-edition"));
        byte[] hidden = ebml(MkvChapterParser.ID_ATOM,
                ebml(MkvChapterParser.ID_TIME_START, uint(5_000_000_000L)),
                ebml(MkvChapterParser.ID_FLAG_HIDDEN, uint(1)));
        byte[] ed2 = ebml(MkvChapterParser.ID_EDITION,
                ebml(MkvChapterParser.ID_EDITION_DEFAULT, uint(1)), atom(0, "default"), hidden);
        byte[] file = concat(ebml(MkvChapterParser.ID_EBML, new byte[4]),
                ebml(MkvChapterParser.ID_SEGMENT, ebml(MkvChapterParser.ID_CHAPTERS, ed1, ed2)));
        assertEquals(Arrays.asList(new Chapter(0, "default")),
                ChapterReader.read(new RandomAccessSource.OfBytes(file), "editions"));
    }

    private static byte[] seekHead(long chaptersPos) {
        return ebml(MkvChapterParser.ID_SEEKHEAD, ebml(MkvChapterParser.ID_SEEK,
                ebml(MkvChapterParser.ID_SEEK_ID, idBytes(MkvChapterParser.ID_CHAPTERS)),
                ebml(MkvChapterParser.ID_SEEK_POSITION, uint8(chaptersPos))));
    }

    private static byte[] atom(long startNs, String title) {
        return ebml(MkvChapterParser.ID_ATOM,
                ebml(MkvChapterParser.ID_TIME_START, uint(startNs)),
                ebml(MkvChapterParser.ID_DISPLAY, ebml(MkvChapterParser.ID_STRING, title.getBytes(StandardCharsets.UTF_8))));
    }

    static byte[] ebml(long id, byte[]... children) {
        byte[] body = concat(children);
        // 8-byte size VINT
        byte[] size = new byte[8];
        size[0] = 0x01;
        long v = body.length;
        for (int i = 7; i >= 1; i--) { size[i] = (byte) (v & 0xFF); v >>= 8; }
        return concat(idBytes(id), size, body);
    }

    static byte[] idBytes(long id) {
        int len = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
        byte[] b = new byte[len];
        for (int i = len - 1; i >= 0; i--) { b[i] = (byte) (id & 0xFF); id >>= 8; }
        return b;
    }

    static byte[] uint(long v) {
        return uint8(v);
    }

    static byte[] uint8(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) (v & 0xFF); v >>= 8; }
        return b;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}

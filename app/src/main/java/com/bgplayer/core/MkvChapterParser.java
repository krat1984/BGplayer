package com.bgplayer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads chapters from Matroska / WebM files (Segment/Chapters/EditionEntry/ChapterAtom).
 * Uses SeekHead to jump to Chapters if they are stored after the clusters.
 * Uses the default edition (or the first one); hidden/disabled chapters are skipped.
 */
public final class MkvChapterParser {

    private static final String TAG = "MkvChapters";

    static final long ID_EBML = 0x1A45DFA3L;
    static final long ID_SEGMENT = 0x18538067L;
    static final long ID_SEEKHEAD = 0x114D9B74L;
    static final long ID_SEEK = 0x4DBBL;
    static final long ID_SEEK_ID = 0x53ABL;
    static final long ID_SEEK_POSITION = 0x53ACL;
    static final long ID_CHAPTERS = 0x1043A770L;
    static final long ID_EDITION = 0x45B9L;
    static final long ID_EDITION_DEFAULT = 0x45DBL;
    static final long ID_EDITION_HIDDEN = 0x45BDL;
    static final long ID_ATOM = 0xB6L;
    static final long ID_TIME_START = 0x91L;
    static final long ID_FLAG_HIDDEN = 0x98L;
    static final long ID_FLAG_ENABLED = 0x4598L;
    static final long ID_DISPLAY = 0x80L;
    static final long ID_STRING = 0x85L;
    static final long ID_CLUSTER = 0x1F43B675L;

    static final long MAX_CHAPTERS_BYTES = 16L * 1024 * 1024;
    static final int MAX_TOP_LEVEL_ELEMENTS = 200_000;

    private MkvChapterParser() {}

    public static boolean sniff(RandomAccessSource src) throws IOException {
        if (src.size() < 4) return false;
        byte[] h = src.readBytes(0, 4);
        return (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
    }

    /** Element header read from the source. */
    static final class Header {
        long id;
        long size;          // -1 if unknown
        long dataStart;
    }

    public static List<Chapter> parse(RandomAccessSource src) throws IOException {
        long fileSize = src.size();
        long pos = 0;
        // Find the Segment.
        Header seg = null;
        while (pos < fileSize) {
            Header h = readHeader(src, pos, fileSize);
            if (h == null) return new ArrayList<>();
            if (h.id == ID_SEGMENT) {
                seg = h;
                break;
            }
            if (h.size < 0) return new ArrayList<>();
            pos = h.dataStart + h.size;
        }
        if (seg == null) {
            BgLog.d(TAG, "no Segment");
            return new ArrayList<>();
        }
        long segStart = seg.dataStart;
        long segEnd = seg.size < 0 ? fileSize : Math.min(fileSize, seg.dataStart + seg.size);

        long chaptersPos = -1;
        pos = segStart;
        int count = 0;
        while (pos < segEnd && count++ < MAX_TOP_LEVEL_ELEMENTS) {
            Header h = readHeader(src, pos, segEnd);
            if (h == null) break;
            if (h.id == ID_CHAPTERS) {
                return parseChaptersElement(src, h);
            }
            if (h.id == ID_SEEKHEAD && h.size > 0 && h.size < 1024 * 1024) {
                long p = findInSeekHead(src, h, ID_CHAPTERS);
                if (p >= 0) chaptersPos = segStart + p;
                if (chaptersPos >= 0) {
                    BgLog.v(TAG, "SeekHead: Chapters at " + chaptersPos);
                    Header ch = readHeader(src, chaptersPos, segEnd);
                    if (ch != null && ch.id == ID_CHAPTERS) return parseChaptersElement(src, ch);
                    BgLog.w(TAG, "SeekHead points to non-Chapters element at " + chaptersPos);
                }
            }
            if (h.size < 0) {
                BgLog.d(TAG, "element 0x" + Long.toHexString(h.id) + " has unknown size, stop scan");
                break;
            }
            pos = h.dataStart + h.size;
        }
        BgLog.d(TAG, "no Chapters element");
        return new ArrayList<>();
    }

    private static long findInSeekHead(RandomAccessSource src, Header seekHead, long wantedId) throws IOException {
        byte[] d = src.readBytes(seekHead.dataStart, (int) seekHead.size);
        RandomAccessSource mem = new RandomAccessSource.OfBytes(d);
        long p = 0;
        while (p < d.length) {
            Header seek = readHeader(mem, p, d.length);
            if (seek == null || seek.size < 0) break;
            if (seek.id == ID_SEEK) {
                long id = -1, position = -1;
                long q = seek.dataStart;
                long end = seek.dataStart + seek.size;
                while (q < end) {
                    Header c = readHeader(mem, q, end);
                    if (c == null || c.size < 0) break;
                    if (c.id == ID_SEEK_ID) id = readUInt(mem, c);
                    else if (c.id == ID_SEEK_POSITION) position = readUInt(mem, c);
                    q = c.dataStart + c.size;
                }
                if (id == wantedId) return position;
            }
            p = seek.dataStart + seek.size;
        }
        return -1;
    }

    private static List<Chapter> parseChaptersElement(RandomAccessSource src, Header chapters) throws IOException {
        if (chapters.size < 0 || chapters.size > MAX_CHAPTERS_BYTES) {
            BgLog.w(TAG, "Chapters element size unsupported: " + chapters.size);
            return new ArrayList<>();
        }
        byte[] d = src.readBytes(chapters.dataStart, (int) chapters.size);
        RandomAccessSource mem = new RandomAccessSource.OfBytes(d);
        List<Chapter> first = null, def = null;
        long p = 0;
        while (p < d.length) {
            Header ed = readHeader(mem, p, d.length);
            if (ed == null || ed.size < 0) break;
            if (ed.id == ID_EDITION) {
                List<Chapter> list = new ArrayList<>();
                boolean[] flags = new boolean[2]; // [isDefault, isHidden]
                parseEdition(mem, ed, list, flags);
                BgLog.v(TAG, "edition: " + list.size() + " chapters default=" + flags[0] + " hidden=" + flags[1]);
                if (first == null) first = list;
                if (flags[0] && def == null) def = list;
            }
            p = ed.dataStart + ed.size;
        }
        List<Chapter> chosen = def != null ? def : first;
        if (chosen == null) return new ArrayList<>();
        BgLog.i(TAG, "Matroska chapters: " + chosen.size());
        return Chapter.normalize(chosen);
    }

    private static void parseEdition(RandomAccessSource mem, Header ed, List<Chapter> out, boolean[] flags) throws IOException {
        long p = ed.dataStart, end = ed.dataStart + ed.size;
        while (p < end) {
            Header c = readHeader(mem, p, end);
            if (c == null || c.size < 0) break;
            if (c.id == ID_EDITION_DEFAULT) flags[0] = readUInt(mem, c) != 0;
            else if (c.id == ID_EDITION_HIDDEN) flags[1] = readUInt(mem, c) != 0;
            else if (c.id == ID_ATOM) parseAtom(mem, c, out);
            p = c.dataStart + c.size;
        }
    }

    private static void parseAtom(RandomAccessSource mem, Header atom, List<Chapter> out) throws IOException {
        long start = -1;
        boolean hidden = false, enabled = true;
        String title = null;
        List<Header> nested = new ArrayList<>();
        long p = atom.dataStart, end = atom.dataStart + atom.size;
        while (p < end) {
            Header c = readHeader(mem, p, end);
            if (c == null || c.size < 0) break;
            if (c.id == ID_TIME_START) start = readUInt(mem, c);
            else if (c.id == ID_FLAG_HIDDEN) hidden = readUInt(mem, c) != 0;
            else if (c.id == ID_FLAG_ENABLED) enabled = readUInt(mem, c) != 0;
            else if (c.id == ID_DISPLAY && title == null) title = readDisplayString(mem, c);
            else if (c.id == ID_ATOM) nested.add(c);
            p = c.dataStart + c.size;
        }
        if (start >= 0 && !hidden && enabled) out.add(new Chapter(start / 1_000_000, title));
        for (Header n : nested) parseAtom(mem, n, out);
    }

    private static String readDisplayString(RandomAccessSource mem, Header display) throws IOException {
        long p = display.dataStart, end = display.dataStart + display.size;
        while (p < end) {
            Header c = readHeader(mem, p, end);
            if (c == null || c.size < 0) break;
            if (c.id == ID_STRING) {
                byte[] b = mem.readBytes(c.dataStart, (int) c.size);
                int n = b.length;
                while (n > 0 && b[n - 1] == 0) n--;
                return new String(b, 0, n, StandardCharsets.UTF_8);
            }
            p = c.dataStart + c.size;
        }
        return null;
    }

    // ---------------------------------------------------------------- EBML primitives

    /** Reads an element header at pos. Returns null if broken. size == -1 means "unknown". */
    static Header readHeader(RandomAccessSource src, long pos, long limit) throws IOException {
        if (pos >= limit) return null;
        int avail = (int) Math.min(16, limit - pos);
        byte[] b = new byte[avail];
        int n = src.read(pos, b, 0, avail);
        if (n <= 0) return null;
        int idLen = vintLength(b[0]);
        if (idLen < 1 || idLen > 4 || idLen >= n) return null;
        long id = 0;
        for (int i = 0; i < idLen; i++) id = (id << 8) | (b[i] & 0xFF);
        int sizeLen = vintLength(b[idLen]);
        if (sizeLen < 1 || idLen + sizeLen > n) return null;
        long size = b[idLen] & (0xFF >> sizeLen);
        boolean allOnes = size == (0xFF >> sizeLen);
        for (int i = 1; i < sizeLen; i++) {
            int v = b[idLen + i] & 0xFF;
            if (v != 0xFF) allOnes = false;
            size = (size << 8) | v;
        }
        Header h = new Header();
        h.id = id;
        h.size = allOnes ? -1 : size;
        h.dataStart = pos + idLen + sizeLen;
        if (h.size >= 0 && h.dataStart + h.size > limit) {
            // Element claims more data than its parent has: clamp (common for truncated files).
            h.size = limit - h.dataStart;
        }
        return h;
    }

    /** Length of a VINT from its first byte (1..8), or 0 if invalid. */
    static int vintLength(byte first) {
        int v = first & 0xFF;
        for (int i = 0; i < 8; i++) if ((v & (0x80 >> i)) != 0) return i + 1;
        return 0;
    }

    static long readUInt(RandomAccessSource src, Header h) throws IOException {
        if (h.size <= 0 || h.size > 8) return 0;
        byte[] b = src.readBytes(h.dataStart, (int) h.size);
        long v = 0;
        for (byte x : b) v = (v << 8) | (x & 0xFF);
        return v;
    }
}

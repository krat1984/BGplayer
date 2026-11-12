package com.bgplayer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads chapters from MP4 / MOV / M4V files.
 * Supports:
 *  - Nero chapters: moov/udta/chpl
 *  - QuickTime chapter track: a text track referenced from another track via tref/chap
 * Nero chapters are preferred when both are present.
 */
public final class Mp4ChapterParser {

    private static final String TAG = "Mp4Chapters";
    /** moov boxes bigger than this are not read (protects memory on huge files). */
    static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;
    static final int MAX_CHAPTERS = 10_000;
    static final int MAX_TITLE_BYTES = 4096;

    private Mp4ChapterParser() {}

    /** Quick check: does the file look like an ISO-BMFF (MP4/MOV) file? */
    public static boolean sniff(RandomAccessSource src) throws IOException {
        if (src.size() < 12) return false;
        byte[] h = src.readBytes(0, 12);
        String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
        return type.equals("ftyp") || type.equals("moov") || type.equals("mdat")
                || type.equals("free") || type.equals("wide") || type.equals("skip");
    }

    public static List<Chapter> parse(RandomAccessSource src) throws IOException {
        long size = src.size();
        long pos = 0;
        while (pos + 8 <= size) {
            byte[] h = src.readBytes(pos, 8);
            long boxSize = u32(h, 0);
            String type = fourcc(h, 4);
            int headerLen = 8;
            if (boxSize == 1) {
                byte[] l = src.readBytes(pos + 8, 8);
                boxSize = u64(l, 0);
                headerLen = 16;
            } else if (boxSize == 0) {
                boxSize = size - pos;
            }
            if (boxSize < headerLen) {
                BgLog.w(TAG, "broken top-level box '" + type + "' size=" + boxSize + " at " + pos);
                return new ArrayList<>();
            }
            BgLog.v(TAG, "top-level box '" + type + "' at " + pos + " size=" + boxSize);
            if (type.equals("moov")) {
                long payload = boxSize - headerLen;
                if (payload > MAX_MOOV_BYTES) {
                    BgLog.w(TAG, "moov too big (" + payload + " bytes), chapters skipped");
                    return new ArrayList<>();
                }
                byte[] moov = src.readBytes(pos + headerLen, (int) payload);
                return parseMoov(moov, src);
            }
            pos += boxSize;
        }
        BgLog.d(TAG, "no moov box found");
        return new ArrayList<>();
    }

    // ---------------------------------------------------------------- moov

    private static final class Track {
        long id;
        long timescale;
        String handler = "";
        List<Long> chapRefs = new ArrayList<>();
        long[] sttsCounts, sttsDeltas;
        long[] stscFirstChunk, stscSamplesPerChunk;
        long fixedSampleSize;
        long[] sampleSizes;
        long sampleCount;
        long[] chunkOffsets;
    }

    private static List<Chapter> parseMoov(byte[] moov, RandomAccessSource src) throws IOException {
        List<Chapter> nero = null;
        List<Track> tracks = new ArrayList<>();
        for (Box b : children(moov, 0, moov.length)) {
            if (b.type.equals("udta")) {
                Box chpl = findChild(moov, b, "chpl");
                if (chpl != null) nero = parseChpl(moov, chpl);
            } else if (b.type.equals("trak")) {
                Track t = parseTrak(moov, b);
                if (t != null) tracks.add(t);
            }
        }
        if (nero != null && !nero.isEmpty()) {
            BgLog.i(TAG, "Nero chapters: " + nero.size());
            return Chapter.normalize(nero);
        }
        Map<Long, Track> byId = new HashMap<>();
        for (Track t : tracks) byId.put(t.id, t);
        for (Track t : tracks) {
            for (Long ref : t.chapRefs) {
                Track ct = byId.get(ref);
                if (ct == null) {
                    BgLog.w(TAG, "tref/chap points to missing track " + ref);
                    continue;
                }
                List<Chapter> qt = readTextTrack(ct, src);
                if (!qt.isEmpty()) {
                    BgLog.i(TAG, "QuickTime chapter track " + ct.id + ": " + qt.size() + " chapters");
                    return Chapter.normalize(qt);
                }
            }
        }
        BgLog.d(TAG, "no chapters in moov (tracks=" + tracks.size() + ")");
        return new ArrayList<>();
    }

    static List<Chapter> parseChpl(byte[] d, Box chpl) {
        List<Chapter> out = new ArrayList<>();
        int p = chpl.payloadStart;
        int end = chpl.end;
        if (p + 4 > end) return out;
        int version = d[p] & 0xFF;
        p += 4; // version + flags
        if (version >= 1) p += 4; // reserved
        if (p + 1 > end) return out;
        int count = d[p] & 0xFF;
        p += 1;
        for (int i = 0; i < count && out.size() < MAX_CHAPTERS; i++) {
            if (p + 9 > end) {
                BgLog.w(TAG, "chpl truncated at chapter " + i);
                break;
            }
            long start100ns = u64(d, p);
            p += 8;
            int len = d[p] & 0xFF;
            p += 1;
            if (p + len > end) {
                BgLog.w(TAG, "chpl title truncated at chapter " + i);
                break;
            }
            String title = new String(d, p, len, StandardCharsets.UTF_8);
            p += len;
            out.add(new Chapter(start100ns / 10_000, title));
        }
        return out;
    }

    private static Track parseTrak(byte[] d, Box trak) {
        Track t = new Track();
        for (Box b : children(d, trak.payloadStart, trak.end)) {
            switch (b.type) {
                case "tkhd": {
                    int p = b.payloadStart;
                    int version = d[p] & 0xFF;
                    p += 4;
                    p += version == 1 ? 16 : 8;
                    if (p + 4 <= b.end) t.id = u32(d, p);
                    break;
                }
                case "tref": {
                    for (Box r : children(d, b.payloadStart, b.end)) {
                        if (r.type.equals("chap")) {
                            for (int p = r.payloadStart; p + 4 <= r.end; p += 4) t.chapRefs.add(u32(d, p));
                        }
                    }
                    break;
                }
                case "mdia":
                    parseMdia(d, b, t);
                    break;
                default:
                    break;
            }
        }
        return t;
    }

    private static void parseMdia(byte[] d, Box mdia, Track t) {
        for (Box b : children(d, mdia.payloadStart, mdia.end)) {
            if (b.type.equals("mdhd")) {
                int p = b.payloadStart;
                int version = d[p] & 0xFF;
                p += 4;
                p += version == 1 ? 16 : 8;
                if (p + 4 <= b.end) t.timescale = u32(d, p);
            } else if (b.type.equals("hdlr")) {
                int p = b.payloadStart + 8; // version/flags + pre_defined
                if (p + 4 <= b.end) t.handler = fourcc(d, p);
            } else if (b.type.equals("minf")) {
                Box stbl = findChild(d, b, "stbl");
                if (stbl != null) parseStbl(d, stbl, t);
            }
        }
    }

    private static void parseStbl(byte[] d, Box stbl, Track t) {
        for (Box b : children(d, stbl.payloadStart, stbl.end)) {
            int p = b.payloadStart + 4;
            switch (b.type) {
                case "stts": {
                    int n = (int) Math.min(u32(d, p), (b.end - p - 4) / 8);
                    p += 4;
                    t.sttsCounts = new long[n];
                    t.sttsDeltas = new long[n];
                    for (int i = 0; i < n; i++, p += 8) {
                        t.sttsCounts[i] = u32(d, p);
                        t.sttsDeltas[i] = u32(d, p + 4);
                    }
                    break;
                }
                case "stsc": {
                    int n = (int) Math.min(u32(d, p), (b.end - p - 4) / 12);
                    p += 4;
                    t.stscFirstChunk = new long[n];
                    t.stscSamplesPerChunk = new long[n];
                    for (int i = 0; i < n; i++, p += 12) {
                        t.stscFirstChunk[i] = u32(d, p);
                        t.stscSamplesPerChunk[i] = u32(d, p + 4);
                    }
                    break;
                }
                case "stsz": {
                    t.fixedSampleSize = u32(d, p);
                    t.sampleCount = u32(d, p + 4);
                    p += 8;
                    if (t.fixedSampleSize == 0) {
                        int n = (int) Math.min(t.sampleCount, (b.end - p) / 4);
                        t.sampleSizes = new long[n];
                        for (int i = 0; i < n; i++, p += 4) t.sampleSizes[i] = u32(d, p);
                    }
                    break;
                }
                case "stco": {
                    int n = (int) Math.min(u32(d, p), (b.end - p - 4) / 4);
                    p += 4;
                    t.chunkOffsets = new long[n];
                    for (int i = 0; i < n; i++, p += 4) t.chunkOffsets[i] = u32(d, p);
                    break;
                }
                case "co64": {
                    int n = (int) Math.min(u32(d, p), (b.end - p - 4) / 8);
                    p += 4;
                    t.chunkOffsets = new long[n];
                    for (int i = 0; i < n; i++, p += 8) t.chunkOffsets[i] = u64(d, p);
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static List<Chapter> readTextTrack(Track t, RandomAccessSource src) throws IOException {
        List<Chapter> out = new ArrayList<>();
        if (t.timescale <= 0 || t.sttsCounts == null || t.stscFirstChunk == null || t.chunkOffsets == null) {
            BgLog.w(TAG, "chapter track " + t.id + " incomplete (handler=" + t.handler + ")");
            return out;
        }
        long total = Math.min(t.sampleCount, MAX_CHAPTERS);
        // sample start times
        long[] times = new long[(int) total];
        long time = 0;
        int s = 0;
        for (int e = 0; e < t.sttsCounts.length && s < total; e++) {
            for (long k = 0; k < t.sttsCounts[e] && s < total; k++) {
                times[s++] = time;
                time += t.sttsDeltas[e];
            }
        }
        // sample offsets via stsc + chunk offsets
        int sample = 0;
        for (int chunk = 1; chunk <= t.chunkOffsets.length && sample < total; chunk++) {
            long perChunk = samplesPerChunk(t, chunk);
            long off = t.chunkOffsets[chunk - 1];
            for (long k = 0; k < perChunk && sample < total; k++) {
                long sz = t.fixedSampleSize != 0 ? t.fixedSampleSize
                        : (t.sampleSizes != null && sample < t.sampleSizes.length ? t.sampleSizes[sample] : 0);
                if (sz >= 2 && sample < s) {
                    String title = readTextSample(src, off, sz);
                    out.add(new Chapter(times[sample] * 1000 / t.timescale, title));
                }
                off += sz;
                sample++;
            }
        }
        return out;
    }

    private static long samplesPerChunk(Track t, int chunk) {
        long res = 0;
        for (int i = 0; i < t.stscFirstChunk.length; i++) {
            if (t.stscFirstChunk[i] <= chunk) res = t.stscSamplesPerChunk[i]; else break;
        }
        return res;
    }

    private static String readTextSample(RandomAccessSource src, long off, long size) throws IOException {
        byte[] lenB = src.readBytes(off, 2);
        int len = ((lenB[0] & 0xFF) << 8) | (lenB[1] & 0xFF);
        len = (int) Math.min(Math.min(len, size - 2), MAX_TITLE_BYTES);
        if (len <= 0) return "";
        byte[] txt = src.readBytes(off + 2, len);
        if (len >= 2 && (txt[0] & 0xFF) == 0xFE && (txt[1] & 0xFF) == 0xFF) {
            return new String(txt, 2, len - 2, StandardCharsets.UTF_16BE);
        }
        if (len >= 2 && (txt[0] & 0xFF) == 0xFF && (txt[1] & 0xFF) == 0xFE) {
            return new String(txt, 2, len - 2, StandardCharsets.UTF_16LE);
        }
        return new String(txt, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- box helpers

    static final class Box {
        final String type;
        final int start, payloadStart, end;

        Box(String type, int start, int payloadStart, int end) {
            this.type = type;
            this.start = start;
            this.payloadStart = payloadStart;
            this.end = end;
        }
    }

    static List<Box> children(byte[] d, int from, int to) {
        List<Box> out = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            long size = u32(d, p);
            String type = fourcc(d, p + 4);
            int header = 8;
            if (size == 1) {
                if (p + 16 > to) break;
                size = u64(d, p + 8);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                BgLog.w(TAG, "broken box '" + type + "' size=" + size + " at " + p);
                break;
            }
            out.add(new Box(type, p, p + header, (int) (p + size)));
            p += (int) size;
        }
        return out;
    }

    private static Box findChild(byte[] d, Box parent, String type) {
        for (Box b : children(d, parent.payloadStart, parent.end)) if (b.type.equals(type)) return b;
        return null;
    }

    static long u32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    static long u64(byte[] d, int p) {
        return (u32(d, p) << 32) | u32(d, p + 4);
    }

    static String fourcc(byte[] d, int p) {
        return new String(d, p, 4, StandardCharsets.ISO_8859_1);
    }
}

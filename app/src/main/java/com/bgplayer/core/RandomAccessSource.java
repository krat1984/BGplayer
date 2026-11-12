package com.bgplayer.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Minimal random-access byte source used by chapter parsers. */
public interface RandomAccessSource {

    long size() throws IOException;

    /** Reads up to len bytes at position; returns number read, or -1 at end. */
    int read(long position, byte[] buf, int off, int len) throws IOException;

    default void readFully(long position, byte[] buf, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = read(position + done, buf, off + done, len - done);
            if (n <= 0) throw new EOFException("EOF at " + (position + done));
            done += n;
        }
    }

    default byte[] readBytes(long position, int len) throws IOException {
        byte[] b = new byte[len];
        readFully(position, b, 0, len);
        return b;
    }

    final class OfBytes implements RandomAccessSource {
        private final byte[] data;

        public OfBytes(byte[] data) { this.data = data; }

        @Override public long size() { return data.length; }

        @Override public int read(long position, byte[] buf, int off, int len) {
            if (position >= data.length) return -1;
            int n = (int) Math.min(len, data.length - position);
            System.arraycopy(data, (int) position, buf, off, n);
            return n;
        }
    }

    final class OfChannel implements RandomAccessSource {
        private final FileChannel channel;

        public OfChannel(FileChannel channel) { this.channel = channel; }

        @Override public long size() throws IOException { return channel.size(); }

        @Override public int read(long position, byte[] buf, int off, int len) throws IOException {
            return channel.read(ByteBuffer.wrap(buf, off, len), position);
        }
    }
}

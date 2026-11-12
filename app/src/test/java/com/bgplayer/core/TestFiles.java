package com.bgplayer.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class TestFiles {
    private TestFiles() {}

    static RandomAccessSource resource(String name) throws IOException {
        try (InputStream in = TestFiles.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing test resource " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new RandomAccessSource.OfBytes(out.toByteArray());
        }
    }
}

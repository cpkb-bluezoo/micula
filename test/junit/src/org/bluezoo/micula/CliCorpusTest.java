/*
 * CliCorpusTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 */

package org.bluezoo.micula;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Decodes streams produced by the reference brotli CLI.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CliCorpusTest {

    @Test
    public void testCliQ1Hello() throws Exception {
        assertDecodes("cli_0_q1");
    }

    @Test
    public void testCliQ5HelloMicula() throws Exception {
        assertDecodes("cli_1_q5");
    }

    @Test
    public void testCliQ5Repeats() throws Exception {
        assertDecodes("cli_2_q5");
    }

    @Test
    public void testCliQ5Fox() throws Exception {
        assertDecodes("cli_3_q5");
    }

    @Test
    public void testCliQ11Fox() throws Exception {
        assertDecodes("cli_4_q11");
    }

    @Test
    public void testCliQ11FoxChunked() throws Exception {
        byte[] compressed = readResource("cli_4_q11.br");
        byte[] expected = readResource("cli_4_q11.txt");
        byte[] decoded = BrotliDecoderTest.decodeChunked(compressed, 1);
        assertArrayEquals(expected, decoded);
    }

    private void assertDecodes(String base) throws Exception {
        byte[] compressed = readResource(base + ".br");
        byte[] expected = readResource(base + ".txt");
        assertTrue(compressed.length > 0);
        byte[] decoded = BrotliDecoderTest.decodeAll(compressed);
        assertArrayEquals(base, expected, decoded);
    }

    private byte[] readResource(String name) throws Exception {
        // Prefer classpath, fall back to source tree for ant copy edge cases
        java.net.URL url = getClass().getResource(name);
        if (url != null) {
            Path path = Paths.get(url.toURI());
            return readPath(path);
        }
        Path path = Paths.get("test/junit/src/org/bluezoo/micula/" + name);
        return readPath(path);
    }

    private byte[] readPath(Path path) throws Exception {
        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        ByteBuffer buf = ByteBuffer.allocate((int) ch.size());
        while (buf.hasRemaining()) {
            int n = ch.read(buf);
            if (n < 0) {
                break;
            }
        }
        ch.close();
        return buf.array();
    }
}

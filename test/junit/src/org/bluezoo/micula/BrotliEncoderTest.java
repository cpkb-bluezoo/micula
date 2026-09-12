/*
 * BrotliEncoderTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 */

package org.bluezoo.micula;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Encoder tests for qualities 0–2 and API guards.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class BrotliEncoderTest {

    @Test
    public void testQuality1RoundTripShort() throws Exception {
        byte[] original = "Hello, Micula!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encode(original, 1);
        assertArrayEquals(original, BrotliDecoderTest.decodeAll(compressed));
    }

    @Test
    public void testQuality1RoundTripRepetitive() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("abcabcabcxyz");
        }
        byte[] original = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encode(original, 1);
        assertTrue("expected compression", compressed.length < original.length);
        assertArrayEquals(original, BrotliDecoderTest.decodeAll(compressed));
    }

    @Test
    public void testQuality2RoundTripDictionaryWords() throws Exception {
        // "that", "with", "from", "time" are identity dictionary words (len 4)
        byte[] original = ("time and time again with that from with that time")
            .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encode(original, 2);
        assertArrayEquals(original, BrotliDecoderTest.decodeAll(compressed));
    }

    @Test
    public void testQuality1ChunkedDecode() throws Exception {
        byte[] original = "abcdefghijklmnopqrstuvwxyz0123456789 repeated text "
            .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encode(original, 1);
        assertArrayEquals(original, BrotliDecoderTest.decodeChunked(compressed, 1));
        assertArrayEquals(original, BrotliDecoderTest.decodeChunked(compressed, 3));
        assertArrayEquals(original, BrotliDecoderTest.decodeChunked(compressed, 17));
    }

    @Test
    public void testSetQuality5Throws() throws Exception {
        CollectingSink sink = new CollectingSink();
        BrotliEncoder enc = new BrotliEncoder(sink);
        enc.setQuality(5);
        try {
            enc.receive(ByteBuffer.wrap(new byte[] { 1 }));
            fail("expected not implemented");
        } catch (BrotliException e) {
            assertTrue(e.getMessage().toLowerCase().contains("not implemented"));
        }
    }

    @Test
    public void testSetQualityOutOfRange() {
        CollectingSink sink = new CollectingSink();
        BrotliEncoder enc = new BrotliEncoder(sink);
        try {
            enc.setQuality(12);
            fail("expected IAE");
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            enc.setQuality(-1);
            fail("expected IAE");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testQuality0StillWorks() throws Exception {
        byte[] original = "quality zero".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encode(original, 0);
        assertArrayEquals(original, BrotliDecoderTest.decodeAll(compressed));
    }

    @Test
    public void testBrotliWriterCompressed() throws Exception {
        byte[] original = "writer helper q1".getBytes(StandardCharsets.UTF_8);
        CollectingSink sink = new CollectingSink();
        BrotliWriter.write(ByteBuffer.wrap(original), sink, 1);
        assertArrayEquals(original, BrotliDecoderTest.decodeAll(sink.toByteArray()));
    }

    static byte[] encode(byte[] original, int quality) throws BrotliException {
        CollectingSink sink = new CollectingSink();
        BrotliEncoder enc = new BrotliEncoder(sink);
        enc.setQuality(quality);
        enc.setWindowBits(22);
        if (original.length > 0) {
            enc.receive(ByteBuffer.wrap(original));
        }
        enc.close();
        return sink.toByteArray();
    }
}

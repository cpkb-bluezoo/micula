/*
 * BrotliDecoderTest.java
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
 * Decoder tests: quality-0 round-trip, empty stream, chunk invariance, limits.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class BrotliDecoderTest {

    @Test
    public void testEmptyStream() throws Exception {
        // WBITS=16 (0), then ISLAST=1, ISLASTEMPTY=1
        byte[] compressed = new byte[] { 0x06 }; // 0 + 11xxxxxx → actually need careful bits
        // Bit pattern: WBITS bit0=0 → 16; then ISLAST=1, ISLASTEMPTY=1
        // First byte LSB-first: bit0=0 (wbits), bit1=1 (islast), bit2=1 (empty) → 0b00000110 = 0x06
        CollectingHandler handler = new CollectingHandler();
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(handler);
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        decoder.receive(ByteBuffer.wrap(compressed));
        decoder.close();
        assertEquals(0, handler.toByteArray().length);
        assertTrue(handler.isEnded());
        assertEquals(16, handler.getWindowBits());
    }

    @Test
    public void testQuality0RoundTrip() throws Exception {
        byte[] original = "Hello, Micula! This is a Brotli round-trip test.\n"
            .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encodeQuality0(original);
        byte[] decoded = decodeAll(compressed);
        assertArrayEquals(original, decoded);
    }

    @Test
    public void testQuality0EmptyInput() throws Exception {
        byte[] compressed = encodeQuality0(new byte[0]);
        byte[] decoded = decodeAll(compressed);
        assertEquals(0, decoded.length);
    }

    @Test
    public void testChunkInvarianceOneByte() throws Exception {
        byte[] original = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encodeQuality0(original);
        byte[] decoded = decodeChunked(compressed, 1);
        assertArrayEquals(original, decoded);
    }

    @Test
    public void testChunkInvarianceOddSizes() throws Exception {
        byte[] original = new byte[200];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 17);
        }
        byte[] compressed = encodeQuality0(original);
        int[] sizes = new int[] { 1, 2, 3, 7, 8, 9, 15, 16, 17 };
        for (int i = 0; i < sizes.length; i++) {
            byte[] decoded = decodeChunked(compressed, sizes[i]);
            assertArrayEquals("chunk size " + sizes[i], original, decoded);
        }
    }

    @Test
    public void testDirectBufferInput() throws Exception {
        byte[] original = "direct buffer test".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encodeQuality0(original);
        ByteBuffer direct = ByteBuffer.allocateDirect(compressed.length);
        direct.put(compressed);
        direct.flip();
        CollectingHandler handler = new CollectingHandler();
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(handler);
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        decoder.receive(direct);
        decoder.close();
        assertArrayEquals(original, handler.toByteArray());
    }

    @Test
    public void testWindowBitsLimit() throws Exception {
        byte[] compressed = encodeQuality0WithWindow(
            "x".getBytes(StandardCharsets.UTF_8), 22);
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(new CollectingHandler());
        decoder.setLimits(new BrotliLimits().setMaxWindowBits(16));
        try {
            decoder.receive(ByteBuffer.wrap(compressed));
            decoder.close();
            fail("expected limit violation");
        } catch (BrotliException e) {
            assertTrue(e.getMessage().contains("WBITS")
                || e.getMessage().toLowerCase().contains("window"));
        }
    }

    @Test
    public void testTotalOutputLimit() throws Exception {
        byte[] original = new byte[1000];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) i;
        }
        byte[] compressed = encodeQuality0(original);
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(new CollectingHandler());
        decoder.setLimits(new BrotliLimits().setMaxTotalOutput(100));
        try {
            decoder.receive(ByteBuffer.wrap(compressed));
            decoder.close();
            fail("expected output limit");
        } catch (BrotliException e) {
            // expected
        }
    }

    @Test
    public void testLargeQuality0() throws Exception {
        byte[] original = new byte[100000];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i & 0xff);
        }
        byte[] compressed = encodeQuality0(original);
        byte[] decoded = decodeAll(compressed);
        assertArrayEquals(original, decoded);
    }

    static byte[] encodeQuality0(byte[] original) throws BrotliException {
        return encodeQuality0WithWindow(original, 22);
    }

    static byte[] encodeQuality0WithWindow(byte[] original, int wbits)
            throws BrotliException {
        CollectingSink sink = new CollectingSink();
        BrotliEncoder enc = new BrotliEncoder(sink);
        enc.setQuality(0);
        enc.setWindowBits(wbits);
        if (original.length > 0) {
            enc.receive(ByteBuffer.wrap(original));
        }
        enc.close();
        return sink.toByteArray();
    }

    static byte[] decodeAll(byte[] compressed) throws BrotliException {
        CollectingHandler handler = new CollectingHandler();
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(handler);
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        decoder.receive(ByteBuffer.wrap(compressed));
        decoder.close();
        return handler.toByteArray();
    }

    static byte[] decodeChunked(byte[] compressed, int chunkSize)
            throws BrotliException {
        CollectingHandler handler = new CollectingHandler();
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(handler);
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        int offset = 0;
        while (offset < compressed.length) {
            int n = Math.min(chunkSize, compressed.length - offset);
            ByteBuffer chunk = ByteBuffer.wrap(compressed, offset, n);
            // wrap(array, offset, length) — position 0, limit length
            decoder.receive(chunk);
            offset += n;
        }
        decoder.close();
        return handler.toByteArray();
    }
}

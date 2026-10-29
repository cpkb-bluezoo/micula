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

    /**
     * An insert longer than the metablock must be rejected, not emitted in
     * full: the handler was told MLEN=1 but the insert would produce 2 bytes.
     */
    @Test
    public void testInsertLengthExceedsMlen() throws Exception {
        byte[] compressed = singleSymbolMetablock(1, 16); // insert 2, copy 2
        CollectingHandler handler = new CollectingHandler();
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(handler);
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        try {
            decoder.receive(ByteBuffer.wrap(compressed));
            decoder.close();
            fail("expected insert length to be rejected");
        } catch (BrotliException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("MLEN"));
        }
        assertTrue("emitted past MLEN: " + handler.toByteArray().length,
            handler.toByteArray().length <= 1);
    }

    /** An insert that exactly fills the metablock is valid. */
    @Test
    public void testInsertLengthEqualsMlen() throws Exception {
        byte[] compressed = singleSymbolMetablock(2, 16); // insert 2, copy 2
        byte[] decoded = decodeAll(compressed);
        assertArrayEquals(new byte[] { 'A', 'A' }, decoded);
    }

    /**
     * Missing input bits must not be decoded as zeros: dropping the final
     * byte of a stream whose last field is a zero-valued symbol leaves the
     * stream incomplete, so close() must fail.
     */
    @Test
    public void testTruncatedStreamRejected() throws Exception {
        byte[] compressed = singleSymbolMetablock(2, 16);
        assertTrue(isRejected(compressed, compressed.length - 1));
    }

    /** Every strict prefix of a valid stream is an incomplete stream. */
    @Test
    public void testEveryTruncationRejected() throws Exception {
        byte[] original = ("The quick brown fox jumps over the lazy dog. "
            + "The quick brown fox jumps over the lazy dog again.\n")
            .getBytes(StandardCharsets.UTF_8);
        int[] qualities = new int[] { 0, 1, 5, 9, 11 };
        for (int q = 0; q < qualities.length; q++) {
            CollectingSink sink = new CollectingSink();
            BrotliEncoder enc = new BrotliEncoder(sink);
            enc.setQuality(qualities[q]);
            enc.receive(ByteBuffer.wrap(original));
            enc.close();
            byte[] compressed = sink.toByteArray();
            assertArrayEquals(original, decodeAll(compressed));
            for (int len = 0; len < compressed.length; len++) {
                assertTrue("quality " + qualities[q] + " prefix " + len,
                    isRejected(compressed, len));
            }
        }
    }

    /**
     * Decodes the first {@code len} bytes of {@code compressed}, expecting a
     * BrotliException.
     *
     * @return true if the exception was thrown
     */
    private static boolean isRejected(byte[] compressed, int len) {
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(new CollectingHandler());
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        try {
            if (len > 0) {
                decoder.receive(ByteBuffer.wrap(compressed, 0, len));
            }
            decoder.close();
        } catch (BrotliException e) {
            return true;
        }
        return false;
    }

    /**
     * A block-type switch whose block length is split across receive() calls
     * must not apply the switch twice. Mixed content makes the encoder emit
     * block switches, and one-byte chunks put a boundary inside every one.
     */
    @Test
    public void testChunkInvarianceWithBlockSwitches() throws Exception {
        byte[] original = mixedContent(120000, 9);
        int[] qualities = new int[] { 5, 9 };
        for (int q = 0; q < qualities.length; q++) {
            CollectingSink sink = new CollectingSink();
            BrotliEncoder enc = new BrotliEncoder(sink);
            enc.setQuality(qualities[q]);
            enc.receive(ByteBuffer.wrap(original));
            enc.close();
            byte[] compressed = sink.toByteArray();
            assertArrayEquals("whole buffer, quality " + qualities[q],
                original, decodeAll(compressed));
            int[] sizes = new int[] { 1, 2, 7, 1000 };
            for (int i = 0; i < sizes.length; i++) {
                assertArrayEquals("quality " + qualities[q] + " chunk "
                    + sizes[i], original, decodeChunked(compressed, sizes[i]));
            }
        }
    }

    /** Runs of text, random bytes and a small alphabet, deterministic by seed. */
    private static byte[] mixedContent(int length, long seed) {
        java.util.Random random = new java.util.Random(seed);
        byte[] data = new byte[length];
        String text = "the quick brown fox ";
        int i = 0;
        while (i < length) {
            int kind = random.nextInt(3);
            int run = 200 + random.nextInt(3000);
            for (int j = 0; j < run && i < length; j++, i++) {
                if (kind == 0) {
                    data[i] = (byte) text.charAt(j % text.length());
                } else if (kind == 1) {
                    data[i] = (byte) random.nextInt(256);
                } else {
                    data[i] = (byte) ('a' + random.nextInt(4));
                }
            }
        }
        return data;
    }

    /**
     * Builds a one-metablock stream in which every prefix code has a single
     * symbol, so commands and literals consume no bits. Literals are 'A'.
     *
     * @param mlen the metablock length (1..65536)
     * @param insertCopySymbol the sole insert-and-copy symbol
     */
    static byte[] singleSymbolMetablock(int mlen, int insertCopySymbol) {
        long[] acc = new long[2]; // [0]=bit buffer, [1]=bit count
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int[][] fields = {
            { 0, 1 },                       // WBITS = 16
            { 1, 1 }, { 0, 1 },             // ISLAST, not ISLASTEMPTY
            { 0, 2 }, { mlen - 1, 16 },     // MNIBBLES = 4, MLEN - 1
            { 0, 1 }, { 0, 1 }, { 0, 1 },   // NBLTYPESL/I/D = 1
            { 0, 2 }, { 0, 4 },             // NPOSTFIX, NDIRECT
            { 0, 2 },                       // literal context mode
            { 0, 1 }, { 0, 1 },             // NTREESL, NTREESD = 1
            { 1, 2 }, { 0, 2 }, { 'A', 8 },                   // literals
            { 1, 2 }, { 0, 2 }, { insertCopySymbol, 10 },     // insert-and-copy
            { 1, 2 }, { 0, 2 }, { 0, 6 },                     // distances
        };
        for (int i = 0; i < fields.length; i++) {
            acc[0] |= ((long) fields[i][0]) << acc[1];
            acc[1] += fields[i][1];
            while (acc[1] >= 8) {
                out.write((int) (acc[0] & 0xff));
                acc[0] >>>= 8;
                acc[1] -= 8;
            }
        }
        if (acc[1] > 0) {
            out.write((int) (acc[0] & 0xff));
        }
        return out.toByteArray();
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

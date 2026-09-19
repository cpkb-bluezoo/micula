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

    /**
     * A copy whose distance equals the last distance can only use the
     * implicit-distance insert-and-copy symbols when the insert length code
     * is below 8 and the copy length code below 16; otherwise the distance
     * symbol (code 0) must still be written.
     */
    @Test
    public void testLastDistanceCopyAfterLongInsertIssue1() throws Exception {
        byte[] original = "d with the theith ".getBytes(StandardCharsets.US_ASCII);
        for (int quality = 1; quality <= 2; quality++) {
            assertArrayEquals("quality " + quality, original,
                BrotliDecoderTest.decodeAll(encode(original, quality)));
        }
    }

    @Test
    public void testLastDistanceCopyAfterLongRunIssue1() throws Exception {
        // A run of 71 identical bytes gives a copy with copy length code >= 16
        byte[] original = new byte[78];
        java.util.Arrays.fill(original, (byte) 0xcd);
        original[0] = (byte) 0xce;
        original[6] = (byte) 0xc0;
        for (int quality = 1; quality <= 2; quality++) {
            assertArrayEquals("quality " + quality, original,
                BrotliDecoderTest.decodeAll(encode(original, quality)));
        }
    }

    @Test
    public void testMixedLiteralsAndRepeatsIssue1() throws Exception {
        byte[] original = new byte[] {
            0x6c, 0x0b, (byte) 0xff, 0x6c, 0x0b, (byte) 0xff, 0x6c, 0x0b,
            0x6c, 0x0b, (byte) 0xff, 0x0b, (byte) 0xff, 0x6c, 0x46, (byte) 0xff,
            0x6c, 0x0b, (byte) 0xff, 0x6c, 0x0b, (byte) 0xff
        };
        for (int quality = 1; quality <= 2; quality++) {
            assertArrayEquals("quality " + quality, original,
                BrotliDecoderTest.decodeAll(encode(original, quality)));
        }
    }

    /** Delta-debugged fragment of a TLS 1.3 Certificate message. */
    @Test
    public void testCertificateFragmentIssue1() throws Exception {
        String hex = "0b060355040a1304546573743110300e060355043234365a"
            + "170d3237303931393037343234365a170d3237303931393037343234365a";
        byte[] original = new byte[hex.length() / 2];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        for (int quality = 1; quality <= 2; quality++) {
            assertArrayEquals("quality " + quality, original,
                BrotliDecoderTest.decodeAll(encode(original, quality)));
        }
    }

    /**
     * Seeded mixture of small-alphabet, periodic, noisy-periodic, word-like
     * and random inputs, so that every prefix code shape (simple and complex)
     * and both implicit and explicit distances are exercised.
     */
    @Test
    public void testRandomRoundTripsIssue1() throws Exception {
        java.util.Random r = new java.util.Random(20260919L);
        String[] words = { "time ", "that ", "with ", "from ", "the ", "and ",
            "hello", "world " };
        for (int i = 0; i < 300; i++) {
            int len = (i % 3 == 0) ? r.nextInt(64) : r.nextInt(3000);
            byte[] d = new byte[len];
            int kind = i % 5;
            int alphabet = 1 + r.nextInt(kind == 4 ? 256 : 12);
            int period = 1 + r.nextInt(20);
            byte[] pattern = new byte[period];
            r.nextBytes(pattern);
            StringBuilder text = new StringBuilder();
            while (text.length() < len) {
                text.append(words[r.nextInt(words.length)]);
            }
            for (int j = 0; j < len; j++) {
                switch (kind) {
                    case 0:
                        d[j] = (byte) ('a' + r.nextInt(alphabet));
                        break;
                    case 1:
                        d[j] = pattern[j % period];
                        break;
                    case 2:
                        d[j] = (r.nextInt(30) == 0)
                            ? (byte) r.nextInt(256) : pattern[j % period];
                        break;
                    case 3:
                        d[j] = (byte) text.charAt(j);
                        break;
                    default:
                        d[j] = (byte) r.nextInt(alphabet);
                        break;
                }
            }
            for (int quality = 1; quality <= 2; quality++) {
                assertArrayEquals("input " + i + " quality " + quality, d,
                    BrotliDecoderTest.decodeAll(encode(d, quality)));
            }
        }
    }

    /**
     * Four literals with code lengths 2,3,3,1 in symbol order: the simple
     * prefix code must list the length-1 symbol first and select the
     * 1,2,3,3 tree, not the 2,2,2,2 tree.
     */
    @Test
    public void testFourSymbolSimpleCodeLiteralsIssue1() throws Exception {
        byte[] original = "aaeecdee".getBytes(StandardCharsets.US_ASCII);
        for (int quality = 1; quality <= 2; quality++) {
            byte[] compressed = encode(original, quality);
            assertArrayEquals("quality " + quality, original,
                BrotliDecoderTest.decodeAll(compressed));
        }
    }

    /**
     * Every assignment of the frequencies 1,1,2,4 to four distinct symbols
     * yields code lengths {3,3,2,1} in some symbol order.
     */
    @Test
    public void testFourSymbolSimpleCodeAllOrderings() throws Exception {
        int[] freqs = { 1, 1, 2, 4 };
        int[] perm = new int[4];
        boolean[] used = new boolean[4];
        permute(freqs, perm, used, 0);
    }

    private void permute(int[] freqs, int[] perm, boolean[] used, int depth)
            throws Exception {
        if (depth == 4) {
            StringBuilder sb = new StringBuilder();
            for (int sym = 0; sym < 4; sym++) {
                for (int i = 0; i < freqs[perm[sym]]; i++) {
                    sb.append((char) ('a' + sym));
                }
            }
            byte[] original = sb.toString().getBytes(StandardCharsets.US_ASCII);
            for (int quality = 1; quality <= 2; quality++) {
                assertArrayEquals(sb + " quality " + quality, original,
                    BrotliDecoderTest.decodeAll(encode(original, quality)));
            }
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (!used[i]) {
                used[i] = true;
                perm[depth] = i;
                permute(freqs, perm, used, depth + 1);
                used[i] = false;
            }
        }
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

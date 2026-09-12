/*
 * HuffmanTableTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 */

package org.bluezoo.micula;

import java.nio.ByteBuffer;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for Huffman tables and simple prefix codes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class HuffmanTableTest {

    @Test
    public void testSingleSymbol() throws Exception {
        HuffmanTable t = HuffmanTable.singleSymbol(42);
        BitReader br = new BitReader();
        br.setInput(ByteBuffer.wrap(new byte[0]));
        br.markEof();
        assertEquals(42, t.tryDecode(br));
    }

    @Test
    public void testTwoSymbol() throws Exception {
        int[] lengths = new int[4];
        lengths[1] = 1;
        lengths[3] = 1;
        HuffmanTable t = HuffmanTable.fromLengths(lengths);

        // Encode: write prefix bits for symbol 1 (code 0) and 3 (code 1)
        CollectingSink sink = new CollectingSink();
        BitWriter bw = new BitWriter(sink, false);
        int[] codes = new int[4];
        HuffmanTable.buildEncodeTables(lengths, codes);
        bw.writePrefixBits(codes[1], 1);
        bw.writePrefixBits(codes[3], 1);
        bw.finish();

        BitReader br = new BitReader();
        br.setInput(ByteBuffer.wrap(sink.toByteArray()));
        br.markEof();
        assertEquals(1, t.tryDecode(br));
        assertEquals(3, t.tryDecode(br));
    }

    @Test
    public void testSimplePrefixRoundTrip() throws Exception {
        CollectingSink sink = new CollectingSink();
        BitWriter bw = new BitWriter(sink, false);
        // Simple NSYM=2 for alphabet 256: symbols 0 and 1
        bw.writeBits(1, 2); // simple
        bw.writeBits(1, 2); // nsym-1 = 1 → nsym=2
        int bits = PrefixCode.log2Ceil(256);
        bw.writeBits(0, bits);
        bw.writeBits(1, bits);
        bw.finish();

        BitReader br = new BitReader();
        br.setInput(ByteBuffer.wrap(sink.toByteArray()));
        br.markEof();
        HuffmanTable t = PrefixCode.tryRead(br, 256);
        assertTrue(t != null);
        // Both symbols length 1
        CollectingSink sink2 = new CollectingSink();
        BitWriter bw2 = new BitWriter(sink2, false);
        int[] lengths = new int[256];
        lengths[0] = 1;
        lengths[1] = 1;
        int[] codes = new int[256];
        HuffmanTable.buildEncodeTables(lengths, codes);
        bw2.writePrefixBits(codes[0], 1);
        bw2.writePrefixBits(codes[1], 1);
        bw2.finish();

        BitReader br2 = new BitReader();
        br2.setInput(ByteBuffer.wrap(sink2.toByteArray()));
        br2.markEof();
        assertEquals(0, t.tryDecode(br2));
        assertEquals(1, t.tryDecode(br2));
    }
}

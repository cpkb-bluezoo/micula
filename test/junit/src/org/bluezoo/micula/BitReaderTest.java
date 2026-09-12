/*
 * BitReaderTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 */

package org.bluezoo.micula;

import java.nio.ByteBuffer;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BitReader} and {@link BitWriter}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class BitReaderTest {

    @Test
    public void testLsbFirstInts() throws Exception {
        // Write bits via BitWriter, read back
        CollectingSink sink = new CollectingSink();
        BitWriter bw = new BitWriter(sink, false);
        bw.writeBits(0x15, 5);
        bw.writeBits(0x3A, 6);
        bw.writeBits(0x7, 3);
        bw.finish();
        byte[] bytes = sink.toByteArray();

        BitReader br = new BitReader();
        br.setInput(ByteBuffer.wrap(bytes));
        br.markEof();
        int[] out = new int[1];
        assertTrue(br.tryReadBits(5, out));
        assertEquals(0x15, out[0]);
        assertTrue(br.tryReadBits(6, out));
        assertEquals(0x3A, out[0]);
        assertTrue(br.tryReadBits(3, out));
        assertEquals(0x7, out[0]);
    }

    @Test
    public void testUnderflowAndResume() throws Exception {
        BitReader br = new BitReader();
        // Feed one byte, ask for 16 bits → underflow
        br.setInput(ByteBuffer.wrap(new byte[] { (byte) 0xAB }));
        int[] out = new int[1];
        assertFalse(br.tryReadBits(16, out));
        br.savePending();
        // Second byte
        br.setInput(ByteBuffer.wrap(new byte[] { (byte) 0xCD }));
        br.markEof();
        assertTrue(br.tryReadBits(16, out));
        assertEquals(0xCDAB, out[0]);
    }

    @Test
    public void testDirectBuffer() throws Exception {
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        direct.put(new byte[] { 0x01, 0x02, 0x03, 0x04 });
        direct.flip();
        BitReader br = new BitReader();
        br.setInput(direct);
        br.markEof();
        int[] out = new int[1];
        assertTrue(br.tryReadBits(8, out));
        assertEquals(0x01, out[0]);
        assertTrue(br.tryReadBits(8, out));
        assertEquals(0x02, out[0]);
    }

    @Test
    public void testReverseBits() {
        assertEquals(0, BitReader.reverseBits(0, 0));
        assertEquals(0b1, BitReader.reverseBits(0b1, 1));
        assertEquals(0b100, BitReader.reverseBits(0b001, 3));
        assertEquals(0b11001, BitReader.reverseBits(0b10011, 5));
    }

    @Test
    public void testByteBoundaryPadding() throws Exception {
        CollectingSink sink = new CollectingSink();
        BitWriter bw = new BitWriter(sink, false);
        bw.writeBits(1, 3);
        bw.jumpToByteBoundary();
        bw.writeBits(0xAA, 8);
        bw.finish();

        BitReader br = new BitReader();
        br.setInput(ByteBuffer.wrap(sink.toByteArray()));
        br.markEof();
        int[] out = new int[1];
        assertTrue(br.tryReadBits(3, out));
        assertEquals(1, out[0]);
        br.jumpToByteBoundary(true);
        assertTrue(br.tryReadBits(8, out));
        assertEquals(0xAA, out[0]);
    }
}

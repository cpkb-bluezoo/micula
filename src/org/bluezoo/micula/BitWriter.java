/*
 * BitWriter.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 *
 * micula is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * micula is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with micula.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.micula;

import java.nio.ByteBuffer;

/**
 * Bit writer for Brotli bitstreams (RFC 7932 §1.5.1).
 *
 * <p>Integers are written LSB-first. Prefix-code symbols are written by
 * emitting their canonical bit pattern MSB-first (stored LSB-first in the
 * byte stream after bit-reversal of the codeword).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class BitWriter {

    private static final int DEFAULT_CAPACITY = 8192;
    private static final int FLUSH_THRESHOLD_NUM = 3;
    private static final int FLUSH_THRESHOLD_DEN = 4;

    private final BrotliSink sink;
    private final boolean direct;

    private ByteBuffer buffer;
    private long accumulator;
    private int bitCount;

    BitWriter(BrotliSink sink, boolean direct) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
        this.direct = direct;
        this.buffer = allocate(DEFAULT_CAPACITY);
        this.accumulator = 0L;
        this.bitCount = 0;
    }

    private ByteBuffer allocate(int capacity) {
        if (direct) {
            return ByteBuffer.allocateDirect(capacity);
        }
        return ByteBuffer.allocate(capacity);
    }

    void reset() {
        accumulator = 0L;
        bitCount = 0;
        buffer.clear();
    }

    /**
     * Writes {@code n} bits of {@code value} LSB-first.
     */
    void writeBits(int value, int n) throws BrotliException {
        if (n == 0) {
            return;
        }
        long v = ((long) value) & ((1L << n) - 1L);
        accumulator |= v << bitCount;
        bitCount += n;
        while (bitCount >= 8) {
            emitByte((int) (accumulator & 0xff));
            accumulator >>>= 8;
            bitCount -= 8;
        }
    }

    /**
     * Writes a prefix-code bit pattern. {@code code} is the canonical code
     * value with {@code length} bits, MSB-first as in Huffman tables; stored
     * in the stream LSB-first after reversal.
     */
    void writePrefixBits(int code, int length) throws BrotliException {
        if (length == 0) {
            return;
        }
        int reversed = BitReader.reverseBits(code, length);
        writeBits(reversed, length);
    }

    /**
     * Pads with zero bits to the next byte boundary.
     */
    void jumpToByteBoundary() throws BrotliException {
        int rem = bitCount & 7;
        if (rem != 0) {
            writeBits(0, 8 - rem);
        }
    }

    /**
     * Writes raw bytes that must start on a byte boundary.
     */
    void writeBytesAligned(byte[] data, int offset, int length)
            throws BrotliException {
        jumpToByteBoundary();
        for (int i = 0; i < length; i++) {
            writeBits(data[offset + i] & 0xff, 8);
        }
    }

    private void emitByte(int b) throws BrotliException {
        if (!buffer.hasRemaining()) {
            flushBuffer();
        }
        buffer.put((byte) b);
        int used = buffer.position();
        int cap = buffer.capacity();
        if (used * FLUSH_THRESHOLD_DEN >= cap * FLUSH_THRESHOLD_NUM) {
            flushBuffer();
        }
    }

    void flushBuffer() throws BrotliException {
        if (buffer.position() == 0) {
            return;
        }
        buffer.flip();
        sink.compressed(buffer);
        buffer.clear();
    }

    /**
     * Flushes all pending bits (padding the last byte with zeros) and buffer.
     */
    void finish() throws BrotliException {
        if (bitCount > 0) {
            emitByte((int) (accumulator & 0xff));
            accumulator = 0L;
            bitCount = 0;
        }
        flushBuffer();
    }

    int getBitCount() {
        return bitCount;
    }
}

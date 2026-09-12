/*
 * BitReader.java
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Incremental bit reader for Brotli bitstreams (RFC 7932 §1.5.1).
 *
 * <p>Integers and extra bits are read LSB-first. Prefix-code bits are obtained
 * by reading LSB-first then reversing (MSB-first interpretation). On underflow
 * try-methods return {@code false} so the decoder can pause and resume.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class BitReader {

    private static final VarHandle LONG_LE =
        MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Accumulator holding up to 64 bits, least-significant bits first. */
    private long accumulator;

    /** Number of valid bits in the accumulator (0–64). */
    private int bitCount;

    /** Bytes pulled from the compressed stream into the accumulator. */
    private long bytesPulled;

    /** Current input chunk (read mode). */
    private ByteBuffer input;

    /** True after {@link #markEof()}. */
    private boolean eof;

    /**
     * Unconsumed input bytes carried across {@code receive()} when the caller
     * passes a fresh, non-overlapping buffer.
     */
    private ByteBuffer pending;

    /** Checkpoint for resumable multi-field reads (e.g. prefix codes). */
    private long markAccumulator;
    private int markBitCount;
    private long markBytesPulled;
    private int markInputPos;
    private boolean markValid;

    BitReader() {
        reset();
    }

    void reset() {
        accumulator = 0L;
        bitCount = 0;
        bytesPulled = 0L;
        input = null;
        eof = false;
        pending = null;
        markValid = false;
    }

    /**
     * Saves bit-reader position so a multi-step read can rewind on underflow.
     */
    void mark() {
        markAccumulator = accumulator;
        markBitCount = bitCount;
        markBytesPulled = bytesPulled;
        markInputPos = (input != null) ? input.position() : -1;
        markValid = true;
    }

    /**
     * Restores the position saved by {@link #mark()}.
     */
    void resetToMark() {
        if (!markValid) {
            return;
        }
        accumulator = markAccumulator;
        bitCount = markBitCount;
        bytesPulled = markBytesPulled;
        if (input != null && markInputPos >= 0) {
            input.position(markInputPos);
        }
    }

    void clearMark() {
        markValid = false;
    }

    /**
     * Supplies the next chunk. Prepends any pending leftover from a prior
     * underflow. When pending is used, the caller's buffer is fully consumed.
     */
    void setInput(ByteBuffer data) {
        if (pending != null && pending.hasRemaining()) {
            int need = pending.remaining() + data.remaining();
            ByteBuffer combined = ByteBuffer.allocate(need);
            combined.put(pending);
            combined.put(data);
            combined.flip();
            data.position(data.limit());
            input = combined;
            pending = null;
        } else {
            input = data;
        }
    }

    /**
     * Stashes unconsumed input bytes into pending after an underflow return.
     */
    void savePending() {
        if (input == null) {
            return;
        }
        if (input.hasRemaining()) {
            int rem = input.remaining();
            if (pending == null || pending.capacity() < rem) {
                pending = ByteBuffer.allocate(Math.max(rem, 64));
            } else {
                pending.clear();
            }
            pending.put(input);
            pending.flip();
        }
        input = null;
    }

    void markEof() {
        eof = true;
    }

    boolean isEof() {
        return eof;
    }

    long getByteOffset() {
        return bytesPulled - (bitCount / 8);
    }

    int getBitOffset() {
        return bitCount & 7;
    }

    int availableBits() {
        return bitCount;
    }

    /**
     * Ensures at least {@code n} bits are available. Returns false on underflow
     * when not at EOF. At EOF, zero-pads so Huffman peeks never run dry.
     */
    boolean ensureBits(int n) {
        while (bitCount < n) {
            if (!refillSafe()) {
                if (eof) {
                    return true;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Adds whole bytes into the accumulator without exceeding 64 bits.
     */
    private boolean refillSafe() {
        if (input == null || !input.hasRemaining()) {
            return false;
        }
        int freeBits = 64 - bitCount;
        if (freeBits < 8) {
            return true;
        }
        int freeBytes = freeBits >> 3;
        int avail = input.remaining();

        if (bitCount == 0 && avail >= 8) {
            long word = (long) LONG_LE.get(input, input.position());
            input.position(input.position() + 8);
            accumulator = word;
            bitCount = 64;
            bytesPulled += 8;
            return true;
        }

        int take = freeBytes < avail ? freeBytes : avail;
        for (int i = 0; i < take; i++) {
            int b = input.get() & 0xff;
            accumulator |= ((long) b) << bitCount;
            bitCount += 8;
            bytesPulled++;
        }
        return true;
    }

    int peekBits(int n) {
        if (n == 0) {
            return 0;
        }
        return (int) (accumulator & ((1L << n) - 1L));
    }

    void dropBits(int n) {
        accumulator >>>= n;
        bitCount -= n;
    }

    /**
     * Tries to read {@code n} bits LSB-first into {@code out[0]}.
     *
     * @return false on underflow
     */
    boolean tryReadBits(int n, int[] out) {
        if (n == 0) {
            out[0] = 0;
            return true;
        }
        if (!ensureBits(n)) {
            return false;
        }
        long mask = (1L << n) - 1L;
        out[0] = (int) (accumulator & mask);
        accumulator >>>= n;
        bitCount -= n;
        if (bitCount < 0) {
            bitCount = 0;
            accumulator = 0L;
        }
        return true;
    }

    int readBits(int n) throws BrotliException {
        int[] out = new int[1];
        if (!tryReadBits(n, out)) {
            throw new BrotliException("Unexpected end of bitstream");
        }
        return out[0];
    }

    /**
     * Peeks {@code n} bits as an MSB-first prefix-code value (for Huffman lookup).
     */
    int peekPrefixBits(int n) {
        int raw = (int) (accumulator & ((1L << n) - 1L));
        return reverseBits(raw, n);
    }

    /**
     * Jumps to the next byte boundary. If {@code requireZeroPadding} is true,
     * unused bits in the current byte must be zero.
     */
    void jumpToByteBoundary(boolean requireZeroPadding) throws BrotliException {
        int rem = bitCount & 7;
        if (rem != 0) {
            int pad = (int) (accumulator & ((1L << rem) - 1L));
            if (requireZeroPadding && pad != 0) {
                throw new BrotliException("Non-zero padding bits before byte boundary");
            }
            accumulator >>>= rem;
            bitCount -= rem;
        }
    }

    /**
     * After the last metablock, verify remaining bits in the accumulator are zero.
     */
    void verifyTrailingBitsZero() throws BrotliException {
        if (bitCount > 0) {
            long mask = (1L << bitCount) - 1L;
            if ((accumulator & mask) != 0L) {
                throw new BrotliException("Non-zero trailing bits at end of stream");
            }
        }
    }

    /**
     * Reads byte-aligned bytes into dest. Must be (or jumps to) a byte boundary.
     * Returns the number of bytes copied; less than {@code length} means underflow.
     */
    int tryReadBytesAligned(byte[] dest, int destPos, int length)
            throws BrotliException {
        jumpToByteBoundary(true);
        int copied = 0;
        while (copied < length && bitCount >= 8) {
            dest[destPos + copied] = (byte) (accumulator & 0xff);
            accumulator >>>= 8;
            bitCount -= 8;
            copied++;
        }
        if (input != null && copied < length) {
            int need = length - copied;
            int avail = input.remaining();
            int take = need < avail ? need : avail;
            if (take > 0) {
                input.get(dest, destPos + copied, take);
                bytesPulled += take;
                copied += take;
            }
        }
        return copied;
    }

    static int reverseBits(int value, int n) {
        int result = 0;
        for (int i = 0; i < n; i++) {
            result = (result << 1) | (value & 1);
            value >>>= 1;
        }
        return result;
    }
}

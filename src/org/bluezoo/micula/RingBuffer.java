/*
 * RingBuffer.java
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
 * Sliding-window ring buffer for reconstructed Brotli output.
 *
 * <p>Sized as a power of two ({@code 1 << wbits}) so indexing can mask.
 * That is 16 bytes larger than the RFC window {@code (1 << wbits) - 16}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class RingBuffer {

    private final byte[] data;
    private final int mask;
    private final ByteBuffer view;
    private long pos;
    private long totalWritten;

    RingBuffer(int wbits, boolean directIgnored) {
        // directIgnored reserved for future direct ByteBuffer ring; heap for now
        int size = 1 << wbits;
        this.data = new byte[size];
        this.mask = size - 1;
        this.view = ByteBuffer.wrap(data);
        this.pos = 0L;
        this.totalWritten = 0L;
    }

    long getPos() {
        return pos;
    }

    long getTotalWritten() {
        return totalWritten;
    }

    int getSize() {
        return data.length;
    }

    byte getByte(long absolutePos) {
        return data[(int) (absolutePos & mask)];
    }

    /**
     * Writes one byte and returns it (for context tracking).
     */
    byte writeByte(byte b) {
        data[(int) (pos & mask)] = b;
        pos++;
        totalWritten++;
        return b;
    }

    /**
     * Copies {@code length} bytes from {@code distance} back (1-based distance
     * as in Brotli). Handles overlapping copies byte-by-byte.
     */
    void copyBackward(int distance, int length) {
        long src = pos - distance;
        for (int i = 0; i < length; i++) {
            byte b = data[(int) ((src + i) & mask)];
            data[(int) (pos & mask)] = b;
            pos++;
        }
        totalWritten += length;
    }

    /**
     * Writes a contiguous run of bytes (e.g. dictionary transform output).
     */
    void writeBytes(byte[] src, int offset, int length) {
        for (int i = 0; i < length; i++) {
            data[(int) (pos & mask)] = src[offset + i];
            pos++;
        }
        totalWritten += length;
    }

    /**
     * Emits the most recently written {@code length} bytes to the handler as
     * one or two {@code content} slices (ring wrap).
     */
    void emitContent(BrotliHandler handler, int length) throws BrotliException {
        if (length <= 0) {
            return;
        }
        long start = pos - length;
        int startIdx = (int) (start & mask);
        int endIdx = (int) ((pos - 1) & mask);

        if (startIdx <= endIdx && (pos & mask) != 0) {
            // Contiguous, unless we wrapped exactly to 0
            if ((int) (pos & mask) >= startIdx || length <= data.length - startIdx) {
                // Check wrap: if startIdx + length > size, wraps
                if (startIdx + length <= data.length) {
                    view.clear();
                    view.position(startIdx);
                    view.limit(startIdx + length);
                    handler.content(view.slice(), true);
                    return;
                }
            }
        }

        // Possibly wrapped
        if (startIdx + length <= data.length) {
            view.clear();
            view.position(startIdx);
            view.limit(startIdx + length);
            handler.content(view.slice(), true);
        } else {
            int first = data.length - startIdx;
            view.clear();
            view.position(startIdx);
            view.limit(data.length);
            handler.content(view.slice(), false);
            view.clear();
            view.position(0);
            view.limit(length - first);
            handler.content(view.slice(), true);
        }
    }

    /**
     * Returns the byte at relative distance 1 (most recent) or 2.
     * Distance 1 = p1, distance 2 = p2 for literal context.
     */
    byte recent(int distance) {
        if (totalWritten < distance) {
            return 0;
        }
        return data[(int) ((pos - distance) & mask)];
    }
}

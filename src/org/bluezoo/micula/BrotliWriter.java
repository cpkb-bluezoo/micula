/*
 * BrotliWriter.java
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
 * Convenience helpers that write a complete Brotli stream for a single buffer.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BrotliWriter {

    private BrotliWriter() {
    }

    /**
     * Compresses {@code input} at quality 0 into {@code sink}.
     *
     * @param input uncompressed data (read mode; position advanced)
     * @param sink compressed output
     * @param windowBits WBITS in 10..24
     * @throws BrotliException on failure
     */
    public static void writeUncompressed(ByteBuffer input, BrotliSink sink, int windowBits)
            throws BrotliException {
        write(input, sink, 0, windowBits);
    }

    /**
     * Compresses {@code input} at quality 0 with default window bits (22).
     *
     * @param input uncompressed data
     * @param sink compressed output
     * @throws BrotliException on failure
     */
    public static void writeUncompressed(ByteBuffer input, BrotliSink sink)
            throws BrotliException {
        writeUncompressed(input, sink, 22);
    }

    /**
     * Compresses {@code input} at the given quality into {@code sink}.
     *
     * @param input uncompressed data (read mode; position advanced)
     * @param sink compressed output
     * @param quality 0, 1, or 2
     * @param windowBits WBITS in 10..24
     * @throws BrotliException on failure
     */
    public static void write(ByteBuffer input, BrotliSink sink, int quality, int windowBits)
            throws BrotliException {
        if (input == null || sink == null) {
            throw new NullPointerException();
        }
        BrotliEncoder enc = new BrotliEncoder(sink);
        enc.setQuality(quality);
        enc.setWindowBits(windowBits);
        if (input.hasRemaining()) {
            enc.receive(input);
        }
        enc.close();
    }

    /**
     * Compresses {@code input} at the given quality with default window bits (22).
     *
     * @param input uncompressed data
     * @param sink compressed output
     * @param quality 0, 1, or 2
     * @throws BrotliException on failure
     */
    public static void write(ByteBuffer input, BrotliSink sink, int quality)
            throws BrotliException {
        write(input, sink, quality, 22);
    }
}

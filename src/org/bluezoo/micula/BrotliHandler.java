/*
 * BrotliHandler.java
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
 * Callback interface for receiving Brotli decoding events.
 *
 * <p>Applications implement this interface and register it with
 * {@link BrotliDecoder#setHandler(BrotliHandler)}. Reconstructed output is
 * delivered via {@link #content(ByteBuffer, boolean)}. Buffer arguments are
 * valid <strong>only during the callback</strong>; copy if retention is
 * needed.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface BrotliHandler {

    /**
     * Receives a locator for the current decode position. Called once before
     * other events.
     *
     * @param locator the locator
     */
    void setLocator(BrotliLocator locator);

    /**
     * Notifies of the stream window bits (WBITS) after the stream header.
     *
     * @param wbits window bits in [10..24]
     * @throws BrotliException if the handler aborts
     */
    void windowBits(int wbits) throws BrotliException;

    /**
     * Indicates the start of a metablock.
     *
     * @param length MLEN (uncompressed length); 0 for empty/metadata
     * @param last true if this is the last metablock
     * @param kind metablock kind
     * @throws BrotliException if the handler aborts
     */
    void startMetablock(int length, boolean last, MetablockKind kind)
            throws BrotliException;

    /**
     * Delivers reconstructed uncompressed output.
     *
     * <p>The buffer is a view into the decoder's ring buffer and is valid only
     * during this call. Ring wrap-around may produce two consecutive calls.
     * {@code end} is true on the last slice of the current write.
     *
     * @param data output bytes (read mode)
     * @param end true if this is the last slice of this write
     * @throws BrotliException if the handler aborts
     */
    void content(ByteBuffer data, boolean end) throws BrotliException;

    /**
     * Delivers metadata metablock bytes (not part of the sliding window or
     * reconstructed content).
     *
     * @param data metadata bytes (valid only during this call)
     * @throws BrotliException if the handler aborts
     */
    void metadata(ByteBuffer data) throws BrotliException;

    /**
     * Notifies of an insert (literal run) when {@link #needsCommands()} is
     * true.
     *
     * @param literals literal bytes (valid only during this call)
     * @throws BrotliException if the handler aborts
     */
    void insert(ByteBuffer literals) throws BrotliException;

    /**
     * Notifies of a backward copy when {@link #needsCommands()} is true.
     *
     * @param distance LZ77 distance
     * @param length copy length
     * @throws BrotliException if the handler aborts
     */
    void copy(int distance, int length) throws BrotliException;

    /**
     * Notifies of a static-dictionary reference when {@link #needsCommands()}
     * is true.
     *
     * @param copyLength requested copy length
     * @param wordIndex dictionary word index
     * @param transformId transform id (0..120)
     * @throws BrotliException if the handler aborts
     */
    void dictionary(int copyLength, int wordIndex, int transformId)
            throws BrotliException;

    /**
     * Indicates the end of the current metablock.
     *
     * @throws BrotliException if the handler aborts
     */
    void endMetablock() throws BrotliException;

    /**
     * Indicates the end of the Brotli stream.
     *
     * @throws BrotliException if the handler aborts
     */
    void endStream() throws BrotliException;

    /**
     * Whether this handler needs LZ77 command events ({@link #insert},
     * {@link #copy}, {@link #dictionary}). Default is false so the decoder
     * can skip that work.
     *
     * @return true if command events are wanted
     */
    default boolean needsCommands() {
        return false;
    }
}

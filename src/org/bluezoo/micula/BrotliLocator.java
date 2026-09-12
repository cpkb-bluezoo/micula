/*
 * BrotliLocator.java
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

/**
 * Reports the current position in a Brotli bitstream during decoding.
 *
 * <p>Unlike text formats, Brotli has no line/column; this locator exposes
 * the stream byte offset, bit remainder within the current byte, and
 * metablock index.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface BrotliLocator {

    /**
     * Returns the number of complete bytes consumed from the compressed
     * stream so far (not counting bits still in the bit accumulator that
     * belong to a partially-read byte).
     *
     * @return stream byte offset
     */
    long getByteOffset();

    /**
     * Returns how many bits of the current byte have already been consumed
     * (0–7).
     *
     * @return bit remainder within the current byte
     */
    int getBitOffset();

    /**
     * Returns the zero-based index of the metablock currently being decoded.
     *
     * @return metablock index
     */
    int getMetablockIndex();
}

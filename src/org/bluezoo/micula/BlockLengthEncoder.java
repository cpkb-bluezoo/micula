/*
 * BlockLengthEncoder.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 *
 * micula is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
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
 * Encodes Brotli block-count symbols (RFC 7932 §6).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class BlockLengthEncoder {

    private BlockLengthEncoder() {
    }

    /**
     * Finds the block-length symbol and extra bits for {@code length}.
     *
     * @param length block length (&gt;= 1)
     * @param out {@code out[0]}=symbol, {@code out[1]}=extra, {@code out[2]}=extraBits
     */
    static void pack(int length, int[] out) throws BrotliException {
        if (length < 1) {
            throw new BrotliException("Invalid block length " + length);
        }
        for (int sym = 0; sym <= 25; sym++) {
            int nbits = InsertCopyLengths.BLOCK_LEN_EXTRA_BITS[sym];
            int base = InsertCopyLengths.BLOCK_LEN_BASE[sym];
            int max = base + (1 << nbits) - 1;
            if (length >= base && length <= max) {
                out[0] = sym;
                out[1] = length - base;
                out[2] = nbits;
                return;
            }
        }
        throw new BrotliException("Block length out of range: " + length);
    }

    /**
     * Writes a block-length code using a prebuilt Huffman table.
     */
    static void write(BitWriter bw, int length, int[] lengths, int[] codes)
            throws BrotliException {
        int[] pack = new int[3];
        pack(length, pack);
        int sym = pack[0];
        int len = lengths[sym];
        if (len > 0) {
            bw.writePrefixBits(codes[sym], len);
        }
        bw.writeBits(pack[1], pack[2]);
    }
}

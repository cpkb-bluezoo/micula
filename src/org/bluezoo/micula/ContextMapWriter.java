/*
 * ContextMapWriter.java
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
 * Writes NTREES / NBLTYPES varints and context maps for the encoder.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ContextMapWriter {

    private ContextMapWriter() {
    }

    /**
     * Writes a DecodeVarLenUint8+1 value (NBLTYPES / NTREES).
     *
     * @param value value in 1..256
     */
    static void writeVarLenUint8PlusOne(BitWriter bw, int value)
            throws BrotliException {
        if (value < 1 || value > 256) {
            throw new BrotliException("Invalid varlenuint8+1 value " + value);
        }
        if (value == 1) {
            bw.writeBits(0, 1);
            return;
        }
        bw.writeBits(1, 1);
        if (value == 2) {
            bw.writeBits(0, 3);
            return;
        }
        int n = 0;
        int v = value - 1;
        while ((1 << n) <= v - (1 << n)) {
            n++;
        }
        // Find smallest n such that 1 + (1<<n) + ((1<<n)-1) >= value
        // i.e. value - 1 - (1<<n) fits in n bits and is >= 0
        for (n = 1; n <= 7; n++) {
            int base = 1 << n;
            int extra = value - 1 - base;
            if (extra >= 0 && extra < (1 << n)) {
                bw.writeBits(n, 3);
                bw.writeBits(extra, n);
                return;
            }
        }
        throw new BrotliException("Cannot encode varlenuint8+1 value " + value);
    }

    /**
     * Writes a context map with RLEMAX=0 and IMTF=0.
     * Map entries are tree indexes in {@code 0 .. ntrees-1}.
     *
     * @param map context map entries
     * @param mapLen number of entries to write
     * @param ntrees number of trees (alphabet size for the map Huffman)
     */
    static void write(BitWriter bw, int[] map, int mapLen, int ntrees)
            throws BrotliException {
        if (ntrees < 2) {
            throw new BrotliException("Context map requires NTREES >= 2");
        }
        // RLEMAX = 0
        bw.writeBits(0, 1);

        int[] hist = new int[ntrees];
        for (int i = 0; i < mapLen; i++) {
            int t = map[i];
            if (t < 0 || t >= ntrees) {
                throw new BrotliException("Context map tree index out of range");
            }
            hist[t]++;
        }
        if (sum(hist) == 0) {
            hist[0] = 1;
        }
        HuffmanEncoder.writePrefixCode(bw, hist);

        int[] lengths = new int[ntrees];
        int[] codes = new int[ntrees];
        HuffmanEncoder.assignLengths(hist, lengths, 15);
        HuffmanTable.buildEncodeTables(lengths, codes);

        for (int i = 0; i < mapLen; i++) {
            int t = map[i];
            int len = lengths[t];
            if (len > 0) {
                bw.writePrefixBits(codes[t], len);
            }
        }

        // IMTF = 0
        bw.writeBits(0, 1);
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
        }
        return s;
    }
}

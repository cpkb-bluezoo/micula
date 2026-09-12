/*
 * HuffmanTable.java
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
 * Flat Huffman decode table for Brotli prefix codes (RFC 7932 §3).
 *
 * <p>Built from per-symbol bit lengths. Lookup uses MSB-first prefix bits
 * from {@link BitReader#peekPrefixBits(int)}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class HuffmanTable {

    static final int MAX_CODE_LENGTH = 15;

    /** Each entry: high 16 bits = symbol, low 16 bits = code length (0 = invalid). */
    private final int[] table;

    private final int maxLength;
    private final int singleSymbol;

    private HuffmanTable(int[] table, int maxLength, int singleSymbol) {
        this.table = table;
        this.maxLength = maxLength;
        this.singleSymbol = singleSymbol;
    }

    int getMaxLength() {
        return maxLength;
    }

    /**
     * Tries to decode one symbol.
     *
     * @return symbol &gt;= 0 on success; -2 on underflow; -1 on invalid code
     */
    int tryDecode(BitReader br) {
        if (maxLength == 0) {
            return singleSymbol;
        }
        if (!br.ensureBits(maxLength)) {
            return -2;
        }
        int peek = br.peekPrefixBits(maxLength);
        int entry = table[peek];
        int len = entry & 0xffff;
        if (len == 0) {
            return -1;
        }
        br.dropBits(len);
        return entry >>> 16;
    }

    int decode(BitReader br) throws BrotliException {
        int s = tryDecode(br);
        if (s == -2) {
            throw new BrotliException("Unexpected end of bitstream in Huffman decode");
        }
        if (s == -1) {
            throw new BrotliException("Invalid Huffman code");
        }
        return s;
    }

    /**
     * Single-symbol code that consumes zero bits (simple prefix code NSYM=1).
     */
    static HuffmanTable singleSymbol(int symbol) {
        return new HuffmanTable(new int[1], 0, symbol);
    }

    /**
     * Builds a decode table from symbol bit lengths (0 = unused symbol).
     * Validates the 15-bit Kraft sum.
     */
    static HuffmanTable fromLengths(int[] lengths) throws BrotliException {
        int alphabetSize = lengths.length;
        int maxLength = 0;
        int used = 0;
        int onlySym = -1;

        for (int i = 0; i < alphabetSize; i++) {
            int len = lengths[i];
            if (len < 0 || len > MAX_CODE_LENGTH) {
                throw new BrotliException("Invalid code length " + len);
            }
            if (len > 0) {
                used++;
                onlySym = i;
                if (len > maxLength) {
                    maxLength = len;
                }
            }
        }

        if (used == 0) {
            throw new BrotliException("Empty Huffman alphabet");
        }

        if (used == 1 && maxLength == 0) {
            return singleSymbol(onlySym);
        }

        // If exactly one symbol, Brotli may still use length > 0, or NSYM=1 with 0.
        // Kraft: sum (1 << (15 - len)) must equal 1 << 15
        int kraft = 0;
        for (int i = 0; i < alphabetSize; i++) {
            int len = lengths[i];
            if (len > 0) {
                kraft += 1 << (MAX_CODE_LENGTH - len);
            }
        }
        if (kraft != (1 << MAX_CODE_LENGTH)) {
            throw new BrotliException(
                "Invalid Huffman code lengths (Kraft sum=" + kraft + ")");
        }

        int[] blCount = new int[MAX_CODE_LENGTH + 1];
        for (int i = 0; i < alphabetSize; i++) {
            int len = lengths[i];
            if (len > 0) {
                blCount[len]++;
            }
        }

        int[] nextCode = new int[MAX_CODE_LENGTH + 1];
        int code = 0;
        blCount[0] = 0;
        for (int bits = 1; bits <= MAX_CODE_LENGTH; bits++) {
            code = (code + blCount[bits - 1]) << 1;
            nextCode[bits] = code;
        }

        int tableSize = 1 << maxLength;
        int[] table = new int[tableSize];

        for (int sym = 0; sym < alphabetSize; sym++) {
            int len = lengths[sym];
            if (len > 0) {
                int symbolCode = nextCode[len];
                nextCode[len]++;
                int step = 1 << (maxLength - len);
                int start = symbolCode << (maxLength - len);
                int entry = (sym << 16) | len;
                for (int i = 0; i < step; i++) {
                    table[start + i] = entry;
                }
            }
        }

        return new HuffmanTable(table, maxLength, -1);
    }

    /**
     * Wraps a prebuilt flat table (used for short code-length alphabets).
     */
    static HuffmanTable fromPrebuilt(int[] table, int maxLength) {
        return new HuffmanTable(table, maxLength, -1);
    }

    /**
     * Builds encode lengths → codes for the writer.
     * {@code codes[sym]} and {@code lengths[sym]} for each used symbol.
     */
    static void buildEncodeTables(int[] lengths, int[] codesOut) throws BrotliException {
        int alphabetSize = lengths.length;
        int[] blCount = new int[MAX_CODE_LENGTH + 1];
        for (int i = 0; i < alphabetSize; i++) {
            int len = lengths[i];
            if (len > 0) {
                blCount[len]++;
            }
        }
        int[] nextCode = new int[MAX_CODE_LENGTH + 1];
        int code = 0;
        blCount[0] = 0;
        for (int bits = 1; bits <= MAX_CODE_LENGTH; bits++) {
            code = (code + blCount[bits - 1]) << 1;
            nextCode[bits] = code;
        }
        for (int sym = 0; sym < alphabetSize; sym++) {
            int len = lengths[sym];
            if (len > 0) {
                codesOut[sym] = nextCode[len];
                nextCode[len]++;
            } else {
                codesOut[sym] = 0;
            }
        }
    }
}

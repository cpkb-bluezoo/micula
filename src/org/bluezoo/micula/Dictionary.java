/*
 * Dictionary.java
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
 * RFC 7932 static dictionary and 121 word transforms (§8, Appendices A/B).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class Dictionary {

    static final int DICT_SIZE = 122784;
    static final int NUM_TRANSFORMS = 121;
    static final int MAX_TRANSFORMED_WORD_LENGTH = 38;

    /** NDBITS[L] for word length L (RFC Appendix A). */
    private static final int[] NDBITS = {
        0, 0, 0, 0,
        10, 10, 11, 11, 10, 10, 10, 10, 10,
        9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5
    };

    private static final int[] DOFFSET = new int[25];

    // Transform types (Google / wire numbering used in transforms table)
    private static final int IDENTITY = 0;
    private static final int OMIT_LAST_1 = 1;
    private static final int OMIT_LAST_9 = 9;
    private static final int UPPERCASE_FIRST = 10;
    private static final int UPPERCASE_ALL = 11;
    private static final int OMIT_FIRST_1 = 12;
    private static final int OMIT_FIRST_9 = 20;

    /**
     * Prefix/suffix blob: each entry is length-prefixed string.
     * From RFC Appendix B / Google kPrefixSuffix.
     */
    private static final byte[] PREFIX_SUFFIX = buildPrefixSuffix();

    private static final int[] PREFIX_SUFFIX_MAP = {
        0x00, 0x02, 0x05, 0x0E, 0x13, 0x16, 0x18, 0x1E, 0x23, 0x25,
        0x2A, 0x2D, 0x2F, 0x32, 0x34, 0x3A, 0x3E, 0x45, 0x47, 0x4E,
        0x55, 0x5A, 0x5C, 0x63, 0x68, 0x6D, 0x72, 0x77, 0x7A, 0x7C,
        0x80, 0x83, 0x88, 0x8C, 0x8E, 0x91, 0x97, 0x9F, 0xA5, 0xA9,
        0xAD, 0xB2, 0xB7, 0xBD, 0xC2, 0xC7, 0xCA, 0xCF, 0xD5, 0xD8
    };

    /** Triplets: prefix_id, transform_type, suffix_id for each of 121 transforms. */
    private static final byte[] TRANSFORMS = buildTransforms();

    private static final byte[] DICT = DictionaryData.BYTES;

    static {
        if (DICT.length != DICT_SIZE) {
            throw new ExceptionInInitializerError(
                "Dictionary size " + DICT.length + " != " + DICT_SIZE);
        }
        int offset = 0;
        for (int len = 0; len < 25; len++) {
            DOFFSET[len] = offset;
            int nwords = NDBITS[len] == 0 ? 0 : (1 << NDBITS[len]);
            offset += nwords * len;
        }
    }

    private Dictionary() {
    }

    static int nwords(int length) {
        if (length < 4 || length > 24) {
            return 0;
        }
        return 1 << NDBITS[length];
    }

    static int ndbits(int length) {
        return NDBITS[length];
    }

    /**
     * Applies a dictionary reference: distance beyond the window selects
     * word index and transform.
     *
     * @param copyLength copy length L
     * @param distance decoded distance
     * @param maxDistance max allowed backward distance
     * @param out buffer for transformed output (at least MAX_TRANSFORMED_WORD_LENGTH)
     * @return output length, or -1 if invalid
     */
    static int lookup(int copyLength, int distance, int maxDistance, byte[] out)
            throws BrotliException {
        if (copyLength < 4 || copyLength > 24) {
            throw new BrotliException("Dictionary copy length out of range: " + copyLength);
        }
        long wordId = (long) distance - ((long) maxDistance + 1L);
        int nw = nwords(copyLength);
        if (nw == 0) {
            throw new BrotliException("No dictionary words of length " + copyLength);
        }
        int index = (int) (wordId % nw);
        int transformId = (int) (wordId >> NDBITS[copyLength]);
        if (transformId < 0 || transformId >= NUM_TRANSFORMS) {
            throw new BrotliException("Invalid transform id " + transformId);
        }
        int wordOffset = DOFFSET[copyLength] + index * copyLength;
        return transform(DICT, wordOffset, copyLength, transformId, out);
    }

    static int transform(byte[] dict, int wordOffset, int wordLen,
            int transformId, byte[] dst) {
        int prefixId = TRANSFORMS[transformId * 3] & 0xff;
        int type = TRANSFORMS[transformId * 3 + 1] & 0xff;
        int suffixId = TRANSFORMS[transformId * 3 + 2] & 0xff;

        int idx = 0;
        idx += copyPrefixed(PREFIX_SUFFIX, PREFIX_SUFFIX_MAP[prefixId], dst, idx);

        int srcOff = wordOffset;
        int len = wordLen;
        if (type >= OMIT_LAST_1 && type <= OMIT_LAST_9) {
            len -= type; // OMIT_LAST_n where n == type
        } else if (type >= OMIT_FIRST_1 && type <= OMIT_FIRST_9) {
            int skip = type - (OMIT_FIRST_1 - 1);
            srcOff += skip;
            len -= skip;
        }
        if (len < 0) {
            len = 0;
        }

        int wordStart = idx;
        for (int i = 0; i < len; i++) {
            dst[idx++] = dict[srcOff + i];
        }

        if (type == UPPERCASE_FIRST) {
            toUpperCase(dst, wordStart);
        } else if (type == UPPERCASE_ALL) {
            int remaining = len;
            int p = wordStart;
            while (remaining > 0) {
                int step = toUpperCase(dst, p);
                p += step;
                remaining -= step;
            }
        }

        idx += copyPrefixed(PREFIX_SUFFIX, PREFIX_SUFFIX_MAP[suffixId], dst, idx);
        return idx;
    }

    private static int copyPrefixed(byte[] blob, int mapOffset, byte[] dst, int dstOff) {
        int len = blob[mapOffset] & 0xff;
        for (int i = 0; i < len; i++) {
            dst[dstOff + i] = blob[mapOffset + 1 + i];
        }
        return len;
    }

    /** Ferment / uppercase one UTF-8 codepoint; returns bytes consumed. */
    private static int toUpperCase(byte[] p, int offset) {
        int b0 = p[offset] & 0xff;
        if (b0 < 0xC0) {
            if (b0 >= 'a' && b0 <= 'z') {
                p[offset] = (byte) (b0 ^ 32);
            }
            return 1;
        }
        if (b0 < 0xE0) {
            p[offset + 1] = (byte) (p[offset + 1] ^ 32);
            return 2;
        }
        p[offset + 2] = (byte) (p[offset + 2] ^ 5);
        return 3;
    }

    private static byte[] buildPrefixSuffix() {
        // Exact bytes from Google kPrefixSuffix / RFC Appendix B
        String s =
            "\u0001 \u0002, \u0008 of the \u0004 of \u0002s \u0001.\u0005 and \u0004 "
            + "in \u0001\"\u0004 to \u0002\">\u0001\n\u0002. \u0001]\u0005 for \u0003 a \u0006 "
            + "that \u0001'\u0006 with \u0006 from \u0004 by \u0001(\u0006. T"
            + "he \u0004 on \u0004 as \u0004 is \u0004ing \u0002\n\t\u0001:\u0003ed "
            + "\u0002=\"\u0004 at \u0003ly \u0001,\u0002='\u0005.com/\u0007. This \u0005"
            + " not \u0003er \u0003al \u0004ful \u0004ive \u0005less \u0004es"
            + "t \u0004ize \u0002\u00c2\u00a0\u0004ous \u0005 the \u0002e ";
        byte[] bytes = new byte[s.length() + 1];
        for (int i = 0; i < s.length(); i++) {
            bytes[i] = (byte) s.charAt(i);
        }
        bytes[s.length()] = 0;
        return bytes;
    }

    private static byte[] buildTransforms() {
        // 121 triplets from Google kTransformsData
        int[] raw = {
            49, 0, 49, 49, 0, 0, 0, 0, 0, 49, 12, 49, 49, 10, 0, 49, 0, 47,
            0, 0, 49, 4, 0, 0, 49, 0, 3, 49, 10, 49, 49, 0, 6, 49, 13, 49,
            49, 1, 49, 1, 0, 0, 49, 0, 1, 0, 10, 0, 49, 0, 7, 49, 0, 9,
            48, 0, 0, 49, 0, 8, 49, 0, 5, 49, 0, 10, 49, 0, 11, 49, 3, 49,
            49, 0, 13, 49, 0, 14, 49, 14, 49, 49, 2, 49, 49, 0, 15, 49, 0, 16,
            0, 10, 49, 49, 0, 12, 5, 0, 49, 0, 0, 1, 49, 15, 49, 49, 0, 18,
            49, 0, 17, 49, 0, 19, 49, 0, 20, 49, 16, 49, 49, 17, 49, 47, 0, 49,
            49, 4, 49, 49, 0, 22, 49, 11, 49, 49, 0, 23, 49, 0, 24, 49, 0, 25,
            49, 7, 49, 49, 1, 26, 49, 0, 27, 49, 0, 28, 0, 0, 12, 49, 0, 29,
            49, 20, 49, 49, 18, 49, 49, 6, 49, 49, 0, 21, 49, 10, 1, 49, 8, 49,
            49, 0, 31, 49, 0, 32, 47, 0, 3, 49, 5, 49, 49, 9, 49, 0, 10, 1,
            49, 10, 8, 5, 0, 21, 49, 11, 0, 49, 10, 10, 49, 0, 30, 0, 0, 5,
            35, 0, 49, 47, 0, 2, 49, 10, 17, 49, 0, 36, 49, 0, 33, 5, 0, 0,
            49, 10, 21, 49, 10, 5, 49, 0, 37, 0, 0, 30, 49, 0, 38, 0, 11, 0,
            49, 0, 39, 0, 11, 49, 49, 0, 34, 49, 11, 8, 49, 10, 12, 0, 0, 21,
            49, 0, 40, 0, 10, 12, 49, 0, 41, 49, 0, 42, 49, 11, 17, 49, 0, 43,
            0, 10, 5, 49, 11, 10, 0, 0, 34, 49, 10, 33, 49, 0, 44, 49, 11, 5,
            45, 0, 49, 0, 0, 33, 49, 10, 30, 49, 11, 30, 49, 0, 46, 49, 11, 1,
            49, 10, 34, 0, 10, 33, 0, 11, 30, 0, 11, 1, 49, 11, 33, 49, 11, 21,
            49, 11, 12, 0, 11, 5, 49, 11, 34, 0, 11, 12, 0, 10, 30, 0, 11, 34,
            0, 10, 34
        };
        byte[] t = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            t[i] = (byte) raw[i];
        }
        return t;
    }

    /**
     * Finds a dictionary word match for the encoder (greedy).
     *
     * @return transform word id base distance contribution, or -1
     */
    static int findMatch(byte[] data, int offset, int available,
            int[] outLength, int[] outTransformId, int[] outWordIndex) {
        int bestLen = 0;
        int bestTransform = 0;
        int bestIndex = 0;
        byte[] tmp = new byte[MAX_TRANSFORMED_WORD_LENGTH];

        for (int len = 24; len >= 4; len--) {
            if (len > available) {
                continue;
            }
            int nw = nwords(len);
            int base = DOFFSET[len];
            for (int idx = 0; idx < nw; idx++) {
                int woff = base + idx * len;
                // Identity transform only for q2 speed
                boolean match = true;
                for (int i = 0; i < len; i++) {
                    if (DICT[woff + i] != data[offset + i]) {
                        match = false;
                        break;
                    }
                }
                if (match && len > bestLen) {
                    bestLen = len;
                    bestTransform = 0;
                    bestIndex = idx;
                    if (bestLen >= 24) {
                        outLength[0] = bestLen;
                        outTransformId[0] = bestTransform;
                        outWordIndex[0] = bestIndex;
                        return bestLen;
                    }
                }
            }
            if (bestLen >= len) {
                break;
            }
        }
        if (bestLen >= 4) {
            outLength[0] = bestLen;
            outTransformId[0] = bestTransform;
            outWordIndex[0] = bestIndex;
            return bestLen;
        }
        return -1;
    }
}

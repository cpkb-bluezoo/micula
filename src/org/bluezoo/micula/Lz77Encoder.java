/*
 * Lz77Encoder.java
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
 * Greedy LZ77 matcher producing insert/copy commands for qualities 1–2.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class Lz77Encoder {

    private static final int HASH_BITS = 15;
    private static final int HASH_SIZE = 1 << HASH_BITS;
    private static final int MIN_MATCH = 4;
    private static final int MAX_MATCH = 16777215;

    /** One insert+copy command. */
    static final class Command {
        /** Offset of literals within the current metablock buffer. */
        int insertOffset;
        int insertLen;
        int copyLen;
        /**
         * Backward distance for LZ copies; for dictionary copies this is the
         * wire distance ({@code maxDistance + 1 + wordId}). Zero means
         * insert-only (trailing literals; no distance symbol).
         */
        int distance;
        /** True if {@link #distance} is a static-dictionary reference. */
        boolean dictionary;
    }

    private Lz77Encoder() {
    }

    /**
     * Greedy-parses {@code block[0..blockLen)} using optional history for
     * cross-metablock matches.
     *
     * @param block current metablock bytes
     * @param blockLen length of block
     * @param history prior uncompressed bytes (may be null)
     * @param historyLen valid length of history
     * @param windowSize {@code (1<<wbits)-16}
     * @param useDictionary quality 2: also try {@link Dictionary#findMatch}
     * @param outCommands {@code outCommands[0]} receives the command array
     * @return number of commands
     */
    static int encode(byte[] block, int blockLen,
            byte[] history, int historyLen,
            int windowSize, boolean useDictionary,
            Command[][] outCommands) {
        if (blockLen <= 0) {
            outCommands[0] = new Command[0];
            return 0;
        }

        int histUse = historyLen;
        if (histUse > windowSize) {
            histUse = windowSize;
        }
        int histStart = historyLen - histUse;

        int[] hashTable = new int[HASH_SIZE];
        for (int i = 0; i < HASH_SIZE; i++) {
            hashTable[i] = -1;
        }

        if (history != null && histUse >= MIN_MATCH) {
            for (int i = 0; i <= histUse - MIN_MATCH; i++) {
                hashTable[hash3(history, histStart + i)] = i;
            }
        }

        Command[] cmds = new Command[16];
        int cmdCount = 0;
        int litStart = 0;
        int pos = 0;

        int[] dictLenOut = new int[1];
        int[] dictTransformOut = new int[1];
        int[] dictIndexOut = new int[1];

        while (pos < blockLen) {
            int absPos = histUse + pos;
            int maxDist = absPos;
            if (maxDist > windowSize) {
                maxDist = windowSize;
            }

            int bestLen = 0;
            int bestDist = 0;
            boolean bestDict = false;

            if (pos + MIN_MATCH <= blockLen && maxDist >= 1) {
                int h = hash3(block, pos);
                int candAbs = hashTable[h];
                if (candAbs >= 0) {
                    int distance = absPos - candAbs;
                    if (distance >= 1 && distance <= maxDist) {
                        int matchLen = matchLength(block, pos, blockLen,
                                history, histStart, histUse, candAbs);
                        if (matchLen >= MIN_MATCH && matchLen > bestLen) {
                            bestLen = matchLen;
                            bestDist = distance;
                            bestDict = false;
                        }
                    }
                }
                hashTable[h] = absPos;
            }

            if (useDictionary && pos + 4 <= blockLen) {
                int avail = blockLen - pos;
                if (avail > 24) {
                    avail = 24;
                }
                int dlen = Dictionary.findMatch(block, pos, avail,
                        dictLenOut, dictTransformOut, dictIndexOut);
                if (dlen > bestLen) {
                    int wordId = (dictTransformOut[0] << Dictionary.ndbits(dlen))
                            + dictIndexOut[0];
                    bestLen = dlen;
                    bestDist = maxDist + 1 + wordId;
                    bestDict = true;
                }
            }

            if (bestLen >= MIN_MATCH) {
                Command c = new Command();
                c.insertOffset = litStart;
                c.insertLen = pos - litStart;
                c.copyLen = bestLen;
                c.distance = bestDist;
                c.dictionary = bestDict;
                cmds = append(cmds, cmdCount, c);
                cmdCount++;

                int end = pos + bestLen;
                pos++;
                while (pos < end) {
                    if (pos + MIN_MATCH <= blockLen) {
                        hashTable[hash3(block, pos)] = histUse + pos;
                    }
                    pos++;
                }
                litStart = pos;
            } else {
                pos++;
            }
        }

        if (litStart < blockLen || cmdCount == 0) {
            Command c = new Command();
            c.insertOffset = litStart;
            c.insertLen = blockLen - litStart;
            c.copyLen = 2;
            c.distance = 0;
            c.dictionary = false;
            cmds = append(cmds, cmdCount, c);
            cmdCount++;
        }

        Command[] result = new Command[cmdCount];
        System.arraycopy(cmds, 0, result, 0, cmdCount);
        outCommands[0] = result;
        return cmdCount;
    }

    private static Command[] append(Command[] cmds, int count, Command c) {
        if (count == cmds.length) {
            Command[] grown = new Command[cmds.length * 2];
            System.arraycopy(cmds, 0, grown, 0, cmds.length);
            cmds = grown;
        }
        cmds[count] = c;
        return cmds;
    }

    private static int hash3(byte[] data, int offset) {
        int v = (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16);
        return (v * 0x1e35a7bd) >>> (32 - HASH_BITS);
    }

    private static int matchLength(byte[] block, int pos, int blockLen,
            byte[] history, int histStart, int histUse, int candAbs) {
        int maxLen = blockLen - pos;
        if (maxLen > MAX_MATCH) {
            maxLen = MAX_MATCH;
        }
        int len = 0;
        while (len < maxLen) {
            byte current = block[pos + len];
            byte reference;
            int refAbs = candAbs + len;
            if (refAbs < histUse) {
                reference = history[histStart + refAbs];
            } else {
                int boff = refAbs - histUse;
                if (boff >= blockLen) {
                    break;
                }
                reference = block[boff];
            }
            if (current != reference) {
                break;
            }
            len++;
        }
        return len;
    }
}

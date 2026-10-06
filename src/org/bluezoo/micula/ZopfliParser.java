/*
 * ZopfliParser.java
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
 * Zopfli-style shortest path over multi-match candidates for qualities 10–11.
 * Quality 10 is a single pass with rough costs. Quality 11 reruns with refined
 * costs and a few shorter LZ lengths. Emits the same
 * {@link Lz77Encoder.Command} objects the rest of the encoder already writes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ZopfliParser {

    private static final int HASH_BITS = 15;
    private static final int HASH_SIZE = 1 << HASH_BITS;
    private static final int MIN_MATCH = 4;
    private static final int MAX_MATCH = 16777215;
    private static final int CHAIN_DEPTH = 64;
    private static final int MAX_MATCHES = 16;
    private static final int FLAT_COMMAND_BITS = 4;
    private static final int MILLIBITS = 1000;
    private static final long INF = Long.MAX_VALUE / 4;
    private static final int MAX_ZOPFLI_LEN_Q11 = 325;
    private static final int SHORT_LENGTH_SAMPLES = 4;

    private ZopfliParser() {
    }

    /**
     * Parses {@code block[0..blockLen)} into commands using a millibit
     * shortest path. {@code distRing} is the metablock-start ring and is not
     * modified.
     *
     * @param quality 10 (one pass) or 11 (two passes)
     */
    static int encode(byte[] block, int blockLen,
            byte[] history, int historyLen,
            int windowSize, DistanceRing distRing, int quality,
            Lz77Encoder.Command[][] outCommands) throws BrotliException {
        if (blockLen <= 0) {
            outCommands[0] = new Lz77Encoder.Command[0];
            return 0;
        }

        int histUse = historyLen;
        if (histUse > windowSize) {
            histUse = windowSize;
        }
        int histStart = historyLen - histUse;

        int[] litCost = buildLiteralCostsFromBlock(block, blockLen);
        int[] cmdCost = null;
        int[] distCost = null;
        boolean shortLengths = false;

        // Pass 1
        shortestPath(block, blockLen, history, histStart, histUse, windowSize,
                distRing, litCost, cmdCost, distCost, shortLengths, outCommands);

        if (quality < 11) {
            return outCommands[0].length;
        }

        // Refine costs from pass-1 commands and rerun
        CostTables tables = buildCostTables(block, outCommands[0], distRing);
        shortestPath(block, blockLen, history, histStart, histUse, windowSize,
                distRing, tables.litCost, tables.cmdCost, tables.distCost,
                true, outCommands);
        return outCommands[0].length;
    }

    private static final class CostTables {
        final int[] litCost;
        final int[] cmdCost;
        final int[] distCost;

        CostTables(int[] litCost, int[] cmdCost, int[] distCost) {
            this.litCost = litCost;
            this.cmdCost = cmdCost;
            this.distCost = distCost;
        }
    }

    private static void shortestPath(byte[] block, int blockLen,
            byte[] history, int histStart, int histUse, int windowSize,
            DistanceRing distRing, int[] litCost, int[] cmdCost, int[] distCost,
            boolean shortLengths, Lz77Encoder.Command[][] outCommands)
            throws BrotliException {
        int[] hashHead = new int[HASH_SIZE];
        for (int i = 0; i < HASH_SIZE; i++) {
            hashHead[i] = -1;
        }
        int[] hashNext = new int[histUse + blockLen];
        for (int i = 0; i < hashNext.length; i++) {
            hashNext[i] = -1;
        }
        if (history != null && histUse >= MIN_MATCH) {
            for (int i = 0; i <= histUse - MIN_MATCH; i++) {
                int h = hash3(history, histStart + i);
                hashNext[i] = hashHead[h];
                hashHead[h] = i;
            }
        }

        long[] cost = new long[blockLen + 1];
        int[] prev = new int[blockLen + 1];
        int[] edgeLen = new int[blockLen + 1];
        int[] edgeDist = new int[blockLen + 1];
        int[] edgeWire = new int[blockLen + 1];
        boolean[] edgeDict = new boolean[blockLen + 1];
        for (int i = 1; i <= blockLen; i++) {
            cost[i] = INF;
            prev[i] = -1;
        }
        cost[0] = 0;

        int[] matchDist = new int[MAX_MATCHES + 1];
        int[] matchLen = new int[MAX_MATCHES + 1];
        int[] matchWire = new int[MAX_MATCHES + 1];
        boolean[] matchDict = new boolean[MAX_MATCHES + 1];
        int[] distOut = new int[3];
        int[] packOut = new int[5];
        int[] dictLenOut = new int[1];
        int[] dictCoveredOut = new int[1];
        int[] dictTransformOut = new int[1];
        int[] dictIndexOut = new int[1];
        int[] shortLens = new int[SHORT_LENGTH_SAMPLES + 1];

        for (int pos = 0; pos < blockLen; pos++) {
            if (cost[pos] >= INF) {
                continue;
            }

            long lit = cost[pos] + litCost[block[pos] & 0xff];
            if (lit < cost[pos + 1]) {
                cost[pos + 1] = lit;
                prev[pos + 1] = pos;
                edgeLen[pos + 1] = 1;
                edgeDist[pos + 1] = 0;
                edgeWire[pos + 1] = 0;
                edgeDict[pos + 1] = false;
            }

            int nMatches = findMatches(block, pos, blockLen, history, histStart,
                    histUse, windowSize, hashHead, hashNext,
                    matchDist, matchLen, matchWire, matchDict,
                    dictLenOut, dictCoveredOut, dictTransformOut, dictIndexOut);

            for (int m = 0; m < nMatches; m++) {
                int covered = matchLen[m];
                int wireLen = matchWire[m];
                if (wireLen < 2) {
                    wireLen = 2;
                }
                boolean dict = matchDict[m];
                int nLens = 1;
                shortLens[0] = covered;
                if (shortLengths && !dict && covered > MIN_MATCH) {
                    nLens = fillShortLengths(covered, shortLens);
                }
                for (int li = 0; li < nLens; li++) {
                    int len = shortLens[li];
                    int to = pos + len;
                    if (to > blockLen) {
                        continue;
                    }
                    int wire = dict ? wireLen : len;
                    if (wire < 2) {
                        wire = 2;
                    }
                    long matchCost = cost[pos]
                            + commandMillibits(0, wire, matchDist[m], distRing,
                                    distOut, packOut, cmdCost, distCost);
                    if (matchCost < cost[to]) {
                        cost[to] = matchCost;
                        prev[to] = pos;
                        edgeLen[to] = len;
                        edgeDist[to] = matchDist[m];
                        edgeWire[to] = wire;
                        edgeDict[to] = dict;
                    }
                }
            }

            if (pos + MIN_MATCH <= blockLen) {
                insertHash(block, pos, histUse, hashHead, hashNext);
            }
        }

        traceCommands(blockLen, prev, edgeLen, edgeDist, edgeWire, edgeDict,
                outCommands);
    }

    /**
     * Fills {@code out} with the full length plus up to
     * {@link #SHORT_LENGTH_SAMPLES} shorter samples in
     * {@code [MIN_MATCH, min(full, MAX_ZOPFLI_LEN_Q11)]}. Returns count.
     * {@code out[0]} is always the full length.
     */
    private static int fillShortLengths(int full, int[] out) {
        out[0] = full;
        int maxShort = full;
        if (maxShort > MAX_ZOPFLI_LEN_Q11) {
            maxShort = MAX_ZOPFLI_LEN_Q11;
        }
        if (maxShort <= MIN_MATCH) {
            return 1;
        }
        int n = 1;
        for (int i = 1; i <= SHORT_LENGTH_SAMPLES; i++) {
            // Spread samples across [4, maxShort]
            int len = MIN_MATCH
                    + ((maxShort - MIN_MATCH) * i) / (SHORT_LENGTH_SAMPLES + 1);
            if (len < MIN_MATCH) {
                len = MIN_MATCH;
            }
            if (len >= full) {
                continue;
            }
            boolean dup = false;
            for (int j = 0; j < n; j++) {
                if (out[j] == len) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out[n++] = len;
            }
        }
        return n;
    }

    private static long commandMillibits(int insertLen, int copyLen, int distance,
            DistanceRing ring, int[] distOut, int[] packOut,
            int[] cmdCost, int[] distCostTbl) throws BrotliException {
        InsertCopyLengths.pack(insertLen, copyLen, false, packOut);
        long cmdPart;
        if (cmdCost == null) {
            cmdPart = (FLAT_COMMAND_BITS + packOut[3] + packOut[4])
                    * (long) MILLIBITS;
        } else {
            cmdPart = cmdCost[packOut[0]]
                    + packOut[3] * (long) MILLIBITS
                    + packOut[4] * (long) MILLIBITS;
        }
        return cmdPart + distanceMillibits(distance, ring, distOut, distCostTbl);
    }

    private static long distanceMillibits(int distance, DistanceRing ring,
            int[] distOut, int[] distCostTbl) throws BrotliException {
        BrotliEncoder.encodeDistance(distance, 0, 0, ring, distOut);
        if (distCostTbl == null) {
            return distOut[2] * (long) MILLIBITS;
        }
        int code = distOut[0];
        if (code < 0 || code >= distCostTbl.length) {
            return distOut[2] * (long) MILLIBITS + 8L * MILLIBITS;
        }
        return distCostTbl[code] + distOut[2] * (long) MILLIBITS;
    }

    private static CostTables buildCostTables(byte[] block,
            Lz77Encoder.Command[] commands, DistanceRing distRing)
            throws BrotliException {
        int[] litHist = new int[256];
        int[] cmdHist = new int[704];
        int[] distHist = new int[64];
        int[] packOut = new int[5];
        int[] distOut = new int[3];
        int litTotal = 0;
        int cmdTotal = 0;
        int distTotal = 0;

        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];
            for (int j = 0; j < cmd.insertLen; j++) {
                litHist[block[cmd.insertOffset + j] & 0xff]++;
                litTotal++;
            }
            int copyLen = cmd.copyLen;
            if (copyLen < 2) {
                copyLen = 2;
            }
            boolean insertOnly = cmd.distance == 0 && cmd.copyCovered == 0;
            InsertCopyLengths.pack(cmd.insertLen, copyLen, insertOnly, packOut);
            cmdHist[packOut[0]]++;
            cmdTotal++;
            if (!insertOnly && cmd.distance != 0) {
                BrotliEncoder.encodeDistance(cmd.distance, 0, 0, distRing, distOut);
                int code = distOut[0];
                if (code >= distHist.length) {
                    int[] grown = new int[code + 1];
                    System.arraycopy(distHist, 0, grown, 0, distHist.length);
                    distHist = grown;
                }
                distHist[code]++;
                distTotal++;
            }
        }

        return new CostTables(
                histToMillibits(litHist, litTotal, 256),
                histToMillibits(cmdHist, cmdTotal, 704),
                histToMillibits(distHist, distTotal, distHist.length));
    }

    private static int[] histToMillibits(int[] hist, int total, int size) {
        int[] cost = new int[size];
        for (int i = 0; i < size; i++) {
            int c = i < hist.length ? hist[i] : 0;
            if (c <= 0 || total <= 0) {
                cost[i] = 8 * MILLIBITS;
            } else {
                double p = (double) c / (double) total;
                int mb = (int) Math.round(-Math.log(p) / Math.log(2.0) * MILLIBITS);
                if (mb < MILLIBITS) {
                    mb = MILLIBITS;
                }
                cost[i] = mb;
            }
        }
        return cost;
    }

    private static int[] buildLiteralCostsFromBlock(byte[] block, int blockLen) {
        int[] hist = new int[256];
        for (int i = 0; i < blockLen; i++) {
            hist[block[i] & 0xff]++;
        }
        return histToMillibits(hist, blockLen, 256);
    }

    private static int findMatches(byte[] block, int pos, int blockLen,
            byte[] history, int histStart, int histUse, int windowSize,
            int[] hashHead, int[] hashNext,
            int[] matchDist, int[] matchLen, int[] matchWire, boolean[] matchDict,
            int[] dictLenOut, int[] dictCoveredOut,
            int[] dictTransformOut, int[] dictIndexOut) {
        int n = 0;
        int absPos = histUse + pos;
        int maxDist = absPos;
        if (maxDist > windowSize) {
            maxDist = windowSize;
        }

        if (pos + MIN_MATCH <= blockLen && maxDist >= 1) {
            int h = hash3(block, pos);
            int candAbs = hashHead[h];
            int depth = 0;
            while (candAbs >= 0 && depth < CHAIN_DEPTH) {
                int distance = absPos - candAbs;
                if (distance >= 1 && distance <= maxDist) {
                    int len = matchLength(block, pos, blockLen,
                            history, histStart, histUse, candAbs);
                    if (len >= MIN_MATCH) {
                        n = upsertMatch(n, matchDist, matchLen, matchWire,
                                matchDict, distance, len, len, false);
                    }
                }
                if (candAbs >= hashNext.length) {
                    break;
                }
                candAbs = hashNext[candAbs];
                depth++;
            }
        }

        if (pos + 4 <= blockLen) {
            int avail = blockLen - pos;
            if (avail > Dictionary.MAX_TRANSFORMED_WORD_LENGTH) {
                avail = Dictionary.MAX_TRANSFORMED_WORD_LENGTH;
            }
            int covered = Dictionary.findMatch(block, pos, avail, true,
                    dictLenOut, dictCoveredOut, dictTransformOut, dictIndexOut);
            if (covered >= MIN_MATCH) {
                int wireLen = dictLenOut[0];
                int wordId = (dictTransformOut[0] << Dictionary.ndbits(wireLen))
                        + dictIndexOut[0];
                int distance = maxDist + 1 + wordId;
                if (n < MAX_MATCHES + 1) {
                    matchDist[n] = distance;
                    matchLen[n] = covered;
                    matchWire[n] = wireLen;
                    matchDict[n] = true;
                    n++;
                }
            }
        }
        return n;
    }

    private static int upsertMatch(int n, int[] matchDist, int[] matchLen,
            int[] matchWire, boolean[] matchDict,
            int distance, int covered, int wireLen, boolean dictionary) {
        for (int i = 0; i < n; i++) {
            if (!matchDict[i] && matchDist[i] == distance) {
                if (covered > matchLen[i]) {
                    matchLen[i] = covered;
                    matchWire[i] = wireLen;
                }
                return n;
            }
        }
        if (n < MAX_MATCHES) {
            matchDist[n] = distance;
            matchLen[n] = covered;
            matchWire[n] = wireLen;
            matchDict[n] = dictionary;
            return n + 1;
        }
        int worst = -1;
        int worstLen = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (!matchDict[i] && matchLen[i] < worstLen) {
                worstLen = matchLen[i];
                worst = i;
            }
        }
        if (worst >= 0 && covered > worstLen) {
            matchDist[worst] = distance;
            matchLen[worst] = covered;
            matchWire[worst] = wireLen;
            matchDict[worst] = dictionary;
        }
        return n;
    }

    private static void traceCommands(int blockLen, int[] prev, int[] edgeLen,
            int[] edgeDist, int[] edgeWire, boolean[] edgeDict,
            Lz77Encoder.Command[][] outCommands) {
        int[] starts = new int[blockLen + 1];
        int[] lens = new int[blockLen + 1];
        int[] dists = new int[blockLen + 1];
        int[] wires = new int[blockLen + 1];
        boolean[] dicts = new boolean[blockLen + 1];
        int edgeCount = 0;
        int at = blockLen;
        while (at > 0) {
            int from = prev[at];
            if (from < 0) {
                break;
            }
            starts[edgeCount] = from;
            lens[edgeCount] = edgeLen[at];
            dists[edgeCount] = edgeDist[at];
            wires[edgeCount] = edgeWire[at];
            dicts[edgeCount] = edgeDict[at];
            edgeCount++;
            at = from;
        }

        Lz77Encoder.Command[] cmds = new Lz77Encoder.Command[16];
        int cmdCount = 0;
        int litStart = 0;
        for (int e = edgeCount - 1; e >= 0; e--) {
            int from = starts[e];
            int len = lens[e];
            int dist = dists[e];
            if (dist == 0 && len == 1) {
                continue;
            }
            Lz77Encoder.Command c = new Lz77Encoder.Command();
            c.insertOffset = litStart;
            c.insertLen = from - litStart;
            c.copyLen = wires[e];
            c.copyCovered = len;
            c.distance = dist;
            c.dictionary = dicts[e];
            cmds = append(cmds, cmdCount, c);
            cmdCount++;
            litStart = from + len;
        }
        if (litStart < blockLen || cmdCount == 0) {
            Lz77Encoder.Command c = new Lz77Encoder.Command();
            c.insertOffset = litStart;
            c.insertLen = blockLen - litStart;
            c.copyLen = 2;
            c.copyCovered = 0;
            c.distance = 0;
            c.dictionary = false;
            cmds = append(cmds, cmdCount, c);
            cmdCount++;
        }

        Lz77Encoder.Command[] result = new Lz77Encoder.Command[cmdCount];
        System.arraycopy(cmds, 0, result, 0, cmdCount);
        outCommands[0] = result;
    }

    private static Lz77Encoder.Command[] append(Lz77Encoder.Command[] cmds,
            int count, Lz77Encoder.Command c) {
        if (count == cmds.length) {
            Lz77Encoder.Command[] grown = new Lz77Encoder.Command[cmds.length * 2];
            System.arraycopy(cmds, 0, grown, 0, cmds.length);
            cmds = grown;
        }
        cmds[count] = c;
        return cmds;
    }

    private static void insertHash(byte[] block, int pos, int histUse,
            int[] hashHead, int[] hashNext) {
        int absPos = histUse + pos;
        int h = hash3(block, pos);
        hashNext[absPos] = hashHead[h];
        hashHead[h] = absPos;
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

/*
 * BrotliEncoder.java
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

import java.nio.ByteBuffer;

/**
 * Push-model Brotli encoder.
 *
 * <p>Quality 0 emits uncompressed metablocks. Qualities 1–8 use LZ77 with
 * Huffman coding. Quality 2+ uses the static dictionary; 3+ deepens LZ search;
 * 4+ matches transformed dictionary words; 5+ uses literal context maps;
 * 6 adds a literal block split and distance context trees; 7 tunes
 * NPOSTFIX/NDIRECT; 8 uses up to four literal trees and an insert-and-copy
 * block split.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BrotliEncoder {

    private static final int MAX_METABLOCK = 1 << 24;
    private static final int DEFAULT_BLOCK = 1 << 16;

    private static final int MAX_DIST_ALPHABET = 256;
    private static final int[][] POSTFIX_CANDIDATES = {
        { 0, 0 },
        { 0, 4 },
        { 1, 0 },
        { 2, 0 }
    };

    private final BitWriter bw;
    private int quality = 0;
    private int windowBits = 22;
    private boolean headerWritten;
    private boolean finished;
    private final byte[] copyBuf = new byte[8192];

    /** Pending uncompressed bytes for qualities 1–2. */
    private byte[] pending = new byte[DEFAULT_BLOCK];
    private int pendingLen;

    /** Sliding window of previously encoded uncompressed bytes. */
    private byte[] history = new byte[1];
    private int historyLen;

    private final DistanceRing distRing = new DistanceRing();

    private final int[] litHist = new int[256];
    private final int[] litHist1 = new int[256];
    private final int[] litHist2 = new int[256];
    private final int[] litHist3 = new int[256];
    private final int[] iacHist = new int[704];
    private final int[] iacHist1 = new int[704];
    private final int[] distHist = new int[MAX_DIST_ALPHABET];
    private final int[] distHist1 = new int[MAX_DIST_ALPHABET];
    private final int[] litLens = new int[256];
    private final int[] litLens1 = new int[256];
    private final int[] litLens2 = new int[256];
    private final int[] litLens3 = new int[256];
    private final int[] iacLens = new int[704];
    private final int[] iacLens1 = new int[704];
    private final int[] distLens = new int[MAX_DIST_ALPHABET];
    private final int[] distLens1 = new int[MAX_DIST_ALPHABET];
    private final int[] litCodes = new int[256];
    private final int[] litCodes1 = new int[256];
    private final int[] litCodes2 = new int[256];
    private final int[] litCodes3 = new int[256];
    private final int[] iacCodeTbl = new int[704];
    private final int[] iacCodeTbl1 = new int[704];
    private final int[] distCodeTbl = new int[MAX_DIST_ALPHABET];
    private final int[] distCodeTbl1 = new int[MAX_DIST_ALPHABET];
    private final int[] packOut = new int[5];
    private final int[] distOut = new int[3];
    private final int[] blenPack = new int[3];
    private final int[] blenHist = new int[26];
    private final int[] blenLens = new int[26];
    private final int[] blenCodes = new int[26];
    private final int[] btypeHist = new int[4];
    private final int[] btypeLens = new int[4];
    private final int[] btypeCodes = new int[4];
    private final int[] cmapL = new int[128];
    private final int[] cmapD = new int[4];
    private final int[] litTreeOfContext = new int[64];
    private final int[] distScratch = new int[4096];
    private final int[] iBtypeLens = new int[4];
    private final int[] iBtypeCodes = new int[4];
    private final int[] iBlenLens = new int[26];
    private final int[] iBlenCodes = new int[26];

    private int[] iacCodes;
    private int[] insertExtras;
    private int[] copyExtras;
    private int[] insertExtraBits;
    private int[] copyExtraBits;
    private int[] distCodes;
    private int[] distExtras;
    private int[] distExtraBits;
    private boolean[] writeDist;
    private int cmdWorkspaceLen;

    /**
     * Creates an encoder that writes compressed bytes to {@code sink}.
     *
     * @param sink compressed output sink
     */
    public BrotliEncoder(BrotliSink sink) {
        this(sink, false);
    }

    /**
     * Creates an encoder.
     *
     * @param sink compressed output sink
     * @param direct whether the bit writer should prefer direct buffers
     */
    public BrotliEncoder(BrotliSink sink, boolean direct) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.bw = new BitWriter(sink, direct);
    }

    /**
     * Sets compression quality. Accepts 0..11 for API stability; only 0–8
     * are implemented.
     *
     * @param quality compression quality
     */
    public void setQuality(int quality) {
        if (quality < 0 || quality > 11) {
            throw new IllegalArgumentException("quality must be 0..11");
        }
        if (headerWritten) {
            throw new IllegalStateException("quality cannot change after encode started");
        }
        this.quality = quality;
    }

    /**
     * Sets window bits (10–24).
     *
     * @param windowBits WBITS
     */
    public void setWindowBits(int windowBits) {
        if (windowBits < 10 || windowBits > 24) {
            throw new IllegalArgumentException("windowBits must be 10..24");
        }
        if (headerWritten) {
            throw new IllegalStateException("windowBits cannot change after encode started");
        }
        this.windowBits = windowBits;
    }

    /**
     * Encodes the next chunk of uncompressed input.
     *
     * @param data uncompressed bytes in read mode
     * @throws BrotliException on encode failure
     */
    public void receive(ByteBuffer data) throws BrotliException {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (finished) {
            throw new BrotliException("Encoder already finished");
        }
        if (quality > 8) {
            throw new BrotliException("Quality " + quality + " not implemented");
        }
        ensureHeader();
        if (quality == 0) {
            while (data.hasRemaining()) {
                int chunk = data.remaining();
                if (chunk > MAX_METABLOCK) {
                    chunk = MAX_METABLOCK;
                }
                writeUncompressedMetablock(data, chunk, false);
            }
            return;
        }
        while (data.hasRemaining()) {
            ensurePendingCapacity(pendingLen + 1);
            int room = pending.length - pendingLen;
            int n = data.remaining();
            if (n > room) {
                n = room;
            }
            data.get(pending, pendingLen, n);
            pendingLen += n;
            while (pendingLen >= DEFAULT_BLOCK) {
                flushCompressedMetablock(DEFAULT_BLOCK, false);
            }
        }
    }

    /**
     * Forces a metablock boundary, flushing any buffered input as a
     * non-last metablock. No-op for quality 0 (already emitted per receive)
     * when there is nothing pending.
     *
     * @throws BrotliException on encode failure
     */
    public void flush() throws BrotliException {
        if (finished) {
            throw new BrotliException("Encoder already finished");
        }
        if (quality > 8) {
            throw new BrotliException("Quality " + quality + " not implemented");
        }
        ensureHeader();
        if (quality == 0) {
            return;
        }
        if (pendingLen > 0) {
            flushCompressedMetablock(pendingLen, false);
        }
    }

    /**
     * Finishes the stream (writes the final empty last metablock if needed).
     *
     * @throws BrotliException on encode failure
     */
    public void close() throws BrotliException {
        if (finished) {
            return;
        }
        ensureHeader();
        if (quality > 8) {
            throw new BrotliException("Quality " + quality + " not implemented");
        }
        if (quality == 0) {
            bw.writeBits(1, 1);
            bw.writeBits(1, 1);
            bw.finish();
            finished = true;
            return;
        }
        if (pendingLen > 0) {
            flushCompressedMetablock(pendingLen, true);
        } else {
            bw.writeBits(1, 1);
            bw.writeBits(1, 1);
        }
        bw.finish();
        finished = true;
    }

    /**
     * Resets encoder state for a new stream (same sink).
     */
    public void reset() {
        bw.reset();
        headerWritten = false;
        finished = false;
        pendingLen = 0;
        historyLen = 0;
        distRing.reset();
    }

    private void ensureHeader() throws BrotliException {
        if (headerWritten) {
            return;
        }
        writeWindowBits(windowBits);
        int windowSize = (1 << windowBits) - 16;
        if (history.length < windowSize) {
            history = new byte[windowSize];
        }
        headerWritten = true;
    }

    private void writeWindowBits(int wbits) throws BrotliException {
        if (wbits == 16) {
            bw.writeBits(0, 1);
            return;
        }
        bw.writeBits(1, 1);
        if (wbits >= 18) {
            bw.writeBits(wbits - 17, 3);
            return;
        }
        bw.writeBits(0, 3);
        if (wbits == 17) {
            bw.writeBits(0, 3);
        } else {
            bw.writeBits(wbits - 8, 3);
        }
    }

    private void writeUncompressedMetablock(ByteBuffer data, int length, boolean last)
            throws BrotliException {
        bw.writeBits(last ? 1 : 0, 1);
        if (last) {
            bw.writeBits(0, 1);
        }
        writeMlen(length);
        if (!last) {
            bw.writeBits(1, 1);
        } else {
            throw new BrotliException("Internal: last uncompressed not supported");
        }
        bw.jumpToByteBoundary();
        int remaining = length;
        while (remaining > 0) {
            int n = remaining < copyBuf.length ? remaining : copyBuf.length;
            data.get(copyBuf, 0, n);
            for (int i = 0; i < n; i++) {
                bw.writeBits(copyBuf[i] & 0xff, 8);
            }
            remaining -= n;
        }
    }

    private void writeMlen(int length) throws BrotliException {
        if (length <= 0 || length > MAX_METABLOCK) {
            throw new BrotliException("Invalid metablock length " + length);
        }
        int ml = length - 1;
        int mnibbles;
        if (ml < (1 << 16)) {
            mnibbles = 4;
        } else if (ml < (1 << 20)) {
            mnibbles = 5;
        } else {
            mnibbles = 6;
        }
        bw.writeBits(mnibbles - 4, 2);
        bw.writeBits(ml, mnibbles * 4);
    }

    private void ensurePendingCapacity(int needed) {
        if (needed <= pending.length) {
            return;
        }
        int cap = pending.length;
        while (cap < needed) {
            cap *= 2;
        }
        byte[] grown = new byte[cap];
        System.arraycopy(pending, 0, grown, 0, pendingLen);
        pending = grown;
    }

    private void flushCompressedMetablock(int length, boolean last)
            throws BrotliException {
        int windowSize = (1 << windowBits) - 16;
        Lz77Encoder.MatchMode mode = Lz77Encoder.MatchMode.forQuality(quality);

        Lz77Encoder.Command[][] box = new Lz77Encoder.Command[1][];
        Lz77Encoder.encode(pending, length, history, historyLen, windowSize,
                mode, box);
        Lz77Encoder.Command[] commands = box[0];

        writeCompressedMetablock(pending, length, commands, last);

        // Append encoded bytes to history
        appendHistory(pending, length, windowSize);

        // Shift pending
        int remain = pendingLen - length;
        if (remain > 0) {
            System.arraycopy(pending, length, pending, 0, remain);
        }
        pendingLen = remain;
    }

    private void appendHistory(byte[] data, int length, int windowSize) {
        if (history.length < windowSize) {
            history = new byte[windowSize];
            historyLen = 0;
        }
        if (length >= windowSize) {
            System.arraycopy(data, length - windowSize, history, 0, windowSize);
            historyLen = windowSize;
            return;
        }
        if (historyLen + length <= windowSize) {
            System.arraycopy(data, 0, history, historyLen, length);
            historyLen += length;
            return;
        }
        int keep = windowSize - length;
        System.arraycopy(history, historyLen - keep, history, 0, keep);
        System.arraycopy(data, 0, history, keep, length);
        historyLen = windowSize;
    }

    private void writeCompressedMetablock(byte[] data, int length,
            Lz77Encoder.Command[] commands, boolean last) throws BrotliException {
        if (quality >= 5) {
            writeCompressedMetablockContext(data, length, commands, last);
            return;
        }
        writeCompressedMetablockSimple(data, length, commands, last);
    }

    private void writeCompressedMetablockSimple(byte[] data, int length,
            Lz77Encoder.Command[] commands, boolean last) throws BrotliException {
        // Header
        bw.writeBits(last ? 1 : 0, 1);
        if (last) {
            bw.writeBits(0, 1); // not empty
        }
        writeMlen(length);
        if (!last) {
            bw.writeBits(0, 1); // ISUNCOMPRESSED=0
        }

        // NBLTYPESL = NBLTYPESI = NBLTYPESD = 1
        bw.writeBits(0, 1);
        bw.writeBits(0, 1);
        bw.writeBits(0, 1);

        // NPOSTFIX=0, NDIRECT=0
        bw.writeBits(0, 2);
        bw.writeBits(0, 4);

        // Context mode LSB6 for the single literal block type
        bw.writeBits(0, 2);

        // NTREESL=1, NTREESD=1 (context maps omitted when NTREES==1)
        bw.writeBits(0, 1);
        bw.writeBits(0, 1);

        clearHistogram(litHist);
        clearHistogram(iacHist);
        clearHistogram(distHist);
        ensureCmdWorkspace(commands.length);

        int bytesEmitted = 0;
        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];
            for (int j = 0; j < cmd.insertLen; j++) {
                litHist[data[cmd.insertOffset + j] & 0xff]++;
            }
            bytesEmitted += cmd.insertLen;

            boolean insertOnly = (cmd.distance == 0);
            boolean skipDistance = bytesEmitted >= length;

            boolean preferImplicit = false;
            if (insertOnly || skipDistance) {
                preferImplicit = true;
            } else if (!cmd.dictionary && cmd.distance == distRing.get(0)) {
                preferImplicit = true;
            }

            int copyLen = cmd.copyLen;
            if (copyLen < 2) {
                copyLen = 2;
            }
            InsertCopyLengths.pack(cmd.insertLen, copyLen, preferImplicit, packOut);
            boolean implicitDist0 = packOut[0] < 128;
            iacCodes[ci] = packOut[0];
            insertExtras[ci] = packOut[1];
            copyExtras[ci] = packOut[2];
            insertExtraBits[ci] = packOut[3];
            copyExtraBits[ci] = packOut[4];
            iacHist[iacCodes[ci]]++;

            writeDist[ci] = false;
            if (!skipDistance && !insertOnly) {
                writeDist[ci] = !implicitDist0;
                if (!implicitDist0) {
                    encodeDistance(cmd.distance, distRing, distOut);
                    distCodes[ci] = distOut[0];
                    distExtras[ci] = distOut[1];
                    distExtraBits[ci] = distOut[2];
                    distHist[distCodes[ci]]++;
                    if (!cmd.dictionary && distCodes[ci] != 0) {
                        distRing.push(cmd.distance);
                    }
                }
                bytesEmitted += cmd.copyCovered;
                if (bytesEmitted > length) {
                    bytesEmitted = length;
                }
            }
        }

        if (sum(litHist) == 0) {
            litHist[0] = 1;
        }
        if (sum(iacHist) == 0) {
            iacHist[0] = 1;
        }
        // NPOSTFIX=0, NDIRECT=0 → distance alphabet 64
        final int distAlphabet = 64;
        if (sumRange(distHist, distAlphabet) == 0) {
            distHist[0] = 1;
        }

        int[] distHistWrite = trimHist(distHist, distAlphabet);
        int[] distLensWrite = new int[distAlphabet];
        int[] distCodesWrite = new int[distAlphabet];

        HuffmanEncoder.assignLengths(litHist, litLens, 15);
        HuffmanEncoder.assignLengths(iacHist, iacLens, 15);
        HuffmanEncoder.assignLengths(distHistWrite, distLensWrite, 15);

        HuffmanTable.buildEncodeTables(litLens, litCodes);
        HuffmanTable.buildEncodeTables(iacLens, iacCodeTbl);
        HuffmanTable.buildEncodeTables(distLensWrite, distCodesWrite);
        System.arraycopy(distLensWrite, 0, distLens, 0, distAlphabet);
        System.arraycopy(distCodesWrite, 0, distCodeTbl, 0, distAlphabet);

        HuffmanEncoder.writePrefixCode(bw, litHist);
        HuffmanEncoder.writePrefixCode(bw, iacHist);
        HuffmanEncoder.writePrefixCode(bw, distHistWrite);

        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];
            int code = iacCodes[ci];
            int len = iacLens[code];
            if (len > 0) {
                bw.writePrefixBits(iacCodeTbl[code], len);
            }
            bw.writeBits(insertExtras[ci], insertExtraBits[ci]);
            bw.writeBits(copyExtras[ci], copyExtraBits[ci]);

            for (int j = 0; j < cmd.insertLen; j++) {
                int lit = data[cmd.insertOffset + j] & 0xff;
                int llen = litLens[lit];
                if (llen > 0) {
                    bw.writePrefixBits(litCodes[lit], llen);
                }
            }

            if (writeDist[ci]) {
                int dc = distCodes[ci];
                int dlen = distLens[dc];
                if (dlen > 0) {
                    bw.writePrefixBits(distCodeTbl[dc], dlen);
                }
                bw.writeBits(distExtras[ci], distExtraBits[ci]);
            }
        }
    }

    private void writeCompressedMetablockContext(byte[] data, int length,
            Lz77Encoder.Command[] commands, boolean last) throws BrotliException {
        boolean useLitBlockSplit = quality >= 6;
        boolean useDistTrees = quality >= 6;
        boolean useFourLitTrees = quality >= 8;
        boolean useIacSplit = quality >= 8;
        boolean choosePostfix = quality >= 7;

        int totalLiterals = 0;
        for (int ci = 0; ci < commands.length; ci++) {
            totalLiterals += commands[ci].insertLen;
        }

        int nbltypesL = 1;
        int firstLitBlockLen = totalLiterals;
        if (useLitBlockSplit && totalLiterals >= 64) {
            nbltypesL = 2;
            firstLitBlockLen = totalLiterals / 2;
            if (firstLitBlockLen < 1) {
                firstLitBlockLen = 1;
            }
            if (firstLitBlockLen >= totalLiterals) {
                firstLitBlockLen = totalLiterals - 1;
            }
        } else {
            useLitBlockSplit = false;
            nbltypesL = 1;
            firstLitBlockLen = totalLiterals > 0 ? totalLiterals : 1;
        }

        int nbltypesI = 1;
        int firstIacBlock = commands.length;
        if (useIacSplit && commands.length >= 32) {
            nbltypesI = 2;
            firstIacBlock = commands.length / 2;
            if (firstIacBlock < 1) {
                firstIacBlock = 1;
            }
            if (firstIacBlock >= commands.length) {
                nbltypesI = 1;
                useIacSplit = false;
                firstIacBlock = commands.length;
            }
        } else {
            useIacSplit = false;
        }

        int npostfix = 0;
        int ndirect = 0;
        if (choosePostfix) {
            int[] chosen = choosePostfixPair(commands, length);
            npostfix = chosen[0];
            ndirect = chosen[1];
        }
        int distAlphabet = 16 + ndirect + (48 << npostfix);

        // --- Pass 1: IAC / distance codes plus context histograms
        clearHistogram(litHist);
        clearHistogram(litHist1);
        clearHistogram(litHist2);
        clearHistogram(litHist3);
        clearHistogram(iacHist);
        clearHistogram(iacHist1);
        clearHistogram(distHist);
        clearHistogram(distHist1);
        ensureCmdWorkspace(commands.length);

        int p1 = 0;
        int p2 = 0;
        int pos = 0;
        int litCount = 0;
        int bytesEmitted = 0;

        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];
            for (int j = 0; j < cmd.insertLen; j++) {
                int lit = data[cmd.insertOffset + j] & 0xff;
                int cid = Context.literalContextId(Context.UTF8, p1, p2);
                int bucket = useFourLitTrees ? (cid >> 4) : (cid < 32 ? 0 : 1);
                litHistBucket(bucket)[lit]++;
                p2 = p1;
                p1 = lit;
                litCount++;
                pos++;
            }
            bytesEmitted += cmd.insertLen;

            boolean insertOnly = (cmd.distance == 0);
            boolean skipDistance = bytesEmitted >= length;

            boolean preferImplicit = false;
            if (insertOnly || skipDistance) {
                preferImplicit = true;
            } else if (!cmd.dictionary && cmd.distance == distRing.get(0)) {
                preferImplicit = true;
            }

            int copyLen = cmd.copyLen;
            if (copyLen < 2) {
                copyLen = 2;
            }
            InsertCopyLengths.pack(cmd.insertLen, copyLen, preferImplicit, packOut);
            boolean implicitDist0 = packOut[0] < 128;
            iacCodes[ci] = packOut[0];
            insertExtras[ci] = packOut[1];
            copyExtras[ci] = packOut[2];
            insertExtraBits[ci] = packOut[3];
            copyExtraBits[ci] = packOut[4];
            if (nbltypesI >= 2 && ci >= firstIacBlock) {
                iacHist1[iacCodes[ci]]++;
            } else {
                iacHist[iacCodes[ci]]++;
            }

            writeDist[ci] = false;
            if (!skipDistance && !insertOnly) {
                writeDist[ci] = !implicitDist0;
                if (!implicitDist0) {
                    encodeDistance(cmd.distance, npostfix, ndirect, distRing, distOut);
                    distCodes[ci] = distOut[0];
                    distExtras[ci] = distOut[1];
                    distExtraBits[ci] = distOut[2];
                    if (distCodes[ci] >= distAlphabet) {
                        throw new BrotliException("Distance code out of alphabet");
                    }
                    int dcid = Context.distanceContextId(copyLen);
                    if (useDistTrees && dcid > 0) {
                        distHist1[distCodes[ci]]++;
                    } else {
                        distHist[distCodes[ci]]++;
                    }
                    if (!cmd.dictionary && distCodes[ci] != 0) {
                        distRing.push(cmd.distance);
                    }
                }
                for (int j = 0; j < cmd.copyCovered; j++) {
                    int b = data[pos] & 0xff;
                    p2 = p1;
                    p1 = b;
                    pos++;
                }
                bytesEmitted += cmd.copyCovered;
                if (bytesEmitted > length) {
                    bytesEmitted = length;
                }
            }
        }

        // Compact literal trees
        int[][] litBuckets = new int[][] { litHist, litHist1, litHist2, litHist3 };
        int maxBuckets = useFourLitTrees ? 4 : 2;
        int[] bucketToTree = new int[4];
        int ntreesL = 0;
        for (int b = 0; b < maxBuckets; b++) {
            if (sum(litBuckets[b]) > 0) {
                bucketToTree[b] = ntreesL;
                if (ntreesL != b) {
                    System.arraycopy(litBuckets[b], 0, litBuckets[ntreesL], 0, 256);
                    clearHistogram(litBuckets[b]);
                }
                ntreesL++;
            } else {
                bucketToTree[b] = -1;
            }
        }
        if (ntreesL == 0) {
            litHist[0] = 1;
            ntreesL = 1;
            bucketToTree[0] = 0;
        }
        for (int cid = 0; cid < 64; cid++) {
            int bucket = useFourLitTrees ? (cid >> 4) : (cid < 32 ? 0 : 1);
            int t = bucketToTree[bucket];
            litTreeOfContext[cid] = (t >= 0) ? t : 0;
        }

        int ntreesD = 1;
        if (useDistTrees && sumRange(distHist1, distAlphabet) > 0
                && sumRange(distHist, distAlphabet) > 0) {
            ntreesD = 2;
        } else if (useDistTrees && sumRange(distHist1, distAlphabet) > 0) {
            System.arraycopy(distHist1, 0, distHist, 0, distAlphabet);
            clearHistogram(distHist1);
            ntreesD = 1;
        }
        if (sumRange(distHist, distAlphabet) == 0) {
            distHist[0] = 1;
        }
        if (sum(iacHist) == 0) {
            iacHist[0] = 1;
        }
        if (nbltypesI >= 2 && sum(iacHist1) == 0) {
            iacHist1[0] = 1;
        }

        int mapLSize = 64 * nbltypesL;
        for (int bt = 0; bt < nbltypesL; bt++) {
            for (int cid = 0; cid < 64; cid++) {
                cmapL[64 * bt + cid] = litTreeOfContext[cid];
            }
        }
        cmapD[0] = 0;
        cmapD[1] = (ntreesD >= 2) ? 1 : 0;
        cmapD[2] = (ntreesD >= 2) ? 1 : 0;
        cmapD[3] = (ntreesD >= 2) ? 1 : 0;

        // --- Header ---
        bw.writeBits(last ? 1 : 0, 1);
        if (last) {
            bw.writeBits(0, 1);
        }
        writeMlen(length);
        if (!last) {
            bw.writeBits(0, 1);
        }

        // NBLTYPESL
        writeBlockTypeGroupHeader(nbltypesL, firstLitBlockLen,
                totalLiterals - firstLitBlockLen);

        // NBLTYPESI
        writeBlockTypeGroupHeader(nbltypesI, firstIacBlock,
                commands.length - firstIacBlock);

        // NBLTYPESD = 1
        bw.writeBits(0, 1);

        // NPOSTFIX / NDIRECT
        bw.writeBits(npostfix, 2);
        bw.writeBits(ndirect >> npostfix, 4);

        for (int i = 0; i < nbltypesL; i++) {
            bw.writeBits(Context.UTF8, 2);
        }

        if (ntreesL == 1) {
            bw.writeBits(0, 1);
        } else {
            ContextMapWriter.writeVarLenUint8PlusOne(bw, ntreesL);
            ContextMapWriter.write(bw, cmapL, mapLSize, ntreesL);
        }

        if (ntreesD == 1) {
            bw.writeBits(0, 1);
        } else {
            ContextMapWriter.writeVarLenUint8PlusOne(bw, ntreesD);
            ContextMapWriter.write(bw, cmapD, 4, ntreesD);
        }

        // Literal trees
        int[][] litLensArr = new int[][] { litLens, litLens1, litLens2, litLens3 };
        int[][] litCodesArr = new int[][] { litCodes, litCodes1, litCodes2, litCodes3 };
        for (int t = 0; t < ntreesL; t++) {
            HuffmanEncoder.assignLengths(litBuckets[t], litLensArr[t], 15);
            HuffmanTable.buildEncodeTables(litLensArr[t], litCodesArr[t]);
            HuffmanEncoder.writePrefixCode(bw, litBuckets[t]);
        }

        // Insert-and-copy trees
        HuffmanEncoder.assignLengths(iacHist, iacLens, 15);
        HuffmanTable.buildEncodeTables(iacLens, iacCodeTbl);
        HuffmanEncoder.writePrefixCode(bw, iacHist);
        if (nbltypesI >= 2) {
            HuffmanEncoder.assignLengths(iacHist1, iacLens1, 15);
            HuffmanTable.buildEncodeTables(iacLens1, iacCodeTbl1);
            HuffmanEncoder.writePrefixCode(bw, iacHist1);
        }

        // Distance trees (trim histograms to alphabet for writePrefixCode)
        int[] distHistWrite = trimHist(distHist, distAlphabet);
        int[] distLensWrite = new int[distAlphabet];
        int[] distCodesWrite = new int[distAlphabet];
        HuffmanEncoder.assignLengths(distHistWrite, distLensWrite, 15);
        HuffmanTable.buildEncodeTables(distLensWrite, distCodesWrite);
        HuffmanEncoder.writePrefixCode(bw, distHistWrite);
        System.arraycopy(distLensWrite, 0, distLens, 0, distAlphabet);
        System.arraycopy(distCodesWrite, 0, distCodeTbl, 0, distAlphabet);
        if (ntreesD >= 2) {
            int[] distHist1Write = trimHist(distHist1, distAlphabet);
            int[] distLens1Write = new int[distAlphabet];
            int[] distCodes1Write = new int[distAlphabet];
            HuffmanEncoder.assignLengths(distHist1Write, distLens1Write, 15);
            HuffmanTable.buildEncodeTables(distLens1Write, distCodes1Write);
            HuffmanEncoder.writePrefixCode(bw, distHist1Write);
            System.arraycopy(distLens1Write, 0, distLens1, 0, distAlphabet);
            System.arraycopy(distCodes1Write, 0, distCodeTbl1, 0, distAlphabet);
        }

        // Rebuild switch tables: writing I overwrote L's tables in the fields
        if (nbltypesL >= 2) {
            clearHistogram(btypeHist);
            btypeHist[1] = 1;
            HuffmanEncoder.assignLengths(btypeHist, btypeLens, 15);
            HuffmanTable.buildEncodeTables(btypeLens, btypeCodes);
            clearHistogram(blenHist);
            BlockLengthEncoder.pack(firstLitBlockLen, blenPack);
            blenHist[blenPack[0]]++;
            BlockLengthEncoder.pack(totalLiterals - firstLitBlockLen, blenPack);
            blenHist[blenPack[0]]++;
            HuffmanEncoder.assignLengths(blenHist, blenLens, 15);
            HuffmanTable.buildEncodeTables(blenLens, blenCodes);
        }
        if (nbltypesI >= 2) {
            clearHistogram(btypeHist);
            btypeHist[1] = 1;
            HuffmanEncoder.assignLengths(btypeHist, iBtypeLens, 15);
            HuffmanTable.buildEncodeTables(iBtypeLens, iBtypeCodes);
            clearHistogram(blenHist);
            BlockLengthEncoder.pack(firstIacBlock, blenPack);
            blenHist[blenPack[0]]++;
            BlockLengthEncoder.pack(commands.length - firstIacBlock, blenPack);
            blenHist[blenPack[0]]++;
            HuffmanEncoder.assignLengths(blenHist, iBlenLens, 15);
            HuffmanTable.buildEncodeTables(iBlenLens, iBlenCodes);
        }

        // --- Commands ---
        p1 = 0;
        p2 = 0;
        pos = 0;
        litCount = 0;
        boolean litSwitched = false;
        boolean iacSwitched = false;

        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];

            if (nbltypesI >= 2 && !iacSwitched && ci == firstIacBlock) {
                int btLen = iBtypeLens[1];
                if (btLen > 0) {
                    bw.writePrefixBits(iBtypeCodes[1], btLen);
                }
                BlockLengthEncoder.write(bw, commands.length - firstIacBlock,
                        iBlenLens, iBlenCodes);
                iacSwitched = true;
            }

            boolean useIac1 = nbltypesI >= 2 && ci >= firstIacBlock;
            int code = iacCodes[ci];
            if (useIac1) {
                int len = iacLens1[code];
                if (len > 0) {
                    bw.writePrefixBits(iacCodeTbl1[code], len);
                }
            } else {
                int len = iacLens[code];
                if (len > 0) {
                    bw.writePrefixBits(iacCodeTbl[code], len);
                }
            }
            bw.writeBits(insertExtras[ci], insertExtraBits[ci]);
            bw.writeBits(copyExtras[ci], copyExtraBits[ci]);

            for (int j = 0; j < cmd.insertLen; j++) {
                if (nbltypesL >= 2 && !litSwitched && litCount == firstLitBlockLen) {
                    int btLen = btypeLens[1];
                    if (btLen > 0) {
                        bw.writePrefixBits(btypeCodes[1], btLen);
                    }
                    BlockLengthEncoder.write(bw, totalLiterals - firstLitBlockLen,
                            blenLens, blenCodes);
                    litSwitched = true;
                }
                int lit = data[cmd.insertOffset + j] & 0xff;
                int cid = Context.literalContextId(Context.UTF8, p1, p2);
                int tree = litTreeOfContext[cid];
                int[] lens = litLensArr[tree];
                int[] codes = litCodesArr[tree];
                int llen = lens[lit];
                if (llen > 0) {
                    bw.writePrefixBits(codes[lit], llen);
                }
                p2 = p1;
                p1 = lit;
                litCount++;
                pos++;
            }

            if (writeDist[ci]) {
                int dc = distCodes[ci];
                int copyLenWire = cmd.copyLen;
                if (copyLenWire < 2) {
                    copyLenWire = 2;
                }
                int dcid = Context.distanceContextId(copyLenWire);
                if (ntreesD >= 2 && dcid > 0) {
                    int dlen = distLens1[dc];
                    if (dlen > 0) {
                        bw.writePrefixBits(distCodeTbl1[dc], dlen);
                    }
                } else {
                    int dlen = distLens[dc];
                    if (dlen > 0) {
                        bw.writePrefixBits(distCodeTbl[dc], dlen);
                    }
                }
                bw.writeBits(distExtras[ci], distExtraBits[ci]);
            }

            if (cmd.distance != 0 || cmd.copyCovered > 0) {
                for (int j = 0; j < cmd.copyCovered; j++) {
                    int b = data[pos] & 0xff;
                    p2 = p1;
                    p1 = b;
                    pos++;
                }
            }
        }
    }

    private int[] litHistBucket(int bucket) {
        switch (bucket) {
            case 0:
                return litHist;
            case 1:
                return litHist1;
            case 2:
                return litHist2;
            default:
                return litHist3;
        }
    }

    private void writeBlockTypeGroupHeader(int nbltypes, int firstLen, int secondLen)
            throws BrotliException {
        if (nbltypes == 1) {
            bw.writeBits(0, 1);
            return;
        }
        ContextMapWriter.writeVarLenUint8PlusOne(bw, nbltypes);
        clearHistogram(btypeHist);
        btypeHist[1] = 1;
        HuffmanEncoder.writePrefixCode(bw, btypeHist);
        HuffmanEncoder.assignLengths(btypeHist, btypeLens, 15);
        HuffmanTable.buildEncodeTables(btypeLens, btypeCodes);

        clearHistogram(blenHist);
        BlockLengthEncoder.pack(firstLen, blenPack);
        blenHist[blenPack[0]]++;
        BlockLengthEncoder.pack(secondLen, blenPack);
        blenHist[blenPack[0]]++;
        HuffmanEncoder.writePrefixCode(bw, blenHist);
        HuffmanEncoder.assignLengths(blenHist, blenLens, 15);
        HuffmanTable.buildEncodeTables(blenLens, blenCodes);

        BlockLengthEncoder.write(bw, firstLen, blenLens, blenCodes);
    }

    private int[] choosePostfixPair(Lz77Encoder.Command[] commands, int length)
            throws BrotliException {
        int nDist = 0;
        int bytesEmitted = 0;
        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];
            bytesEmitted += cmd.insertLen;
            boolean insertOnly = (cmd.distance == 0);
            boolean skipDistance = bytesEmitted >= length;
            boolean preferImplicit = insertOnly || skipDistance
                    || (!cmd.dictionary && cmd.distance == distRing.get(0));
            int copyLen = cmd.copyLen;
            if (copyLen < 2) {
                copyLen = 2;
            }
            InsertCopyLengths.pack(cmd.insertLen, copyLen, preferImplicit, packOut);
            boolean implicitDist0 = packOut[0] < 128;
            if (!skipDistance && !insertOnly && !implicitDist0) {
                if (nDist < distScratch.length) {
                    distScratch[nDist++] = cmd.distance;
                }
            }
            if (!skipDistance && !insertOnly) {
                bytesEmitted += cmd.copyCovered;
                if (bytesEmitted > length) {
                    bytesEmitted = length;
                }
            }
        }

        int bestPost = 0;
        int bestDirect = 0;
        int bestCost = Integer.MAX_VALUE;
        for (int i = 0; i < POSTFIX_CANDIDATES.length; i++) {
            int np = POSTFIX_CANDIDATES[i][0];
            int nd = POSTFIX_CANDIDATES[i][1];
            int cost = 0;
            // Score with the metablock-start ring (no pushes); extra-bit sum only
            for (int d = 0; d < nDist; d++) {
                encodeDistance(distScratch[d], np, nd, distRing, distOut);
                cost += distOut[2];
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestPost = np;
                bestDirect = nd;
            }
        }
        return new int[] { bestPost, bestDirect };
    }

    private static int[] trimHist(int[] hist, int alphabet) {
        if (hist.length == alphabet) {
            return hist;
        }
        int[] out = new int[alphabet];
        System.arraycopy(hist, 0, out, 0, alphabet);
        return out;
    }

    private static int sumRange(int[] a, int n) {
        int s = 0;
        int lim = n < a.length ? n : a.length;
        for (int i = 0; i < lim; i++) {
            s += a[i];
        }
        return s;
    }

    /**
     * Encodes a backward or dictionary distance with NPOSTFIX=0, NDIRECT=0.
     *
     * @param out {@code out[0]}=code, {@code out[1]}=extra, {@code out[2]}=extraBits
     */
    private static void encodeDistance(int distance, DistanceRing ring, int[] out)
            throws BrotliException {
        encodeDistance(distance, 0, 0, ring, out);
    }

    /**
     * Encodes a backward or dictionary distance.
     *
     * @param out {@code out[0]}=code, {@code out[1]}=extra, {@code out[2]}=extraBits
     */
    private static void encodeDistance(int distance, int npostfix, int ndirect,
            DistanceRing ring, int[] out) throws BrotliException {
        if (distance <= 0) {
            throw new BrotliException("Invalid distance " + distance);
        }
        for (int c = 0; c < 16; c++) {
            if (ring.resolveShort(c) == distance) {
                out[0] = c;
                out[1] = 0;
                out[2] = 0;
                return;
            }
        }
        if (ndirect > 0 && distance <= ndirect) {
            out[0] = 15 + distance;
            out[1] = 0;
            out[2] = 0;
            return;
        }
        int rem = distance - ndirect - 1;
        if (rem < 0) {
            throw new BrotliException("Invalid distance rem " + distance);
        }
        int postfixMask = (1 << npostfix) - 1;
        int lcode = rem & postfixMask;
        int prefixBody = rem >> npostfix;
        int dist = prefixBody + 4;
        int bucket = log2Floor(dist) - 1;
        if (bucket < 1) {
            bucket = 1;
        }
        int bit = (dist >> bucket) & 1;
        int offset = (2 + bit) << bucket;
        int nbits = bucket;
        int hcode = 2 * (nbits - 1) + bit;
        int code = 16 + ndirect + (hcode << npostfix) + lcode;
        int extra = dist - offset;
        out[0] = code;
        out[1] = extra;
        out[2] = nbits;
    }

    private static int log2Floor(int n) {
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
        }
        return s;
    }

    private static void clearHistogram(int[] hist) {
        for (int i = 0; i < hist.length; i++) {
            hist[i] = 0;
        }
    }

    private void ensureCmdWorkspace(int commandCount) {
        if (commandCount <= cmdWorkspaceLen) {
            return;
        }
        int cap = cmdWorkspaceLen == 0 ? 16 : cmdWorkspaceLen;
        while (cap < commandCount) {
            cap *= 2;
        }
        iacCodes = new int[cap];
        insertExtras = new int[cap];
        copyExtras = new int[cap];
        insertExtraBits = new int[cap];
        copyExtraBits = new int[cap];
        distCodes = new int[cap];
        distExtras = new int[cap];
        distExtraBits = new int[cap];
        writeDist = new boolean[cap];
        cmdWorkspaceLen = cap;
    }
}

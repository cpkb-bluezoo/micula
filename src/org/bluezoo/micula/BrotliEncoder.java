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
 * <p>Quality 0 emits uncompressed metablocks. Qualities 1–11 use LZ77 with
 * Huffman coding. Quality 2+ uses the static dictionary; 3+ deepens LZ search;
 * 4+ matches transformed dictionary words; 5+ uses literal context maps;
 * 6 adds a literal block split and distance context trees; 7 tunes
 * NPOSTFIX/NDIRECT; 8 uses up to four literal trees and an insert-and-copy
 * block split; 9 uses larger metablocks and a distance block split; 10 uses
 * Zopfli command selection with bounded block splits and context clustering;
 * 11 adds a second Zopfli pass with refined costs and shorter length samples.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BrotliEncoder {

    private static final int MAX_METABLOCK = 1 << 24;
    private static final int DEFAULT_BLOCK = 1 << 16;
    private static final int QUALITY9_BLOCK = 1 << 18;
    private static final int MAX_LIT_BLOCK_TYPES = 16;
    private static final int MAX_IAC_BLOCK_TYPES = 8;
    private static final int MAX_DIST_BLOCK_TYPES = 8;
    private static final int MAX_LIT_TREES = 16;
    private static final int MAX_DIST_SLOTS = MAX_DIST_BLOCK_TYPES * 2;
    private static final int MAX_CTX_SLOTS = 64 * MAX_LIT_BLOCK_TYPES;
    private static final int MAX_BTYPE_ALPHABET = MAX_LIT_BLOCK_TYPES + 2;

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

    /** Pending uncompressed bytes for qualities 1+. */
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
    private final int[] distHist2 = new int[MAX_DIST_ALPHABET];
    private final int[] distHist3 = new int[MAX_DIST_ALPHABET];
    private final int[] litLens = new int[256];
    private final int[] litLens1 = new int[256];
    private final int[] litLens2 = new int[256];
    private final int[] litLens3 = new int[256];
    private final int[] iacLens = new int[704];
    private final int[] iacLens1 = new int[704];
    private final int[] distLens = new int[MAX_DIST_ALPHABET];
    private final int[] distLens1 = new int[MAX_DIST_ALPHABET];
    private final int[] distLens2 = new int[MAX_DIST_ALPHABET];
    private final int[] distLens3 = new int[MAX_DIST_ALPHABET];
    private final int[] litCodes = new int[256];
    private final int[] litCodes1 = new int[256];
    private final int[] litCodes2 = new int[256];
    private final int[] litCodes3 = new int[256];
    private final int[] iacCodeTbl = new int[704];
    private final int[] iacCodeTbl1 = new int[704];
    private final int[] distCodeTbl = new int[MAX_DIST_ALPHABET];
    private final int[] distCodeTbl1 = new int[MAX_DIST_ALPHABET];
    private final int[] distCodeTbl2 = new int[MAX_DIST_ALPHABET];
    private final int[] distCodeTbl3 = new int[MAX_DIST_ALPHABET];
    private final int[] packOut = new int[5];
    private final int[] distOut = new int[3];
    private final int[] blenPack = new int[3];
    private final int[] blenHist = new int[26];
    private final int[] blenLens = new int[26];
    private final int[] blenCodes = new int[26];
    private final int[] btypeHist = new int[MAX_BTYPE_ALPHABET];
    private final int[] btypeLens = new int[MAX_BTYPE_ALPHABET];
    private final int[] btypeCodes = new int[MAX_BTYPE_ALPHABET];
    private final int[] cmapL = new int[MAX_CTX_SLOTS];
    private final int[] cmapD = new int[4 * MAX_DIST_BLOCK_TYPES];
    private final int[] litTreeOfContext = new int[64];
    private final int[] distScratch = new int[4096];
    private final int[] iBtypeLens = new int[MAX_BTYPE_ALPHABET];
    private final int[] iBtypeCodes = new int[MAX_BTYPE_ALPHABET];
    private final int[] iBlenLens = new int[26];
    private final int[] iBlenCodes = new int[26];
    private final int[] dBtypeLens = new int[MAX_BTYPE_ALPHABET];
    private final int[] dBtypeCodes = new int[MAX_BTYPE_ALPHABET];
    private final int[] dBlenLens = new int[26];
    private final int[] dBlenCodes = new int[26];
    private final int[] distSlotToTree = new int[MAX_DIST_SLOTS];
    private final int[][] litHistsHq = new int[MAX_LIT_TREES][256];
    private final int[][] litLensHq = new int[MAX_LIT_TREES][256];
    private final int[][] litCodesHq = new int[MAX_LIT_TREES][256];
    private final int[][] iacHistsHq = new int[MAX_IAC_BLOCK_TYPES][704];
    private final int[][] iacLensHq = new int[MAX_IAC_BLOCK_TYPES][704];
    private final int[][] iacCodesHq = new int[MAX_IAC_BLOCK_TYPES][704];
    private final int[][] distHistsHq = new int[MAX_DIST_SLOTS][MAX_DIST_ALPHABET];
    private final int[][] distLensHq = new int[MAX_DIST_SLOTS][MAX_DIST_ALPHABET];
    private final int[][] distCodesHq = new int[MAX_DIST_SLOTS][MAX_DIST_ALPHABET];
    private final int[][] ctxHists = new int[MAX_CTX_SLOTS][256];
    private final int[] ctxMapScratch = new int[MAX_CTX_SLOTS];

    private int[] iacCodes;
    private int[] insertExtras;
    private int[] copyExtras;
    private int[] insertExtraBits;
    private int[] copyExtraBits;
    private int[] distCodes;
    private int[] distExtras;
    private int[] distExtraBits;
    private boolean[] writeDist;
    private int[] distCopyLens;
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
     * Sets compression quality. Accepts 0..11.
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
        int flushAt = metablockFlushSize();
        while (data.hasRemaining()) {
            ensurePendingCapacity(pendingLen + 1);
            int room = pending.length - pendingLen;
            int n = data.remaining();
            if (n > room) {
                n = room;
            }
            data.get(pending, pendingLen, n);
            pendingLen += n;
            while (pendingLen >= flushAt) {
                flushCompressedMetablock(flushAt, false);
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
     * Metablock flush threshold for the current quality and window.
     */
    private int metablockFlushSize() {
        if (quality < 9) {
            return DEFAULT_BLOCK;
        }
        int size = QUALITY9_BLOCK;
        int winCap = 1 << windowBits;
        if (size > winCap) {
            size = winCap;
        }
        if (size > MAX_METABLOCK) {
            size = MAX_METABLOCK;
        }
        return size;
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
        if (mode.zopfli) {
            ZopfliParser.encode(pending, length, history, historyLen, windowSize,
                    distRing, quality, box);
        } else {
            Lz77Encoder.encode(pending, length, history, historyLen, windowSize,
                    mode, box);
        }
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
        if (quality >= 10) {
            writeCompressedMetablockHq(data, length, commands, last);
            return;
        }
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

    private void writeCompressedMetablockHq(byte[] data, int length,
            Lz77Encoder.Command[] commands, boolean last) throws BrotliException {
        int totalLiterals = 0;
        for (int ci = 0; ci < commands.length; ci++) {
            totalLiterals += commands[ci].insertLen;
        }

        // --- Pass 1: pack commands / distances (postfix chosen as quality 7)
        int[] chosen = choosePostfixPair(commands, length);
        int npostfix = chosen[0];
        int ndirect = chosen[1];
        int distAlphabet = 16 + ndirect + (48 << npostfix);

        ensureCmdWorkspace(commands.length);
        for (int t = 0; t < MAX_LIT_TREES; t++) {
            clearHistogram(litHistsHq[t]);
        }
        for (int t = 0; t < MAX_DIST_SLOTS; t++) {
            clearHistogram(distHistsHq[t]);
        }
        for (int t = 0; t < MAX_IAC_BLOCK_TYPES; t++) {
            clearHistogram(iacHistsHq[t]);
        }

        int p1 = 0;
        int p2 = 0;
        int pos = 0;
        int bytesEmitted = 0;
        int totalExplicitDist = 0;

        // Flat literal stream for splitting
        byte[] litStream = totalLiterals > 0 ? new byte[totalLiterals] : new byte[0];
        int litAt = 0;

        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];
            for (int j = 0; j < cmd.insertLen; j++) {
                litStream[litAt++] = data[cmd.insertOffset + j];
            }
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
            iacCodes[ci] = packOut[0];
            insertExtras[ci] = packOut[1];
            copyExtras[ci] = packOut[2];
            insertExtraBits[ci] = packOut[3];
            copyExtraBits[ci] = packOut[4];

            writeDist[ci] = false;
            distCopyLens[ci] = copyLen;
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
                    totalExplicitDist++;
                    if (!cmd.dictionary && distCodes[ci] != 0) {
                        distRing.push(cmd.distance);
                    }
                }
                for (int j = 0; j < cmd.copyCovered; j++) {
                    pos++;
                }
                bytesEmitted += cmd.copyCovered;
                if (bytesEmitted > length) {
                    bytesEmitted = length;
                }
            } else {
                // still advance context for inserts only — pos for inserts tracked via litStream
            }
        }

        // Literal / IAC / distance block lengths (quality 11 uses finer splits)
        int litChunk = quality >= 11 ? 512 : 1024;
        int litCap = quality >= 11 ? 16 : 8;
        int iacChunk = quality >= 11 ? 16 : 32;
        int iacCap = quality >= 11 ? 8 : 4;
        int distChunk = quality >= 11 ? 16 : 32;
        int distCap = quality >= 11 ? 8 : 4;
        int litTreeCap = quality >= 11 ? 16 : 8;

        int[] litBlockLens = splitByteStream(litStream, totalLiterals, litChunk,
                litCap, 256);
        int nbltypesL = litBlockLens.length;

        // IAC block lengths (alphabet = insert length code)
        int[] iacBlockLens = splitCommands(commands, iacChunk, iacCap);
        int nbltypesI = iacBlockLens.length;

        // Distance block lengths
        int[] distBlockLens = splitDistances(totalExplicitDist, distChunk, distCap);
        int nbltypesD = distBlockLens.length;

        // Assign IAC histograms by block
        int cmdAt = 0;
        for (int bt = 0; bt < nbltypesI; bt++) {
            int end = cmdAt + iacBlockLens[bt];
            for (int ci = cmdAt; ci < end && ci < commands.length; ci++) {
                iacHistsHq[bt][iacCodes[ci]]++;
            }
            cmdAt = end;
        }
        for (int bt = 0; bt < nbltypesI; bt++) {
            if (sum(iacHistsHq[bt]) == 0) {
                iacHistsHq[bt][0] = 1;
            }
        }

        // Context clustering: 64 contexts x nbltypesL, then cluster to litTreeCap trees
        int nCtxSlots = 64 * nbltypesL;
        for (int i = 0; i < nCtxSlots; i++) {
            clearHistogram(ctxHists[i]);
        }
        p1 = 0;
        p2 = 0;
        pos = 0;
        int litIdx = 0;
        int litBt = 0;
        int litInBlock = 0;
        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];
            for (int j = 0; j < cmd.insertLen; j++) {
                while (litBt < nbltypesL - 1 && litInBlock >= litBlockLens[litBt]) {
                    litBt++;
                    litInBlock = 0;
                }
                int lit = data[cmd.insertOffset + j] & 0xff;
                int cid = Context.literalContextId(Context.UTF8, p1, p2);
                ctxHists[64 * litBt + cid][lit]++;
                p2 = p1;
                p1 = lit;
                litInBlock++;
                pos++;
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

        int ntreesL = BlockSplitter.clusterContexts(ctxHists, nCtxSlots, litTreeCap,
                ctxMapScratch);
        // Copy clustered tree histograms into litHistsHq
        for (int t = 0; t < ntreesL; t++) {
            System.arraycopy(ctxHists[t], 0, litHistsHq[t], 0, 256);
            if (sum(litHistsHq[t]) == 0) {
                litHistsHq[t][0] = 1;
            }
        }
        for (int bt = 0; bt < nbltypesL; bt++) {
            for (int cid = 0; cid < 64; cid++) {
                cmapL[64 * bt + cid] = ctxMapScratch[64 * bt + cid];
            }
        }

        // Distance histograms by block × context
        for (int t = 0; t < MAX_DIST_SLOTS; t++) {
            clearHistogram(distHistsHq[t]);
        }
        int distIdx = 0;
        int distBt = 0;
        int distInBlock = 0;
        for (int ci = 0; ci < commands.length; ci++) {
            if (!writeDist[ci]) {
                continue;
            }
            while (distBt < nbltypesD - 1 && distInBlock >= distBlockLens[distBt]) {
                distBt++;
                distInBlock = 0;
            }
            int dcid = Context.distanceContextId(distCopyLens[ci]);
            int ctx = dcid > 0 ? 1 : 0;
            distHistsHq[distBt * 2 + ctx][distCodes[ci]]++;
            distInBlock++;
            distIdx++;
        }
        int maxDistSlots = nbltypesD * 2;
        int ntreesD = 0;
        for (int s = 0; s < maxDistSlots; s++) {
            if (sumRange(distHistsHq[s], distAlphabet) > 0) {
                distSlotToTree[s] = ntreesD;
                if (ntreesD != s) {
                    System.arraycopy(distHistsHq[s], 0, distHistsHq[ntreesD], 0,
                            distAlphabet);
                    clearHistogram(distHistsHq[s]);
                }
                ntreesD++;
            } else {
                distSlotToTree[s] = -1;
            }
        }
        if (ntreesD == 0) {
            distHistsHq[0][0] = 1;
            ntreesD = 1;
            distSlotToTree[0] = 0;
        }
        for (int bt = 0; bt < nbltypesD; bt++) {
            for (int cid = 0; cid < 4; cid++) {
                int slot = bt * 2 + (cid == 0 ? 0 : 1);
                int t = distSlotToTree[slot];
                cmapD[4 * bt + cid] = (t >= 0) ? t : 0;
            }
        }

        // --- Header ---
        bw.writeBits(last ? 1 : 0, 1);
        if (last) {
            bw.writeBits(0, 1);
        }
        writeMlen(length);
        if (!last) {
            bw.writeBits(0, 1);
        }

        writeBlockTypeGroupHeader(litBlockLens);
        writeBlockTypeGroupHeader(iacBlockLens);
        writeBlockTypeGroupHeader(distBlockLens);

        bw.writeBits(npostfix, 2);
        bw.writeBits(ndirect >> npostfix, 4);

        for (int i = 0; i < nbltypesL; i++) {
            bw.writeBits(Context.UTF8, 2);
        }

        if (ntreesL == 1) {
            bw.writeBits(0, 1);
        } else {
            ContextMapWriter.writeVarLenUint8PlusOne(bw, ntreesL);
            ContextMapWriter.write(bw, cmapL, 64 * nbltypesL, ntreesL);
        }
        if (ntreesD == 1) {
            bw.writeBits(0, 1);
        } else {
            ContextMapWriter.writeVarLenUint8PlusOne(bw, ntreesD);
            ContextMapWriter.write(bw, cmapD, 4 * nbltypesD, ntreesD);
        }

        for (int t = 0; t < ntreesL; t++) {
            HuffmanEncoder.assignLengths(litHistsHq[t], litLensHq[t], 15);
            HuffmanTable.buildEncodeTables(litLensHq[t], litCodesHq[t]);
            HuffmanEncoder.writePrefixCode(bw, litHistsHq[t]);
        }
        for (int t = 0; t < nbltypesI; t++) {
            HuffmanEncoder.assignLengths(iacHistsHq[t], iacLensHq[t], 15);
            HuffmanTable.buildEncodeTables(iacLensHq[t], iacCodesHq[t]);
            HuffmanEncoder.writePrefixCode(bw, iacHistsHq[t]);
        }
        for (int t = 0; t < ntreesD; t++) {
            int[] histWrite = trimHist(distHistsHq[t], distAlphabet);
            int[] lensWrite = new int[distAlphabet];
            int[] codesWrite = new int[distAlphabet];
            HuffmanEncoder.assignLengths(histWrite, lensWrite, 15);
            HuffmanTable.buildEncodeTables(lensWrite, codesWrite);
            HuffmanEncoder.writePrefixCode(bw, histWrite);
            System.arraycopy(lensWrite, 0, distLensHq[t], 0, distAlphabet);
            System.arraycopy(codesWrite, 0, distCodesHq[t], 0, distAlphabet);
        }

        // Rebuild switch tables into L / I / D dedicated arrays
        rebuildSwitchTables(litBlockLens, btypeLens, btypeCodes, blenLens, blenCodes);
        rebuildSwitchTables(iacBlockLens, iBtypeLens, iBtypeCodes, iBlenLens, iBlenCodes);
        rebuildSwitchTables(distBlockLens, dBtypeLens, dBtypeCodes, dBlenLens, dBlenCodes);

        // --- Commands ---
        p1 = 0;
        p2 = 0;
        pos = 0;
        litBt = 0;
        litInBlock = 0;
        int iacBt = 0;
        int iacInBlock = 0;
        distBt = 0;
        distInBlock = 0;

        for (int ci = 0; ci < commands.length; ci++) {
            Lz77Encoder.Command cmd = commands[ci];

            if (nbltypesI >= 2 && iacBt < nbltypesI - 1
                    && iacInBlock >= iacBlockLens[iacBt]) {
                int btLen = iBtypeLens[1];
                if (btLen > 0) {
                    bw.writePrefixBits(iBtypeCodes[1], btLen);
                }
                BlockLengthEncoder.write(bw, iacBlockLens[iacBt + 1],
                        iBlenLens, iBlenCodes);
                iacBt++;
                iacInBlock = 0;
            }

            int code = iacCodes[ci];
            int ilen = iacLensHq[iacBt][code];
            if (ilen > 0) {
                bw.writePrefixBits(iacCodesHq[iacBt][code], ilen);
            }
            bw.writeBits(insertExtras[ci], insertExtraBits[ci]);
            bw.writeBits(copyExtras[ci], copyExtraBits[ci]);
            iacInBlock++;

            for (int j = 0; j < cmd.insertLen; j++) {
                if (nbltypesL >= 2 && litBt < nbltypesL - 1
                        && litInBlock >= litBlockLens[litBt]) {
                    int btLen = btypeLens[1];
                    if (btLen > 0) {
                        bw.writePrefixBits(btypeCodes[1], btLen);
                    }
                    BlockLengthEncoder.write(bw, litBlockLens[litBt + 1],
                            blenLens, blenCodes);
                    litBt++;
                    litInBlock = 0;
                }
                int lit = data[cmd.insertOffset + j] & 0xff;
                int cid = Context.literalContextId(Context.UTF8, p1, p2);
                int tree = cmapL[64 * litBt + cid];
                int llen = litLensHq[tree][lit];
                if (llen > 0) {
                    bw.writePrefixBits(litCodesHq[tree][lit], llen);
                }
                p2 = p1;
                p1 = lit;
                litInBlock++;
                pos++;
            }

            if (writeDist[ci]) {
                if (nbltypesD >= 2 && distBt < nbltypesD - 1
                        && distInBlock >= distBlockLens[distBt]) {
                    int btLen = dBtypeLens[1];
                    if (btLen > 0) {
                        bw.writePrefixBits(dBtypeCodes[1], btLen);
                    }
                    BlockLengthEncoder.write(bw, distBlockLens[distBt + 1],
                            dBlenLens, dBlenCodes);
                    distBt++;
                    distInBlock = 0;
                }
                int dc = distCodes[ci];
                int dcid = Context.distanceContextId(distCopyLens[ci]);
                int tree = cmapD[4 * distBt + dcid];
                int dlen = distLensHq[tree][dc];
                if (dlen > 0) {
                    bw.writePrefixBits(distCodesHq[tree][dc], dlen);
                }
                bw.writeBits(distExtras[ci], distExtraBits[ci]);
                distInBlock++;
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

    private int[] splitByteStream(byte[] stream, int len, int chunkSize,
            int maxTypes, int alphabet) {
        if (len <= 0) {
            return new int[] { 1 };
        }
        int nChunks = (len + chunkSize - 1) / chunkSize;
        int[][] hists = new int[nChunks][alphabet];
        int[] lens = new int[nChunks];
        for (int i = 0; i < len; i++) {
            int c = i / chunkSize;
            hists[c][stream[i] & 0xff]++;
            lens[c]++;
        }
        return BlockSplitter.mergeChunks(hists, lens, nChunks, alphabet, maxTypes);
    }

    private int[] splitCommands(Lz77Encoder.Command[] commands, int chunkSize,
            int maxTypes) {
        if (commands.length <= 0) {
            return new int[] { 1 };
        }
        if (commands.length < 32) {
            return new int[] { commands.length };
        }
        int nChunks = (commands.length + chunkSize - 1) / chunkSize;
        int[][] hists = new int[nChunks][24];
        int[] lens = new int[nChunks];
        for (int i = 0; i < commands.length; i++) {
            int c = i / chunkSize;
            int code = InsertCopyLengths.insertLengthCode(commands[i].insertLen);
            if (code < 0) {
                code = 0;
            }
            if (code > 23) {
                code = 23;
            }
            hists[c][code]++;
            lens[c]++;
        }
        return BlockSplitter.mergeChunks(hists, lens, nChunks, 24, maxTypes);
    }

    private int[] splitDistances(int totalExplicit, int chunkSize, int maxTypes) {
        if (totalExplicit <= 0) {
            return new int[] { 1 };
        }
        if (totalExplicit < 32) {
            return new int[] { totalExplicit };
        }
        int nChunks = (totalExplicit + chunkSize - 1) / chunkSize;
        int[][] hists = new int[nChunks][16];
        int[] lens = new int[nChunks];
        int di = 0;
        for (int ci = 0; ci < writeDist.length && di < totalExplicit; ci++) {
            if (!writeDist[ci]) {
                continue;
            }
            int c = di / chunkSize;
            int bucket = distCodes[ci] & 15;
            hists[c][bucket]++;
            lens[c]++;
            di++;
        }
        return BlockSplitter.mergeChunks(hists, lens, nChunks, 16, maxTypes);
    }

    private void rebuildSwitchTables(int[] blockLens, int[] outBtypeLens,
            int[] outBtypeCodes, int[] outBlenLens, int[] outBlenCodes)
            throws BrotliException {
        if (blockLens.length < 2) {
            return;
        }
        int alphabet = blockLens.length + 2;
        int[] btHist = new int[alphabet];
        btHist[1] = 1;
        int[] btLens = new int[alphabet];
        int[] btCodes = new int[alphabet];
        HuffmanEncoder.assignLengths(btHist, btLens, 15);
        HuffmanTable.buildEncodeTables(btLens, btCodes);
        for (int i = 0; i < outBtypeLens.length; i++) {
            outBtypeLens[i] = 0;
            outBtypeCodes[i] = 0;
        }
        System.arraycopy(btLens, 0, outBtypeLens, 0, alphabet);
        System.arraycopy(btCodes, 0, outBtypeCodes, 0, alphabet);

        clearHistogram(blenHist);
        for (int i = 0; i < blockLens.length; i++) {
            BlockLengthEncoder.pack(blockLens[i], blenPack);
            blenHist[blenPack[0]]++;
        }
        HuffmanEncoder.assignLengths(blenHist, outBlenLens, 15);
        HuffmanTable.buildEncodeTables(outBlenLens, outBlenCodes);
    }

    private void writeCompressedMetablockContext(byte[] data, int length,
            Lz77Encoder.Command[] commands, boolean last) throws BrotliException {
        boolean useLitBlockSplit = quality >= 6;
        boolean useDistTrees = quality >= 6;
        boolean useFourLitTrees = quality >= 8;
        boolean useIacSplit = quality >= 8;
        boolean choosePostfix = quality >= 7;
        boolean useDistSplit = quality >= 9;

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

        // --- Pass 1: IAC / distance codes plus literal / IAC histograms
        clearHistogram(litHist);
        clearHistogram(litHist1);
        clearHistogram(litHist2);
        clearHistogram(litHist3);
        clearHistogram(iacHist);
        clearHistogram(iacHist1);
        clearHistogram(distHist);
        clearHistogram(distHist1);
        clearHistogram(distHist2);
        clearHistogram(distHist3);
        ensureCmdWorkspace(commands.length);

        int p1 = 0;
        int p2 = 0;
        int pos = 0;
        int litCount = 0;
        int bytesEmitted = 0;
        int totalExplicitDist = 0;

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
            distCopyLens[ci] = copyLen;
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
                    totalExplicitDist++;
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

        int nbltypesD = 1;
        int firstDistBlock = totalExplicitDist;
        if (useDistSplit && totalExplicitDist >= 32) {
            nbltypesD = 2;
            firstDistBlock = totalExplicitDist / 2;
            if (firstDistBlock < 1) {
                firstDistBlock = 1;
            }
            if (firstDistBlock >= totalExplicitDist) {
                nbltypesD = 1;
                useDistSplit = false;
                firstDistBlock = totalExplicitDist;
            }
        } else {
            useDistSplit = false;
        }

        // Rebuild distance histograms by block type and context bucket
        int[][] distBuckets = new int[][] {
            distHist, distHist1, distHist2, distHist3
        };
        int explicitIdx = 0;
        for (int ci = 0; ci < commands.length; ci++) {
            if (!writeDist[ci]) {
                continue;
            }
            int bt = (nbltypesD >= 2 && explicitIdx >= firstDistBlock) ? 1 : 0;
            int dcid = Context.distanceContextId(distCopyLens[ci]);
            int ctx = (useDistTrees && dcid > 0) ? 1 : 0;
            distBuckets[bt * 2 + ctx][distCodes[ci]]++;
            explicitIdx++;
        }

        int maxDistSlots = nbltypesD * (useDistTrees ? 2 : 1);
        if (maxDistSlots < 1) {
            maxDistSlots = 1;
        }
        int ntreesD = 0;
        for (int s = 0; s < maxDistSlots; s++) {
            if (sumRange(distBuckets[s], distAlphabet) > 0) {
                distSlotToTree[s] = ntreesD;
                if (ntreesD != s) {
                    System.arraycopy(distBuckets[s], 0, distBuckets[ntreesD], 0,
                            distAlphabet);
                    clearHistogram(distBuckets[s]);
                }
                ntreesD++;
            } else {
                distSlotToTree[s] = -1;
            }
        }
        if (ntreesD == 0) {
            distHist[0] = 1;
            ntreesD = 1;
            distSlotToTree[0] = 0;
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
        int mapDSize = 4 * nbltypesD;
        for (int bt = 0; bt < nbltypesD; bt++) {
            for (int cid = 0; cid < 4; cid++) {
                int slot;
                if (useDistTrees) {
                    slot = bt * 2 + (cid == 0 ? 0 : 1);
                } else {
                    slot = bt;
                }
                if (slot >= maxDistSlots) {
                    slot = 0;
                }
                int t = distSlotToTree[slot];
                cmapD[4 * bt + cid] = (t >= 0) ? t : 0;
            }
        }

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

        // NBLTYPESD
        writeBlockTypeGroupHeader(nbltypesD, firstDistBlock,
                totalExplicitDist - firstDistBlock);

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
            ContextMapWriter.write(bw, cmapD, mapDSize, ntreesD);
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

        // Distance trees
        int[][] distLensArr = new int[][] {
            distLens, distLens1, distLens2, distLens3
        };
        int[][] distCodesArr = new int[][] {
            distCodeTbl, distCodeTbl1, distCodeTbl2, distCodeTbl3
        };
        for (int t = 0; t < ntreesD; t++) {
            int[] histWrite = trimHist(distBuckets[t], distAlphabet);
            int[] lensWrite = new int[distAlphabet];
            int[] codesWrite = new int[distAlphabet];
            HuffmanEncoder.assignLengths(histWrite, lensWrite, 15);
            HuffmanTable.buildEncodeTables(lensWrite, codesWrite);
            HuffmanEncoder.writePrefixCode(bw, histWrite);
            System.arraycopy(lensWrite, 0, distLensArr[t], 0, distAlphabet);
            System.arraycopy(codesWrite, 0, distCodesArr[t], 0, distAlphabet);
        }

        // Rebuild switch tables: later categories overwrite shared fields
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
        if (nbltypesD >= 2) {
            clearHistogram(btypeHist);
            btypeHist[1] = 1;
            HuffmanEncoder.assignLengths(btypeHist, dBtypeLens, 15);
            HuffmanTable.buildEncodeTables(dBtypeLens, dBtypeCodes);
            clearHistogram(blenHist);
            BlockLengthEncoder.pack(firstDistBlock, blenPack);
            blenHist[blenPack[0]]++;
            BlockLengthEncoder.pack(totalExplicitDist - firstDistBlock, blenPack);
            blenHist[blenPack[0]]++;
            HuffmanEncoder.assignLengths(blenHist, dBlenLens, 15);
            HuffmanTable.buildEncodeTables(dBlenLens, dBlenCodes);
        }

        // --- Commands ---
        p1 = 0;
        p2 = 0;
        pos = 0;
        litCount = 0;
        boolean litSwitched = false;
        boolean iacSwitched = false;
        boolean distSwitched = false;
        int explicitDistWritten = 0;

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
                if (nbltypesD >= 2 && !distSwitched
                        && explicitDistWritten == firstDistBlock) {
                    int btLen = dBtypeLens[1];
                    if (btLen > 0) {
                        bw.writePrefixBits(dBtypeCodes[1], btLen);
                    }
                    BlockLengthEncoder.write(bw,
                            totalExplicitDist - firstDistBlock,
                            dBlenLens, dBlenCodes);
                    distSwitched = true;
                }
                int bt = (nbltypesD >= 2 && explicitDistWritten >= firstDistBlock)
                        ? 1 : 0;
                int dc = distCodes[ci];
                int dcid = Context.distanceContextId(distCopyLens[ci]);
                int tree = cmapD[4 * bt + dcid];
                int dlen = distLensArr[tree][dc];
                if (dlen > 0) {
                    bw.writePrefixBits(distCodesArr[tree][dc], dlen);
                }
                bw.writeBits(distExtras[ci], distExtraBits[ci]);
                explicitDistWritten++;
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
        writeBlockTypeGroupHeader(new int[] { firstLen, secondLen });
    }

    private void writeBlockTypeGroupHeader(int[] blockLens) throws BrotliException {
        if (blockLens.length <= 1) {
            bw.writeBits(0, 1);
            return;
        }
        int alphabet = blockLens.length + 2;
        ContextMapWriter.writeVarLenUint8PlusOne(bw, blockLens.length);
        int[] btHist = new int[alphabet];
        btHist[1] = 1;
        HuffmanEncoder.writePrefixCode(bw, btHist);
        int[] btLens = new int[alphabet];
        int[] btCodes = new int[alphabet];
        HuffmanEncoder.assignLengths(btHist, btLens, 15);
        HuffmanTable.buildEncodeTables(btLens, btCodes);
        for (int i = 0; i < btypeLens.length; i++) {
            btypeLens[i] = 0;
            btypeCodes[i] = 0;
        }
        System.arraycopy(btLens, 0, btypeLens, 0, alphabet);
        System.arraycopy(btCodes, 0, btypeCodes, 0, alphabet);

        clearHistogram(blenHist);
        for (int i = 0; i < blockLens.length; i++) {
            BlockLengthEncoder.pack(blockLens[i], blenPack);
            blenHist[blenPack[0]]++;
        }
        HuffmanEncoder.writePrefixCode(bw, blenHist);
        HuffmanEncoder.assignLengths(blenHist, blenLens, 15);
        HuffmanTable.buildEncodeTables(blenLens, blenCodes);

        BlockLengthEncoder.write(bw, blockLens[0], blenLens, blenCodes);
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
    static void encodeDistance(int distance, DistanceRing ring, int[] out)
            throws BrotliException {
        encodeDistance(distance, 0, 0, ring, out);
    }

    /**
     * Encodes a backward or dictionary distance.
     *
     * @param out {@code out[0]}=code, {@code out[1]}=extra, {@code out[2]}=extraBits
     */
    static void encodeDistance(int distance, int npostfix, int ndirect,
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
        distCopyLens = new int[cap];
        cmdWorkspaceLen = cap;
    }
}

/*
 * BrotliEncoder.java
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
 * Push-model Brotli encoder.
 *
 * <p>Quality 0 emits uncompressed metablocks. Qualities 1–2 use greedy LZ77
 * with one Huffman tree per alphabet (literals, insert-and-copy, distances).
 * Quality 2 also uses static dictionary matches.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BrotliEncoder {

    private static final int MAX_METABLOCK = 1 << 24;
    private static final int DEFAULT_BLOCK = 1 << 16;

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
     * Sets compression quality. Accepts 0..11 for API stability; only 0, 1,
     * and 2 are implemented.
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
        if (quality > 2) {
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
        if (quality > 2) {
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
        if (quality > 2) {
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
        boolean useDict = quality >= 2;

        Lz77Encoder.Command[][] box = new Lz77Encoder.Command[1][];
        Lz77Encoder.encode(pending, length, history, historyLen, windowSize,
                useDict, box);
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

        int[] litHist = new int[256];
        int[] iacHist = new int[704];
        int[] distHist = new int[64];

        // First pass: decide IaC / distance codes and build histograms
        int[] iacCodes = new int[commands.length];
        int[] insertExtras = new int[commands.length];
        int[] copyExtras = new int[commands.length];
        int[] insertExtraBits = new int[commands.length];
        int[] copyExtraBits = new int[commands.length];
        int[] distCodes = new int[commands.length];
        int[] distExtras = new int[commands.length];
        int[] distExtraBits = new int[commands.length];
        boolean[] writeDist = new boolean[commands.length];

        int[] packOut = new int[5];
        int[] distOut = new int[3];

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
            // Only symbols below 128 imply distance code 0; long inserts and
            // copies have no such symbol and need the distance written out.
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
                bytesEmitted += cmd.copyLen;
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
        if (sum(distHist) == 0) {
            distHist[0] = 1;
        }

        int[] litLens = new int[256];
        int[] iacLens = new int[704];
        int[] distLens = new int[64];
        HuffmanEncoder.assignLengths(litHist, litLens, 15);
        HuffmanEncoder.assignLengths(iacHist, iacLens, 15);
        HuffmanEncoder.assignLengths(distHist, distLens, 15);

        int[] litCodes = new int[256];
        int[] iacCodeTbl = new int[704];
        int[] distCodeTbl = new int[64];
        HuffmanTable.buildEncodeTables(litLens, litCodes);
        HuffmanTable.buildEncodeTables(iacLens, iacCodeTbl);
        HuffmanTable.buildEncodeTables(distLens, distCodeTbl);

        HuffmanEncoder.writePrefixCode(bw, litHist);
        HuffmanEncoder.writePrefixCode(bw, iacHist);
        HuffmanEncoder.writePrefixCode(bw, distHist);

        // Fix NSYM=1: lengths all zero — writePrefixBits with len 0 is fine
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

    /**
     * Encodes a backward or dictionary distance with NPOSTFIX=0, NDIRECT=0.
     *
     * @param out {@code out[0]}=code, {@code out[1]}=extra, {@code out[2]}=extraBits
     */
    private static void encodeDistance(int distance, DistanceRing ring, int[] out)
            throws BrotliException {
        if (distance <= 0) {
            throw new BrotliException("Invalid distance " + distance);
        }
        // Try short codes 0..15 (0 is the last distance)
        for (int c = 0; c < 16; c++) {
            if (ring.resolveShort(c) == distance) {
                out[0] = c;
                out[1] = 0;
                out[2] = 0;
                return;
            }
        }
        // PrefixEncodeCopyDistance(distance + 15, 0, 0)
        int distanceCode = distance + 15;
        if (distanceCode < 16) {
            out[0] = distanceCode;
            out[1] = 0;
            out[2] = 0;
            return;
        }
        int dist = 4 + (distanceCode - 16);
        int bucket = log2Floor(dist) - 1;
        int prefix = (dist >> bucket) & 1;
        int offset = (2 + prefix) << bucket;
        int nbits = bucket;
        int code = 16 + (2 * (nbits - 1) + prefix);
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
}

/*
 * BrotliDecoder.java
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
 * Event-driven Brotli decoder (RFC 7932).
 *
 * <p>Feed compressed bytes with {@link #receive(ByteBuffer)}; reconstructed
 * output and optional LZ77 events are delivered through a
 * {@link BrotliHandler}. Decoding is incremental and resumes mid-symbol
 * across buffer boundaries.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BrotliDecoder {

    private enum DecoderState {
        WINDOW_BITS,
        METABLOCK_BEGIN,
        METABLOCK_ISLASTEMPTY,
        METABLOCK_MNIBBLES,
        METABLOCK_MLEN,
        METABLOCK_METADATA,
        METABLOCK_UNCOMPRESSED_BIT,
        METABLOCK_UNCOMPRESSED_DATA,
        METABLOCK_COMPRESSED_HEADER,
        COMMAND_LOOP,
        FINISH,
        DONE
    }

    private static final int BLOCK_CATEGORY_L = 0;
    private static final int BLOCK_CATEGORY_I = 1;
    private static final int BLOCK_CATEGORY_D = 2;

    private final BitReader br = new BitReader();
    private final DistanceRing distRing = new DistanceRing();
    private final int[] tmpBits = new int[1];
    private final int[] unpackOut = new int[3];
    private final byte[] dictScratch = new byte[Dictionary.MAX_TRANSFORMED_WORD_LENGTH];
    private final LocatorImpl locator = new LocatorImpl();

    private BrotliHandler handler = new BrotliDefaultHandler();
    private BrotliLimits limits = new BrotliLimits();
    private boolean handlerBound;
    private boolean needsCommands;

    private DecoderState state = DecoderState.WINDOW_BITS;
    private int wbits;
    private int windowSize;
    private RingBuffer ring;
    private int metablockIndex;
    private boolean isLast;
    private boolean isLastEmpty;
    private int mnibbles;
    private int mlen;
    private int metaSkipLen;
    private int metaSkipRead;
    private byte[] metaBuf;
    private int uncompressedRead;
    private byte[] uncompressedBuf;
    private long metablockWritten;
    private MetablockKind kind;

    // Compressed metablock state
    private int npostfix;
    private int ndirect;
    private int distanceAlphabetSize;

    private int[] nbltypes = new int[3];
    private int[] btype = new int[3];
    private int[] btypePrev = new int[3];
    private int[] blen = new int[3];
    private HuffmanTable[] htreeBtype = new HuffmanTable[3];
    private HuffmanTable[] htreeBlen = new HuffmanTable[3];

    private int[] contextModes;
    private int ntreesL;
    private int ntreesD;
    private int[] cmapL;
    private int[] cmapD;
    private HuffmanTable[] htreeL;
    private HuffmanTable[] htreeI;
    private HuffmanTable[] htreeD;

    // Header-reading progress
    private int headerCategory;
    private int headerPhase;
    private int treesRead;
    private int contextMapIndex;
    private int contextMapSize;
    private int contextMapRleMax;
    private HuffmanTable contextMapTree;
    private int[] contextMapTarget;
    private int contextMapNtrees;
    private boolean readingLiteralContextMap;

    // Command loop
    private int insertLen;
    private int copyLen;
    private boolean implicitDistance0;
    private int literalsRemaining;
    private int distance;
    private int distanceCode;
    private boolean distanceResolved;
    private int p1;
    private int p2;
    private byte[] insertAccum;
    private int insertAccumLen;
    private ByteBuffer insertView;

    private final class LocatorImpl implements BrotliLocator {
        @Override
        public long getByteOffset() {
            return br.getByteOffset();
        }

        @Override
        public int getBitOffset() {
            return br.getBitOffset();
        }

        @Override
        public int getMetablockIndex() {
            return metablockIndex;
        }
    }

    /**
     * Creates a decoder with default limits and a no-op handler.
     */
    public BrotliDecoder() {
        reset();
    }

    /**
     * Sets the event handler. Must be called before {@link #receive}.
     *
     * @param handler event handler (null restores the default no-op)
     */
    public void setHandler(BrotliHandler handler) {
        this.handler = (handler != null) ? handler : new BrotliDefaultHandler();
        this.handlerBound = false;
        this.needsCommands = this.handler.needsCommands();
    }

    /**
     * Sets resource limits.
     *
     * @param limits limits (null restores defaults)
     */
    public void setLimits(BrotliLimits limits) {
        this.limits = (limits != null) ? limits : new BrotliLimits();
    }

    /**
     * Feeds the next chunk of compressed data.
     *
     * @param data compressed bytes in read mode
     * @throws BrotliException on format or limit errors
     */
    public void receive(ByteBuffer data) throws BrotliException {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (state == DecoderState.DONE) {
            throw new BrotliException("Decoder already finished");
        }
        bindHandler();
        br.setInput(data);
        process();
        if (state != DecoderState.DONE) {
            br.savePending();
        }
    }

    /**
     * Marks end of compressed input and finishes decoding.
     *
     * @throws BrotliException if the stream is incomplete or invalid
     */
    public void close() throws BrotliException {
        if (state == DecoderState.DONE) {
            return;
        }
        bindHandler();
        br.markEof();
        process();
        if (state != DecoderState.DONE) {
            throw new BrotliException("Unexpected end of Brotli stream");
        }
    }

    /**
     * Resets the decoder for a new stream.
     */
    public void reset() {
        br.reset();
        distRing.reset();
        state = DecoderState.WINDOW_BITS;
        wbits = 0;
        windowSize = 0;
        ring = null;
        metablockIndex = 0;
        isLast = false;
        isLastEmpty = false;
        mnibbles = 0;
        mlen = 0;
        metaSkipLen = 0;
        metaSkipRead = 0;
        metaBuf = null;
        uncompressedRead = 0;
        uncompressedBuf = null;
        metablockWritten = 0L;
        kind = MetablockKind.COMPRESSED;
        clearCompressedState();
        p1 = 0;
        p2 = 0;
        handlerBound = false;
    }

    private void bindHandler() {
        if (!handlerBound) {
            handler.setLocator(locator);
            needsCommands = handler.needsCommands();
            handlerBound = true;
        }
    }

    private void clearCompressedState() {
        npostfix = 0;
        ndirect = 0;
        distanceAlphabetSize = 0;
        for (int i = 0; i < 3; i++) {
            nbltypes[i] = 1;
            btype[i] = 0;
            btypePrev[i] = 1;
            blen[i] = 1 << 24;
            htreeBtype[i] = null;
            htreeBlen[i] = null;
        }
        contextModes = null;
        ntreesL = 1;
        ntreesD = 1;
        cmapL = null;
        cmapD = null;
        htreeL = null;
        htreeI = null;
        htreeD = null;
        headerCategory = 0;
        headerPhase = 0;
        treesRead = 0;
        contextMapIndex = 0;
        contextMapSize = 0;
        contextMapRleMax = -1;
        contextMapTree = null;
        contextMapTarget = null;
        contextMapNtrees = 0;
        readingLiteralContextMap = true;
        insertLen = 0;
        copyLen = 0;
        implicitDistance0 = false;
        literalsRemaining = 0;
        distance = 0;
        distanceCode = 0;
        distanceResolved = false;
        insertAccum = null;
        insertAccumLen = 0;
        insertView = null;
    }

    private void process() throws BrotliException {
        while (true) {
            switch (state) {
                case WINDOW_BITS:
                    if (!readWindowBits()) {
                        return;
                    }
                    break;
                case METABLOCK_BEGIN:
                    if (!readMetablockBegin()) {
                        return;
                    }
                    break;
                case METABLOCK_ISLASTEMPTY:
                    if (!readIsLastEmpty()) {
                        return;
                    }
                    break;
                case METABLOCK_MNIBBLES:
                    if (!readMnibbles()) {
                        return;
                    }
                    break;
                case METABLOCK_MLEN:
                    if (!readMlen()) {
                        return;
                    }
                    break;
                case METABLOCK_METADATA:
                    if (!readMetadata()) {
                        return;
                    }
                    break;
                case METABLOCK_UNCOMPRESSED_BIT:
                    if (!readUncompressedBit()) {
                        return;
                    }
                    break;
                case METABLOCK_UNCOMPRESSED_DATA:
                    if (!readUncompressedData()) {
                        return;
                    }
                    break;
                case METABLOCK_COMPRESSED_HEADER:
                    if (!readCompressedHeader()) {
                        return;
                    }
                    break;
                case COMMAND_LOOP:
                    if (!processCommands()) {
                        return;
                    }
                    break;
                case FINISH:
                    finishStream();
                    state = DecoderState.DONE;
                    return;
                case DONE:
                    return;
                default:
                    throw new BrotliException("Invalid decoder state");
            }
        }
    }

    private boolean readWindowBits() throws BrotliException {
        br.mark();
        if (!br.tryReadBits(1, tmpBits)) {
            br.resetToMark();
            return false;
        }
        if (tmpBits[0] == 0) {
            wbits = 16;
        } else {
            if (!br.tryReadBits(3, tmpBits)) {
                br.resetToMark();
                return false;
            }
            int n = tmpBits[0];
            if (n != 0) {
                wbits = 17 + n;
            } else {
                if (!br.tryReadBits(3, tmpBits)) {
                    br.resetToMark();
                    return false;
                }
                n = tmpBits[0];
                if (n == 1) {
                    throw new BrotliException("Invalid WBITS bit pattern");
                }
                if (n == 0) {
                    wbits = 17;
                } else {
                    wbits = 8 + n;
                }
            }
        }
        br.clearMark();
        if (wbits < 10 || wbits > 24) {
            throw new BrotliException("WBITS out of range: " + wbits);
        }
        limits.checkWindowBits(wbits);
        windowSize = (1 << wbits) - 16;
        ring = new RingBuffer(wbits, false);
        handler.windowBits(wbits);
        state = DecoderState.METABLOCK_BEGIN;
        return true;
    }

    private boolean readMetablockBegin() throws BrotliException {
        if (!br.tryReadBits(1, tmpBits)) {
            return false;
        }
        isLast = tmpBits[0] != 0;
        isLastEmpty = false;
        metablockWritten = 0L;
        clearCompressedState();
        if (isLast) {
            state = DecoderState.METABLOCK_ISLASTEMPTY;
        } else {
            state = DecoderState.METABLOCK_MNIBBLES;
        }
        return true;
    }

    private boolean readIsLastEmpty() throws BrotliException {
        if (!br.tryReadBits(1, tmpBits)) {
            return false;
        }
        isLastEmpty = tmpBits[0] != 0;
        if (isLastEmpty) {
            state = DecoderState.FINISH;
            return true;
        }
        state = DecoderState.METABLOCK_MNIBBLES;
        return true;
    }

    private boolean readMnibbles() throws BrotliException {
        if (!br.tryReadBits(2, tmpBits)) {
            return false;
        }
        int code = tmpBits[0];
        if (code == 3) {
            mnibbles = 0;
            kind = MetablockKind.METADATA;
            state = DecoderState.METABLOCK_METADATA;
            headerPhase = 0;
            return true;
        }
        mnibbles = code + 4;
        state = DecoderState.METABLOCK_MLEN;
        return true;
    }

    private boolean readMlen() throws BrotliException {
        int bits = mnibbles * 4;
        if (!br.tryReadBits(bits, tmpBits)) {
            return false;
        }
        mlen = tmpBits[0] + 1;
        if (mnibbles > 4) {
            int nibbleMask = 0xf << ((mnibbles - 1) * 4);
            if ((tmpBits[0] & nibbleMask) == 0) {
                throw new BrotliException("Invalid MLEN: trailing nibble zero");
            }
        }
        limits.checkMetablockLength(mlen);
        if (!isLast) {
            state = DecoderState.METABLOCK_UNCOMPRESSED_BIT;
        } else {
            kind = MetablockKind.COMPRESSED;
            handler.startMetablock(mlen, true, kind);
            headerCategory = 0;
            headerPhase = 0;
            state = DecoderState.METABLOCK_COMPRESSED_HEADER;
        }
        return true;
    }

    private boolean readUncompressedBit() throws BrotliException {
        if (!br.tryReadBits(1, tmpBits)) {
            return false;
        }
        if (tmpBits[0] != 0) {
            kind = MetablockKind.UNCOMPRESSED;
            handler.startMetablock(mlen, isLast, kind);
            try {
                br.jumpToByteBoundary(true);
            } catch (BrotliException e) {
                throw e;
            }
            uncompressedRead = 0;
            if (uncompressedBuf == null || uncompressedBuf.length < Math.min(mlen, 65536)) {
                uncompressedBuf = new byte[Math.min(Math.max(mlen, 1), 65536)];
            }
            state = DecoderState.METABLOCK_UNCOMPRESSED_DATA;
        } else {
            kind = MetablockKind.COMPRESSED;
            handler.startMetablock(mlen, isLast, kind);
            headerCategory = 0;
            headerPhase = 0;
            state = DecoderState.METABLOCK_COMPRESSED_HEADER;
        }
        return true;
    }

    private boolean readMetadata() throws BrotliException {
        // phase 0: reserved + MSKIPBYTES + MSKIPLEN
        if (headerPhase == 0) {
            br.mark();
            if (!br.tryReadBits(1, tmpBits)) {
                br.resetToMark();
                return false;
            }
            if (tmpBits[0] != 0) {
                throw new BrotliException("Metadata reserved bit must be zero");
            }
            if (!br.tryReadBits(2, tmpBits)) {
                br.resetToMark();
                return false;
            }
            int mskipBytes = tmpBits[0];
            if (mskipBytes == 0) {
                metaSkipLen = 0;
            } else {
                if (!br.tryReadBits(mskipBytes * 8, tmpBits)) {
                    br.resetToMark();
                    return false;
                }
                metaSkipLen = tmpBits[0] + 1;
                if (mskipBytes > 1) {
                    int top = 0xff << ((mskipBytes - 1) * 8);
                    if ((tmpBits[0] & top) == 0) {
                        throw new BrotliException("Invalid metadata length");
                    }
                }
            }
            br.clearMark();
            limits.checkMetadataLength(metaSkipLen);
            br.jumpToByteBoundary(true);
            handler.startMetablock(0, isLast, MetablockKind.METADATA);
            metaSkipRead = 0;
            if (metaSkipLen > 0) {
                metaBuf = new byte[Math.min(metaSkipLen, 65536)];
            }
            headerPhase = 1;
        }
        while (metaSkipRead < metaSkipLen) {
            int chunk = metaSkipLen - metaSkipRead;
            if (chunk > metaBuf.length) {
                chunk = metaBuf.length;
            }
            int got = br.tryReadBytesAligned(metaBuf, 0, chunk);
            if (got == 0) {
                return false;
            }
            ByteBuffer view = ByteBuffer.wrap(metaBuf, 0, got);
            handler.metadata(view);
            metaSkipRead += got;
            if (got < chunk) {
                return false;
            }
        }
        handler.endMetablock();
        metablockIndex++;
        if (isLast) {
            state = DecoderState.FINISH;
        } else {
            state = DecoderState.METABLOCK_BEGIN;
        }
        return true;
    }

    private boolean readUncompressedData() throws BrotliException {
        while (uncompressedRead < mlen) {
            int chunk = mlen - uncompressedRead;
            if (chunk > uncompressedBuf.length) {
                chunk = uncompressedBuf.length;
            }
            int got = br.tryReadBytesAligned(uncompressedBuf, 0, chunk);
            if (got == 0) {
                return false;
            }
            for (int i = 0; i < got; i++) {
                byte b = uncompressedBuf[i];
                p2 = p1;
                p1 = b & 0xff;
                ring.writeByte(b);
            }
            ring.emitContent(handler, got);
            uncompressedRead += got;
            metablockWritten += got;
            limits.checkTotalOutput(ring.getTotalWritten());
            if (got < chunk) {
                return false;
            }
        }
        handler.endMetablock();
        metablockIndex++;
        if (isLast) {
            state = DecoderState.FINISH;
        } else {
            state = DecoderState.METABLOCK_BEGIN;
        }
        return true;
    }

    /**
     * Reads compressed metablock header. Returns false on underflow.
     */
    private boolean readCompressedHeader() throws BrotliException {
        // Phases:
        // 0..2: for each category L,I,D: NBLTYPES + optional trees + first count
        // 3: NPOSTFIX, NDIRECT
        // 4: context modes
        // 5: NTREESL + context map L
        // 6: NTREESD + context map D
        // 7: literal trees
        // 8: insert-and-copy trees
        // 9: distance trees
        while (headerCategory < 3) {
            if (!readBlockTypeGroup(headerCategory)) {
                return false;
            }
            headerCategory++;
            headerPhase = 0;
        }

        if (headerPhase == 0) {
            br.mark();
            if (!br.tryReadBits(2, tmpBits)) {
                br.resetToMark();
                return false;
            }
            npostfix = tmpBits[0];
            if (!br.tryReadBits(4, tmpBits)) {
                br.resetToMark();
                return false;
            }
            ndirect = tmpBits[0] << npostfix;
            br.clearMark();
            distanceAlphabetSize = 16 + ndirect + (48 << npostfix);
            headerPhase = 1;
        }

        if (headerPhase == 1) {
            int n = nbltypes[BLOCK_CATEGORY_L];
            if (contextModes == null || contextModes.length != n) {
                contextModes = new int[n];
                treesRead = 0;
            }
            while (treesRead < n) {
                if (!br.tryReadBits(2, tmpBits)) {
                    return false;
                }
                contextModes[treesRead] = tmpBits[0];
                treesRead++;
            }
            headerPhase = 2;
            treesRead = 0;
        }

        if (headerPhase == 2) {
            if (!readVarLenUint8PlusOne(tmpBits)) {
                return false;
            }
            ntreesL = tmpBits[0];
            int mapSize = 64 * nbltypes[BLOCK_CATEGORY_L];
            cmapL = new int[mapSize];
            if (ntreesL >= 2) {
                readingLiteralContextMap = true;
                contextMapTarget = cmapL;
                contextMapSize = mapSize;
                contextMapNtrees = ntreesL;
                contextMapIndex = 0;
                contextMapTree = null;
                contextMapRleMax = -1;
                headerPhase = 3;
            } else {
                // already zeros
                headerPhase = 4;
            }
        }

        if (headerPhase == 3) {
            if (!readContextMap()) {
                return false;
            }
            headerPhase = 4;
        }

        if (headerPhase == 4) {
            if (!readVarLenUint8PlusOne(tmpBits)) {
                return false;
            }
            ntreesD = tmpBits[0];
            int mapSize = 4 * nbltypes[BLOCK_CATEGORY_D];
            cmapD = new int[mapSize];
            if (ntreesD >= 2) {
                readingLiteralContextMap = false;
                contextMapTarget = cmapD;
                contextMapSize = mapSize;
                contextMapNtrees = ntreesD;
                contextMapIndex = 0;
                contextMapTree = null;
                contextMapRleMax = -1;
                headerPhase = 5;
            } else {
                headerPhase = 6;
            }
        }

        if (headerPhase == 5) {
            if (!readContextMap()) {
                return false;
            }
            headerPhase = 6;
        }

        if (headerPhase == 6) {
            if (htreeL == null || htreeL.length != ntreesL) {
                htreeL = new HuffmanTable[ntreesL];
                treesRead = 0;
            }
            while (treesRead < ntreesL) {
                HuffmanTable t = PrefixCode.tryRead(br, 256);
                if (t == null) {
                    return false;
                }
                htreeL[treesRead] = t;
                treesRead++;
            }
            headerPhase = 7;
            treesRead = 0;
        }

        if (headerPhase == 7) {
            int n = nbltypes[BLOCK_CATEGORY_I];
            if (htreeI == null || htreeI.length != n) {
                htreeI = new HuffmanTable[n];
                treesRead = 0;
            }
            while (treesRead < n) {
                HuffmanTable t = PrefixCode.tryRead(br, 704);
                if (t == null) {
                    return false;
                }
                htreeI[treesRead] = t;
                treesRead++;
            }
            headerPhase = 8;
            treesRead = 0;
        }

        if (headerPhase == 8) {
            if (htreeD == null || htreeD.length != ntreesD) {
                htreeD = new HuffmanTable[ntreesD];
                treesRead = 0;
            }
            while (treesRead < ntreesD) {
                HuffmanTable t = PrefixCode.tryRead(br, distanceAlphabetSize);
                if (t == null) {
                    return false;
                }
                htreeD[treesRead] = t;
                treesRead++;
            }
            // Ready for commands
            literalsRemaining = 0;
            distanceResolved = false;
            state = DecoderState.COMMAND_LOOP;
            return true;
        }

        return true;
    }

    private boolean readBlockTypeGroup(int category) throws BrotliException {
        // headerPhase 0: NBLTYPES
        // 1: btype tree
        // 2: blen tree
        // 3: first block count
        if (headerPhase == 0) {
            if (!readVarLenUint8PlusOne(tmpBits)) {
                return false;
            }
            nbltypes[category] = tmpBits[0];
            btype[category] = 0;
            btypePrev[category] = 1;
            if (nbltypes[category] >= 2) {
                headerPhase = 1;
            } else {
                blen[category] = 1 << 24;
                headerPhase = 0;
                return true; // done with this category
            }
        }
        if (headerPhase == 1) {
            HuffmanTable t = PrefixCode.tryRead(br, nbltypes[category] + 2);
            if (t == null) {
                return false;
            }
            htreeBtype[category] = t;
            headerPhase = 2;
        }
        if (headerPhase == 2) {
            HuffmanTable t = PrefixCode.tryRead(br, 26);
            if (t == null) {
                return false;
            }
            htreeBlen[category] = t;
            headerPhase = 3;
        }
        if (headerPhase == 3) {
            int len = readBlockLength(category);
            if (len < 0) {
                return false;
            }
            blen[category] = len;
            headerPhase = 0;
        }
        return true;
    }

    /**
     * Reads NBLTYPES / NTREES style value (DecodeVarLenUint8 + 1).
     * Writes result to {@code out[0]}. Returns false on underflow.
     */
    private boolean readVarLenUint8PlusOne(int[] out) throws BrotliException {
        br.mark();
        if (!br.tryReadBits(1, tmpBits)) {
            br.resetToMark();
            return false;
        }
        if (tmpBits[0] == 0) {
            out[0] = 1;
            br.clearMark();
            return true;
        }
        if (!br.tryReadBits(3, tmpBits)) {
            br.resetToMark();
            return false;
        }
        int n = tmpBits[0];
        if (n == 0) {
            out[0] = 2;
            br.clearMark();
            return true;
        }
        if (!br.tryReadBits(n, tmpBits)) {
            br.resetToMark();
            return false;
        }
        out[0] = 1 + (1 << n) + tmpBits[0];
        br.clearMark();
        return true;
    }

    /**
     * Reads a block length using the category's block-count Huffman table.
     *
     * @return length, or -1 on underflow
     */
    private int readBlockLength(int category) throws BrotliException {
        br.mark();
        int sym = htreeBlen[category].tryDecode(br);
        if (sym == -2) {
            br.resetToMark();
            return -1;
        }
        if (sym < 0 || sym > 25) {
            throw new BrotliException("Invalid block length symbol");
        }
        int nbits = InsertCopyLengths.BLOCK_LEN_EXTRA_BITS[sym];
        if (!br.tryReadBits(nbits, tmpBits)) {
            br.resetToMark();
            return -1;
        }
        br.clearMark();
        return InsertCopyLengths.BLOCK_LEN_BASE[sym] + tmpBits[0];
    }

    private boolean readContextMap() throws BrotliException {
        if (contextMapRleMax < 0) {
            br.mark();
            if (!br.tryReadBits(1, tmpBits)) {
                br.resetToMark();
                return false;
            }
            if (tmpBits[0] == 0) {
                contextMapRleMax = 0;
            } else {
                if (!br.tryReadBits(4, tmpBits)) {
                    br.resetToMark();
                    return false;
                }
                contextMapRleMax = tmpBits[0] + 1;
            }
            br.clearMark();
        }
        if (contextMapTree == null) {
            HuffmanTable t = PrefixCode.tryRead(br, contextMapNtrees + contextMapRleMax);
            if (t == null) {
                return false;
            }
            contextMapTree = t;
            contextMapIndex = 0;
        }

        while (contextMapIndex < contextMapSize) {
            br.mark();
            int sym = contextMapTree.tryDecode(br);
            if (sym == -2) {
                br.resetToMark();
                return false;
            }
            if (sym < 0) {
                throw new BrotliException("Invalid context map symbol");
            }
            if (sym == 0) {
                contextMapTarget[contextMapIndex++] = 0;
                br.clearMark();
            } else if (sym <= contextMapRleMax) {
                int repBits = sym;
                if (!br.tryReadBits(repBits, tmpBits)) {
                    br.resetToMark();
                    return false;
                }
                int repeat = (1 << repBits) + tmpBits[0];
                if (contextMapIndex + repeat > contextMapSize) {
                    throw new BrotliException("Context map RLE overflow");
                }
                for (int r = 0; r < repeat; r++) {
                    contextMapTarget[contextMapIndex++] = 0;
                }
                br.clearMark();
            } else {
                contextMapTarget[contextMapIndex++] = sym - contextMapRleMax;
                br.clearMark();
            }
        }

        if (!br.tryReadBits(1, tmpBits)) {
            return false;
        }
        if (tmpBits[0] != 0) {
            inverseMoveToFront(contextMapTarget, contextMapSize);
        }
        contextMapTree = null;
        contextMapRleMax = -1;
        return true;
    }

    private static void inverseMoveToFront(int[] v, int vLen) {
        int[] mtf = new int[256];
        for (int i = 0; i < 256; i++) {
            mtf[i] = i;
        }
        for (int i = 0; i < vLen; i++) {
            int index = v[i] & 0xff;
            int value = mtf[index];
            v[i] = value;
            for (; index > 0; index--) {
                mtf[index] = mtf[index - 1];
            }
            mtf[0] = value;
        }
    }

    private boolean processCommands() throws BrotliException {
        while (metablockWritten < mlen) {
            // Start of new command if no literals remaining mid-insert
            if (literalsRemaining == 0 && !distanceResolved
                    && insertLen == 0 && copyLen == 0) {
                if (!readNextCommand()) {
                    return false;
                }
            }

            // Insert literals
            while (literalsRemaining > 0) {
                if (!readOneLiteral()) {
                    return false;
                }
            }

            if (metablockWritten >= mlen) {
                // Last command: skip copy
                insertLen = 0;
                copyLen = 0;
                distanceResolved = false;
                break;
            }

            // Resolve and apply copy
            if (!distanceResolved) {
                if (!resolveDistance()) {
                    return false;
                }
            }
            if (!applyCopy()) {
                return false;
            }
            insertLen = 0;
            copyLen = 0;
            distanceResolved = false;
        }

        // Emit any pending insert command event
        flushInsertEvent();

        handler.endMetablock();
        metablockIndex++;
        if (isLast) {
            state = DecoderState.FINISH;
        } else {
            state = DecoderState.METABLOCK_BEGIN;
        }
        return true;
    }

    private boolean readNextCommand() throws BrotliException {
        if (!ensureBlockSwitch(BLOCK_CATEGORY_I)) {
            return false;
        }

        br.mark();
        int sym = htreeI[btype[BLOCK_CATEGORY_I]].tryDecode(br);
        if (sym == -2) {
            br.resetToMark();
            return false;
        }
        if (sym < 0 || sym > 703) {
            throw new BrotliException("Invalid insert-and-copy symbol");
        }
        InsertCopyLengths.unpack(sym, unpackOut);
        int insertCode = unpackOut[0];
        int copyCode = unpackOut[1];
        boolean dist0 = unpackOut[2] != 0;

        int ibits = InsertCopyLengths.INSERT_EXTRA_BITS[insertCode];
        if (!br.tryReadBits(ibits, tmpBits)) {
            br.resetToMark();
            return false;
        }
        int decodedInsert = InsertCopyLengths.INSERT_BASE[insertCode] + tmpBits[0];

        int cbits = InsertCopyLengths.COPY_EXTRA_BITS[copyCode];
        if (!br.tryReadBits(cbits, tmpBits)) {
            br.resetToMark();
            return false;
        }
        int decodedCopy = InsertCopyLengths.COPY_BASE[copyCode] + tmpBits[0];
        br.clearMark();

        insertLen = decodedInsert;
        copyLen = decodedCopy;
        implicitDistance0 = dist0;
        blen[BLOCK_CATEGORY_I]--;
        literalsRemaining = insertLen;
        distanceResolved = false;
        if (needsCommands && insertLen > 0) {
            if (insertAccum == null || insertAccum.length < insertLen) {
                insertAccum = new byte[insertLen];
            }
            insertAccumLen = 0;
        }
        return true;
    }

    private boolean ensureBlockSwitch(int category) throws BrotliException {
        if (nbltypes[category] < 2) {
            return true;
        }
        if (blen[category] > 0) {
            return true;
        }
        br.mark();
        int sym = htreeBtype[category].tryDecode(br);
        if (sym == -2) {
            br.resetToMark();
            return false;
        }
        if (sym < 0) {
            throw new BrotliException("Invalid block type symbol");
        }
        int n = nbltypes[category];
        int newType;
        if (sym == 0) {
            newType = btypePrev[category];
        } else if (sym == 1) {
            newType = btype[category] + 1;
            if (newType >= n) {
                newType = 0;
            }
        } else {
            newType = sym - 2;
            if (newType >= n) {
                throw new BrotliException("Block type out of range");
            }
        }
        btypePrev[category] = btype[category];
        btype[category] = newType;

        int len = readBlockLengthAfterMark(category);
        if (len < 0) {
            br.resetToMark();
            return false;
        }
        blen[category] = len;
        br.clearMark();
        return true;
    }

    /**
     * Like {@link #readBlockLength} but assumes an outer mark is already set
     * (does not mark/reset itself on success path for the Huffman symbol;
     * still needs bits for extra).
     */
    private int readBlockLengthAfterMark(int category) throws BrotliException {
        int sym = htreeBlen[category].tryDecode(br);
        if (sym == -2) {
            return -1;
        }
        if (sym < 0 || sym > 25) {
            throw new BrotliException("Invalid block length symbol");
        }
        int nbits = InsertCopyLengths.BLOCK_LEN_EXTRA_BITS[sym];
        if (!br.tryReadBits(nbits, tmpBits)) {
            return -1;
        }
        return InsertCopyLengths.BLOCK_LEN_BASE[sym] + tmpBits[0];
    }

    private boolean readOneLiteral() throws BrotliException {
        if (!ensureBlockSwitch(BLOCK_CATEGORY_L)) {
            return false;
        }

        int mode = contextModes[btype[BLOCK_CATEGORY_L]];
        int cid = Context.literalContextId(mode, p1, p2);
        int treeIndex = cmapL[64 * btype[BLOCK_CATEGORY_L] + cid];
        if (treeIndex < 0 || treeIndex >= ntreesL) {
            throw new BrotliException("Literal context map index out of range");
        }

        int sym = htreeL[treeIndex].tryDecode(br);
        if (sym == -2) {
            return false;
        }
        if (sym < 0 || sym > 255) {
            throw new BrotliException("Invalid literal symbol");
        }

        blen[BLOCK_CATEGORY_L]--;
        byte b = (byte) sym;
        if (needsCommands && insertAccum != null) {
            insertAccum[insertAccumLen++] = b;
        }
        p2 = p1;
        p1 = sym;
        ring.writeByte(b);
        ring.emitContent(handler, 1);
        metablockWritten++;
        literalsRemaining--;
        limits.checkTotalOutput(ring.getTotalWritten());
        return true;
    }

    private boolean resolveDistance() throws BrotliException {
        if (implicitDistance0) {
            distanceCode = 0;
            distance = distRing.get(0);
            distanceResolved = true;
            return true;
        }

        if (!ensureBlockSwitch(BLOCK_CATEGORY_D)) {
            return false;
        }

        br.mark();
        // Peek: we need to decrement blen only after successful decode
        int cid = Context.distanceContextId(copyLen);
        int treeIndex = cmapD[4 * btype[BLOCK_CATEGORY_D] + cid];
        if (treeIndex < 0 || treeIndex >= ntreesD) {
            throw new BrotliException("Distance context map index out of range");
        }
        int sym = htreeD[treeIndex].tryDecode(br);
        if (sym == -2) {
            br.resetToMark();
            return false;
        }
        if (sym < 0 || sym >= distanceAlphabetSize) {
            throw new BrotliException("Invalid distance symbol");
        }
        distanceCode = sym;

        if (sym < 16) {
            distance = distRing.resolveShort(sym);
            if (distance < 1) {
                throw new BrotliException("Invalid short distance");
            }
            br.clearMark();
        } else if (sym < 16 + ndirect) {
            distance = sym - 15;
            br.clearMark();
        } else {
            int dcode = sym;
            int ndistbits = 1 + ((dcode - ndirect - 16) >> (npostfix + 1));
            if (!br.tryReadBits(ndistbits, tmpBits)) {
                br.resetToMark();
                return false;
            }
            int dextra = tmpBits[0];
            int postfixMask = (1 << npostfix) - 1;
            int hcode = (dcode - ndirect - 16) >> npostfix;
            int lcode = (dcode - ndirect - 16) & postfixMask;
            int offset = ((2 + (hcode & 1)) << ndistbits) - 4;
            distance = ((offset + dextra) << npostfix) + lcode + ndirect + 1;
            br.clearMark();
        }

        blen[BLOCK_CATEGORY_D]--;
        distanceResolved = true;
        return true;
    }

    private boolean applyCopy() throws BrotliException {
        flushInsertEvent();

        long maxAllowed = ring.getTotalWritten();
        if (maxAllowed > windowSize) {
            maxAllowed = windowSize;
        }

        if (distance <= maxAllowed) {
            int remaining = (int) (mlen - metablockWritten);
            if (copyLen > remaining) {
                throw new BrotliException("Copy length exceeds remaining MLEN");
            }
            if (needsCommands) {
                handler.copy(distance, copyLen);
            }
            // RFC §4: do not push distance symbol 0
            if (distanceCode != 0) {
                distRing.push(distance);
            }
            ring.copyBackward(distance, copyLen);
            ring.emitContent(handler, copyLen);
            p1 = ring.recent(1) & 0xff;
            p2 = ring.recent(2) & 0xff;
            metablockWritten += copyLen;
            limits.checkTotalOutput(ring.getTotalWritten());
            return true;
        }

        // Dictionary reference — do not push
        int outLen = Dictionary.lookup(copyLen, distance, (int) maxAllowed, dictScratch);
        if (outLen < 0) {
            throw new BrotliException("Invalid dictionary reference");
        }
        int remaining = (int) (mlen - metablockWritten);
        if (outLen > remaining) {
            throw new BrotliException("Dictionary word exceeds remaining MLEN");
        }
        if (needsCommands) {
            long wordId = (long) distance - (maxAllowed + 1L);
            int nw = Dictionary.nwords(copyLen);
            int wordIndex = (int) (wordId % nw);
            int transformId = (int) (wordId >> Dictionary.ndbits(copyLen));
            handler.dictionary(copyLen, wordIndex, transformId);
        }
        ring.writeBytes(dictScratch, 0, outLen);
        ring.emitContent(handler, outLen);
        p1 = ring.recent(1) & 0xff;
        p2 = ring.recent(2) & 0xff;
        metablockWritten += outLen;
        limits.checkTotalOutput(ring.getTotalWritten());
        return true;
    }

    private void flushInsertEvent() throws BrotliException {
        if (needsCommands && insertAccum != null && insertAccumLen > 0) {
            if (insertView == null) {
                insertView = ByteBuffer.wrap(insertAccum, 0, insertAccumLen);
            } else {
                insertView.clear();
                insertView.limit(insertAccumLen);
            }
            // wrap creates a new buffer each time for correct limit — safer:
            handler.insert(ByteBuffer.wrap(insertAccum, 0, insertAccumLen));
            insertAccumLen = 0;
        }
    }

    private void finishStream() throws BrotliException {
        br.jumpToByteBoundary(true);
        br.verifyTrailingBitsZero();
        handler.endStream();
    }
}

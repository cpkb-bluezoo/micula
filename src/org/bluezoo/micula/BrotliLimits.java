/*
 * BrotliLimits.java
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
 * Configurable resource limits for Brotli decoding.
 *
 * <p>Defaults are conservative for untrusted input. Call
 * {@link #disableAllLimits()} only for trusted streams (e.g. unit tests).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BrotliLimits {

    /** RFC maximum window bits. */
    public static final int RFC_MAX_WINDOW_BITS = 24;

    /** RFC maximum metablock output length (16 MiB). */
    public static final int RFC_MAX_METABLOCK_LENGTH = 1 << 24;

    private int maxWindowBits = RFC_MAX_WINDOW_BITS;
    private long maxTotalOutput = 64L * 1024L * 1024L;
    private int maxMetablockLength = RFC_MAX_METABLOCK_LENGTH;
    private int maxMetadataLength = 1 << 24;
    private boolean limitsEnabled = true;

    /**
     * Creates limits with industry-default caps.
     */
    public BrotliLimits() {
    }

    /**
     * Disables all resource limits. Use only for trusted input.
     *
     * @return this
     */
    public BrotliLimits disableAllLimits() {
        this.limitsEnabled = false;
        return this;
    }

    /**
     * Returns whether limits are enforced.
     *
     * @return true if limits are on
     */
    public boolean isLimitsEnabled() {
        return limitsEnabled;
    }

    /**
     * Sets the maximum allowed WBITS (10–24).
     *
     * @param maxWindowBits maximum window bits
     * @return this
     */
    public BrotliLimits setMaxWindowBits(int maxWindowBits) {
        if (maxWindowBits < 10 || maxWindowBits > RFC_MAX_WINDOW_BITS) {
            throw new IllegalArgumentException("maxWindowBits must be 10..24");
        }
        this.maxWindowBits = maxWindowBits;
        return this;
    }

    /**
     * Returns the maximum allowed WBITS.
     *
     * @return max window bits
     */
    public int getMaxWindowBits() {
        return maxWindowBits;
    }

    /**
     * Sets the maximum total decompressed output in bytes.
     *
     * @param maxTotalOutput maximum total output
     * @return this
     */
    public BrotliLimits setMaxTotalOutput(long maxTotalOutput) {
        if (maxTotalOutput < 0) {
            throw new IllegalArgumentException("maxTotalOutput must be >= 0");
        }
        this.maxTotalOutput = maxTotalOutput;
        return this;
    }

    /**
     * Returns the maximum total decompressed output.
     *
     * @return max total output
     */
    public long getMaxTotalOutput() {
        return maxTotalOutput;
    }

    /**
     * Sets the maximum metablock length (MLEN).
     *
     * @param maxMetablockLength maximum MLEN
     * @return this
     */
    public BrotliLimits setMaxMetablockLength(int maxMetablockLength) {
        if (maxMetablockLength < 0 || maxMetablockLength > RFC_MAX_METABLOCK_LENGTH) {
            throw new IllegalArgumentException(
                "maxMetablockLength must be 0.." + RFC_MAX_METABLOCK_LENGTH);
        }
        this.maxMetablockLength = maxMetablockLength;
        return this;
    }

    /**
     * Returns the maximum metablock length.
     *
     * @return max MLEN
     */
    public int getMaxMetablockLength() {
        return maxMetablockLength;
    }

    /**
     * Sets the maximum metadata metablock length.
     *
     * @param maxMetadataLength maximum metadata length
     * @return this
     */
    public BrotliLimits setMaxMetadataLength(int maxMetadataLength) {
        if (maxMetadataLength < 0) {
            throw new IllegalArgumentException("maxMetadataLength must be >= 0");
        }
        this.maxMetadataLength = maxMetadataLength;
        return this;
    }

    /**
     * Returns the maximum metadata length.
     *
     * @return max metadata length
     */
    public int getMaxMetadataLength() {
        return maxMetadataLength;
    }

    void checkWindowBits(int wbits) throws BrotliException {
        if (!limitsEnabled) {
            return;
        }
        if (wbits > maxWindowBits) {
            throw new BrotliException(
                "WBITS " + wbits + " exceeds limit " + maxWindowBits);
        }
    }

    void checkMetablockLength(int mlen) throws BrotliException {
        if (!limitsEnabled) {
            return;
        }
        if (mlen > maxMetablockLength) {
            throw new BrotliException(
                "MLEN " + mlen + " exceeds limit " + maxMetablockLength);
        }
    }

    void checkMetadataLength(int len) throws BrotliException {
        if (!limitsEnabled) {
            return;
        }
        if (len > maxMetadataLength) {
            throw new BrotliException(
                "Metadata length " + len + " exceeds limit " + maxMetadataLength);
        }
    }

    void checkTotalOutput(long total) throws BrotliException {
        if (!limitsEnabled) {
            return;
        }
        if (total > maxTotalOutput) {
            throw new BrotliException(
                "Total output " + total + " exceeds limit " + maxTotalOutput);
        }
    }
}

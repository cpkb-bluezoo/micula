/*
 * Context.java
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
 * Literal and distance context IDs (RFC 7932 §7).
 *
 * <p>Lookup tables match the RFC / reference decoder layout: for mode M,
 * {@code context = LOOKUP[(M<<9)+p1] | LOOKUP[(M<<9)+256+p2]}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class Context {

    static final int LSB6 = 0;
    static final int MSB6 = 1;
    static final int UTF8 = 2;
    static final int SIGNED = 3;

    /** Combined lookup: 4 modes × 512 entries (p1 then p2). */
    private static final int[] LOOKUP = new int[2048];

    private static final String UTF_MAP =
        "         !!  !                  \"#$##%#$&'##(#)#+++++++++"
        + "+((&*'##,---,---,-----,-----,-----&#'###.///.///./////./////./////&#'# ";
    private static final String UTF_RLE = "A/*  ':  & : $  \u0081 @";

    static {
        unpackLookupTable();
    }

    private Context() {
    }

    static int literalContextId(int mode, int p1, int p2) {
        p1 &= 0xff;
        p2 &= 0xff;
        int base = mode << 9;
        return LOOKUP[base + p1] | LOOKUP[base + 256 + p2];
    }

    static int distanceContextId(int copyLength) {
        if (copyLength == 2) {
            return 0;
        }
        if (copyLength == 3) {
            return 1;
        }
        if (copyLength == 4) {
            return 2;
        }
        return 3;
    }

    private static void unpackLookupTable() {
        for (int i = 0; i < 256; i++) {
            LOOKUP[i] = i & 0x3F;
            LOOKUP[512 + i] = i >> 2;
            LOOKUP[1792 + i] = 2 + (i >> 6);
        }
        for (int i = 0; i < 128; i++) {
            LOOKUP[1024 + i] = 4 * (UTF_MAP.charAt(i) - 32);
        }
        for (int i = 0; i < 64; i++) {
            LOOKUP[1152 + i] = i & 1;
            LOOKUP[1216 + i] = 2 + (i & 1);
        }
        int offset = 1280;
        for (int k = 0; k < 19; k++) {
            int value = k & 3;
            int rep = UTF_RLE.charAt(k) - 32;
            for (int i = 0; i < rep; i++) {
                LOOKUP[offset++] = value;
            }
        }
        for (int i = 0; i < 16; i++) {
            LOOKUP[1792 + i] = 1;
            LOOKUP[2032 + i] = 6;
        }
        LOOKUP[1792] = 0;
        LOOKUP[2047] = 7;
        for (int i = 0; i < 256; i++) {
            LOOKUP[1536 + i] = LOOKUP[1792 + i] << 3;
        }
    }
}

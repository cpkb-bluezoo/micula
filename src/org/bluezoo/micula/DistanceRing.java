/*
 * DistanceRing.java
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
 * Four-slot ring of recent distances (RFC 7932 §4).
 *
 * <p>Initialized to {@code [16, 15, 11, 4]} at stream start. Distance symbol 0
 * and static-dictionary references do not push.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class DistanceRing {

    /**
     * ring[0] is most recent (last distance). Equivalent to Google's
     * {@code [16,15,11,4]} with index pointing at the last slot.
     */
    private final int[] ring = new int[] { 4, 11, 15, 16 };

    void reset() {
        ring[0] = 4;
        ring[1] = 11;
        ring[2] = 15;
        ring[3] = 16;
    }

    int get(int index) {
        return ring[index];
    }

    void push(int distance) {
        ring[3] = ring[2];
        ring[2] = ring[1];
        ring[1] = ring[0];
        ring[0] = distance;
    }

    /**
     * Resolves a short distance code 0..15 to an actual distance, applying
     * ±1..±3 adjustments for codes 4..15 (RFC §4). Does not push.
     *
     * @return distance, or -1 if invalid
     */
    int resolveShort(int dcode) {
        if (dcode < 0 || dcode > 15) {
            return -1;
        }
        int base;
        int delta;
        switch (dcode) {
            case 0:
                return ring[0];
            case 1:
                return ring[1];
            case 2:
                return ring[2];
            case 3:
                return ring[3];
            case 4:
                base = ring[0];
                delta = -1;
                break;
            case 5:
                base = ring[0];
                delta = 1;
                break;
            case 6:
                base = ring[0];
                delta = -2;
                break;
            case 7:
                base = ring[0];
                delta = 2;
                break;
            case 8:
                base = ring[0];
                delta = -3;
                break;
            case 9:
                base = ring[0];
                delta = 3;
                break;
            case 10:
                base = ring[1];
                delta = -1;
                break;
            case 11:
                base = ring[1];
                delta = 1;
                break;
            case 12:
                base = ring[1];
                delta = -2;
                break;
            case 13:
                base = ring[1];
                delta = 2;
                break;
            case 14:
                base = ring[1];
                delta = -3;
                break;
            case 15:
                base = ring[1];
                delta = 3;
                break;
            default:
                return -1;
        }
        int d = base + delta;
        if (d < 1) {
            return -1;
        }
        return d;
    }
}

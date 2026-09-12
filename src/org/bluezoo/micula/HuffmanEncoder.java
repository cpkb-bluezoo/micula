/*
 * HuffmanEncoder.java
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
 * Huffman code-length assignment for the encoder.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class HuffmanEncoder {

    private HuffmanEncoder() {
    }

    /**
     * Assigns bit lengths for symbols with positive histogram counts.
     * Unused symbols get length 0. A single used symbol gets length 0
     * (simple NSYM=1). Lengths satisfy the 15-bit Kraft inequality when
     * more than one symbol is used.
     *
     * @param histogram symbol frequencies (may be zero)
     * @param lengthsOut output lengths (same size as histogram)
     * @param maxBits maximum code length (typically 15)
     */
    static void assignLengths(int[] histogram, int[] lengthsOut, int maxBits)
            throws BrotliException {
        if (maxBits < 1 || maxBits > HuffmanTable.MAX_CODE_LENGTH) {
            throw new BrotliException("Invalid maxBits " + maxBits);
        }
        int n = histogram.length;
        for (int i = 0; i < n; i++) {
            lengthsOut[i] = 0;
        }

        int used = 0;
        int only = -1;
        for (int i = 0; i < n; i++) {
            if (histogram[i] > 0) {
                used++;
                only = i;
            }
        }
        if (used == 0) {
            // Valid trivial tree: symbol 0 with zero bits.
            lengthsOut[0] = 0;
            return;
        }
        if (used == 1) {
            lengthsOut[only] = 0;
            return;
        }

        // Collect leaves: [symbol, frequency]
        int[] symbols = new int[used];
        int[] freqs = new int[used];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (histogram[i] > 0) {
                symbols[k] = i;
                freqs[k] = histogram[i];
                k++;
            }
        }

        // Huffman tree with 2*used-1 nodes. Leaves 0..used-1, internal used..
        int maxNodes = 2 * used - 1;
        int[] nodeFreq = new int[maxNodes];
        int[] left = new int[maxNodes];
        int[] right = new int[maxNodes];
        int[] parent = new int[maxNodes];
        for (int i = 0; i < maxNodes; i++) {
            left[i] = -1;
            right[i] = -1;
            parent[i] = -1;
        }
        for (int i = 0; i < used; i++) {
            nodeFreq[i] = freqs[i];
        }

        int nextNode = used;
        while (nextNode < maxNodes) {
            int a = -1;
            int b = -1;
            for (int i = 0; i < nextNode; i++) {
                if (parent[i] != -1) {
                    continue;
                }
                if (a < 0 || nodeFreq[i] < nodeFreq[a]) {
                    b = a;
                    a = i;
                } else if (b < 0 || nodeFreq[i] < nodeFreq[b]) {
                    b = i;
                }
            }
            if (a < 0 || b < 0) {
                throw new BrotliException("Huffman tree construction failed");
            }
            left[nextNode] = a;
            right[nextNode] = b;
            nodeFreq[nextNode] = nodeFreq[a] + nodeFreq[b];
            parent[a] = nextNode;
            parent[b] = nextNode;
            nextNode++;
        }

        int root = maxNodes - 1;
        int[] depth = new int[maxNodes];
        depth[root] = 0;
        // Iterative DFS via explicit stack
        int[] stack = new int[maxNodes];
        int sp = 0;
        stack[sp++] = root;
        while (sp > 0) {
            int node = stack[--sp];
            int d = depth[node];
            if (left[node] >= 0) {
                depth[left[node]] = d + 1;
                depth[right[node]] = d + 1;
                stack[sp++] = left[node];
                stack[sp++] = right[node];
            }
        }

        for (int i = 0; i < used; i++) {
            int d = depth[i];
            if (d < 1) {
                d = 1;
            }
            if (d > maxBits) {
                d = maxBits;
            }
            lengthsOut[symbols[i]] = d;
        }

        fixKraft(lengthsOut, maxBits);
    }

    /**
     * Adjusts lengths so sum(2^(maxBits-len)) == 2^maxBits for used symbols.
     */
    private static void fixKraft(int[] lengths, int maxBits) throws BrotliException {
        int target = 1 << maxBits;
        int kraft = 0;
        int used = 0;
        for (int i = 0; i < lengths.length; i++) {
            int len = lengths[i];
            if (len > 0) {
                kraft += 1 << (maxBits - len);
                used++;
            }
        }
        if (used < 2) {
            return;
        }

        // Too large: lengthen codes
        while (kraft > target) {
            int best = -1;
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] > 0 && lengths[i] < maxBits) {
                    if (best < 0 || lengths[i] < lengths[best]) {
                        best = i;
                    }
                }
            }
            if (best < 0) {
                throw new BrotliException("Cannot fix Huffman Kraft (too large)");
            }
            kraft -= 1 << (maxBits - lengths[best]);
            lengths[best]++;
            kraft += 1 << (maxBits - lengths[best]);
        }

        // Too small: shorten codes
        while (kraft < target) {
            int best = -1;
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] > 1) {
                    if (kraft + (1 << (maxBits - lengths[i])) <= target) {
                        if (best < 0 || lengths[i] > lengths[best]) {
                            best = i;
                        }
                    }
                }
            }
            if (best < 0) {
                // Add a filler by shortening any length>1 even if overshoots, then lengthen others
                for (int i = 0; i < lengths.length; i++) {
                    if (lengths[i] > 1) {
                        best = i;
                        break;
                    }
                }
            }
            if (best < 0) {
                throw new BrotliException("Cannot fix Huffman Kraft (too small)");
            }
            kraft -= 1 << (maxBits - lengths[best]);
            lengths[best]--;
            kraft += 1 << (maxBits - lengths[best]);
        }

        if (kraft != target) {
            // Final pass: lengthen until exact
            while (kraft > target) {
                int best = -1;
                for (int i = 0; i < lengths.length; i++) {
                    if (lengths[i] > 0 && lengths[i] < maxBits) {
                        best = i;
                        break;
                    }
                }
                if (best < 0) {
                    throw new BrotliException("Huffman Kraft mismatch " + kraft);
                }
                kraft -= 1 << (maxBits - lengths[best]);
                lengths[best]++;
                kraft += 1 << (maxBits - lengths[best]);
            }
        }
        if (kraft != target) {
            throw new BrotliException("Huffman Kraft mismatch " + kraft);
        }
    }

    /**
     * Assigns lengths from histogram and writes the prefix code.
     */
    static void writePrefixCode(BitWriter bw, int[] histogram) throws BrotliException {
        int n = histogram.length;
        int used = 0;
        int only = 0;
        for (int i = 0; i < n; i++) {
            if (histogram[i] > 0) {
                used++;
                only = i;
            }
        }
        if (used <= 1) {
            PrefixCode.writeSimple(bw, new int[] { only }, n);
            return;
        }
        int[] lengths = new int[n];
        assignLengths(histogram, lengths, HuffmanTable.MAX_CODE_LENGTH);
        PrefixCode.writeFromLengths(bw, lengths, only);
    }

    /**
     * Writes a prefix code for the given lengths (simple when possible).
     */
    static void writePrefixCodeFromLengths(BitWriter bw, int[] lengths)
            throws BrotliException {
        PrefixCode.writeFromLengths(bw, lengths);
    }
}

/*
 * BlockSplitter.java
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
 * Bounded block splitting and literal-context clustering for qualities 10–11.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class BlockSplitter {

    private static final double MERGE_PENALTY = 0.5;

    private BlockSplitter() {
    }

    /**
     * Merges adjacent chunk histograms until at most {@code maxTypes} remain.
     * Each chunk histogram is {@code alphabet}-sized. Empty chunks are skipped.
     *
     * @return block lengths in symbols (same units as the chunks), length
     *         between 1 and {@code maxTypes}
     */
    static int[] mergeChunks(int[][] chunkHists, int[] chunkLens, int nChunks,
            int alphabet, int maxTypes) {
        if (nChunks <= 0) {
            return new int[] { 1 };
        }
        // Compact away empty chunks
        int alive = 0;
        for (int i = 0; i < nChunks; i++) {
            if (chunkLens[i] > 0 && sum(chunkHists[i], alphabet) > 0) {
                if (alive != i) {
                    System.arraycopy(chunkHists[i], 0, chunkHists[alive], 0, alphabet);
                    chunkLens[alive] = chunkLens[i];
                }
                alive++;
            }
        }
        if (alive == 0) {
            return new int[] { 1 };
        }
        nChunks = alive;

        while (nChunks > maxTypes) {
            int best = -1;
            double bestDelta = Double.POSITIVE_INFINITY;
            for (int i = 0; i < nChunks - 1; i++) {
                double separate = shannonBits(chunkHists[i], alphabet)
                        + shannonBits(chunkHists[i + 1], alphabet);
                int[] combined = addHist(chunkHists[i], chunkHists[i + 1], alphabet);
                double together = shannonBits(combined, alphabet) + MERGE_PENALTY;
                double delta = together - separate;
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            // Merge best and best+1 into best
            for (int s = 0; s < alphabet; s++) {
                chunkHists[best][s] += chunkHists[best + 1][s];
            }
            chunkLens[best] += chunkLens[best + 1];
            for (int j = best + 1; j < nChunks - 1; j++) {
                System.arraycopy(chunkHists[j + 1], 0, chunkHists[j], 0, alphabet);
                chunkLens[j] = chunkLens[j + 1];
            }
            nChunks--;
        }

        // Also merge while combined is cheaper (even under maxTypes), but keep >= 1
        boolean changed = true;
        while (changed && nChunks > 1) {
            changed = false;
            int best = -1;
            double bestDelta = 0.0;
            for (int i = 0; i < nChunks - 1; i++) {
                double separate = shannonBits(chunkHists[i], alphabet)
                        + shannonBits(chunkHists[i + 1], alphabet);
                int[] combined = addHist(chunkHists[i], chunkHists[i + 1], alphabet);
                double together = shannonBits(combined, alphabet) + MERGE_PENALTY;
                double delta = together - separate;
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = i;
                }
            }
            if (best >= 0) {
                for (int s = 0; s < alphabet; s++) {
                    chunkHists[best][s] += chunkHists[best + 1][s];
                }
                chunkLens[best] += chunkLens[best + 1];
                for (int j = best + 1; j < nChunks - 1; j++) {
                    System.arraycopy(chunkHists[j + 1], 0, chunkHists[j], 0, alphabet);
                    chunkLens[j] = chunkLens[j + 1];
                }
                nChunks--;
                changed = true;
            }
        }

        int[] lengths = new int[nChunks];
        System.arraycopy(chunkLens, 0, lengths, 0, nChunks);
        return lengths;
    }

    /**
     * Clusters context histograms down to at most {@code maxTrees} trees.
     *
     * @param hists {@code nContexts} histograms of size 256
     * @param nContexts number of active context slots
     * @param mapOut length {@code nContexts}; filled with tree indexes 0..ntrees-1
     * @return number of trees
     */
    static int clusterContexts(int[][] hists, int nContexts, int maxTrees,
            int[] mapOut) {
        if (nContexts <= 0) {
            mapOut[0] = 0;
            return 1;
        }
        int[] parent = new int[nContexts];
        for (int i = 0; i < nContexts; i++) {
            parent[i] = i;
        }
        int[][] reps = new int[nContexts][];
        for (int i = 0; i < nContexts; i++) {
            reps[i] = new int[256];
            System.arraycopy(hists[i], 0, reps[i], 0, 256);
        }
        boolean[] alive = new boolean[nContexts];
        for (int i = 0; i < nContexts; i++) {
            alive[i] = sum(hists[i], 256) > 0;
            if (!alive[i]) {
                // empty contexts map to tree 0 later
                parent[i] = -1;
            }
        }
        int nAlive = 0;
        for (int i = 0; i < nContexts; i++) {
            if (alive[i]) {
                nAlive++;
            }
        }
        if (nAlive == 0) {
            for (int i = 0; i < nContexts; i++) {
                mapOut[i] = 0;
            }
            return 1;
        }

        while (nAlive > maxTrees) {
            int bestA = -1;
            int bestB = -1;
            long bestDist = Long.MAX_VALUE;
            for (int a = 0; a < nContexts; a++) {
                if (!alive[a]) {
                    continue;
                }
                for (int b = a + 1; b < nContexts; b++) {
                    if (!alive[b]) {
                        continue;
                    }
                    long d = l1(reps[a], reps[b]);
                    if (d < bestDist) {
                        bestDist = d;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (bestA < 0) {
                break;
            }
            for (int s = 0; s < 256; s++) {
                reps[bestA][s] += reps[bestB][s];
            }
            alive[bestB] = false;
            // redirect all that pointed at bestB to bestA
            for (int i = 0; i < nContexts; i++) {
                if (parent[i] == bestB) {
                    parent[i] = bestA;
                }
            }
            parent[bestB] = bestA;
            nAlive--;
        }

        // Compact alive reps into tree indexes 0..ntrees-1
        int[] slotToTree = new int[nContexts];
        int ntrees = 0;
        for (int i = 0; i < nContexts; i++) {
            slotToTree[i] = -1;
        }
        for (int i = 0; i < nContexts; i++) {
            if (!alive[i]) {
                continue;
            }
            slotToTree[i] = ntrees;
            if (ntrees != i) {
                System.arraycopy(reps[i], 0, hists[ntrees], 0, 256);
            } else {
                System.arraycopy(reps[i], 0, hists[i], 0, 256);
            }
            ntrees++;
        }
        for (int i = 0; i < nContexts; i++) {
            int root = i;
            while (root >= 0 && parent[root] >= 0 && parent[root] != root) {
                root = parent[root];
            }
            if (root < 0 || slotToTree[root] < 0) {
                mapOut[i] = 0;
            } else {
                mapOut[i] = slotToTree[root];
            }
        }
        if (ntrees == 0) {
            for (int i = 0; i < nContexts; i++) {
                mapOut[i] = 0;
            }
            hists[0][0] = 1;
            return 1;
        }
        return ntrees;
    }

    private static long l1(int[] a, int[] b) {
        long s = 0;
        for (int i = 0; i < 256; i++) {
            int d = a[i] - b[i];
            if (d < 0) {
                d = -d;
            }
            s += d;
        }
        return s;
    }

    private static int[] addHist(int[] a, int[] b, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = a[i] + b[i];
        }
        return out;
    }

    private static double shannonBits(int[] hist, int n) {
        int total = sum(hist, n);
        if (total <= 0) {
            return 0.0;
        }
        double bits = 0.0;
        for (int i = 0; i < n; i++) {
            int c = hist[i];
            if (c > 0) {
                double p = (double) c / (double) total;
                bits += -c * (Math.log(p) / Math.log(2.0));
            }
        }
        return bits;
    }

    private static int sum(int[] a, int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += a[i];
        }
        return s;
    }
}

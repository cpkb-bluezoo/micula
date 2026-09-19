/*
 * PrefixCodeTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * Round-trip tests for the prefix code writer against the reader.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class PrefixCodeTest {

    /**
     * Complete codes with lengths 1,2,...,k,k use k+1 distinct code lengths
     * (counting the zero length of unused symbols), so the code-length code
     * needs between 4 and 16 symbols.
     */
    @Test
    public void testComplexCodeWithManyDistinctLengths() throws Exception {
        for (int k = 2; k <= 15; k++) {
            int[] lengths = new int[256];
            for (int i = 0; i < k; i++) {
                lengths[i] = i + 1;
            }
            lengths[k] = k;
            assertRoundTrip("k=" + k, lengths);
        }
    }

    @Test
    public void testSimpleCodeFourSymbolsEveryPosition() throws Exception {
        // Lengths 1,2,3,3 with the length-1 symbol at each sorted position.
        int[][] shapes = {
            { 1, 2, 3, 3 }, { 2, 1, 3, 3 }, { 3, 1, 2, 3 }, { 3, 3, 1, 2 },
            { 2, 3, 3, 1 }, { 3, 2, 3, 1 }, { 2, 2, 2, 2 }
        };
        for (int s = 0; s < shapes.length; s++) {
            int[] lengths = new int[256];
            for (int i = 0; i < 4; i++) {
                lengths[10 + i * 7] = shapes[s][i];
            }
            assertRoundTrip("shape " + s, lengths);
        }
    }

    private static void assertRoundTrip(String label, int[] lengths)
            throws Exception {
        int[] codes = new int[lengths.length];
        HuffmanTable.buildEncodeTables(lengths, codes);

        CollectingSink sink = new CollectingSink();
        BitWriter bw = new BitWriter(sink, false);
        PrefixCode.writeFromLengths(bw, lengths);
        for (int sym = 0; sym < lengths.length; sym++) {
            if (lengths[sym] > 0) {
                bw.writePrefixBits(codes[sym], lengths[sym]);
            }
        }
        bw.finish();

        BitReader br = new BitReader();
        br.setInput(ByteBuffer.wrap(sink.toByteArray()));
        br.markEof();
        HuffmanTable table = PrefixCode.tryRead(br, lengths.length);
        assertNotNull(label, table);
        for (int sym = 0; sym < lengths.length; sym++) {
            if (lengths[sym] > 0) {
                assertEquals(label + " symbol " + sym, sym, table.tryDecode(br));
            }
        }
    }
}

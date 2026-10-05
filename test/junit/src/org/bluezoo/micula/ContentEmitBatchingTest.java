/*
 * ContentEmitBatchingTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 */

package org.bluezoo.micula;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies literal {@link BrotliHandler#content} batching.
 */
public class ContentEmitBatchingTest {

    @Test
    public void testBatchedContentMatchesFullDecode() throws Exception {
        byte[] original = ("The quick brown fox jumps over the lazy dog. "
            + "Pack my box with five dozen liquor jugs.").getBytes(StandardCharsets.UTF_8);
        byte[] compressed = BrotliDecoderTest.encodeQuality0(original);

        CollectingHandler batched = new CollectingHandler();
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(batched);
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        decoder.setContentEmitThreshold(64);
        decoder.receive(ByteBuffer.wrap(compressed));
        decoder.close();

        assertArrayEquals(original, batched.toByteArray());
    }

    @Test
    public void testBatchingReducesContentCallbacks() throws Exception {
        byte[] original = new byte[500];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) ('a' + (i % 26));
        }
        byte[] compressed = BrotliDecoderTest.encodeQuality0(original);

        final int[] contentCalls = {0};
        final byte[] sink = new byte[original.length];
        final int[] pos = {0};
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(new BrotliDefaultHandler() {
            @Override
            public void content(ByteBuffer data, boolean end) {
                contentCalls[0]++;
                int n = data.remaining();
                data.get(sink, pos[0], n);
                pos[0] += n;
            }
        });
        decoder.setLimits(new BrotliLimits().disableAllLimits());
        decoder.setContentEmitThreshold(4096);
        decoder.receive(ByteBuffer.wrap(compressed));
        decoder.close();

        byte[] decoded = new byte[pos[0]];
        System.arraycopy(sink, 0, decoded, 0, pos[0]);
        assertArrayEquals(original, decoded);
        assertTrue("expected fewer content callbacks than bytes",
            contentCalls[0] < original.length);
    }
}

/*
 * MiculaDecodeChunkedBench.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 */

package org.bluezoo.micula.bench;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.bluezoo.micula.BrotliDecoder;
import org.bluezoo.micula.BrotliLimits;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Decode throughput when input is fed in small chunks (incremental API).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class MiculaDecodeChunkedBench {

    @State(Scope.Benchmark)
    public static class ChunkedState {

        @Param({"FOX", "LITERAL_HEAVY"})
        public String corpus;

        @Param({"1", "64", "8192"})
        public int chunkSize;

        public byte[] compressed;
        public BrotliDecoder decoder;
        public NoopContentHandler handler;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            compressed = BenchSupport.compressedForCorpus(corpus);
        }

        @Setup(Level.Iteration)
        public void setupIteration() {
            handler = new NoopContentHandler();
            decoder = new BrotliDecoder();
            decoder.setHandler(handler);
            decoder.setLimits(new BrotliLimits().disableAllLimits());
        }
    }

    @Benchmark
    public void decodeChunked(ChunkedState state) throws Exception {
        state.decoder.reset();
        int offset = 0;
        while (offset < state.compressed.length) {
            int n = state.chunkSize;
            if (n > state.compressed.length - offset) {
                n = state.compressed.length - offset;
            }
            state.decoder.receive(ByteBuffer.wrap(state.compressed, offset, n));
            offset += n;
        }
        state.decoder.close();
    }
}

/*
 * MiculaDecodeBench.java
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
 * Decode throughput with a reused {@link BrotliDecoder}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class MiculaDecodeBench {

    @State(Scope.Benchmark)
    public static class DecodeState {

        @Param({"FOX", "REPETITIVE"})
        public String corpus;

        public byte[] compressed;
        public BrotliDecoder decoder;
        public NoopContentHandler handler;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            if ("FOX".equals(corpus)) {
                compressed = BenchSupport.readBytes(BenchSupport.FOX_BR);
            } else {
                byte[] plain = BenchSupport.repetitivePlain(2 * 1024 * 1024);
                compressed = BenchSupport.compress(plain, 1);
            }
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
    public void decode(DecodeState state) throws Exception {
        state.decoder.reset();
        state.decoder.receive(ByteBuffer.wrap(state.compressed));
        state.decoder.close();
    }
}

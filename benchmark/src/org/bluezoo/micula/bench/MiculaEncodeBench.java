/*
 * MiculaEncodeBench.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 */

package org.bluezoo.micula.bench;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.bluezoo.micula.BrotliEncoder;
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
 * Encode throughput with a reused {@link BrotliEncoder}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class MiculaEncodeBench {

    @State(Scope.Benchmark)
    public static class EncodeState {

        @Param({"1", "2"})
        public int quality;

        public byte[] plain;
        public BrotliEncoder encoder;
        public BenchSupport.DiscardChannel channel;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            plain = BenchSupport.readBytes(BenchSupport.FOX_TXT);
        }

        @Setup(Level.Iteration)
        public void setupIteration() {
            channel = new BenchSupport.DiscardChannel();
            encoder = new BrotliEncoder(new org.bluezoo.micula.ChannelBrotliSink(channel));
            encoder.setQuality(quality);
            encoder.setWindowBits(22);
        }
    }

    @Benchmark
    public void encode(EncodeState state) throws Exception {
        state.encoder.reset();
        state.encoder.receive(ByteBuffer.wrap(state.plain));
        state.encoder.close();
    }
}

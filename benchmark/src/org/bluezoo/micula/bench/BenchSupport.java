/*
 * BenchSupport.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 */

package org.bluezoo.micula.bench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.bluezoo.micula.BrotliEncoder;
import org.bluezoo.micula.BrotliException;
import org.bluezoo.micula.ChannelBrotliSink;

/**
 * Shared corpus loading and helpers for JMH benchmarks.
 */
final class BenchSupport {

    static final Path FOX_BR = corpusPath("cli_4_q11.br");
    static final Path FOX_TXT = corpusPath("cli_4_q11.txt");

    /** ~1 MiB plaintext built from the fox corpus (literal-heavy when decoded). */
    static final int LITERAL_HEAVY_PLAIN_SIZE = 1 << 20;

    private static byte[] literalHeavyQ1Compressed;

    private BenchSupport() {
    }

    static Path corpusPath(String name) {
        return Paths.get("test", "junit", "src", "org", "bluezoo", "micula", name);
    }

    static byte[] readBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    static byte[] compress(byte[] plain, int quality) throws BrotliException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        BrotliEncoder enc = new BrotliEncoder(new ChannelBrotliSink(
                java.nio.channels.Channels.newChannel(bos)));
        enc.setQuality(quality);
        enc.setWindowBits(22);
        if (plain.length > 0) {
            enc.receive(ByteBuffer.wrap(plain));
        }
        enc.close();
        return bos.toByteArray();
    }

    static byte[] repetitivePlain(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31);
        }
        return data;
    }

    /**
     * Plaintext with repeating fox sentence (mostly unique literals per byte).
     */
    static byte[] literalHeavyPlain(int size) throws IOException {
        byte[] seed = readBytes(FOX_TXT);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = seed[i % seed.length];
        }
        return out;
    }

    /**
     * Micula q1-compressed {@link #literalHeavyPlain(int)} at
     * {@link #LITERAL_HEAVY_PLAIN_SIZE} (cached).
     */
    static byte[] literalHeavyQ1Compressed() throws Exception {
        if (literalHeavyQ1Compressed == null) {
            literalHeavyQ1Compressed = compress(
                    literalHeavyPlain(LITERAL_HEAVY_PLAIN_SIZE), 1);
        }
        return literalHeavyQ1Compressed;
    }

    static byte[] compressedForCorpus(String name) throws Exception {
        if ("FOX".equals(name)) {
            return readBytes(FOX_BR);
        }
        if ("LITERAL_HEAVY".equals(name)) {
            return literalHeavyQ1Compressed();
        }
        throw new IllegalArgumentException("Unknown corpus: " + name);
    }

    /**
     * Writable channel that discards output (encode benchmarks).
     */
    static final class DiscardChannel implements WritableByteChannel {

        DiscardChannel() {
        }

        @Override
        public int write(ByteBuffer src) {
            int n = src.remaining();
            src.position(src.limit());
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}

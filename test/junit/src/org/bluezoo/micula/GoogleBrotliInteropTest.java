/*
 * GoogleBrotliInteropTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 */

package org.bluezoo.micula;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Encodes with micula and decompresses with the reference Google {@code brotli}
 * CLI, so each encoder quality is checked against an independent decoder.
 *
 * <p>Skipped when the CLI is not on {@code PATH} (override with system property
 * {@code brotli.cli} or environment variable {@code BROTLI}). CI installs the
 * package so these tests always run there.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GoogleBrotliInteropTest {

    private static String brotliCli;

    @BeforeClass
    public static void requireBrotliCli() {
        brotliCli = resolveBrotliCli();
        Assume.assumeTrue(
            "brotli CLI required (install brotli, or set brotli.cli / BROTLI)",
            brotliCli != null);
    }

    @Test
    public void testMiculaEncodeGoogleDecodeQualities0Through9() throws Exception {
        List<byte[]> samples = samples();
        for (int i = 0; i < samples.size(); i++) {
            byte[] original = samples.get(i);
            for (int quality = 0; quality <= 9; quality++) {
                byte[] compressed = BrotliEncoderTest.encode(original, quality);
                byte[] decoded = decodeWithGoogleBrotli(compressed);
                assertArrayEquals(
                    "sample " + i + " quality " + quality,
                    original,
                    decoded);
            }
        }
    }

    @Test
    public void testCliReportsVersion() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(brotliCli, "--version");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] out = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        assertEquals("brotli --version exit", 0, code);
        String version = new String(out, StandardCharsets.UTF_8).trim();
        assertTrue("unexpected version output: " + version,
            version.toLowerCase().contains("brotli"));
    }

    private static List<byte[]> samples() {
        List<byte[]> list = new ArrayList<byte[]>();
        list.add(new byte[0]);
        list.add("Hello, Micula!".getBytes(StandardCharsets.UTF_8));

        StringBuilder repeats = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            repeats.append("abcabcabcxyz");
        }
        list.add(repeats.toString().getBytes(StandardCharsets.UTF_8));

        list.add(("time and time again with that from with that time")
            .getBytes(StandardCharsets.UTF_8));

        list.add(("Time and That with From the World and Time again")
            .getBytes(StandardCharsets.US_ASCII));

        StringBuilder prose = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            prose.append("The quick brown fox jumps over the lazy dog. ");
            prose.append("Pack my box with five dozen liquor jugs. ");
        }
        list.add(prose.toString().getBytes(StandardCharsets.UTF_8));

        byte[] run = new byte[78];
        java.util.Arrays.fill(run, (byte) 0xcd);
        run[0] = (byte) 0xce;
        run[6] = (byte) 0xc0;
        list.add(run);

        String hex = "0b060355040a1304546573743110300e060355043234365a"
            + "170d3237303931393037343234365a170d3237303931393037343234365a";
        byte[] cert = new byte[hex.length() / 2];
        for (int i = 0; i < cert.length; i++) {
            cert[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        list.add(cert);

        return list;
    }

    private static byte[] decodeWithGoogleBrotli(byte[] compressed) throws Exception {
        Path in = Files.createTempFile("micula-google-", ".br");
        Path out = Files.createTempFile("micula-google-", ".out");
        try {
            Files.write(in, compressed);
            ProcessBuilder pb = new ProcessBuilder(
                brotliCli, "-d", "-f", "-o", out.toString(), in.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] processOut = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            if (code != 0) {
                String msg = new String(processOut, StandardCharsets.UTF_8).trim();
                throw new AssertionError(
                    "brotli -d failed (exit " + code + "): " + msg);
            }
            return Files.readAllBytes(out);
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
        }
    }

    /**
     * @return absolute path or command name, or {@code null} if not found
     */
    static String resolveBrotliCli() {
        String prop = System.getProperty("brotli.cli");
        if (prop != null && prop.length() > 0) {
            return prop;
        }
        String env = System.getenv("BROTLI");
        if (env != null && env.length() > 0) {
            return env;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("brotli", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            int code = process.waitFor();
            if (code == 0) {
                return "brotli";
            }
        } catch (Exception ignored) {
            // not on PATH
        }
        return null;
    }
}

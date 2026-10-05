/*
 * NoopContentHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 */

package org.bluezoo.micula.bench;

import java.nio.ByteBuffer;

import org.bluezoo.micula.BrotliDefaultHandler;

/**
 * Handler that ignores reconstructed output.
 */
final class NoopContentHandler extends BrotliDefaultHandler {

    NoopContentHandler() {
    }

    @Override
    public void content(ByteBuffer data, boolean end) {
    }
}

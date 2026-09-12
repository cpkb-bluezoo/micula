/*
 * package-info.java
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

/**
 * Event-driven Brotli codec for Java (RFC 7932).
 *
 * <p>
 * Micula provides a pure-Java encoder and decoder for the Brotli compressed
 * data format. The name <em>micula</em> is Latin for “small crumb”;
 * <em>brotli</em> is German for a small piece of bread.
 * </p>
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>NIO-first</b> — {@link java.nio.ByteBuffer} only; no
 *       {@code InputStream}/{@code OutputStream}</li>
 *   <li><b>Push model</b> — feed bytes via {@code receive(ByteBuffer)};
 *       receive events via a handler/sink</li>
 *   <li><b>Incremental</b> — chunk-invariant; resumes mid-symbol across
 *       buffer boundaries</li>
 * </ul>
 *
 * <h2>Decoding</h2>
 * <pre>{@code
 * BrotliDecoder decoder = new BrotliDecoder();
 * decoder.setHandler(handler);
 * while (channel.read(buf) > 0) {
 *     buf.flip();
 *     decoder.receive(buf);
 *     buf.compact();
 * }
 * decoder.close();
 * }</pre>
 *
 * <h2>Encoding</h2>
 * <pre>{@code
 * BrotliEncoder encoder = new BrotliEncoder(sink);
 * encoder.setQuality(1);
 * encoder.receive(uncompressed);
 * encoder.close();
 * }</pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7932">RFC 7932</a>
 */
package org.bluezoo.micula;

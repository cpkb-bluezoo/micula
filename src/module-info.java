/*
 * module-info.java
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
 * Event-driven Brotli encoder and decoder (RFC 7932).
 *
 * <p>Provides a streaming, NIO-first Brotli codec. The decoder uses a push
 * model where compressed bytes are fed via
 * {@link org.bluezoo.micula.BrotliDecoder#receive(java.nio.ByteBuffer)} and
 * events are delivered via {@link org.bluezoo.micula.BrotliHandler}.
 *
 * @see org.bluezoo.micula.BrotliDecoder
 * @see org.bluezoo.micula.BrotliEncoder
 */
module org.bluezoo.micula {
    exports org.bluezoo.micula;
}

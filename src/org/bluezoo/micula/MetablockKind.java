/*
 * MetablockKind.java
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

/**
 * Kind of metablock in a Brotli stream (RFC 7932 §9.2).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum MetablockKind {

    /** Compressed metablock with Huffman-coded commands. */
    COMPRESSED,

    /** Uncompressed metablock: raw literal bytes on a byte boundary. */
    UNCOMPRESSED,

    /** Metadata metablock: bytes not added to the window or output. */
    METADATA
}

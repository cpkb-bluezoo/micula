/*
 * BrotliDefaultHandler.java
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

import java.nio.ByteBuffer;

/**
 * Convenience base class with empty {@link BrotliHandler} implementations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class BrotliDefaultHandler implements BrotliHandler {

    /**
     * Constructs a new default handler.
     */
    public BrotliDefaultHandler() {
    }

    @Override
    public void setLocator(BrotliLocator locator) {
    }

    @Override
    public void windowBits(int wbits) throws BrotliException {
    }

    @Override
    public void startMetablock(int length, boolean last, MetablockKind kind)
            throws BrotliException {
    }

    @Override
    public void content(ByteBuffer data, boolean end) throws BrotliException {
    }

    @Override
    public void metadata(ByteBuffer data) throws BrotliException {
    }

    @Override
    public void insert(ByteBuffer literals) throws BrotliException {
    }

    @Override
    public void copy(int distance, int length) throws BrotliException {
    }

    @Override
    public void dictionary(int copyLength, int wordIndex, int transformId)
            throws BrotliException {
    }

    @Override
    public void endMetablock() throws BrotliException {
    }

    @Override
    public void endStream() throws BrotliException {
    }
}

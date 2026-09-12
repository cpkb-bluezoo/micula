/*
 * ChannelBrotliSink.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * {@link BrotliSink} that writes compressed bytes to a
 * {@link WritableByteChannel}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ChannelBrotliSink implements BrotliSink {

    private final WritableByteChannel channel;

    /**
     * Creates a sink that writes to the given channel.
     *
     * @param channel destination channel
     */
    public ChannelBrotliSink(WritableByteChannel channel) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        this.channel = channel;
    }

    @Override
    public void compressed(ByteBuffer data) throws BrotliException {
        try {
            while (data.hasRemaining()) {
                int n = channel.write(data);
                if (n < 0) {
                    throw new BrotliException("Channel closed while writing");
                }
            }
        } catch (IOException e) {
            throw new BrotliException("Write failed", e);
        }
    }
}

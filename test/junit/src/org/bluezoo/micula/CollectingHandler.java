/*
 * CollectingHandler.java
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
import java.util.ArrayList;
import java.util.List;

/**
 * Test handler that accumulates reconstructed content.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class CollectingHandler extends BrotliDefaultHandler {

    private final List<byte[]> chunks = new ArrayList<byte[]>();
    private int windowBits = -1;
    private boolean ended;
    private final List<String> commands = new ArrayList<String>();
    private boolean needCommands;

    void setNeedCommands(boolean needCommands) {
        this.needCommands = needCommands;
    }

    @Override
    public boolean needsCommands() {
        return needCommands;
    }

    @Override
    public void windowBits(int wbits) {
        this.windowBits = wbits;
    }

    @Override
    public void content(ByteBuffer data, boolean end) {
        byte[] copy = new byte[data.remaining()];
        data.get(copy);
        chunks.add(copy);
    }

    @Override
    public void insert(ByteBuffer literals) {
        commands.add("insert:" + literals.remaining());
    }

    @Override
    public void copy(int distance, int length) {
        commands.add("copy:" + distance + ":" + length);
    }

    @Override
    public void dictionary(int copyLength, int wordIndex, int transformId) {
        commands.add("dict:" + copyLength + ":" + wordIndex + ":" + transformId);
    }

    @Override
    public void endStream() {
        ended = true;
    }

    int getWindowBits() {
        return windowBits;
    }

    boolean isEnded() {
        return ended;
    }

    List<String> getCommands() {
        return commands;
    }

    byte[] toByteArray() {
        int total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            total += chunks.get(i).length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (int i = 0; i < chunks.size(); i++) {
            byte[] c = chunks.get(i);
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length;
        }
        return out;
    }
}

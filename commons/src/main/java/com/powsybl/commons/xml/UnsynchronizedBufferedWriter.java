/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.commons.xml;

import java.io.IOException;
import java.io.Writer;

/**
 * A buffering {@link Writer} that does not synchronize on each write.
 *
 * <p>Since JDK 17 the standard {@link java.io.Writer} implementations (OutputStreamWriter, BufferedWriter, and the
 * StAX writers built on them) acquire an internal lock on every {@code write} call. XML serialization is
 * single-threaded, so that lock is pure overhead - and StAX makes a very large number of small write calls (one per
 * element name, attribute, value, indentation...). This writer accumulates those small writes in a plain
 * {@code char[]} without locking and flushes them to the (locking) encoding delegate in large chunks, so the lock is
 * taken a handful of times instead of millions.</p>
 *
 * <p>Encoding is delegated to the wrapped writer (typically an {@code OutputStreamWriter}), so the produced bytes are
 * identical to writing directly to that delegate. Callers must {@link #flush()} or {@link #close()} the resulting
 * writer (directly or through the StAX writer) before the underlying stream is closed, otherwise the buffered tail is
 * lost.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class UnsynchronizedBufferedWriter extends Writer {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private final Writer delegate;
    private final char[] buffer;
    private int position;

    UnsynchronizedBufferedWriter(Writer delegate) {
        this(delegate, DEFAULT_BUFFER_SIZE);
    }

    UnsynchronizedBufferedWriter(Writer delegate, int size) {
        this.delegate = delegate;
        this.buffer = new char[size];
    }

    @Override
    public void write(int c) throws IOException {
        if (position >= buffer.length) {
            flushBuffer();
        }
        buffer[position++] = (char) c;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (len >= buffer.length) {
            // too big to buffer: flush what we have and write it through directly
            flushBuffer();
            delegate.write(cbuf, off, len);
            return;
        }
        if (position + len > buffer.length) {
            flushBuffer();
        }
        System.arraycopy(cbuf, off, buffer, position, len);
        position += len;
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        if (len >= buffer.length) {
            flushBuffer();
            delegate.write(str, off, len);
            return;
        }
        if (position + len > buffer.length) {
            flushBuffer();
        }
        str.getChars(off, off + len, buffer, position);
        position += len;
    }

    private void flushBuffer() throws IOException {
        if (position > 0) {
            delegate.write(buffer, 0, position);
            position = 0;
        }
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        // Flush but do not close the delegate: a StAX writer must not close the underlying output stream
        // (JSR-173), and the caller that created the stream is responsible for closing it.
        flush();
    }
}

/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.commons.xml;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * An {@link OutputStreamWriter} that buffers characters without synchronizing on each write.
 *
 * <p>Since JDK 17 the standard {@link Writer} implementations (OutputStreamWriter, BufferedWriter, and the StAX
 * writers built on them) acquire an internal lock on every {@code write} call. XML serialization is single-threaded,
 * so that lock is pure overhead - and StAX makes a very large number of small write calls (one per element name,
 * attribute, value, indentation...). This writer accumulates those small writes in a plain {@code char[]} without
 * locking and passes them to the (locking) superclass in large chunks, so the lock is taken a handful of times
 * instead of millions.</p>
 *
 * <p><b>Why it extends {@code OutputStreamWriter} instead of wrapping one:</b> the JDK StAX writer only installs the
 * encoder that escapes characters unrepresentable in the target charset (as {@code &#xNNNN;} references) when the
 * {@code Writer} it is given is itself an {@code OutputStreamWriter} - it discovers the encoding by calling
 * {@link #getEncoding()} on it. Wrapping one in any decorator (including {@link java.io.BufferedWriter}) silently
 * defeats that check, and such characters degrade to {@code '?'}. Extending it keeps the escaping intact while still
 * removing the per-write lock.</p>
 *
 * <p>Callers must {@link #flush()} or {@link #close()} this writer (directly or through the StAX writer) before the
 * underlying stream is closed, otherwise the buffered tail is lost - see {@link FlushOnEndDocumentStreamWriter}.</p>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class UnsynchronizedBufferedWriter extends OutputStreamWriter {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private final char[] buffer;
    private int position;

    UnsynchronizedBufferedWriter(OutputStream os, Charset charset) {
        this(os, charset, DEFAULT_BUFFER_SIZE);
    }

    UnsynchronizedBufferedWriter(OutputStream os, Charset charset, int size) {
        super(os, charset);
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
            // too big to be worth buffering: flush what we have and encode it in one go
            flushBuffer();
            super.write(cbuf, off, len);
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
            super.write(str, off, len);
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
            super.write(buffer, 0, position);
            position = 0;
        }
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        super.flush();
    }

    @Override
    public void close() throws IOException {
        // Flush but do not close the underlying stream: a StAX writer must not close it (JSR-173), and the caller
        // that created the stream is responsible for closing it.
        flush();
    }
}

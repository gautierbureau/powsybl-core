/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.commons.xml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class UnsynchronizedBufferedWriterTest {

    /** Records whether it was closed, so a test can check the underlying stream is left open. */
    private static final class RecordingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static String utf8(ByteArrayOutputStream os) {
        return os.toString(StandardCharsets.UTF_8);
    }

    @Test
    void smallWritesAreBufferedUntilFlush() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(os, StandardCharsets.UTF_8, 16)) {
            writer.write("ab");
            writer.write("cd");
            // still in the buffer: nothing has reached the stream yet
            assertEquals("", utf8(os));

            writer.flush();
            assertEquals("abcd", utf8(os));
        }
    }

    @Test
    void writeSingleCharAcrossTheBufferBoundary() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(os, StandardCharsets.UTF_8, 4)) {
            // fills the 4-char buffer exactly, then the 5th char forces a drain before being buffered
            for (int i = 0; i < 5; i++) {
                writer.write('a' + i);
            }
        }
        assertEquals("abcde", utf8(os));
    }

    @Test
    void writeOfExactlyBufferSizeIsWrittenThrough() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(os, StandardCharsets.UTF_8, 4)) {
            // len >= buffer.length takes the write-through branch rather than being buffered
            writer.write("abcd");
        }
        assertEquals("abcd", utf8(os));
    }

    @Test
    void writeLargerThanBufferKeepsOrder() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(os, StandardCharsets.UTF_8, 8)) {
            writer.write("head");
            // 12 chars >= the 8-char buffer: the pending "head" must be drained before the pass-through,
            // otherwise it would end up after it
            writer.write("0123456789ab");
            writer.write("tail");
        }
        assertEquals("head0123456789abtail", utf8(os));
    }

    @Test
    void writeThatOverflowsTheBufferDrainsFirst() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(os, StandardCharsets.UTF_8, 8)) {
            writer.write("abcde");
            // 5 more chars do not fit alongside the pending 5: drain, then buffer
            writer.write("fghij");
        }
        assertEquals("abcdefghij", utf8(os));
    }

    @Test
    void charArrayAndStringWritesAreEquivalent() throws IOException {
        ByteArrayOutputStream fromChars = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(fromChars, StandardCharsets.UTF_8, 8)) {
            writer.write("xxhelloyy".toCharArray(), 2, 5);
        }
        ByteArrayOutputStream fromString = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(fromString, StandardCharsets.UTF_8, 8)) {
            writer.write("xxhelloyy", 2, 5);
        }
        assertEquals("hello", utf8(fromChars));
        assertEquals("hello", utf8(fromString));
    }

    @Test
    void closeFlushesButDoesNotCloseTheStream() throws IOException {
        RecordingOutputStream os = new RecordingOutputStream();
        Writer writer = new UnsynchronizedBufferedWriter(os, StandardCharsets.UTF_8, 16);
        writer.write("tail");
        writer.close();

        assertEquals("tail", utf8(os));
        // a StAX writer must not close the underlying stream (JSR-173): its creator owns it
        assertFalse(os.closed);
    }

    @Test
    void flushOnEmptyBufferWritesNothing() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(os, StandardCharsets.UTF_8, 16)) {
            writer.flush();
            writer.flush();
        }
        assertEquals(0, os.size());
    }

    @Test
    void encodesLikeAPlainOutputStreamWriter() throws IOException {
        String text = "café 中文 € àéîõü";
        ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        try (Writer writer = new UnsynchronizedBufferedWriter(buffered, StandardCharsets.UTF_8, 8)) {
            // split across the buffer boundary to exercise chunked encoding
            for (int i = 0; i < text.length(); i++) {
                writer.write(text.charAt(i));
            }
        }
        ByteArrayOutputStream reference = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(reference, StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        assertArrayEquals(reference.toByteArray(), buffered.toByteArray());
    }

    @Test
    void isAnOutputStreamWriterReportingItsEncoding() throws IOException {
        // The JDK StAX writer only escapes characters unrepresentable in the target charset when the Writer it is
        // handed is an OutputStreamWriter it can query for its encoding. Wrapping one instead of extending it
        // silently degrades those characters to '?', so this relationship is load-bearing.
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (UnsynchronizedBufferedWriter writer =
                     new UnsynchronizedBufferedWriter(os, StandardCharsets.ISO_8859_1, 16)) {
            assertInstanceOf(OutputStreamWriter.class, writer);
            assertEquals(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.ISO_8859_1)
                    .getEncoding(), writer.getEncoding());
        }
    }
}

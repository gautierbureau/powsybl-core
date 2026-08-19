/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.commons.xml;

import javanet.staxutils.helpers.StreamWriterDelegate;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * {@link XMLStreamWriter} decorator that flushes the underlying writer when the document ends.
 *
 * <p>Several powsybl export paths finalize their writer by calling {@link #writeEndDocument()} only, without a
 * subsequent {@code flush()}/{@code close()} on the StAX writer (they rely on closing the output stream). That works
 * with a StAX writer that writes eagerly to the stream, but not with a buffering writer (see
 * {@link UnsynchronizedBufferedWriter}), whose buffered tail would be lost. Flushing on {@code writeEndDocument} makes
 * the buffering safe for every caller without requiring each of them to flush explicitly.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class FlushOnEndDocumentStreamWriter extends StreamWriterDelegate {

    FlushOnEndDocumentStreamWriter(XMLStreamWriter out) {
        super(out);
    }

    @Override
    public void writeEndDocument() throws XMLStreamException {
        super.writeEndDocument();
        flush();
    }
}

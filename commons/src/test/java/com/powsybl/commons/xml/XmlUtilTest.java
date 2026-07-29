/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.commons.xml;

import com.google.common.collect.ImmutableMap;
import com.powsybl.commons.PowsyblException;
import org.junit.jupiter.api.Test;
import org.xml.sax.*;

import javax.xml.stream.*;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.powsybl.commons.xml.XmlUtil.getXMLInputFactory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class XmlUtilTest {

    private static final String XML = String.join(System.lineSeparator(),
            "<a>",
            "    <b attrBool=\"true\" attrInt=\"34\" attrDbl=\"2e-65\" attrFlt=\"0.054864\">",
            "        <c/>",
            "    </b>",
            "    <d/>",
            "</a>");

    @Test
    void readAttributes() throws XMLStreamException {
        AtomicReference<Boolean> attrBoolBoxed = new AtomicReference<>(false);
        AtomicBoolean attrBool = new AtomicBoolean(false);
        AtomicReference<Integer> attrInteger = new AtomicReference<>(-1);
        AtomicInteger attrInt = new AtomicInteger(-1);
        AtomicReference<Double> attrDbl = new AtomicReference<>(0d);
        AtomicReference<Float> attrFloat = new AtomicReference<>(0f);
        try (StringReader reader = new StringReader(XML)) {
            XMLStreamReader xmlReader = getXMLInputFactory().createXMLStreamReader(reader);
            xmlReader.next();
            try {
                XmlUtil.readSubElements(xmlReader, elementName -> {
                    if ("b".equals(elementName)) {
                        attrBoolBoxed.set(XmlUtil.readBooleanAttribute(xmlReader, "attrBool"));
                        attrBool.set(XmlUtil.readBooleanAttribute(xmlReader, "attrBool", false));
                        attrInteger.set(XmlUtil.readIntegerAttribute(xmlReader, "attrInt"));
                        attrInt.set(XmlUtil.readIntAttribute(xmlReader, "attrInt", -1));
                        attrDbl.set(XmlUtil.readDoubleAttribute(xmlReader, "attrDbl", 0));
                        attrFloat.set(XmlUtil.readFloatAttribute(xmlReader, "attrFlt", 0));
                    }
                });
            } finally {
                xmlReader.close();
            }
        }

        assertTrue(attrBoolBoxed::get);
        assertTrue(attrBool.get());
        assertEquals(34, attrInteger.get());
        assertEquals(34, attrInt.get());
        assertEquals(2e-65, attrDbl.get(), 1e-80);
        assertEquals(0.054864f, attrFloat.get(), 1e-15);
    }

    @Test
    void readUntilEndElementWithDepthTest() throws XMLStreamException {
        Map<String, Integer> depths = new HashMap<>();
        try (StringReader reader = new StringReader(XML)) {
            XMLStreamReader xmlReader = getXMLInputFactory().createXMLStreamReader(reader);
            xmlReader.next();
            try {
                XmlUtil.readSubElements(xmlReader, elementName -> {
                    depths.put(elementName, 0);
                    XmlUtil.readSubElements(xmlReader, elementName1 -> {
                        depths.put(elementName1, 1);
                        XmlUtil.skipSubElements(xmlReader);
                    });
                });
            } finally {
                xmlReader.close();
            }
        }
        assertEquals(ImmutableMap.of("b", 0, "c", 1, "d", 0), depths);
    }

    @Test
    void nestedReadUntilEndElementWithDepthTest() throws XMLStreamException {
        Map<String, Integer> depths = new HashMap<>();
        try (StringReader reader = new StringReader(XML)) {
            XMLStreamReader xmlReader = getXMLInputFactory().createXMLStreamReader(reader);
            try {
                xmlReader.next();
                XmlUtil.readSubElements(xmlReader, elementName -> {
                    depths.put(elementName, 0);
                    // consume b and c
                    if (elementName.equals("b")) {
                        XmlUtil.readSubElements(xmlReader, elementName1 -> {
                            depths.put(elementName1, 1);
                            XmlUtil.skipSubElements(xmlReader);
                        });
                    }
                });
            } finally {
                xmlReader.close();
            }
        }
        assertEquals(ImmutableMap.of("b", 0, "c", 1, "d", 0), depths);
    }

    @Test
    void readUntilStartElementTest() throws XMLStreamException {
        readUntilStartElementTest("/a", "a");
        readUntilStartElementTest("/a/b/c", "c");
        readUntilStartElementTest("/a/d", "d");

        readUntilStartElementNotFoundTest("/a/e", "a");
        readUntilStartElementNotFoundTest("/a/b/a", "b");

        try {
            readUntilStartElementTest("/b", null);
        } catch (PowsyblException e) {
            assertEquals("Unable to find b: end of document has been reached", e.getMessage());
        }
    }

    private void readUntilStartElementTest(String path, String expected) throws XMLStreamException {
        try (StringReader reader = new StringReader(XML)) {
            XMLStreamReader xmlReader = getXMLInputFactory().createXMLStreamReader(reader);
            try {
                XmlUtil.readUntilStartElement(path, xmlReader, elementName -> assertEquals(expected, xmlReader.getLocalName()));
            } finally {
                xmlReader.close();
            }
        }
    }

    private void readUntilStartElementNotFoundTest(String path, String parent) throws XMLStreamException {
        try {
            readUntilStartElementTest(path, null);
        } catch (PowsyblException e) {
            assertEquals("Unable to find " + path + ": parent element " + parent + " has been closed", e.getMessage());
        }
    }

    @Test
    void readTextTest() throws XMLStreamException {
        String xml = "<a>hello</a>";
        try (StringReader reader = new StringReader(xml)) {
            XMLStreamReader xmlReader = getXMLInputFactory().createXMLStreamReader(reader);
            try {
                String text = null;
                while (xmlReader.hasNext()) {
                    int next = xmlReader.next();
                    if (next == XMLStreamConstants.START_ELEMENT && xmlReader.getLocalName().equals("a")) {
                        text = XmlUtil.readText(xmlReader);
                    }
                }
                assertEquals("hello", text);
            } finally {
                xmlReader.close();
            }
        }
    }

    @Test
    void initializeWriterDefault() throws XMLStreamException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLStreamWriter writer = XmlUtil.initializeWriter(true, " ", baos);
        writer.close();
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", baos.toString());
    }

    @Test
    void initializeWriter() throws XMLStreamException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLStreamWriter writer = XmlUtil.initializeWriter(false, " ", baos, StandardCharsets.ISO_8859_1);
        writer.close();
        assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>", baos.toString());
    }

    @Test
    void writerBufferedTailIsNotLostWithoutExplicitFlush() throws XMLStreamException {
        // The OutputStream path buffers, and several powsybl exporters finalize with writeEndDocument() only,
        // relying on closing the stream. Nothing may be dropped in that case.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLStreamWriter writer = XmlUtil.initializeWriter(false, " ", baos);
        writer.writeStartElement("a");
        writer.writeAttribute("attr", "value");
        writer.writeCharacters("some text content");
        writer.writeEndElement();
        writer.writeEndDocument();
        // deliberately no flush() and no close()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><a attr=\"value\">some text content</a>",
                baos.toString(StandardCharsets.UTF_8));
    }

    @Test
    void writerEscapesCharactersNotRepresentableInTheTargetCharset() throws XMLStreamException {
        // Building the StAX writer over a Writer rather than over the OutputStream must not lose the numeric
        // character references the JDK emits for characters the declared encoding cannot represent, otherwise
        // they would silently degrade to '?'.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLStreamWriter writer = XmlUtil.initializeWriter(false, " ", baos, StandardCharsets.ISO_8859_1);
        writer.writeStartElement("a");
        writer.writeAttribute("attr", "café 中文");
        writer.writeCharacters("café €");
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.close();

        assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                        + "<a attr=\"café &#x4e2d;&#x6587;\">café &#x20ac;</a>",
                baos.toString(StandardCharsets.ISO_8859_1));
    }

    @Test
    void writerOverOutputStreamAndOverWriterProduceTheSameDocument() throws XMLStreamException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        XMLStreamWriter streamWriter = XmlUtil.initializeWriter(true, "  ", baos);
        writeSampleDocument(streamWriter);
        streamWriter.close();

        StringWriter stringWriter = new StringWriter();
        XMLStreamWriter writerOnWriter = XmlUtil.initializeWriter(true, "  ", stringWriter);
        writeSampleDocument(writerOnWriter);
        writerOnWriter.close();

        assertEquals(stringWriter.toString(), baos.toString(StandardCharsets.UTF_8));
    }

    private static void writeSampleDocument(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("network");
        for (int i = 0; i < 100; i++) {
            writer.writeEmptyElement("line");
            writer.writeAttribute("id", "LINE_" + i);
            writer.writeAttribute("r", "0.0512345");
        }
        writer.writeEndElement();
        writer.writeEndDocument();
    }

    @Test
    void testSchemaFactory() {
        SchemaFactory factory = XmlUtil.getSchemaFactory();
        assertNotNull(factory);
        String safeXsd = """
                <?xml version="1.0"?>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>
                """;
        assertDoesNotThrow(() -> factory.newSchema(new StreamSource(new StringReader(safeXsd))));
    }

    @Test
    void schemaFactoryShouldRejectExternalEntityResolution() {
        SchemaFactory factory = XmlUtil.getSchemaFactory();
        assertNotNull(factory);
        String exploitXsd = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                    <xs:annotation>
                        <xs:documentation>&xxe;</xs:documentation>
                    </xs:annotation>
                </xs:schema>
                """;
        assertThrows(SAXParseException.class, () -> factory.newSchema(new StreamSource(new StringReader(exploitXsd))));
    }

    @Test
    void schemaFactoryShouldRejectExternalSchemaImport() {
        SchemaFactory factory = XmlUtil.createSchemaFactoryInstance();
        assertNotNull(factory);
        String exploitXsd1 = """
                <?xml version="1.0"?>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" >
                    <xs:import schemaLocation="file:///etc/passwd"/>
                </xs:schema>
                """;
        String exploitXsd2 = """
                <?xml version="1.0"?>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" >
                    <xs:import schemaLocation="http://localhost:12345/test.xsd"/>
                </xs:schema>
                """;
        assertThrows(SAXParseException.class, () -> factory.newSchema(new StreamSource(new StringReader(exploitXsd1))));
        assertThrows(SAXParseException.class, () -> factory.newSchema(new StreamSource(new StringReader(exploitXsd2))));
    }

    @Test
    void xmlReaderShouldRejectExternalEntityResolution() throws Exception {
        XMLReader xmlReader = XmlUtil.createXMLReader();
        assertNotNull(xmlReader);
        String exploitXml = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <root>&xxe;</root>
            """;
        assertThrows(Exception.class, () -> xmlReader.parse(new InputSource(new StringReader(exploitXml))));
    }

}

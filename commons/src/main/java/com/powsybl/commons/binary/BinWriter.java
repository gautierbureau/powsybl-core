/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.commons.binary;

import com.github.luben.zstd.ZstdOutputStream;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.io.AbstractTreeDataWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.powsybl.commons.binary.BinUtil.*;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 * @author Clement Leclerc {@literal <clement.leclerc at rte-france.com>}
 */
public class BinWriter extends AbstractTreeDataWriter {

    private final String rootVersion;
    private final byte[] binaryMagicNumber;
    private final OutputStream outputStream;
    private final SegmentedByteBuffer body = new SegmentedByteBuffer();
    // Lookup keyed by name, holding the assigned index per type tag (0 = not yet assigned), so the hot
    // writeEntry path does not allocate a TypedName key on every attribute. The dictionary list keeps the
    // (name, type) entries in index order for serialization - index of an entry is its position + 1.
    private final Map<String, int[]> namesIndex = new HashMap<>();
    private final List<TypedName> dictionary = new ArrayList<>();
    private Map<String, String> extensionVersions = Collections.emptyMap();

    private static final int NB_TYPES = TYPE_OBJECT + 1;

    private record TypedName(String name, byte type) { }

    public BinWriter(OutputStream os, byte[] binaryMagicNumber, String rootVersion) {
        this.outputStream = Objects.requireNonNull(os);
        this.binaryMagicNumber = Objects.requireNonNull(binaryMagicNumber);
        this.rootVersion = Objects.requireNonNull(rootVersion);
    }

    @Override
    public void writeStartNodes() {
        // nothing to do
    }

    @Override
    public void writeEndNodes() {
        // nothing to do
    }

    @Override
    public void writeNamespace(String prefix, String namespace) {
        // nothing to do
    }

    @Override
    public void writeStartNode(String namespace, String name) {
        if (dictionary.isEmpty()) {
            // root element is not a child of another node, its index is not consumed in the body
            namesIndex.computeIfAbsent(name, k -> new int[NB_TYPES])[TYPE_OBJECT] = 1;
            dictionary.add(new TypedName(name, TYPE_OBJECT));
        } else {
            writeEntry(name, TYPE_OBJECT);
        }
    }

    @Override
    public void writeEndNode() {
        body.writeShort(END_NODE);
    }

    private void writeEntry(String name, byte type) {
        int[] indexByType = namesIndex.computeIfAbsent(name, k -> new int[NB_TYPES]);
        int index = indexByType[type];
        if (index == 0) {
            index = dictionary.size() + 1;
            if (index > MAX_NAME_IDX) {
                throw new PowsyblException("Binary format: too many distinct names (max " + MAX_NAME_IDX + ")");
            }
            indexByType[type] = index;
            dictionary.add(new TypedName(name, type));
        }
        body.writeShort(index);
    }

    @Override
    public void writeNodeContent(String value) {
        writeEntry("", TYPE_STRING_CONTENT);
        writeString(value);
    }

    @Override
    public void writeStringAttribute(String name, String value) {
        writeEntry(name, TYPE_STRING);
        writeString(value);
    }

    private void writeString(String value) {
        if (value == null) {
            body.writeShort(NULL_STRING_SENTINEL);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= NULL_STRING_SENTINEL) {
            throw new PowsyblException("Binary format: string too long (max " + (NULL_STRING_SENTINEL - 1) + " bytes)");
        }
        body.writeShort(bytes.length);
        body.writeBytes(bytes);
    }

    private static void writeString(String value, OutputStream out) throws IOException {
        if (value == null) {
            writeShort(out, NULL_STRING_SENTINEL);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= NULL_STRING_SENTINEL) {
            throw new PowsyblException("Binary format: string too long (max " + (NULL_STRING_SENTINEL - 1) + " bytes)");
        }
        writeShort(out, bytes.length);
        out.write(bytes);
    }

    private static void writeShort(OutputStream out, int s) throws IOException {
        out.write((s >>> 8) & 0xFF);
        out.write(s & 0xFF);
    }

    @Override
    public void writeStringAttribute(String name, String value, String defaultValue) {
        if (Objects.equals(defaultValue, name)) {
            return;
        }
        writeStringAttribute(name, value);
    }

    @Override
    public void writeDoubleAttribute(String name, double value) {
        writeDoubleAttribute(name, value, Double.NaN);
    }

    @Override
    public void writeDoubleAttribute(String name, double value, double absentValue) {
        boolean isAbsent = Double.isNaN(absentValue) ? Double.isNaN(value) : value == absentValue;
        if (isAbsent) {
            return;
        }
        writeEntry(name, TYPE_DOUBLE);
        body.writeDouble(value);
    }

    @Override
    public void writeFloatAttribute(String name, float value) {
        if (Float.isNaN(value)) {
            return;
        }
        writeEntry(name, TYPE_FLOAT);
        body.writeFloat(value);
    }

    @Override
    public void writeIntAttribute(String name, int value) {
        writeEntry(name, TYPE_INT);
        body.writeInt(value);
    }

    @Override
    public void writeIntAttribute(String name, int value, int absentValue) {
        if (value == absentValue) {
            return;
        }
        writeIntAttribute(name, value);
    }

    @Override
    public void writeBooleanAttribute(String name, boolean value) {
        writeEntry(name, TYPE_BOOLEAN);
        body.writeBoolean(value);
    }

    @Override
    public void writeBooleanAttribute(String name, boolean value, boolean absentValue) {
        if (value == absentValue) {
            return;
        }
        writeBooleanAttribute(name, value);
    }

    @Override
    public <E extends Enum<E>> void writeEnumAttribute(String name, E value) {
        if (value == null) {
            return;
        }
        writeEntry(name, TYPE_ENUM);
        body.writeShort(value.ordinal());
    }

    @Override
    public void writeIntArrayAttribute(String name, Collection<Integer> values) {
        writeEntry(name, TYPE_INT_ARRAY);
        body.writeShort(values.size());
        for (int v : values) {
            body.writeInt(v);
        }
    }

    @Override
    public void writeStringArrayAttribute(String name, Collection<String> values) {
        writeEntry(name, TYPE_STRING_ARRAY);
        body.writeShort(values.size());
        for (String s : values) {
            writeString(s);
        }
    }

    @Override
    public void setVersions(Map<String, String> extensionVersions) {
        this.extensionVersions = Objects.requireNonNull(extensionVersions);
    }

    @Override
    public void close() {
        try (var zstdOut = new ZstdOutputStream(outputStream, -2).setChecksum(false)) {
            writeHeader(zstdOut);
            body.writeTo(zstdOut);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeHeader(OutputStream out) throws IOException {
        out.write(binaryMagicNumber);
        writeString(rootVersion, out);

        writeShort(out, extensionVersions.size());
        for (var entry : extensionVersions.entrySet()) {
            writeString(entry.getKey(), out);
            writeString(entry.getValue(), out);
        }

        writeShort(out, dictionary.size());
        for (TypedName key : dictionary) {
            writeString(key.name(), out);
            out.write(key.type());
        }
        out.flush();
    }
}

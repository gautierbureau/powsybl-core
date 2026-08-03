/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.io.DeserializerContext;
import com.powsybl.commons.io.TreeDataReader;
import com.powsybl.commons.io.TreeDataWriter;
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.MeasurementPointAdder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the body of a measurement point — its id, the buses of the bus/breaker view it
 * is measured at, and the busbar sections — kept in one place because a point is shared between the
 * extensions watching one, a tap changer blocking, an ACMC, a SMACC, and serialized the same for
 * each: a bus as {@code <bus voltageLevel="VL">busId</bus>}, a busbar section as
 * {@code <busbarSection>bbsId</busbarSection>}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class MeasurementPointSerDe {

    public static final String BUS_ROOT_ELEMENT = "bus";
    public static final String BUS_ARRAY_ELEMENT = "buses";
    public static final String BUSBAR_SECTION_ROOT_ELEMENT = "busbarSection";
    public static final String BUSBAR_SECTION_ARRAY_ELEMENT = "busbarSections";

    private static final String VOLTAGE_LEVEL_ATTRIBUTE = "voltageLevel";
    private static final String ID_ATTRIBUTE = "id";

    private MeasurementPointSerDe() {
    }

    /**
     * The array to single element names a measurement point brings, for an extension serializer to
     * merge into its own {@code getArrayNameToSingleNameMap}.
     */
    public static Map<String, String> arrayNameToSingleNameMap() {
        return Map.of(BUS_ARRAY_ELEMENT, BUS_ROOT_ELEMENT,
                BUSBAR_SECTION_ARRAY_ELEMENT, BUSBAR_SECTION_ROOT_ELEMENT);
    }

    /**
     * Writes the id and the buses and busbar sections of a point, the enclosing element already
     * opened by the caller under whichever name the extension gives it.
     */
    public static void writeBody(MeasurementPoint measurementPoint, TreeDataWriter writer, String namespaceUri) {
        writer.writeStringAttribute(ID_ATTRIBUTE, measurementPoint.getId());
        writer.writeStartNodes();
        for (MeasurementPoint.BusRef bus : measurementPoint.getBuses()) {
            writer.writeStartNode(namespaceUri, BUS_ROOT_ELEMENT);
            writer.writeStringAttribute(VOLTAGE_LEVEL_ATTRIBUTE, bus.voltageLevelId());
            writer.writeNodeContent(bus.busId());
            writer.writeEndNode();
        }
        writer.writeEndNodes();
        writer.writeStartNodes();
        for (String busbarSectionId : measurementPoint.getBusbarSectionIds()) {
            writer.writeStartNode(namespaceUri, BUSBAR_SECTION_ROOT_ELEMENT);
            writer.writeNodeContent(busbarSectionId);
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    /**
     * Reads the id and the buses and busbar sections of a point into an adder, the caller having
     * entered the enclosing element and being the one to {@code add()} the point to its parent.
     */
    public static void readBody(MeasurementPointAdder<?> adder, DeserializerContext context, String parentElement) {
        TreeDataReader reader = context.getReader();
        String id = reader.readStringAttribute(ID_ATTRIBUTE);
        List<MeasurementPoint.BusRef> buses = new ArrayList<>();
        List<String> busbarSectionIds = new ArrayList<>();
        reader.readChildNodes(elementName -> {
            switch (elementName) {
                case BUS_ROOT_ELEMENT -> {
                    String voltageLevelId = reader.readStringAttribute(VOLTAGE_LEVEL_ATTRIBUTE);
                    buses.add(new MeasurementPoint.BusRef(voltageLevelId, reader.readContent()));
                }
                case BUSBAR_SECTION_ROOT_ELEMENT -> busbarSectionIds.add(reader.readContent());
                default -> throw new PowsyblException("Unknown element name '" + elementName + "' in '" + parentElement + "'");
            }
        });
        adder.withId(id);
        adder.withBuses(buses);
        adder.withBusbarSectionIds(busbarSectionIds);
    }
}

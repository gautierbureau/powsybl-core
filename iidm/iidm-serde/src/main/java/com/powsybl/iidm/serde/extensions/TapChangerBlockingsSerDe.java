/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde.extensions;

import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.extensions.AbstractExtensionSerDe;
import com.powsybl.commons.extensions.ExtensionSerDe;
import com.powsybl.commons.io.DeserializerContext;
import com.powsybl.commons.io.SerializerContext;
import com.powsybl.commons.io.TreeDataWriter;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.ControlVoltageLevel;
import com.powsybl.iidm.network.extensions.ControlVoltageLevelAdder;
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.TapChangerBlocking;
import com.powsybl.iidm.network.extensions.TapChangerBlockingAdder;
import com.powsybl.iidm.network.extensions.TapChangerBlockings;
import com.powsybl.iidm.network.extensions.TapChangerBlockingsAdder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the tap changer blockings, the buses and busbar sections a point is measured at
 * kept apart the way the secondary voltage control pilot point keeps them: a bus as
 * {@code <bus voltageLevel="VL">busId</bus>}, a busbar section as
 * {@code <busbarSection>bbsId</busbarSection>}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
@AutoService(ExtensionSerDe.class)
public class TapChangerBlockingsSerDe extends AbstractExtensionSerDe<Network, TapChangerBlockings> {

    private static final String TCB_ROOT_ELEMENT = "tcb";
    private static final String MEASUREMENT_POINT_ROOT_ELEMENT = "measurementPoint";
    private static final String MEASUREMENT_POINT_ARRAY_ELEMENT = "measurementPoints";
    private static final String CONTROL_VOLTAGE_LEVEL_ROOT_ELEMENT = "controlVoltageLevel";
    private static final String CONTROL_VOLTAGE_LEVEL_ARRAY_ELEMENT = "controlVoltageLevels";
    private static final String BUS_ROOT_ELEMENT = "bus";
    private static final String BUS_ARRAY_ELEMENT = "buses";
    private static final String BUSBAR_SECTION_ROOT_ELEMENT = "busbarSection";
    private static final String BUSBAR_SECTION_ARRAY_ELEMENT = "busbarSections";
    private static final String VOLTAGE_LEVEL_ATTRIBUTE = "voltageLevel";
    private static final String ID_ATTRIBUTE = "id";
    private static final String NAME_ATTRIBUTE = "name";
    private static final String FORCE_ONE_TRANSFORMER_LOADS_ATTRIBUTE = "forceOneTransformerLoads";

    public TapChangerBlockingsSerDe() {
        super(TapChangerBlockings.NAME, "network", TapChangerBlockings.class,
                "tapChangerBlockings_V1_0.xsd", "http://www.powsybl.org/schema/iidm/ext/tapchangerblockings/1_0", "tcb");
    }

    @Override
    public Map<String, String> getArrayNameToSingleNameMap() {
        return Map.of(MEASUREMENT_POINT_ARRAY_ELEMENT, MEASUREMENT_POINT_ROOT_ELEMENT,
                CONTROL_VOLTAGE_LEVEL_ARRAY_ELEMENT, CONTROL_VOLTAGE_LEVEL_ROOT_ELEMENT,
                BUS_ARRAY_ELEMENT, BUS_ROOT_ELEMENT,
                BUSBAR_SECTION_ARRAY_ELEMENT, BUSBAR_SECTION_ROOT_ELEMENT);
    }

    @Override
    public void write(TapChangerBlockings tcbs, SerializerContext context) {
        TreeDataWriter writer = context.getWriter();
        writer.writeStartNodes();
        for (TapChangerBlocking tcb : tcbs.getTapChangerBlockings()) {
            writer.writeStartNode(getNamespaceUri(), TCB_ROOT_ELEMENT);
            writer.writeStringAttribute(NAME_ATTRIBUTE, tcb.getName());
            writeMeasurementPoints(tcb, writer);
            writeControlVoltageLevels(tcb.getControlVoltageLevels(), writer);
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    private void writeMeasurementPoints(TapChangerBlocking tcb, TreeDataWriter writer) {
        writer.writeStartNodes();
        for (MeasurementPoint measurementPoint : tcb.getMeasurementPoints()) {
            writer.writeStartNode(getNamespaceUri(), MEASUREMENT_POINT_ROOT_ELEMENT);
            writer.writeStringAttribute(ID_ATTRIBUTE, measurementPoint.getId());
            writer.writeStartNodes();
            for (MeasurementPoint.BusRef bus : measurementPoint.getBuses()) {
                writer.writeStartNode(getNamespaceUri(), BUS_ROOT_ELEMENT);
                writer.writeStringAttribute(VOLTAGE_LEVEL_ATTRIBUTE, bus.voltageLevelId());
                writer.writeNodeContent(bus.busId());
                writer.writeEndNode();
            }
            writer.writeEndNodes();
            writer.writeStartNodes();
            for (String busbarSectionId : measurementPoint.getBusbarSectionIds()) {
                writer.writeStartNode(getNamespaceUri(), BUSBAR_SECTION_ROOT_ELEMENT);
                writer.writeNodeContent(busbarSectionId);
                writer.writeEndNode();
            }
            writer.writeEndNodes();
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    private void writeControlVoltageLevels(List<ControlVoltageLevel> controlVoltageLevels, TreeDataWriter writer) {
        writer.writeStartNodes();
        for (ControlVoltageLevel controlVoltageLevel : controlVoltageLevels) {
            writer.writeStartNode(getNamespaceUri(), CONTROL_VOLTAGE_LEVEL_ROOT_ELEMENT);
            if (controlVoltageLevel.forceOneTransformerLoads()) {
                writer.writeStringAttribute(FORCE_ONE_TRANSFORMER_LOADS_ATTRIBUTE, "true");
            }
            writer.writeNodeContent(controlVoltageLevel.getId());
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    @Override
    public TapChangerBlockings read(Network network, DeserializerContext context) {
        TapChangerBlockingsAdder adder = network.newExtension(TapChangerBlockingsAdder.class);
        context.getReader().readChildNodes(elementName -> {
            if (!elementName.equals(TCB_ROOT_ELEMENT)) {
                throw new PowsyblException(getExceptionMessageUnknownElement(elementName, TapChangerBlockings.NAME));
            }
            readTapChangerBlocking(context, adder);
        });
        return adder.add();
    }

    private static void readTapChangerBlocking(DeserializerContext context, TapChangerBlockingsAdder adder) {
        String name = context.getReader().readStringAttribute(NAME_ATTRIBUTE);
        TapChangerBlockingAdder tcbAdder = adder.newTapChangerBlocking().withName(name);
        context.getReader().readChildNodes(elementName -> {
            switch (elementName) {
                case MEASUREMENT_POINT_ROOT_ELEMENT -> readMeasurementPoint(context, tcbAdder);
                case CONTROL_VOLTAGE_LEVEL_ROOT_ELEMENT -> readControlVoltageLevel(context, tcbAdder);
                default -> throw new PowsyblException(getExceptionMessageUnknownElement(elementName, TCB_ROOT_ELEMENT));
            }
        });
        tcbAdder.add();
    }

    private static void readMeasurementPoint(DeserializerContext context, TapChangerBlockingAdder tcbAdder) {
        String id = context.getReader().readStringAttribute(ID_ATTRIBUTE);
        List<MeasurementPoint.BusRef> buses = new ArrayList<>();
        List<String> busbarSectionIds = new ArrayList<>();
        context.getReader().readChildNodes(elementName -> {
            switch (elementName) {
                case BUS_ROOT_ELEMENT -> {
                    String voltageLevelId = context.getReader().readStringAttribute(VOLTAGE_LEVEL_ATTRIBUTE);
                    buses.add(new MeasurementPoint.BusRef(voltageLevelId, context.getReader().readContent()));
                }
                case BUSBAR_SECTION_ROOT_ELEMENT -> busbarSectionIds.add(context.getReader().readContent());
                default -> throw new PowsyblException(getExceptionMessageUnknownElement(elementName, MEASUREMENT_POINT_ROOT_ELEMENT));
            }
        });
        tcbAdder.newMeasurementPoint()
                .withBuses(buses)
                .withBusbarSectionIds(busbarSectionIds)
                .withId(id)
                .add();
    }

    private static void readControlVoltageLevel(DeserializerContext context, TapChangerBlockingAdder tcbAdder) {
        boolean forceOneTransformerLoads = context.getReader().readBooleanAttribute(FORCE_ONE_TRANSFORMER_LOADS_ATTRIBUTE, false);
        String id = context.getReader().readContent();
        ControlVoltageLevelAdder<TapChangerBlockingAdder> vlAdder = tcbAdder.newControlVoltageLevel().withId(id);
        if (forceOneTransformerLoads) {
            vlAdder.withForceOneTransformerLoads();
        }
        vlAdder.add();
    }

    private static String getExceptionMessageUnknownElement(String elementName, String where) {
        return "Unknown element name '" + elementName + "' in '" + where + "'";
    }
}

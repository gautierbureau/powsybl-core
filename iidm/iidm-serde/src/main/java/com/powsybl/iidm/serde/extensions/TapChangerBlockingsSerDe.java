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
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.TapChangerBlocking;
import com.powsybl.iidm.network.extensions.TapChangerBlockingAdder;
import com.powsybl.iidm.network.extensions.TapChangerBlockings;
import com.powsybl.iidm.network.extensions.TapChangerBlockingsAdder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the tap changer blockings. The measurement points a blocking watches and the
 * voltage levels it controls are read and written by {@link MeasurementPointSerDe} and
 * {@link ControlVoltageLevelSerDe}, shared with the other extensions that watch or control one.
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
    private static final String NAME_ATTRIBUTE = "name";

    public TapChangerBlockingsSerDe() {
        super(TapChangerBlockings.NAME, "network", TapChangerBlockings.class,
                "tapChangerBlockings_V1_0.xsd", "http://www.powsybl.org/schema/iidm/ext/tapchangerblockings/1_0", "tcb");
    }

    @Override
    public Map<String, String> getArrayNameToSingleNameMap() {
        Map<String, String> map = new HashMap<>(MeasurementPointSerDe.arrayNameToSingleNameMap());
        map.put(MEASUREMENT_POINT_ARRAY_ELEMENT, MEASUREMENT_POINT_ROOT_ELEMENT);
        map.put(CONTROL_VOLTAGE_LEVEL_ARRAY_ELEMENT, CONTROL_VOLTAGE_LEVEL_ROOT_ELEMENT);
        return map;
    }

    @Override
    public void write(TapChangerBlockings tcbs, SerializerContext context) {
        TreeDataWriter writer = context.getWriter();
        writer.writeStartNodes();
        for (TapChangerBlocking tcb : tcbs.getTapChangerBlockings()) {
            writer.writeStartNode(getNamespaceUri(), TCB_ROOT_ELEMENT);
            writer.writeStringAttribute(NAME_ATTRIBUTE, tcb.getName());
            writeMeasurementPoints(tcb.getMeasurementPoints(), writer);
            writeControlVoltageLevels(tcb.getControlVoltageLevels(), writer);
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    private void writeMeasurementPoints(List<MeasurementPoint> measurementPoints, TreeDataWriter writer) {
        writer.writeStartNodes();
        for (MeasurementPoint measurementPoint : measurementPoints) {
            writer.writeStartNode(getNamespaceUri(), MEASUREMENT_POINT_ROOT_ELEMENT);
            MeasurementPointSerDe.writeBody(measurementPoint, writer, getNamespaceUri());
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    private void writeControlVoltageLevels(List<ControlVoltageLevel> controlVoltageLevels, TreeDataWriter writer) {
        writer.writeStartNodes();
        for (ControlVoltageLevel controlVoltageLevel : controlVoltageLevels) {
            writer.writeStartNode(getNamespaceUri(), CONTROL_VOLTAGE_LEVEL_ROOT_ELEMENT);
            ControlVoltageLevelSerDe.writeBody(controlVoltageLevel, writer);
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
                case MEASUREMENT_POINT_ROOT_ELEMENT -> {
                    var mpAdder = tcbAdder.newMeasurementPoint();
                    MeasurementPointSerDe.readBody(mpAdder, context, MEASUREMENT_POINT_ROOT_ELEMENT);
                    mpAdder.add();
                }
                case CONTROL_VOLTAGE_LEVEL_ROOT_ELEMENT -> ControlVoltageLevelSerDe.readBody(tcbAdder.newControlVoltageLevel(), context);
                default -> throw new PowsyblException(getExceptionMessageUnknownElement(elementName, TCB_ROOT_ELEMENT));
            }
        });
        tcbAdder.add();
    }

    private static String getExceptionMessageUnknownElement(String elementName, String where) {
        return "Unknown element name '" + elementName + "' in '" + where + "'";
    }
}

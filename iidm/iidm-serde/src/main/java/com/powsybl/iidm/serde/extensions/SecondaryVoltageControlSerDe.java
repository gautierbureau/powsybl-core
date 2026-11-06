/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde.extensions;

import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.extensions.ExtensionSerDe;
import com.powsybl.commons.io.DeserializerContext;
import com.powsybl.commons.io.SerializerContext;
import com.powsybl.commons.io.TreeDataWriter;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.*;
import com.powsybl.iidm.serde.IidmVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
@AutoService(ExtensionSerDe.class)
public class SecondaryVoltageControlSerDe extends AbstractVersionableNetworkExtensionSerDe<Network, SecondaryVoltageControl, SecondaryVoltageControlSerDe.Version> {

    public enum Version implements SerDeVersion<Version> {
        V_1_0("/xsd/secondaryVoltageControl_V1_0.xsd", "http://www.powsybl.org/schema/iidm/ext/secondary_voltage_control/1_0",
            new VersionNumbers(1, 0), IidmVersion.V_1_0, IidmVersion.V_1_18),
        V_1_1("/xsd/secondaryVoltageControl_V1_1.xsd", "http://www.powsybl.org/schema/iidm/ext/secondary_voltage_control/1_1",
            new VersionNumbers(1, 1), IidmVersion.V_1_0, null);

        private final VersionInfo versionInfo;

        Version(String xsdResourcePath, String namespaceUri, VersionNumbers versionNumbers, IidmVersion minIidmVersionIncluded, IidmVersion maxIidmVersionExcluded) {
            this.versionInfo = new VersionInfo(xsdResourcePath, namespaceUri, "svc", versionNumbers,
                    minIidmVersionIncluded, maxIidmVersionExcluded, SecondaryVoltageControl.NAME);
        }

        @Override
        public VersionInfo getVersionInfo() {
            return versionInfo;
        }
    }

    private static final String CONTROL_ZONE_ROOT_ELEMENT = "controlZone";
    private static final String CONTROL_ZONE_ARRAY_ELEMENT = "controlZones";
    private static final String PILOT_POINT_ELEMENT = "pilotPoint";
    private static final String BUSBAR_SECTION_OR_BUS_ID_ROOT_ELEMENT = "busbarSectionOrBusId";
    private static final String BUSBAR_SECTION_OR_BUS_ID_ARRAY_ELEMENT = "busbarSectionOrBusIds";
    private static final String CONTROL_UNIT_ROOT_ELEMENT = "controlUnit";
    private static final String CONTROL_UNIT_ARRAY_ELEMENT = "controlUnits";

    private static final String PARTICIPATION_FACTOR = "participationFactor";

    public SecondaryVoltageControlSerDe() {
        super(SecondaryVoltageControl.NAME, SecondaryVoltageControl.class, Version.values());
    }

    @Override
    public Map<String, String> getArrayNameToSingleNameMap() {
        return Map.of(CONTROL_ZONE_ARRAY_ELEMENT, CONTROL_ZONE_ROOT_ELEMENT,
                CONTROL_UNIT_ARRAY_ELEMENT, CONTROL_UNIT_ROOT_ELEMENT,
                BUSBAR_SECTION_OR_BUS_ID_ARRAY_ELEMENT, BUSBAR_SECTION_OR_BUS_ID_ROOT_ELEMENT);
    }

    @Override
    public void write(SecondaryVoltageControl control, SerializerContext context) {
        TreeDataWriter writer = context.getWriter();
        Version extVersion = getExtensionVersionToExport(context);
        String namespaceUri = getNamespaceUri(extVersion.getVersionString());
        writer.writeStartNodes();
        for (ControlZone controlZone : control.getControlZones()) {
            writer.writeStartNode(namespaceUri, CONTROL_ZONE_ROOT_ELEMENT);
            writer.writeStringAttribute("name", controlZone.getName());
            writePilotPoint(controlZone, writer, namespaceUri);
            writeControlUnits(controlZone.getControlUnits(), writer, namespaceUri, extVersion);
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    private void writePilotPoint(ControlZone controlZone, TreeDataWriter writer, String namespaceUri) {
        writer.writeStartNode(namespaceUri, PILOT_POINT_ELEMENT);
        writer.writeDoubleAttribute("targetV", controlZone.getPilotPoint().getTargetV());
        writer.writeStartNodes();
        for (String busbarSectionOrBusId : controlZone.getPilotPoint().getBusbarSectionsOrBusesIds()) {
            writer.writeStartNode(namespaceUri, BUSBAR_SECTION_OR_BUS_ID_ROOT_ELEMENT);
            writer.writeNodeContent(busbarSectionOrBusId);
            writer.writeEndNode();
        }
        writer.writeEndNodes();
        writer.writeEndNode();
    }

    private void writeControlUnits(List<ControlUnit> controlUnits, TreeDataWriter writer, String namespaceUri, Version extVersion) {
        writer.writeStartNodes();
        for (ControlUnit controlUnit : controlUnits) {
            writer.writeStartNode(namespaceUri, CONTROL_UNIT_ROOT_ELEMENT);
            writer.writeBooleanAttribute("participate", controlUnit.isParticipate());
            if (extVersion.isGreaterThan(Version.V_1_0)) {
                writer.writeDoubleAttribute(PARTICIPATION_FACTOR, controlUnit.getParticipationFactor(), Double.NaN);
            }
            writer.writeNodeContent(controlUnit.getId());
            writer.writeEndNode();
        }
        writer.writeEndNodes();
    }

    @Override
    public SecondaryVoltageControl read(Network network, DeserializerContext context) {
        SecondaryVoltageControlAdder adder = network.newExtension(SecondaryVoltageControlAdder.class);
        context.getReader().readChildNodes(elementName -> {
            if (!elementName.equals(CONTROL_ZONE_ROOT_ELEMENT)) {
                throw new PowsyblException(getExceptionMessageUnknownElement(elementName, "secondaryVoltageControl"));
            }
            readControlZone(context, adder, getExtensionVersionImported(context));
        });
        return adder.add();
    }

    private static void readControlZone(DeserializerContext context, SecondaryVoltageControlAdder adder, Version extVersion) {
        String name = context.getReader().readStringAttribute("name");
        ControlZoneAdder controlZoneAdder = adder.newControlZone()
                .withName(name);
        context.getReader().readChildNodes(elementName -> {
            switch (elementName) {
                case PILOT_POINT_ELEMENT -> readPilotPoint(context, controlZoneAdder);
                case CONTROL_UNIT_ROOT_ELEMENT -> readControlUnit(context, controlZoneAdder, extVersion);
                default -> throw new PowsyblException(getExceptionMessageUnknownElement(elementName, "'controlZone'"));
            }
        });
        controlZoneAdder.add();
    }

    private static void readPilotPoint(DeserializerContext context, ControlZoneAdder controlZoneAdder) {
        double targetV = context.getReader().readDoubleAttribute("targetV");
        List<String> busbarSectionsOrBusesIds = new ArrayList<>();
        context.getReader().readChildNodes(elementName -> {
            if (!elementName.equals(BUSBAR_SECTION_OR_BUS_ID_ROOT_ELEMENT)) {
                throw new PowsyblException(getExceptionMessageUnknownElement(elementName, PILOT_POINT_ELEMENT));
            }
            busbarSectionsOrBusesIds.add(context.getReader().readContent());
        });
        controlZoneAdder.newPilotPoint()
                .withBusbarSectionsOrBusesIds(busbarSectionsOrBusesIds)
                .withTargetV(targetV)
                .add();
    }

    private static void readControlUnit(DeserializerContext context, ControlZoneAdder controlZoneAdder, Version extVersion) {
        boolean participate = context.getReader().readBooleanAttribute("participate");
        double participationFactor = extVersion.isGreaterThan(Version.V_1_0)
                ? context.getReader().readDoubleAttribute(PARTICIPATION_FACTOR, Double.NaN)
                : Double.NaN;
        String id = context.getReader().readContent();
        controlZoneAdder.newControlUnit()
                .withId(id)
                .withParticipate(participate)
                .withParticipationFactor(participationFactor)
                .add();
    }

    private static String getExceptionMessageUnknownElement(String elementName, String where) {
        return "Unknown element name '" + elementName + "' in '" + where + "'";
    }
}

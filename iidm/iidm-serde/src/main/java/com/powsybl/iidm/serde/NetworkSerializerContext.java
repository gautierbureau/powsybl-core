/**
 * Copyright (c) 2016, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde;

import com.powsybl.commons.io.SerializerContext;
import com.powsybl.commons.io.TreeDataWriter;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.TopologyLevel;
import com.powsybl.iidm.serde.anonymizer.Anonymizer;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class NetworkSerializerContext extends AbstractNetworkSerDeContext<ExportOptions> implements SerializerContext {

    private final TreeDataWriter writer;
    private final ExportOptions options;
    private final BusFilter filter;
    private final boolean valid;
    // true when the network being written has no subnetwork: every identifiable is then written inside the
    // root network, so the per-element "written inside network" test can be short-circuited (common case).
    private final boolean noSubnetworks;
    private final Set<Identifiable> exportedEquipments;
    private final Map<String, TopologyLevel> voltageLevelExportTopologyLevel;

    NetworkSerializerContext(Anonymizer anonymizer, TreeDataWriter writer, ExportOptions options, BusFilter filter, IidmVersion version, boolean valid, boolean noSubnetworks) {
        super(anonymizer, version);
        this.writer = Objects.requireNonNull(writer);
        this.options = options;
        this.filter = filter;
        this.valid = valid;
        this.noSubnetworks = noSubnetworks;
        this.exportedEquipments = new HashSet<>();
        this.voltageLevelExportTopologyLevel = new HashMap<>();
    }

    public boolean hasNoSubnetworks() {
        return noSubnetworks;
    }

    @Override
    public TreeDataWriter getWriter() {
        return writer;
    }

    @Override
    public ExportOptions getOptions() {
        return options;
    }

    public BusFilter getFilter() {
        return filter;
    }

    public boolean isValid() {
        return valid;
    }

    public Set<Identifiable> getExportedEquipments() {
        return Collections.unmodifiableSet(exportedEquipments);
    }

    public void addExportedEquipment(Identifiable<?> equipment) {
        exportedEquipments.add(equipment);
    }

    public boolean isExportedEquipment(Identifiable<?> equipment) {
        return exportedEquipments.contains(equipment);
    }

    public Optional<String> getExtensionVersion(String extensionName) {
        return options.getExtensionVersion(extensionName);
    }

    public String getNamespaceURI() {
        return getVersion().getNamespaceURI(valid);
    }

    public void addVoltageLevelExportTopologyLevel(String voltageLevelId, TopologyLevel topologyLevel) {
        if (!voltageLevelId.isEmpty()) {
            voltageLevelExportTopologyLevel.put(voltageLevelId, topologyLevel);
        }
    }

    public TopologyLevel getVoltageLevelExportTopologyLevel(String voltageLevelId) {
        return voltageLevelExportTopologyLevel.get(voltageLevelId);
    }

    public String anonymizeFromMinimumVersion(String val, IidmVersion version) {
        return getVersion().compareTo(version) >= 0 ? getAnonymizer().anonymizeString(val) : val;
    }

}

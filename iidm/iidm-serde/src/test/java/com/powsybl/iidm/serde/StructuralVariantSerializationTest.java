/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Serialization: {@code NetworkSerDe} writes a structural variant's <b>resolved</b> view. In the
 * single-network variant model every object's parent is the one network, so writing while a structural
 * variant is the working variant serializes exactly what that variant resolves — base − tombstoned +
 * added — with no special flatten step.
 *
 * @author Claude
 */
class StructuralVariantSerializationTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("grid", "example");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        line(n, "L", "VLA", "VLB");
        line(n, "M", "VLB", "VLC");
        return n;
    }

    private static void line(Network n, String id, String vl1, String vl2) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1("bus_" + vl1).setBus1("bus_" + vl1)
                .setVoltageLevel2(vl2).setConnectableBus2("bus_" + vl2).setBus2("bus_" + vl2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static List<String> lineIds(VoltageLevel vl) {
        return vl.getConnectableStream(Line.class).map(Line::getId).sorted().toList();
    }

    @Test
    void serializingAStructuralVariantWritesItsResolvedView() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        // split L on a structural "fault" variant, entirely through the public API
        vm.cloneVariant(INITIAL, "fault", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("fault");
        double r = n.getLine("L").getR();
        double x = n.getLine("L").getX();
        n.getLine("L").remove();
        Substation sf = n.newSubstation().setId("SF").setFictitious(true).add();
        sf.newVoltageLevel().setId("Vf").setNominalV(400).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add().getBusBreakerView().newBus().setId("busF").add();
        halfLine(n, "L1", "VLA", "bus_VLA", "Vf", "busF", r * 0.4, x * 0.4);
        halfLine(n, "L2", "Vf", "busF", "VLB", "bus_VLB", r * 0.6, x * 0.6);

        // --- serialize while "fault" is active: the round-tripped network is the split network ---
        Network faultCopy = NetworkSerDe.copy(n);
        assertNull(faultCopy.getLine("L"));
        assertNotNull(faultCopy.getLine("L1"));
        assertNotNull(faultCopy.getLine("L2"));
        assertNotNull(faultCopy.getVoltageLevel("Vf"));
        assertEquals(List.of("L1"), lineIds(faultCopy.getVoltageLevel("VLA")));
        assertEquals(List.of("L2", "M"), lineIds(faultCopy.getVoltageLevel("VLB")));
        // the copy is a plain network — no structural variant needed to read it back
        assertEquals(0.4, faultCopy.getLine("L1").getR(), 1e-9);

        // --- serialize while INITIAL is active: the round-tripped network is the intact base ---
        vm.setWorkingVariant(INITIAL);
        Network baseCopy = NetworkSerDe.copy(n);
        assertNotNull(baseCopy.getLine("L"));
        assertNull(baseCopy.getLine("L1"));
        assertNull(baseCopy.getVoltageLevel("Vf"));
        assertNull(baseCopy.getSubstation("SF"));
        assertEquals(List.of("L"), lineIds(baseCopy.getVoltageLevel("VLA")));
    }

    private static void halfLine(Network n, String id, String vl1, String bus1, String vl2, String bus2, double r, double x) {
        n.newLine().setId(id).setVoltageLevel1(vl1).setConnectableBus1(bus1).setBus1(bus1)
                .setVoltageLevel2(vl2).setConnectableBus2(bus2).setBus2(bus2)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }
}

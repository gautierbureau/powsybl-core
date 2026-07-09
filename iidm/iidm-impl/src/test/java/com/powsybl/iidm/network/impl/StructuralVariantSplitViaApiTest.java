/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

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
 * Phase 1 (container scoping): a <b>container-creating</b> structural change — a fault-on-line split that
 * adds a new fictitious voltage level — now works entirely through the <b>public API</b>. It is the
 * ordinary sequence a modification like {@code CreateVoltageLevelOnLine} performs
 * ({@code remove()} + {@code newSubstation()/newVoltageLevel()/newBus()} + {@code newLine()}), run while a
 * {@code STRUCTURAL} variant is the working one; existence-scoping of the new VL/bus/substation is handled
 * centrally in {@code NetworkIndex.checkAndAdd}. No internal helper.
 *
 * @author Claude
 */
class StructuralVariantSplitViaApiTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("grid", "example");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        line(n, "L", "VLA", "VLB");   // to split
        line(n, "M", "VLB", "VLC");   // through-line on the shared endpoint VLB
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
    void faultOnLineSplitThroughThePublicApi() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        VoltageLevel vla = n.getVoltageLevel("VLA");
        VoltageLevel vlb = n.getVoltageLevel("VLB");

        // ============================ ENTIRELY THE PUBLIC API ============================
        vm.cloneVariant(INITIAL, "fault", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("fault");

        // the ordinary split sequence, scoped to "fault":
        double r = n.getLine("L").getR();
        double x = n.getLine("L").getX();
        n.getLine("L").remove();                                    // structural removal
        Substation sf = n.newSubstation().setId("SF").setFictitious(true).add();
        sf.newVoltageLevel().setId("Vf").setNominalV(400).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add().getBusBreakerView().newBus().setId("busF").add();
        line2(n, "L1", "VLA", "bus_VLA", "Vf", "busF", r * 0.4, x * 0.4);
        line2(n, "L2", "Vf", "busF", "VLB", "bus_VLB", r * 0.6, x * 0.6);
        // ================================================================================

        // "fault" shows the split: L gone, L1/L2 present, Vf present, through-line M kept on VLB
        assertNull(n.getLine("L"));
        assertNotNull(n.getLine("L1"));
        assertNotNull(n.getLine("L2"));
        assertNotNull(n.getVoltageLevel("Vf"));
        assertEquals(List.of("L1"), lineIds(vla));
        assertEquals(List.of("L2", "M"), lineIds(vlb));
        assertEquals(0.4, n.getLine("L1").getR(), 1e-9);

        // the base variant is the untouched original network
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getLine("L"));
        assertNull(n.getLine("L1"));
        assertNull(n.getVoltageLevel("Vf"));
        assertNull(n.getSubstation("SF"));
        assertEquals(List.of("L"), lineIds(vla));
        assertEquals(List.of("L", "M"), lineIds(vlb));
    }

    private static void line2(Network n, String id, String vlA, String busA, String vlB, String busB, double r, double x) {
        n.newLine().setId(id).setVoltageLevel1(vlA).setConnectableBus1(busA).setBus1(busA)
                .setVoltageLevel2(vlB).setConnectableBus2(busB).setBus2(busB)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    @Test
    void faultOnLineSplitWithNodeBreakerEndpointsThroughThePublicApi() {
        // VLA[bbs@0, L@1] --L-- VLB[bbs@0, L@1] ; split L, half-lines attach at the node/breaker feeder node
        Network n = Network.create("grid", "example");
        for (String vl : new String[] {"VLA", "VLB"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            VoltageLevel v = s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.NODE_BREAKER).add();
            v.getNodeBreakerView().newBusbarSection().setId("bbs_" + vl).setNode(0).add();
            v.getNodeBreakerView().newDisconnector().setId("d_" + vl).setNode1(0).setNode2(1).add();
        }
        n.newLine().setId("L").setVoltageLevel1("VLA").setNode1(1).setVoltageLevel2("VLB").setNode2(1)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();

        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "fault", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("fault");

        n.getLine("L").remove();
        Substation sf = n.newSubstation().setId("SF").setFictitious(true).add();
        sf.newVoltageLevel().setId("Vf").setNominalV(400).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add().getBusBreakerView().newBus().setId("busF").add();
        n.newLine().setId("L1").setVoltageLevel1("VLA").setNode1(1).setVoltageLevel2("Vf").setConnectableBus2("busF").setBus2("busF")
                .setR(0.4).setX(4).setG1(0).setB1(0).setG2(0).setB2(0).add();
        n.newLine().setId("L2").setVoltageLevel1("Vf").setConnectableBus1("busF").setBus1("busF").setVoltageLevel2("VLB").setNode2(1)
                .setR(0.6).setX(6).setG1(0).setB1(0).setG2(0).setB2(0).add();

        assertNull(n.getLine("L"));
        assertEquals(List.of("L1"), lineIds(n.getVoltageLevel("VLA")));
        assertEquals(List.of("L2"), lineIds(n.getVoltageLevel("VLB")));

        vm.setWorkingVariant(INITIAL);
        assertEquals(List.of("L"), lineIds(n.getVoltageLevel("VLA")));
        assertNull(n.getVoltageLevel("Vf"));
    }
}

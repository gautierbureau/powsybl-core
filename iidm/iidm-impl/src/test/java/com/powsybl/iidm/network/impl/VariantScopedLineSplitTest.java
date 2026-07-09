/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Structural-variant spike <b>v2.1a</b> turnkey: a fault-on-line split as pure variant state on a
 * <b>single network</b>. See {@code structural-variant-generic-design.md}, the v2.1 section. After
 * {@code split(...)}, the whole split topology is visible simply by {@code setWorkingVariant("faulted")}
 * — no side-car index, no ambient context, no thread-local —
 * while the source variant keeps the original network.
 *
 * @author Claude
 */
class VariantScopedLineSplitTest {

    @Test
    void splitLivesEntirelyInAClonedVariant() {
        // VLA --L-- VLB --M-- VLC (bus/breaker); M is a through-line on the shared endpoint VLB.
        NetworkImpl n = (NetworkImpl) Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        newLine(n, "L", "VLA", "VLB");
        newLine(n, "M", "VLB", "VLC");

        VariantScopedLineSplit.split(n, "faulted", "L", 40.0, "SF", "Vf", "busF", "L1", "L2");

        VoltageLevel vla = n.getVoltageLevel("VLA");
        VoltageLevel vlb = n.getVoltageLevel("VLB");

        // --- the faulted variant shows the whole split, reached only by switching the working variant ---
        n.getVariantManager().setWorkingVariant("faulted");
        assertNull(n.getLine("L"));
        assertNotNull(n.getLine("L1"));
        assertNotNull(n.getLine("L2"));
        assertNotNull(n.getVoltageLevel("Vf"));
        assertEquals(3, n.getLineCount()); // L1, L2, and the shared through-line M
        assertEquals(0.4, n.getLine("L1").getR(), 1e-9);
        assertEquals(0.6, n.getLine("L2").getR(), 1e-9);
        // enumeration AND the bus view agree — L1 on VLA's bus, L2 and M on VLB's bus
        assertEquals(List.of("L1"), lineIds(vla));
        assertEquals(List.of("L1"), busViewLineIds(vla));
        assertEquals(List.of("L2", "M"), lineIds(vlb));
        assertEquals(List.of("L2", "M"), busViewLineIds(vlb));

        // --- the source variant, on the SAME network, is the untouched original ---
        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertNotNull(n.getLine("L"));
        assertNull(n.getLine("L1"));
        assertNull(n.getVoltageLevel("Vf"));
        assertEquals(2, n.getLineCount());
        assertEquals(List.of("L"), lineIds(vla));
        assertEquals(List.of("L"), busViewLineIds(vla));
        assertEquals(List.of("L", "M"), lineIds(vlb));
        assertEquals(List.of("L", "M"), busViewLineIds(vlb));
    }

    @Test
    void splitHandlesNodeBreakerEndpoints() {
        // VLA[bbs@0, L@1] --L-- VLB[bbs@0, L@1, M@2] --M-- VLC[bbs@0, M@1]
        NetworkImpl n = (NetworkImpl) Network.create("base", "test");
        nodeBreakerVl(n, "VLA", 1);
        nodeBreakerVl(n, "VLB", 2);
        nodeBreakerVl(n, "VLC", 1);
        nodeBreakerLine(n, "L", "VLA", 1, "VLB", 1);
        nodeBreakerLine(n, "M", "VLB", 2, "VLC", 1);

        VariantScopedLineSplit.split(n, "faulted", "L", 50.0, "SF", "Vf", "busF", "L1", "L2");

        VoltageLevel vla = n.getVoltageLevel("VLA");
        VoltageLevel vlb = n.getVoltageLevel("VLB");

        n.getVariantManager().setWorkingVariant("faulted");
        assertNull(n.getLine("L"));
        assertEquals(List.of("L1"), lineIds(vla));
        assertEquals(List.of("L1"), busViewLineIds(vla));
        assertEquals(List.of("L2", "M"), lineIds(vlb));
        assertEquals(List.of("L2", "M"), busViewLineIds(vlb));

        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertNotNull(n.getLine("L"));
        assertNull(n.getVoltageLevel("Vf"));
        assertEquals(List.of("L"), busViewLineIds(vla));
        assertEquals(List.of("L", "M"), busViewLineIds(vlb));
    }

    private static void newLine(Network n, String id, String vl1, String vl2) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1("bus_" + vl1).setBus1("bus_" + vl1)
                .setVoltageLevel2(vl2).setConnectableBus2("bus_" + vl2).setBus2("bus_" + vl2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static void nodeBreakerVl(Network n, String id, int feederCount) {
        Substation s = n.newSubstation().setId("S_" + id).add();
        VoltageLevel v = s.newVoltageLevel().setId(id).setNominalV(400).setTopologyKind(TopologyKind.NODE_BREAKER).add();
        v.getNodeBreakerView().newBusbarSection().setId("bbs_" + id).setNode(0).add();
        for (int feeder = 1; feeder <= feederCount; feeder++) {
            v.getNodeBreakerView().newDisconnector().setId("d_" + id + "_" + feeder).setNode1(0).setNode2(feeder).add();
        }
    }

    private static void nodeBreakerLine(Network n, String id, String vl1, int node1, String vl2, int node2) {
        n.newLine().setId(id).setVoltageLevel1(vl1).setNode1(node1).setVoltageLevel2(vl2).setNode2(node2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static List<String> lineIds(VoltageLevel vl) {
        return vl.getConnectableStream(Line.class).map(Line::getId).sorted().toList();
    }

    private static List<String> busViewLineIds(VoltageLevel vl) {
        return StreamSupport.stream(vl.getBusView().getBuses().spliterator(), false)
                .flatMap(Bus::getConnectedTerminalStream)
                .map(t -> t.getConnectable())
                .filter(Line.class::isInstance)
                .map(c -> c.getId())
                .sorted()
                .toList();
    }
}

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
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Structural-variant spike: flatten-on-write for serialization. A branch shares base objects by
 * reference, so {@code NetworkSerDe} drops them (it only writes elements whose parent network is the
 * one being written). {@link BranchFlattener} rebuilds the branch view into a plain, self-contained
 * network — every object owned by it, so it serializes fully.
 *
 * @author Claude
 */
class StructuralBranchFlattenTest {

    private static void line(Network n, String id, String vl1, String b1, String vl2, String b2) {
        n.newLine().setId(id).setVoltageLevel1(vl1).setConnectableBus1(b1).setBus1(b1)
                .setVoltageLevel2(vl2).setConnectableBus2(b2).setBus2(b2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    @Test
    void flattenProducesSelfContainedNetworkIncludingSharedObjects() {
        // VLA --L-- VLB --M-- VLC, with a load on VLA
        Network base = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = base.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        base.getVoltageLevel("VLA").newLoad().setId("LDA").setConnectableBus("bus_VLA").setBus("bus_VLA").setP0(1).setQ0(0).add();
        line(base, "L", "VLA", "bus_VLA", "VLB", "bus_VLB");
        line(base, "M", "VLB", "bus_VLB", "VLC", "bus_VLC");

        NetworkImpl branch = BranchLineSplit.split((NetworkImpl) base, "branch", "L", 50.0,
                "SF", "Vf", "busF", "L1", "L2");
        Network flat = BranchFlattener.flatten(branch);

        // the flat network is complete: the shared VLC and through-line M are present, the split applied
        assertEquals(4, flat.getVoltageLevelCount()); // VLA, VLB, VLC, Vf
        assertNotNull(flat.getVoltageLevel("VLC"));
        assertNotNull(flat.getLoad("LDA"));
        assertNotNull(flat.getLine("M"));
        assertNotNull(flat.getLine("L1"));
        assertNotNull(flat.getLine("L2"));
        assertNull(flat.getLine("L"));
        assertEquals(3, flat.getLineCount()); // M, L1, L2
        // the rebind was applied during flatten: M connects VLB -- VLC
        Line m = flat.getLine("M");
        assertEquals(Set.of("VLB", "VLC"),
                Set.of(m.getTerminal1().getVoltageLevel().getId(), m.getTerminal2().getVoltageLevel().getId()));

        // self-contained: every element's parent network is the flat network, so NetworkSerDe writes
        // them all (the branch itself would drop the shared ones)
        assertSame(flat, flat.getVoltageLevel("VLC").getParentNetwork());
        assertSame(flat, flat.getLine("M").getParentNetwork());
        assertSame(flat, flat.getLoad("LDA").getParentNetwork());

        // base untouched
        assertNotNull(base.getLine("L"));
        assertNull(base.getVoltageLevel("Vf"));
        assertEquals(2, base.getLineCount());
    }
}

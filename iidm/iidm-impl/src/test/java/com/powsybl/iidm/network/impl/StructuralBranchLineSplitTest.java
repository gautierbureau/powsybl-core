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
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Structural-variant spike, phase 2b: the write path, end to end. Split a line at a fictitious
 * mid-line voltage level <em>on a branch</em> and show the branch reflects the split while the shared
 * base is untouched — no full network copy.
 *
 * <p>The enabler is copy-on-write materialisation: the branch's own adders reconstruct the dirty
 * region (the endpoint voltage levels and the line) as branch-owned copies that shadow the base
 * (materialize mode). The split then runs against those branch-owned objects with the ordinary public
 * API — {@code line.remove()} and {@code newLine()} — so it hits the branch, not the base. This is the
 * same sequence of primitive operations the {@code ConnectVoltageLevelOnLine} modification performs;
 * that modification lives in a downstream module (iidm-modification), so it is mirrored here rather
 * than imported.</p>
 *
 * @author Claude
 */
class StructuralBranchLineSplitTest {

    private static Network buildBase() {
        // Minimal cascade-free case: VLA --L-- VLB, endpoints hosting only the line to split.
        Network n = Network.create("base", "test");
        Substation sa = n.newSubstation().setId("SA").add();
        VoltageLevel vla = sa.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vla.getBusBreakerView().newBus().setId("busA").add();
        Substation sb = n.newSubstation().setId("SB").add();
        VoltageLevel vlb = sb.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlb.getBusBreakerView().newBus().setId("busB").add();
        newLine(n, "L", "VLA", "busA", "VLB", "busB", 1.0, 10.0);
        return n;
    }

    private static void newLine(Network n, String id, String vl1, String bus1, String vl2, String bus2, double r, double x) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1(bus1).setBus1(bus1)
                .setVoltageLevel2(vl2).setConnectableBus2(bus2).setBus2(bus2)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    @Test
    void splitLineOnBranchLeavesBaseUntouched() {
        NetworkImpl base = (NetworkImpl) buildBase();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");
        OverlayNetworkIndex overlay = (OverlayNetworkIndex) branch.getIndex();

        // --- copy-on-write materialise the dirty region (endpoints + the line) into the branch ---
        overlay.beginMaterialize();
        Substation saB = branch.newSubstation().setId("SA").add();
        VoltageLevel vlaB = saB.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlaB.getBusBreakerView().newBus().setId("busA").add();
        Substation sbB = branch.newSubstation().setId("SB").add();
        VoltageLevel vlbB = sbB.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlbB.getBusBreakerView().newBus().setId("busB").add();
        newLine(branch, "L", "VLA", "busA", "VLB", "busB", 1.0, 10.0);
        overlay.endMaterialize();

        Line branchLine = branch.getLine("L");
        assertNotSame(base.getLine("L"), branchLine); // the branch has its own copy of the line

        // --- split L on the branch: fictitious mid VL + two half-lines, remove the original ---
        Substation sf = branch.newSubstation().setId("SF").setFictitious(true).add();
        VoltageLevel vf = sf.newVoltageLevel().setId("Vf").setNominalV(400).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vf.getBusBreakerView().newBus().setId("busF").add();

        branchLine.remove();
        newLine(branch, "L1", "VLA", "busA", "Vf", "busF", 0.5, 5.0);
        newLine(branch, "L2", "Vf", "busF", "VLB", "busB", 0.5, 5.0);

        // --- the branch reflects the split ---
        assertNull(branch.getLine("L"));
        assertNotNull(branch.getLine("L1"));
        assertNotNull(branch.getLine("L2"));
        assertNotNull(branch.getVoltageLevel("Vf"));
        assertEquals(2, branch.getLineCount());
        assertEquals("busF", branch.getLine("L1").getTerminal2().getBusBreakerView().getBus().getId());

        // --- the shared base is completely untouched: still VLA --L-- VLB ---
        assertNotNull(base.getLine("L"));
        assertNull(base.getLine("L1"));
        assertNull(base.getVoltageLevel("Vf"));
        assertEquals(1, base.getLineCount());
        assertEquals("busA", base.getLine("L").getTerminal1().getBusBreakerView().getBus().getId());
    }
}

/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Structural-variant spike <b>v2</b> (generic, don't-copy) de-risking prototype. See
 * {@code structural-variant-generic-design.md}. Proves the two new primitives — <em>branch-detached</em>
 * (hide a base terminal) and <em>branch-attach add</em> (add a branch-owned terminal onto a shared VL
 * without mutating its graph) — by splitting a line with <b>no materialisation</b>: the endpoint voltage
 * levels stay shared with the base (same object references), yet the branch view shows the split and the
 * base is untouched.
 *
 * <p>This is the v2 counterpart of {@link StructuralBranchLineSplitTest}: where v1 asserts the endpoint
 * VLs are branch-owned <em>copies</em> ({@code assertNotSame}), v2 asserts they are the <em>same shared
 * objects</em> ({@code assertSame}) — copy nothing.</p>
 *
 * @author Claude
 */
class StructuralBranchLineSplitV2Test {

    private static Network buildBase() {
        // VLA --L-- VLB --M-- VLC : L is the line to split; M is a through-line on the shared endpoint VLB.
        Network n = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        newLine(n, "L", "VLA", "bus_VLA", "VLB", "bus_VLB", 1.0, 10.0);
        newLine(n, "M", "VLB", "bus_VLB", "VLC", "bus_VLC", 2.0, 20.0);
        return n;
    }

    private static void newLine(Network n, String id, String vl1, String bus1, String vl2, String bus2, double r, double x) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1(bus1).setBus1(bus1)
                .setVoltageLevel2(vl2).setConnectableBus2(bus2).setBus2(bus2)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    @Test
    void splitWithoutMaterialisationKeepsEndpointVoltageLevelsShared() {
        NetworkImpl base = (NetworkImpl) buildBase();

        NetworkImpl branch = BranchLineSplitV2.split(base, "branch", "L", 40.0,
                "SF", "Vf", "busF", "L1", "L2");
        BranchContext context = branch.getBranchContext();

        VoltageLevel vlaBase = base.getVoltageLevel("VLA");
        VoltageLevel vlbBase = base.getVoltageLevel("VLB");
        VoltageLevel vlcBase = base.getVoltageLevel("VLC");

        // --- the endpoint VLs are NEVER copied: the branch sees the very same shared objects ---
        assertSame(vlaBase, branch.getVoltageLevel("VLA"));
        assertSame(vlbBase, branch.getVoltageLevel("VLB"));
        assertSame(vlcBase, branch.getVoltageLevel("VLC"));

        // --- under the branch context, the shared VLs show the split as a membership delta ---
        ThreadLocalBranchContext.run(context, () -> assertBranchView(branch, vlaBase, vlbBase, vlcBase));

        // --- the shared base is completely untouched, with or without a context active ---
        assertEquals(List.of("L"), BranchLineSplitV2.lineIdsInBranchView(vlaBase));
        assertEquals(List.of("L", "M"), BranchLineSplitV2.lineIdsInBranchView(vlbBase));
        assertNotNull(base.getLine("L"));
        assertNull(base.getLine("L1"));
        assertNull(base.getVoltageLevel("Vf"));
        assertEquals(2, base.getLineCount());
    }

    private static void assertBranchView(NetworkImpl branch, VoltageLevel vla, VoltageLevel vlb, VoltageLevel vlc) {
        assertNull(branch.getLine("L"));
        assertNotNull(branch.getLine("L1"));
        assertNotNull(branch.getLine("L2"));
        assertEquals(3, branch.getLineCount()); // L1, L2 and the shared through-line M (L is tombstoned)

        // VLA: L is hidden (detached), L1 shown (attached) — enumeration and bus view agree
        assertEquals(List.of("L1"), BranchLineSplitV2.lineIdsInBranchView(vla));
        assertEquals(List.of("L1"), busViewLineIds(vla));

        // VLB: L hidden, L2 shown, and the through-line M still on the shared VL with NO rebind
        assertEquals(List.of("L2", "M"), BranchLineSplitV2.lineIdsInBranchView(vlb));
        assertEquals(List.of("L2", "M"), busViewLineIds(vlb));

        // VLC: untouched, still hosts M
        assertEquals(List.of("M"), BranchLineSplitV2.lineIdsInBranchView(vlc));

        // the fictitious mid VL and its two half-lines are branch-owned, impedance split 40/60
        assertEquals(0.4, branch.getLine("L1").getR(), 1e-9);
        assertEquals(0.6, branch.getLine("L2").getR(), 1e-9);
        assertEquals("busF", branch.getLine("L1").getTerminal2().getBusBreakerView().getBus().getId());
    }

    /** The line ids a shared VL's bus view exposes in the current (branch or base) view, sorted. */
    private static List<String> busViewLineIds(VoltageLevel vl) {
        return StreamSupport.stream(vl.getBusView().getBuses().spliterator(), false)
                .flatMap(Bus::getConnectedTerminalStream)
                .map(t -> t.getConnectable())
                .filter(c -> c instanceof com.powsybl.iidm.network.Line)
                .map(Connectable::getId)
                .sorted()
                .toList();
    }
}

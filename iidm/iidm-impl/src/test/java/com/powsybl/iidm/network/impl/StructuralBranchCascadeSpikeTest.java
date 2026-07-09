/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural-variant spike — de-risking the materialisation cascade
 * (see {@code structural-variant-cascade-design.md}).
 *
 * <p>Topology {@code VLA --L-- VLB --M-- VLC}. Splitting {@code L} materialises {@code VLB}; the
 * through-line {@code M} is the case {@code BranchLineSplit} rejects today. This proves the recommended
 * fix on the smallest graph that exhibits the cascade: rebind only {@code M}'s <em>near</em> terminal
 * (the {@code VLB} side) onto the branch copy {@code VLB'}, leaving the far side at {@code VLC}. The
 * one load-bearing claim — the same shared {@code M} resolves to {@code VLB'} in the branch and
 * {@code VLB} in the base, with {@code VLC} never materialised — is validated.</p>
 *
 * <p>Both directions go through the public API: forward via {@code Terminal.getVoltageLevel()} (guarded
 * by the per-terminal override flag + the active {@link BranchContext}), reverse via
 * {@code VoltageLevel.getConnectables()} (guarded by the per-topology-model branch-attachment hint).
 * The same mechanism is exercised for bus/breaker and node/breaker voltage levels.</p>
 *
 * @author Claude
 */
class StructuralBranchCascadeSpikeTest {

    private static void newLine(Network n, String id, String vl1, String bus1, String vl2, String bus2) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1(bus1).setBus1(bus1)
                .setVoltageLevel2(vl2).setConnectableBus2(bus2).setBus2(bus2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static Network buildChain() {
        Network n = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        newLine(n, "L", "VLA", "bus_VLA", "VLB", "bus_VLB");
        newLine(n, "M", "VLB", "bus_VLB", "VLC", "bus_VLC");
        return n;
    }

    private static void nbLine(Network n, String id, String vl1, int node1, String vl2, int node2) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setNode1(node1)
                .setVoltageLevel2(vl2).setNode2(node2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static Network buildNodeBreakerChain() {
        // VLA --L-- VLB --M-- VLC, node/breaker; each VL a busbar at node 0, feeders via disconnectors.
        Network n = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            VoltageLevel v = s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.NODE_BREAKER).add();
            v.getNodeBreakerView().newBusbarSection().setId("bbs_" + vl).setNode(0).add();
        }
        VoltageLevel vlb = n.getVoltageLevel("VLB");
        n.getVoltageLevel("VLA").getNodeBreakerView().newDisconnector().setId("dLA").setNode1(0).setNode2(1).add();
        vlb.getNodeBreakerView().newDisconnector().setId("dLB").setNode1(0).setNode2(1).add();
        vlb.getNodeBreakerView().newDisconnector().setId("dMB").setNode1(0).setNode2(2).add();
        n.getVoltageLevel("VLC").getNodeBreakerView().newDisconnector().setId("dMC").setNode1(0).setNode2(1).add();
        nbLine(n, "L", "VLA", 1, "VLB", 1);
        nbLine(n, "M", "VLB", 2, "VLC", 1);
        return n;
    }

    private static TerminalExt terminalOn(Line line, String voltageLevelId) {
        Terminal t1 = line.getTerminal1();
        return (TerminalExt) (t1.getVoltageLevel().getId().equals(voltageLevelId) ? t1 : line.getTerminal2());
    }

    private static Set<String> connectableIds(Iterable<Connectable> connectables) {
        Set<String> ids = new HashSet<>();
        connectables.forEach(c -> ids.add(c.getId()));
        return ids;
    }

    @Test
    void rebindNearTerminalOfThroughLineWithoutCascadingToFarVoltageLevel() {
        NetworkImpl base = (NetworkImpl) buildChain();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");
        OverlayNetworkIndex overlay = (OverlayNetworkIndex) branch.getIndex();

        // materialise the split voltage level VLB -> VLB' (branch-owned copy of its bus)
        overlay.beginMaterialize();
        Substation sbB = branch.newSubstation().setId("S_VLB").add();
        VoltageLevelExt vlbBranch = (VoltageLevelExt) sbB.newVoltageLevel().setId("VLB").setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlbBranch.getBusBreakerView().newBus().setId("bus_VLB").add();
        overlay.endMaterialize();

        // rebind M's near terminal (the VLB side) onto VLB' for this branch only
        Line m = base.getLine("M");
        TerminalExt nearM = terminalOn(m, "VLB");
        BranchContext context = new BranchContext();
        context.rebind(nearM, vlbBranch);

        // --- FORWARD: under the branch context, M's near terminal resolves to VLB' ---
        ThreadLocalBranchContext.run(context, () ->
                assertSame(vlbBranch, nearM.getVoltageLevel(), "in the branch, M's near terminal is at VLB'"));

        // --- FORWARD: outside the context (base view), it resolves to the base VLB ---
        assertSame(base.getVoltageLevel("VLB"), nearM.getVoltageLevel(), "in the base, M's near terminal is at VLB");

        // --- REVERSE (near), through the public API: under the branch context VLB'.getConnectables()
        //     unions in the rebound M; outside the context it does not ---
        ThreadLocalBranchContext.run(context, () ->
                assertTrue(connectableIds(vlbBranch.getConnectables()).contains("M"), "VLB' hosts M in the branch"));
        assertFalse(connectableIds(vlbBranch.getConnectables()).contains("M"),
                "outside the branch context VLB' does not claim M");

        // --- NO CASCADE: VLC is never materialised and still enumerates M unchanged ---
        assertSame(base.getVoltageLevel("VLC"), branch.getVoltageLevel("VLC"), "VLC is shared, not materialised");
        VoltageLevel vlc = base.getVoltageLevel("VLC");
        assertTrue(connectableIds(vlc.getConnectables()).contains("M"), "VLC still hosts M");

        // --- BASE INTACT: M still connects VLB -- VLC, VLB still hosts M ---
        assertEquals(Set.of("VLB", "VLC"),
                Set.of(m.getTerminal1().getVoltageLevel().getId(), m.getTerminal2().getVoltageLevel().getId()));
        assertTrue(connectableIds(base.getVoltageLevel("VLB").getConnectables()).contains("M"));
    }

    @Test
    void rebindWorksForNodeBreakerVoltageLevelsToo() {
        // The forward hook is terminal-type-agnostic and the reverse union lives in
        // AbstractTopologyModel, so the same branch-scoped attachment works for node/breaker VLs.
        NetworkImpl base = (NetworkImpl) buildNodeBreakerChain();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");
        OverlayNetworkIndex overlay = (OverlayNetworkIndex) branch.getIndex();

        // materialise VLB -> VLB' (a branch-owned node/breaker VL; a busbar suffices for this proof)
        overlay.beginMaterialize();
        Substation sbB = branch.newSubstation().setId("S_VLB").add();
        VoltageLevelExt vlbBranch = (VoltageLevelExt) sbB.newVoltageLevel().setId("VLB").setNominalV(400)
                .setTopologyKind(TopologyKind.NODE_BREAKER).add();
        vlbBranch.getNodeBreakerView().newBusbarSection().setId("bbs_VLB").setNode(0).add();
        overlay.endMaterialize();

        Line m = base.getLine("M");
        TerminalExt nearM = terminalOn(m, "VLB");
        BranchContext context = new BranchContext();
        context.rebind(nearM, vlbBranch);

        // forward: same shared M, two views
        ThreadLocalBranchContext.run(context, () -> assertSame(vlbBranch, nearM.getVoltageLevel()));
        assertSame(base.getVoltageLevel("VLB"), nearM.getVoltageLevel());

        // reverse through the public API: VLB' hosts M under the context, not outside
        ThreadLocalBranchContext.run(context, () ->
                assertTrue(connectableIds(vlbBranch.getConnectables()).contains("M")));
        assertFalse(connectableIds(vlbBranch.getConnectables()).contains("M"));

        // no cascade: VLC shared, never materialised, still hosts M
        assertSame(base.getVoltageLevel("VLC"), branch.getVoltageLevel("VLC"));
        assertTrue(connectableIds(base.getVoltageLevel("VLC").getConnectables()).contains("M"));

        // base intact
        assertEquals(Set.of("VLB", "VLC"),
                Set.of(m.getTerminal1().getVoltageLevel().getId(), m.getTerminal2().getVoltageLevel().getId()));
        assertTrue(connectableIds(base.getVoltageLevel("VLB").getConnectables()).contains("M"));
    }
}

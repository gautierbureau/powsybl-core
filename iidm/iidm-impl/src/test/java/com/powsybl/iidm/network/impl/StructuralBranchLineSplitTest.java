/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.EnergySource;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.MinMaxReactiveLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void splitLineApiReHomesEndpointInjectionAndLeavesBaseUntouched() {
        // VLA hosts a load in addition to the line; the load must be re-homed into the branch copy.
        Network base = Network.create("base", "test");
        Substation sa = base.newSubstation().setId("SA").add();
        VoltageLevel vla = sa.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vla.getBusBreakerView().newBus().setId("busA").add();
        vla.newLoad().setId("LD").setConnectableBus("busA").setBus("busA").setP0(10).setQ0(5).add();
        Substation sb = base.newSubstation().setId("SB").add();
        VoltageLevel vlb = sb.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlb.getBusBreakerView().newBus().setId("busB").add();
        newLine(base, "L", "VLA", "busA", "VLB", "busB", 1.0, 10.0);

        NetworkImpl branch = BranchLineSplit.split((NetworkImpl) base, "branch", "L", 40.0,
                "SF", "Vf", "busF", "L1", "L2");

        // branch: split applied, endpoint load preserved, impedance split 40/60
        assertNull(branch.getLine("L"));
        assertNotNull(branch.getLine("L1"));
        assertNotNull(branch.getLine("L2"));
        assertNotNull(branch.getVoltageLevel("Vf"));
        assertEquals(2, branch.getLineCount());
        assertNotNull(branch.getLoad("LD"));
        assertEquals("busA", branch.getLoad("LD").getTerminal().getBusBreakerView().getConnectableBus().getId());
        assertEquals(0.4, branch.getLine("L1").getR(), 1e-9);
        assertEquals(0.6, branch.getLine("L2").getR(), 1e-9);

        // base untouched: still VLA --L-- VLB, load intact
        assertNotNull(base.getLine("L"));
        assertNull(base.getVoltageLevel("Vf"));
        assertEquals(1, base.getLineCount());
        assertNotNull(base.getLoad("LD"));
        assertNotSame(base.getLoad("LD"), branch.getLoad("LD"));
    }

    @Test
    void splitLineApiReHomesEndpointGenerator() {
        // A generator at an endpoint: the fault-current source for a short-circuit study must survive
        // materialisation into the branch.
        Network base = Network.create("base", "test");
        Substation sa = base.newSubstation().setId("SA").add();
        VoltageLevel vla = sa.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vla.getBusBreakerView().newBus().setId("busA").add();
        Generator g = vla.newGenerator().setId("G").setConnectableBus("busA").setBus("busA")
                .setMinP(0).setMaxP(100).setTargetP(50).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.HYDRO).add();
        g.newMinMaxReactiveLimits().setMinQ(-50).setMaxQ(50).add();
        Substation sb = base.newSubstation().setId("SB").add();
        VoltageLevel vlb = sb.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlb.getBusBreakerView().newBus().setId("busB").add();
        newLine(base, "L", "VLA", "busA", "VLB", "busB", 1.0, 10.0);

        NetworkImpl branch = BranchLineSplit.split((NetworkImpl) base, "branch", "L", 50.0,
                "SF", "Vf", "busF", "L1", "L2");

        Generator gB = branch.getGenerator("G");
        assertNotNull(gB);
        assertNotSame(base.getGenerator("G"), gB);
        assertEquals(50.0, gB.getTargetP(), 1e-9);
        assertEquals(400.0, gB.getTargetV(), 1e-9);
        assertEquals(EnergySource.HYDRO, gB.getEnergySource());
        MinMaxReactiveLimits limits = assertInstanceOf(MinMaxReactiveLimits.class, gB.getReactiveLimits());
        assertEquals(-50.0, limits.getMinQ(), 1e-9);
        assertEquals(50.0, limits.getMaxQ(), 1e-9);
        assertNull(branch.getLine("L"));
        assertEquals(2, branch.getLineCount());

        // base untouched
        assertNotNull(base.getLine("L"));
        assertNotNull(base.getGenerator("G"));
        assertNull(base.getVoltageLevel("Vf"));
    }

    @Test
    void splitLineApiReHomesThroughConnectableWithoutCascade() {
        // A three-VL chain: VLA --L-- VLB --M-- VLC. Splitting L materialises VLB, which also hosts the
        // through-line M. M is not recreated: its near terminal is rebound onto VLB' (no cascade into
        // VLC). The branch view is correct under the branch context.
        Network base = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = base.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        newLine(base, "L", "VLA", "bus_VLA", "VLB", "bus_VLB", 1.0, 10.0);
        newLine(base, "M", "VLB", "bus_VLB", "VLC", "bus_VLC", 1.0, 10.0);

        NetworkImpl branch = BranchLineSplit.split((NetworkImpl) base, "branch", "L", 50.0,
                "SF", "Vf", "busF", "L1", "L2");
        BranchContext context = branch.getBranchContext();

        Line m = branch.getLine("M");
        TerminalExt nearM = (TerminalExt) (m.getTerminal1().getVoltageLevel().getId().equals("VLB")
                ? m.getTerminal1() : m.getTerminal2());
        VoltageLevel vlbBranch = branch.getVoltageLevel("VLB");

        // under the branch context: split applied and M rebound onto VLB'
        ThreadLocalBranchContext.run(context, () -> {
            assertNull(branch.getLine("L"));
            assertNotNull(branch.getLine("L1"));
            assertNotNull(branch.getLine("L2"));
            assertSame(vlbBranch, nearM.getVoltageLevel());
            Set<String> vlbIds = new HashSet<>();
            vlbBranch.getConnectables().forEach(c -> vlbIds.add(c.getId()));
            assertTrue(vlbIds.contains("M"), "VLB' hosts the rebound through-line M");
        });

        // no cascade: VLC is the shared base instance, still hosts M
        assertSame(((NetworkImpl) base).getVoltageLevel("VLC"), branch.getVoltageLevel("VLC"));
        Set<String> vlcIds = new HashSet<>();
        base.getVoltageLevel("VLC").getConnectables().forEach(c -> vlcIds.add(c.getId()));
        assertTrue(vlcIds.contains("M"));

        // base intact: L and M both present, no fictitious VL
        assertNotNull(base.getLine("L"));
        assertNotNull(base.getLine("M"));
        assertNull(base.getVoltageLevel("Vf"));
        assertEquals(2, base.getLineCount());
    }

    @Test
    void splitLineApiHandlesNodeBreakerEndpoints() {
        // Node/breaker endpoints: VLA --L-- VLB, each a busbar (node 0) with the line feeder at node 1
        // via a disconnector. The split materialises the node/breaker graphs and attaches the half-lines
        // at the freed feeder nodes.
        Network base = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB"}) {
            Substation s = base.newSubstation().setId("S_" + vl).add();
            VoltageLevel v = s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.NODE_BREAKER).add();
            v.getNodeBreakerView().newBusbarSection().setId("bbs_" + vl).setNode(0).add();
            v.getNodeBreakerView().newDisconnector().setId("d_" + vl).setNode1(0).setNode2(1).add();
        }
        base.newLine().setId("L").setVoltageLevel1("VLA").setNode1(1).setVoltageLevel2("VLB").setNode2(1)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();

        NetworkImpl branch = BranchLineSplit.split((NetworkImpl) base, "branch", "L", 50.0,
                "SF", "Vf", "busF", "L1", "L2");

        // the branch reflects the split, endpoints materialised as node/breaker copies
        assertNull(branch.getLine("L"));
        assertNotNull(branch.getLine("L1"));
        assertNotNull(branch.getLine("L2"));
        assertEquals(TopologyKind.NODE_BREAKER, branch.getVoltageLevel("VLA").getTopologyKind());
        assertNotNull(branch.getVoltageLevel("VLA").getNodeBreakerView().getBusbarSection("bbs_VLA"));
        assertEquals(2, branch.getLineCount());

        // base untouched
        assertNotNull(base.getLine("L"));
        assertNull(base.getVoltageLevel("Vf"));
        assertEquals(1, base.getLineCount());
        assertNotSame(base.getVoltageLevel("VLA"), branch.getVoltageLevel("VLA"));
    }
}

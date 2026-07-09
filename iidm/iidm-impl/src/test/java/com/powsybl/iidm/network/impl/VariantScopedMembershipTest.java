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

/**
 * Structural-variant spike <b>v2.1a</b> step 1: variant-scoped terminal <b>membership</b>. See
 * {@code structural-variant-generic-design.md}, the v2.1 section.
 *
 * <p>The membership counterpart of {@link VariantScopedExistenceTest}: a voltage level's terminal set
 * (what {@code getConnectables} enumerates) becomes a function of the <b>working variant</b>, with
 * <b>no</b> ambient context and <b>no</b> thread-local — the same
 * enumeration folds v2 drove from an ambient context now read the active variant. And the membership
 * delta is cloned by the <em>real</em> {@code cloneVariant}, exactly as state is.</p>
 *
 * @author Claude
 */
class VariantScopedMembershipTest {

    private static NetworkImpl buildBase() {
        // VLA --L-- VLB --M-- VLC
        Network n = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            Substation s = n.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        newLine(n, "L", "VLA", "VLB");
        newLine(n, "M", "VLB", "VLC");
        return (NetworkImpl) n;
    }

    private static void newLine(Network n, String id, String vl1, String vl2) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1("bus_" + vl1).setBus1("bus_" + vl1)
                .setVoltageLevel2(vl2).setConnectableBus2("bus_" + vl2).setBus2("bus_" + vl2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static List<String> lineIds(VoltageLevel vl) {
        return vl.getConnectableStream(Line.class).map(Line::getId).sorted().toList();
    }

    /** Line ids the VL's bus view exposes as connected in the current working variant, sorted. */
    private static List<String> busViewLineIds(VoltageLevel vl) {
        return StreamSupport.stream(vl.getBusView().getBuses().spliterator(), false)
                .flatMap(Bus::getConnectedTerminalStream)
                .map(t -> t.getConnectable())
                .filter(Line.class::isInstance)
                .map(c -> c.getId())
                .sorted()
                .toList();
    }

    @Test
    void membershipIsScopedToTheWorkingVariant() {
        NetworkImpl n = buildBase();
        VariantScopedMembership membership = n.enableVariantScopedMembership();
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted");

        VoltageLevelExt vla = (VoltageLevelExt) n.getVoltageLevel("VLA");
        VoltageLevelExt vlb = (VoltageLevelExt) n.getVoltageLevel("VLB");
        // M's terminal on VLB
        TerminalExt mOnVlb = (TerminalExt) n.getLine("M").getTerminal1();

        // in the faulted variant, move M's membership from VLB onto VLA — pure per-variant state
        n.getVariantManager().setWorkingVariant("faulted");
        membership.detachInCurrentVariant(vlb, mOnVlb);
        membership.attachInCurrentVariant(vla, mOnVlb);

        // faulted view: getConnectables reflects the moved membership (enumeration is bus-agnostic)...
        assertEquals(List.of("L", "M"), lineIds(vla));
        assertEquals(List.of("L"), lineIds(vlb));
        assertEquals(List.of("M"), lineIds(n.getVoltageLevel("VLC")));

        // ...while the initial variant on the SAME network is unchanged — no context needed either way
        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(List.of("L"), lineIds(vla));
        assertEquals(List.of("L", "M"), lineIds(vlb));
    }

    @Test
    void membershipDrivesTheBusView() {
        // The bus view reads variant-scoped membership too. The faithful, bus-agnostic direction is
        // detach: hide a line's own (correctly-bussed) terminal from its VL in one variant only. (The
        // attach direction needs a terminal bussed on the target VL — what the real split produces — and
        // is exercised end-to-end by the v2 split; here we prove the bus-view subtraction reads the
        // working variant.)
        NetworkImpl n = buildBase();
        VariantScopedMembership membership = n.enableVariantScopedMembership();
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted");

        VoltageLevelExt vla = (VoltageLevelExt) n.getVoltageLevel("VLA");
        VoltageLevelExt vlb = (VoltageLevelExt) n.getVoltageLevel("VLB");

        n.getVariantManager().setWorkingVariant("faulted");
        membership.detachInCurrentVariant(vla, (TerminalExt) n.getLine("L").getTerminal1());
        membership.detachInCurrentVariant(vlb, (TerminalExt) n.getLine("M").getTerminal1());

        // faulted: L gone from VLA's bus, M gone from VLB's bus
        assertEquals(List.of(), busViewLineIds(vla));
        assertEquals(List.of("L"), busViewLineIds(vlb));

        // initial variant untouched on the same network
        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(List.of("L"), busViewLineIds(vla));
        assertEquals(List.of("L", "M"), busViewLineIds(vlb));
    }

    @Test
    void membershipDrivesTheNodeBreakerBusView() {
        // Node/breaker calculated-bus view reads variant membership (detach direction, faithful).
        // VLA[bbs@0, L@1] --L-- VLB[bbs@0, L@1, M@2] --M-- VLC[bbs@0, M@1]
        NetworkImpl n = (NetworkImpl) Network.create("base", "test");
        nodeBreakerVl(n, "VLA", 1);
        nodeBreakerVl(n, "VLB", 2);
        nodeBreakerVl(n, "VLC", 1);
        nodeBreakerLine(n, "L", "VLA", 1, "VLB", 1);
        nodeBreakerLine(n, "M", "VLB", 2, "VLC", 1);
        VariantScopedMembership membership = n.enableVariantScopedMembership();
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted");

        VoltageLevelExt vlb = (VoltageLevelExt) n.getVoltageLevel("VLB");

        n.getVariantManager().setWorkingVariant("faulted");
        membership.detachInCurrentVariant(vlb, (TerminalExt) n.getLine("M").getTerminal1());
        assertEquals(List.of("L"), busViewLineIds(vlb));

        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(List.of("L", "M"), busViewLineIds(vlb));
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

    @Test
    void membershipIsClonedByTheRealVariantClone() {
        NetworkImpl n = buildBase();
        VariantScopedMembership membership = n.enableVariantScopedMembership();
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted");
        VoltageLevelExt vla = (VoltageLevelExt) n.getVoltageLevel("VLA");
        VoltageLevelExt vlb = (VoltageLevelExt) n.getVoltageLevel("VLB");
        TerminalExt mOnVlb = (TerminalExt) n.getLine("M").getTerminal1();

        n.getVariantManager().setWorkingVariant("faulted");
        membership.detachInCurrentVariant(vlb, mOnVlb);
        membership.attachInCurrentVariant(vla, mOnVlb);

        // clone the faulted variant: the real cloneVariant copies the membership maps, so the child
        // inherits the moved terminal
        n.getVariantManager().cloneVariant("faulted", "faulted2");
        n.getVariantManager().setWorkingVariant("faulted2");
        assertEquals(List.of("L", "M"), lineIds(vla));
        assertEquals(List.of("L"), lineIds(vlb));

        // a clone of the pristine initial variant does not inherit it
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "scenario");
        n.getVariantManager().setWorkingVariant("scenario");
        assertEquals(List.of("L", "M"), lineIds(vlb));
    }
}

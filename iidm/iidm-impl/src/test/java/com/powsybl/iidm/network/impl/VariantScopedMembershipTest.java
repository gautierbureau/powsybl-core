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
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Structural-variant spike <b>v2.1a</b> step 1: variant-scoped terminal <b>membership</b>. See
 * {@code structural-variant-generic-design.md}, the v2.1 section.
 *
 * <p>The membership counterpart of {@link VariantScopedExistenceTest}: a voltage level's terminal set
 * (what {@code getConnectables} enumerates) becomes a function of the <b>working variant</b>, with
 * <b>no</b> {@link BranchContext} and <b>no</b> {@code ThreadLocalBranchContext.run} — the same
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

        // faulted view: getConnectables reflects the moved membership...
        assertEquals(List.of("L", "M"), lineIds(vla));
        assertEquals(List.of("L"), lineIds(vlb));
        assertEquals(List.of("M"), lineIds(n.getVoltageLevel("VLC")));

        // ...while the initial variant on the SAME network is unchanged — no context needed either way
        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(List.of("L"), lineIds(vla));
        assertEquals(List.of("L", "M"), lineIds(vlb));
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

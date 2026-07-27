/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.EnergySource;
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
 * The structural-variant work is strictly additive: the pre-existing {@link VariantManager} API is
 * untouched and keeps its historical contract. A clone made with any of the old {@code cloneVariant}
 * overloads (no strategy argument) — or, equivalently, with the explicit
 * {@link VariantCloneStrategy#STATE_ONLY} — isolates per-variant <b>state</b> but shares the network
 * <b>structure</b> across all variants, so a structural edit made while such a variant is active is
 * network-wide. Nothing about that changed.
 *
 * @author Claude
 */
class StructuralVariantBackwardCompatibilityTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("grid", "example");
        Substation s1 = n.newSubstation().setId("SUB1").add();
        VoltageLevel vl1 = s1.newVoltageLevel().setId("VL1").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl1.getBusBreakerView().newBus().setId("b1").add();
        vl1.newGenerator().setId("GEN1").setConnectableBus("b1").setBus("b1")
                .setMinP(0).setMaxP(500).setTargetP(200).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.NUCLEAR).add();
        Substation s2 = n.newSubstation().setId("SUB2").add();
        VoltageLevel vl2 = s2.newVoltageLevel().setId("VL2").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl2.getBusBreakerView().newBus().setId("b2").add();
        n.newLine().setId("L").setVoltageLevel1("VL1").setConnectableBus1("b1").setBus1("b1")
                .setVoltageLevel2("VL2").setConnectableBus2("b2").setBus2("b2")
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
        return n;
    }

    /**
     * The old {@code cloneVariant} overloads (no strategy) behave exactly as before: state is isolated
     * per variant, structure is shared. This is the historical IIDM contract and it is unchanged.
     */
    @Test
    void oldCloneVariantApiKeepsIsolatedStateAndSharedStructure() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        // old single-target overload — no strategy argument
        vm.cloneVariant(INITIAL, "wc");
        // old multi-target overload — no strategy argument
        vm.cloneVariant(INITIAL, List.of("wc2"), false);

        vm.setWorkingVariant("wc");
        n.getGenerator("GEN1").setTargetP(150.0);   // STATE change: isolated to "wc"

        // state isolated exactly as before
        assertEquals(150.0, n.getGenerator("GEN1").getTargetP());
        vm.setWorkingVariant(INITIAL);
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
        vm.setWorkingVariant("wc2");
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());

        // STRUCTURE is shared: a removal under an old-style variant is network-wide (historical behaviour)
        vm.setWorkingVariant("wc");
        n.getLine("L").remove();
        assertNull(n.getLine("L"));                 // gone in "wc"
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getLine("L"));                 // ...and gone everywhere — structure is shared
        vm.setWorkingVariant("wc2");
        assertNull(n.getLine("L"));
    }

    /**
     * The explicit {@link VariantCloneStrategy#STATE_ONLY} is the historical behaviour spelled out: it is
     * identical to the old no-strategy overloads.
     */
    @Test
    void explicitStateOnlyIsIdenticalToTheOldApi() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "s", VariantCloneStrategy.STATE_ONLY);

        vm.setWorkingVariant("s");
        n.getGenerator("GEN1").setTargetP(150.0);
        n.getLine("L").remove();                    // structural edit under a STATE_ONLY variant...

        assertEquals(150.0, n.getGenerator("GEN1").getTargetP()); // state isolated
        vm.setWorkingVariant(INITIAL);
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP()); // state isolated
        assertNull(n.getLine("L"));                               // ...is still network-wide
    }

    /**
     * A network that only ever uses the old API never enables any variant-scoped structure machinery, so
     * it keeps running on the exact former code paths. Sanity-check that it behaves normally end to end.
     */
    @Test
    void networkUsingOnlyTheOldApiIsUnaffected() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, List.of("a", "b", "c"));
        assertEquals(4, vm.getVariantIds().size());

        vm.setWorkingVariant("b");
        n.getGenerator("GEN1").setTargetP(300.0);
        assertEquals(300.0, n.getGenerator("GEN1").getTargetP());
        assertNotNull(n.getLine("L"));

        vm.removeVariant("b");
        assertEquals(3, vm.getVariantIds().size());
        vm.setWorkingVariant(INITIAL);
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
        assertNotNull(n.getLine("L"));
    }
}

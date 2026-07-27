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
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * There is a single kind of variant: a variant is a branch. State <em>and</em> structure are scoped to the
 * variant they are written in, whichever variant that is — a clone, a clone of a clone, or the initial
 * variant itself. A variant never sees a change made in another variant, nor a change made in its source
 * after the clone.
 *
 * <p>This is the semantics network-store has always had (a partial variant resolves as its full variant's
 * contents minus what it tombstoned plus what it overrode), now matched by {@code iidm-impl}. Structural
 * edits used to be network-wide here; that divergence is what these tests pin down.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantStructureScopingTest {

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

    /** Per-variant state is isolated exactly as it always was — nothing about that changed. */
    @Test
    void stateStaysIsolatedPerVariant() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "wc");
        vm.cloneVariant(INITIAL, List.of("wc2"), false);

        vm.setWorkingVariant("wc");
        n.getGenerator("GEN1").setTargetP(150.0);

        assertEquals(150.0, n.getGenerator("GEN1").getTargetP());
        vm.setWorkingVariant(INITIAL);
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
        vm.setWorkingVariant("wc2");
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
    }

    /** A removal made in a variant stays in it. It used to be network-wide. */
    @Test
    void aRemovalIsScopedToTheVariantItWasMadeIn() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, List.of("wc", "wc2"), false);

        vm.setWorkingVariant("wc");
        n.getLine("L").remove();

        assertNull(n.getLine("L"));
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getLine("L"));
        vm.setWorkingVariant("wc2");
        assertNotNull(n.getLine("L"));
    }

    /** An add made in a variant stays in it. */
    @Test
    void anAddIsScopedToTheVariantItWasMadeIn() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "expansion");

        vm.setWorkingVariant("expansion");
        n.getVoltageLevel("VL1").newLoad().setId("LD").setConnectableBus("b1").setBus("b1").setP0(20).setQ0(10).add();
        assertNotNull(n.getLoad("LD"));

        vm.setWorkingVariant(INITIAL);
        assertNull(n.getLoad("LD"));
    }

    /**
     * A variant forked from another inherits its structure and keeps diverging locally. There is no second
     * kind of clone that would silently make its writes network-wide.
     */
    @Test
    void aForkOfAForkKeepsItsWritesLocal() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "fault");
        vm.setWorkingVariant("fault");
        n.getLine("L").remove();
        n.getGenerator("GEN1").setTargetP(150.0);

        vm.cloneVariant("fault", "fault-lowload");
        vm.setWorkingVariant("fault-lowload");
        assertNull(n.getLine("L"));                                 // structure inherited
        assertEquals(150.0, n.getGenerator("GEN1").getTargetP());   // state inherited
        n.getVoltageLevel("VL1").newLoad().setId("EXTRA").setConnectableBus("b1").setBus("b1").setP0(5).setQ0(1).add();

        vm.setWorkingVariant("fault");
        assertNull(n.getLoad("EXTRA"));       // the child's add did not leak into its source
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getLoad("EXTRA"));       // nor into the base
        assertNotNull(n.getLine("L"));
    }

    /**
     * The initial variant is the shared base — network-store's full variant. A structural edit made there
     * still reaches every variant that has not overridden that object, which is how {@code iidm-impl} has
     * always behaved and what a partial variant resolving against its full variant gives you. Only edits
     * made <em>in</em> a cloned variant are scoped to it.
     */
    @Test
    void anEditInTheInitialVariantIsSeenByVariantsThatDidNotOverrideIt() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "wc");

        vm.setWorkingVariant(INITIAL);
        n.getVoltageLevel("VL1").newLoad().setId("LATE").setConnectableBus("b1").setBus("b1").setP0(1).setQ0(1).add();
        n.getLine("L").remove();

        vm.setWorkingVariant("wc");
        assertNotNull(n.getLoad("LATE"));  // base add is visible
        assertNull(n.getLine("L"));        // base removal applies too
    }

    /** A variant's own override wins over a later base change to the same object. */
    @Test
    void aVariantOverrideWinsOverTheBase() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "wc");

        vm.setWorkingVariant("wc");
        n.getLine("L").remove();           // tombstoned in "wc" only

        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getLine("L"));
        vm.setWorkingVariant("wc");
        assertNull(n.getLine("L"));
    }
}

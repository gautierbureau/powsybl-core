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
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * <b>Worked examples of the proposed structural-variant public API</b> (see
 * {@code structural-variant-public-api.md}). Each example is annotated with the <b>public API call it
 * will become</b> once {@code cloneVariant(..., STRUCTURAL)} is wired into the ordinary add/remove/modify
 * paths (Phase 1); here it runs through the equivalent internal machinery so the behaviour is real and
 * asserted today. Three scenarios: expansion planning (add), short circuit (split), N-1 (remove) — all
 * on <b>one</b> network, reached by {@code setWorkingVariant}, base always untouched.
 *
 * @author Claude
 */
class StructuralVariantApiExampleTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    /** A tiny grid: SUB1/VL1[b1] (GEN1) --LINE12-- SUB2/VL2[b2]. */
    private static NetworkImpl smallGrid() {
        Network n = Network.create("grid", "example");
        Substation s1 = n.newSubstation().setId("SUB1").add();
        VoltageLevel vl1 = s1.newVoltageLevel().setId("VL1").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl1.getBusBreakerView().newBus().setId("b1").add();
        vl1.newGenerator().setId("GEN1").setConnectableBus("b1").setBus("b1")
                .setMinP(0).setMaxP(300).setTargetP(200).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.NUCLEAR).add();
        Substation s2 = n.newSubstation().setId("SUB2").add();
        VoltageLevel vl2 = s2.newVoltageLevel().setId("VL2").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl2.getBusBreakerView().newBus().setId("b2").add();
        n.newLine().setId("LINE12").setVoltageLevel1("VL1").setConnectableBus1("b1").setBus1("b1")
                .setVoltageLevel2("VL2").setConnectableBus2("b2").setBus2("b2")
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
        return (NetworkImpl) n;
    }

    @Test
    void example1ExpansionPlanningAddAUnit() {
        NetworkImpl n = smallGrid();
        VoltageLevelExt vl1 = (VoltageLevelExt) n.getVoltageLevel("VL1");

        // ============================ PROPOSED PUBLIC API ============================
        //   VariantManager vm = n.getVariantManager();
        //   vm.cloneVariant("InitialState", "expansion", VariantCloneStrategy.STRUCTURAL);
        //   vm.setWorkingVariant("expansion");
        //   vl1.newGenerator().setId("NEW_CCGT").setConnectableBus("b1").setBus("b1")
        //         .setMinP(0).setMaxP(400).setTargetP(350).setTargetV(400).setVoltageRegulatorOn(true)
        //         .setEnergySource(EnergySource.THERMAL).add();          // scoped to "expansion"
        // ============================================================================
        // (runs today through the equivalent internal helper)
        VariantScopedConnectableAdd.addInVariant(n, "expansion",
                () -> vl1.newGenerator().setId("NEW_CCGT").setConnectableBus("b1").setBus("b1")
                        .setMinP(0).setMaxP(400).setTargetP(350).setTargetV(400).setVoltageRegulatorOn(true)
                        .setEnergySource(EnergySource.THERMAL).add(),
                vl1);

        // in the "expansion" variant the new unit exists (a load flow here would see the expanded grid)
        n.getVariantManager().setWorkingVariant("expansion");
        assertNotNull(n.getGenerator("NEW_CCGT"));

        // the base scenario, on the SAME network, does not have it
        n.getVariantManager().setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("NEW_CCGT"));
    }

    @Test
    void example2ShortCircuitFaultOnLine() {
        NetworkImpl n = smallGrid();

        // ============================ PROPOSED PUBLIC API ============================
        //   vm.cloneVariant("InitialState", "fault", VariantCloneStrategy.STRUCTURAL);
        //   vm.setWorkingVariant("fault");
        //   // the ordinary iidm-modification, scoped to "fault":
        //   new CreateVoltageLevelOnLine(40, ..., "LINE12", "HALF_A", "HALF_B", "Vf", ...).apply(n);
        // ============================================================================
        VariantScopedLineSplit.split(n, "fault", "LINE12", 40.0, "SFx", "Vf", "busF", "HALF_A", "HALF_B");

        // the "fault" variant shows the line split at a mid-line fictitious voltage level
        n.getVariantManager().setWorkingVariant("fault");
        assertNull(n.getLine("LINE12"));
        assertNotNull(n.getLine("HALF_A"));
        assertNotNull(n.getLine("HALF_B"));
        assertNotNull(n.getVoltageLevel("Vf"));

        // the intact network is preserved in the base variant
        n.getVariantManager().setWorkingVariant(INITIAL);
        assertNotNull(n.getLine("LINE12"));
        assertNull(n.getVoltageLevel("Vf"));
    }

    @Test
    void example3ContingencyRemoveAUnit() {
        NetworkImpl n = smallGrid();
        VoltageLevelExt vl1 = (VoltageLevelExt) n.getVoltageLevel("VL1");
        VariantScopedExistence existence = n.enableVariantScopedExistence();
        VariantScopedMembership membership = n.enableVariantScopedMembership();
        TerminalExt gen1Terminal = (TerminalExt) n.getGenerator("GEN1").getTerminal();

        // ============================ PROPOSED PUBLIC API ============================
        //   vm.cloneVariant("InitialState", "n-1-gen1", VariantCloneStrategy.STRUCTURAL);
        //   vm.setWorkingVariant("n-1-gen1");
        //   n.getGenerator("GEN1").remove();                             // scoped to "n-1-gen1"
        // ============================================================================
        n.getVariantManager().cloneVariant(INITIAL, "n-1-gen1");
        n.getVariantManager().setWorkingVariant("n-1-gen1");
        existence.hideInCurrentVariant("GEN1");
        membership.detachInCurrentVariant(vl1, gen1Terminal);

        // GEN1 is out in the contingency variant (a structural removal, not just a disconnection)...
        assertNull(n.getGenerator("GEN1"));

        // ...and still present in the base scenario on the same network
        n.getVariantManager().setWorkingVariant(INITIAL);
        assertNotNull(n.getGenerator("GEN1"));
    }
}

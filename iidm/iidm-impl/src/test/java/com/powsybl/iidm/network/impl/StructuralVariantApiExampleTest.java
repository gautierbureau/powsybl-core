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
        VoltageLevel vl1 = n.getVoltageLevel("VL1");

        // ===== the real public API — a STRUCTURAL clone, then the ordinary adder, scoped automatically ==
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "expansion", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("expansion");
        vl1.newGenerator().setId("NEW_CCGT").setConnectableBus("b1").setBus("b1")
                .setMinP(0).setMaxP(400).setTargetP(350).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.THERMAL).add();
        // ================================================================================================

        // in the "expansion" variant the new unit exists (a load flow here would see the expanded grid)
        assertNotNull(n.getGenerator("NEW_CCGT"));

        // the base scenario, on the SAME network, does not have it
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("NEW_CCGT"));
    }

    @Test
    void example2ShortCircuitFaultOnLine() {
        Network n = smallGrid();

        // ===== the real public API — a STRUCTURAL clone, then the ordinary split sequence (a modification
        //       such as CreateVoltageLevelOnLine performs it): remove the line, add a fictitious mid VL,
        //       add the two half-lines — all scoped to "fault", including the new voltage level ===========
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "fault", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("fault");
        n.getLine("LINE12").remove();
        Substation sf = n.newSubstation().setId("SFx").setFictitious(true).add();
        sf.newVoltageLevel().setId("Vf").setNominalV(400).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add().getBusBreakerView().newBus().setId("busF").add();
        halfLine(n, "HALF_A", "VL1", "b1", "Vf", "busF");
        halfLine(n, "HALF_B", "Vf", "busF", "VL2", "b2");
        // ================================================================================================

        assertNull(n.getLine("LINE12"));
        assertNotNull(n.getLine("HALF_A"));
        assertNotNull(n.getLine("HALF_B"));
        assertNotNull(n.getVoltageLevel("Vf"));

        // the intact network is preserved in the base variant
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getLine("LINE12"));
        assertNull(n.getVoltageLevel("Vf"));
    }

    private static void halfLine(Network n, String id, String vl1, String bus1, String vl2, String bus2) {
        n.newLine().setId(id).setVoltageLevel1(vl1).setConnectableBus1(bus1).setBus1(bus1)
                .setVoltageLevel2(vl2).setConnectableBus2(bus2).setBus2(bus2)
                .setR(0.5).setX(5).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    @Test
    void example3ContingencyRemoveAUnit() {
        NetworkImpl n = smallGrid();

        // ===== the real public API — a STRUCTURAL clone, then the ordinary remove(), scoped automatically
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "n-1-gen1", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("n-1-gen1");
        n.getGenerator("GEN1").remove();
        // ================================================================================================

        // GEN1 is out in the contingency variant (a structural removal, not just a disconnection)...
        assertNull(n.getGenerator("GEN1"));

        // ...and still present in the base scenario on the same network
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getGenerator("GEN1"));
    }
}

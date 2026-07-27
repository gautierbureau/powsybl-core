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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A structural variant composes with ordinary per-variant <b>state</b>: you can set state inside a
 * structural variant, and you can fork further variants (structural or state-only) from it that inherit
 * its structure and carry their own state.
 *
 * @author Claude
 */
class StructuralVariantWithStateTest {

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

    @Test
    void stateWorksInsideAStructuralVariantAndInVariantsForkedFromIt() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        // structural variant: a structural change (line out) AND a state change (generator setpoint)
        vm.cloneVariant(INITIAL, "fault");
        vm.setWorkingVariant("fault");
        n.getLine("L").remove();                    // structural, scoped to "fault"
        n.getGenerator("GEN1").setTargetP(150.0);   // state, scoped to "fault"

        // fork a STATE-ONLY variant FROM the structural variant: it inherits the structure (line still
        // out) and the state (150), then diverges only the state
        vm.cloneVariant("fault", "fault-lowload");
        vm.setWorkingVariant("fault-lowload");
        assertNull(n.getLine("L"));                                 // structure inherited from "fault"
        assertEquals(150.0, n.getGenerator("GEN1").getTargetP());   // state inherited from "fault"
        n.getGenerator("GEN1").setTargetP(100.0);                   // diverge the state only
        assertEquals(100.0, n.getGenerator("GEN1").getTargetP());

        // "fault" keeps its own structure + state
        vm.setWorkingVariant("fault");
        assertNull(n.getLine("L"));
        assertEquals(150.0, n.getGenerator("GEN1").getTargetP());

        // base is the untouched original: line present, original setpoint
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getLine("L"));
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
    }
}

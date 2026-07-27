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
 * The public structural-variant API is <b>type-agnostic</b>: generators, loads — any connectable — scope
 * to a {@code STRUCTURAL} variant through their ordinary adders, with no per-type code (see

 *
 * @author Claude
 */
class StructuralVariantTypeAgnosticTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network gridWithBus() {
        Network n = Network.create("base", "test");
        Substation sa = n.newSubstation().setId("SA").add();
        VoltageLevel vla = sa.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vla.getBusBreakerView().newBus().setId("busA").add();
        Substation sb = n.newSubstation().setId("SB").add();
        VoltageLevel vlb = sb.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlb.getBusBreakerView().newBus().setId("busB").add();
        n.newLine().setId("L").setVoltageLevel1("VLA").setConnectableBus1("busA").setBus1("busA")
                .setVoltageLevel2("VLB").setConnectableBus2("busB").setBus2("busB")
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
        return n;
    }

    @Test
    void aGeneratorScopesToTheStructuralVariant() {
        Network n = gridWithBus();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "expansion");
        vm.setWorkingVariant("expansion");
        n.getVoltageLevel("VLA").newGenerator().setId("G").setConnectableBus("busA").setBus("busA")
                .setMinP(0).setMaxP(200).setTargetP(150).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.THERMAL).add();

        assertNotNull(n.getGenerator("G"));
        assertEquals(150.0, n.getGenerator("G").getTargetP(), 1e-9);
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("G"));
    }

    @Test
    void aLoadScopesToTheStructuralVariantViaTheSameApi() {
        Network n = gridWithBus();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "demand");
        vm.setWorkingVariant("demand");
        n.getVoltageLevel("VLA").newLoad().setId("LD").setConnectableBus("busA").setBus("busA")
                .setP0(20).setQ0(10).add();

        assertNotNull(n.getLoad("LD"));
        assertEquals(1, n.getVoltageLevel("VLA").getLoadStream().count());
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getLoad("LD"));
        assertEquals(0, n.getVoltageLevel("VLA").getLoadStream().count());
    }
}

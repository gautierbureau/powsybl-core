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
 * The public structural-variant API (see {@code structural-variant-public-api.md}): a {@code STRUCTURAL}
 * clone plus an ordinary {@code remove()} works end-to-end through the real API, with <b>no</b> internal
 * helper calls. Removing an object while a structural variant is the working variant scopes the removal to
 * that variant; the base and other variants keep it.
 *
 * @author Claude
 */
class StructuralVariantRemoveTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network smallGrid() {
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
        return n;
    }

    @Test
    void removeInAStructuralVariantIsScopedToIt() {
        Network n = smallGrid();
        VariantManager vm = n.getVariantManager();
        VoltageLevel vl1 = n.getVoltageLevel("VL1");

        // ---- the whole public API surface: a STRUCTURAL clone + an ordinary remove() ----
        vm.cloneVariant(INITIAL, "n-1-gen1", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("n-1-gen1");
        n.getGenerator("GEN1").remove();

        // in the contingency variant GEN1 is structurally gone — index, enumeration and bus view agree
        assertNull(n.getGenerator("GEN1"));
        assertEquals(0, n.getGeneratorCount());
        assertEquals(List.of(), vl1.getGeneratorStream().map(g -> g.getId()).toList());

        // the base variant, on the same network, keeps GEN1
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getGenerator("GEN1"));
        assertEquals(1, n.getGeneratorCount());
        assertEquals(List.of("GEN1"), vl1.getGeneratorStream().map(g -> g.getId()).toList());
    }

    @Test
    void removeInAStateOnlyVariantStaysNetworkWide() {
        // Backward compatibility: without STRUCTURAL, remove() is network-wide exactly as before.
        Network n = smallGrid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "state", VariantCloneStrategy.STATE_ONLY);
        vm.setWorkingVariant("state");
        n.getGenerator("GEN1").remove();

        assertNull(n.getGenerator("GEN1"));
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("GEN1")); // gone everywhere — historical behaviour preserved
    }

    @Test
    void addInAStructuralVariantIsScopedToIt() {
        Network n = smallGrid();
        VariantManager vm = n.getVariantManager();
        VoltageLevel vl1 = n.getVoltageLevel("VL1");

        // ---- the whole public API surface: a STRUCTURAL clone + an ordinary newGenerator().add() ----
        vm.cloneVariant(INITIAL, "expansion", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("expansion");
        vl1.newGenerator().setId("NEW_CCGT").setConnectableBus("b1").setBus("b1")
                .setMinP(0).setMaxP(400).setTargetP(350).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.THERMAL).add();

        // in the expansion variant the new unit exists, on VL1's bus alongside GEN1
        assertNotNull(n.getGenerator("NEW_CCGT"));
        assertEquals(2, n.getGeneratorCount());
        assertEquals(List.of("GEN1", "NEW_CCGT"), vl1.getGeneratorStream().map(g -> g.getId()).sorted().toList());

        // the base variant, on the same network, never sees it
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("NEW_CCGT"));
        assertEquals(1, n.getGeneratorCount());
        assertEquals(List.of("GEN1"), vl1.getGeneratorStream().map(g -> g.getId()).toList());
    }

    @Test
    void addAndRemoveComposeInOneStructuralVariant() {
        // A scenario that both retires a unit and commissions another — ordinary add + remove.
        Network n = smallGrid();
        VariantManager vm = n.getVariantManager();
        VoltageLevel vl1 = n.getVoltageLevel("VL1");

        vm.cloneVariant(INITIAL, "swap", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("swap");
        n.getGenerator("GEN1").remove();
        vl1.newGenerator().setId("REPLACEMENT").setConnectableBus("b1").setBus("b1")
                .setMinP(0).setMaxP(250).setTargetP(180).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.THERMAL).add();

        assertNull(n.getGenerator("GEN1"));
        assertNotNull(n.getGenerator("REPLACEMENT"));
        assertEquals(List.of("REPLACEMENT"), vl1.getGeneratorStream().map(g -> g.getId()).toList());

        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getGenerator("GEN1"));
        assertNull(n.getGenerator("REPLACEMENT"));
        assertEquals(List.of("GEN1"), vl1.getGeneratorStream().map(g -> g.getId()).toList());
    }

    @Test
    void twoIndependentContingenciesCoexistOnOneNetwork() {
        Network n = smallGrid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "n-1-gen1", VariantCloneStrategy.STRUCTURAL);
        vm.cloneVariant(INITIAL, "n-1-line12", VariantCloneStrategy.STRUCTURAL);

        vm.setWorkingVariant("n-1-gen1");
        n.getGenerator("GEN1").remove();
        vm.setWorkingVariant("n-1-line12");
        n.getLine("LINE12").remove();

        // each variant sees only its own outage; the other's object is intact
        vm.setWorkingVariant("n-1-gen1");
        assertNull(n.getGenerator("GEN1"));
        assertNotNull(n.getLine("LINE12"));
        vm.setWorkingVariant("n-1-line12");
        assertNotNull(n.getGenerator("GEN1"));
        assertNull(n.getLine("LINE12"));

        // base intact
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getGenerator("GEN1"));
        assertNotNull(n.getLine("LINE12"));
    }
}

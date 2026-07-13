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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A structural variant scopes existence per object, so an id may belong to <em>different</em> objects in
 * different variants: two variants can each add the same id independently (sibling collision), and an id
 * removed in a variant can be re-added there while the base keeps the original. The index resolves an id to
 * the single object visible in the active variant. (Outside a structural variant an id stays unique
 * network-wide, unchanged.)
 *
 * @author Claude
 */
class StructuralVariantSameIdTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("same-id-grid", "test");
        Substation s = n.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("b").add();
        addUnit(n, "BASE", 50);
        return n;
    }

    private static void addUnit(Network n, String id, double targetP) {
        n.getVoltageLevel("VL").newGenerator().setId(id).setConnectableBus("b").setBus("b")
                .setMinP(0).setMaxP(500).setTargetP(targetP).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.THERMAL).add();
    }

    @Test
    void siblingVariantsCanEachAddTheSameIdIndependently() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "A", VariantCloneStrategy.STRUCTURAL);
        vm.cloneVariant(INITIAL, "B", VariantCloneStrategy.STRUCTURAL);

        vm.setWorkingVariant("A");
        addUnit(n, "NEW", 10);
        vm.setWorkingVariant("B");
        addUnit(n, "NEW", 20); // same id, different object — must not throw (not visible in B)

        vm.setWorkingVariant("A");
        assertNotNull(n.getGenerator("NEW"));
        assertEquals(10, n.getGenerator("NEW").getTargetP());
        assertEquals(2, n.getVoltageLevel("VL").getGeneratorCount()); // BASE + A's NEW

        vm.setWorkingVariant("B");
        assertNotNull(n.getGenerator("NEW"));
        assertEquals(20, n.getGenerator("NEW").getTargetP()); // B sees its own NEW, not A's
        assertEquals(2, n.getVoltageLevel("VL").getGeneratorCount()); // BASE + B's NEW

        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("NEW")); // neither exists in the base
        assertEquals(1, n.getVoltageLevel("VL").getGeneratorCount()); // only BASE
    }

    @Test
    void anIdRemovedInAVariantCanBeReAddedThereWhileTheBaseKeepsTheOriginal() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "A", VariantCloneStrategy.STRUCTURAL);

        vm.setWorkingVariant("A");
        n.getGenerator("BASE").remove(); // tombstone BASE in A
        assertNull(n.getGenerator("BASE"));
        addUnit(n, "BASE", 99); // re-add a different BASE in A — must not throw
        assertNotNull(n.getGenerator("BASE"));
        assertEquals(99, n.getGenerator("BASE").getTargetP());
        assertEquals(1, n.getVoltageLevel("VL").getGeneratorCount());

        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getGenerator("BASE"));
        assertEquals(50, n.getGenerator("BASE").getTargetP()); // the original, untouched
        assertEquals(1, n.getVoltageLevel("VL").getGeneratorCount());
    }

    @Test
    void idUniquenessIsStillEnforcedOutsideAStructuralVariant() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        // a plain (STATE_ONLY) variant does not scope structure, so ids stay unique network-wide
        vm.cloneVariant(INITIAL, "wc");
        vm.setWorkingVariant("wc");
        org.junit.jupiter.api.Assertions.assertThrows(com.powsybl.commons.PowsyblException.class,
                () -> addUnit(n, "BASE", 1));
    }
}

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
 * Phase 2 (copy-on-write existence): an object added in a structural variant resolves its visibility
 * through the <b>variant parentage</b> — O(1) at add time instead of hiding it in every other variant.
 * This pins the parentage semantics: descendants inherit the object, siblings/ancestors do not, and
 * removing the variant makes it disappear everywhere.
 *
 * @author Claude
 */
class StructuralVariantParentageTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("grid", "example");
        Substation s = n.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("b").add();
        return n;
    }

    private static void addUnit(Network n, String id) {
        n.getVoltageLevel("VL").newGenerator().setId(id).setConnectableBus("b").setBus("b")
                .setMinP(0).setMaxP(100).setTargetP(50).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.THERMAL).add();
    }

    @Test
    void addedObjectIsVisibleInTheVariantAndItsDescendantsOnly() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "scenario", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("scenario");
        addUnit(n, "G");                                             // added in "scenario"

        // a child forked from "scenario" AFTER the add inherits G (descendant visibility)
        vm.cloneVariant("scenario", "sub", VariantCloneStrategy.STRUCTURAL);
        // a sibling forked from the base does NOT see G
        vm.cloneVariant(INITIAL, "other", VariantCloneStrategy.STRUCTURAL);

        vm.setWorkingVariant("scenario");
        assertNotNull(n.getGenerator("G"));
        vm.setWorkingVariant("sub");
        assertNotNull(n.getGenerator("G"));      // inherited by the descendant
        vm.setWorkingVariant("other");
        assertNull(n.getGenerator("G"));         // sibling: not visible
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("G"));         // base: not visible
    }

    @Test
    void removingTheVariantMakesTheAddedObjectDisappearEverywhere() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "scenario", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("scenario");
        addUnit(n, "G");
        vm.cloneVariant("scenario", "sub", VariantCloneStrategy.STRUCTURAL);

        // remove the variant G was added in; the child is re-parented onto the base
        vm.setWorkingVariant(INITIAL);
        vm.removeVariant("scenario");

        vm.setWorkingVariant("sub");
        assertNull(n.getGenerator("G"));         // gone: G existed only in the removed variant
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("G"));
    }
}

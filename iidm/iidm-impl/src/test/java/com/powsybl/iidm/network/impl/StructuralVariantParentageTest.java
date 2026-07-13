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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copy-on-write existence: an object added in a structural variant is per-variant state, snapshot-copied
 * at clone (like removals and terminal membership). This pins the snapshot semantics: a variant forked
 * <em>after</em> the add inherits the object, siblings/ancestors do not, a fork keeps what it inherited
 * even after the variant it was forked from is removed, and — crucially — a later edit to a parent variant
 * (an add or a removal) does not leak into a variant forked before that edit, with {@code getX(id)} and the
 * voltage-level enumeration always agreeing.
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
        addUnit(n, "BASE"); // a base unit present in every variant, to check enumeration/count consistency
        return n;
    }

    private static boolean vlHasGenerator(Network n, String id) {
        return n.getVoltageLevel("VL").getGeneratorStream().anyMatch(g -> g.getId().equals(id));
    }

    private static int vlGeneratorCount(Network n) {
        return n.getVoltageLevel("VL").getGeneratorCount();
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
    void aForkKeepsWhatItInheritedAfterTheSourceVariantIsRemoved() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "scenario", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("scenario");
        addUnit(n, "G");
        vm.cloneVariant("scenario", "sub", VariantCloneStrategy.STRUCTURAL); // sub inherits G (snapshot)

        // remove the variant G was added in; "sub" holds its own snapshot copy taken at its clone
        vm.setWorkingVariant(INITIAL);
        vm.removeVariant("scenario");

        vm.setWorkingVariant("sub");
        assertNotNull(n.getGenerator("G")); // kept: "sub" forked from "scenario" after the add
        assertTrue(vlHasGenerator(n, "G"));
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getGenerator("G"));    // never existed in the base
        assertFalse(vlHasGenerator(n, "G"));
    }

    @Test
    void addingInAVariantWithNoForkThenRemovingItDropsTheObject() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "scenario", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("scenario");
        addUnit(n, "G"); // added only in "scenario", nothing forked from it

        vm.setWorkingVariant(INITIAL);
        vm.removeVariant("scenario");
        assertNull(n.getGenerator("G")); // gone: no variant inherited it
        assertFalse(vlHasGenerator(n, "G"));
    }

    @Test
    void aParentAddAfterAForkDoesNotLeakIntoTheFork() {
        // Regression: an object added in a parent variant after a child was forked must not appear in the
        // child, and getX(id) must agree with the voltage-level enumeration (they resolved differently
        // before existence became snapshot-copied like membership).
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "A", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("A");
        vm.cloneVariant("A", "C", VariantCloneStrategy.STRUCTURAL); // C forked from A BEFORE the add
        vm.setWorkingVariant("A");
        addUnit(n, "G");                                            // add G in A, AFTER C was forked

        vm.setWorkingVariant("A");
        assertNotNull(n.getGenerator("G"));
        assertTrue(vlHasGenerator(n, "G"));
        assertEquals(2, vlGeneratorCount(n)); // BASE + G

        vm.setWorkingVariant("C");
        assertNull(n.getGenerator("G"));       // existence: not visible in the earlier fork
        assertFalse(vlHasGenerator(n, "G"));   // membership: agrees — not enumerated
        assertEquals(1, vlGeneratorCount(n));  // only BASE, consistent with the above
    }

    @Test
    void aParentRemovalAfterAForkDoesNotLeakIntoTheFork() {
        // The removal counterpart: removing a base object in a parent variant after a child was forked must
        // not remove it from the child, again with getX(id) and the enumeration agreeing.
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "A", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("A");
        vm.cloneVariant("A", "C", VariantCloneStrategy.STRUCTURAL); // C forked from A BEFORE the removal
        vm.setWorkingVariant("A");
        n.getGenerator("BASE").remove();                           // remove BASE in A, AFTER C was forked

        vm.setWorkingVariant("A");
        assertNull(n.getGenerator("BASE"));
        assertFalse(vlHasGenerator(n, "BASE"));
        assertEquals(0, vlGeneratorCount(n));

        vm.setWorkingVariant("C");
        assertNotNull(n.getGenerator("BASE")); // existence: still visible in the earlier fork
        assertTrue(vlHasGenerator(n, "BASE")); // membership: agrees — still enumerated
        assertEquals(1, vlGeneratorCount(n));
    }
}

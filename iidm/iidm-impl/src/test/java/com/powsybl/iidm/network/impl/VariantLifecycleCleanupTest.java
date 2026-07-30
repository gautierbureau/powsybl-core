/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Lifecycle of objects that exist only in a variant, and the boundary of what may be done concurrently.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantLifecycleCleanupTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("grid", "example");
        Substation s = n.newSubstation().setId("SA").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("busA").add();
        return n;
    }

    private static void addLoad(Network n, String id, double p0) {
        n.getVoltageLevel("VLA").newLoad().setId(id).setConnectableBus("busA").setBus("busA").setP0(p0).setQ0(1).add();
    }

    /**
     * Removing the only variant an object lived in must drop the object for good — id included. It used to
     * stay in the index, invisible everywhere yet still holding its id, so that id could never be used again.
     */
    @Test
    void removingAVariantFreesTheIdsOfTheObjectsThatOnlyExistedInIt() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "s");
        vm.setWorkingVariant("s");
        addLoad(n, "LD", 1);
        assertNotNull(n.getLoad("LD"));

        vm.setWorkingVariant(INITIAL);
        vm.removeVariant("s");
        assertNull(n.getLoad("LD"));
        assertEquals(0, n.getLoadCount());

        // the id is free again: re-creating it in the base variant works
        assertDoesNotThrow(() -> addLoad(n, "LD", 2));
        assertEquals(2.0, n.getLoad("LD").getP0(), 1e-9);
    }

    /** An object still visible in another variant is of course kept. */
    @Test
    void removingAVariantKeepsObjectsStillVisibleElsewhere() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "s1");
        vm.setWorkingVariant("s1");
        addLoad(n, "LD", 1);
        vm.cloneVariant("s1", "s2"); // s2 inherits s1's delta, so LD exists there too

        vm.setWorkingVariant(INITIAL);
        vm.removeVariant("s1");

        vm.setWorkingVariant("s2");
        assertNotNull(n.getLoad("LD"));
        vm.setWorkingVariant(INITIAL);
        assertNull(n.getLoad("LD"));
    }

    /**
     * The guard on structural edits must not catch the variant lifecycle's own tidy-up: removing a variant
     * drops the objects that only existed in it, and that is bookkeeping under the variant lock rather than
     * a caller editing the network, so it stays legal while multi-thread access is enabled.
     */
    @Test
    void removingAVariantStillCleansUpWhileMultiThreadAccessIsEnabled() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "s");
        vm.setWorkingVariant("s");
        addLoad(n, "LD", 1);

        vm.setWorkingVariant(INITIAL);
        vm.allowVariantMultiThreadAccess(true);
        assertDoesNotThrow(() -> vm.removeVariant("s"));

        vm.allowVariantMultiThreadAccess(false);
        assertNull(n.getLoad("LD"));
        assertDoesNotThrow(() -> addLoad(n, "LD", 2)); // the id was freed
    }

    /**
     * Both halves of a structural change are now concurrent: removing equipment inside a variant and
     * creating it (see {@code VariantConcurrentStructuralDivergenceTest}). State writes were always
     * concurrent and still are.
     */
    @Test
    void creatingAndWritingWhileMultiThreadAccessIsEnabledIsAllowed() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "s");
        vm.allowVariantMultiThreadAccess(true);
        vm.setWorkingVariant("s");

        assertDoesNotThrow(() -> addLoad(n, "LD", 1));
        assertDoesNotThrow(() -> n.getLoad("LD").setP0(42.0));
        assertEquals(42.0, n.getLoad("LD").getP0(), 1e-9);

        vm.setWorkingVariant(INITIAL);
        assertNull(n.getLoad("LD")); // still scoped to the variant it was created in
    }
}

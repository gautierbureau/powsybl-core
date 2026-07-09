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
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proposed public structural-variant API surface (see {@code structural-variant-public-api.md}): the
 * {@code cloneVariant(..., VariantCloneStrategy)} overloads. {@code STATE_ONLY} is the historical
 * behaviour; {@code STRUCTURAL} is defined but not yet wired in the eager implementation, so it fails
 * loudly. This pins the API shape (backward-compatible default methods) ahead of the implementation.
 *
 * @author Claude
 */
class VariantCloneStrategyTest {

    private static Network buildNetwork() {
        Network n = Network.create("base", "test");
        Substation s = n.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("b").add();
        return n;
    }

    @Test
    void stateOnlyStrategyDelegatesToTheExistingClone() {
        Network n = buildNetwork();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "scenario", VariantCloneStrategy.STATE_ONLY);

        assertTrue(vm.getVariantIds().contains("scenario"));
        vm.setWorkingVariant("scenario");
        assertEquals("scenario", vm.getWorkingVariantId());
    }

    @Test
    void structuralStrategyIsDefinedButNotYetImplemented() {
        Network n = buildNetwork();
        VariantManager vm = n.getVariantManager();

        UnsupportedOperationException single = assertThrows(UnsupportedOperationException.class,
                () -> vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "structural", VariantCloneStrategy.STRUCTURAL));
        assertTrue(single.getMessage().contains("structural-variant-public-api.md"));

        assertThrows(UnsupportedOperationException.class,
                () -> vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, List.of("s1", "s2"),
                        VariantCloneStrategy.STRUCTURAL, false));

        // the failed clones did not create any variant
        assertEquals(List.of(VariantManagerConstants.INITIAL_VARIANT_ID), List.copyOf(vm.getVariantIds()));
    }
}

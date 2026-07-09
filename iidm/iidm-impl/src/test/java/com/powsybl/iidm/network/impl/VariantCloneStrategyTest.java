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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public structural-variant API surface (see {@code structural-variant-public-api.md}): the
 * {@code cloneVariant(..., VariantCloneStrategy)} overloads. {@code STATE_ONLY} is the historical
 * behaviour; {@code STRUCTURAL} (Phase 1) additionally lets the variant diverge structurally. Both
 * produce a usable working variant. The scoping behaviour itself is covered by
 * {@link StructuralVariantRemoveTest}.
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
    void structuralStrategyCreatesUsableVariants() {
        Network n = buildNetwork();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "structural", VariantCloneStrategy.STRUCTURAL);
        vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, List.of("s1", "s2"), VariantCloneStrategy.STRUCTURAL, false);

        assertTrue(vm.getVariantIds().containsAll(List.of("structural", "s1", "s2")));
        vm.setWorkingVariant("structural");
        assertEquals("structural", vm.getWorkingVariantId());
    }
}

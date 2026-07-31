/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.ShuntTestCaseFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shunt's section counts are nullable per variant, and the columnar store holds them as ints behind a
 * SECTION_NULL sentinel. These cover the unset state surviving a clone and staying per-variant, which is where
 * a sentinel conversion goes wrong.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class ShuntSectionCountVariantTest {

    private Network network;
    private ShuntCompensator shunt;
    private VariantManager variantManager;

    @BeforeEach
    void setUp() {
        network = ShuntTestCaseFactory.create();
        shunt = network.getShuntCompensator("SHUNT");
        variantManager = network.getVariantManager();
    }

    @Test
    void sectionCountsAreIndependentPerVariant() {
        shunt.setSectionCount(1);
        shunt.setSolvedSectionCount(1);
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");

        variantManager.setWorkingVariant("v2");
        assertEquals(1, shunt.getSectionCount());
        assertEquals(1, shunt.getSolvedSectionCount());
        shunt.setSectionCount(0);
        shunt.setSolvedSectionCount(0);
        assertEquals(0, shunt.getSectionCount());

        variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(1, shunt.getSectionCount());
        assertEquals(1, shunt.getSolvedSectionCount());
    }

    @Test
    void anUnsetSectionCountStaysUnsetInTheVariantThatUnsetIt() {
        shunt.setSectionCount(1);
        shunt.setSolvedSectionCount(1);
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");

        variantManager.setWorkingVariant("v2");
        network.setMinimumAcceptableValidationLevel(com.powsybl.iidm.network.ValidationLevel.EQUIPMENT);
        shunt.unsetSectionCount();
        shunt.unsetSolvedSectionCount();
        assertFalse(shunt.findSectionCount().isPresent());
        assertNull(shunt.getSolvedSectionCount());
        assertThrows(PowsyblException.class, () -> shunt.getSectionCount());

        // the other variant is untouched
        variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertTrue(shunt.findSectionCount().isPresent());
        assertEquals(1, shunt.getSectionCount());
        assertEquals(1, shunt.getSolvedSectionCount());
    }

    @Test
    void anUnsetSectionCountIsInheritedByAClone() {
        network.setMinimumAcceptableValidationLevel(com.powsybl.iidm.network.ValidationLevel.EQUIPMENT);
        shunt.unsetSectionCount();
        shunt.unsetSolvedSectionCount();

        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        variantManager.setWorkingVariant("v2");
        assertFalse(shunt.findSectionCount().isPresent());
        assertNull(shunt.getSolvedSectionCount());

        // and setting it in the clone does not leak back
        shunt.setSectionCount(1);
        assertEquals(1, shunt.getSectionCount());
        variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertFalse(shunt.findSectionCount().isPresent());
    }

    @Test
    void sectionCountSurvivesDetachAndAFurtherClone() {
        shunt.setSectionCount(1);
        shunt.setSolvedSectionCount(1);

        Network other = Network.create("other", "test");
        other.newSubstation().setId("OS").add()
                .newVoltageLevel().setId("OVL").setNominalV(400)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add()
                .getBusBreakerView().newBus().setId("OB").add();
        String id = network.getId();
        Network detached = Network.merge(network, other).getSubnetwork(id).detach();

        ShuntCompensator moved = detached.getShuntCompensator("SHUNT");
        assertEquals(1, moved.getSectionCount());
        assertEquals(1, moved.getSolvedSectionCount());

        VariantManager vm = detached.getVariantManager();
        vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        vm.setWorkingVariant("v2");
        assertEquals(1, moved.getSectionCount());
        moved.setSectionCount(0);
        vm.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(1, moved.getSectionCount());
    }
}

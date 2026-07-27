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
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A structural variant is only wired for adding variant-scoped equipment and removing connectables. Editing
 * shared containers, switches or buses still mutates the single shared graph, so those operations are
 * rejected with a clear exception rather than silently corrupting the base and sibling variants. This pins
 * the fail-fast guards (and that the base variant is left intact after a rejected edit).
 *
 * @author Claude
 */
class StructuralVariantUnsupportedEditsTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static VariantManager structuralVariant(Network n) {
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "s", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("s");
        return vm;
    }

    private static void assertRejected(Executable op) {
        PowsyblException e = assertThrows(PowsyblException.class, op::run);
        assertTrue(e.getMessage().contains("structural variant"), () -> "unexpected message: " + e.getMessage());
    }

    @FunctionalInterface
    private interface Executable {
        void run();
    }

    @Test
    void removingASharedVoltageLevelIsRejectedAndLeavesTheBaseIntact() {
        Network n = EurostagTutorialExample1Factory.create();
        VariantManager vm = structuralVariant(n);

        assertRejected(() -> n.getVoltageLevel("VLGEN").remove());

        assertNotNull(n.getVoltageLevel("VLGEN")); // still there in the structural variant
        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getVoltageLevel("VLGEN")); // and in the base
    }

    @Test
    void removingASharedSubstationIsRejectedAndLeavesTheBaseIntact() {
        Network n = EurostagTutorialExample1Factory.create();
        VariantManager vm = structuralVariant(n);

        assertRejected(() -> n.getSubstation("P1").remove());

        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getSubstation("P1"));
    }

    @Test
    void addingAVoltageLevelUnderASharedSubstationIsRejected() {
        Network n = EurostagTutorialExample1Factory.create();
        structuralVariant(n);

        assertRejected(() -> n.getSubstation("P1").newVoltageLevel()
                .setId("NEW_VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add());
    }

    @Test
    void addingAVoltageLevelUnderASubstationCreatedInTheSameVariantIsAllowed() {
        Network n = EurostagTutorialExample1Factory.create();
        structuralVariant(n);

        // the fault-on-line split pattern: a new (variant-scoped) substation can be extended freely
        assertDoesNotThrow(() -> {
            var sf = n.newSubstation().setId("SF").setFictitious(true).add();
            sf.newVoltageLevel().setId("VF").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER)
                    .add().getBusBreakerView().newBus().setId("busF").add();
        });
    }

    @Test
    void removingASharedBusIsRejectedAndLeavesTheBaseIntact() {
        Network n = EurostagTutorialExample1Factory.create();
        VariantManager vm = structuralVariant(n);

        assertRejected(() -> n.getVoltageLevel("VLLOAD").getBusBreakerView().removeBus("NLOAD"));

        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getBusBreakerView().getBus("NLOAD"));
    }

    @Test
    void addingASwitchOnASharedNodeBreakerVoltageLevelIsRejected() {
        Network n = FourSubstationsNodeBreakerFactory.create();
        structuralVariant(n);

        assertRejected(() -> n.getVoltageLevel("S1VL1").getNodeBreakerView().newSwitch());
        assertRejected(() -> n.getVoltageLevel("S1VL1").getNodeBreakerView().newInternalConnection());
    }

    @Test
    void removingASwitchOnASharedNodeBreakerVoltageLevelIsRejectedAndLeavesTheBaseIntact() {
        Network n = FourSubstationsNodeBreakerFactory.create();
        VariantManager vm = structuralVariant(n);
        Switch aSwitch = n.getVoltageLevel("S1VL1").getNodeBreakerView().getSwitchStream().findFirst().orElseThrow();
        String switchId = aSwitch.getId();

        assertRejected(() -> n.getVoltageLevel("S1VL1").getNodeBreakerView().removeSwitch(switchId));

        vm.setWorkingVariant(INITIAL);
        assertNotNull(n.getSwitch(switchId));
    }
}

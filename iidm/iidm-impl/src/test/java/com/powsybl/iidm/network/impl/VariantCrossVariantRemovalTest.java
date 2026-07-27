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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A removal made in the initial variant is shared, so it must not strand equipment that only another variant
 * has. The removability checks enumerate a voltage level's connectables, which resolve against the working
 * variant; equipment added onto a shared voltage level in another variant lives in the variant-scoped
 * membership instead of that voltage level's graph, so those checks cannot see it on their own.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantCrossVariantRemovalTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("grid", "example");
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

    private static void addScopedLoad(Network n, String variant, String id) {
        n.getVariantManager().setWorkingVariant(variant);
        n.getVoltageLevel("VLA").newLoad().setId(id).setConnectableBus("busA").setBus("busA").setP0(10).setQ0(5).add();
    }

    @Test
    void removingAVoltageLevelAnotherVariantAttachedEquipmentToIsRejected() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "s");
        addScopedLoad(n, "s", "SCOPED_LD");

        vm.setWorkingVariant(INITIAL);
        n.getLine("L").remove(); // VLA now looks empty from the base

        PowsyblException e = assertThrows(PowsyblException.class, () -> n.getVoltageLevel("VLA").remove());
        assertTrue(e.getMessage().contains("VLA") && e.getMessage().contains("'s'") && e.getMessage().contains("SCOPED_LD"),
                () -> "unexpected message: " + e.getMessage());

        // nothing was removed, and the variant is intact
        assertNotNull(n.getVoltageLevel("VLA"));
        vm.setWorkingVariant("s");
        assertNotNull(n.getLoad("SCOPED_LD"));
        assertEquals("VLA", n.getLoad("SCOPED_LD").getTerminal().getVoltageLevel().getId());
    }

    @Test
    void removingTheSubstationOfSuchAVoltageLevelIsRejectedToo() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "s");
        addScopedLoad(n, "s", "SCOPED_LD");

        vm.setWorkingVariant(INITIAL);
        n.getLine("L").remove();

        assertThrows(PowsyblException.class, () -> n.getSubstation("SA").remove());
        assertNotNull(n.getSubstation("SA"));
        assertNotNull(n.getVoltageLevel("VLA"));
    }

    /** Once the variant that held the equipment is gone, the voltage level is removable again. */
    @Test
    void removingTheVariantUnblocksTheRemoval() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "s");
        addScopedLoad(n, "s", "SCOPED_LD");

        vm.setWorkingVariant(INITIAL);
        n.getLine("L").remove();
        vm.removeVariant("s");

        assertDoesNotThrow(() -> n.getVoltageLevel("VLA").remove());
        assertNull(n.getVoltageLevel("VLA"));
    }

    /** A voltage level no variant has touched stays removable — the guard only fires when it must. */
    @Test
    void anUntouchedVoltageLevelStaysRemovable() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "s");
        addScopedLoad(n, "s", "SCOPED_LD"); // on VLA, not VLB

        vm.setWorkingVariant(INITIAL);
        n.getLine("L").remove();
        assertDoesNotThrow(() -> n.getVoltageLevel("VLB").remove());
        assertNull(n.getVoltageLevel("VLB"));

        vm.setWorkingVariant("s");
        assertNotNull(n.getLoad("SCOPED_LD"));
    }
}

/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching in a variant. Opening or closing a switch is a <em>state</em> change, not a structural one: a
 * switch's open/retained flags are per-variant columns of {@code SwitchVariantStore}, so switching has always
 * been variant-scoped and still is. What is guarded is <em>removing</em> a switch or adding one onto shared
 * structure, which changes the topology graph every variant shares.
 *
 * <p>Worth pinning down anyway, because switching is how node-breaker contingencies are usually expressed,
 * and because the calculated bus topology derived from those switches is cached per variant.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantSwitchOperationTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    /** Closed, non-retained switches, one per worker to operate on. */
    private static List<String> operableSwitches(Network n, int count) {
        return n.getSwitchStream().filter(s -> !s.isOpen()).map(Switch::getId).limit(count).toList();
    }

    @Test
    void openingASwitchIsScopedToTheWorkingVariant() {
        Network n = FourSubstationsNodeBreakerFactory.create();
        VariantManager vm = n.getVariantManager();
        String swId = operableSwitches(n, 1).get(0);

        vm.cloneVariant(INITIAL, "outage");
        vm.setWorkingVariant("outage");
        n.getSwitch(swId).setOpen(true);
        assertTrue(n.getSwitch(swId).isOpen());

        vm.setWorkingVariant(INITIAL);
        assertFalse(n.getSwitch(swId).isOpen(), "the base variant must not see the opening");
    }

    /** The bus view is derived from the switch positions, and cached per variant — it must follow. */
    @Test
    void openingASwitchChangesOnlyThatVariantsBusView() {
        Network n = FourSubstationsNodeBreakerFactory.create();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "outage");

        vm.setWorkingVariant(INITIAL);
        long baseBuses = n.getBusView().getBusStream().count();     // warm the base cache

        vm.setWorkingVariant("outage");
        assertEquals(baseBuses, n.getBusView().getBusStream().count()); // warm the variant cache too

        // open every switch of one voltage level: its busbars can no longer be merged into one bus
        n.getVoltageLevel("S1VL2").getNodeBreakerView().getSwitchStream().forEach(s -> s.setOpen(true));
        long outageBuses = n.getBusView().getBusStream().count();
        assertNotEquals(baseBuses, outageBuses, "the variant's bus view must reflect its own switching");

        vm.setWorkingVariant(INITIAL);
        assertEquals(baseBuses, n.getBusView().getBusStream().count(), "the base's bus view must be untouched");
    }

    /** Each worker switches in its own variant, in parallel, on one shared network. */
    @RepeatedTest(5)
    void workersSwitchIndependentlyInTheirOwnVariants() {
        Network n = FourSubstationsNodeBreakerFactory.create();
        VariantManager vm = n.getVariantManager();
        List<String> switches = operableSwitches(n, 6);
        vm.allowVariantMultiThreadAccess(true);

        IntStream.range(0, switches.size()).parallel().forEach(k -> {
            vm.cloneVariant(INITIAL, "sw-" + k);
            vm.setWorkingVariant("sw-" + k);

            n.getSwitch(switches.get(k)).setOpen(true);

            // its own switching is applied, and no sibling's is visible
            assertTrue(n.getSwitch(switches.get(k)).isOpen());
            for (int j = 0; j < switches.size(); j++) {
                if (j != k) {
                    assertFalse(n.getSwitch(switches.get(j)).isOpen(),
                            "worker " + k + " saw worker " + j + "'s switching");
                }
            }
            n.getBusView().getBusStream().count(); // exercise the per-variant derived topology
        });

        vm.allowVariantMultiThreadAccess(false);
        vm.setWorkingVariant(INITIAL);
        for (String sw : switches) {
            assertFalse(n.getSwitch(sw).isOpen(), "switching leaked into the base variant");
        }
    }

    /** Switching combines with a structural contingency in the same variant. */
    @Test
    void switchingAndStructuralRemovalCombineInOneVariant() {
        Network n = FourSubstationsNodeBreakerFactory.create();
        VariantManager vm = n.getVariantManager();
        String swId = operableSwitches(n, 1).get(0);
        String lineId = n.getLineStream().findFirst().orElseThrow().getId();

        vm.cloneVariant(INITIAL, "combined");
        vm.setWorkingVariant("combined");
        n.getSwitch(swId).setOpen(true);   // state
        n.getLine(lineId).remove();        // structure
        assertTrue(n.getSwitch(swId).isOpen());
        assertEquals(0, n.getLineStream().filter(l -> lineId.equals(l.getId())).count());

        vm.setWorkingVariant(INITIAL);
        assertFalse(n.getSwitch(swId).isOpen());
        assertEquals(1, n.getLineStream().filter(l -> lineId.equals(l.getId())).count());
    }
}

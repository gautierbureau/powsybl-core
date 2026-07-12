/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a {@link NumericVariantStore} first created <b>after</b> structural variants already exist (a
 * columnar-backed type, here an {@link ActivePowerControl} extension, whose store did not exist at network
 * build time) must have its copy-on-write band table pre-sized to the current variant count. Otherwise the
 * table starts empty and the first per-variant write on a worker thread grows it via {@code Arrays.copyOf},
 * racing concurrent workers so that some variants silently lose their diverged values.
 *
 * @author Claude
 */
class NumericVariantStoreLazyCreationCowTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("lazy-cow-grid", "test");
        Substation s = n.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("b").add();
        vl.newGenerator().setId("GEN").setConnectableBus("b").setBus("b")
                .setMinP(0).setMaxP(500).setTargetP(200).setTargetV(400).setVoltageRegulatorOn(true).add();
        return n;
    }

    @Test
    void extensionStoreCreatedAfterCloningDivergesPerVariantUnderConcurrentWrites() throws InterruptedException {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        int workers = 8;
        for (int i = 0; i < workers; i++) {
            vm.cloneVariant(INITIAL, "w" + i, VariantCloneStrategy.STRUCTURAL);
        }

        // Add the ActivePowerControl extension only now — its NumericVariantStore is created lazily here,
        // after the structural variants already exist, so its band table must be pre-sized on this (main)
        // thread rather than grown later on a worker thread.
        Generator gen = n.getGenerator("GEN");
        gen.newExtension(ActivePowerControlAdder.class).withParticipate(true).withDroop(1.0).add();
        ActivePowerControl<Generator> apc = gen.getExtension(ActivePowerControl.class);

        vm.allowVariantMultiThreadAccess(true);
        List<Thread> threads = new ArrayList<>();
        List<AssertionError> failures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            int w = i;
            threads.add(new Thread(() -> {
                vm.setWorkingVariant("w" + w);
                double expected = 10.0 + w;
                apc.setDroop(expected);
                for (int it = 0; it < 2000; it++) {
                    if (apc.getDroop() != expected) {
                        synchronized (failures) {
                            failures.add(new AssertionError("variant w" + w + " lost its droop: got " + apc.getDroop()));
                        }
                        return;
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(), () -> failures.get(0).getMessage());

        vm.allowVariantMultiThreadAccess(false);
        // each structural variant kept exactly its own value, and the base is untouched
        for (int i = 0; i < workers; i++) {
            vm.setWorkingVariant("w" + i);
            assertEquals(10.0 + i, apc.getDroop());
        }
        vm.setWorkingVariant(INITIAL);
        assertEquals(1.0, apc.getDroop());
    }
}

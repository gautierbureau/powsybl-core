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
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workload this whole feature exists for: one variant per worker, each applying its own contingency in
 * parallel on a single shared network. Each worker removes a different line in its own variant, and must see
 * exactly its own removal — no other worker's, and never a corrupted index or a
 * {@code ConcurrentModificationException} from an unrelated read.
 *
 * <p>Structure is shared between variants, so the per-variant divergence bookkeeping is what has to be
 * thread-safe here: the maps keyed by variant are concurrent, while the per-variant entries below them stay
 * thread-confined because a variant is only ever edited by the worker that owns it.</p>
 *
 * <p>Repeated, because a data race does not fail every run.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantConcurrentStructuralDivergenceTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;
    private static final int WORKERS = 32;

    /** A chain of voltage levels, one line between each consecutive pair. */
    private static Network chain(int size) {
        Network n = Network.create("chain", "test");
        for (int i = 0; i < size; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
            vl.newLoad().setId("LD" + i).setBus("B" + i).setConnectableBus("B" + i).setP0(1).setQ0(0).add();
        }
        for (int i = 0; i < size - 1; i++) {
            n.newLine().setId("L" + i)
                    .setVoltageLevel1("VL" + i).setBus1("B" + i).setConnectableBus1("B" + i)
                    .setVoltageLevel2("VL" + (i + 1)).setBus2("B" + (i + 1)).setConnectableBus2("B" + (i + 1))
                    .setR(0.1).setX(1).setG1(0).setB1(0).setG2(0).setB2(0).add();
        }
        return n;
    }

    /** One worker's whole job: fork its own variant, apply its contingency there, check what it sees. */
    private static void applyContingency(Network n, VariantManager vm, int k) {
        String variant = "n-1-L" + k;
        vm.cloneVariant(INITIAL, variant);
        vm.setWorkingVariant(variant);

        n.getLine("L" + k).remove();            // structural, scoped to this worker's variant
        n.getLoad("LD" + k).setP0(100.0 + k);   // and a state write alongside it

        // its own contingency is applied, and no other worker's is visible here
        assertNull(n.getLine("L" + k));
        for (int j = 0; j < WORKERS; j++) {
            if (j != k) {
                assertNotNull(n.getLine("L" + j), "worker " + k + " saw worker " + j + "'s removal");
            }
        }
        assertEquals(100.0 + k, n.getLoad("LD" + k).getP0(), 0.0);
    }

    @RepeatedTest(10)
    void eachWorkerAppliesItsOwnContingencyInItsOwnVariant() throws Exception {
        Network n = chain(WORKERS + 1);
        VariantManager vm = n.getVariantManager();
        vm.preAllocateVariants(WORKERS);
        vm.allowVariantMultiThreadAccess(true);

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CyclicBarrier barrier = new CyclicBarrier(WORKERS);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            final int k = i;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(); // maximise contention
                    applyContingency(n, vm, k);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), () -> "concurrent structural divergence failed: " + errors);

        vm.allowVariantMultiThreadAccess(false);

        // back on the main thread: every variant kept exactly its own divergence...
        for (int k = 0; k < WORKERS; k++) {
            vm.setWorkingVariant("n-1-L" + k);
            assertNull(n.getLine("L" + k));
            assertEquals(WORKERS - 1, n.getLineCount());
            assertEquals(100.0 + k, n.getLoad("LD" + k).getP0(), 0.0);
        }

        // ...and the shared base was never touched
        vm.setWorkingVariant(INITIAL);
        assertEquals(WORKERS, n.getLineCount());
        for (int k = 0; k < WORKERS; k++) {
            assertNotNull(n.getLine("L" + k));
            assertEquals(1.0, n.getLoad("LD" + k).getP0(), 0.0);
        }
    }
}

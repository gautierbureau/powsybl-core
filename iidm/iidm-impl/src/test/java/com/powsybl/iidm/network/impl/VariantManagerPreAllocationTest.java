/**
 * Copyright (c) 2026, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link VariantManager#preAllocateVariants(int)} capacity reservation and the thread-safe,
 * on-demand variant creation it enables (see powsybl/powsybl-core#721).
 *
 * @author Claude Code
 */
class VariantManagerPreAllocationTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    @Test
    void preAllocateReservesCapacityWithoutCreatingVariants() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManagerImpl vm = (VariantManagerImpl) network.getVariantManager();

        assertEquals(1, vm.getVariantArraySize());
        assertEquals(1, vm.getVariantIds().size());

        // reserving capacity grows the backing arrays but does not create any live variant
        vm.preAllocateVariants(3);
        assertEquals(4, vm.getVariantArraySize());
        assertEquals(Collections.singleton(INITIAL), vm.getVariantIds());

        // the three reserved slots are consumed by clones without any further array growth
        vm.cloneVariant(INITIAL, "v1");
        vm.cloneVariant(INITIAL, "v2");
        vm.cloneVariant(INITIAL, "v3");
        assertEquals(4, vm.getVariantArraySize());
        assertEquals(4, vm.getVariantIds().size());
    }

    @Test
    void preAllocateRejectsNegativeCount() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager vm = network.getVariantManager();
        assertThrows(IllegalArgumentException.class, () -> vm.preAllocateVariants(-1));
    }

    @Test
    void overflowBeyondReservedCapacityThrowsUnderMultiThreadAccess() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager vm = network.getVariantManager();

        vm.preAllocateVariants(1);
        vm.allowVariantMultiThreadAccess(true);

        // first creation reuses the single reserved slot
        vm.cloneVariant(INITIAL, "v1");
        // second creation would need to resize the arrays from a worker thread -> fail fast
        PowsyblException e = assertThrows(PowsyblException.class, () -> vm.cloneVariant(INITIAL, "v2"));
        assertTrue(e.getMessage().contains("No pre-allocated variant capacity left"));
    }

    @Test
    void removeDoesNotShrinkArraysUnderMultiThreadAccess() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManagerImpl vm = (VariantManagerImpl) network.getVariantManager();

        vm.preAllocateVariants(2);
        vm.allowVariantMultiThreadAccess(true);
        vm.cloneVariant(INITIAL, "v1");
        vm.cloneVariant(INITIAL, "v2");
        assertEquals(3, vm.getVariantArraySize());

        // removing the last variant must NOT shrink the arrays while multi-thread access is on: the slot is
        // parked and stays available, so a subsequent creation reuses it instead of overflowing
        vm.removeVariant("v2");
        assertEquals(3, vm.getVariantArraySize());
        vm.cloneVariant(INITIAL, "v3");
        assertEquals(3, vm.getVariantArraySize());
        assertEquals(3, vm.getVariantIds().size());
    }

    @Test
    void concurrentOnDemandVariantCreationIsThreadSafe() throws Exception {
        int nThreads = 8;
        Network network = EurostagTutorialExample1Factory.create();
        Generator gen = network.getGenerator("GEN");
        double baseTargetP = gen.getTargetP();
        VariantManager vm = network.getVariantManager();

        // reserve one slot per worker, then go multi-threaded
        vm.preAllocateVariants(nThreads);
        vm.allowVariantMultiThreadAccess(true);

        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        CyclicBarrier barrier = new CyclicBarrier(nThreads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < nThreads; i++) {
            final int k = i;
            futures.add(pool.submit(() -> {
                try {
                    String vid = "v" + k;
                    double value = baseTargetP + k + 1;
                    barrier.await(); // maximise contention: everyone creates at once
                    vm.cloneVariant(INITIAL, vid);
                    vm.setWorkingVariant(vid);
                    // fresh clone inherits the source value, then diverges in isolation
                    assertEquals(baseTargetP, gen.getTargetP(), 0.0);
                    gen.setTargetP(value);
                    for (int r = 0; r < 2000; r++) {
                        assertEquals(value, gen.getTargetP(), 0.0);
                    }
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
        assertTrue(errors.isEmpty(), () -> "Concurrent variant creation failed: " + errors);

        // every worker's variant exists and kept its own isolated value; no growth beyond reserved capacity
        assertEquals(nThreads + 1, vm.getVariantIds().size());
        for (int i = 0; i < nThreads; i++) {
            vm.setWorkingVariant("v" + i);
            assertEquals(baseTargetP + i + 1, gen.getTargetP(), 0.0);
        }
    }
}

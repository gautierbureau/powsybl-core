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
 * Tests for concurrent, on-demand {@code STRUCTURAL} variant creation into pre-allocated capacity — the
 * full-#721 scope (B1) on top of the copy-on-write engine. {@link VariantManager#preAllocateVariants(int)}
 * reserves the {@code cowBands} table (and the eager per-variant state) up front so that a later structural
 * clone into a reserved slot, and the copy-on-write bookkeeping it records, never resize anything on a
 * worker thread; workers then fork one structural variant each from the shared (unwritten) base.
 *
 * @author Claude Code
 */
class VariantPreAllocationStructuralCloneTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    @Test
    void preAllocateReservesCapacityForStructuralClonesWithoutGrowing() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManagerImpl vm = (VariantManagerImpl) network.getVariantManager();

        assertEquals(1, vm.getVariantArraySize());

        vm.preAllocateVariants(3);
        assertEquals(4, vm.getVariantArraySize());
        assertEquals(Collections.singleton(INITIAL), vm.getVariantIds());

        // three structural clones consume the reserved slots without any further array growth
        vm.cloneVariant(INITIAL, "v1");
        vm.cloneVariant(INITIAL, "v2");
        vm.cloneVariant(INITIAL, "v3");
        assertEquals(4, vm.getVariantArraySize());
        assertEquals(4, vm.getVariantIds().size());
    }

    @Test
    void overflowBeyondReservedCapacityThrowsUnderMultiThreadAccess() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager vm = network.getVariantManager();

        vm.preAllocateVariants(1);
        vm.allowVariantMultiThreadAccess(true);

        vm.cloneVariant(INITIAL, "v1");
        PowsyblException e = assertThrows(PowsyblException.class,
            () -> vm.cloneVariant(INITIAL, "v2"));
        assertTrue(e.getMessage().contains("No pre-allocated variant capacity left"));
    }

    @Test
    void concurrentStructuralCloneMustForkFromNonStructuralBase() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager vm = network.getVariantManager();
        vm.preAllocateVariants(4);

        // single-thread nested structural forks are allowed (chains are fine off the parallel region)
        vm.cloneVariant(INITIAL, "s1");
        vm.cloneVariant("s1", "s2");

        // once multi-thread access is enabled, forking from a structural variant is rejected fail-fast...
        vm.allowVariantMultiThreadAccess(true);
        PowsyblException e = assertThrows(PowsyblException.class,
            () -> vm.cloneVariant("s1", "s3"));
        assertTrue(e.getMessage().contains("must fork from a non-structural"));

        // ...the rejected clone left no partial state, and forking from the base is still fine
        assertTrue(vm.getVariantIds().contains("s1"));
        assertTrue(!vm.getVariantIds().contains("s3"));
        vm.cloneVariant(INITIAL, "s4");
        assertTrue(vm.getVariantIds().contains("s4"));
    }

    @Test
    void writingTheSharedBaseDuringTheParallelRegionIsRejected() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator gen = network.getGenerator("GEN");
        double baseTargetP = gen.getTargetP();
        VariantManager vm = network.getVariantManager();

        vm.preAllocateVariants(2);
        vm.allowVariantMultiThreadAccess(true);

        // writing the base is still fine before it becomes a fork source
        gen.setTargetP(baseTargetP + 1);

        // forking a structural variant off the base freezes the base for the parallel region
        vm.cloneVariant(INITIAL, "w");

        // writing the worker's own leaf is fine
        vm.setWorkingVariant("w");
        gen.setTargetP(123.0);
        assertEquals(123.0, gen.getTargetP(), 0.0);

        // writing the shared base while the region is active is rejected fail-fast
        vm.setWorkingVariant(INITIAL);
        PowsyblException e = assertThrows(PowsyblException.class, () -> gen.setTargetP(500.0));
        assertTrue(e.getMessage().contains("must not be written while multi-thread access is enabled"));

        // once the region ends, the base is writable again
        vm.allowVariantMultiThreadAccess(false);
        gen.setTargetP(500.0);
        assertEquals(500.0, gen.getTargetP(), 0.0);
    }

    @Test
    void concurrentOnDemandStructuralCloneIsThreadSafe() throws Exception {
        int nThreads = 8;
        Network network = EurostagTutorialExample1Factory.create();
        Generator gen = network.getGenerator("GEN");
        double baseTargetP = gen.getTargetP();
        VariantManager vm = network.getVariantManager();

        // reserve one structural slot per worker, then go multi-threaded
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
                    barrier.await(); // maximise contention: everyone forks at once
                    // each worker forks its own structural variant from the shared, unwritten base
                    vm.cloneVariant(INITIAL, vid);
                    vm.setWorkingVariant(vid);
                    // the fork inherits the base value (copy-on-write, no copy), then diverges in isolation
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
        assertTrue(errors.isEmpty(), () -> "Concurrent structural clone failed: " + errors);

        // every worker's structural variant exists and kept its own isolated value
        assertEquals(nThreads + 1, vm.getVariantIds().size());
        for (int i = 0; i < nThreads; i++) {
            vm.setWorkingVariant("v" + i);
            assertEquals(baseTargetP + i + 1, gen.getTargetP(), 0.0);
        }
        // the shared base was never written and is unchanged (copy-on-write isolation held)
        vm.setWorkingVariant(INITIAL);
        assertEquals(baseTargetP, gen.getTargetP(), 0.0);
    }
}

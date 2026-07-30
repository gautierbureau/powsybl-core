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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creating equipment while another thread clones a variant <em>and grows the variant arrays</em>.
 *
 * <p>This is the one interleaving the rest of the concurrency tests cannot reach, because they reserve
 * capacity with {@code preAllocateVariants} and so never grow anything during their parallel region. Without
 * a reservation, a clone extends the arrays on whatever thread called it, and an object sizes its per-variant
 * state in its constructor but publishes itself in the index as a separate, later step. A clone that runs
 * between those two points grows every object in its stateful-objects snapshot — which cannot contain the new
 * object, as it is not published yet — and leaves it one slot short.</p>
 *
 * <p>The creation happens in the <b>base</b> variant on purpose. Equipment created in a variant is scoped to
 * it and is invisible from a sibling, so a short slot there could never be read; created in the base it is
 * shared, every fork sees it, and the short slot is reachable.</p>
 *
 * <p>Repeated and contended, because the window between sizing and publishing is narrow.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantConcurrentCloneAndCreateTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;
    private static final int CLONERS = 8;
    private static final int CREATIONS = 60;

    private static Network grid() {
        Network n = Network.create("clone-and-create", "test");
        Substation s = n.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("B").add();
        vl.newLoad().setId("SEED").setBus("B").setConnectableBus("B").setP0(1).setQ0(0).add();
        return n;
    }

    @RepeatedTest(20)
    void equipmentCreatedWhileAnotherThreadGrowsTheVariantArraysStaysReadable() throws Exception {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        // deliberately no preAllocateVariants: every clone below extends the per-variant arrays
        vm.allowVariantMultiThreadAccess(true);
        vm.setWorkingVariant(INITIAL);

        ExecutorService pool = Executors.newFixedThreadPool(CLONERS + 1);
        CyclicBarrier barrier = new CyclicBarrier(CLONERS + 1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        // the creator: keeps adding equipment to the shared base variant
        futures.add(pool.submit(() -> {
            try {
                vm.setWorkingVariant(INITIAL);
                barrier.await();
                for (int i = 0; i < CREATIONS; i++) {
                    n.getVoltageLevel("VL").newLoad().setId("LD" + i)
                            .setBus("B").setConnectableBus("B").setP0(i).setQ0(0).add();
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }));

        // the cloners: each forks its own variant off the base, growing every per-variant array, then reads
        // whatever equipment is visible from its fork — including loads the creator added moments ago
        for (int k = 0; k < CLONERS; k++) {
            final int id = k;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    for (int r = 0; r < 8; r++) {
                        String variant = "v" + id + "_" + r;
                        vm.cloneVariant(INITIAL, variant);
                        vm.setWorkingVariant(variant);
                        // reading a load's terminal touches the per-variant connectableBusId of its terminal,
                        // which is exactly the state a missed extend cascade leaves short
                        n.getLoadStream().forEach(l -> assertNotNull(l.getTerminal().getBusBreakerView()
                                .getConnectableBus()));
                        assertNotNull(n.getLoad("SEED").getTerminal().getBusBreakerView().getConnectableBus());
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
        assertTrue(errors.isEmpty(), () -> "clone-while-creating failed: " + errors);
    }
}

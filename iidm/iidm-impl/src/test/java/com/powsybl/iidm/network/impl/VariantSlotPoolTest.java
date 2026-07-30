/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The way to use {@link VariantManager#preAllocateVariants(int)} now that it is a hint: reserve one slot per
 * <em>worker thread</em>, not one per unit of work, and give the slot back when the worker is done with it.
 *
 * <p>A contingency list is usually far longer than the pool running it, and a variant is only needed while
 * its contingency is being studied. Removing the variant returns its slot, so a handful of reserved slots
 * serve an arbitrarily long list with no storage growth at all inside the parallel region — which is the only
 * thing reserving buys, since a clone past the reservation now grows rather than failing.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantSlotPoolTest {
    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network chain(int size) {
        Network n = Network.create("pool", "test");
        for (int i = 0; i < size; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
            vl.newLoad().setId("LD" + i).setBus("B" + i).setConnectableBus("B" + i).setP0(10).setQ0(0).add();
        }
        for (int i = 0; i < size - 1; i++) {
            n.newLine().setId("L" + i).setVoltageLevel1("VL" + i).setBus1("B" + i).setConnectableBus1("B" + i)
                .setVoltageLevel2("VL" + (i + 1)).setBus2("B" + (i + 1)).setConnectableBus2("B" + (i + 1))
                .setR(0.1).setX(1).setG1(0).setB1(0).setG2(0).setB2(0).add();
        }
        return n;
    }

    @Test
    void slotPoolSizedByThreadsNotContingencies() throws Exception {
        int threads = 4;
        int contingencies = 40;              // ten times the pool
        Network network = chain(contingencies + 1);
        VariantManager variants = network.getVariantManager();

        variants.preAllocateVariants(threads);          // one slot per worker, not per contingency
        variants.allowVariantMultiThreadAccess(true);
        int sizeAfterReserve = ((VariantManagerImpl) variants).getVariantArraySize();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int c = 0; c < contingencies; c++) {
            final int k = c;
            fs.add(pool.submit(() -> {
                String v = "cont-" + k;
                variants.cloneVariant(INITIAL, v);
                try {
                    variants.setWorkingVariant(v);
                    network.getLine("L" + k).remove();
                    assertNull(network.getLine("L" + k));
                    assertEquals(contingencies - 1, network.getLineCount());
                } finally {
                    variants.setWorkingVariant(INITIAL);
                    variants.removeVariant(v);          // hands the slot back to the pool
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        int sizeAtEnd = ((VariantManagerImpl) variants).getVariantArraySize();
        variants.allowVariantMultiThreadAccess(false);
        variants.setWorkingVariant(INITIAL);
        assertEquals(contingencies, network.getLineCount());
        System.out.println("  POOL: reserved=" + threads + " slots, ran " + contingencies
                + " contingencies, variantArraySize " + sizeAfterReserve + " -> " + sizeAtEnd
                + ", base intact with " + network.getLineCount() + " lines");
    }
}

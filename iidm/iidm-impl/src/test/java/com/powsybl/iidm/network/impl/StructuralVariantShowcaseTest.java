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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the variant work makes possible, as a worked example: a parallel N-1 analysis where each contingency
 * is a <em>structural</em> change — the line is really removed — applied to <b>one shared network</b>.
 *
 * <p>None of this ran before. A clone copied state only, so the equipment set was shared by every variant and
 * {@code line.remove()} removed it everywhere; the only ways to run N-1 in parallel were to deep-copy the
 * network per worker, or to apply and undo contingencies one at a time. And variant creation was main-thread
 * only, so the variants had to be created up front even when the contingency list was not known yet.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class StructuralVariantShowcaseTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    /** A chain of {@code size} voltage levels, one line between each consecutive pair, one load on each. */
    private static Network chain(int size) {
        Network n = Network.create("showcase", "test");
        for (int i = 0; i < size; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
            vl.newLoad().setId("LD" + i).setBus("B" + i).setConnectableBus("B" + i).setP0(10).setQ0(0).add();
        }
        for (int i = 0; i < size - 1; i++) {
            n.newLine().setId("L" + i)
                    .setVoltageLevel1("VL" + i).setBus1("B" + i).setConnectableBus1("B" + i)
                    .setVoltageLevel2("VL" + (i + 1)).setBus2("B" + (i + 1)).setConnectableBus2("B" + (i + 1))
                    .setR(0.1).setX(1).setG1(0).setB1(0).setG2(0).setB2(0).add();
        }
        return n;
    }

    @Test
    void parallelStructuralNMinus1OnASingleSharedNetwork() {
        Network network = chain(13);                       // 13 voltage levels, 12 lines
        VariantManager variants = network.getVariantManager();

        // no preAllocateVariants: the contingency list drives how many variants exist, and each worker
        // creates its own when it gets there
        variants.allowVariantMultiThreadAccess(true);

        List<String> contingencies = IntStream.range(0, 12).mapToObj(i -> "L" + i).toList();
        Map<String, String> report = new ConcurrentHashMap<>();

        contingencies.parallelStream().forEach(line -> {
            // ---- everything below runs on a worker thread, against the one shared network ----
            variants.cloneVariant(INITIAL, line);          // created on demand, from this thread
            variants.setWorkingVariant(line);

            network.getLine(line).remove();                // STRUCTURAL: the line is gone, in this variant only
            network.getLoad("LD0").setP0(42.0);            // state, as before

            // this worker sees its own contingency and nobody else's
            assertNull(network.getLine(line));
            assertEquals(11, network.getLineCount());
            assertEquals(42.0, network.getLoad("LD0").getP0(), 0.0);

            report.put(line, "removed " + line + ", " + network.getLineCount() + " lines left, "
                    + network.getBusView().getBusStream().count() + " buses in view");
        });

        // every variant kept exactly its own divergence
        for (String line : contingencies) {
            variants.setWorkingVariant(line);
            assertNull(network.getLine(line), line + " should be gone in its own variant");
            assertEquals(11, network.getLineCount());
            assertNotNull(report.get(line));
        }

        // ...and the shared base variant never changed
        variants.setWorkingVariant(INITIAL);
        assertEquals(12, network.getLineCount());
        assertEquals(10.0, network.getLoad("LD0").getP0(), 0.0);
        for (String line : contingencies) {
            assertNotNull(network.getLine(line), line + " must still be there in the base");
        }

        report.keySet().stream().sorted().forEach(k -> System.out.println("  " + report.get(k)));
        System.out.println("  base variant: " + network.getLineCount() + " lines, LD0.p0="
                + network.getLoad("LD0").getP0());
    }

    /** The same, with each worker also <em>adding</em> equipment that exists only in its own variant. */
    @Test
    void workersCanAlsoCreateEquipmentScopedToTheirOwnVariant() {
        Network network = chain(9);
        VariantManager variants = network.getVariantManager();
        variants.allowVariantMultiThreadAccess(true);

        IntStream.range(0, 8).parallel().forEach(k -> {
            variants.cloneVariant(INITIAL, "remedial-" + k);
            variants.setWorkingVariant("remedial-" + k);

            network.getLine("L" + k).remove();                          // the contingency
            network.getVoltageLevel("VL" + k).newGenerator()            // the remedial action
                    .setId("BACKUP" + k).setBus("B" + k).setConnectableBus("B" + k)
                    .setMinP(0).setMaxP(100).setTargetP(50).setTargetQ(0)
                    .setVoltageRegulatorOn(false).add();

            assertNotNull(network.getGenerator("BACKUP" + k));
            for (int j = 0; j < 8; j++) {
                if (j != k) {
                    assertNull(network.getGenerator("BACKUP" + j), "saw another worker's generator");
                }
            }
        });

        variants.setWorkingVariant(INITIAL);
        assertEquals(0, network.getGeneratorCount());  // none of them leaked into the base
        assertEquals(8, network.getLineCount());
        System.out.println("  base variant: " + network.getGeneratorCount() + " generators, "
                + network.getLineCount() + " lines — untouched by 8 parallel remedial-action studies");
    }
}

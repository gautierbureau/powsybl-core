/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.structuralvariant;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real OpenLoadFlow load flows, in parallel, one per {@code STRUCTURAL} variant of ONE shared network —
 * the expansion-study pattern the copy-on-write structural variants are built for. Each variant
 * <b>adds a new generator and a new shortcut line</b> to a shared chain grid (structural setup on the
 * main thread, per the {@link VariantManager} contract); then one worker thread per variant runs
 * {@code LoadFlow.find("OpenLoadFlow").run(network, variantId, ...)} concurrently with the others.
 *
 * <p>Verified afterwards: every run fully converged; every variant's added shortcut line actually carries
 * power (so OLF really solved the variant's own expanded topology); a sequential OLF re-run of each
 * variant reproduces the parallel results (no cross-variant interference); sibling expansions stay
 * invisible to each other; and the base variant comes out untouched (no results written, no added
 * equipment).</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class ParallelStructuralVariantOpenLoadFlowTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;
    private static final int BUSES = 12;
    private static final int VARIANTS = 8;

    private static Network chainGrid() {
        Network n = Network.create("olf-chain", "test");
        for (int i = 0; i < BUSES; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
            if (i == 0) {
                vl.newGenerator().setId("GEN0").setBus("B0").setConnectableBus("B0")
                        .setMinP(0).setMaxP(1000).setTargetP(110).setTargetV(400)
                        .setVoltageRegulatorOn(true).add();
            } else {
                vl.newLoad().setId("LOAD" + i).setBus("B" + i).setConnectableBus("B" + i)
                        .setP0(10).setQ0(0).add();
            }
        }
        for (int i = 0; i < BUSES - 1; i++) {
            newLine(n, "L" + i, i, i + 1, 4.0);
        }
        return n;
    }

    private static void newLine(Network n, String id, int bus1, int bus2, double x) {
        n.newLine().setId(id)
                .setVoltageLevel1("VL" + bus1).setBus1("B" + bus1).setConnectableBus1("B" + bus1)
                .setVoltageLevel2("VL" + bus2).setBus2("B" + bus2).setConnectableBus2("B" + bus2)
                .setR(0.1).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    // Structural delta of variant k, applied on the MAIN thread: a new generator and a new shortcut line.
    private static void applyExpansion(Network n, int k) {
        int genBus = 1 + (k * 3) % (BUSES - 1);
        int lineFrom = 1 + k % (BUSES - 3);
        n.getVoltageLevel("VL" + genBus).newGenerator().setId("XGEN" + k)
                .setBus("B" + genBus).setConnectableBus("B" + genBus)
                .setMinP(0).setMaxP(100).setTargetP(30).setTargetQ(0).setVoltageRegulatorOn(false).add();
        newLine(n, "XLINE" + k, lineFrom, lineFrom + 2, 2.0);
    }

    @RepeatedTest(3)
    void parallelOpenLoadFlowsOnStructuralExpansionVariants() throws InterruptedException {
        Network n = chainGrid();
        VariantManager vm = n.getVariantManager();

        // --- main thread: fork the structural variants and apply their expansions (structural ops) ---
        for (int k = 0; k < VARIANTS; k++) {
            vm.cloneVariant(INITIAL, "v" + k, VariantCloneStrategy.STRUCTURAL);
            vm.setWorkingVariant("v" + k);
            applyExpansion(n, k);
        }
        vm.setWorkingVariant(INITIAL);
        vm.allowVariantMultiThreadAccess(true);

        LoadFlow.Runner olf = LoadFlow.find("OpenLoadFlow");
        LoadFlowParameters parameters = new LoadFlowParameters().setDc(true);

        // --- parallel phase: one real OpenLoadFlow run per variant, all concurrent ---
        LoadFlowResult[] results = new LoadFlowResult[VARIANTS];
        CyclicBarrier start = new CyclicBarrier(VARIANTS);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int k = 0; k < VARIANTS; k++) {
            int w = k;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    results[w] = olf.run(n, "v" + w, LocalComputationManager.getDefault(), parameters);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add(e);
                } catch (BrokenBarrierException e) {
                    failures.add(e);
                }
            });
            worker.setUncaughtExceptionHandler((t, e) -> failures.add(e));
            workers.add(worker);
        }
        workers.forEach(Thread::start);
        for (Thread t : workers) {
            t.join();
        }
        assertTrue(failures.isEmpty(), () -> "worker failed: " + failures.get(0));

        // --- every parallel run converged and solved ITS OWN expanded topology ---
        Map<String, Double> parallelFlows = new HashMap<>();
        for (int k = 0; k < VARIANTS; k++) {
            assertTrue(results[k].isFullyConverged(), "load flow on v" + k + " did not converge");
            vm.setWorkingVariant("v" + k);
            assertEquals(BUSES, n.getLineCount());      // (BUSES - 1) chain lines + this variant's shortcut
            assertEquals(2, n.getGeneratorCount());     // GEN0 + this variant's XGEN
            Line xline = n.getLine("XLINE" + k);
            double xflow = xline.getTerminal1().getP();
            assertFalse(Double.isNaN(xflow), "no flow written on XLINE" + k);
            assertTrue(Math.abs(xflow) > 1e-3, "the added shortcut of v" + k + " carries no power");
            for (Line l : n.getLines()) {
                parallelFlows.put(k + ":" + l.getId(), l.getTerminal1().getP());
            }
            int sibling = (k + 1) % VARIANTS;
            assertNull(n.getLine("XLINE" + sibling));   // sibling expansions stay invisible
            assertNull(n.getGenerator("XGEN" + sibling));
        }

        // --- a sequential re-run reproduces the parallel results: no cross-variant interference ---
        for (int k = 0; k < VARIANTS; k++) {
            LoadFlowResult rerun = olf.run(n, "v" + k, LocalComputationManager.getDefault(), parameters);
            assertTrue(rerun.isFullyConverged());
            vm.setWorkingVariant("v" + k);
            for (Line l : n.getLines()) {
                assertEquals(parallelFlows.get(k + ":" + l.getId()), l.getTerminal1().getP(), 1e-9,
                        "flow on " + l.getId() + " differs between parallel and sequential run of v" + k);
            }
        }

        // --- the base variant was never touched by any load flow ---
        vm.setWorkingVariant(INITIAL);
        assertEquals(BUSES - 1, n.getLineCount());
        assertEquals(1, n.getGeneratorCount());
        for (int k = 0; k < VARIANTS; k++) {
            assertNull(n.getLine("XLINE" + k));
            assertNull(n.getGenerator("XGEN" + k));
        }
        assertTrue(Double.isNaN(n.getLine("L0").getTerminal1().getP()));
        assertTrue(Double.isNaN(n.getBusBreakerView().getBus("B1").getAngle()));

        vm.allowVariantMultiThreadAccess(false);
    }
}

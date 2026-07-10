/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The load-flow-shaped concurrency contract of structural variants: each of K worker threads owns one
 * {@code STRUCTURAL} variant that <em>adds a new generator and a new line</em> to a shared base grid, and
 * runs a small DC load flow on it — enumerating the lines/generators <b>visible in its variant</b> to build
 * the susceptance matrix, solving for angles, and writing a full result band (bus angles and voltages,
 * terminal p/q on every line) concurrently with the other workers.
 *
 * <p>This is the pattern a parallel N-k / expansion study with a real load-flow engine follows: structural
 * setup on the main thread (per the {@link VariantManager} contract), then workers that only read shared
 * ancestors and read/write their own variant. It must hold because worker writes materialise rows only in
 * the worker's own copy-on-write band, in a band table pre-sized on the main thread — no worker ever
 * touches another variant's band (the base variant is not written during the parallel phase, so
 * freeze-on-parent-write never fires cross-thread).</p>
 *
 * <p>Repeated to widen the race window; results are verified against a sequential recomputation of the
 * same solver on each variant (any cross-variant leak, lost write, or mis-scoped enumeration breaks the
 * exact equality), and the base variant is verified untouched.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class StructuralVariantParallelDcLoadFlowTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;
    private static final int BUSES = 12;
    private static final int VARIANTS = 8;
    private static final double SLACK_TARGET_P = 11.0; // balances the 11 unit loads of the base chain

    // --- the grid: a chain of buses, a slack generator at bus 0, a 1 MW load on every other bus ---

    private static Network chainGrid() {
        Network n = Network.create("dc-chain", "test");
        for (int i = 0; i < BUSES; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
            if (i == 0) {
                vl.newGenerator().setId("GEN0").setBus("B0").setConnectableBus("B0")
                        .setMinP(0).setMaxP(1000).setTargetP(SLACK_TARGET_P).setTargetV(400)
                        .setVoltageRegulatorOn(true).add();
            } else {
                vl.newLoad().setId("LOAD" + i).setBus("B" + i).setConnectableBus("B" + i)
                        .setP0(1).setQ0(0).add();
            }
        }
        for (int i = 0; i < BUSES - 1; i++) {
            newLine(n, "L" + i, i, i + 1, 1.0);
        }
        return n;
    }

    private static void newLine(Network n, String id, int bus1, int bus2, double x) {
        n.newLine().setId(id)
                .setVoltageLevel1("VL" + bus1).setBus1("B" + bus1).setConnectableBus1("B" + bus1)
                .setVoltageLevel2("VL" + bus2).setBus2("B" + bus2).setConnectableBus2("B" + bus2)
                .setR(0).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    // Structural delta of variant k, applied on the MAIN thread: a new generator and a new shortcut line.
    private static void applyExpansion(Network n, int k) {
        int genBus = 1 + (k * 3) % (BUSES - 1);
        int lineFrom = 1 + k % (BUSES - 3);
        n.getVoltageLevel("VL" + genBus).newGenerator().setId("XGEN" + k)
                .setBus("B" + genBus).setConnectableBus("B" + genBus)
                .setMinP(0).setMaxP(100).setTargetP(5).setTargetQ(0).setVoltageRegulatorOn(false).add();
        newLine(n, "XLINE" + k, lineFrom, lineFrom + 2, 0.5);
    }

    // --- a small DC load flow over whatever the ACTIVE VARIANT exposes ---

    private record DcSolution(double[] angles, Map<String, Double> flowByLine) {
    }

    private static DcSolution solveDc(Network n) {
        Map<String, Integer> busIndex = new HashMap<>();
        for (int i = 0; i < BUSES; i++) {
            busIndex.put("B" + i, i);
        }
        double[] p = new double[BUSES];
        for (Generator g : n.getGenerators()) {
            if (!"GEN0".equals(g.getId())) { // bus 0 is the slack
                p[busIndex.get(g.getTerminal().getBusBreakerView().getBus().getId())] += g.getTargetP();
            }
        }
        for (Load l : n.getLoads()) {
            p[busIndex.get(l.getTerminal().getBusBreakerView().getBus().getId())] -= l.getP0();
        }
        double[][] b = new double[BUSES][BUSES];
        List<Line> lines = new ArrayList<>();
        for (Line l : n.getLines()) { // variant-scoped: includes this variant's XLINE, nobody else's
            lines.add(l);
            int i = busIndex.get(l.getTerminal1().getBusBreakerView().getBus().getId());
            int j = busIndex.get(l.getTerminal2().getBusBreakerView().getBus().getId());
            double y = 1.0 / l.getX();
            b[i][i] += y;
            b[j][j] += y;
            b[i][j] -= y;
            b[j][i] -= y;
        }
        double[] theta = solveReduced(b, p);
        Map<String, Double> flows = new HashMap<>();
        for (Line l : lines) {
            int i = busIndex.get(l.getTerminal1().getBusBreakerView().getBus().getId());
            int j = busIndex.get(l.getTerminal2().getBusBreakerView().getBus().getId());
            flows.put(l.getId(), (theta[i] - theta[j]) / l.getX());
        }
        return new DcSolution(theta, flows);
    }

    // Solve B' theta = p with bus 0 as the slack (theta[0] = 0), by Gaussian elimination.
    private static double[] solveReduced(double[][] b, double[] p) {
        int m = BUSES - 1;
        double[][] a = new double[m][m + 1];
        for (int i = 0; i < m; i++) {
            System.arraycopy(b[i + 1], 1, a[i], 0, m);
            a[i][m] = p[i + 1];
        }
        for (int col = 0; col < m; col++) {
            int pivot = col;
            for (int r = col + 1; r < m; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (int r = col + 1; r < m; r++) {
                double f = a[r][col] / a[col][col];
                for (int c = col; c <= m; c++) {
                    a[r][c] -= f * a[col][c];
                }
            }
        }
        double[] theta = new double[BUSES];
        for (int i = m - 1; i >= 0; i--) {
            double s = a[i][m];
            for (int c = i + 1; c < m; c++) {
                s -= a[i][c] * theta[c + 1];
            }
            theta[i + 1] = s / a[i][i];
        }
        return theta;
    }

    // Write the solution into the active variant, as a load-flow engine does: every bus, every terminal.
    private static void writeResults(Network n, DcSolution sol) {
        for (int i = 0; i < BUSES; i++) {
            Bus bus = n.getBusBreakerView().getBus("B" + i);
            bus.setAngle(Math.toDegrees(sol.angles()[i]));
            bus.setV(400.0);
        }
        for (Line l : n.getLines()) {
            double f = sol.flowByLine().get(l.getId());
            l.getTerminal1().setP(f).setQ(0);
            l.getTerminal2().setP(-f).setQ(0);
        }
        for (Generator g : n.getGenerators()) {
            g.getTerminal().setP(-g.getTargetP()).setQ(0);
        }
    }

    private static void assertResults(Network n, DcSolution expected) {
        for (int i = 0; i < BUSES; i++) {
            assertEquals(Math.toDegrees(expected.angles()[i]), n.getBusBreakerView().getBus("B" + i).getAngle());
        }
        for (Line l : n.getLines()) {
            assertEquals(expected.flowByLine().get(l.getId()), l.getTerminal1().getP());
            assertEquals(-expected.flowByLine().get(l.getId()), l.getTerminal2().getP());
        }
    }

    @RepeatedTest(3)
    void parallelDcLoadFlowsOnStructuralExpansionVariants() throws InterruptedException {
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

        // --- parallel phase: one worker per variant runs the DC load flow and writes its results ---
        CyclicBarrier start = new CyclicBarrier(VARIANTS);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int k = 0; k < VARIANTS; k++) {
            int w = k;
            Thread worker = new Thread(() -> {
                try {
                    vm.setWorkingVariant("v" + w);
                    start.await();
                    // the variant sees its own expansion: base lines + XLINE, base gens + XGEN
                    assertEquals(BUSES, n.getLineCount());       // (BUSES - 1) chain lines + the shortcut
                    assertEquals(2, n.getGeneratorCount());      // GEN0 + XGEN
                    DcSolution sol = solveDc(n);
                    writeResults(n, sol);
                    // read back under concurrency, twice, to widen the race window
                    for (int round = 0; round < 2; round++) {
                        assertResults(n, sol);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add(e);
                } catch (BrokenBarrierException e) {
                    failures.add(e);
                }
            });
            worker.setUncaughtExceptionHandler((t, e) -> failures.add(e)); // assertion or runtime failures
            workers.add(worker);
        }
        workers.forEach(Thread::start);
        for (Thread t : workers) {
            t.join();
        }
        assertTrue(failures.isEmpty(), () -> "worker failed: " + failures.get(0));

        // --- main thread: verify every variant against a sequential recomputation of the same solver ---
        for (int k = 0; k < VARIANTS; k++) {
            vm.setWorkingVariant("v" + k);
            assertEquals(BUSES, n.getLineCount());
            DcSolution reference = solveDc(n);
            assertResults(n, reference);
            // the added line changes the flow pattern: the shortcut must actually carry power
            assertTrue(Math.abs(reference.flowByLine().get("XLINE" + k)) > 1e-9);
            // and no sibling expansion leaks in
            int sibling = (k + 1) % VARIANTS;
            assertNull(n.getLine("XLINE" + sibling));
            assertNull(n.getGenerator("XGEN" + sibling));
        }

        // --- the base variant was never touched by any load flow ---
        vm.setWorkingVariant(INITIAL);
        assertEquals(BUSES - 1, n.getLineCount());
        assertEquals(1, n.getGeneratorCount());
        for (int k = 0; k < VARIANTS; k++) {
            assertNull(n.getLine("XLINE" + k));
            assertNull(n.getGenerator("XGEN" + k));
        }
        assertTrue(Double.isNaN(n.getBusBreakerView().getBus("B1").getAngle()));
        assertTrue(Double.isNaN(n.getLine("L0").getTerminal1().getP()));

        vm.allowVariantMultiThreadAccess(false);
    }
}

/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.structuralvariant;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.serde.NetworkSerDe;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC</b> load flows with real OpenLoadFlow on <b>IEEE-300</b>, comparing three ways of running K
 * expansion scenarios (each adds a new generator and a new line onto the shared base grid):
 *
 * <ol>
 *   <li><b>structural / parallel</b> — K {@code STRUCTURAL} variants of ONE shared network, one AC run
 *       per variant, all threads concurrent (what the copy-on-write port is for);</li>
 *   <li><b>structural / sequential</b> — the same K variants, AC runs one after the other;</li>
 *   <li><b>copies (no structural variants)</b> — the classic baseline: K full {@code NetworkSerDe.copy}
 *       networks, each modified, AC runs sequential and parallel.</li>
 * </ol>
 *
 * <p>Beyond timing/retained-memory, the test cross-validates the physics: for every scenario k, the AC
 * solution computed on the structural variant (parallel AND sequential) must match the solution computed
 * on the equivalent full copy, branch by branch — a structural variant is numerically indistinguishable
 * from a materialised network.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class Ieee300AcStructuralVariantBenchmarkTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;
    private static final int K = 8;
    private static final double FLOW_EPS_MW = 1e-3;

    // One expansion scenario: a new generator on an existing (generator-hosting) bus, and a new line
    // between two existing buses of the same nominal voltage. Planned once on the base network by id,
    // applied identically to structural variants and to full copies.
    private record Expansion(String genBusId, String lineBus1Id, String lineBus2Id) {
    }

    private static List<Expansion> planExpansions(Network n) {
        // buses that host a generator, grouped by nominal voltage; take the biggest group so the added
        // lines connect buses of the same voltage level class (electrically sane, safely in the main grid)
        Map<Double, List<Bus>> byNominalV = new HashMap<>();
        n.getGeneratorStream()
                .map(g -> g.getTerminal().getBusBreakerView().getBus())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(b -> byNominalV.computeIfAbsent(b.getVoltageLevel().getNominalV(), v -> new ArrayList<>()).add(b));
        List<Bus> pool = byNominalV.values().stream().max(Comparator.comparingInt(List::size)).orElseThrow();
        assertTrue(pool.size() >= K + 1, "not enough same-voltage generator buses on IEEE-300");
        pool.sort(Comparator.comparing(Bus::getId));
        List<Expansion> expansions = new ArrayList<>();
        for (int k = 0; k < K; k++) {
            expansions.add(new Expansion(pool.get(k).getId(), pool.get(k).getId(), pool.get(k + 1).getId()));
        }
        return expansions;
    }

    private static void applyExpansion(Network n, int k, Expansion e) {
        Bus genBus = n.getBusBreakerView().getBus(e.genBusId());
        genBus.getVoltageLevel().newGenerator().setId("XGEN" + k)
                .setBus(e.genBusId()).setConnectableBus(e.genBusId())
                .setMinP(0).setMaxP(100).setTargetP(10).setTargetQ(0).setVoltageRegulatorOn(false).add();
        Bus b1 = n.getBusBreakerView().getBus(e.lineBus1Id());
        Bus b2 = n.getBusBreakerView().getBus(e.lineBus2Id());
        n.newLine().setId("XLINE" + k)
                .setVoltageLevel1(b1.getVoltageLevel().getId()).setBus1(b1.getId()).setConnectableBus1(b1.getId())
                .setVoltageLevel2(b2.getVoltageLevel().getId()).setBus2(b2.getId()).setConnectableBus2(b2.getId())
                .setR(1).setX(15).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static LoadFlowParameters acParameters() {
        return new LoadFlowParameters(); // plain AC, OLF defaults
    }

    private record Run(double setupMs, double loadFlowMs, long retainedBytes, Map<String, Double> flows) {
    }

    // flows key: "k:lineId" -> p1, captured for every line of every scenario
    private static void captureFlows(Network n, int k, Map<String, Double> flows) {
        for (Line l : n.getLines()) {
            flows.put(k + ":" + l.getId(), l.getTerminal1().getP());
        }
    }

    private static Run structural(boolean parallel) throws InterruptedException {
        long m0 = usedMemory();
        Network n = IeeeCdfNetworkFactory.create300();
        List<Expansion> expansions = planExpansions(n);
        VariantManager vm = n.getVariantManager();

        long t0 = System.nanoTime();
        for (int k = 0; k < K; k++) {
            vm.cloneVariant(INITIAL, "v" + k, VariantCloneStrategy.STRUCTURAL);
            vm.setWorkingVariant("v" + k);
            applyExpansion(n, k, expansions.get(k));
        }
        vm.setWorkingVariant(INITIAL);
        double setupMs = (System.nanoTime() - t0) / 1e6;

        LoadFlow.Runner olf = LoadFlow.find("OpenLoadFlow");
        LoadFlowParameters parameters = acParameters();
        LoadFlowResult[] results = new LoadFlowResult[K];
        long t1 = System.nanoTime();
        if (parallel) {
            vm.allowVariantMultiThreadAccess(true);
            CyclicBarrier start = new CyclicBarrier(K);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int k = 0; k < K; k++) {
                int w = k;
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        results[w] = olf.run(n, "v" + w, LocalComputationManager.getDefault(), parameters);
                    } catch (InterruptedException | java.util.concurrent.BrokenBarrierException e) {
                        failures.add(e);
                    }
                });
                t.setUncaughtExceptionHandler((th, e) -> failures.add(e));
                threads.add(t);
            }
            threads.forEach(Thread::start);
            for (Thread t : threads) {
                t.join();
            }
            assertTrue(failures.isEmpty(), () -> "worker failed: " + failures.get(0));
        } else {
            for (int k = 0; k < K; k++) {
                results[k] = olf.run(n, "v" + k, LocalComputationManager.getDefault(), parameters);
            }
        }
        double loadFlowMs = (System.nanoTime() - t1) / 1e6;
        if (parallel) {
            vm.allowVariantMultiThreadAccess(false);
        }

        Map<String, Double> flows = new HashMap<>();
        for (int k = 0; k < K; k++) {
            assertTrue(results[k].isFullyConverged(), "AC on structural variant v" + k + " did not converge");
            vm.setWorkingVariant("v" + k);
            captureFlows(n, k, flows);
        }
        long retained = Math.max(0, usedMemory() - m0);
        blackhole(n);
        return new Run(setupMs, loadFlowMs, retained, flows);
    }

    private static Run copies(boolean parallel) throws InterruptedException {
        long m0 = usedMemory();
        Network base = IeeeCdfNetworkFactory.create300();
        List<Expansion> expansions = planExpansions(base);

        long t0 = System.nanoTime();
        List<Network> networks = new ArrayList<>();
        for (int k = 0; k < K; k++) {
            Network copy = NetworkSerDe.copy(base);
            applyExpansion(copy, k, expansions.get(k));
            networks.add(copy);
        }
        double setupMs = (System.nanoTime() - t0) / 1e6;

        LoadFlow.Runner olf = LoadFlow.find("OpenLoadFlow");
        LoadFlowParameters parameters = acParameters();
        LoadFlowResult[] results = new LoadFlowResult[K];
        long t1 = System.nanoTime();
        if (parallel) {
            CyclicBarrier start = new CyclicBarrier(K);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int k = 0; k < K; k++) {
                int w = k;
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        results[w] = olf.run(networks.get(w), LocalComputationManager.getDefault(), parameters);
                    } catch (InterruptedException | java.util.concurrent.BrokenBarrierException e) {
                        failures.add(e);
                    }
                });
                t.setUncaughtExceptionHandler((th, e) -> failures.add(e));
                threads.add(t);
            }
            threads.forEach(Thread::start);
            for (Thread t : threads) {
                t.join();
            }
            assertTrue(failures.isEmpty(), () -> "worker failed: " + failures.get(0));
        } else {
            for (int k = 0; k < K; k++) {
                results[k] = olf.run(networks.get(k), LocalComputationManager.getDefault(), parameters);
            }
        }
        double loadFlowMs = (System.nanoTime() - t1) / 1e6;

        Map<String, Double> flows = new HashMap<>();
        for (int k = 0; k < K; k++) {
            assertTrue(results[k].isFullyConverged(), "AC on copy " + k + " did not converge");
            captureFlows(networks.get(k), k, flows);
        }
        long retained = Math.max(0, usedMemory() - m0);
        blackhole(networks);
        return new Run(setupMs, loadFlowMs, retained, flows);
    }

    @Test
    void acOnIeee300StructuralVariantsVsCopies() throws InterruptedException {
        // warm-up: JIT the whole pipeline (IIDM build, OLF AC, serde copy) before measuring
        structural(true);
        copies(false);

        Map<String, Run> runs = new LinkedHashMap<>();
        runs.put("structural / parallel  ", structural(true));
        runs.put("structural / sequential", structural(false));
        runs.put("copies     / parallel  ", copies(true));
        runs.put("copies     / sequential", copies(false));

        System.out.println();
        System.out.printf("AC load flow, OpenLoadFlow, IEEE-300, %d expansion scenarios (each: +1 generator, +1 line)%n", K);
        System.out.printf("%-24s | %10s | %12s | %12s%n", "approach", "setup (ms)", "AC runs (ms)", "retained (MB)");
        System.out.println("-".repeat(70));
        runs.forEach((name, r) -> System.out.printf("%-24s | %10.1f | %12.1f | %12.2f%n",
                name, r.setupMs(), r.loadFlowMs(), r.retainedBytes() / 1e6));

        // cross-validation: for each scenario, the AC solution on the structural variant equals the AC
        // solution on the equivalent full copy, branch by branch — parallel and sequential alike
        Map<String, Double> reference = runs.get("copies     / sequential").flows();
        for (Map.Entry<String, Run> e : runs.entrySet()) {
            Map<String, Double> flows = e.getValue().flows();
            assertEquals(reference.size(), flows.size(), e.getKey() + ": branch set differs");
            for (Map.Entry<String, Double> f : reference.entrySet()) {
                double actual = flows.get(f.getKey());
                if (Double.isNaN(f.getValue())) {
                    assertTrue(Double.isNaN(actual), e.getKey() + ": " + f.getKey() + " expected NaN");
                } else {
                    assertEquals(f.getValue(), actual, FLOW_EPS_MW,
                            e.getKey() + ": flow differs on " + f.getKey());
                }
            }
        }
    }

    private static long usedMemory() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @SuppressWarnings("unused")
    private static volatile Object sink;

    private static void blackhole(Object o) {
        sink = o;
    }
}

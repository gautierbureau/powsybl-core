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
import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>AC</b> load flows with real OpenLoadFlow, comparing three ways of running K expansion scenarios
 * (each adds a new generator and a new line onto the shared base grid):
 *
 * <ol>
 *   <li><b>structural / parallel</b> — K {@code STRUCTURAL} variants of ONE shared network, one AC run
 *       per variant, all threads concurrent (what the copy-on-write port is for);</li>
 *   <li><b>structural / sequential</b> — the same K variants, AC runs one after the other;</li>
 *   <li><b>copies (no structural variants)</b> — the classic baseline: K full {@code NetworkSerDe.copy}
 *       networks, each modified, AC runs sequential and parallel.</li>
 * </ol>
 *
 * <p>Cases: IEEE-300 (bundled) and the MATPOWER PEGASE 9241 / 13659 cases (downloaded and cached by
 * {@link MatpowerCases}; those tests are skipped offline). Beyond timing/retained-memory, every case
 * cross-validates the physics: for each scenario k the AC solution on the structural variant (parallel
 * AND sequential) must match the solution on the equivalent full copy, branch by branch.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class AcStructuralVariantBenchmarkTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;
    private static final int K = 8;

    @Test
    void ieee300() throws InterruptedException {
        benchmark("IEEE-300", IeeeCdfNetworkFactory::create300, LoadFlowParameters::new, 1e-3);
    }

    @Test
    void pegase9241() throws InterruptedException {
        assumeTrue(MatpowerCases.load("case9241pegase") != null, "case9241pegase not downloadable (offline?)");
        benchmark("PEGASE 9241", () -> MatpowerCases.load("case9241pegase"), LoadFlowParameters::new, 0.1);
    }

    @Test
    void pegase13659() throws InterruptedException {
        assumeTrue(MatpowerCases.load("case13659pegase") != null, "case13659pegase not downloadable (offline?)");
        // this stressed winter-peak case is fragile from a cold start (its buses all import with the
        // fictitious 1 kV base the data ships); start Newton-Raphson from the case's solved voltages
        benchmark("PEGASE 13659", () -> MatpowerCases.load("case13659pegase"),
                () -> new LoadFlowParameters().setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES), 0.1);
    }

    // One expansion scenario: a new generator on an existing (generator-hosting) bus, and a second
    // circuit doubling an existing line (a classic grid reinforcement — electrically benign on any case).
    // Planned once on the base network by id, applied identically to structural variants and full copies.
    private record Expansion(String genBusId, String reinforcedLineId) {
    }

    private static List<Expansion> planExpansions(Network n) {
        // buses that host a connected generator, grouped by nominal voltage; expand at the highest voltage
        // class that has enough of them (the transmission grid)
        Map<Double, List<Bus>> byNominalV = new HashMap<>();
        n.getGeneratorStream()
                .map(g -> g.getTerminal().getBusBreakerView().getBus())
                .filter(Objects::nonNull)
                .distinct()
                .forEach(b -> byNominalV.computeIfAbsent(b.getVoltageLevel().getNominalV(), v -> new ArrayList<>()).add(b));
        List<Bus> pool = byNominalV.entrySet().stream()
                .filter(e -> e.getValue().size() >= K)
                .max(Comparator.comparingDouble(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .orElseThrow();
        pool.sort(Comparator.comparing(Bus::getId));
        double nominalV = pool.get(0).getVoltageLevel().getNominalV();
        // K existing, connected lines of that voltage class, each to be doubled by one scenario. Exclude
        // near-zero-impedance jumpers (doubling one creates a degenerate loop) and spread the picks across
        // the grid instead of clustering on the first ids.
        double zBase = nominalV * nominalV / 100.0;
        List<Line> candidates = n.getLineStream()
                .filter(l -> l.getTerminal1().getVoltageLevel().getNominalV() == nominalV
                        && l.getTerminal1().getBusBreakerView().getBus() != null
                        && l.getTerminal2().getBusBreakerView().getBus() != null
                        && l.getX() > 0.003 * zBase)
                .sorted(Comparator.comparing(Line::getId))
                .toList();
        assertTrue(candidates.size() >= K, "not enough lines at the chosen voltage class");
        List<Expansion> expansions = new ArrayList<>();
        int stride = candidates.size() / K;
        for (int k = 0; k < K; k++) {
            expansions.add(new Expansion(pool.get(k).getId(), candidates.get(k * stride).getId()));
        }
        return expansions;
    }

    private static void applyExpansion(Network n, int k, Expansion e) {
        Bus genBus = n.getBusBreakerView().getBus(e.genBusId());
        genBus.getVoltageLevel().newGenerator().setId("XGEN" + k)
                .setBus(e.genBusId()).setConnectableBus(e.genBusId())
                .setMinP(0).setMaxP(100).setTargetP(10).setTargetQ(0).setVoltageRegulatorOn(false).add();
        Line reinforced = n.getLine(e.reinforcedLineId());
        Bus b1 = reinforced.getTerminal1().getBusBreakerView().getBus();
        Bus b2 = reinforced.getTerminal2().getBusBreakerView().getBus();
        n.newLine().setId("XLINE" + k)
                .setVoltageLevel1(b1.getVoltageLevel().getId()).setBus1(b1.getId()).setConnectableBus1(b1.getId())
                .setVoltageLevel2(b2.getVoltageLevel().getId()).setBus2(b2.getId()).setConnectableBus2(b2.getId())
                .setR(reinforced.getR()).setX(reinforced.getX()).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private record Run(double setupMs, double loadFlowMs, long retainedBytes, Map<String, Double> flows) {
    }

    // flows key: "k:lineId" -> p1, captured for every line of every scenario
    private static void captureFlows(Network n, int k, Map<String, Double> flows) {
        for (Line l : n.getLines()) {
            flows.put(k + ":" + l.getId(), l.getTerminal1().getP());
        }
    }

    private void benchmark(String title, Supplier<Network> networkSupplier, Supplier<LoadFlowParameters> parameters,
                           double flowEpsMw) throws InterruptedException {
        // warm-up: JIT the whole pipeline (IIDM build, OLF AC, serde copy) before measuring
        structural(networkSupplier, parameters, true);
        copies(networkSupplier, parameters, false);

        Map<String, Run> runs = new LinkedHashMap<>();
        runs.put("structural / parallel  ", structural(networkSupplier, parameters, true));
        runs.put("structural / sequential", structural(networkSupplier, parameters, false));
        runs.put("copies     / parallel  ", copies(networkSupplier, parameters, true));
        runs.put("copies     / sequential", copies(networkSupplier, parameters, false));

        System.out.println();
        System.out.printf("AC load flow, OpenLoadFlow, %s, %d expansion scenarios (each: +1 generator, +1 line)%n", title, K);
        System.out.printf("%-24s | %10s | %12s | %9s | %13s%n", "approach", "setup (ms)", "AC runs (ms)", "total (s)", "retained (MB)");
        System.out.println("-".repeat(84));
        runs.forEach((name, r) -> System.out.printf("%-24s | %10.1f | %12.1f | %9.2f | %13.2f%n",
                name, r.setupMs(), r.loadFlowMs(), (r.setupMs() + r.loadFlowMs()) / 1e3, r.retainedBytes() / 1e6));

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
                    assertEquals(f.getValue(), actual, flowEpsMw, e.getKey() + ": flow differs on " + f.getKey());
                }
            }
        }
    }

    private static Run structural(Supplier<Network> networkSupplier, Supplier<LoadFlowParameters> parameters, boolean parallel) throws InterruptedException {
        long m0 = usedMemory();
        Network n = networkSupplier.get();
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

        LoadFlowResult[] results = new LoadFlowResult[K];
        long t1 = System.nanoTime();
        if (parallel) {
            vm.allowVariantMultiThreadAccess(true);
            runParallel(w -> results[w] = LoadFlow.find("OpenLoadFlow")
                    .run(n, "v" + w, LocalComputationManager.getDefault(), parameters.get()));
            vm.allowVariantMultiThreadAccess(false);
        } else {
            for (int k = 0; k < K; k++) {
                results[k] = LoadFlow.find("OpenLoadFlow")
                        .run(n, "v" + k, LocalComputationManager.getDefault(), parameters.get());
            }
        }
        double loadFlowMs = (System.nanoTime() - t1) / 1e6;

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

    private static Run copies(Supplier<Network> networkSupplier, Supplier<LoadFlowParameters> parameters, boolean parallel) throws InterruptedException {
        long m0 = usedMemory();
        Network base = networkSupplier.get();
        List<Expansion> expansions = planExpansions(base);

        long t0 = System.nanoTime();
        List<Network> networks = new ArrayList<>();
        for (int k = 0; k < K; k++) {
            Network copy = NetworkSerDe.copy(base);
            applyExpansion(copy, k, expansions.get(k));
            networks.add(copy);
        }
        double setupMs = (System.nanoTime() - t0) / 1e6;

        LoadFlowResult[] results = new LoadFlowResult[K];
        long t1 = System.nanoTime();
        if (parallel) {
            runParallel(w -> results[w] = LoadFlow.find("OpenLoadFlow")
                    .run(networks.get(w), LocalComputationManager.getDefault(), parameters.get()));
        } else {
            for (int k = 0; k < K; k++) {
                results[k] = LoadFlow.find("OpenLoadFlow")
                        .run(networks.get(k), LocalComputationManager.getDefault(), parameters.get());
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

    private interface Worker {
        void run(int index);
    }

    private static void runParallel(Worker worker) throws InterruptedException {
        CyclicBarrier start = new CyclicBarrier(K);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int k = 0; k < K; k++) {
            int w = k;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    worker.run(w);
                } catch (InterruptedException | BrokenBarrierException e) {
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

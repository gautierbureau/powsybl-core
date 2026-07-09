/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>Spike v2.1a — measurement harness (not a CI gate).</b> Quantifies the payoff of a variant-scoped
 * structural split ({@link VariantScopedLineSplit}, a cloned variant + small delta on one network)
 * against the classic fault-on-line approach ({@code NetworkSerDe.copy(base)} + split on the copy, i.e.
 * a whole second network). Lives in {@code iidm-serde} (which has both {@code NetworkSerDe} and the
 * spike classes on its classpath) in a split package so it can reach the package-private spike API.
 *
 * <p>Gated behind {@code -Dbenchmark=true} so it is skipped in normal builds. Run with:
 * {@code mvn -pl iidm/iidm-serde test -Dtest=VariantScopedSplitBenchmarkTest -Dbenchmark=true}.</p>
 *
 * @author Claude
 */
class VariantScopedSplitBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantScopedSplitBenchmarkTest.class);
    private static final int[] SIZES = {1_000, 4_000, 10_000};
    private static final int WARMUP = 5;
    private static final int RUNS = 11;

    @Test
    void compareVariantScopedSplitToFullCopy() {
        assumeTrue(Boolean.getBoolean("benchmark"), "measurement harness; enable with -Dbenchmark=true");

        LOGGER.info("size | copy(ms) | variant(ms) | time x | copy(MB) | variant(MB) | mem x");
        LOGGER.info("-----+----------+-------------+--------+----------+-------------+------");
        for (int size : SIZES) {
            // --- warm up both paths (JIT) ---
            for (int i = 0; i < WARMUP; i++) {
                Network warm = buildChain(size);
                Network c = NetworkSerDe.copy(warm);
                applyCopySplit(c);
                applyVariantSplit(buildChain(size));
            }

            // --- time (median of RUNS; fresh base each run) ---
            double copyMs = medianMs(() -> {
                Network base = buildChain(size);
                long t0 = System.nanoTime();
                Network copy = NetworkSerDe.copy(base);
                applyCopySplit(copy);
                long dt = System.nanoTime() - t0;
                blackhole(copy);
                return dt;
            });
            double variantMs = medianMs(() -> {
                Network base = buildChain(size);
                long t0 = System.nanoTime();
                applyVariantSplit(base);
                long dt = System.nanoTime() - t0;
                blackhole(base);
                return dt;
            });

            // --- retained memory (single shot; keep the result referenced across the measurement) ---
            long copyMem = measureRetained(size, true);
            long variantMem = measureRetained(size, false);

            LOGGER.info(String.format("%5d | %8.1f | %11.1f | %5.1fx | %8.2f | %11.2f | %4.1fx",
                    size, copyMs, variantMs, copyMs / variantMs,
                    copyMem / 1e6, variantMem / 1e6, (double) copyMem / variantMem));

            // sanity: the variant delta must be materially smaller than a whole second network
            assertTrue(variantMem < copyMem, "variant-scoped split should retain less than a full copy");
        }
    }

    /** Retained heap of a full copy+split (a whole second network) vs a variant-scoped split (a delta). */
    private long measureRetained(int size, boolean fullCopy) {
        Network base = buildChain(size);
        long before = usedMemory();
        Object result;
        if (fullCopy) {
            Network copy = NetworkSerDe.copy(base);
            applyCopySplit(copy);
            result = copy;
        } else {
            applyVariantSplit(base);
            result = base;
        }
        long after = usedMemory();
        blackhole(result);
        blackhole(base);
        return Math.max(0, after - before);
    }

    @Test
    void compareManyContingenciesToManyCopies() {
        // The real use case: N-1 contingency analysis. K structural variants on ONE shared network vs K
        // full copies. The structural network shares the base object graph once; copies duplicate it K
        // times. (Storage is still eager per-variant state; Phase 2's columnar copy-on-write would remove
        // the remaining per-variant state duplication too.)
        assumeTrue(Boolean.getBoolean("benchmark"), "measurement harness; enable with -Dbenchmark=true");

        int size = 4_000;
        int k = 40;
        LOGGER.info("contingencies={} on a {}-line network", k, size);
        LOGGER.info("approach   | time(ms) | retained(MB)");
        LOGGER.info("-----------+----------+-------------");

        // warm up
        contingenciesAsStructuralVariants(buildChain(size), Math.min(k, 5));
        contingenciesAsCopies(buildChain(size), Math.min(k, 5));

        Network base1 = buildChain(size);
        long m0 = usedMemory();
        long t0 = System.nanoTime();
        Network structural = contingenciesAsStructuralVariants(base1, k);
        double structuralMs = (System.nanoTime() - t0) / 1e6;
        long structuralMem = Math.max(0, usedMemory() - m0);
        blackhole(structural);

        Network base2 = buildChain(size);
        long m1 = usedMemory();
        long t1 = System.nanoTime();
        List<Network> copies = contingenciesAsCopies(base2, k);
        double copiesMs = (System.nanoTime() - t1) / 1e6;
        long copiesMem = Math.max(0, usedMemory() - m1);
        blackhole(copies);
        blackhole(base2);

        LOGGER.info(String.format("structural | %8.1f | %11.2f", structuralMs, structuralMem / 1e6));
        LOGGER.info(String.format("copies     | %8.1f | %11.2f", copiesMs, copiesMem / 1e6));
        LOGGER.info(String.format("            speed-up %.1fx, memory %.1fx", copiesMs / structuralMs,
                (double) copiesMem / structuralMem));
        assertTrue(structuralMem < copiesMem, "K structural variants should retain less than K copies");
    }

    /** K contingencies as structural variants of one network (each removes a different line). */
    private static Network contingenciesAsStructuralVariants(Network base, int k) {
        VariantManager vm = base.getVariantManager();
        for (int i = 0; i < k; i++) {
            vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "c" + i, VariantCloneStrategy.STRUCTURAL);
            vm.setWorkingVariant("c" + i);
            base.getLine("L" + i).remove();
        }
        vm.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        return base;
    }

    /** K contingencies as K full copies (each removes a different line). */
    private static List<Network> contingenciesAsCopies(Network base, int k) {
        List<Network> copies = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            Network c = NetworkSerDe.copy(base);
            c.getLine("L" + i).remove();
            copies.add(c);
        }
        return copies;
    }

    // --- variant-scoped split: a STRUCTURAL clone, then the same split sequence, all public API ---
    private static void applyVariantSplit(Network base) {
        VariantManager vm = base.getVariantManager();
        vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("faulted");
        applyCopySplit(base); // remove L0 + add fictitious VL + half-lines, now scoped to "faulted"
    }

    // --- classic fault-on-line split on a plain (fully copied) network, via the public API ---
    private static void applyCopySplit(Network net) {
        Line l = net.getLine("L0");
        String vlA = l.getTerminal1().getVoltageLevel().getId();
        String busA = l.getTerminal1().getBusBreakerView().getConnectableBus().getId();
        String vlB = l.getTerminal2().getVoltageLevel().getId();
        String busB = l.getTerminal2().getBusBreakerView().getConnectableBus().getId();
        double r = l.getR();
        double x = l.getX();
        l.remove();
        Substation sf = net.newSubstation().setId("SFx").setFictitious(true).add();
        sf.newVoltageLevel().setId("Vfx").setNominalV(400).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add().getBusBreakerView().newBus().setId("busFx").add();
        newLine(net, "HALF_A", vlA, busA, "Vfx", "busFx", r * 0.4, x * 0.4);
        newLine(net, "HALF_B", "Vfx", "busFx", vlB, busB, r * 0.6, x * 0.6);
    }

    /** A bus/breaker chain of {@code size} lines: V0 --L0-- V1 --L1-- ... (size+1 voltage levels). */
    private static Network buildChain(int size) {
        Network n = Network.create("chain", "test");
        for (int i = 0; i <= size; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            s.newVoltageLevel().setId("V" + i).setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("b" + i).add();
        }
        for (int i = 0; i < size; i++) {
            newLine(n, "L" + i, "V" + i, "b" + i, "V" + (i + 1), "b" + (i + 1), 1.0, 10.0);
        }
        return n;
    }

    private static void newLine(Network n, String id, String vl1, String bus1, String vl2, String bus2, double r, double x) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1(bus1).setBus1(bus1)
                .setVoltageLevel2(vl2).setConnectableBus2(bus2).setBus2(bus2)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private interface LongOp {
        long run();
    }

    private static double medianMs(LongOp op) {
        List<Long> nanos = new ArrayList<>(RUNS);
        for (int i = 0; i < RUNS; i++) {
            nanos.add(op.run());
        }
        nanos.sort(Long::compareTo);
        return nanos.get(RUNS / 2) / 1e6;
    }

    private static long usedMemory() {
        Runtime rt = Runtime.getRuntime();
        long used = 0;
        for (int i = 0; i < 6; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            used = rt.totalMemory() - rt.freeMemory();
        }
        return used;
    }

    private static volatile Object sink;

    private static void blackhole(Object o) {
        sink = o;
    }
}

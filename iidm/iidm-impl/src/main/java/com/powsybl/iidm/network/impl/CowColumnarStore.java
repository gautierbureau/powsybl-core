/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>Spike Phase 2 — copy-on-write columnar store (de-risking prototype).</b> See
 * {@code structural-variant-public-api.md} (Phase 2, "bulk state O(1)").</p>
 *
 * <p>The isolated proof of the hardest remaining piece: making a <b>columnar per-variant state store</b>
 * (the shape of {@code NumericVariantStore}/{@code TerminalVariantStore}/{@code SwitchVariantStore})
 * copy-on-write over the variant tree ({@link CowVariantParentage}), so cloning a variant is <b>O(1)</b>
 * — it copies no band — while preserving IIDM's <b>snapshot</b> semantics. State is a grid of
 * {@code rows × columns} per variant; a variant holds a row only where it has <em>diverged</em>,
 * everything else falls through to its parent.</p>
 *
 * <p>Two properties, both asserted by {@code CowColumnarStoreTest}:</p>
 * <ul>
 *   <li><b>Fork is O(1).</b> Cloning copies nothing; a fresh variant stores no rows and inherits by
 *       parent pointer. Storage is O(diverged rows), not O(variants × rows).</li>
 *   <li><b>Snapshot is preserved on the write side.</b> Writing a row in a variant that still has
 *       children inheriting it first <em>freezes</em> that row into those children, so a change to a
 *       parent never leaks into a variant forked earlier. Per-row, so only touched rows cost anything.</li>
 * </ul>
 *
 * <p>This retires the design risk of the live conversion (converting the real columnar stores + the
 * per-object {@code MultiVariantObject} arrays, gated so normal variants stay dense); it does not perform
 * it.</p>
 *
 * @author Claude
 */
final class CowColumnarStore {

    private final CowVariantParentage parentage;
    private final int columns;
    private int rowCount;
    // Sparse: variant -> (row -> the row's column values). A (variant, row) present means that variant has
    // diverged that row; absent means it inherits the row from the nearest ancestor that has it.
    private final Map<Integer, Map<Integer, double[]>> byVariant = new HashMap<>();

    CowColumnarStore(CowVariantParentage parentage, int columns) {
        this.parentage = parentage;
        this.columns = columns;
    }

    /** Allocate a row with initial values, held by {@code inVariant} (typically the root); returns its id. */
    int allocateRow(int inVariant, double... init) {
        int row = rowCount++;
        byVariant.computeIfAbsent(inVariant, k -> new HashMap<>()).put(row, init.clone());
        return row;
    }

    /** The value of {@code (row, col)} in {@code variant}: its own if diverged, else the nearest ancestor's. */
    double get(int variant, int row, int col) {
        double[] r = resolveRow(variant, row);
        return r == null ? 0.0 : r[col];
    }

    /**
     * Write {@code value} into {@code (row, col)} of {@code variant}. Copy-on-write: any direct child that
     * still inherits this row is first frozen to the current value (snapshot), then the row is materialised
     * for {@code variant} (copied from its parent on first touch) and written.
     */
    void set(int variant, int row, int col, double value) {
        for (int child : parentage.children(variant)) {
            if (!owns(child, row)) {
                materialiseRow(child, row);
            }
        }
        materialiseRow(variant, row);
        byVariant.get(variant).get(row)[col] = value;
    }

    private boolean owns(int variant, int row) {
        Map<Integer, double[]> rows = byVariant.get(variant);
        return rows != null && rows.containsKey(row);
    }

    private void materialiseRow(int variant, int row) {
        if (owns(variant, row)) {
            return;
        }
        double[] resolved = resolveRow(variant, row);
        double[] copy = resolved != null ? resolved.clone() : new double[columns];
        byVariant.computeIfAbsent(variant, k -> new HashMap<>()).put(row, copy);
    }

    private double[] resolveRow(int variant, int row) {
        int v = variant;
        while (v != -1) {
            Map<Integer, double[]> rows = byVariant.get(v);
            if (rows != null) {
                double[] r = rows.get(row);
                if (r != null) {
                    return r;
                }
            }
            v = parentage.parent(v);
        }
        return null;
    }

    /** Total number of diverged rows across all variants — the true storage cost (not variants × rows). */
    int materialisedRowCount() {
        return byVariant.values().stream().mapToInt(Map::size).sum();
    }
}

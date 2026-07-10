/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Prototype for the deferred columnar copy-on-write clone — the gated variant. Not wired into any
 * live path; exercised only by {@code CowGatedColumnarStoreTest}.</b> See
 * {@code structural-variant-public-api.md} (the "O(1) clone" section). The final de-risk before a live
 * port.</p>
 *
 * <p>{@link CowColumnarStore} proved the copy-on-write algorithm. The remaining risk is <em>gating</em>:
 * making a real columnar store copy-on-write must cost <b>nothing</b> for the overwhelming majority of
 * networks that never use a structural variant, and must stay correct where a copy-on-write (structural)
 * variant is forked from a <b>dense</b> one. This models both storage modes in one store:</p>
 * <ul>
 *   <li><b>Dense variants</b> (the initial variant and {@code STATE_ONLY} clones) own every row — the
 *       existing eager behaviour, unchanged.</li>
 *   <li><b>Copy-on-write variants</b> ({@code STRUCTURAL} clones) own only their <em>diverged</em> rows and
 *       fall through the parentage — O(1) to create, even from a dense parent.</li>
 * </ul>
 *
 * <p>The gate is a single {@code cowActive} flag, flipped on the first structural clone. While it is
 * {@code false} — every network today — {@code get}/{@code set} take the plain dense path with no
 * parentage walk, no membership check, nothing (asserted by {@link #isFastPath()} in the test). When it is
 * {@code true}, dense variants are still read directly; only copy-on-write variants resolve through the
 * parentage, and a write to a dense parent that has copy-on-write children first freezes the row into them
 * (snapshot across the dense→sparse boundary).</p>
 *
 * @author Claude
 */
final class CowGatedColumnarStore {

    private final CowVariantParentage parentage;
    private final int columns;
    private int rowCount;
    // variant -> (row -> values). A dense variant holds every row; a copy-on-write variant holds only its
    // diverged rows and inherits the rest through the parentage.
    private final Map<Integer, Map<Integer, double[]>> storage = new HashMap<>();
    private final Set<Integer> cowVariants = new HashSet<>();
    private boolean cowActive;

    CowGatedColumnarStore(CowVariantParentage parentage, int columns, int rootVariant) {
        this.parentage = parentage;
        this.columns = columns;
        storage.put(rootVariant, new HashMap<>()); // the initial variant is dense
    }

    /** True while no structural variant exists — {@code get}/{@code set} take the plain dense path. */
    boolean isFastPath() {
        return !cowActive;
    }

    /** Allocate a row with initial values in every dense variant; returns its id. */
    int allocateRow(double... init) {
        int row = rowCount++;
        for (Map.Entry<Integer, Map<Integer, double[]>> e : storage.entrySet()) {
            if (!cowVariants.contains(e.getKey())) {
                e.getValue().put(row, init.clone());
            }
        }
        return row;
    }

    /** A {@code STATE_ONLY} clone: eager, dense — the child owns a copy of every row (today's behaviour). */
    void cloneStateOnly(int child, int parent) {
        Map<Integer, double[]> src = storage.get(parent);
        Map<Integer, double[]> copy = new HashMap<>();
        src.forEach((row, vals) -> copy.put(row, vals.clone()));
        storage.put(child, copy);
    }

    /** A {@code STRUCTURAL} clone: O(1) — the child copies nothing and inherits through the parentage. */
    void cloneStructural(int child, int parent) {
        cowActive = true;
        cowVariants.add(child);
        storage.put(child, new HashMap<>());
    }

    double get(int variant, int row, int col) {
        if (!cowActive) {
            return storage.get(variant).get(row)[col]; // FAST PATH: every variant dense, owns the row
        }
        if (!cowVariants.contains(variant)) {
            return storage.get(variant).get(row)[col]; // dense variant: direct
        }
        double[] r = resolveRow(variant, row);
        return r == null ? 0.0 : r[col];
    }

    void set(int variant, int row, int col, double value) {
        if (!cowActive) {
            storage.get(variant).get(row)[col] = value; // FAST PATH
            return;
        }
        // snapshot across the boundary: freeze the row into any copy-on-write child that still inherits it
        for (int child : parentage.children(variant)) {
            if (cowVariants.contains(child) && !owns(child, row)) {
                double[] resolved = resolveRow(child, row);
                storage.get(child).put(row, resolved != null ? resolved.clone() : new double[columns]);
            }
        }
        if (cowVariants.contains(variant) && !owns(variant, row)) {
            double[] resolved = resolveRow(variant, row);
            storage.get(variant).put(row, resolved != null ? resolved.clone() : new double[columns]);
        }
        storage.get(variant).get(row)[col] = value;
    }

    private boolean owns(int variant, int row) {
        Map<Integer, double[]> rows = storage.get(variant);
        return rows != null && rows.containsKey(row);
    }

    private double[] resolveRow(int variant, int row) {
        int v = variant;
        while (v != -1) {
            Map<Integer, double[]> rows = storage.get(v);
            if (rows != null && rows.containsKey(row)) {
                return rows.get(row);
            }
            v = parentage.parent(v);
        }
        return null;
    }

    /** Rows stored for {@code variant} — 0 for a fresh copy-on-write variant, all for a dense one. */
    int rowsStored(int variant) {
        Map<Integer, double[]> rows = storage.get(variant);
        return rows == null ? 0 : rows.size();
    }
}

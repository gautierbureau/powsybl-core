/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

/**
 * Columnar (structure-of-arrays) store for the variant-dependent {@code p} and {@code q} of every
 * terminal in a network.
 *
 * <p>Instead of each terminal owning its own {@code TDoubleArrayList} indexed by variant
 * (array-of-structures), all terminals share two
 * network-level, <em>variant-major</em>, <em>flat</em> arrays: the {@code p} of terminal {@code row} in
 * variant {@code v} is {@code p[v * rowStride + row]}. A variant clone is then a couple of
 * {@link System#arraycopy} calls over contiguous bands <em>within the existing array</em> — no per-clone
 * allocation — instead of one method call per terminal. A flat pre-grown array (not {@code double[][]})
 * is essential: allocating a fresh band per clone reintroduces the very garbage the layout is meant to
 * avoid and turns the win into a regression.</p>
 *
 * <p>Thread-safety follows the {@link com.powsybl.iidm.network.VariantManager} contract exactly as the
 * previous per-terminal layout did: structural changes ({@link #extend}, {@link #reduce}, {@link #delete},
 * {@link #allocate}, {@link #allocateRow}) happen on the main thread only, never concurrently with reads
 * or writes; pre-allocated variants may be read/written concurrently, each thread on its own variant index
 * (its own band). The backing arrays are published through {@code volatile} references so worker threads
 * observe fully constructed state.</p>
 *
 * <p>Row lifecycle is monotonic: {@link #allocateRow} hands out an ever-increasing row
 * index and rows are not recycled when a terminal is removed (a full implementation would keep a free list).
 * This is correct — dead rows are simply never read.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class TerminalVariantStore implements VariantColumnStore {

    private static final int DEFAULT_ROW_CAPACITY = 16;

    // flat variant-major layout: value(variant v, row r) = data[v * rowStride + r]
    private volatile double[] p;
    private volatile double[] q;

    private int rowStride;      // capacity (in rows) of each variant band
    private int rowCount;       // high-water mark of allocated rows
    private int variantSize;    // number of live variant bands
    private int variantCapacity;

    // rows freed by removed terminals, available for reuse (avoids leaking a row per removed terminal)
    private final Deque<Integer> freeRows = new ArrayDeque<>();

    private final VariantManagerImpl variantManager;

    TerminalVariantStore(VariantManagerImpl variantManager) {
        this.variantManager = Objects.requireNonNull(variantManager);
        int variantArraySize = variantManager.getVariantArraySize();
        this.rowStride = DEFAULT_ROW_CAPACITY;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.p = newData(variantCapacity * rowStride);
        this.q = newData(variantCapacity * rowStride);
    }

    private static double[] newData(int length) {
        double[] data = new double[length];
        Arrays.fill(data, Double.NaN);
        return data;
    }

    /**
     * Allocate a row for a new terminal. The row reads as {@code NaN} in every existing variant band. A row
     * freed by a previously removed terminal is reused if available, otherwise a fresh one is allocated.
     */
    int allocateRow() {
        checkStructuralModification("allocateRow");
        if (!freeRows.isEmpty()) {
            int row = freeRows.pop();
            resetRow(row);
            return row;
        }
        if (rowCount == rowStride) {
            growRowStride();
        }
        return rowCount++;
    }

    /**
     * Release the row of a removed terminal so it can be reused. The caller must never read/write the row again.
     */
    void freeRow(int row) {
        checkStructuralModification("freeRow");
        freeRows.push(row);
    }

    // Reset a (reused) row to NaN in every live variant band.
    private void resetRow(int row) {
        double[] pd = p;
        double[] qd = q;
        for (int v = 0; v < variantSize; v++) {
            pd[v * rowStride + row] = Double.NaN;
            qd[v * rowStride + row] = Double.NaN;
        }
    }

    /**
     * Allocate a fresh row and initialise it with the given single-variant {@code p}/{@code q}. Used when a
     * terminal is moved between networks (merge/detach), which the API only allows on single-variant networks.
     */
    int importRow(double pValue, double qValue) {
        int row = allocateRow();
        p[row] = pValue; // variant 0 (the only variant in a merge/detach)
        q[row] = qValue;
        return row;
    }

    // Structural operations only; reads and per-variant writes are the concurrent path and stay unchecked.
    private void checkStructuralModification(String operation) {
        variantManager.checkStructuralModification("terminal p/q", operation);
    }

    private void growRowStride() {
        int newStride = rowStride * 2;
        p = restride(p, newStride);
        q = restride(q, newStride);
        rowStride = newStride;
    }

    // Move each live variant band to its new, wider offset; new cells default to NaN.
    private double[] restride(double[] data, int newStride) {
        double[] out = newData(variantCapacity * newStride);
        for (int v = 0; v < variantSize; v++) {
            System.arraycopy(data, v * rowStride, out, v * newStride, rowCount);
        }
        return out;
    }

    private void ensureVariantCapacity(int required) {
        if (required <= variantCapacity) {
            return;
        }
        int newCapacity = Math.max(required, variantCapacity * 2);
        int oldLength = variantCapacity * rowStride;
        int newLength = newCapacity * rowStride;
        // NaN-fill the grown region so a row that is only populated later (a terminal added after a clone)
        // reads NaN, not the 0.0 that Arrays.copyOf would leave.
        p = growNaN(p, oldLength, newLength);
        q = growNaN(q, oldLength, newLength);
        variantCapacity = newCapacity;
    }

    private static double[] growNaN(double[] data, int oldLength, int newLength) {
        double[] out = Arrays.copyOf(data, newLength);
        Arrays.fill(out, oldLength, newLength, Double.NaN);
        return out;
    }

    double getP(int variantIndex, int row) {
        return p[variantIndex * rowStride + row];
    }

    double setP(int variantIndex, int row, double value) {
        double[] data = p;
        int i = variantIndex * rowStride + row;
        double old = data[i];
        data[i] = value;
        return old;
    }

    double getQ(int variantIndex, int row) {
        return q[variantIndex * rowStride + row];
    }

    double setQ(int variantIndex, int row, double value) {
        double[] data = q;
        int i = variantIndex * rowStride + row;
        double old = data[i];
        data[i] = value;
        return old;
    }

    // --- structural changes, driven once per operation by NetworkImpl (main thread only) ---

    public void extend(int number, int sourceIndex) {
        checkStructuralModification("extend");
        ensureVariantCapacity(variantSize + number);
        int stride = rowStride;
        int srcOff = sourceIndex * stride;
        double[] pd = p;
        double[] qd = q;
        for (int i = 0; i < number; i++) {
            int dstOff = (variantSize + i) * stride;
            System.arraycopy(pd, srcOff, pd, dstOff, rowCount);
            System.arraycopy(qd, srcOff, qd, dstOff, rowCount);
        }
        p = pd;
        q = qd;
        variantSize += number;
    }

    public void reduce(int number) {
        variantSize -= number; // bands past the new size are simply no longer read
    }

    public void delete(int index) {
        // Match the previous per-terminal behaviour ("nothing to do"): the band at this now-unused index
        // is left in place and will be overwritten if the index is later recycled by allocate().
    }

    public void allocate(int[] indexes, int sourceIndex) {
        checkStructuralModification("allocate");
        int stride = rowStride;
        int srcOff = sourceIndex * stride;
        double[] pd = p;
        double[] qd = q;
        for (int index : indexes) {
            int dstOff = index * stride;
            System.arraycopy(pd, srcOff, pd, dstOff, rowCount);
            System.arraycopy(qd, srcOff, qd, dstOff, rowCount);
        }
        p = pd;
        q = qd;
    }
}

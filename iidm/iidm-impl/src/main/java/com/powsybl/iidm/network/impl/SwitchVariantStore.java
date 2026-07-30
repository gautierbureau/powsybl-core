/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import gnu.trove.stack.array.TIntArrayStack;

import java.util.Arrays;
import java.util.Objects;

/**
 * Columnar (structure-of-arrays) store for the variant-dependent <em>topology</em> of every switch in a
 * network: its {@code open} and {@code retained} state.
 *
 * <p>The second columnar variant-storage type. Switch open/retained is per-variant topology
 * (unlike terminal p/q, which is a computed characteristic) and is the state that security-analysis
 * contingencies mutate, so it is on the path of the real variant-cloning workload. It follows the same flat
 * variant-major layout as {@link TerminalVariantStore}: {@code open[variant * rowStride + row]}, so a clone
 * is a couple of {@link System#arraycopy} calls instead of one method call per switch.</p>
 *
 * <p>{@code boolean[]} (one byte per flag) is used here for clarity and to mirror the terminal store; a
 * further compaction would bit-pack each variant band into a {@code long[]} (one bit per switch), making a
 * clone an even smaller bitset copy. That is left as a follow-up.</p>
 *
 * <p>Thread-safety follows the {@link com.powsybl.iidm.network.VariantManager} contract as before: structural
 * changes happen on the main thread only; pre-allocated variants are read/written concurrently, each thread
 * on its own band. Rows are recycled only on re-home
 * (merge/detach), where the switch swaps to its new row in the same call. A <em>removed</em> switch still
 * leaks its row: recycling there would need a {@code removed} guard on {@link SwitchImpl}, unlike terminals
 * whose reads are already guarded.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class SwitchVariantStore implements VariantColumnStore {

    private static final int DEFAULT_ROW_CAPACITY = 16;

    // flat variant-major layout: open(variant v, row r) = open[v * rowStride + r]
    private volatile boolean[] open;
    private volatile boolean[] retained;

    private int rowStride;
    private int rowCount;
    private int variantSize;
    private int variantCapacity;

    // rows released by switches re-homed into another network's store (merge/detach), available for reuse
    private final TIntArrayStack freeRows = new TIntArrayStack();

    private final VariantManagerImpl variantManager;

    SwitchVariantStore(VariantManagerImpl variantManager) {
        this.variantManager = Objects.requireNonNull(variantManager);
        int variantArraySize = variantManager.getVariantArraySize();
        this.rowStride = DEFAULT_ROW_CAPACITY;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.open = new boolean[variantCapacity * rowStride];
        this.retained = new boolean[variantCapacity * rowStride];
    }

    /**
     * Allocate a row for a new switch, initialised with the given open/retained state in every live variant
     * band (a new switch has the same state in all variants, as the previous per-switch constructor did).
     */
    int allocateRow(boolean openValue, boolean retainedValue) {
        checkStructuralModification("allocateRow");
        int row;
        if (freeRows.size() > 0) {
            row = freeRows.pop();
        } else {
            if (rowCount == rowStride) {
                growRowStride();
            }
            row = rowCount++;
        }
        boolean[] od = open;
        boolean[] rd = retained;
        for (int v = 0; v < variantSize; v++) {
            od[v * rowStride + row] = openValue;
            rd[v * rowStride + row] = retainedValue;
        }
        return row;
    }

    // Structural operations only; reads and per-variant writes are the concurrent path and stay unchecked.
    private void checkStructuralModification(String operation) {
        variantManager.checkStructuralModification("switch open/retained", operation);
    }

    private void growRowStride() {
        int newStride = rowStride * 2;
        open = restride(open, newStride);
        retained = restride(retained, newStride);
        rowStride = newStride;
    }

    private boolean[] restride(boolean[] data, int newStride) {
        boolean[] out = new boolean[variantCapacity * newStride];
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
        open = Arrays.copyOf(open, newCapacity * rowStride);
        retained = Arrays.copyOf(retained, newCapacity * rowStride);
        variantCapacity = newCapacity;
    }

    boolean getOpen(int variantIndex, int row) {
        return open[variantIndex * rowStride + row];
    }

    boolean setOpen(int variantIndex, int row, boolean value) {
        boolean[] data = open;
        int i = variantIndex * rowStride + row;
        boolean old = data[i];
        data[i] = value;
        return old;
    }

    boolean getRetained(int variantIndex, int row) {
        return retained[variantIndex * rowStride + row];
    }

    boolean setRetained(int variantIndex, int row, boolean value) {
        boolean[] data = retained;
        int i = variantIndex * rowStride + row;
        boolean old = data[i];
        data[i] = value;
        return old;
    }

    /**
     * Move {@code row} out of {@code source} and into this store, for a switch changing network
     * (merge/detach), which the API only allows on single-variant networks. The source row is released: the
     * switch switches to the returned row in the same call and never reads the source again, so a detach
     * leaves no dead row behind in the source store.
     */
    int importRow(SwitchVariantStore source, int row) {
        int newRow = allocateRow(source.getOpen(0, row), source.getRetained(0, row));
        source.freeRow(row);
        return newRow;
    }

    /**
     * Release a row for reuse. Only safe when no read of the row can follow. Re-homing satisfies that (the
     * switch swaps to its new row in the same call); removal does not, because {@link SwitchImpl} has no
     * {@code removed} guard on its getters, so a removed switch still leaks its row.
     */
    private void freeRow(int row) {
        checkStructuralModification("freeRow");
        freeRows.push(row);
    }

    /** Number of rows handed out, including those currently on the free list. For tests and diagnostics. */
    int getRowCount() {
        return rowCount;
    }

    /** Number of rows available for reuse. For tests and diagnostics. */
    int getFreeRowCount() {
        return freeRows.size();
    }

    // --- structural changes, driven once per operation by NetworkImpl (main thread only) ---

    public void extend(int number, int sourceIndex) {
        checkStructuralModification("extend");
        ensureVariantCapacity(variantSize + number);
        int stride = rowStride;
        int srcOff = sourceIndex * stride;
        boolean[] od = open;
        boolean[] rd = retained;
        for (int i = 0; i < number; i++) {
            int dstOff = (variantSize + i) * stride;
            System.arraycopy(od, srcOff, od, dstOff, rowCount);
            System.arraycopy(rd, srcOff, rd, dstOff, rowCount);
        }
        open = od;
        retained = rd;
        variantSize += number;
    }

    public void reduce(int number) {
        variantSize -= number;
    }

    public void delete(int index) {
        // nothing to do: the band is left in place and overwritten if the index is recycled by allocate()
    }

    public void allocate(int[] indexes, int sourceIndex) {
        checkStructuralModification("allocate");
        int stride = rowStride;
        int srcOff = sourceIndex * stride;
        boolean[] od = open;
        boolean[] rd = retained;
        for (int index : indexes) {
            int dstOff = index * stride;
            System.arraycopy(od, srcOff, od, dstOff, rowCount);
            System.arraycopy(rd, srcOff, rd, dstOff, rowCount);
        }
        open = od;
        retained = rd;
    }
}

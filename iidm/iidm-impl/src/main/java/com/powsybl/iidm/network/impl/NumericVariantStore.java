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

/**
 * Generic columnar (structure-of-arrays) store of variant-dependent numeric state, holding a fixed set of
 * {@code double} columns and {@code int} columns shared by every object of one type (e.g. all node terminals'
 * v / angle / connected-component / synchronous-component).
 *
 * <p>Reusable form of {@link TerminalVariantStore}: a variant's cells for all columns are contiguous, so a
 * clone copies one flat block per variant with {@link System#arraycopy} instead of one method call per object.
 * Layout, per array, is variant-major then column-major then row: {@code doubles[(v * nDouble + col) *
 * rowStride + row]}. Per-column default values initialise new rows / capacity so behaviour matches the former
 * per-object {@code TDoubleArrayList}/{@code TIntArrayList} defaults.</p>
 *
 * <p>Thread-safety follows the {@link com.powsybl.iidm.network.VariantManager} contract: structural changes on
 * the main thread only; pre-allocated variants read/written concurrently, each thread on its own band. Rows of
 * removed objects are recycled through a free list (callers must guard reads of removed objects, as terminals
 * do with their {@code removed} flag).</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class NumericVariantStore implements VariantColumnStore {

    private static final int DEFAULT_ROW_CAPACITY = 16;

    private final int nDouble;
    private final int nInt;
    private final double[] doubleDefaults;
    private final int[] intDefaults;

    private volatile double[] doubles;
    private volatile int[] ints;

    private int rowStride;
    private int rowCount;
    private int variantSize;
    private int variantCapacity;

    private final Deque<Integer> freeRows = new ArrayDeque<>();

    NumericVariantStore(int variantArraySize, double[] doubleDefaults, int[] intDefaults) {
        this.nDouble = doubleDefaults.length;
        this.nInt = intDefaults.length;
        this.doubleDefaults = doubleDefaults.clone();
        this.intDefaults = intDefaults.clone();
        this.rowStride = DEFAULT_ROW_CAPACITY;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.doubles = new double[variantCapacity * nDouble * rowStride];
        this.ints = new int[variantCapacity * nInt * rowStride];
    }

    private int doubleIndex(int variant, int col, int row) {
        return (variant * nDouble + col) * rowStride + row;
    }

    private int intIndex(int variant, int col, int row) {
        return (variant * nInt + col) * rowStride + row;
    }

    /** Allocate a row for a new object, initialised to the column defaults in every live variant band. */
    int allocateRow() {
        int row;
        if (!freeRows.isEmpty()) {
            row = freeRows.pop();
        } else {
            if (rowCount == rowStride) {
                growRowStride();
            }
            row = rowCount++;
        }
        double[] dd = doubles;
        int[] id = ints;
        for (int v = 0; v < variantSize; v++) {
            for (int c = 0; c < nDouble; c++) {
                dd[doubleIndex(v, c, row)] = doubleDefaults[c];
            }
            for (int c = 0; c < nInt; c++) {
                id[intIndex(v, c, row)] = intDefaults[c];
            }
        }
        return row;
    }

    /** Release the row of a removed object for reuse. */
    void freeRow(int row) {
        freeRows.push(row);
    }

    double getDouble(int variant, int col, int row) {
        return doubles[doubleIndex(variant, col, row)];
    }

    double setDouble(int variant, int col, int row, double value) {
        double[] data = doubles;
        int i = doubleIndex(variant, col, row);
        double old = data[i];
        data[i] = value;
        return old;
    }

    int getInt(int variant, int col, int row) {
        return ints[intIndex(variant, col, row)];
    }

    int setInt(int variant, int col, int row, int value) {
        int[] data = ints;
        int i = intIndex(variant, col, row);
        int old = data[i];
        data[i] = value;
        return old;
    }

    // --- structural changes, driven once per operation by NetworkImpl (main thread only) ---

    @Override
    public void extend(int number, int sourceIndex) {
        ensureVariantCapacity(variantSize + number);
        int dBlock = nDouble * rowStride;
        int iBlock = nInt * rowStride;
        double[] dd = doubles;
        int[] id = ints;
        for (int i = 0; i < number; i++) {
            int dst = variantSize + i;
            if (dBlock > 0) {
                System.arraycopy(dd, sourceIndex * dBlock, dd, dst * dBlock, dBlock);
            }
            if (iBlock > 0) {
                System.arraycopy(id, sourceIndex * iBlock, id, dst * iBlock, iBlock);
            }
        }
        doubles = dd;
        ints = id;
        variantSize += number;
    }

    @Override
    public void reduce(int number) {
        variantSize -= number;
    }

    @Override
    public void delete(int index) {
        // nothing to do: the band is left in place and overwritten if the index is recycled by allocate()
    }

    @Override
    public void allocate(int[] indexes, int sourceIndex) {
        int dBlock = nDouble * rowStride;
        int iBlock = nInt * rowStride;
        double[] dd = doubles;
        int[] id = ints;
        for (int index : indexes) {
            if (dBlock > 0) {
                System.arraycopy(dd, sourceIndex * dBlock, dd, index * dBlock, dBlock);
            }
            if (iBlock > 0) {
                System.arraycopy(id, sourceIndex * iBlock, id, index * iBlock, iBlock);
            }
        }
        doubles = dd;
        ints = id;
    }

    // Move each live variant band to a wider row stride.
    private void growRowStride() {
        int newStride = rowStride * 2;
        double[] nd = new double[variantCapacity * nDouble * newStride];
        int[] ni = new int[variantCapacity * nInt * newStride];
        for (int v = 0; v < variantSize; v++) {
            for (int c = 0; c < nDouble; c++) {
                System.arraycopy(doubles, (v * nDouble + c) * rowStride, nd, (v * nDouble + c) * newStride, rowCount);
            }
            for (int c = 0; c < nInt; c++) {
                System.arraycopy(ints, (v * nInt + c) * rowStride, ni, (v * nInt + c) * newStride, rowCount);
            }
        }
        doubles = nd;
        ints = ni;
        rowStride = newStride;
    }

    private void ensureVariantCapacity(int required) {
        if (required <= variantCapacity) {
            return;
        }
        int newCapacity = Math.max(required, variantCapacity * 2);
        doubles = Arrays.copyOf(doubles, newCapacity * nDouble * rowStride);
        ints = Arrays.copyOf(ints, newCapacity * nInt * rowStride);
        variantCapacity = newCapacity;
    }
}

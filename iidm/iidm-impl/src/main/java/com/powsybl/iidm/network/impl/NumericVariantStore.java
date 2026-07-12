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
 * Generic columnar (structure-of-arrays) store of variant-dependent state, holding a fixed set of
 * {@code double}, {@code int} and {@code boolean} columns shared by every object of one type (e.g. all node
 * terminals' v / angle / connected-component / synchronous-component).
 *
 * <p>Reusable form of {@link TerminalVariantStore}: a variant's cells for all columns of a given primitive
 * type are contiguous, so a clone copies one flat block per variant with {@link System#arraycopy} instead of
 * one method call per object. Layout, per array, is variant-major then column-major then row, e.g.
 * {@code doubles[(v * nDouble + col) * rowStride + row]}. Per-column default values initialise new rows and
 * grown capacity, so behaviour matches the former per-object {@code TDoubleArrayList}/{@code TIntArrayList}/
 * {@code TBooleanArrayList} defaults.</p>
 *
 * <p>Thread-safety follows the {@link com.powsybl.iidm.network.VariantManager} contract: structural changes on
 * the main thread only; pre-allocated variants read/written concurrently, each thread on its own band.</p>
 *
 * <p>Row lifecycle: {@link #freeRow(int)} returns a row to a free list for reuse by a future object. Freeing is
 * only safe when the owner guarantees no read of the freed row can follow (otherwise a reused row would surface
 * another object's value). Today only terminals/buses, whose reads are gated by a {@code removed} flag, free
 * their row on removal; other owners (injections, tap changers, converters, areas, DC nodes, extensions, ...)
 * keep their row for their lifetime, so {@code rowCount} grows monotonically under add/remove churn and is only
 * reclaimed when the whole store is discarded. Wiring {@code freeRow} for those owners requires first gating
 * their columnar getters on removal, as terminals do.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
public class NumericVariantStore implements VariantColumnStore {

    private static final int DEFAULT_ROW_CAPACITY = 16;

    private final int nDouble;
    private final int nInt;
    private final int nBoolean;
    private final double[] doubleDefaults;
    private final int[] intDefaults;
    private final boolean[] booleanDefaults;

    private volatile double[] doubles;
    private volatile int[] ints;
    private volatile boolean[] booleans;

    // Geometry fields (rowStride, rowCount, variantSize, variantCapacity) are deliberately NOT volatile: a
    // read computes its index from the volatile array reference AND rowStride, so publishing only the array
    // is not by itself sufficient to safely observe a resized store. Correctness relies entirely on the
    // VariantManager contract that geometry-changing operations (grow/allocate/extend, which reassign the
    // array and mutate rowStride together) run on the main thread only, with a happens-before edge before any
    // worker thread starts reading. Do not mutate geometry from a worker thread (e.g. via a lazy store
    // creation or a late equipment add during a parallel analysis): a reader could then observe a new, wider
    // array with the old stride and index out of bounds / into the wrong variant.
    private int rowStride;
    private int rowCount;
    private int variantSize;
    private int variantCapacity;

    private final Deque<Integer> freeRows = new ArrayDeque<>();

    NumericVariantStore(int variantArraySize, double[] doubleDefaults, int[] intDefaults, boolean[] booleanDefaults) {
        this.nDouble = doubleDefaults.length;
        this.nInt = intDefaults.length;
        this.nBoolean = booleanDefaults.length;
        this.doubleDefaults = doubleDefaults.clone();
        this.intDefaults = intDefaults.clone();
        this.booleanDefaults = booleanDefaults.clone();
        this.rowStride = DEFAULT_ROW_CAPACITY;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.doubles = new double[variantCapacity * nDouble * rowStride];
        this.ints = new int[variantCapacity * nInt * rowStride];
        this.booleans = new boolean[variantCapacity * nBoolean * rowStride];
    }

    private int doubleIndex(int variant, int col, int row) {
        return (variant * nDouble + col) * rowStride + row;
    }

    private int intIndex(int variant, int col, int row) {
        return (variant * nInt + col) * rowStride + row;
    }

    private int booleanIndex(int variant, int col, int row) {
        return (variant * nBoolean + col) * rowStride + row;
    }

    /** Allocate a row for a new object, initialised to the column defaults in every live variant band. */
    public int allocateRow() {
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
        boolean[] bd = booleans;
        for (int v = 0; v < variantSize; v++) {
            for (int c = 0; c < nDouble; c++) {
                dd[doubleIndex(v, c, row)] = doubleDefaults[c];
            }
            for (int c = 0; c < nInt; c++) {
                id[intIndex(v, c, row)] = intDefaults[c];
            }
            for (int c = 0; c < nBoolean; c++) {
                bd[booleanIndex(v, c, row)] = booleanDefaults[c];
            }
        }
        return row;
    }

    /**
     * Allocate a row initialised to the given per-instance values in every live variant band (as the former
     * per-object constructor did, filling every variant with the object's initial values). The array lengths
     * must match the store's column counts.
     */
    public int allocateRow(double[] doubleInit, int[] intInit, boolean[] booleanInit) {
        int row = allocateRow();
        double[] dd = doubles;
        int[] id = ints;
        boolean[] bd = booleans;
        for (int v = 0; v < variantSize; v++) {
            for (int c = 0; c < nDouble; c++) {
                dd[doubleIndex(v, c, row)] = doubleInit[c];
            }
            for (int c = 0; c < nInt; c++) {
                id[intIndex(v, c, row)] = intInit[c];
            }
            for (int c = 0; c < nBoolean; c++) {
                bd[booleanIndex(v, c, row)] = booleanInit[c];
            }
        }
        return row;
    }

    /** Release the row of a removed object for reuse. */
    public void freeRow(int row) {
        freeRows.push(row);
    }

    public double getDouble(int variant, int col, int row) {
        return doubles[doubleIndex(variant, col, row)];
    }

    public double setDouble(int variant, int col, int row, double value) {
        double[] data = doubles;
        int i = doubleIndex(variant, col, row);
        double old = data[i];
        data[i] = value;
        return old;
    }

    public int getInt(int variant, int col, int row) {
        return ints[intIndex(variant, col, row)];
    }

    public int setInt(int variant, int col, int row, int value) {
        int[] data = ints;
        int i = intIndex(variant, col, row);
        int old = data[i];
        data[i] = value;
        return old;
    }

    /** Set an int column of a row to the same value in every live variant band. */
    public void fillInt(int col, int row, int value) {
        int[] data = ints;
        for (int v = 0; v < variantSize; v++) {
            data[intIndex(v, col, row)] = value;
        }
    }

    /** Set a boolean column of a row to the same value in every live variant band. */
    public void fillBoolean(int col, int row, boolean value) {
        boolean[] data = booleans;
        for (int v = 0; v < variantSize; v++) {
            data[booleanIndex(v, col, row)] = value;
        }
    }

    public boolean getBoolean(int variant, int col, int row) {
        return booleans[booleanIndex(variant, col, row)];
    }

    public boolean setBoolean(int variant, int col, int row, boolean value) {
        boolean[] data = booleans;
        int i = booleanIndex(variant, col, row);
        boolean old = data[i];
        data[i] = value;
        return old;
    }

    // --- structural changes, driven once per operation by NetworkImpl (main thread only) ---

    @Override
    public void extend(int number, int sourceIndex) {
        ensureVariantCapacity(variantSize + number);
        int dBlock = nDouble * rowStride;
        int iBlock = nInt * rowStride;
        int bBlock = nBoolean * rowStride;
        double[] dd = doubles;
        int[] id = ints;
        boolean[] bd = booleans;
        for (int i = 0; i < number; i++) {
            int dst = variantSize + i;
            if (dBlock > 0) {
                System.arraycopy(dd, sourceIndex * dBlock, dd, dst * dBlock, dBlock);
            }
            if (iBlock > 0) {
                System.arraycopy(id, sourceIndex * iBlock, id, dst * iBlock, iBlock);
            }
            if (bBlock > 0) {
                System.arraycopy(bd, sourceIndex * bBlock, bd, dst * bBlock, bBlock);
            }
        }
        doubles = dd;
        ints = id;
        booleans = bd;
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
        int bBlock = nBoolean * rowStride;
        double[] dd = doubles;
        int[] id = ints;
        boolean[] bd = booleans;
        for (int index : indexes) {
            if (dBlock > 0) {
                System.arraycopy(dd, sourceIndex * dBlock, dd, index * dBlock, dBlock);
            }
            if (iBlock > 0) {
                System.arraycopy(id, sourceIndex * iBlock, id, index * iBlock, iBlock);
            }
            if (bBlock > 0) {
                System.arraycopy(bd, sourceIndex * bBlock, bd, index * bBlock, bBlock);
            }
        }
        doubles = dd;
        ints = id;
        booleans = bd;
    }

    // Move each live variant band to a wider row stride.
    private void growRowStride() {
        int newStride = rowStride * 2;
        double[] nd = new double[variantCapacity * nDouble * newStride];
        int[] ni = new int[variantCapacity * nInt * newStride];
        boolean[] nb = new boolean[variantCapacity * nBoolean * newStride];
        for (int v = 0; v < variantSize; v++) {
            for (int c = 0; c < nDouble; c++) {
                System.arraycopy(doubles, (v * nDouble + c) * rowStride, nd, (v * nDouble + c) * newStride, rowCount);
            }
            for (int c = 0; c < nInt; c++) {
                System.arraycopy(ints, (v * nInt + c) * rowStride, ni, (v * nInt + c) * newStride, rowCount);
            }
            for (int c = 0; c < nBoolean; c++) {
                System.arraycopy(booleans, (v * nBoolean + c) * rowStride, nb, (v * nBoolean + c) * newStride, rowCount);
            }
        }
        doubles = nd;
        ints = ni;
        booleans = nb;
        rowStride = newStride;
    }

    private void ensureVariantCapacity(int required) {
        if (required <= variantCapacity) {
            return;
        }
        int newCapacity = Math.max(required, variantCapacity * 2);
        doubles = Arrays.copyOf(doubles, newCapacity * nDouble * rowStride);
        ints = Arrays.copyOf(ints, newCapacity * nInt * rowStride);
        booleans = Arrays.copyOf(booleans, newCapacity * nBoolean * rowStride);
        variantCapacity = newCapacity;
    }
}

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
 * only safe when no read of the freed row can follow, otherwise a reused row would surface another object's
 * value. Two situations satisfy that, and they are not the same:</p>
 * <ul>
 *   <li><b>Re-homing</b> ({@link #importRow(NumericVariantStore, int)}, on merge/detach) always does: the owner
 *       swaps to its new row in the target store in the same call and never looks at the source row again.
 *       Every owner therefore releases its source row, so a detach does not leave the previous network holding
 *       a dead row per moved object — which every later variant clone would go on copying.</li>
 *   <li><b>Removal</b> only does when the owner refuses reads afterwards. Terminals do, gating every columnar
 *       getter on a {@code removed} flag, and are the only owners that free their row on removal
 *       ({@code AbstractTerminal}, {@code NodeTerminal}, {@code BusTerminal}, {@code DcTerminalImpl}). Every
 *       other owner — configured buses, injections, tap changers, converters, areas, DC nodes, extensions, ...
 *       — keeps its row for its lifetime, so {@code rowCount} still grows monotonically under add/remove churn.
 *       Wiring {@code freeRow} for them requires gating their getters on removal first, as terminals do.</li>
 * </ul>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
public class NumericVariantStore implements VariantColumnStore {

    private static final int DEFAULT_ROW_CAPACITY = 16;

    private final String key;
    private final VariantManagerImpl variantManager;
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

    NumericVariantStore(String key, VariantManagerImpl variantManager, double[] doubleDefaults, int[] intDefaults,
                        boolean[] booleanDefaults) {
        this.key = Objects.requireNonNull(key);
        this.variantManager = Objects.requireNonNull(variantManager);
        int variantArraySize = variantManager.getVariantArraySize();
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

    /**
     * Check that a later {@code getOrCreateNumericVariantStore} call for this store's key describes exactly the
     * same columns, and fail loudly if it does not.
     *
     * <p>A store is shared by every object declaring its key, and each object addresses its state by column
     * index, so two types sharing a key must agree on the column layout. Without this check a mistyped,
     * copy-pasted or accidentally colliding key would silently hand one type another type's columns: reads
     * would return a plausible value from the wrong column, or an {@link ArrayIndexOutOfBoundsException} would
     * surface far from the cause. Sharing a key on purpose stays supported (ratio and phase tap changers do it)
     * as long as the layouts match.</p>
     *
     * <p>{@link Arrays#equals(double[], double[])} compares {@code NaN} to {@code NaN} as equal, which is what
     * the many {@code NaN} column defaults need.</p>
     */
    void checkColumnLayout(double[] expectedDoubleDefaults, int[] expectedIntDefaults, boolean[] expectedBooleanDefaults) {
        if (!Arrays.equals(doubleDefaults, expectedDoubleDefaults)
                || !Arrays.equals(intDefaults, expectedIntDefaults)
                || !Arrays.equals(booleanDefaults, expectedBooleanDefaults)) {
            throw new IllegalStateException("Columnar variant store '" + key
                    + "' already exists with a different column layout: holds "
                    + describeLayout(doubleDefaults, intDefaults, booleanDefaults) + " but was requested with "
                    + describeLayout(expectedDoubleDefaults, expectedIntDefaults, expectedBooleanDefaults)
                    + ". Two object types may only share a store key if they declare identical columns.");
        }
    }

    private static String describeLayout(double[] doubleDefaults, int[] intDefaults, boolean[] booleanDefaults) {
        return "double" + Arrays.toString(doubleDefaults)
                + " int" + Arrays.toString(intDefaults)
                + " boolean" + Arrays.toString(booleanDefaults);
    }

    // Structural operations only: they add/remove rows or variant bands, and may reallocate the backing arrays
    // and change the row stride together. Reads and per-variant writes are deliberately not checked - they are
    // the concurrent path, and the check must stay off their hot path.
    private void checkStructuralModification(String operation) {
        variantManager.checkStructuralModification(key, operation);
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
        checkStructuralModification("allocateRow");
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
        checkStructuralModification("freeRow");
        freeRows.push(row);
    }

    /**
     * Move {@code row} out of {@code source} and into this store, returning its new row index. Every column is
     * carried over generically, so an owner re-homing itself cannot forget one, and the source row is released:
     * the owner switches to the returned row in the same breath and never reads the source again, which is what
     * makes freeing safe here even for owners that cannot recycle on removal (see the class javadoc).
     *
     * <p>Only the initial variant is moved, since only single-variant networks can be merged or detached. The
     * two stores necessarily agree on the column layout — they are keyed the same, and
     * {@link #checkColumnLayout} enforces that — which is what lets this copy column by column blindly.</p>
     */
    public int importRow(NumericVariantStore source, int row) {
        source.checkColumnLayout(doubleDefaults, intDefaults, booleanDefaults);
        int newRow = allocateRow();
        for (int c = 0; c < nDouble; c++) {
            setDouble(0, c, newRow, source.getDouble(0, c, row));
        }
        for (int c = 0; c < nInt; c++) {
            setInt(0, c, newRow, source.getInt(0, c, row));
        }
        for (int c = 0; c < nBoolean; c++) {
            setBoolean(0, c, newRow, source.getBoolean(0, c, row));
        }
        source.freeRow(row);
        return newRow;
    }

    /** Number of rows handed out, including those currently on the free list. For tests and diagnostics. */
    int getRowCount() {
        return rowCount;
    }

    /** Number of rows available for reuse. For tests and diagnostics. */
    int getFreeRowCount() {
        return freeRows.size();
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
        checkStructuralModification("fillInt");
        int[] data = ints;
        for (int v = 0; v < variantSize; v++) {
            data[intIndex(v, col, row)] = value;
        }
    }

    /** Set a boolean column of a row to the same value in every live variant band. */
    public void fillBoolean(int col, int row, boolean value) {
        checkStructuralModification("fillBoolean");
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
        checkStructuralModification("extend");
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
        checkStructuralModification("allocate");
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

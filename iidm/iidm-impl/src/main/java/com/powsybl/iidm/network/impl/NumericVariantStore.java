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
import java.util.BitSet;
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
 * <p>Variants cloned with {@code VariantCloneStrategy.STRUCTURAL} are stored copy-on-write, exactly as in
 * {@link TerminalVariantStore}: {@link #extendStructural} copies nothing (O(1)); reads resolve through the
 * clone parentage ({@link VariantCowState}); rows diverge per write (all columns of the touched row at
 * once), and a write to a variant first freezes the touched row into the copy-on-write children still
 * inheriting it. The {@link VariantCowState#isActive()} gate keeps every network without a structural
 * variant on the plain dense path. See {@link TerminalVariantStore} for the copy-on-write thread-safety
 * caveat.</p>
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
    private int variantSize;    // number of live variant indexes (dense + copy-on-write)
    private int flatSize;       // high-water variant index (+1) physically held by the flat arrays
    private int variantCapacity;

    private final Deque<Integer> freeRows = new ArrayDeque<>();

    // shared copy-on-write bookkeeping (parentage, structural marks, master gate)
    private final VariantCowState cowState;

    // sparse bands of the copy-on-write variants, indexed by variant; see TerminalVariantStore
    private volatile CowBand[] cowBands = new CowBand[0];

    // The diverged rows of one copy-on-write variant, all columns column-major: d[col * rowStride + row].
    private static final class CowBand {
        final BitSet materialized;
        final double[] doubles;
        final int[] ints;
        final boolean[] booleans;

        CowBand(int rowStride, int nDouble, int nInt, int nBoolean) {
            this.materialized = new BitSet(rowStride);
            this.doubles = new double[nDouble * rowStride];
            this.ints = new int[nInt * rowStride];
            this.booleans = new boolean[nBoolean * rowStride];
        }

        CowBand(CowBand from, int oldStride, int newStride, int nDouble, int nInt, int nBoolean, int rowCount) {
            this.materialized = new BitSet(newStride);
            this.materialized.or(from.materialized);
            this.doubles = new double[nDouble * newStride];
            this.ints = new int[nInt * newStride];
            this.booleans = new boolean[nBoolean * newStride];
            for (int c = 0; c < nDouble; c++) {
                System.arraycopy(from.doubles, c * oldStride, this.doubles, c * newStride, rowCount);
            }
            for (int c = 0; c < nInt; c++) {
                System.arraycopy(from.ints, c * oldStride, this.ints, c * newStride, rowCount);
            }
            for (int c = 0; c < nBoolean; c++) {
                System.arraycopy(from.booleans, c * oldStride, this.booleans, c * newStride, rowCount);
            }
        }
    }

    NumericVariantStore(int variantArraySize, double[] doubleDefaults, int[] intDefaults, boolean[] booleanDefaults,
                        VariantCowState cowState) {
        this.cowState = cowState;
        this.nDouble = doubleDefaults.length;
        this.nInt = intDefaults.length;
        this.nBoolean = booleanDefaults.length;
        this.doubleDefaults = doubleDefaults.clone();
        this.intDefaults = intDefaults.clone();
        this.booleanDefaults = booleanDefaults.clone();
        this.rowStride = DEFAULT_ROW_CAPACITY;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.flatSize = variantArraySize;
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
            clearRowInCowBands(row);
        } else {
            if (rowCount == rowStride) {
                growRowStride();
            }
            row = rowCount++;
        }
        double[] dd = doubles;
        int[] id = ints;
        boolean[] bd = booleans;
        for (int v = 0; v < flatSize; v++) {
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
        for (int v = 0; v < flatSize; v++) {
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
        if (!cowState.isActive()) {
            return doubles[doubleIndex(variant, col, row)]; // FAST PATH: no copy-on-write variant exists
        }
        int v = resolve(variant, row);
        CowBand band = bandOf(v);
        return band == null ? doubles[doubleIndex(v, col, row)] : band.doubles[col * rowStride + row];
    }

    public double setDouble(int variant, int col, int row, double value) {
        if (!cowState.isActive()) { // FAST PATH
            double[] data = doubles;
            int i = doubleIndex(variant, col, row);
            double old = data[i];
            data[i] = value;
            return old;
        }
        freezeInheritors(variant, row);
        if (cowState.isCow(variant)) {
            CowBand band = materializeRow(variant, row);
            int i = col * rowStride + row;
            double old = band.doubles[i];
            band.doubles[i] = value;
            return old;
        }
        double[] data = doubles;
        int i = doubleIndex(variant, col, row);
        double old = data[i];
        data[i] = value;
        return old;
    }

    public int getInt(int variant, int col, int row) {
        if (!cowState.isActive()) {
            return ints[intIndex(variant, col, row)]; // FAST PATH
        }
        int v = resolve(variant, row);
        CowBand band = bandOf(v);
        return band == null ? ints[intIndex(v, col, row)] : band.ints[col * rowStride + row];
    }

    public int setInt(int variant, int col, int row, int value) {
        if (!cowState.isActive()) { // FAST PATH
            int[] data = ints;
            int i = intIndex(variant, col, row);
            int old = data[i];
            data[i] = value;
            return old;
        }
        freezeInheritors(variant, row);
        if (cowState.isCow(variant)) {
            CowBand band = materializeRow(variant, row);
            int i = col * rowStride + row;
            int old = band.ints[i];
            band.ints[i] = value;
            return old;
        }
        int[] data = ints;
        int i = intIndex(variant, col, row);
        int old = data[i];
        data[i] = value;
        return old;
    }

    /** Set an int column of a row to the same value in every live variant band. */
    public void fillInt(int col, int row, int value) {
        int[] data = ints;
        for (int v = 0; v < flatSize; v++) {
            data[intIndex(v, col, row)] = value;
        }
        if (cowState.isActive()) {
            // every variant gets the value: also the copy-on-write bands that diverged the row (the others
            // resolve to a dense band, already filled). Same-value everywhere, so no freeze is needed.
            CowBand[] bands = cowBands;
            for (CowBand band : bands) {
                if (band != null && band.materialized.get(row)) {
                    band.ints[col * rowStride + row] = value;
                }
            }
            cowBands = bands;
        }
    }

    /** Set a boolean column of a row to the same value in every live variant band. */
    public void fillBoolean(int col, int row, boolean value) {
        boolean[] data = booleans;
        for (int v = 0; v < flatSize; v++) {
            data[booleanIndex(v, col, row)] = value;
        }
        if (cowState.isActive()) {
            CowBand[] bands = cowBands;
            for (CowBand band : bands) {
                if (band != null && band.materialized.get(row)) {
                    band.booleans[col * rowStride + row] = value;
                }
            }
            cowBands = bands;
        }
    }

    public boolean getBoolean(int variant, int col, int row) {
        if (!cowState.isActive()) {
            return booleans[booleanIndex(variant, col, row)]; // FAST PATH
        }
        int v = resolve(variant, row);
        CowBand band = bandOf(v);
        return band == null ? booleans[booleanIndex(v, col, row)] : band.booleans[col * rowStride + row];
    }

    public boolean setBoolean(int variant, int col, int row, boolean value) {
        if (!cowState.isActive()) { // FAST PATH
            boolean[] data = booleans;
            int i = booleanIndex(variant, col, row);
            boolean old = data[i];
            data[i] = value;
            return old;
        }
        freezeInheritors(variant, row);
        if (cowState.isCow(variant)) {
            CowBand band = materializeRow(variant, row);
            int i = col * rowStride + row;
            boolean old = band.booleans[i];
            band.booleans[i] = value;
            return old;
        }
        boolean[] data = booleans;
        int i = booleanIndex(variant, col, row);
        boolean old = data[i];
        data[i] = value;
        return old;
    }

    // --- copy-on-write machinery (only reached while the gate is active); see TerminalVariantStore ---

    private int resolve(int variant, int row) {
        int v = variant;
        while (cowState.isCow(v)) {
            CowBand band = bandOf(v);
            if (band != null && band.materialized.get(row)) {
                return v;
            }
            v = cowState.getParent(v);
        }
        return v;
    }

    private CowBand bandOf(int variant) {
        CowBand[] bands = cowBands;
        return variant >= 0 && variant < bands.length ? bands[variant] : null;
    }

    private void freezeInheritors(int variant, int row) {
        for (int child : cowState.getCowChildren(variant)) {
            CowBand band = bandOf(child);
            if (band == null || !band.materialized.get(row)) {
                materializeRow(child, row);
            }
        }
    }

    private CowBand materializeRow(int variant, int row) {
        CowBand band = ensureBand(variant);
        if (!band.materialized.get(row)) {
            copyResolvedRow(cowState.getParent(variant), row, band);
            band.materialized.set(row);
            publishCowBands();
        }
        return band;
    }

    // Copy the resolved value of every column of a row (resolved from `from` upwards) into a copy-on-write band.
    private void copyResolvedRow(int from, int row, CowBand band) {
        int src = resolve(from, row);
        CowBand srcBand = bandOf(src);
        if (srcBand == null) {
            for (int c = 0; c < nDouble; c++) {
                band.doubles[c * rowStride + row] = doubles[doubleIndex(src, c, row)];
            }
            for (int c = 0; c < nInt; c++) {
                band.ints[c * rowStride + row] = ints[intIndex(src, c, row)];
            }
            for (int c = 0; c < nBoolean; c++) {
                band.booleans[c * rowStride + row] = booleans[booleanIndex(src, c, row)];
            }
        } else {
            for (int c = 0; c < nDouble; c++) {
                band.doubles[c * rowStride + row] = srcBand.doubles[c * rowStride + row];
            }
            for (int c = 0; c < nInt; c++) {
                band.ints[c * rowStride + row] = srcBand.ints[c * rowStride + row];
            }
            for (int c = 0; c < nBoolean; c++) {
                band.booleans[c * rowStride + row] = srcBand.booleans[c * rowStride + row];
            }
        }
    }

    private CowBand ensureBand(int variant) {
        CowBand[] bands = cowBands;
        if (variant >= bands.length) {
            // only possible on the main thread (structural operations size the table before workers run)
            bands = Arrays.copyOf(bands, Math.max(variant + 1, variantSize));
        }
        CowBand band = bands[variant];
        if (band == null) {
            band = new CowBand(rowStride, nDouble, nInt, nBoolean);
            bands[variant] = band;
            cowBands = bands;
        }
        return band;
    }

    private void publishCowBands() {
        CowBand[] bands = cowBands;
        cowBands = bands;
    }

    private void clearRowInCowBands(int row) {
        CowBand[] bands = cowBands;
        for (CowBand band : bands) {
            if (band != null) {
                band.materialized.clear(row);
            }
        }
        cowBands = bands;
    }

    /** Number of rows the given variant has diverged (materialised) in its copy-on-write band. Test hook. */
    int cowRowsMaterialized(int variant) {
        CowBand band = bandOf(variant);
        return band == null ? 0 : band.materialized.cardinality();
    }

    // --- structural changes, driven once per operation by NetworkImpl (main thread only) ---

    @Override
    public void extend(int number, int sourceIndex) {
        int newSize = variantSize + number;
        ensureVariantCapacity(newSize);
        int dBlock = nDouble * rowStride;
        int iBlock = nInt * rowStride;
        int bBlock = nBoolean * rowStride;
        double[] dd = doubles;
        int[] id = ints;
        boolean[] bd = booleans;
        if (!cowState.isActive() || !cowState.isCow(sourceIndex)) {
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
        } else {
            // the source is copy-on-write: an eager (STATE_ONLY) clone of it copies its resolved band
            int first = variantSize;
            copyResolvedBandToFlat(sourceIndex, first);
            for (int i = 1; i < number; i++) {
                int dst = variantSize + i;
                if (dBlock > 0) {
                    System.arraycopy(dd, first * dBlock, dd, dst * dBlock, dBlock);
                }
                if (iBlock > 0) {
                    System.arraycopy(id, first * iBlock, id, dst * iBlock, iBlock);
                }
                if (bBlock > 0) {
                    System.arraycopy(bd, first * bBlock, bd, dst * bBlock, bBlock);
                }
            }
        }
        doubles = dd;
        ints = id;
        booleans = bd;
        variantSize = newSize;
        flatSize = Math.max(flatSize, newSize);
    }

    @Override
    public void extendStructural(int number, int sourceIndex) {
        // O(1): the new copy-on-write variants own no rows and inherit through the parentage
        variantSize += number;
        CowBand[] bands = cowBands;
        if (bands.length < variantSize) {
            cowBands = Arrays.copyOf(bands, variantSize);
        }
    }

    @Override
    public void reduce(int number) {
        variantSize -= number;
        flatSize = Math.min(flatSize, variantSize);
        CowBand[] bands = cowBands;
        boolean changed = false;
        for (int v = variantSize; v < bands.length; v++) {
            if (bands[v] != null) {
                bands[v] = null;
                changed = true;
            }
        }
        if (changed) {
            cowBands = bands;
        }
    }

    @Override
    public void delete(int index) {
        // the flat band is left in place (overwritten if the index is recycled by allocate()); a
        // copy-on-write band is dropped so its diverged rows can be garbage collected
        dropCowBand(index);
    }

    @Override
    public void allocate(int[] indexes, int sourceIndex) {
        int required = flatSize;
        for (int index : indexes) {
            required = Math.max(required, index + 1);
        }
        ensureVariantCapacity(required);
        int dBlock = nDouble * rowStride;
        int iBlock = nInt * rowStride;
        int bBlock = nBoolean * rowStride;
        double[] dd = doubles;
        int[] id = ints;
        boolean[] bd = booleans;
        if (!cowState.isActive() || !cowState.isCow(sourceIndex)) {
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
        } else {
            for (int index : indexes) {
                copyResolvedBandToFlat(sourceIndex, index);
            }
        }
        doubles = dd;
        ints = id;
        booleans = bd;
        // the target becomes (or stays) dense: it owns its flat band, any copy-on-write past is dropped
        for (int index : indexes) {
            dropCowBand(index);
        }
        flatSize = required;
    }

    @Override
    public void allocateStructural(int[] indexes, int sourceIndex) {
        // O(1) per band: recycled/overwritten indexes become fresh copy-on-write variants owning no rows
        CowBand[] bands = cowBands;
        int required = variantSize;
        for (int index : indexes) {
            required = Math.max(required, index + 1);
        }
        if (bands.length < required) {
            bands = Arrays.copyOf(bands, required);
        }
        for (int index : indexes) {
            bands[index] = null;
        }
        cowBands = bands;
    }

    @Override
    public void materializeInheritors(int variantIndex) {
        for (int child : cowState.getCowChildren(variantIndex)) {
            CowBand band = ensureBand(child);
            for (int row = 0; row < rowCount; row++) {
                if (!band.materialized.get(row)) {
                    copyResolvedRow(cowState.getParent(child), row, band);
                    band.materialized.set(row);
                }
            }
        }
        publishCowBands();
    }

    // Copy the resolved band of a copy-on-write source, row by row, into a flat destination band.
    private void copyResolvedBandToFlat(int sourceIndex, int dst) {
        double[] dd = doubles;
        int[] id = ints;
        boolean[] bd = booleans;
        for (int row = 0; row < rowCount; row++) {
            int src = resolve(sourceIndex, row);
            CowBand srcBand = bandOf(src);
            if (srcBand == null) {
                for (int c = 0; c < nDouble; c++) {
                    dd[doubleIndex(dst, c, row)] = dd[doubleIndex(src, c, row)];
                }
                for (int c = 0; c < nInt; c++) {
                    id[intIndex(dst, c, row)] = id[intIndex(src, c, row)];
                }
                for (int c = 0; c < nBoolean; c++) {
                    bd[booleanIndex(dst, c, row)] = bd[booleanIndex(src, c, row)];
                }
            } else {
                for (int c = 0; c < nDouble; c++) {
                    dd[doubleIndex(dst, c, row)] = srcBand.doubles[c * rowStride + row];
                }
                for (int c = 0; c < nInt; c++) {
                    id[intIndex(dst, c, row)] = srcBand.ints[c * rowStride + row];
                }
                for (int c = 0; c < nBoolean; c++) {
                    bd[booleanIndex(dst, c, row)] = srcBand.booleans[c * rowStride + row];
                }
            }
        }
    }

    private void dropCowBand(int index) {
        CowBand[] bands = cowBands;
        if (index < bands.length && bands[index] != null) {
            bands[index] = null;
            cowBands = bands;
        }
    }

    // Move each live variant band (flat and copy-on-write) to a wider row stride.
    private void growRowStride() {
        int newStride = rowStride * 2;
        double[] nd = new double[variantCapacity * nDouble * newStride];
        int[] ni = new int[variantCapacity * nInt * newStride];
        boolean[] nb = new boolean[variantCapacity * nBoolean * newStride];
        for (int v = 0; v < flatSize; v++) {
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
        CowBand[] bands = cowBands;
        boolean changed = false;
        for (int v = 0; v < bands.length; v++) {
            if (bands[v] != null) {
                bands[v] = new CowBand(bands[v], rowStride, newStride, nDouble, nInt, nBoolean, rowCount);
                changed = true;
            }
        }
        if (changed) {
            cowBands = bands;
        }
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

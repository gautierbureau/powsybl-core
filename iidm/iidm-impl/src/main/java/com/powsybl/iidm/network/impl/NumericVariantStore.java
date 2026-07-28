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
 * {@code doubles[v][row >>> SHIFT][col * CHUNK + slot]}. Per-column default values initialise new rows and
 * grown capacity, so behaviour matches the former per-object {@code TDoubleArrayList}/{@code TIntArrayList}/
 * {@code TBooleanArrayList} defaults.</p>
 *
 * <p>Cloned variants are stored copy-on-write, exactly as in
 * {@link TerminalVariantStore}: {@link #extend} copies nothing (O(1)); reads resolve through the
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

    // Rows live in fixed-size chunks: value(v, col, row) = doubles[v][row >>> SHIFT][col * CHUNK + slot].
    // Growing either axis appends to a directory of references and never moves a chunk, so a reader holding
    // one is never invalidated -- see SwitchVariantStore for the rationale and the measurement.
    private static final int CHUNK = 128;
    private static final int SHIFT = Integer.numberOfTrailingZeros(CHUNK);
    private static final int MASK = CHUNK - 1;

    private volatile double[][][] doubles;
    private volatile int[][][] ints;
    private volatile boolean[][][] booleans;

    // Geometry fields are not volatile, and no longer need to be for row growth: a row is addressed as
    // [v][row >>> SHIFT][col * CHUNK + slot] with CHUNK a constant, so adding rows only appends chunks and a
    // reader holding a chunk keeps reading valid data. Growing the *variant* axis still re-allocates the
    // per-variant directory, so it remains a main-thread operation under the VariantManager contract (and
    // is what preAllocateVariants reserves for).
    private int rowCount;
    private int variantSize;    // number of live variant indexes (dense + copy-on-write)
    private int flatSize;       // high-water variant index (+1) physically held by the flat arrays
    private int variantCapacity;

    private final Deque<Integer> freeRows = new ArrayDeque<>();

    // shared copy-on-write bookkeeping (parentage, structural marks, master gate)
    private final VariantCowState cowState;

    // sparse bands of the copy-on-write variants, indexed by variant; see TerminalVariantStore
    private volatile CowBand[] cowBands = new CowBand[0];

    // The diverged rows of one copy-on-write variant, chunked like the dense storage.
    private static final class CowBand {
        final BitSet materialized = new BitSet();
        private final int nDouble;
        private final int nInt;
        private final int nBoolean;
        volatile double[][] doubles = new double[0][];
        volatile int[][] ints = new int[0][];
        volatile boolean[][] booleans = new boolean[0][];

        CowBand(int nDouble, int nInt, int nBoolean) {
            this.nDouble = nDouble;
            this.nInt = nInt;
            this.nBoolean = nBoolean;
        }

        double getDouble(int col, int row) {
            return doubles[row >>> SHIFT][col * CHUNK + (row & MASK)];
        }

        void setDouble(int col, int row, double value) {
            doubles[row >>> SHIFT][col * CHUNK + (row & MASK)] = value;
        }

        int getInt(int col, int row) {
            return ints[row >>> SHIFT][col * CHUNK + (row & MASK)];
        }

        void setInt(int col, int row, int value) {
            ints[row >>> SHIFT][col * CHUNK + (row & MASK)] = value;
        }

        boolean getBoolean(int col, int row) {
            return booleans[row >>> SHIFT][col * CHUNK + (row & MASK)];
        }

        void setBoolean(int col, int row, boolean value) {
            booleans[row >>> SHIFT][col * CHUNK + (row & MASK)] = value;
        }

        void ensureRow(int row) {
            int needed = (row >>> SHIFT) + 1;
            if (doubles.length < needed) {
                doubles = growChunks(doubles, needed, nDouble);
                ints = growIntChunks(ints, needed, nInt);
                booleans = growBoolChunks(booleans, needed, nBoolean);
            }
        }
    }

    private static double[][] growChunks(double[][] chunks, int needed, int nCol) {
        double[][] grown = Arrays.copyOf(chunks, needed);
        for (int c = chunks.length; c < needed; c++) {
            grown[c] = new double[nCol * CHUNK];
        }
        return grown;
    }

    private static int[][] growIntChunks(int[][] chunks, int needed, int nCol) {
        int[][] grown = Arrays.copyOf(chunks, needed);
        for (int c = chunks.length; c < needed; c++) {
            grown[c] = new int[nCol * CHUNK];
        }
        return grown;
    }

    private static boolean[][] growBoolChunks(boolean[][] chunks, int needed, int nCol) {
        boolean[][] grown = Arrays.copyOf(chunks, needed);
        for (int c = chunks.length; c < needed; c++) {
            grown[c] = new boolean[nCol * CHUNK];
        }
        return grown;
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
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.flatSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.doubles = new double[variantCapacity][][];
        this.ints = new int[variantCapacity][][];
        this.booleans = new boolean[variantCapacity][][];
        for (int v = 0; v < variantCapacity; v++) {
            this.doubles[v] = new double[0][];
            this.ints[v] = new int[0][];
            this.booleans[v] = new boolean[0][];
        }
        // Pre-size the copy-on-write band table to the current variant count so it is never grown on a worker
        // thread. extend keeps this invariant on clone, but a store created lazily (a new columnar
        // type first used after structural variants already exist) would otherwise start empty and let
        // ensureBand run Arrays.copyOf concurrently from workers, racing and dropping diverged bands.
        this.cowBands = new CowBand[variantSize];
    }

    private static int slot(int col, int row) {
        return col * CHUNK + (row & MASK);
    }

    private double denseDouble(int variant, int col, int row) {
        return doubles[variant][row >>> SHIFT][slot(col, row)];
    }

    private int denseInt(int variant, int col, int row) {
        return ints[variant][row >>> SHIFT][slot(col, row)];
    }

    private boolean denseBoolean(int variant, int col, int row) {
        return booleans[variant][row >>> SHIFT][slot(col, row)];
    }

    private void setDenseDouble(int variant, int col, int row, double value) {
        doubles[variant][row >>> SHIFT][slot(col, row)] = value;
    }

    private void setDenseInt(int variant, int col, int row, int value) {
        ints[variant][row >>> SHIFT][slot(col, row)] = value;
    }

    private void setDenseBoolean(int variant, int col, int row, boolean value) {
        booleans[variant][row >>> SHIFT][slot(col, row)] = value;
    }

    /** Make {@code row} addressable in every dense band and every copy-on-write band. */
    private void ensureRow(int row) {
        int needed = (row >>> SHIFT) + 1;
        double[][][] dd = doubles;
        int[][][] id = ints;
        boolean[][][] bd = booleans;
        for (int v = 0; v < dd.length; v++) {
            if (dd[v].length < needed) {
                dd[v] = growChunks(dd[v], needed, nDouble);
                id[v] = growIntChunks(id[v], needed, nInt);
                bd[v] = growBoolChunks(bd[v], needed, nBoolean);
            }
        }
        CowBand[] bands = cowBands;
        for (CowBand band : bands) {
            if (band != null) {
                band.ensureRow(row);
            }
        }
        cowBands = bands;
    }

    /** Allocate a row for a new object, initialised to the column defaults in every live variant band. */
    public int allocateRow() {
        int row;
        if (!freeRows.isEmpty()) {
            row = freeRows.pop();
            clearRowInCowBands(row);
        } else {
            row = rowCount++;
            ensureRow(row);
        }
        for (int v = 0; v < flatSize; v++) {
            for (int c = 0; c < nDouble; c++) {
                setDenseDouble(v, c, row, doubleDefaults[c]);
            }
            for (int c = 0; c < nInt; c++) {
                setDenseInt(v, c, row, intDefaults[c]);
            }
            for (int c = 0; c < nBoolean; c++) {
                setDenseBoolean(v, c, row, booleanDefaults[c]);
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
        for (int v = 0; v < flatSize; v++) {
            for (int c = 0; c < nDouble; c++) {
                setDenseDouble(v, c, row, doubleInit[c]);
            }
            for (int c = 0; c < nInt; c++) {
                setDenseInt(v, c, row, intInit[c]);
            }
            for (int c = 0; c < nBoolean; c++) {
                setDenseBoolean(v, c, row, booleanInit[c]);
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
            return denseDouble(variant, col, row); // FAST PATH: no copy-on-write variant exists
        }
        int v = resolve(variant, row);
        CowBand band = bandOf(v);
        return band == null ? denseDouble(v, col, row) : band.getDouble(col, row);
    }

    public double setDouble(int variant, int col, int row, double value) {
        if (!cowState.isActive()) { // FAST PATH
            double old = denseDouble(variant, col, row);
            setDenseDouble(variant, col, row, value);
            return old;
        }
        freezeInheritors(variant, row);
        if (cowState.isCow(variant)) {
            CowBand band = materializeRow(variant, row);
            double old = band.getDouble(col, row);
            band.setDouble(col, row, value);
            return old;
        }
        double old = denseDouble(variant, col, row);
        setDenseDouble(variant, col, row, value);
        return old;
    }

    public int getInt(int variant, int col, int row) {
        if (!cowState.isActive()) {
            return denseInt(variant, col, row); // FAST PATH
        }
        int v = resolve(variant, row);
        CowBand band = bandOf(v);
        return band == null ? denseInt(v, col, row) : band.getInt(col, row);
    }

    public int setInt(int variant, int col, int row, int value) {
        if (!cowState.isActive()) { // FAST PATH
            int old = denseInt(variant, col, row);
            setDenseInt(variant, col, row, value);
            return old;
        }
        freezeInheritors(variant, row);
        if (cowState.isCow(variant)) {
            CowBand band = materializeRow(variant, row);
            int old = band.getInt(col, row);
            band.setInt(col, row, value);
            return old;
        }
        int old = denseInt(variant, col, row);
        setDenseInt(variant, col, row, value);
        return old;
    }

    /** Set an int column of a row to the same value in every live variant band. */
    public void fillInt(int col, int row, int value) {
        for (int v = 0; v < flatSize; v++) {
            setDenseInt(v, col, row, value);
        }
        if (cowState.isActive()) {
            // every variant gets the value: also the copy-on-write bands that diverged the row (the others
            // resolve to a dense band, already filled). Same-value everywhere, so no freeze is needed.
            CowBand[] bands = cowBands;
            for (CowBand band : bands) {
                if (band != null && band.materialized.get(row)) {
                    band.setInt(col, row, value);
                }
            }
            cowBands = bands;
        }
    }

    /** Set a boolean column of a row to the same value in every live variant band. */
    public void fillBoolean(int col, int row, boolean value) {
        for (int v = 0; v < flatSize; v++) {
            setDenseBoolean(v, col, row, value);
        }
        if (cowState.isActive()) {
            CowBand[] bands = cowBands;
            for (CowBand band : bands) {
                if (band != null && band.materialized.get(row)) {
                    band.setBoolean(col, row, value);
                }
            }
            cowBands = bands;
        }
    }

    public boolean getBoolean(int variant, int col, int row) {
        if (!cowState.isActive()) {
            return denseBoolean(variant, col, row); // FAST PATH
        }
        int v = resolve(variant, row);
        CowBand band = bandOf(v);
        return band == null ? denseBoolean(v, col, row) : band.getBoolean(col, row);
    }

    public boolean setBoolean(int variant, int col, int row, boolean value) {
        if (!cowState.isActive()) { // FAST PATH
            boolean old = denseBoolean(variant, col, row);
            setDenseBoolean(variant, col, row, value);
            return old;
        }
        freezeInheritors(variant, row);
        if (cowState.isCow(variant)) {
            CowBand band = materializeRow(variant, row);
            boolean old = band.getBoolean(col, row);
            band.setBoolean(col, row, value);
            return old;
        }
        boolean old = denseBoolean(variant, col, row);
        setDenseBoolean(variant, col, row, value);
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
        cowState.checkWritable(variant);
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
                band.setDouble(c, row, denseDouble(src, c, row));
            }
            for (int c = 0; c < nInt; c++) {
                band.setInt(c, row, denseInt(src, c, row));
            }
            for (int c = 0; c < nBoolean; c++) {
                band.setBoolean(c, row, denseBoolean(src, c, row));
            }
        } else {
            for (int c = 0; c < nDouble; c++) {
                band.setDouble(c, row, srcBand.getDouble(c, row));
            }
            for (int c = 0; c < nInt; c++) {
                band.setInt(c, row, srcBand.getInt(c, row));
            }
            for (int c = 0; c < nBoolean; c++) {
                band.setBoolean(c, row, srcBand.getBoolean(c, row));
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
            band = new CowBand(nDouble, nInt, nBoolean);
            for (int row = 0; row < rowCount; row++) {
                band.ensureRow(row);
            }
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
        for (int row = 0; row < rowCount; row++) {
            int src = resolve(sourceIndex, row);
            CowBand srcBand = bandOf(src);
            if (srcBand == null) {
                for (int c = 0; c < nDouble; c++) {
                    setDenseDouble(dst, c, row, denseDouble(src, c, row));
                }
                for (int c = 0; c < nInt; c++) {
                    setDenseInt(dst, c, row, denseInt(src, c, row));
                }
                for (int c = 0; c < nBoolean; c++) {
                    setDenseBoolean(dst, c, row, denseBoolean(src, c, row));
                }
            } else {
                for (int c = 0; c < nDouble; c++) {
                    setDenseDouble(dst, c, row, srcBand.getDouble(c, row));
                }
                for (int c = 0; c < nInt; c++) {
                    setDenseInt(dst, c, row, srcBand.getInt(c, row));
                }
                for (int c = 0; c < nBoolean; c++) {
                    setDenseBoolean(dst, c, row, srcBand.getBoolean(c, row));
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

    private void ensureVariantCapacity(int required) {
        if (required <= variantCapacity) {
            return;
        }
        int newCapacity = Math.max(required, variantCapacity * 2);
        double[][][] dd = Arrays.copyOf(doubles, newCapacity);
        int[][][] id = Arrays.copyOf(ints, newCapacity);
        boolean[][][] bd = Arrays.copyOf(booleans, newCapacity);
        int needed = rowCount == 0 ? 0 : ((rowCount - 1) >>> SHIFT) + 1;
        for (int v = variantCapacity; v < newCapacity; v++) {
            dd[v] = growChunks(new double[0][], needed, nDouble);
            id[v] = growIntChunks(new int[0][], needed, nInt);
            bd[v] = growBoolChunks(new boolean[0][], needed, nBoolean);
        }
        doubles = dd;
        ints = id;
        booleans = bd;
        variantCapacity = newCapacity;
    }
}

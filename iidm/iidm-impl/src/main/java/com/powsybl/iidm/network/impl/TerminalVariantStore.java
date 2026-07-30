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
 * Columnar (structure-of-arrays) store for the variant-dependent {@code p} and {@code q} of every
 * terminal in a network.
 *
 * <p>Instead of each terminal owning its own {@code TDoubleArrayList} indexed by variant
 * (array-of-structures), all terminals share two
 * network-level, <em>variant-major</em>, <em>flat</em> arrays: the {@code p} of terminal {@code row} in
 * variant {@code v} is {@code p[v][row >>> SHIFT][row & MASK]}. A variant clone is then a couple of
 * {@link System#arraycopy} calls over contiguous bands <em>within the existing array</em> — no per-clone
 * allocation — instead of one method call per terminal. A flat pre-grown array (not {@code double[][]})
 * is essential: allocating a fresh band per clone reintroduces the very garbage the layout is meant to
 * avoid and turns the win into a regression.</p>
 *
 * <h2>Copy-on-write (cloned) variants</h2>
 *
 * <p>A cloned variant is stored copy-on-write:
 * {@link #extend} copies <b>nothing</b> (O(1)) —
 * the new variant owns no rows and resolves reads through the clone parentage ({@link VariantCowState})
 * to its nearest ancestor that holds the row. Rows diverge one by one: writing a copy-on-write variant
 * first materialises the touched row into its sparse band ({@code CowBand}), and — to preserve the clone
 * snapshot — writing any variant first freezes the touched row into the copy-on-write children that still
 * inherit it. The dense initial variant keeps the eager
 * behaviour exactly; while no copy-on-write variant exists (the {@link VariantCowState#isActive()} gate,
 * i.e. every network today) every read and write takes the plain dense path.</p>
 *
 * <p>Thread-safety follows the {@link com.powsybl.iidm.network.VariantManager} contract exactly as the
 * previous per-terminal layout did: structural changes ({@link #extend}, {@link #reduce}, {@link #delete},
 * {@link #allocate}, {@link #allocateRow}) happen on the main thread only, never concurrently with reads
 * or writes; pre-allocated variants may be read/written concurrently, each thread on its own variant index
 * (its own band). The backing arrays — including the copy-on-write bands — are published through
 * {@code volatile} references so worker threads observe fully constructed state. Copy-on-write adds one
 * caveat: a write to a variant touches the bands of its copy-on-write children (the freeze), so
 * concurrently <em>writing a variant</em> and <em>accessing one of its copy-on-write descendants</em> from
 * different threads requires external synchronization; independent variants (e.g. one structural variant
 * per worker thread, the shared parent left unwritten) stay safely concurrent as before.</p>
 *
 * <p>Row lifecycle is monotonic: {@link #allocateRow} hands out an ever-increasing row
 * index and rows are not recycled when a terminal is removed (a full implementation would keep a free list).
 * This is correct — dead rows are simply never read.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class TerminalVariantStore implements VariantColumnStore {

    // Rows live in fixed-size chunks: value(variant v, row r) = p[v][r >>> SHIFT][r & MASK]. Growing either
    // axis appends to a directory of references and never moves a chunk, so a reader holding one is never
    // invalidated -- see SwitchVariantStore for the rationale and the measurement.
    private static final int CHUNK = 128;
    private static final int SHIFT = Integer.numberOfTrailingZeros(CHUNK);
    private static final int MASK = CHUNK - 1;

    private volatile double[][][] p;
    private volatile double[][][] q;
    private int rowCount;       // high-water mark of allocated rows
    private int variantSize;    // number of live variant indexes (dense + copy-on-write)
    private int flatSize;       // high-water variant index (+1) physically held by the flat arrays
    private int variantCapacity;

    // rows freed by removed terminals, available for reuse (avoids leaking a row per removed terminal)
    private final Deque<Integer> freeRows = new ArrayDeque<>();

    // shared copy-on-write bookkeeping (parentage, structural marks, master gate)
    private final VariantCowState cowState;

    // sparse bands of the copy-on-write variants, indexed by variant; null = owns no rows. Sized on the
    // main thread (structural operations) so worker-thread writes never resize it, published volatile.
    private volatile CowBand[] cowBands = new CowBand[0];

    // The diverged rows of one copy-on-write variant: values are only read for rows whose materialized bit
    // is set, and the BitSet is pre-sized to the row stride so setting a bit never reallocates its words.
    private static final class CowBand {
        final BitSet materialized = new BitSet();
        volatile double[][] p = new double[0][];
        volatile double[][] q = new double[0][];

        double p(int row) {
            return p[row >>> SHIFT][row & MASK];
        }

        double q(int row) {
            return q[row >>> SHIFT][row & MASK];
        }

        void setP(int row, double value) {
            p[row >>> SHIFT][row & MASK] = value;
        }

        void setQ(int row, double value) {
            q[row >>> SHIFT][row & MASK] = value;
        }

        void ensureRow(int row) {
            p = growChunks(p, row);
            q = growChunks(q, row);
        }
    }

    /** Append NaN-filled chunks until {@code row} is addressable; existing chunks are never moved. */
    private static double[][] growChunks(double[][] chunks, int row) {
        int needed = (row >>> SHIFT) + 1;
        if (chunks.length >= needed) {
            return chunks;
        }
        double[][] grown = Arrays.copyOf(chunks, needed);
        for (int c = chunks.length; c < needed; c++) {
            grown[c] = newData(CHUNK);
        }
        return grown;
    }

    TerminalVariantStore(int variantArraySize, VariantCowState cowState) {
        this.cowState = cowState;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.flatSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.p = newBands(variantCapacity);
        this.q = newBands(variantCapacity);
        // Pre-size the copy-on-write band table to the current variant count so it is never grown on a worker
        // thread (see NumericVariantStore for the full rationale).
        this.cowBands = new CowBand[variantSize];
    }

    private static double[][][] newBands(int variants) {
        double[][][] bands = new double[variants][][];
        for (int v = 0; v < variants; v++) {
            bands[v] = new double[0][];
        }
        return bands;
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
    synchronized int allocateRow() {
        if (!freeRows.isEmpty()) {
            int row = freeRows.pop();
            resetRow(row);
            return row;
        }
        ensureRow(rowCount);
        return rowCount++;
    }

    /**
     * Release the row of a removed terminal so it can be reused. The caller must never read/write the row again.
     */
    synchronized void freeRow(int row) {
        freeRows.push(row);
    }

    // Reset a (reused) row to NaN in every live dense band, and un-materialise it in every copy-on-write
    // band (it then resolves to the dense NaN through the parentage).
    private void resetRow(int row) {
        double[][][] pd = p;
        double[][][] qd = q;
        for (int v = 0; v < flatSize; v++) {
            pd[v][row >>> SHIFT][row & MASK] = Double.NaN;
            qd[v][row >>> SHIFT][row & MASK] = Double.NaN;
        }
        CowBand[] bands = cowBands;
        for (CowBand band : bands) {
            if (band != null) {
                band.materialized.clear(row);
            }
        }
        cowBands = bands;
    }

    /**
     * Allocate a fresh row and initialise it with the given single-variant {@code p}/{@code q}. Used when a
     * terminal is moved between networks (merge/detach), which the API only allows on single-variant networks.
     */
    synchronized int importRow(double pValue, double qValue) {
        int row = allocateRow();
        p[0][row >>> SHIFT][row & MASK] = pValue; // variant 0 (the only variant in a merge/detach)
        q[0][row >>> SHIFT][row & MASK] = qValue;
        return row;
    }

    /** Make {@code row} addressable in every dense band and every copy-on-write band. */
    private void ensureRow(int row) {
        double[][][] pd = p;
        double[][][] qd = q;
        for (int v = 0; v < pd.length; v++) {
            pd[v] = growChunks(pd[v], row);
            qd[v] = growChunks(qd[v], row);
        }
        p = pd;
        q = qd;
        CowBand[] bands = cowBands;
        for (CowBand band : bands) {
            if (band != null) {
                band.ensureRow(row);
            }
        }
        cowBands = bands;
    }

    private void ensureVariantCapacity(int required) {
        if (required <= variantCapacity) {
            return;
        }
        int newCapacity = Math.max(required, variantCapacity * 2);
        double[][][] pd = Arrays.copyOf(p, newCapacity);
        double[][][] qd = Arrays.copyOf(q, newCapacity);
        // new bands start empty and are grown to the current row count; chunks are NaN-filled, so a row only
        // populated later (a terminal added after a clone) reads NaN rather than 0.0
        for (int v = variantCapacity; v < newCapacity; v++) {
            pd[v] = new double[0][];
            qd[v] = new double[0][];
            for (int row = 0; row < rowCount; row++) {
                pd[v] = growChunks(pd[v], row);
                qd[v] = growChunks(qd[v], row);
            }
        }
        p = pd;
        q = qd;
        variantCapacity = newCapacity;
    }

    double getP(int variantIndex, int row) {
        if (!cowState.isActive()) {
            return p[variantIndex][row >>> SHIFT][row & MASK]; // FAST PATH: no copy-on-write variant exists
        }
        int v = resolve(variantIndex, row);
        CowBand band = bandOf(v);
        return band == null ? p[v][row >>> SHIFT][row & MASK] : band.p(row);
    }

    double setP(int variantIndex, int row, double value) {
        if (!cowState.isActive()) { // FAST PATH
            double[] chunk = p[variantIndex][row >>> SHIFT];
            double old = chunk[row & MASK];
            chunk[row & MASK] = value;
            return old;
        }
        freezeInheritors(variantIndex, row);
        if (cowState.isCow(variantIndex)) {
            CowBand band = materializeRow(variantIndex, row);
            double old = band.p(row);
            band.setP(row, value);
            return old;
        }
        double[] chunk = p[variantIndex][row >>> SHIFT];
        double old = chunk[row & MASK];
        chunk[row & MASK] = value;
        return old;
    }

    double getQ(int variantIndex, int row) {
        if (!cowState.isActive()) {
            return q[variantIndex][row >>> SHIFT][row & MASK]; // FAST PATH
        }
        int v = resolve(variantIndex, row);
        CowBand band = bandOf(v);
        return band == null ? q[v][row >>> SHIFT][row & MASK] : band.q(row);
    }

    double setQ(int variantIndex, int row, double value) {
        if (!cowState.isActive()) { // FAST PATH
            double[] chunk = q[variantIndex][row >>> SHIFT];
            double old = chunk[row & MASK];
            chunk[row & MASK] = value;
            return old;
        }
        freezeInheritors(variantIndex, row);
        if (cowState.isCow(variantIndex)) {
            CowBand band = materializeRow(variantIndex, row);
            double old = band.q(row);
            band.setQ(row, value);
            return old;
        }
        double[] chunk = q[variantIndex][row >>> SHIFT];
        double old = chunk[row & MASK];
        chunk[row & MASK] = value;
        return old;
    }

    // --- copy-on-write machinery (only reached while the gate is active) ---

    // Resolve the variant whose band actually holds the row: walk the parentage from the given variant
    // until a dense variant (always a flat band) or a copy-on-write variant that materialised the row.
    private int resolve(int variantIndex, int row) {
        int v = variantIndex;
        while (cowState.isCow(v)) {
            CowBand band = bandOf(v);
            if (band != null && band.materialized.get(row)) {
                return v;
            }
            v = cowState.getParent(v);
        }
        return v;
    }

    private CowBand bandOf(int variantIndex) {
        CowBand[] bands = cowBands;
        return variantIndex >= 0 && variantIndex < bands.length ? bands[variantIndex] : null;
    }

    // Snapshot preservation: before a row of this variant is overwritten, freeze its current resolved value
    // into every copy-on-write child that still inherits it. One level is enough — freezing a child cuts the
    // resolution path of that child's whole subtree.
    private void freezeInheritors(int variantIndex, int row) {
        cowState.checkWritable(variantIndex);
        for (int child : cowState.getCowChildren(variantIndex)) {
            CowBand band = bandOf(child);
            if (band == null || !band.materialized.get(row)) {
                materializeRow(child, row);
            }
        }
    }

    // Copy the resolved value of a row into the copy-on-write band of the given variant (if not already
    // diverged) and republish the bands so concurrent readers observe it through the volatile read.
    private CowBand materializeRow(int variantIndex, int row) {
        CowBand band = ensureBand(variantIndex);
        if (!band.materialized.get(row)) {
            int src = resolve(cowState.getParent(variantIndex), row);
            CowBand srcBand = bandOf(src);
            if (srcBand == null) {
                band.setP(row, p[src][row >>> SHIFT][row & MASK]);
                band.setQ(row, q[src][row >>> SHIFT][row & MASK]);
            } else {
                band.setP(row, srcBand.p(row));
                band.setQ(row, srcBand.q(row));
            }
            band.materialized.set(row);
            publishCowBands();
        }
        return band;
    }

    private CowBand ensureBand(int variantIndex) {
        CowBand[] bands = cowBands;
        if (variantIndex >= bands.length) {
            // only possible on the main thread (structural operations size the table before workers run)
            bands = Arrays.copyOf(bands, Math.max(variantIndex + 1, variantSize));
        }
        CowBand band = bands[variantIndex];
        if (band == null) {
            band = new CowBand();
            for (int row = 0; row < rowCount; row++) {
                band.ensureRow(row);
            }
            bands[variantIndex] = band;
            cowBands = bands;
        }
        return band;
    }

    // Re-publish the copy-on-write bands after an in-place mutation, so a reader thread synchronising on
    // the volatile read observes the frozen values (see the thread-safety note in the class javadoc).
    private void publishCowBands() {
        CowBand[] bands = cowBands;
        cowBands = bands;
    }

    /** Number of rows the given variant has diverged (materialised) in its copy-on-write band. Test hook. */
    int cowRowsMaterialized(int variantIndex) {
        CowBand band = bandOf(variantIndex);
        return band == null ? 0 : band.materialized.cardinality();
    }

    // --- structural changes, driven once per operation by NetworkImpl (main thread only) ---

    @Override
    // synchronized on the store, like allocateRow: growing the variant dimension replaces each band's
    // chunk spine, so it must not interleave with a row allocation appending chunks to one of them
    public synchronized void extend(int number, int sourceIndex) {
        // O(1): the new copy-on-write variants own no rows and inherit through the parentage. Pre-size the
        // band table on the main thread so worker-thread writes never resize it.
        variantSize += number;
        CowBand[] bands = cowBands;
        if (bands.length < variantSize) {
            cowBands = Arrays.copyOf(bands, variantSize);
        }
    }

    @Override
    public void reduce(int number) {
        variantSize -= number; // bands past the new size are simply no longer read
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
        // Match the previous per-terminal behaviour ("nothing to do"): the flat band at this now-unused
        // index is left in place and will be overwritten if the index is later recycled by allocate().
        // A copy-on-write band, however, is dropped so its diverged rows can be garbage collected.
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
                    int src = resolve(cowState.getParent(child), row);
                    CowBand srcBand = bandOf(src);
                    band.setP(row, srcBand == null ? p[src][row >>> SHIFT][row & MASK] : srcBand.p(row));
                    band.setQ(row, srcBand == null ? q[src][row >>> SHIFT][row & MASK] : srcBand.q(row));
                    band.materialized.set(row);
                }
            }
        }
        publishCowBands();
    }

    private void dropCowBand(int index) {
        CowBand[] bands = cowBands;
        if (index < bands.length && bands[index] != null) {
            bands[index] = null;
            cowBands = bands;
        }
    }
}
